package net.posdaca.oiia.shadow

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.intellij.openapi.project.Project
import net.posdaca.oiia.core.files.HoI4ModLoadOrderEntry
import net.posdaca.oiia.core.files.HoI4ResourceRoots
import java.io.IOException
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlin.io.path.exists

internal object ShadowPlaysetSync {
    private const val NOT_FOUND_MESSAGE = "Refresh Shadow's Mod list once, then sync again."
    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create()

    fun sync(project: Project): ShadowPlaysetSyncResult {
        val loadOrder = HoI4ResourceRoots.currentModLoadOrder(project)
        if (loadOrder.isEmpty()) {
            throw ShadowPlaysetSyncException("No HOI4 mod settings were found for this project in PLS.")
        }

        val workspaceDirectory = defaultWorkspaceDirectory()
        val indexPath = workspaceDirectory.resolve("mods").resolve("index.json")
        if (!indexPath.exists()) {
            throw ShadowPlaysetSyncException("Shadow mod index was not found at $indexPath. $NOT_FOUND_MESSAGE")
        }

        val index = readIndex(indexPath)
        val matchResult = matchMods(loadOrder.map { it.toRequest() }, index.mods)
        if (matchResult.matchedModIds.isEmpty()) {
            throw ShadowPlaysetSyncException("No PLS mod entries matched Shadow's mod index. $NOT_FOUND_MESSAGE")
        }

        val playsetId = "oiia:${stableProjectId(project)}"
        val playsetName = "Oiia - ${project.name}"
        val playsetPath = writePlayset(
            workspaceDirectory = workspaceDirectory,
            playset = ShadowPlaysetDocument(
                id = playsetId,
                name = playsetName,
                modIds = matchResult.matchedModIds,
                enabledModIds = matchResult.matchedModIds,
            )
        )

        return ShadowPlaysetSyncResult(
            playsetId = playsetId,
            playsetPath = playsetPath,
            playsetName = playsetName,
            matchedModCount = matchResult.matchedModIds.size,
            missingMods = matchResult.missingMods,
        )
    }

    fun defaultWorkspaceDirectory(): Path {
        val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: Path.of(System.getProperty("user.home"), "AppData", "Roaming")
        return appData.resolve("Posdaca").resolve("Hoi4Workspace")
    }

    fun readIndex(indexPath: Path): ShadowModIndex {
        return Files.newBufferedReader(indexPath, StandardCharsets.UTF_8).use { reader ->
            gson.fromJson(reader, ShadowModIndex::class.java) ?: ShadowModIndex()
        }
    }

    fun matchMods(
        requestedMods: List<ShadowRequestedMod>,
        indexMods: List<ShadowModIndexEntry>,
    ): ShadowModMatchResult {
        val byIdentityKey = buildIdentityLookup(indexMods)
        val byRemoteId = indexMods
            .asSequence()
            .filter { it.id.isNotBlank() && it.remoteFileId.isNotBlank() }
            .groupBy { canonicalIdentityKey(it.remoteFileId) }
            .mapValues { it.value.first() }

        val byContentPath = indexMods
            .asSequence()
            .filter { it.id.isNotBlank() && it.contentPath.isNotBlank() }
            .mapNotNull { mod -> normalizeComparablePath(mod.contentPath)?.let { it to mod } }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.first() }

        val matchedIds = mutableListOf<String>()
        val missingMods = mutableListOf<ShadowRequestedMod>()
        val seenIds = mutableSetOf<String>()

        for (requestedMod in requestedMods) {
            val matchedMod = matchByRemoteId(requestedMod.remoteId, byIdentityKey, byRemoteId)
                ?: matchByContentPath(requestedMod.modDirectory, byIdentityKey, byContentPath)

            if (matchedMod == null) {
                missingMods.add(requestedMod)
                continue
            }

            if (seenIds.add(matchedMod.id.lowercase(Locale.ROOT))) {
                matchedIds.add(matchedMod.id)
            }
        }

        return ShadowModMatchResult(matchedIds, missingMods)
    }

    private fun buildIdentityLookup(indexMods: List<ShadowModIndexEntry>): Map<String, ShadowModIndexEntry> {
        val result = linkedMapOf<String, ShadowModIndexEntry>()

        fun add(key: String?, mod: ShadowModIndexEntry) {
            if (mod.id.isBlank() || key.isNullOrBlank()) return
            result.putIfAbsent(canonicalIdentityKey(key), mod)
        }

        for (mod in indexMods) {
            add(mod.id, mod)
            if (mod.remoteFileId.isNotBlank()) add("steam:${mod.remoteFileId.trim()}", mod)
            for (identityKey in mod.identityKeys) {
                add(identityKey, mod)
            }
        }

        return result
    }

    private fun matchByRemoteId(
        remoteId: String?,
        byIdentityKey: Map<String, ShadowModIndexEntry>,
        byRemoteId: Map<String, ShadowModIndexEntry>,
    ): ShadowModIndexEntry? {
        val cleanRemoteId = remoteId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return byIdentityKey[canonicalIdentityKey("steam:$cleanRemoteId")]
            ?: byRemoteId[canonicalIdentityKey(cleanRemoteId)]
            ?: byIdentityKey[canonicalIdentityKey(cleanRemoteId)]
    }

    private fun matchByContentPath(
        modDirectory: Path,
        byIdentityKey: Map<String, ShadowModIndexEntry>,
        byContentPath: Map<String, ShadowModIndexEntry>,
    ): ShadowModIndexEntry? {
        return normalizeComparablePath(modDirectory.toString())?.let { byContentPath[it] }
            ?: localContentIdentityKey(modDirectory)?.let { byIdentityKey[canonicalIdentityKey(it)] }
    }

    fun writePlayset(workspaceDirectory: Path, playset: ShadowPlaysetDocument): Path {
        val playsetsDirectory = workspaceDirectory.resolve("playsets")
        Files.createDirectories(playsetsDirectory)
        val playsetPath = playsetsDirectory.resolve("${sanitizeFileName(playset.id)}.json")
        val temporaryPath = playsetPath.resolveSibling("${playsetPath.fileName}.${UUID.randomUUID().toString().replace("-", "")}.tmp")

        Files.newBufferedWriter(
            temporaryPath,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { writer ->
            gson.toJson(playset, writer)
            writer.appendLine()
        }

        try {
            Files.move(
                temporaryPath,
                playsetPath,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: IOException) {
            Files.move(temporaryPath, playsetPath, StandardCopyOption.REPLACE_EXISTING)
        }

        return playsetPath
    }

    fun sanitizeFileName(value: String): String {
        val sanitized = buildString(value.length) {
            for (character in value) {
                append(if (isInvalidFileNameCharacter(character)) '_' else character)
            }
        }

        val safeName = sanitized.takeUnless { it.isBlank() || it == "." || it == ".." } ?: sha256(value)
        return if (safeName.length <= 120) {
            safeName
        } else {
            "${safeName.take(80)}_${sha256(value).take(12)}"
        }
    }

    internal fun localContentIdentityKey(path: Path): String? {
        val normalizedPath = normalizeShadowIdentityPath(path.toString()) ?: return null
        return "local-content:${sha256(normalizedPath)}"
    }

    private fun HoI4ModLoadOrderEntry.toRequest(): ShadowRequestedMod {
        return ShadowRequestedMod(modDirectory, remoteId ?: readRemoteFileId(modDirectory))
    }

    private fun readRemoteFileId(modDirectory: Path): String? {
        val descriptor = modDirectory.resolve("descriptor.mod")
        if (!descriptor.exists()) return null

        return runCatching {
            Files.readAllLines(descriptor, StandardCharsets.UTF_8)
                .asSequence()
                .map { it.trim() }
                .firstNotNullOfOrNull { line ->
                    val equalsIndex = line.indexOf('=')
                    if (equalsIndex <= 0) return@firstNotNullOfOrNull null
                    val key = line.substring(0, equalsIndex).trim()
                    if (!key.equals("remote_file_id", ignoreCase = true)) return@firstNotNullOfOrNull null
                    line.substring(equalsIndex + 1).trim().trim('"').takeIf { it.isNotBlank() }
                }
        }.getOrNull()
    }

    private fun stableProjectId(project: Project): String {
        val projectKey = project.basePath?.let { normalizeComparablePath(it) } ?: project.name
        return sha256(projectKey).take(16)
    }

    private fun normalizeComparablePath(value: String): String? {
        if (value.isBlank()) return null
        val expanded = value.trim()
            .replace("\$USER_HOME\$", System.getProperty("user.home"))
        return try {
            Path.of(expanded)
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace('\\', '/')
                .trimEnd('/')
                .lowercase(Locale.ROOT)
        } catch (_: InvalidPathException) {
            null
        }
    }

    private fun normalizeShadowIdentityPath(value: String): String? {
        if (value.isBlank()) return null
        val expanded = value.trim()
            .replace("\$USER_HOME\$", System.getProperty("user.home"))
        return try {
            Path.of(expanded)
                .toAbsolutePath()
                .normalize()
                .toString()
                .trimEnd('\\', '/')
                .lowercase(Locale.ROOT)
        } catch (_: InvalidPathException) {
            expanded
                .replace('/', File.separatorChar)
                .trim()
                .trimEnd('\\', '/')
                .lowercase(Locale.ROOT)
        }
    }

    private fun canonicalIdentityKey(value: String): String {
        return value.trim().lowercase(Locale.ROOT)
    }

    private fun isInvalidFileNameCharacter(character: Char): Boolean {
        return character.code < 32 || character in setOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

internal class ShadowPlaysetSyncException(message: String) : RuntimeException(message)

internal data class ShadowPlaysetSyncResult(
    val playsetId: String,
    val playsetPath: Path,
    val playsetName: String,
    val matchedModCount: Int,
    val missingMods: List<ShadowRequestedMod>,
)

internal data class ShadowModMatchResult(
    val matchedModIds: List<String>,
    val missingMods: List<ShadowRequestedMod>,
)

internal data class ShadowRequestedMod(
    val modDirectory: Path,
    val remoteId: String?,
)

internal class ShadowModIndex {
    var schemaVersion: String = ""
    var updatedAt: String = ""
    var mods: List<ShadowModIndexEntry> = emptyList()
}

internal class ShadowModIndexEntry {
    var id: String = ""
    var shadowId: String = ""
    var name: String = ""
    var source: String = ""
    var remoteFileId: String = ""
    var descriptorPath: String = ""
    var launcherPath: String = ""
    var contentPath: String = ""
    var version: String = ""
    var identityKeys: List<String> = emptyList()
}

internal data class ShadowPlaysetDocument(
    val id: String,
    val name: String,
    val modIds: List<String>,
    val enabledModIds: List<String>,
    val disabledDlcIds: List<String> = emptyList(),
    val source: String = "Oiia",
    val isExternal: Boolean = true,
    @SerializedName("can_edit")
    val canEdit: Boolean = false,
)
