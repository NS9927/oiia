package net.posdaca.oiia.focus

import net.posdaca.oiia.core.ParadoxTextEntry
import net.posdaca.oiia.core.ParadoxTextParser
import net.posdaca.oiia.core.atomValue
import net.posdaca.oiia.core.blockEntries
import net.posdaca.oiia.core.entries
import net.posdaca.oiia.core.firstAtom
import net.posdaca.oiia.core.firstEntry
import net.posdaca.oiia.core.parseParadoxBoolean

/**
 * Fallback parser for focus tree files, built on the shared [ParadoxTextParser] token parser.
 *
 * Used when PLS PSI parsing is unavailable; mirrors the PSI path's field coverage
 * (id / country / default / focus / shared_focus blocks and references, and the focus
 * body fields the preview renders).
 */
internal object FocusTreeTextParser {

    data class Result(
        val trees: List<NationalFocusTreeData>,
        val standaloneSharedFocuses: List<FocusData>,
    )

    fun parse(filePath: String, content: String): Result {
        val trees = mutableListOf<NationalFocusTreeData>()
        val standaloneSharedFocuses = mutableListOf<FocusData>()
        for (entry in ParadoxTextParser.parse(content)) {
            when (entry.key.lowercase()) {
                "focus_tree" -> parseTree(filePath, entry)?.let { trees.add(it) }
                "shared_focus" -> parseFocus(filePath, entry, shared = true)?.let { standaloneSharedFocuses.add(it) }
            }
        }
        return Result(trees, standaloneSharedFocuses)
    }

    private fun parseTree(filePath: String, entry: ParadoxTextEntry): NationalFocusTreeData? {
        val body = entry.blockEntries()
        val id = body.firstAtom("id") ?: return null
        val country = body.firstEntry("country")?.let { country ->
            country.atomValue() ?: country.blockEntries().firstOrNull()?.key
        }
        val defaultFocus = body.firstAtom("default").parseParadoxBoolean() ?: false

        val focuses = mutableListOf<FocusData>()
        val sharedFocuses = mutableListOf<FocusData>()
        val sharedFocusReferences = mutableListOf<String>()
        for (field in body) {
            when (field.key.lowercase()) {
                "focus" -> parseFocus(filePath, field, shared = false)?.let { focuses.add(it) }
                "shared_focus" -> {
                    val inline = parseFocus(filePath, field, shared = true)
                    if (inline != null) {
                        sharedFocuses.add(inline)
                    } else {
                        field.atomValue()?.takeIf { it.isNotBlank() }?.let { sharedFocusReferences.add(it) }
                    }
                }
            }
        }

        return NationalFocusTreeData(
            id = id,
            country = country,
            focuses = focuses,
            sharedFocuses = sharedFocuses,
            sharedFocusReferences = sharedFocusReferences,
            defaultFocus = defaultFocus
        )
    }

    private fun parseFocus(filePath: String, entry: ParadoxTextEntry, shared: Boolean): FocusData? {
        val body = entry.blockEntries()
        val id = body.firstAtom("id") ?: return null
        val iconKey = body.firstAtom("icon") ?: iconBlockValue(body)
        val prerequisites = body.entries("prerequisite").flatMap { focusRefs(it) }
        val mutuallyExclusive = body.entries("mutually_exclusive").flatMap { focusRefs(it) }
        val aiWillDo = body
            .firstOrNull { it.key.startsWith("ai_will_da") || it.key.startsWith("ai_will_do") }
            ?.blockEntries()
            ?.firstAtom("base_factor")
            ?.toDoubleOrNull()

        return FocusData(
            id = id,
            iconKey = iconKey,
            text = body.firstAtom("text"),
            x = body.firstAtom("x")?.toDoubleOrNull() ?: 0.0,
            y = body.firstAtom("y")?.toDoubleOrNull() ?: 0.0,
            cost = body.firstAtom("cost")?.toDoubleOrNull() ?: 10.0,
            prerequisites = prerequisites,
            mutuallyExclusive = mutuallyExclusive,
            relativePositionId = body.firstAtom("relative_position_id"),
            aiWillDo = aiWillDo,
            completeTooltip = body.firstAtom("complete_tooltip"),
            prerequisitesText = if (prerequisites.isNotEmpty()) prerequisites.joinToString(", ") else null,
            sourceFilePath = filePath,
            sourceOffset = -1,
            sourceLine = entry.lineNumber,
            isSharedFocus = shared
        )
    }

    /** `prerequisite = { focus = a focus = b }` — one entry per referenced focus. */
    private fun focusRefs(entry: ParadoxTextEntry): List<String> {
        return entry.blockEntries().entries("focus").mapNotNull { it.atomValue() }
    }

    private fun iconBlockValue(body: List<ParadoxTextEntry>): String? {
        return body.firstEntry("icon")
            ?.blockEntries()
            ?.firstOrNull { it.key.equals("value", ignoreCase = true) }
            ?.atomValue()
    }
}
