package net.posdaca.oiia.focus

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.scale.JBUIScale
import net.posdaca.oiia.core.preview.PreviewGraphCanvas
import net.posdaca.oiia.core.preview.PreviewHintHtml
import net.posdaca.oiia.core.preview.PreviewNodeStyle
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics2D
import java.awt.Point
import kotlin.math.roundToInt

class NationalFocusPreviewPanel(
    project: Project,
    snapshot: FocusPreviewSnapshot,
    service: NationalFocusService
) : JBPanel<JBPanel<*>>(BorderLayout()) {

    private val canvas = FocusCanvas(project, snapshot, service)

    init {
        background = JBColor.PanelBackground
        val scrollPane = JBScrollPane(canvas)
        scrollPane.horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        scrollPane.verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        scrollPane.border = null
        scrollPane.viewport.background = JBColor.PanelBackground
        add(scrollPane, BorderLayout.CENTER)
        service.resolve(snapshot) { canvas.applySnapshot(it) }
    }

    private class FocusCanvas(
        project: Project,
        snapshot: FocusPreviewSnapshot,
        private val service: NationalFocusService
    ) : PreviewGraphCanvas<FocusData>(project) {

        private var workingTrees: List<NationalFocusTreeData> = snapshot.trees
        private val allFocusesList = mutableListOf<FocusData>()
        private var dragBaseGridX = 0
        private var dragBaseGridY = 0
        private var dragGridDeltaX = 0
        private var dragGridDeltaY = 0

        override val horizontalGap: Int = JBUIScale.scale(70)
        override val verticalGap: Int = JBUIScale.scale(80)

        fun applySnapshot(next: FocusPreviewSnapshot) {
            val resolvedById = next.allFocuses.associateBy { it.id }
            workingTrees = FocusPreviewSnapshot(workingTrees).withResolved(resolvedById).trees
            revalidate()
            repaint()
        }

        override fun isDocumentEmpty(): Boolean = workingTrees.isEmpty()

        override fun findHit(logical: Point): FocusData? {
            for ((id, bounds) in logicalNodeBounds) {
                if (bounds.contains(logical)) return allFocusesList.find { it.id == id }
            }
            return null
        }

        override fun hitId(hit: FocusData): String = hit.id

        override fun navigateTo(hit: FocusData) {
            navigateToSource(hit.sourceFilePath, hit.sourceLine)
        }

        override fun buildHintHtml(hit: FocusData): String {
            val type = if (hit.isSharedFocus) "shared_focus" else "focus"
            val position = if (hit.relativePositionId != null) {
                "rel(${hit.x.toInt()}, ${hit.y.toInt()}) from ${PreviewHintHtml.escape(hit.relativePositionId)}"
            } else {
                "(${hit.x.toInt()}, ${hit.y.toInt()})"
            }
            return PreviewHintHtml()
                .header(type, hit.displayName, hit.id)
                .description(hit.localizedDescription ?: hit.text)
                .row("Cost", hit.cost.toInt().toString())
                .row("Pos", position)
                .escapedRow("Req", hit.prerequisites.takeIf { it.isNotEmpty() }?.joinToString(", "))
                .escapedRow("MutExc", hit.mutuallyExclusive.takeIf { it.isNotEmpty() }?.joinToString(", "))
                .row("AI", hit.aiWillDo?.let { "%.0f%%".format(it * 100) })
                .row("Type", if (hit.isSharedFocus) "Shared focus" else null)
                .footer(hit.completeTooltip)
                .build()
        }

        override fun canDragNode(hit: FocusData): Boolean =
            !hit.sourceFilePath.isNullOrBlank() && hit.id.isNotBlank()

        override fun onNodeDragStarted(hit: FocusData) {
            dragBaseGridX = hit.x.toInt()
            dragBaseGridY = hit.y.toInt()
            dragGridDeltaX = 0
            dragGridDeltaY = 0
        }

        override fun onNodeDragged(hit: FocusData, startLogical: Point, currentLogical: Point) {
            val cellW = (nodeWidth + horizontalGap).coerceAtLeast(1)
            val cellH = (nodeHeight + verticalGap).coerceAtLeast(1)
            dragGridDeltaX = ((currentLogical.x - startLogical.x).toDouble() / cellW).roundToInt()
            dragGridDeltaY = ((currentLogical.y - startLogical.y).toDouble() / cellH).roundToInt()
        }

        override fun onNodeDragFinished(hit: FocusData): Boolean {
            val newX = dragBaseGridX + dragGridDeltaX
            val newY = dragBaseGridY + dragGridDeltaY
            dragGridDeltaX = 0
            dragGridDeltaY = 0
            if (hit.x.toInt() == newX && hit.y.toInt() == newY) {
                repaint()
                return true
            }
            if (!service.updateFocusPosition(hit, newX, newY)) {
                repaint()
                return true
            }
            workingTrees = workingTrees.map { tree ->
                tree.copy(
                    focuses = tree.focuses.map { if (it.id == hit.id) it.copy(x = newX.toDouble(), y = newY.toDouble()) else it },
                    sharedFocuses = tree.sharedFocuses.map { if (it.id == hit.id) it.copy(x = newX.toDouble(), y = newY.toDouble()) else it }
                )
            }
            revalidate()
            repaint()
            return true
        }

        override fun computeLogicalSize(): Dimension {
            var maxW = 400
            var maxH = 300
            for (tree in workingTrees) {
                val (_, positions) = layoutTree(tree) ?: continue
                val minX = positions.values.minOfOrNull { it.x } ?: 0
                val maxX = positions.values.maxOfOrNull { it.x } ?: 0
                val maxY = positions.values.maxOfOrNull { it.y } ?: 0
                maxW = maxOf(maxW, maxX - minX + nodeWidth + padding * 2)
                maxH = maxOf(maxH, maxY + nodeHeight + padding * 2)
            }
            return Dimension(maxW, maxH)
        }

        override fun paintGraph(g2d: Graphics2D) {
            logicalPositions.clear()
            logicalNodeBounds.clear()
            allFocusesList.clear()
            var offsetY = padding
            for (tree in workingTrees) {
                val (focuses, rawPositions) = layoutTree(tree) ?: continue
                allFocusesList.addAll(focuses)
                val minRawX = rawPositions.values.minOfOrNull { it.x } ?: 0
                val minRawY = rawPositions.values.minOfOrNull { it.y } ?: 0
                val shiftX = padding - minRawX
                val treeOffsetY = offsetY + padding - minRawY
                for ((focusId, rawPos) in rawPositions) {
                    val px = rawPos.x + shiftX
                    val py = rawPos.y + treeOffsetY
                    logicalPositions[focusId] = Point(px, py)
                    logicalNodeBounds[focusId] = java.awt.Rectangle(px, py, nodeWidth, nodeHeight)
                }
                drawMutuallyExclusive(g2d, focuses)
                drawPrerequisiteConnections(g2d, focuses)
                for (focus in focuses) {
                    val pos = logicalPositions[focus.id] ?: continue
                    drawNodeCard(
                        g2d = g2d,
                        pos = pos,
                        title = focus.displayName,
                        iconPath = focus.iconImagePath,
                        fallbackText = focus.iconKey,
                        selected = focus.id == selectedId,
                        hovered = focus.id == hoveredId,
                        shared = focus.isSharedFocus,
                        badge = focus.isSharedFocus
                    )
                }
                val maxRawY = rawPositions.values.maxOfOrNull { it.y } ?: 0
                offsetY += (maxRawY - minRawY) + nodeHeight + padding * 2
            }
        }

        private fun effectiveGrid(focus: FocusData): Point {
            val baseX = focus.x.toInt()
            val baseY = focus.y.toInt()
            if (draggingNode && pressedHit?.id == focus.id) {
                return Point(baseX + dragGridDeltaX, baseY + dragGridDeltaY)
            }
            return Point(baseX, baseY)
        }

        private fun layoutTree(tree: NationalFocusTreeData): Pair<List<FocusData>, Map<String, Point>>? {
            val focuses = tree.focuses + tree.sharedFocuses
            if (focuses.isEmpty()) return null
            return focuses to computeRawPositions(focuses)
        }

        private fun computeRawPositions(focuses: List<FocusData>): Map<String, Point> {
            val positions = mutableMapOf<String, Point>()
            val resolved = mutableSetOf<String>()
            fun resolveWithRelative(focus: FocusData): Point {
                if (focus.id in resolved) return positions[focus.id] ?: Point(0, 0)
                val relId = focus.relativePositionId
                if (relId != null) {
                    val relFocus = focuses.find { it.id == relId }
                    if (relFocus != null) {
                        val relPos = resolveWithRelative(relFocus)
                        val grid = effectiveGrid(focus)
                        val pt = Point(
                            relPos.x + grid.x * (nodeWidth + horizontalGap),
                            relPos.y + grid.y * (nodeHeight + verticalGap)
                        )
                        positions[focus.id] = pt
                        resolved.add(focus.id)
                        return pt
                    }
                }
                val grid = effectiveGrid(focus)
                val pt = Point(grid.x * (nodeWidth + horizontalGap), grid.y * (nodeHeight + verticalGap))
                positions[focus.id] = pt
                resolved.add(focus.id)
                return pt
            }
            for (focus in focuses) resolveWithRelative(focus)
            return positions
        }

        private fun drawPrerequisiteConnections(g2d: Graphics2D, focuses: List<FocusData>) {
            for (focus in focuses) {
                val endPt = logicalPositions[focus.id] ?: continue
                val endCx = endPt.x + nodeWidth / 2
                val endTop = endPt.y
                for (prereqId in focus.prerequisites) {
                    val startPt = logicalPositions[prereqId] ?: continue
                    val sx = startPt.x + nodeWidth / 2
                    val sy = startPt.y + nodeHeight
                    drawOrthogonalLink(
                        g2d,
                        sx,
                        sy,
                        endCx,
                        endTop,
                        isHighlighted(prereqId, focus.id)
                    )
                }
            }
        }

        private fun drawMutuallyExclusive(g2d: Graphics2D, focuses: List<FocusData>) {
            val seen = mutableSetOf<String>()
            for (focus in focuses) {
                for (meId in focus.mutuallyExclusive) {
                    val pairKey = if (focus.id < meId) "${focus.id}::$meId" else "$meId::${focus.id}"
                    if (!seen.add(pairKey)) continue
                    val ptA = logicalPositions[focus.id] ?: continue
                    val ptB = logicalPositions[meId] ?: continue
                    val ax = ptA.x + nodeWidth / 2
                    val ay = ptA.y + nodeHeight / 2
                    val bx = ptB.x + nodeWidth / 2
                    val by = ptB.y + nodeHeight / 2
                    val active = focus.id == hoveredId || meId == hoveredId ||
                        focus.id == selectedId || meId == selectedId
                    g2d.color = if (active) PreviewNodeStyle.exclusiveActive else PreviewNodeStyle.exclusive
                    g2d.stroke = BasicStroke(
                        if (active) JBUIScale.scale(2.5f) else JBUIScale.scale(1.5f),
                        BasicStroke.CAP_BUTT,
                        BasicStroke.JOIN_BEVEL,
                        0f,
                        floatArrayOf(JBUIScale.scale(6f), JBUIScale.scale(4f)),
                        0f
                    )
                    val midX = (ax + bx) / 2
                    val midY = (ay + by) / 2
                    g2d.drawLine(ax, ay, midX, midY)
                    g2d.drawLine(midX, midY, bx, by)
                    val cross = JBUIScale.scale(5)
                    g2d.stroke = BasicStroke(JBUIScale.scale(2f))
                    g2d.drawLine(midX - cross, midY - cross, midX + cross, midY + cross)
                    g2d.drawLine(midX + cross, midY - cross, midX - cross, midY + cross)
                }
            }
        }
    }
}