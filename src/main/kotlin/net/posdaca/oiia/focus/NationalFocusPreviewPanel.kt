package net.posdaca.oiia.focus

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.LightweightHint
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBFont
import net.posdaca.oiia.core.PreviewHintSupport
import net.posdaca.oiia.core.PreviewImageLoader
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.ToolTipManager

class NationalFocusPreviewPanel(
    project: Project,
    focusTrees: List<NationalFocusTreeData>,
    service: NationalFocusService
) : JBPanel<JBPanel<*>>(BorderLayout()) {

    private val canvas = FocusCanvas(project, focusTrees, service)

    init {
        background = JBColor.PanelBackground
        val scrollPane = JBScrollPane(canvas)
        scrollPane.horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        scrollPane.verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        scrollPane.border = null
        scrollPane.viewport.background = JBColor.PanelBackground
        add(scrollPane, BorderLayout.CENTER)

        val allFocuses = focusTrees.flatMap { it.focuses + it.sharedFocuses }
        service.scheduleResolution(allFocuses) { canvas.repaint() }
    }

    private class FocusCanvas(
        private val project: Project,
        focusTrees: List<NationalFocusTreeData>,
        private val service: NationalFocusService
    ) : JBPanel<JBPanel<*>>(null), Scrollable {

        private var workingTrees: List<NationalFocusTreeData> = focusTrees

        private val nodeWidth = JBUIScale.scale(132)
        private val nodeHeight = JBUIScale.scale(116)
        private val hGap = JBUIScale.scale(70)
        private val vGap = JBUIScale.scale(80)
        private val padding = JBUIScale.scale(40)
        private var zoomFactor = 1.0

        private var selectedFocusId: String? = null
        private var hoveredFocusId: String? = null
        private var lockedFocusId: String? = null
        private var lockedHint: LightweightHint? = null
        private var dragPressScreenPoint: Point? = null
        private var dragScrollStart: Point? = null
        private var pressedFocusId: String? = null
        private var draggingView = false
        private var draggingFocus = false
        private var dragStartLogicalPoint: Point? = null
        private var dragBaseGridX = 0
        private var dragBaseGridY = 0
        private var dragGridDeltaX = 0
        private var dragGridDeltaY = 0
        private var singleClickTimer: Timer? = null

        private val logicalPositions = mutableMapOf<String, Point>()
        private val logicalNodeBounds = mutableMapOf<String, Rectangle>()
        private val allFocusesList = mutableListOf<FocusData>()
        private val iconCache = mutableMapOf<String, BufferedImage>()

        private val scrollableHsb: javax.swing.JScrollBar?
            get() {
                val sp = SwingUtilities.getAncestorOfClass(JBScrollPane::class.java, this) as? JBScrollPane
                return sp?.horizontalScrollBar
            }
        private val scrollableVsb: javax.swing.JScrollBar?
            get() {
                val sp = SwingUtilities.getAncestorOfClass(JBScrollPane::class.java, this) as? JBScrollPane
                return sp?.verticalScrollBar
            }

        init {
            isOpaque = true
            background = JBColor.PanelBackground
            enableEvents(AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_WHEEL_EVENT_MASK)
            ToolTipManager.sharedInstance().registerComponent(this)

            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1) {
                        if (e.clickCount > 1) cancelPendingSingleClick()
                        val logicalPt = screenToLogical(e)
                        val pressed = findFocusAt(logicalPt)
                        pressedFocusId = pressed?.id
                        dragPressScreenPoint = Point(e.locationOnScreen)
                        dragScrollStart = Point(scrollableHsb?.value ?: 0, scrollableVsb?.value ?: 0)
                        dragStartLogicalPoint = logicalPt
                        if (pressed != null) {
                            dragBaseGridX = pressed.x.toInt()
                            dragBaseGridY = pressed.y.toInt()
                        } else {
                            dragBaseGridX = 0
                            dragBaseGridY = 0
                        }
                        dragGridDeltaX = 0
                        dragGridDeltaY = 0
                        draggingView = false
                        draggingFocus = false
                    }
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (e.button != MouseEvent.BUTTON1) return
                    val wasDraggingView = draggingView
                    val wasDraggingFocus = draggingFocus
                    val draggedId = pressedFocusId
                    val gridDx = dragGridDeltaX
                    val gridDy = dragGridDeltaY
                    val baseX = dragBaseGridX
                    val baseY = dragBaseGridY
                    cursor = Cursor.getDefaultCursor()
                    dragPressScreenPoint = null
                    dragScrollStart = null
                    dragStartLogicalPoint = null
                    dragGridDeltaX = 0
                    dragGridDeltaY = 0
                    draggingView = false
                    draggingFocus = false

                    if (wasDraggingFocus && draggedId != null && (gridDx != 0 || gridDy != 0)) {
                        val focus = findFocusData(draggedId)
                        if (focus != null) {
                            commitFocusDrag(focus, baseX + gridDx, baseY + gridDy)
                        } else {
                            repaint()
                        }
                    } else if (!wasDraggingView && !wasDraggingFocus) {
                        val clicked = findFocusAt(screenToLogical(e))
                        if (clicked != null && clicked.id == draggedId) {
                            selectedFocusId = clicked.id
                            if (e.clickCount >= 2) {
                                cancelPendingSingleClick()
                                hideFocusHint(clearLocked = true)
                                navigateToFocus(clicked)
                            } else {
                                scheduleFocusHint(clicked, e.point)
                            }
                        } else if (clicked == null) {
                            cancelPendingSingleClick()
                            selectedFocusId = null
                            hideFocusHint(clearLocked = true)
                        }
                        repaint()
                    }
                    pressedFocusId = null
                }

                override fun mouseExited(e: MouseEvent) {
                    if (draggingFocus || draggingView) return
                    if (hoveredFocusId != null) {
                        hoveredFocusId = null
                        repaint()
                    }
                }
            })

            addMouseMotionListener(object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    val logicalPt = screenToLogical(e)
                    val hovered = findFocusAt(logicalPt)
                    val prevId = hoveredFocusId
                    hoveredFocusId = hovered?.id
                    if (prevId != hoveredFocusId) repaint()
                    cursor = if (hovered != null && canDragFocus(hovered)) {
                        Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    } else {
                        Cursor.getDefaultCursor()
                    }
                }

                override fun mouseDragged(e: MouseEvent) {
                    val pressPoint = dragPressScreenPoint ?: return
                    val threshold = JBUIScale.scale(4)
                    val currentPoint = e.locationOnScreen
                    val screenDx = currentPoint.x - pressPoint.x
                    val screenDy = currentPoint.y - pressPoint.y
                    if (!draggingView && !draggingFocus &&
                        screenDx * screenDx + screenDy * screenDy < threshold * threshold
                    ) return

                    val pressedId = pressedFocusId
                    val startLogical = dragStartLogicalPoint
                    val pressedFocus = pressedId?.let { findFocusData(it) }
                    if (pressedFocus != null && canDragFocus(pressedFocus) && startLogical != null) {
                        if (!draggingFocus) {
                            cancelPendingSingleClick()
                            hideFocusHint(clearLocked = true)
                            selectedFocusId = pressedFocus.id
                            draggingFocus = true
                        }
                        val logicalPt = screenToLogical(e)
                        val cellW = (nodeWidth + hGap).coerceAtLeast(1)
                        val cellH = (nodeHeight + vGap).coerceAtLeast(1)
                        dragGridDeltaX = Math.round((logicalPt.x - startLogical.x).toDouble() / cellW).toInt()
                        dragGridDeltaY = Math.round((logicalPt.y - startLogical.y).toDouble() / cellH).toInt()
                        cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                        repaint()
                        return
                    }

                    val scrollStart = dragScrollStart ?: return
                    if (!draggingView) cancelPendingSingleClick()
                    draggingView = true
                    cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                    setScrollBarValue(scrollableHsb, scrollStart.x - screenDx)
                    setScrollBarValue(scrollableVsb, scrollStart.y - screenDy)
                }
            })

            addMouseWheelListener { e: MouseWheelEvent ->
                val oldZoom = zoomFactor
                val rotation = -e.wheelRotation
                zoomFactor *= Math.pow(1.15, rotation.toDouble())
                zoomFactor = zoomFactor.coerceIn(0.15, 4.0)

                val hsb = scrollableHsb
                val vsb = scrollableVsb
                val oldVx = hsb?.value ?: 0
                val oldVy = vsb?.value ?: 0

                revalidate()

                val scaleRatio = zoomFactor / oldZoom
                val newVx = (e.x * scaleRatio - (e.x - oldVx)).toInt().coerceAtLeast(0)
                val newVy = (e.y * scaleRatio - (e.y - oldVy)).toInt().coerceAtLeast(0)
                setScrollBarValue(hsb, newVx)
                setScrollBarValue(vsb, newVy)
                repaint()
            }
        }

        override fun updateUI() {
            super.updateUI()
            background = JBColor.PanelBackground
        }

        override fun getToolTipText(event: MouseEvent?): String? {
            if (lockedFocusId != null) return null
            if (event == null) return null
            val logicalPt = screenToLogical(event)
            val focus = findFocusAt(logicalPt) ?: return null
            return buildFocusHintText(focus)
        }

        private fun canDragFocus(focus: FocusData): Boolean {
            if (focus.sourceFilePath.isNullOrBlank()) return false
            if (focus.id.isBlank()) return false
            return true
        }

        private fun findFocusData(id: String): FocusData? {
            for (tree in workingTrees) {
                tree.focuses.firstOrNull { it.id == id }?.let { return it }
                tree.sharedFocuses.firstOrNull { it.id == id }?.let { return it }
            }
            return null
        }

        private fun effectiveGrid(focus: FocusData): Point {
            val baseX = focus.x.toInt()
            val baseY = focus.y.toInt()
            if (draggingFocus && focus.id == pressedFocusId) {
                return Point(baseX + dragGridDeltaX, baseY + dragGridDeltaY)
            }
            return Point(baseX, baseY)
        }

        private fun commitFocusDrag(focus: FocusData, newX: Int, newY: Int) {
            if (focus.x.toInt() == newX && focus.y.toInt() == newY) {
                repaint()
                return
            }
            val ok = service.updateFocusPosition(focus, newX, newY)
            if (!ok) {
                repaint()
                return
            }
            workingTrees = workingTrees.map { tree ->
                tree.copy(
                    focuses = tree.focuses.map { if (it.id == focus.id) it.copy(x = newX.toDouble(), y = newY.toDouble()) else it },
                    sharedFocuses = tree.sharedFocuses.map { if (it.id == focus.id) it.copy(x = newX.toDouble(), y = newY.toDouble()) else it }
                )
            }
            selectedFocusId = focus.id
            revalidate()
            repaint()
        }

        private fun setScrollBarValue(scrollBar: javax.swing.JScrollBar?, value: Int) {
            if (scrollBar == null) return
            val max = scrollBar.maximum - scrollBar.visibleAmount
            scrollBar.value = value.coerceIn(scrollBar.minimum, max.coerceAtLeast(scrollBar.minimum))
        }

        private fun navigateToFocus(focus: FocusData) {
            val resolved = service.resolveFocusData(focus)
            val path = resolved.sourceFilePath ?: return
            val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(path) ?: return
            if (resolved.sourceLine > 0) {
                OpenFileDescriptor(project, vf, resolved.sourceLine - 1, 0).navigate(true)
            } else {
                OpenFileDescriptor(project, vf).navigate(true)
            }
        }

        private fun scheduleFocusHint(focus: FocusData, point: Point) {
            cancelPendingSingleClick()
            val hintPoint = Point(point)
            singleClickTimer = Timer(PreviewHintSupport.multiClickInterval()) {
                singleClickTimer = null
                if (isShowing) showFocusHint(focus, hintPoint)
            }.apply {
                isRepeats = false
                start()
            }
        }

        private fun cancelPendingSingleClick() {
            singleClickTimer?.stop()
            singleClickTimer = null
        }

        private fun showFocusHint(focus: FocusData, point: Point) {
            hideFocusHint(clearLocked = false)
            selectedFocusId = focus.id
            lockedFocusId = focus.id

            val hint = PreviewHintSupport.showHint(this, point, buildFocusHintText(focus)) { hiddenHint ->
                if (lockedHint === hiddenHint) {
                    lockedHint = null
                    lockedFocusId = null
                    repaint()
                }
            }
            lockedHint = hint
        }

        private fun hideFocusHint(clearLocked: Boolean) {
            val hint = lockedHint
            lockedHint = null
            PreviewHintSupport.hideHint(hint)
            if (clearLocked) lockedFocusId = null
        }

        private fun buildFocusHintText(focus: FocusData): String {
            val resolved = service.resolveFocusData(focus)
            val definitionType = if (resolved.isSharedFocus) "shared_focus" else "focus"
            val sb = StringBuilder()
            sb.append("<html><table width='").append(HINT_WIDTH).append("' cellspacing='0' cellpadding='2'>")
            sb.append("<tr><td><font color='#808080'>&lt;").append(definitionType).append("&gt;</font> ")
            sb.append("<b>").append(esc(resolved.displayName)).append("</b></td></tr>")
            sb.append("<tr><td><font color='#808080' size='-2'>ID: ").append(esc(resolved.id))
                .append("</font></td></tr>")
            val description = resolved.localizedDescription ?: resolved.text
            if (description != null) {
                sb.append("<tr><td><br><font color='#99ccff'><i>").append(esc(description))
                    .append("</i></font></td></tr>")
            }
            sb.append("<tr><td><br><table cellspacing='0' cellpadding='2'>")
            appendHintRow(sb, "Cost", resolved.cost.toInt().toString())
            if (resolved.relativePositionId != null) {
                appendHintRow(
                    sb,
                    "Pos",
                    "rel(${resolved.x.toInt()}, ${resolved.y.toInt()}) from ${esc(resolved.relativePositionId)}"
                )
            } else {
                appendHintRow(sb, "Pos", "(${resolved.x.toInt()}, ${resolved.y.toInt()})")
            }
            if (resolved.prerequisites.isNotEmpty()) {
                appendHintRow(sb, "Req", esc(resolved.prerequisites.joinToString(", ")))
            }
            if (resolved.mutuallyExclusive.isNotEmpty()) {
                appendHintRow(sb, "MutExc", esc(resolved.mutuallyExclusive.joinToString(", ")))
            }
            if (resolved.aiWillDo != null) {
                appendHintRow(sb, "AI", "%.0f%%".format(resolved.aiWillDo * 100))
            }
            if (resolved.isSharedFocus) {
                appendHintRow(sb, "Type", "Shared focus")
            }
            sb.append("</table></td></tr>")
            if (resolved.completeTooltip != null) {
                sb.append("<tr><td><br>").append(esc(resolved.completeTooltip)).append("</td></tr>")
            }
            sb.append("</table></html>")
            return sb.toString()
        }

        private fun appendHintRow(sb: StringBuilder, label: String, value: String) {
            sb.append("<tr><td><font color='#808080'>")
                .append(label)
                .append(":</font></td><td>")
                .append(value)
                .append("</td></tr>")
        }

        private fun esc(s: String): String =
            s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int {
            return JBUIScale.scale(20)
        }

        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int {
            return if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width
        }

        override fun getScrollableTracksViewportWidth(): Boolean = false
        override fun getScrollableTracksViewportHeight(): Boolean = false

        override fun getPreferredSize(): Dimension {
            if (workingTrees.isEmpty()) return Dimension(400, 300)
            val logicalSize = computeLogicalSize()
            val w = ((logicalSize.width + padding) * zoomFactor + padding).toInt() + 50
            val h = ((logicalSize.height + padding) * zoomFactor + padding).toInt() + 50
            return Dimension(w.coerceAtLeast(400), h.coerceAtLeast(300))
        }

        private fun computeLogicalSize(): Dimension {
            var maxW = 400
            var maxH = 300
            var currentY = padding
            for (tree in workingTrees) {
                val allFocuses = tree.focuses + tree.sharedFocuses
                if (allFocuses.isEmpty()) continue
                val positions = computeRawPositions(allFocuses)
                val minX = positions.values.minOfOrNull { it.x } ?: 0
                val maxX = positions.values.maxOfOrNull { it.x } ?: 0
                val maxY = positions.values.maxOfOrNull { it.y } ?: 0
                val w = maxX - minX + nodeWidth + padding * 2
                val h = maxY + nodeHeight + padding * 2
                if (w > maxW) maxW = w
                if (h > maxH) maxH = h
                currentY += (positions.values.maxOfOrNull { it.y } ?: 0) + nodeHeight + padding
            }
            return Dimension(maxW, maxH)
        }

        private fun findFocusAt(p: Point): FocusData? {
            for ((id, bounds) in logicalNodeBounds) {
                if (bounds.contains(p)) return allFocusesList.find { it.id == id }
            }
            return null
        }

        private fun screenToLogical(e: MouseEvent): Point {
            return Point((e.x / zoomFactor).toInt(), (e.y / zoomFactor).toInt())
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2d = g.create() as Graphics2D
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val originalTransform = g2d.transform
            val at = AffineTransform()
            at.scale(zoomFactor, zoomFactor)
            g2d.transform(at)
            logicalPositions.clear()
            logicalNodeBounds.clear()
            allFocusesList.clear()
            var offsetY = padding
            for (tree in workingTrees) {
                val allFocuses = tree.focuses + tree.sharedFocuses
                if (allFocuses.isEmpty()) continue
                allFocusesList.addAll(allFocuses)
                val rawPositions = computeRawPositions(allFocuses)
                val minRawX = rawPositions.values.minOfOrNull { it.x } ?: 0
                val minRawY = rawPositions.values.minOfOrNull { it.y } ?: 0
                val shiftX = padding - minRawX
                val treeOffsetY = offsetY + padding - minRawY
                for ((focusId, rawPos) in rawPositions) {
                    val px = rawPos.x + shiftX
                    val py = rawPos.y + treeOffsetY
                    logicalPositions[focusId] = Point(px, py)
                    logicalNodeBounds[focusId] = Rectangle(px, py, nodeWidth, nodeHeight)
                }
                drawMutuallyExclusive(g2d, allFocuses)
                drawPrerequisiteConnections(g2d, allFocuses)
                drawFocusNodes(g2d, allFocuses)
                val maxRawY = rawPositions.values.maxOfOrNull { it.y } ?: 0
                offsetY += (maxRawY - minRawY) + nodeHeight + padding * 2
            }
            g2d.transform = originalTransform
            g2d.dispose()
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
                            relPos.x + grid.x * (nodeWidth + hGap),
                            relPos.y + grid.y * (nodeHeight + vGap)
                        )
                        positions[focus.id] = pt
                        resolved.add(focus.id)
                        return pt
                    }
                }
                val grid = effectiveGrid(focus)
                val pt = Point(grid.x * (nodeWidth + hGap), grid.y * (nodeHeight + vGap))
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
                    val isSel = prereqId == selectedFocusId || focus.id == selectedFocusId
                    val isHov = prereqId == hoveredFocusId || focus.id == hoveredFocusId
                    g2d.color = if (isSel || isHov) PREREQUISITE_LINE_ACTIVE_COLOR else PREREQUISITE_LINE_COLOR
                    g2d.stroke = BasicStroke(if (isSel || isHov) JBUIScale.scale(2.5f) else JBUIScale.scale(1.5f))
                    val midY = (sy + endTop) / 2
                    val path = Path2D.Double()
                    path.moveTo(sx.toDouble(), sy.toDouble())
                    path.lineTo(sx.toDouble(), midY.toDouble())
                    path.lineTo(endCx.toDouble(), midY.toDouble())
                    path.lineTo(endCx.toDouble(), endTop.toDouble())
                    g2d.draw(path)
                    val aSize = JBUIScale.scale(8)
                    val arrow = Polygon()
                    arrow.addPoint(endCx, endTop)
                    arrow.addPoint(endCx - aSize / 2, endTop + aSize)
                    arrow.addPoint(endCx + aSize / 2, endTop + aSize)
                    g2d.fill(arrow)
                }
            }
        }

        private fun drawMutuallyExclusive(g2d: Graphics2D, focuses: List<FocusData>) {
            val seen = mutableSetOf<String>()
            for (focus in focuses) {
                for (meId in focus.mutuallyExclusive) {
                    val pairKey = if (focus.id < meId) "${focus.id}::${meId}" else "${meId}::${focus.id}"
                    if (pairKey in seen) continue
                    seen.add(pairKey)
                    val ptA = logicalPositions[focus.id] ?: continue
                    val ptB = logicalPositions[meId] ?: continue
                    val ax = ptA.x + nodeWidth / 2
                    val ay = ptA.y + nodeHeight / 2
                    val bx = ptB.x + nodeWidth / 2
                    val by = ptB.y + nodeHeight / 2
                    val isHov = focus.id == hoveredFocusId || meId == hoveredFocusId ||
                            focus.id == selectedFocusId || meId == selectedFocusId
                    g2d.color = if (isHov) MUTUALLY_EXCLUSIVE_ACTIVE_COLOR else MUTUALLY_EXCLUSIVE_COLOR
                    g2d.stroke = BasicStroke(
                        if (isHov) JBUIScale.scale(2.5f) else JBUIScale.scale(1.5f),
                        BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL,
                        0f, floatArrayOf(JBUIScale.scale(6f), JBUIScale.scale(4f)), 0f
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

        private fun loadIcon(path: String): BufferedImage? {
            return PreviewImageLoader.load(path, iconCache)
        }

        private fun drawFocusNodes(g2d: Graphics2D, focuses: List<FocusData>) {
            val sortedFocuses = focuses.map { focus -> service.resolveFocusData(focus) }
            for (focus in sortedFocuses) {
                val pos = logicalPositions[focus.id] ?: continue
                val isSel = focus.id == selectedFocusId
                val isHov = focus.id == hoveredFocusId
                val isShared = focus.isSharedFocus

                val bgColor = when {
                    isSel -> NODE_SELECTED_BACKGROUND
                    isHov -> NODE_HOVER_BACKGROUND
                    isShared -> NODE_SHARED_BACKGROUND
                    else -> NODE_BACKGROUND
                }
                val borderColor = when {
                    isSel -> NODE_SELECTED_BORDER
                    isHov -> NODE_HOVER_BORDER
                    isShared -> NODE_SHARED_BORDER
                    else -> NODE_BORDER
                }
                val bw = if (isSel || isHov) JBUIScale.scale(2.5f) else JBUIScale.scale(1.5f)

                val arc = JBUIScale.scale(8).toDouble()
                val rr = RoundRectangle2D.Double(
                    pos.x.toDouble(),
                    pos.y.toDouble(),
                    nodeWidth.toDouble(),
                    nodeHeight.toDouble(),
                    arc,
                    arc
                )
                g2d.color = bgColor
                g2d.fill(rr)
                g2d.color = borderColor
                g2d.stroke = BasicStroke(bw)
                g2d.draw(rr)

                val iconSz = JBUIScale.scale(72)
                val iconX = pos.x + (nodeWidth - iconSz) / 2
                val iconY = pos.y + JBUIScale.scale(9)
                val iconArc = JBUIScale.scale(6).toDouble()
                val iconRR = RoundRectangle2D.Double(
                    iconX.toDouble(),
                    iconY.toDouble(),
                    iconSz.toDouble(),
                    iconSz.toDouble(),
                    iconArc,
                    iconArc
                )

                val img = focus.iconImagePath?.let { loadIcon(it) }
                if (img != null) {
                    val oldClip = g2d.clip
                    g2d.clip = intersectClip(oldClip, iconRR)
                    g2d.drawImage(img, iconX, iconY, iconSz, iconSz, null)
                    g2d.clip = oldClip
                    g2d.color = borderColor
                    g2d.stroke = BasicStroke(JBUIScale.scale(0.5f))
                    g2d.draw(iconRR)
                } else {
                    g2d.color = ICON_FALLBACK_BACKGROUND
                    g2d.fill(iconRR)
                    if (focus.iconKey != null) {
                        g2d.color = ICON_FALLBACK_TEXT
                        g2d.font = focusIconFallbackFont()
                        val txt = if (focus.iconKey.length > 8) focus.iconKey.take(6) + ".." else focus.iconKey
                        val ifm = g2d.fontMetrics
                        g2d.drawString(
                            txt,
                            iconX + (iconSz - ifm.stringWidth(txt)) / 2,
                            iconY + iconSz / 2 + ifm.ascent / 3
                        )
                    }
                }

                val titleBandHeight = JBUIScale.scale(38)
                val titleBandY = pos.y + nodeHeight - titleBandHeight
                val titleShape = RoundRectangle2D.Double(
                    pos.x.toDouble(),
                    titleBandY.toDouble(),
                    nodeWidth.toDouble(),
                    titleBandHeight.toDouble(),
                    arc,
                    arc
                )
                g2d.color = TITLE_BAND_BACKGROUND
                g2d.fill(titleShape)
                g2d.color = borderColor
                g2d.stroke = BasicStroke(JBUIScale.scale(0.8f))
                g2d.drawLine(pos.x, titleBandY, pos.x + nodeWidth, titleBandY)

                if (isShared) {
                    g2d.color = SHARED_LABEL_COLOR
                    g2d.fillOval(
                        pos.x + nodeWidth - JBUIScale.scale(13),
                        pos.y + JBUIScale.scale(5),
                        JBUIScale.scale(8),
                        JBUIScale.scale(8)
                    )
                }

                g2d.color = NODE_TITLE_COLOR
                g2d.font = focusTitleFont()
                val name = focus.displayName
                val titleRect = Rectangle(
                    pos.x + JBUIScale.scale(8),
                    titleBandY + JBUIScale.scale(4),
                    nodeWidth - JBUIScale.scale(16),
                    titleBandHeight - JBUIScale.scale(8)
                )
                drawCenteredTitle(g2d, name, titleRect)
            }
        }

        private fun intersectClip(currentClip: Shape?, shape: Shape): Shape {
            if (currentClip == null) return shape
            val area = Area(currentClip)
            area.intersect(Area(shape))
            return area
        }

        private fun focusTitleFont(): Font = JBFont.label().deriveFont(Font.BOLD, 12f)

        private fun focusIconFallbackFont(): Font = JBFont.label().deriveFont(Font.PLAIN, 10f)

        private fun drawCenteredTitle(g2d: Graphics2D, text: String, rect: Rectangle) {
            val lines = splitTextToLines(g2d, text, rect.width)
            val fm = g2d.fontMetrics
            val lineHeight = fm.height - fm.leading
            var y = rect.y + (rect.height - lineHeight * lines.size) / 2 + fm.ascent
            for (line in lines) {
                val x = rect.x + (rect.width - fm.stringWidth(line)) / 2
                g2d.drawString(line, x, y)
                y += lineHeight
            }
        }

        private fun splitTextToLines(g2d: Graphics2D, text: String, maxW: Int): List<String> {
            val chars = text.trim().codePoints().toArray().map { String(Character.toChars(it)) }
            if (chars.isEmpty()) return emptyList()

            val lines = mutableListOf<String>()
            var line = ""
            var index = 0
            while (index < chars.size) {
                val next = line + chars[index]
                if (line.isNotEmpty() && g2d.fontMetrics.stringWidth(next) > maxW) {
                    lines.add(line.trim())
                    line = ""
                    if (lines.size == TITLE_MAX_LINES - 1) break
                } else {
                    line = next
                    index++
                }
            }

            val rest = (line + chars.drop(index).joinToString("")).trim()
            if (rest.isNotEmpty() && lines.size < TITLE_MAX_LINES) {
                lines.add(if (g2d.fontMetrics.stringWidth(rest) > maxW) truncateStr(g2d, rest, maxW) else rest)
            }
            return lines.ifEmpty { listOf(truncateStr(g2d, text, maxW)) }
        }

        private fun truncateStr(g2d: Graphics2D, text: String, maxW: Int): String {
            if (g2d.fontMetrics.stringWidth(text) <= maxW) return text
            var r = text
            while (r.isNotEmpty() && g2d.fontMetrics.stringWidth("$r...") > maxW) r = r.dropLast(1)
            return "$r..."
        }

        override fun removeNotify() {
            ToolTipManager.sharedInstance().unregisterComponent(this)
            cancelPendingSingleClick()
            hideFocusHint(clearLocked = true)
            super.removeNotify()
            iconCache.clear()
        }

        companion object {
            private val PREREQUISITE_LINE_COLOR = JBColor(0x4F6F8A, 0x508CB4)
            private val PREREQUISITE_LINE_ACTIVE_COLOR = JBColor(0x1676B8, 0x78C8FF)
            private val MUTUALLY_EXCLUSIVE_COLOR = JBColor(0xB64A4A, 0xB43C3C)
            private val MUTUALLY_EXCLUSIVE_ACTIVE_COLOR = JBColor(0xD33737, 0xFF6464)
            private val NODE_BACKGROUND = JBColor(0xEEF3F8, 0x374150)
            private val NODE_HOVER_BACKGROUND = JBColor(0xE2EEF8, 0x3C4B5A)
            private val NODE_SELECTED_BACKGROUND = JBColor(0xD7EAF8, 0x415064)
            private val NODE_SHARED_BACKGROUND = JBColor(0xE0EEF9, 0x466482)
            private val NODE_BORDER = JBColor(0x5B86B2, 0x5A8CBE)
            private val NODE_HOVER_BORDER = JBColor(0x3485C7, 0x82BEF0)
            private val NODE_SELECTED_BORDER = JBColor(0x0D75BD, 0x96D2FF)
            private val NODE_SHARED_BORDER = JBColor(0x3476B4, 0x78AADC)
            private val SHARED_LABEL_COLOR = JBColor(0x236FA8, 0x64B4F0)
            private val ICON_FALLBACK_BACKGROUND = JBColor(0xD7DFE8, 0x46505F)
            private val ICON_FALLBACK_TEXT = JBColor(0x465C72, 0xB4C8DC)
            private val TITLE_BAND_BACKGROUND = JBColor(0xE3EAF1, 0x2B3340)
            private val NODE_TITLE_COLOR = JBColor(0x1F2328, 0xDCDCDC)
            private const val TITLE_MAX_LINES = 2
            private const val HINT_WIDTH = 340
        }
    }
}
