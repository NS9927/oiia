package net.posdaca.oiia.core

import com.intellij.openapi.project.Project
import icu.windea.pls.lang.settings.ChronicleProfilesSettings
import icu.windea.pls.lang.settings.ParadoxGameSettingsState
import icu.windea.pls.lang.settings.ParadoxModDescriptorSettingsState
import icu.windea.pls.lang.settings.ParadoxModSettingsState
import icu.windea.pls.model.ParadoxGameType
import java.io.File
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.isDirectory

internal object HoI4ResourceRoots {
    private const val CACHE_TTL_MS = 1000L
    private val cacheLock = Any()
    private val resourceRootsCache = mutableMapOf<ResourceRootCacheKey, CachedRoots>()

    fun plsRoots(gameFirst: Boolean = true): List<Path> {
        val state = ChronicleProfilesSettings.getInstance().state
        val modSettings = state.modSettings.values.filter { it.isHoi4Mod() }
        val gameRoots = gameRoots(modSettings)
        val modRoots = allModRoots(modSettings)
        return if (gameFirst) {
            distinctNormalized(gameRoots + modRoots)
        } else {
            distinctNormalized(modRoots + gameRoots)
        }
    }

    fun resourceRoots(
        project: Project,
        projectFirst: Boolean = true,
        gameFirst: Boolean = true
    ): List<Path> {
        val projectRoot = directoryPath(project.basePath)
        val projectKey = projectRoot?.let { normalizedKey(it) } ?: project.basePath.orEmpty()
        val cacheKey = ResourceRootCacheKey(projectKey, projectFirst, gameFirst)
        cachedResourceRoots(cacheKey)?.let { return it }
        val state = ChronicleProfilesSettings.getInstance().state
        val modRootPlan = orderedModRootPlan(state.modSettings.values, projectRoot)
        val gameRoots = gameRoots(modRootPlan.settings)
        val modRoots = modRootPlan.roots
        val plsRoots = if (gameFirst) gameRoots + modRoots else modRoots + gameRoots
        val roots = distinctNormalized(
            if (projectFirst) listOfNotNull(projectRoot) + plsRoots else plsRoots + listOfNotNull(projectRoot)
        )
        cacheResourceRoots(cacheKey, roots)
        return roots
    }

    fun currentModLoadOrder(project: Project): List<HoI4ModLoadOrderEntry> {
        val projectRoot = directoryPath(project.basePath)
        val state = ChronicleProfilesSettings.getInstance().state
        val modRootPlan = orderedModRootPlan(state.modSettings.values, projectRoot)
        val descriptorSettingsByDirectory = state.modDescriptorSettings.values
            .filter { it.isHoi4Mod() }
            .mapNotNull { settings ->
                directoryPath(settings.modDirectory)?.let { normalizedKey(it) to settings }
            }
            .toMap()

        return modRootPlan.roots.map { root ->
            val descriptorSettings = descriptorSettingsByDirectory[normalizedKey(root)]
            HoI4ModLoadOrderEntry(root, descriptorSettings?.remoteId?.takeIf { it.isNotBlank() })
        }
    }

    fun normalizedKey(path: Path): String = path.toAbsolutePath().normalize().toString().lowercase()

    private fun directoryPath(value: String?): Path? {
        if (value.isNullOrBlank()) return null
        val path = try {
            Path.of(expandPath(value))
        } catch (_: InvalidPathException) {
            return null
        }
        return if (path.isDirectory()) path.toAbsolutePath().normalize() else null
    }

    private fun orderedModRootPlan(
        modSettings: Collection<ParadoxModSettingsState>,
        priorityRoot: Path?
    ): ModRootPlan {
        val byDirectory = modSettings
            .filter { it.isHoi4Mod() }
            .mapNotNull { settings ->
                directoryPath(settings.modDirectory)?.let { normalizedKey(it) to settings }
            }
            .toMap()
        val visited = mutableSetOf<String>()
        val processedSettings = mutableSetOf<String>()
        val result = mutableListOf<Path>()
        val resultSettings = mutableListOf<ParadoxModSettingsState>()
        val prioritySettings = priorityRoot?.let { findPrioritySettings(byDirectory, it) }
            ?: return ModRootPlan(emptyList(), emptyList())

        fun addModWithDependencies(settings: ParadoxModSettingsState) {
            val modDirectory = directoryPath(settings.modDirectory)
            val settingsKey = modDirectory?.let { normalizedKey(it) }
            addModDirectory(modDirectory, visited, result)
            if (settingsKey != null && !processedSettings.add(settingsKey)) return
            resultSettings.add(settings)
            for (dependency in settings.modDependencies) {
                if (!dependency.enabled) continue
                val dependencyPath = directoryPath(dependency.modDirectory)
                addModDirectory(dependencyPath, visited, result)
                dependencyPath?.let { byDirectory[normalizedKey(it)] }?.let(::addModWithDependencies)
            }
        }

        addModWithDependencies(prioritySettings)
        return ModRootPlan(result, resultSettings)
    }

    private fun allModRoots(modSettings: Collection<ParadoxModSettingsState>): List<Path> {
        val visited = mutableSetOf<String>()
        val result = mutableListOf<Path>()
        for (settings in modSettings) {
            addModDirectory(directoryPath(settings.modDirectory), visited, result)
            for (dependency in settings.modDependencies) {
                if (dependency.enabled) addModDirectory(directoryPath(dependency.modDirectory), visited, result)
            }
        }
        return result
    }

    private fun gameRoots(modSettings: Collection<ParadoxModSettingsState>): List<Path> {
        val state = ChronicleProfilesSettings.getInstance().state
        val settingsGameRoots = modSettings.flatMap {
            listOfNotNull(directoryPath(it.finalGameDirectory), directoryPath(it.gameDirectory))
        }
        return distinctNormalized(
            settingsGameRoots +
                    state.gameSettings.values.filter { it.isHoi4Game() }.mapNotNull { directoryPath(it.gameDirectory) }
        )
    }

    private fun addModDirectory(path: Path?, visited: MutableSet<String>, result: MutableList<Path>) {
        if (path == null) return
        val key = normalizedKey(path)
        if (visited.add(key)) result.add(path)
    }

    private fun distinctNormalized(paths: List<Path>): List<Path> {
        return paths.distinctBy { normalizedKey(it) }
    }

    private fun cachedResourceRoots(key: ResourceRootCacheKey): List<Path>? {
        val now = System.currentTimeMillis()
        return synchronized(cacheLock) {
            val cached = resourceRootsCache[key] ?: return@synchronized null
            if (now - cached.createdAtMs <= CACHE_TTL_MS) cached.roots else null
        }
    }

    private fun cacheResourceRoots(key: ResourceRootCacheKey, roots: List<Path>) {
        synchronized(cacheLock) {
            resourceRootsCache[key] = CachedRoots(roots, System.currentTimeMillis())
            if (resourceRootsCache.size > 16) {
                val oldestKey = resourceRootsCache.minByOrNull { it.value.createdAtMs }?.key
                if (oldestKey != null) resourceRootsCache.remove(oldestKey)
            }
        }
    }

    private fun findPrioritySettings(
        byDirectory: Map<String, ParadoxModSettingsState>,
        priorityRoot: Path
    ): ParadoxModSettingsState? {
        val priorityKey = normalizedKey(priorityRoot)
        byDirectory[priorityKey]?.let { return it }
        return byDirectory.entries
            .mapNotNull { (modRootKey, settings) ->
                val score = when {
                    isSameOrChild(priorityKey, modRootKey) -> modRootKey.length
                    isSameOrChild(modRootKey, priorityKey) -> priorityKey.length - (modRootKey.length - priorityKey.length)
                    else -> null
                }
                score?.let { it to settings }
            }
            .maxByOrNull { it.first }
            ?.second
    }

    private fun isSameOrChild(pathKey: String, rootKey: String): Boolean {
        return pathKey == rootKey ||
                pathKey.startsWith(rootKey.trimEnd('\\', '/') + File.separatorChar)
    }

    private fun expandPath(value: String): String {
        var result = value.trim()
        val userHome = System.getProperty("user.home")
        if (!userHome.isNullOrBlank()) {
            result = result.replace("\$USER_HOME\$", userHome)
            if (result == "~") {
                result = userHome
            } else if (result.startsWith("~/") || result.startsWith("~\\")) {
                result = userHome + result.substring(1)
            }
        }
        return result
    }

    private fun ParadoxGameSettingsState.isHoi4Game(): Boolean {
        return gameType == ParadoxGameType.Hoi4 || finalGameType == ParadoxGameType.Hoi4
    }

    private fun ParadoxModSettingsState.isHoi4Mod(): Boolean {
        return gameType == ParadoxGameType.Hoi4 || finalGameType == ParadoxGameType.Hoi4
    }

    private fun ParadoxModDescriptorSettingsState.isHoi4Mod(): Boolean {
        return gameType == ParadoxGameType.Hoi4 || finalGameType == ParadoxGameType.Hoi4
    }

    private data class ModRootPlan(
        val roots: List<Path>,
        val settings: List<ParadoxModSettingsState>
    )

    private data class ResourceRootCacheKey(
        val projectKey: String,
        val projectFirst: Boolean,
        val gameFirst: Boolean
    )

    private data class CachedRoots(
        val roots: List<Path>,
        val createdAtMs: Long
    )
}

internal data class HoI4ModLoadOrderEntry(
    val modDirectory: Path,
    val remoteId: String?,
)
