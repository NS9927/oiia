package net.posdaca.oiia.core

internal class PrefixIconLookup(cache: Map<String, String>) {
    private data class IndexedPath(val index: Int, val path: String)

    private val exact = cache.entries
        .mapIndexed { index, entry -> entry.key to IndexedPath(index, entry.value) }
        .toMap()
    private val firstValueByPrefix = linkedMapOf<String, String>().apply {
        for ((key, path) in cache) {
            for (length in 1..key.length) {
                putIfAbsent(key.substring(0, length), path)
            }
        }
    }

    fun find(aliases: Iterable<String>): String? {
        for (alias in aliases) {
            exact[alias]?.path?.let { return it }

            firstValueByPrefix[alias]?.let { return it }

            var best: IndexedPath? = null
            for (length in 1 until alias.length) {
                val candidate = exact[alias.substring(0, length)] ?: continue
                if (best == null || candidate.index < best.index) best = candidate
            }
            best?.path?.let { return it }
        }
        return null
    }
}
