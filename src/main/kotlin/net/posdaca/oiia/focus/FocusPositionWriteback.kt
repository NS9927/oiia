package net.posdaca.oiia.focus

/**
 * Document replacements for focus `x` / `y` write-back.
 *
 * Chronicle sometimes binds `x =` without the integer, leaving `5` as a sibling token. Replacing
 * an empty value then concatenates (`x = 5` -> `x = 55`). Always rewrite the number on the source
 * line instead of inserting at a PSI value range.
 */
internal object FocusPositionWriteback {
    data class Replacement(val start: Int, val end: Int, val text: String)

    fun axisNumberReplacement(
        line: String,
        lineStartOffset: Int,
        keyStartInLine: Int,
        key: String,
        value: Int
    ): Replacement? {
        if (keyStartInLine < 0 || keyStartInLine > line.length) return null
        val from = line.substring(keyStartInLine)
        val numbered = Regex("^${Regex.escape(key)}\\s*=\\s*(-?\\d+)").find(from)
        if (numbered != null) {
            val group = numbered.groups[1] ?: return null
            return replacement(lineStartOffset, keyStartInLine, group.range, value)
        }
        val assign = Regex("^${Regex.escape(key)}\\s*=").find(from) ?: return null
        val afterAssign = keyStartInLine + assign.range.last + 1
        val dangling = Regex("^\\s*(-?\\d+)").find(line.substring(afterAssign)) ?: return null
        val group = dangling.groups[1] ?: return null
        return replacement(lineStartOffset, afterAssign, group.range, value)
    }

    private fun replacement(lineStartOffset: Int, localBase: Int, numberRange: IntRange, value: Int): Replacement {
        val start = lineStartOffset + localBase + numberRange.first
        val end = lineStartOffset + localBase + numberRange.last + 1
        return Replacement(start, end, value.toString())
    }
}
