package net.posdaca.oiia.core.files

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import net.posdaca.oiia.core.HoI4ResourceRoots
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * Single entry for preview modules to discover and read resource files.
 *
 * Order of preference:
 * 1. IntelliJ VFS under configured resource roots
 * 2. NIO only as last-resort fallback (outside indexed trees / missing VFS)
 */
internal object ResourceFiles {
    private const val LIST_TTL_MS = 1000L
    private const val MAX_LIST_CACHE = 32
    private val listLock = Any()
    private val listCache = mutableMapOf<ListCacheKey, CachedList>()

    fun resourceRoots(
        project: Project,
        projectFirst: Boolean = true,
        gameFirst: Boolean = false,
    ): List<Path> = HoI4ResourceRoots.resourceRoots(project, projectFirst, gameFirst)

    fun normalizedKey(path: Path): String = HoI4ResourceRoots.normalizedKey(path)

    fun normalizedKey(path: String): String = path.replace('\\', '/').trim().lowercase()

    fun toVirtualFile(path: Path): VirtualFile? {
        if (!isIdeApplicationAvailable()) return null
        val absolute = path.toAbsolutePath().normalize().toString()
        return runCatching {
            LocalFileSystem.getInstance().findFileByPath(absolute)
                ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(absolute)
        }.getOrNull()
    }

    fun toVirtualFile(path: String): VirtualFile? {
        if (!isIdeApplicationAvailable()) return null
        val normalized = path.replace('\\', '/').trim()
        if (normalized.isEmpty()) return null
        return runCatching {
            LocalFileSystem.getInstance().findFileByPath(normalized)
                ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(normalized)
        }.getOrNull() ?: runCatching { toVirtualFile(Path.of(normalized)) }.getOrNull()
    }

    fun toPath(file: VirtualFile): Path = Path.of(file.path)

    fun isRegularFile(path: Path): Boolean = path.isRegularFile()

    fun isDirectory(path: Path): Boolean = path.isDirectory()

    fun exists(path: Path): Boolean = path.exists()

    fun fileStamp(path: Path?): Long {
        if (path == null || !path.exists()) return 0L
        return try {
            Files.getLastModifiedTime(path).toMillis() xor
                path.fileSize() xor
                normalizedKey(path).hashCode().toLong()
        } catch (_: Exception) {
            0L
        }
    }

    fun fileStamp(file: VirtualFile?): Long {
        if (file == null || !file.isValid) return 0L
        return file.timeStamp xor file.length xor normalizedKey(file.path).hashCode().toLong()
    }

    /** Resolve a game-relative path (e.g. `common/national_focus/foo.txt`) against the given roots. */
    fun findFirst(roots: List<Path>, gameRelativePath: String): Path? {
        val relative = normalizeRelative(gameRelativePath) ?: return null
        for (root in roots) {
            val candidate = root.resolve(relative).normalize()
            if (candidate.isRegularFile()) return candidate.toAbsolutePath().normalize()
        }
        return null
    }

    /**
     * List files under one or more relative directories for each resource root.
     * Results are de-duplicated by normalized absolute path and cached briefly.
     */
    fun listFiles(
        project: Project,
        relativeDirectories: Collection<String>,
        extensions: Set<String> = emptySet(),
        maxDepth: Int = 8,
        projectFirst: Boolean = true,
        gameFirst: Boolean = false,
    ): List<Path> {
        val roots = resourceRoots(project, projectFirst, gameFirst)
        return listFiles(roots, relativeDirectories, extensions, maxDepth)
    }

    fun listFiles(
        roots: List<Path>,
        relativeDirectories: Collection<String>,
        extensions: Set<String> = emptySet(),
        maxDepth: Int = 8,
    ): List<Path> {
        val dirs = relativeDirectories.mapNotNull(::normalizeRelative).ifEmpty { listOf("") }
        val ext = extensions.map { it.lowercase().let { e -> if (e.startsWith(".")) e else ".$e" } }.toSet()
        val key = ListCacheKey(
            rootsKey = roots.joinToString("|") { normalizedKey(it) },
            dirsKey = dirs.joinToString("|"),
            extensionsKey = ext.sorted().joinToString("|"),
            maxDepth = maxDepth,
        )
        val now = System.currentTimeMillis()
        synchronized(listLock) {
            val cached = listCache[key]
            if (cached != null && now - cached.createdAtMs <= LIST_TTL_MS) return cached.files
        }

        val files = linkedMapOf<String, Path>()
        for (root in roots) {
            for (dir in dirs) {
                collectUnderRoot(root, dir, ext, maxDepth, files)
            }
        }
        val result = files.values.toList()
        synchronized(listLock) {
            listCache[key] = CachedList(result, System.currentTimeMillis())
            if (listCache.size > MAX_LIST_CACHE) {
                val oldest = listCache.minByOrNull { it.value.createdAtMs }?.key
                if (oldest != null) listCache.remove(oldest)
            }
        }
        return result
    }

    fun readText(path: Path): String? {
        toVirtualFile(path)?.let { vf -> readText(vf)?.let { return it } }
        return runCatching {
            Files.readString(path).removePrefix("\uFEFF")
        }.getOrNull()
    }

    fun readText(path: String): String? {
        toVirtualFile(path)?.let { vf -> readText(vf)?.let { return it } }
        return runCatching { readText(Path.of(path)) }.getOrNull()
    }

    fun readText(file: VirtualFile): String? {
        if (!file.isValid || file.isDirectory) return null
        return runCatching {
            VfsUtilCore.loadText(file).removePrefix("\uFEFF")
        }.getOrNull() ?: runCatching {
            String(file.contentsToByteArray(), StandardCharsets.UTF_8).removePrefix("\uFEFF")
        }.getOrNull()
    }

    fun readBytes(path: Path): ByteArray? {
        toVirtualFile(path)?.let { vf ->
            if (vf.isValid && !vf.isDirectory) {
                runCatching { return vf.contentsToByteArray() }
            }
        }
        return runCatching { Files.readAllBytes(path) }.getOrNull()
    }

    fun rootScores(roots: List<Path>): List<Pair<String, Int>> {
        return roots.mapIndexed { index, root -> normalizedKey(root) to roots.size - index }
    }

    fun rootScore(path: Path, roots: List<Path>): Int {
        val absolute = runCatching { path.toAbsolutePath().normalize() }.getOrElse { return 0 }
        return roots.mapIndexed { index, root ->
            val rootAbsolute = runCatching { root.toAbsolutePath().normalize() }.getOrNull()
            val matches = rootAbsolute != null && (absolute == rootAbsolute || absolute.startsWith(rootAbsolute))
            matches to (roots.size - index)
        }.firstOrNull { it.first }?.second ?: 0
    }


    private fun isIdeApplicationAvailable(): Boolean {
        return runCatching {
            com.intellij.openapi.application.ApplicationManager.getApplication() != null
        }.getOrDefault(false)
    }

    private fun collectUnderRoot(
        root: Path,
        relativeDir: String,
        extensions: Set<String>,
        maxDepth: Int,
        out: MutableMap<String, Path>,
    ) {
        val base = if (relativeDir.isEmpty()) root else root.resolve(relativeDir)
        val baseVf = toVirtualFile(base)
        if (baseVf != null && baseVf.isValid) {
            collectVirtual(baseVf, extensions, maxDepth, 0, out)
            return
        }
        if (!base.isDirectory() && !base.isRegularFile()) return
        if (base.isRegularFile()) {
            if (matchesExtension(base.fileName.toString(), extensions)) {
                val absolute = base.toAbsolutePath().normalize()
                out.putIfAbsent(normalizedKey(absolute), absolute)
            }
            return
        }
        runCatching {
            Files.walk(base, maxDepth.coerceAtLeast(1)).use { stream ->
                stream
                    .filter { it.isRegularFile() }
                    .filter { matchesExtension(it.fileName.toString(), extensions) }
                    .forEach {
                        val absolute = it.toAbsolutePath().normalize()
                        out.putIfAbsent(normalizedKey(absolute), absolute)
                    }
            }
        }
    }

    private fun collectVirtual(
        dir: VirtualFile,
        extensions: Set<String>,
        maxDepth: Int,
        depth: Int,
        out: MutableMap<String, Path>,
    ) {
        if (!dir.isValid) return
        if (!dir.isDirectory) {
            if (matchesExtension(dir.name, extensions)) {
                val path = toPath(dir).toAbsolutePath().normalize()
                out.putIfAbsent(normalizedKey(path), path)
            }
            return
        }
        if (depth > maxDepth) return
        for (child in dir.children.orEmpty()) {
            if (!child.isValid) continue
            if (child.isDirectory) {
                collectVirtual(child, extensions, maxDepth, depth + 1, out)
            } else if (matchesExtension(child.name, extensions)) {
                val path = toPath(child).toAbsolutePath().normalize()
                out.putIfAbsent(normalizedKey(path), path)
            }
        }
    }

    private fun matchesExtension(name: String, extensions: Set<String>): Boolean {
        if (extensions.isEmpty()) return true
        val lower = name.lowercase()
        return extensions.any { lower.endsWith(it) }
    }    private fun normalizeRelative(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return path.replace('\\', '/').trim().trimStart('/')
    }

    private data class ListCacheKey(
        val rootsKey: String,
        val dirsKey: String,
        val extensionsKey: String,
        val maxDepth: Int,
    )

    private data class CachedList(
        val files: List<Path>,
        val createdAtMs: Long,
    )
}
