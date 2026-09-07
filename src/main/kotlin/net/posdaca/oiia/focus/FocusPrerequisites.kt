package net.posdaca.oiia.focus

import java.awt.Point

/**
 * HOI4 national-focus prerequisite rules, kept pure so the preview snapshot and tests can share
 * them without PSI or Swing.
 *
 * Wiki: members of one `prerequisite = { ... }` block are OR; separate blocks are AND. A focus
 * does not appear when any block has no member located in the same tree.
 */
internal object FocusPrerequisites {
    fun normalizeGroups(groups: List<List<String>>): List<List<String>> {
        return groups.map { group ->
            group.map(::cleanId).filter { it.isNotEmpty() }.distinct()
        }.filter { it.isNotEmpty() }
    }

    fun format(groups: List<List<String>>): String? {
        val normalized = normalizeGroups(groups)
        if (normalized.isEmpty()) return null
        if (normalized.size == 1) return formatGroup(normalized.first(), parenthesize = false)
        return normalized.joinToString(" AND ") { formatGroup(it, parenthesize = it.size > 1) }
    }

    /**
     * A focus stays visible when it has no prerequisites, or when every OR-block contains at least
     * one id still in [treeIds]. Hidden parents are dropped and the check is repeated so children
     * of a hidden focus do not float in the preview.
     */
    fun isVisible(groups: List<List<String>>, treeIds: Set<String>): Boolean {
        val normalized = normalizeGroups(groups)
        if (normalized.isEmpty()) return true
        return normalized.all { group -> group.any { it in treeIds } }
    }

    fun visibleFocuses(focuses: List<FocusData>): List<FocusData> {
        var remaining = focuses
        while (true) {
            val ids = remaining.mapTo(hashSetOf()) { it.id }
            val next = remaining.filter { isVisible(it.prerequisiteGroups, ids) }
            if (next.size == remaining.size) return next
            remaining = next
        }
    }

    fun visibleIds(focuses: List<FocusData>): Set<String> =
        visibleFocuses(focuses).mapTo(hashSetOf()) { it.id }

    fun planLinks(
        groups: List<List<String>>,
        targetId: String,
        positions: Map<String, Point>,
        nodeWidth: Int,
        nodeHeight: Int,
        minJointGap: Int = 8
    ): List<FocusPrerequisiteLinkPlan> {
        val target = positions[targetId] ?: return emptyList()
        val targetTop = target.y
        val targetCenterX = target.x + nodeWidth / 2
        val inTreeGroups = normalizeGroups(groups)
            .map { group -> group.filter { it != targetId && it in positions } }
            .filter { it.isNotEmpty() }
        if (inTreeGroups.isEmpty()) return emptyList()

        val allSingles = inTreeGroups.all { it.size == 1 }
        if (inTreeGroups.size > 1 && allSingles) {
            // The game draws AND (separate prerequisite blocks) as one solid joint bar.
            return listOf(
                jointLink(
                    fromIds = inTreeGroups.map { it.first() },
                    positions = positions,
                    nodeWidth = nodeWidth,
                    nodeHeight = nodeHeight,
                    endX = targetCenterX,
                    endY = targetTop,
                    minJointGap = minJointGap,
                    dashed = false
                )
            )
        }

        return inTreeGroups.mapIndexed { index, group ->
            val endX = andArrivalX(targetCenterX, nodeWidth, index, inTreeGroups.size)
            if (group.size == 1) {
                simpleLink(group.first(), positions, nodeWidth, nodeHeight, endX, targetTop)
            } else {
                // The game uses dotted tiles for OR (several focuses in one prerequisite block).
                jointLink(group, positions, nodeWidth, nodeHeight, endX, targetTop, minJointGap, dashed = true)
            }
        }
    }

    private fun formatGroup(group: List<String>, parenthesize: Boolean): String {
        if (group.size == 1) return group.first()
        val body = group.joinToString(" OR ")
        return if (parenthesize) "($body)" else body
    }

    private fun cleanId(raw: String): String = raw.trim().trim('"')

    private fun andArrivalX(centerX: Int, nodeWidth: Int, index: Int, count: Int): Int {
        if (count <= 1) return centerX
        val span = (nodeWidth * 0.4).toInt().coerceAtLeast(12)
        val step = span.toDouble() / (count - 1)
        return centerX - span / 2 + (index * step).toInt()
    }

    private fun simpleLink(
        fromId: String,
        positions: Map<String, Point>,
        nodeWidth: Int,
        nodeHeight: Int,
        endX: Int,
        endY: Int
    ): FocusPrerequisiteLinkPlan {
        val start = positions.getValue(fromId)
        val startX = start.x + nodeWidth / 2
        val startY = start.y + nodeHeight
        val midY = (startY + endY) / 2
        return FocusPrerequisiteLinkPlan(
            fromIds = listOf(fromId),
            segments = orthogonalSegments(startX, startY, endX, endY, midY),
            arrowX = endX,
            arrowY = endY,
            joint = false
        )
    }

    private fun jointLink(
        fromIds: List<String>,
        positions: Map<String, Point>,
        nodeWidth: Int,
        nodeHeight: Int,
        endX: Int,
        endY: Int,
        minJointGap: Int,
        dashed: Boolean
    ): FocusPrerequisiteLinkPlan {
        val parents = fromIds.map { id ->
            val point = positions.getValue(id)
            ParentAnchor(id, point.x + nodeWidth / 2, point.y + nodeHeight)
        }
        val maxBottom = parents.maxOf { it.bottom }
        if (endY - maxBottom < minJointGap) {
            val segments = parents.flatMap { parent ->
                val midY = (parent.bottom + endY) / 2
                orthogonalSegments(parent.centerX, parent.bottom, endX, endY, midY)
            }
            return FocusPrerequisiteLinkPlan(
                fromIds = fromIds,
                segments = segments,
                arrowX = endX,
                arrowY = endY,
                joint = false,
                dashed = dashed
            )
        }
        val jointY = (maxBottom + endY) / 2
        val barMin = (parents.minOf { it.centerX }).coerceAtMost(endX)
        val barMax = (parents.maxOf { it.centerX }).coerceAtLeast(endX)
        return FocusPrerequisiteLinkPlan(
            fromIds = fromIds,
            segments = buildList {
                for (parent in parents) {
                    segment(parent.centerX, parent.bottom, parent.centerX, jointY)?.let(::add)
                }
                segment(barMin, jointY, barMax, jointY)?.let(::add)
                segment(endX, jointY, endX, endY)?.let(::add)
            },
            arrowX = endX,
            arrowY = endY,
            joint = true,
            dashed = dashed,
            jointX = endX,
            jointY = jointY
        )
    }

    private fun orthogonalSegments(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        midY: Int
    ): List<FocusPrerequisiteSegment> {
        return listOfNotNull(
            segment(startX, startY, startX, midY),
            segment(startX, midY, endX, midY),
            segment(endX, midY, endX, endY)
        )
    }

    private fun segment(x1: Int, y1: Int, x2: Int, y2: Int): FocusPrerequisiteSegment? {
        if (x1 == x2 && y1 == y2) return null
        return FocusPrerequisiteSegment(x1, y1, x2, y2)
    }

    private data class ParentAnchor(val id: String, val centerX: Int, val bottom: Int)
}

internal data class FocusPrerequisiteSegment(
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int
)

internal data class FocusPrerequisiteLinkPlan(
    val fromIds: List<String>,
    val segments: List<FocusPrerequisiteSegment>,
    val arrowX: Int,
    val arrowY: Int,
    val joint: Boolean,
    val dashed: Boolean = false,
    val jointX: Int = arrowX,
    val jointY: Int = arrowY
)
