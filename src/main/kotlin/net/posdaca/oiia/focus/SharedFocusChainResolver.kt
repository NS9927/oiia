package net.posdaca.oiia.focus

/**
 * Expands HOI4 shared_focus references.
 * Referencing one shared focus loads that definition and every shared focus after it in the same file.
 */
internal object SharedFocusChainResolver {
    fun expand(
        referencedIds: List<String>,
        definitionsByFile: Map<String, List<FocusData>>,
        inlineSharedFocuses: List<FocusData> = emptyList()
    ): List<FocusData> {
        val result = linkedMapOf<String, FocusData>()
        for (focus in inlineSharedFocuses) {
            result.putIfAbsent(focus.id, focus.copy(isSharedFocus = true))
        }

        if (referencedIds.isEmpty() || definitionsByFile.isEmpty()) {
            return result.values.toList()
        }

        val indexById = linkedMapOf<String, Pair<String, Int>>()
        for ((filePath, focuses) in definitionsByFile) {
            focuses.forEachIndexed { index, focus ->
                indexById.putIfAbsent(focus.id, filePath to index)
            }
        }

        for (rawId in referencedIds) {
            val id = rawId.trim().trim('"')
            if (id.isBlank() || result.containsKey(id)) continue
            val location = indexById[id] ?: continue
            val (filePath, startIndex) = location
            val fileFocuses = definitionsByFile[filePath].orEmpty()
            for (index in startIndex until fileFocuses.size) {
                val focus = fileFocuses[index]
                result.putIfAbsent(focus.id, focus.copy(isSharedFocus = true))
            }
        }
        return result.values.toList()
    }
}
