package net.posdaca.oiia.core.files

import java.nio.file.Path

/**
 * Shared localisation file listing/parsing for preview modules.
 * File discovery goes through [ResourceFiles]; yml entry parsing stays here.
 */
internal object LocalisationFiles {
    private const val MAX_PARSED_CACHE_SIZE = 1024
    private val lock = Any()
    private val parsedFileCache = linkedMapOf<String, CachedParsedFile>()

    fun findFiles(roots: List<Path>, maxDepth: Int = 4): List<Path> {
        return ResourceFiles.listFiles(
            roots = roots,
            relativeDirectories = listOf("localisation", "localization"),
            extensions = setOf(".yml"),
            maxDepth = maxDepth,
        )
    }

    fun parseFile(path: Path): Map<String, String> {
        val normalizedPath = ResourceFiles.normalizedKey(path)
        val stamp = ResourceFiles.fileStamp(path)
        synchronized(lock) {
            val cached = parsedFileCache[normalizedPath]
            if (cached != null && cached.stamp == stamp) return cached.entries
        }

        val entries = runCatching {
            val text = ResourceFiles.readText(path) ?: return emptyMap()
            parseEntries(text)
        }.getOrDefault(emptyMap())

        synchronized(lock) {
            parsedFileCache[normalizedPath] = CachedParsedFile(stamp, entries)
            while (parsedFileCache.size > MAX_PARSED_CACHE_SIZE) {
                val firstKey = parsedFileCache.keys.firstOrNull() ?: break
                parsedFileCache.remove(firstKey)
            }
        }
        return entries
    }

    fun rootScores(roots: List<Path>): List<Pair<String, Int>> = ResourceFiles.rootScores(roots)

    fun rootScoreForRoots(path: Path, roots: List<Path>): Int = ResourceFiles.rootScore(path, roots)

    fun rootScore(path: Path, rootScores: List<Pair<String, Int>>): Int {
        val absolute = runCatching { path.toAbsolutePath().normalize() }.getOrNull() ?: return 0
        // Prefer path-aware scoring when callers still pass legacy rootScores pairs.
        val pathKey = ResourceFiles.normalizedKey(absolute).replace('\\', '/')
        return rootScores.firstOrNull { (rootKey, _) ->
            val normalizedRoot = rootKey.replace('\\', '/').trimEnd('/')
            pathKey == normalizedRoot || pathKey.startsWith("$normalizedRoot/")
        }?.second ?: 0
    }

    fun unescape(value: String): String {
        return value
            .replace("\\\"", "\"")
            .replace("\\n", " ")
            .replace("\\t", " ")
    }

    private fun parseEntries(content: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        LOCALISATION_REGEX.findAll(content).forEach { match ->
            result[match.groupValues[1]] = match.groupValues[2]
        }
        return result
    }

    private data class CachedParsedFile(
        val stamp: Long,
        val entries: Map<String, String>,
    )

    private val LOCALISATION_REGEX = Regex("""(?m)^\s*([^\s:#]+)\s*:\d*\s*"((?:\\.|[^"])*)"""")
}