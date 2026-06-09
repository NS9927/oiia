package net.posdaca.oiia.technology

import com.intellij.codeInsight.hint.HintUtil
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.ui.HintHint
import com.intellij.ui.JBColor
import com.intellij.ui.LightweightHint
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBFont
import net.posdaca.oiia.core.PreviewImageLoader
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.ToolTipManager

class TechnologyPreviewPanel(
    project: Project,
    technologyTrees: List<TechnologyTreeData>,
    service: TechnologyService
) : JBPanel<JBPanel<*>>(BorderLayout()) {

    private val canvas = TechnologyCanvas(project, technologyTrees, service)

    init {
        background = JBColor.PanelBackground
        val scrollPane = JBScrollPane(canvas)
        scrollPane.horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        scrollPane.verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        scrollPane.border = null
        scrollPane.viewport.background = JBColor.PanelBackground
        add(scrollPane, BorderLayout.CENTER)

        val allTechnologies = technologyTrees.flatMap { it.technologies }
        service.scheduleResolution(allTechnologies) { canvas.repaint() }
    }

    private class TechnologyCanvas(
        private val project: Project,
        private val technologyTrees: List<TechnologyTreeData>,
        private val service: TechnologyService
    ) : JBPanel<JBPanel<*>>(null), Scrollable {

        private val nodeWidth = JBUIScale.scale(132)
        private val nodeHeight = JBUIScale.scale(116)
        private val hGap = JBUIScale.scale(68)
        private val vGap = JBUIScale.scale(72)
        private val padding = JBUIScale.scale(40)
        private var zoomFactor = 1.0

        private var selectedTechnologyId: String? = null
        private var hoveredTechnologyId: String? = null
        private var lockedTechnologyId: String? = null
        private var lockedHint: LightweightHint? = null
        private var dragPressScreenPoint: Point? = null
        private var dragScrollStart: Point? = null
        private var pressedTechnologyId: String? = null
        private var draggingView = false
        private var singleClickTimer: Timer? = null

        private val logicalPositions = mutableMapOf<String, Point>()
        private val logicalNodeBounds = mutableMapOf<String, Rectangle>()
        private val allTechnologiesList = mutableListOf<TechnologyData>()
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
                        pressedTechnologyId = findTechnologyAt(logicalPt)?.id
                        dragPressScreenPoint = Point(e.locationOnScreen)
                        dragScrollStart = Point(scrollableHsb?.value ?: 0, scrollableVsb?.value ?: 0)
                        draggingView = false
                    }
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (e.button != MouseEvent.BUTTON1) return
                    val wasDragging = draggingView
                    cursor = Cursor.getDefaultCursor()
                    dragPressScreenPoint = null
                    dragScrollStart = null
                    draggingView = false

                    if (!wasDragging) {
                        val clicked = findTechnologyAt(screenToLogical(e))
                        if (clicked != null && clicked.id == pressedTechnologyId) {
                            selectedTechnologyId = clicked.id
                            if (e.clickCount >= 2) {
                                cancelPendingSingleClick()
                                hideTechnologyHint(clearLocked = true)
                                navigateToTechnology(clicked)
                            } else {
                                scheduleTechnologyHint(clicked, e.point)
                            }
                        } else if (clicked == null) {
                            cancelPendingSingleClick()
                            selectedTechnologyId = null
                            hideTechnologyHint(clearLocked = true)
                        }
                        repaint()
                    }
                    pressedTechnologyId = null
                }

                override fun mouseExited(e: MouseEvent) {
                    if (hoveredTechnologyId != null) {
                        hoveredTechnologyId = null
                        repaint()
                    }
                }
            })

            addMouseMotionListener(object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    val hovered = findTechnologyAt(screenToLogical(e))
                    val prevId = hoveredTechnologyId
                    hoveredTechnologyId = hovered?.id
                    if (prevId != hoveredTechnologyId) repaint()
                }

                override fun mouseDragged(e: MouseEvent) {
                    val pressPoint = dragPressScreenPoint ?: return
                    val scrollStart = dragScrollStart ?: return
                    val currentPoint = e.locationOnScreen
                    val dx = currentPoint.x - pressPoint.x
                    val dy = currentPoint.y - pressPoint.y
                    if (!draggingView && dx * dx + dy * dy < JBUIScale.scale(4) * JBUIScale.scale(4)) return

                    if (!draggingView) cancelPendingSingleClick()
                    draggingView = true
                    cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                    setScrollBarValue(scrollableHsb, scrollStart.x - dx)
                    setScrollBarValue(scrollableVsb, scrollStart.y - dy)
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
            if (lockedTechnologyId != null) return null
            if (event == null) return null
            val technology = findTechnologyAt(screenToLogical(event)) ?: return null
            return buildTechnologyHintText(technology)
        }

        private fun setScrollBarValue(scrollBar: javax.swing.JScrollBar?, value: Int) {
            if (scrollBar == null) return
            val max = scrollBar.maximum - scrollBar.visibleAmount
            scrollBar.value = value.coerceIn(scrollBar.minimum, max.coerceAtLeast(scrollBar.minimum))
        }

        private fun navigateToTechnology(technology: TechnologyData) {
            val resolved = service.resolveTechnologyData(technology)
            val path = resolved.sourceFilePath ?: return
            val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(path) ?: return
            if (resolved.sourceLine > 0) {
                OpenFileDescriptor(project, vf, resolved.sourceLine - 1, 0).navigate(true)
            } else {
                OpenFileDescriptor(project, vf).navigate(true)
            }
        }

        private fun scheduleTechnologyHint(technology: TechnologyData, point: Point) {
            cancelPendingSingleClick()
            val hintPoint = Point(point)
            singleClickTimer = Timer(multiClickInterval()) {
                singleClickTimer = null
                if (isShowing) showTechnologyHint(technology, hintPoint)
            }.apply {
                isRepeats = false
                start()
            }
        }

        private fun cancelPendingSingleClick() {
            singleClickTimer?.stop()
            singleClickTimer = null
        }

        private fun multiClickInterval(): Int {
            val interval = Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval") as? Int
            return (interval ?: 250).coerceAtLeast(150)
        }

        private fun showTechnologyHint(technology: TechnologyData, point: Point) {
            hideTechnologyHint(clearLocked = false)
            selectedTechnologyId = technology.id
            lockedTechnologyId = technology.id

            val content = HintUtil.createInformationLabel(buildTechnologyHintText(technology))
            content.border = BorderFactory.createCompoundBorder(
                HintUtil.createHintBorder(),
                BorderFactory.createEmptyBorder(
                    JBUIScale.scale(10),
                    JBUIScale.scale(12),
                    JBUIScale.scale(10),
                    JBUIScale.scale(12)
                )
            )
            content.background = HintUtil.getInformationColor()
            content.isOpaque = true

            val hint = LightweightHint(content)
            hint.setForceShowAsPopup(true)
            hint.setCancelOnClickOutside(true)
            hint.setCancelOnOtherWindowOpen(true)
            hint.addHintListener {
                if (lockedHint === hint) {
                    lockedHint = null
                    lockedTechnologyId = null
                    repaint()
                }
            }

            val hintPoint = computeHintPoint(point, content)
            val hintHint = HintHint(this, hintPoint)
                .setTextBg(HintUtil.getInformationColor())
                .setTextFg(JBColor.foreground())
                .setBorderColor(HintUtil.getHintBorderColor())
                .setShowImmediately(true)
            hint.show(this, hintPoint.x, hintPoint.y, this, hintHint)
            lockedHint = hint
        }

        private fun hideTechnologyHint(clearLocked: Boolean) {
            val hint = lockedHint
            lockedHint = null
            if (hint?.isVisible == true) hint.hide()
            if (clearLocked) lockedTechnologyId = null
        }

        private fun computeHintPoint(point: Point, content: JComponent): Point {
            val gap = JBUIScale.scale(12)
            val viewport = visibleRect
            val size = content.preferredSize
            val rightLimit = viewport.x + viewport.width - gap
            val bottomLimit = viewport.y + viewport.height - gap
            var x = point.x + gap
            var y = point.y + gap
            if (x + size.width > rightLimit) x = point.x - size.width - gap
            if (y + size.height > bottomLimit) y = point.y - size.height - gap
            return Point(x.coerceAtLeast(viewport.x + gap), y.coerceAtLeast(viewport.y + gap))
        }

        private fun buildTechnologyHintText(technology: TechnologyData): String {
            val resolved = service.resolveTechnologyData(technology)
            val sb = StringBuilder()
            sb.append("<html><table width='").append(HINT_WIDTH).append("' cellspacing='0' cellpadding='2'>")
            sb.append("<tr><td><font color='#808080'>&lt;technology&gt;</font> ")
            sb.append("<b>").append(esc(resolved.displayName)).append("</b></td></tr>")
            sb.append("<tr><td><font color='#808080' size='-2'>ID: ").append(esc(resolved.id))
                .append("</font></td></tr>")
            if (resolved.localizedDescription != null) {
                sb.append("<tr><td><br><font color='#99ccff'><i>")
                    .append(esc(resolved.localizedDescription))
                    .append("</i></font></td></tr>")
            }
            sb.append("<tr><td><br><table cellspacing='0' cellpadding='2'>")
            resolved.folderName?.let { appendHintRow(sb, "Folder", esc(it)) }
            resolved.startYear?.let { appendHintRow(sb, "Year", it.toString()) }
            appendHintRow(sb, "Pos", "(${resolved.x.toInt()}, ${resolved.y.toInt()})")
            if (resolved.categories.isNotEmpty()) appendHintRow(
                sb,
                "Categories",
                esc(resolved.categories.joinToString(", "))
            )
            if (resolved.leadsTo.isNotEmpty()) appendHintRow(sb, "Path", esc(resolved.leadsTo.joinToString(", ")))
            sb.append("</table></td></tr>")
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
            if (technologyTrees.isEmpty()) return Dimension(400, 300)
            val logicalSize = computeLogicalSize()
            val w = ((logicalSize.width + padding) * zoomFactor + padding).toInt() + 50
            val h = ((logicalSize.height + padding) * zoomFactor + padding).toInt() + 50
            return Dimension(w.coerceAtLeast(400), h.coerceAtLeast(300))
        }

        private fun computeLogicalSize(): Dimension {
            var maxW = 400
            var currentY = padding
            for (tree in technologyTrees) {
                if (tree.technologies.isEmpty()) continue
                val positions = computeRawPositions(tree.technologies)
                val minX = positions.values.minOfOrNull { it.x } ?: 0
                val maxX = positions.values.maxOfOrNull { it.x } ?: 0
                val minY = positions.values.minOfOrNull { it.y } ?: 0
                val maxY = positions.values.maxOfOrNull { it.y } ?: 0
                val w = maxX - minX + nodeWidth + padding * 2
                if (w > maxW) maxW = w
                currentY += (maxY - minY) + nodeHeight + padding * 2
            }
            return Dimension(maxW, currentY.coerceAtLeast(300))
        }

        private fun findTechnologyAt(p: Point): TechnologyData? {
            for ((id, bounds) in logicalNodeBounds) {
                if (bounds.contains(p)) return allTechnologiesList.find { it.id == id }
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
            allTechnologiesList.clear()
            var offsetY = padding
            for (tree in technologyTrees) {
                if (tree.technologies.isEmpty()) continue
                allTechnologiesList.addAll(tree.technologies)
                val rawPositions = computeRawPositions(tree.technologies)
                val minRawX = rawPositions.values.minOfOrNull { it.x } ?: 0
                val minRawY = rawPositions.values.minOfOrNull { it.y } ?: 0
                val shiftX = padding - minRawX
                val treeOffsetY = offsetY + padding - minRawY
                for ((technologyId, rawPos) in rawPositions) {
                    val px = rawPos.x + shiftX
                    val py = rawPos.y + treeOffsetY
                    logicalPositions[technologyId] = Point(px, py)
                    logicalNodeBounds[technologyId] = Rectangle(px, py, nodeWidth, nodeHeight)
                }
                drawTreeTitle(g2d, tree.folderName, padding, offsetY + JBUIScale.scale(14))
                drawPathConnections(g2d, tree.technologies)
                drawTechnologyNodes(g2d, tree.technologies)
                val maxRawY = rawPositions.values.maxOfOrNull { it.y } ?: 0
                offsetY += (maxRawY - minRawY) + nodeHeight + padding * 2
            }
            g2d.transform = originalTransform
            g2d.dispose()
        }

        private fun computeRawPositions(technologies: List<TechnologyData>): Map<String, Point> {
            val positions = mutableMapOf<String, Point>()
            for (technology in technologies) {
                positions[technology.id] = Point(
                    technology.x.toInt() * (nodeWidth + hGap),
                    technology.y.toInt() * (nodeHeight + vGap)
                )
            }
            return positions
        }

        private fun drawTreeTitle(g2d: Graphics2D, title: String, x: Int, y: Int) {
            g2d.font = JBFont.label().deriveFont(Font.BOLD, 13f)
            g2d.color = TREE_TITLE_COLOR
            g2d.drawString(title, x, y)
        }

        private fun drawPathConnections(g2d: Graphics2D, technologies: List<TechnologyData>) {
            for (technology in technologies) {
                val startPt = logicalPositions[technology.id] ?: continue
                val sx = startPt.x + nodeWidth / 2
                val sy = startPt.y + nodeHeight
                for (nextId in technology.leadsTo) {
                    val endPt = logicalPositions[nextId] ?: continue
                    val endCx = endPt.x + nodeWidth / 2
                    val endTop = endPt.y
                    val isActive = technology.id == selectedTechnologyId || nextId == selectedTechnologyId ||
                            technology.id == hoveredTechnologyId || nextId == hoveredTechnologyId
                    g2d.color = if (isActive) PATH_ACTIVE_COLOR else PATH_COLOR
                    g2d.stroke = BasicStroke(if (isActive) JBUIScale.scale(2.5f) else JBUIScale.scale(1.5f))
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

        private fun loadIcon(path: String): BufferedImage? {
            return PreviewImageLoader.load(path, iconCache)
        }

        private fun drawTechnologyNodes(g2d: Graphics2D, technologies: List<TechnologyData>) {
            for (technology in technologies.map { service.resolveTechnologyData(it) }) {
                val pos = logicalPositions[technology.id] ?: continue
                val isSel = technology.id == selectedTechnologyId
                val isHov = technology.id == hoveredTechnologyId
                val bgColor = when {
                    isSel -> NODE_SELECTED_BACKGROUND
                    isHov -> NODE_HOVER_BACKGROUND
                    else -> NODE_BACKGROUND
                }
                val borderColor = when {
                    isSel -> NODE_SELECTED_BORDER
                    isHov -> NODE_HOVER_BORDER
                    else -> NODE_BORDER
                }
                val bw = if (isSel || isHov) JBUIScale.scale(2.5f) else JBUIScale.scale(1.5f)

                val arc = JBUIScale.scale(8).toDouble()
                val rr = RoundRectangle2D.Double(
                    pos.x.toDouble(), pos.y.toDouble(), nodeWidth.toDouble(), nodeHeight.toDouble(), arc, arc
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
                    iconX.toDouble(), iconY.toDouble(), iconSz.toDouble(), iconSz.toDouble(), iconArc, iconArc
                )
                val img = technology.iconImagePath?.let { loadIcon(it) }
                if (img != null) {
                    val oldClip = g2d.clip
                    g2d.setClip(iconRR)
                    g2d.drawImage(img, iconX, iconY, iconSz, iconSz, null)
                    g2d.clip = oldClip
                    g2d.color = borderColor
                    g2d.stroke = BasicStroke(JBUIScale.scale(0.5f))
                    g2d.draw(iconRR)
                } else {
                    g2d.color = ICON_FALLBACK_BACKGROUND
                    g2d.fill(iconRR)
                    g2d.color = ICON_FALLBACK_TEXT
                    g2d.font = JBFont.label().deriveFont(Font.PLAIN, 10f)
                    val txt = technology.id.take(8)
                    val ifm = g2d.fontMetrics
                    g2d.drawString(
                        txt,
                        iconX + (iconSz - ifm.stringWidth(txt)) / 2,
                        iconY + iconSz / 2 + ifm.ascent / 3
                    )
                }

                val titleBandHeight = JBUIScale.scale(38)
                val titleBandY = pos.y + nodeHeight - titleBandHeight
                val titleShape = RoundRectangle2D.Double(
                    pos.x.toDouble(), titleBandY.toDouble(), nodeWidth.toDouble(), titleBandHeight.toDouble(), arc, arc
                )
                g2d.color = TITLE_BAND_BACKGROUND
                g2d.fill(titleShape)
                g2d.color = borderColor
                g2d.stroke = BasicStroke(JBUIScale.scale(0.8f))
                g2d.drawLine(pos.x, titleBandY, pos.x + nodeWidth, titleBandY)

                g2d.color = NODE_TITLE_COLOR
                g2d.font = JBFont.label().deriveFont(Font.BOLD, 12f)
                val titleRect = Rectangle(
                    pos.x + JBUIScale.scale(8),
                    titleBandY + JBUIScale.scale(4),
                    nodeWidth - JBUIScale.scale(16),
                    titleBandHeight - JBUIScale.scale(8)
                )
                drawCenteredTitle(g2d, technology.displayName, titleRect)
            }
        }

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
            hideTechnologyHint(clearLocked = true)
            super.removeNotify()
            iconCache.clear()
        }

        companion object {
            private val PATH_COLOR = JBColor(0x4F6F8A, 0x508CB4)
            private val PATH_ACTIVE_COLOR = JBColor(0x1676B8, 0x78C8FF)
            private val TREE_TITLE_COLOR = JBColor(0x345A7C, 0xA6C8E8)
            private val NODE_BACKGROUND = JBColor(0xEEF3F8, 0x374150)
            private val NODE_HOVER_BACKGROUND = JBColor(0xE2EEF8, 0x3C4B5A)
            private val NODE_SELECTED_BACKGROUND = JBColor(0xD7EAF8, 0x415064)
            private val NODE_BORDER = JBColor(0x5B86B2, 0x5A8CBE)
            private val NODE_HOVER_BORDER = JBColor(0x3485C7, 0x82BEF0)
            private val NODE_SELECTED_BORDER = JBColor(0x0D75BD, 0x96D2FF)
            private val ICON_FALLBACK_BACKGROUND = JBColor(0xD7DFE8, 0x46505F)
            private val ICON_FALLBACK_TEXT = JBColor(0x465C72, 0xB4C8DC)
            private val TITLE_BAND_BACKGROUND = JBColor(0xE3EAF1, 0x2B3340)
            private val NODE_TITLE_COLOR = JBColor(0x1F2328, 0xDCDCDC)
            private const val TITLE_MAX_LINES = 2
            private const val HINT_WIDTH = 340
        }
    }
}
