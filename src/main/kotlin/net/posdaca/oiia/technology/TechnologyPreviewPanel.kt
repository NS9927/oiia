package net.posdaca.oiia.technology

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBFont
import net.posdaca.oiia.core.preview.PreviewGraphCanvas
import net.posdaca.oiia.core.preview.PreviewHintHtml
import net.posdaca.oiia.core.preview.PreviewNodeStyle
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Point
import java.awt.geom.Path2D

class TechnologyPreviewPanel(
    project: Project,
    snapshot: TechnologyPreviewSnapshot,
    service: TechnologyService
) : JBPanel<JBPanel<*>>(BorderLayout()) {

    private val canvas = TechnologyCanvas(project, snapshot)

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

    private class TechnologyCanvas(
        project: Project,
        snapshot: TechnologyPreviewSnapshot
    ) : PreviewGraphCanvas<TechnologyHit>(project) {

        private var technologyTrees: List<TechnologyTreeData> = snapshot.trees
        private val logicalNodeHits = mutableMapOf<String, TechnologyHit>()

        override val horizontalGap: Int = JBUIScale.scale(68)
        override val verticalGap: Int = JBUIScale.scale(72)

        fun applySnapshot(next: TechnologyPreviewSnapshot) {
            val resolvedById = next.allTechnologies.associateBy { it.id }
            technologyTrees = TechnologyPreviewSnapshot(technologyTrees).withResolved(resolvedById).trees
            revalidate()
            repaint()
        }

        override fun isDocumentEmpty(): Boolean = technologyTrees.isEmpty()

        override fun findHit(logical: Point): TechnologyHit? {
            for ((nodeKey, bounds) in logicalNodeBounds) {
                if (bounds.contains(logical)) return logicalNodeHits[nodeKey]
            }
            return null
        }

        override fun hitId(hit: TechnologyHit): String = hit.technology.id

        override fun navigateTo(hit: TechnologyHit) {
            navigateToSource(hit.technology.sourceFilePath, hit.technology.sourceLine)
        }

        override fun buildHintHtml(hit: TechnologyHit): String {
            val technology = hit.technology
            val folderPosition = technology.positionIn(hit.folderName)
            return PreviewHintHtml()
                .header("technology", technology.displayName, technology.id)
                .description(technology.localizedDescription)
                .escapedRow("Folder", hit.folderName)
                .row("Year", technology.startYear?.toString())
                .row("Pos", folderPosition?.let { "(${it.x.toInt()}, ${it.y.toInt()})" })
                .escapedRow("Categories", technology.categories.takeIf { it.isNotEmpty() }?.joinToString(", "))
                .escapedRow("Path", technology.leadsTo.takeIf { it.isNotEmpty() }?.joinToString(", "))
                .escapedRow("Xor", technology.xor.takeIf { it.isNotEmpty() }?.joinToString(", "))
                .escapedRow("Sub techs", technology.subTechnologies.takeIf { it.isNotEmpty() }?.joinToString(", "))
                .build()
        }

        override fun computeLogicalSize(): Dimension {
            var maxW = 400
            var currentY = padding
            for (tree in technologyTrees) {
                if (tree.technologies.isEmpty()) continue
                val positions = computeRawPositions(tree)
                val minX = positions.values.minOfOrNull { it.x } ?: 0
                val maxX = positions.values.maxOfOrNull { it.x } ?: 0
                val minY = positions.values.minOfOrNull { it.y } ?: 0
                val maxY = positions.values.maxOfOrNull { it.y } ?: 0
                maxW = maxOf(maxW, maxX - minX + nodeWidth + padding * 2)
                currentY += (maxY - minY) + nodeHeight + padding * 2
            }
            return Dimension(maxW, currentY.coerceAtLeast(300))
        }

        override fun paintGraph(g2d: Graphics2D) {
            logicalPositions.clear()
            logicalNodeBounds.clear()
            logicalNodeHits.clear()
            var offsetY = padding
            for (tree in technologyTrees) {
                if (tree.technologies.isEmpty()) continue
                val rawPositions = computeRawPositions(tree)
                val minRawX = rawPositions.values.minOfOrNull { it.x } ?: 0
                val minRawY = rawPositions.values.minOfOrNull { it.y } ?: 0
                val shiftX = padding - minRawX
                val treeOffsetY = offsetY + padding - minRawY
                for ((technologyId, rawPos) in rawPositions) {
                    val px = rawPos.x + shiftX
                    val py = rawPos.y + treeOffsetY
                    val key = nodeKey(tree.folderName, technologyId)
                    logicalPositions[key] = Point(px, py)
                    logicalNodeBounds[key] = java.awt.Rectangle(px, py, nodeWidth, nodeHeight)
                    tree.technologies.firstOrNull { it.id == technologyId }?.let {
                        logicalNodeHits[key] = TechnologyHit(key, tree.folderName, it)
                    }
                }
                g2d.font = JBFont.label().deriveFont(Font.BOLD, 13f)
                g2d.color = PreviewNodeStyle.treeTitle
                g2d.drawString(tree.folderName, padding, offsetY + JBUIScale.scale(14))
                drawPathConnections(g2d, tree)
                drawXorConnections(g2d, tree)
                for (technology in tree.technologies) {
                    val pos = logicalPositions[nodeKey(tree.folderName, technology.id)] ?: continue
                    drawNodeCard(
                        g2d = g2d,
                        pos = pos,
                        title = technology.displayName,
                        iconPath = technology.iconImagePath,
                        fallbackText = technology.id,
                        selected = technology.id == selectedId,
                        hovered = technology.id == hoveredId
                    )
                }
                val maxRawY = rawPositions.values.maxOfOrNull { it.y } ?: 0
                offsetY += (maxRawY - minRawY) + nodeHeight + padding * 2
            }
        }

        private fun computeRawPositions(tree: TechnologyTreeData): Map<String, Point> {
            val positions = mutableMapOf<String, Point>()
            for (technology in tree.technologies) {
                val folderPosition = technology.positionIn(tree.folderName) ?: continue
                positions[technology.id] = Point(
                    folderPosition.x.toInt() * (nodeWidth + horizontalGap),
                    folderPosition.y.toInt() * (nodeHeight + verticalGap)
                )
            }
            return positions
        }

        private fun drawPathConnections(g2d: Graphics2D, tree: TechnologyTreeData) {
            val technologyIdsInFolder = tree.technologies.mapTo(hashSetOf()) { it.id }
            for (technology in tree.technologies) {
                val startPt = logicalPositions[nodeKey(tree.folderName, technology.id)] ?: continue
                val sx = startPt.x + nodeWidth / 2
                val sy = startPt.y + nodeHeight
                for (nextId in technology.leadsTo.filter { it in technologyIdsInFolder }) {
                    val endPt = logicalPositions[nodeKey(tree.folderName, nextId)] ?: continue
                    val endCx = endPt.x + nodeWidth / 2
                    val endTop = endPt.y
                    drawOrthogonalLink(
                        g2d,
                        sx,
                        sy,
                        endCx,
                        endTop,
                        isHighlighted(technology.id, nextId)
                    )
                }
            }
        }

        private fun drawXorConnections(g2d: Graphics2D, tree: TechnologyTreeData) {
            val technologyIdsInFolder = tree.technologies.mapTo(hashSetOf()) { it.id }
            val drawnPairs = hashSetOf<String>()
            for (technology in tree.technologies) {
                val startPt = logicalPositions[nodeKey(tree.folderName, technology.id)] ?: continue
                val startCx = startPt.x + nodeWidth / 2
                val startCy = startPt.y + nodeHeight / 2
                for (xorId in technology.xor.filter { it in technologyIdsInFolder }) {
                    val pairKey = listOf(technology.id, xorId).sorted().joinToString("\u0000")
                    if (!drawnPairs.add(pairKey)) continue
                    val endPt = logicalPositions[nodeKey(tree.folderName, xorId)] ?: continue
                    val endCx = endPt.x + nodeWidth / 2
                    val endCy = endPt.y + nodeHeight / 2
                    val active = technology.id == selectedId || xorId == selectedId ||
                        technology.id == hoveredId || xorId == hoveredId
                    g2d.color = if (active) PreviewNodeStyle.xorActive else PreviewNodeStyle.xor
                    g2d.stroke = BasicStroke(
                        if (active) JBUIScale.scale(2.2f) else JBUIScale.scale(1.3f),
                        BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND,
                        10f,
                        floatArrayOf(JBUIScale.scale(5f), JBUIScale.scale(4f)),
                        0f
                    )
                    val path = Path2D.Double()
                    path.moveTo(startCx.toDouble(), startCy.toDouble())
                    val midX = (startCx + endCx) / 2
                    path.lineTo(midX.toDouble(), startCy.toDouble())
                    path.lineTo(midX.toDouble(), endCy.toDouble())
                    path.lineTo(endCx.toDouble(), endCy.toDouble())
                    g2d.draw(path)
                }
            }
        }

        private fun nodeKey(folderName: String, technologyId: String): String = "$folderName\u0000$technologyId"
    }

    private data class TechnologyHit(
        val nodeKey: String,
        val folderName: String,
        val technology: TechnologyData
    )
}