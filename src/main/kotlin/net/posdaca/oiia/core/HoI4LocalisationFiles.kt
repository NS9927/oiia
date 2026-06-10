package net.posdaca.oiia.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile

internal object HoI4LocalisationFiles {
    private const val FILE_LIST_TTL_MS = 1000L
    private const val MAX_PARSED_CACHE_SIZE = 1024
    private val lock = Any()
    private val fileListCache = mutableMapOf<FileListKey, CachedFileList>()
    private val parsedFileCache = linkedMapOf<String, CachedParsedFile>()

    fun findFiles(roots: List<Path>, maxDepth: Int = 4): List<Path> {
        val key = FileListKey(rootsKey(roots), maxDepth)
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val cached = fileListCache[key]
            if (cached != null && now - cached.createdAtMs <= FILE_LIST_TTL_MS) return cached.files
        }

        val files = scanFiles(roots, maxDepth)
        synchronized(lock) {
            fileListCache[key] = CachedFileList(files, System.currentTimeMillis())
            if (fileListCache.size > 16) {
                val oldestKey = fileListCache.minByOrNull { it.value.createdAtMs }?.key
                if (oldestKey != null) fileListCache.remove(oldestKey)
            }
        }
        return files
    }

    fun parseFile(path: Path): Map<String, String> {
        val normalizedPath = HoI4ResourceRoots.normalizedKey(path)
        val stamp = fileStamp(path)
        synchronized(lock) {
            val cached = parsedFileCache[normalizedPath]
            if (cached != null && cached.stamp == stamp) return cached.entries
        }

        val entries = runCatching {
            parseEntries(Files.readString(path).removePrefix("\uFEFF"))
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

    fun rootScores(roots: List<Path>): List<Pair<String, Int>> {
        return roots.mapIndexed { index, root -> HoI4ResourceRoots.normalizedKey(root) to roots.size - index }
    }

    fun rootScoreForRoots(path: Path, roots: List<Path>): Int {
        return rootScore(path, rootScores(roots))
    }

    fun rootScore(path: Path, rootScores: List<Pair<String, Int>>): Int {
        val key = HoI4ResourceRoots.normalizedKey(path)
        return rootScores.firstOrNull { key.startsWith(it.first) }?.second ?: 0
    }

    fun unescape(value: String): String {
        return value
            .replace("\\\"", "\"")
            .replace("\\n", " ")
            .replace("\\t", " ")
    }

    private fun scanFiles(roots: List<Path>, maxDepth: Int): List<Path> {
        val files = mutableListOf<Path>()
        val seen = mutableSetOf<String>()
        for (root in roots) {
            for (locDir in listOf(root.resolve("localisation"), root.resolve("localization"))) {
                if (!Files.isDirectory(locDir)) continue
                runCatching {
                    Files.walk(locDir, maxDepth).use { stream ->
                        stream
                            .filter { it.isRegularFile() && it.fileName.toString().endsWith(".yml", ignoreCase = true) }
                            .forEach {
                                val path = it.toAbsolutePath().normalize()
                                if (seen.add(HoI4ResourceRoots.normalizedKey(path))) files.add(path)
                            }
                    }
                }
            }
        }
        return files
    }

    private fun parseEntries(content: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        LOCALISATION_REGEX.findAll(content).forEach { match ->
            result[match.groupValues[1]] = match.groupValues[2]
        }
        return result
    }

    private fun rootsKey(roots: List<Path>): String {
        return roots.joinToString("|") { HoI4ResourceRoots.normalizedKey(it) }
    }

    private fun fileStamp(path: Path): Long {
        if (!path.exists()) return 0L
        return try {
            Files.getLastModifiedTime(path).toMillis() xor path.fileSize() xor HoI4ResourceRoots.normalizedKey(path)
                .hashCode().toLong()
        } catch (_: Exception) {
            0L
        }
    }

    private data class FileListKey(
        val rootsKey: String,
        val maxDepth: Int
    )

    private data class CachedFileList(
        val files: List<Path>,
        val createdAtMs: Long
    )

    private data class CachedParsedFile(
        val stamp: Long,
        val entries: Map<String, String>
    )

    private val LOCALISATION_REGEX = Regex("""(?m)^\s*([^\s:#]+)\s*:\d*\s*"((?:\\.|[^"])*)"""")
}
