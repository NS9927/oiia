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
        private val titleHeight = JBUIScale.scale(24)
        private val labelHeight = JBUIScale.scale(16)
        private val folderGap = JBUIScale.scale(72)

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
            val columns = layoutFolderColumns()
            if (columns.isEmpty()) return Dimension(400, 300)
            val last = columns.last()
            return Dimension(
                (last.x + last.width + padding).coerceAtLeast(400),
                (columns.maxOf { it.height } + padding).coerceAtLeast(300)
            )
        }

        override fun paintGraph(g2d: Graphics2D) {
            logicalPositions.clear()
            logicalNodeBounds.clear()
            logicalNodeHits.clear()
            for (column in layoutFolderColumns()) {
                g2d.font = JBFont.label().deriveFont(Font.BOLD, 13f)
                g2d.color = PreviewNodeStyle.treeTitle
                g2d.drawString(column.folderName, column.x, padding + JBUIScale.scale(14))
                for (box in column.boxes) {
                    box.rootLabel?.let { label ->
                        g2d.font = JBFont.label().deriveFont(Font.PLAIN, 11f)
                        g2d.color = PreviewNodeStyle.treeTitle
                        g2d.drawString(label, column.x, box.labelY + JBUIScale.scale(11))
                    }
                    for ((technologyId, pos) in box.positions) {
                        val key = nodeKey(column.folderName, technologyId)
                        logicalPositions[key] = pos
                        logicalNodeBounds[key] = java.awt.Rectangle(pos.x, pos.y, nodeWidth, nodeHeight)
                        box.tree.technologies.firstOrNull { it.id == technologyId }?.let {
                            logicalNodeHits[key] = TechnologyHit(key, column.folderName, it)
                        }
                    }
                    drawPathConnections(g2d, box.tree)
                    drawXorConnections(g2d, box.tree)
                    for (technology in box.tree.technologies) {
                        val pos = box.positions[technology.id] ?: continue
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
                }
            }
        }

        private data class TreeBox(
            val tree: TechnologyTreeData,
            val rootLabel: String?,
            val labelY: Int,
            val positions: Map<String, Point>,
            val width: Int,
            val height: Int
        )

        private data class FolderColumn(
            val folderName: String,
            val boxes: List<TreeBox>,
            val x: Int,
            val width: Int,
            val height: Int
        )

        /**
         * Folders become side-by-side columns (next folder to the right, like the game's tabs);
         * each folder's trees stack vertically inside its column.
         */
        private fun layoutFolderColumns(): List<FolderColumn> {
            val columns = mutableListOf<FolderColumn>()
            var cursorX = padding
            for ((folder, treesInFolder) in technologyTrees.groupBy { it.folderName }) {
                val visible = treesInFolder.filter { it.technologies.isNotEmpty() }
                if (visible.isEmpty()) continue
                val multiTree = visible.size > 1
                val boxes = mutableListOf<TreeBox>()
                var cursorY = padding + titleHeight
                var columnWidth = 0
                for (tree in visible) {
                    val rawPositions = computeRawPositions(tree)
                    if (rawPositions.isEmpty()) continue
                    val minX = rawPositions.values.minOf { it.x }
                    val maxX = rawPositions.values.maxOf { it.x }
                    val minY = rawPositions.values.minOf { it.y }
                    val maxY = rawPositions.values.maxOf { it.y }
                    var labelY = 0
                    if (multiTree) {
                        labelY = cursorY
                        cursorY += labelHeight
                    }
                    val positions = rawPositions.mapValues { (_, raw) ->
                        Point(cursorX + raw.x - minX, cursorY + raw.y - minY)
                    }
                    val width = maxX - minX + nodeWidth
                    val height = maxY - minY + nodeHeight
                    boxes += TreeBox(
                        tree,
                        if (multiTree) tree.startTechnology else null,
                        labelY,
                        positions,
                        width,
                        height
                    )
                    cursorY += height + verticalGap
                    columnWidth = maxOf(columnWidth, width)
                }
                if (boxes.isEmpty()) continue
                columns += FolderColumn(folder, boxes, cursorX, columnWidth, cursorY - verticalGap)
                cursorX += columnWidth + folderGap
            }
            return columns
        }

        private fun computeRawPositions(tree: TechnologyTreeData): Map<String, Point> {
            val layout = tree.layout
            val stepX = gridStep(layout?.slotWidth, nodeWidth + horizontalGap)
            val stepY = gridStep(layout?.slotHeight, nodeHeight + verticalGap)
            val format = layout?.format?.lowercase()
            val positions = mutableMapOf<String, Point>()
            for (technology in tree.technologies) {
                val folderPosition = technology.positionIn(tree.folderName) ?: continue
                val gx = folderPosition.x.toInt()
                val gy = folderPosition.y.toInt()
                // Mirrors the game's gridbox placement: down mirrors Y, left/right rotate the axes.
                positions[technology.id] = when (format) {
                    "left" -> Point(gy * stepX, gx * stepY)
                    "right" -> Point(-gy * stepX, gx * stepY)
                    "down" -> Point(gx * stepX, -gy * stepY)
                    else -> Point(gx * stepX, gy * stepY)
                }
            }
            return positions
        }

        /** Honors the game's slot size when it demands more room than our cards need. */
        private fun gridStep(slot: Int?, cardStep: Int): Int = if (slot != null && slot > cardStep) slot else cardStep

        private fun drawPathConnections(g2d: Graphics2D, tree: TechnologyTreeData) {
            val technologyIdsInFolder = tree.technologies.mapTo(hashSetOf()) { it.id }
            val horizontal = tree.layout?.isHorizontal == true
            for (technology in tree.technologies) {
                val startPt = logicalPositions[nodeKey(tree.folderName, technology.id)] ?: continue
                val sx = if (horizontal) startPt.x + nodeWidth else startPt.x + nodeWidth / 2
                val sy = if (horizontal) startPt.y + nodeHeight / 2 else startPt.y + nodeHeight
                for (nextId in technology.leadsTo.filter { it in technologyIdsInFolder }) {
                    val endPt = logicalPositions[nodeKey(tree.folderName, nextId)] ?: continue
                    val ex = if (horizontal) endPt.x else endPt.x + nodeWidth / 2
                    val ey = if (horizontal) endPt.y + nodeHeight / 2 else endPt.y
                    drawOrthogonalLink(
                        g2d,
                        sx,
                        sy,
                        ex,
                        ey,
                        isHighlighted(technology.id, nextId),
                        horizontalFirst = horizontal
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