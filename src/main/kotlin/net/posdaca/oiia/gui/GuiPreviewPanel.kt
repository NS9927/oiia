package net.posdaca.oiia.gui

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBFont
import net.posdaca.OiiaBundle
import net.posdaca.oiia.core.PreviewImageLoader
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

class GuiPreviewPanel(
    project: Project,
    previewFile: GuiPreviewFile,
    service: GuiPreviewService
) : JBPanel<JBPanel<*>>(BorderLayout()) {

    private val roots = previewFile.roots
    private val selector = ComboBox(roots.toTypedArray())
    private val statusLabel = JBLabel()
    private val canvas = GuiCanvas(project, service)

    init {
        background = JBColor.PanelBackground
        add(createToolbar(), BorderLayout.NORTH)

        val scrollPane = JBScrollPane(canvas)
        scrollPane.border = null
        scrollPane.viewport.background = JBColor.PanelBackground
        add(scrollPane, BorderLayout.CENTER)

        if (roots.isNotEmpty()) {
            selector.selectedItem = roots.first()
            canvas.setRoot(roots.first())
        }
        updateStatus()
    }

    private fun createToolbar(): JComponent {
        val panel = JPanel(BorderLayout(JBUIScale.scale(8), 0))
        panel.background = JBColor.PanelBackground
        panel.border = BorderFactory.createEmptyBorder(
            JBUIScale.scale(6),
            JBUIScale.scale(8),
            JBUIScale.scale(6),
            JBUIScale.scale(8)
        )

        statusLabel.font = JBFont.label()
        panel.add(statusLabel, BorderLayout.CENTER)

        selector.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): java.awt.Component {
                val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                if (value is GuiElement) text = displayName(value)
                return component
            }
        }
        selector.addActionListener {
            val root = selector.selectedItem as? GuiElement ?: return@addActionListener
            canvas.setRoot(root)
            updateStatus()
        }

        val actions = JPanel()
        actions.isOpaque = false
        actions.add(JBLabel(OiiaBundle.message("toolwindow.GuiPreview.root")))
        actions.add(selector)
        panel.add(actions, BorderLayout.EAST)
        return panel
    }

    private fun updateStatus() {
        val selected = selector.selectedItem as? GuiElement
        statusLabel.text = if (selected == null) {
            OiiaBundle.message("toolwindow.GuiPreview.no.container")
        } else {
            OiiaBundle.message(
                "toolwindow.GuiPreview.status",
                displayName(selected),
                selected.children.size
            )
        }
    }

    private fun displayName(element: GuiElement): String {
        return element.properties["previewDisplayName"] ?: element.displayName
    }

    private class GuiCanvas(
        private val project: Project,
        private val service: GuiPreviewService
    ) : JBPanel<JBPanel<*>>(null), Scrollable {

        private val padding = JBUIScale.scale(48)
        private val imageCache = mutableMapOf<String, BufferedImage>()
        private val spritePathCache = mutableMapOf<String, String?>()
        private val localisationCache = mutableMapOf<String, String?>()
        private var root: GuiElement? = null
        private var nodes = emptyList<GuiLayoutNode>()
        private var logicalSize = Dimension(JBUIScale.scale(800), JBUIScale.scale(520))
        private var zoomFactor = 1.0
        private var hovered: GuiLayoutNode? = null
        private var selected: GuiLayoutNode? = null
        private var dragPressScreenPoint: Point? = null
        private var dragScrollStart: Point? = null
        private var pressedNode: GuiLayoutNode? = null
        private var draggingView = false

        private val scrollableHsb: javax.swing.JScrollBar?
            get() = (SwingUtilities.getAncestorOfClass(JBScrollPane::class.java, this) as? JBScrollPane)?.horizontalScrollBar
        private val scrollableVsb: javax.swing.JScrollBar?
            get() = (SwingUtilities.getAncestorOfClass(JBScrollPane::class.java, this) as? JBScrollPane)?.verticalScrollBar

        init {
            isOpaque = true
            background = JBColor.PanelBackground
            ToolTipManager.sharedInstance().registerComponent(this)
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (e.button != MouseEvent.BUTTON1) return
                    val logicalPoint = screenToLogical(e.point)
                    pressedNode = findNodeAt(logicalPoint)
                    dragPressScreenPoint = Point(e.locationOnScreen)
                    dragScrollStart = Point(scrollableHsb?.value ?: 0, scrollableVsb?.value ?: 0)
                    draggingView = false
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (e.button != MouseEvent.BUTTON1) return
                    val wasDragging = draggingView
                    cursor = Cursor.getDefaultCursor()
                    dragPressScreenPoint = null
                    dragScrollStart = null
                    draggingView = false

                    if (!wasDragging) {
                        val clicked = findNodeAt(screenToLogical(e.point))
                        if (clicked != null && clicked.element == pressedNode?.element) {
                            selected = clicked
                            if (e.clickCount >= 2) navigateTo(clicked.element)
                        } else if (clicked == null) {
                            selected = null
                        }
                        repaint()
                    }
                    pressedNode = null
                }

                override fun mouseExited(e: MouseEvent) {
                    if (hovered != null) {
                        hovered = null
                        repaint()
                    }
                }
            })

            addMouseMotionListener(object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    val next = findNodeAt(screenToLogical(e.point))
                    if (next?.element != hovered?.element) {
                        hovered = next
                        repaint()
                    }
                }

                override fun mouseDragged(e: MouseEvent) {
                    val pressPoint = dragPressScreenPoint ?: return
                    val scrollStart = dragScrollStart ?: return
                    val dx = e.locationOnScreen.x - pressPoint.x
                    val dy = e.locationOnScreen.y - pressPoint.y
                    val threshold = JBUIScale.scale(4)
                    if (!draggingView && dx * dx + dy * dy < threshold * threshold) return
                    draggingView = true
                    cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                    setScrollBarValue(scrollableHsb, scrollStart.x - dx)
                    setScrollBarValue(scrollableVsb, scrollStart.y - dy)
                }
            })

            addMouseWheelListener { e: MouseWheelEvent ->
                val oldZoom = zoomFactor
                zoomFactor *= 1.15.pow(-e.wheelRotation.toDouble())
                zoomFactor = zoomFactor.coerceIn(0.2, 4.0)

                val hsb = scrollableHsb
                val vsb = scrollableVsb
                val oldVx = hsb?.value ?: 0
                val oldVy = vsb?.value ?: 0
                revalidate()

                val scaleRatio = zoomFactor / oldZoom
                setScrollBarValue(hsb, (e.x * scaleRatio - (e.x - oldVx)).toInt())
                setScrollBarValue(vsb, (e.y * scaleRatio - (e.y - oldVy)).toInt())
                repaint()
            }
        }

        fun setRoot(nextRoot: GuiElement) {
            root = nextRoot
            nodes = layout(nextRoot)
            logicalSize = computeLogicalSize(nodes)
            selected = null
            hovered = null
            revalidate()
            repaint()
            SwingUtilities.invokeLater {
                setScrollBarValue(scrollableHsb, 0)
                setScrollBarValue(scrollableVsb, 0)
            }
        }

        override fun updateUI() {
            super.updateUI()
            background = JBColor.PanelBackground
        }

        override fun getPreferredSize(): Dimension {
            return Dimension(
                (logicalSize.width * zoomFactor).roundToInt(),
                (logicalSize.height * zoomFactor).roundToInt()
            )
        }

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

        override fun getScrollableUnitIncrement(visibleRect: Rectangle?, orientation: Int, direction: Int): Int {
            return JBUIScale.scale(32)
        }

        override fun getScrollableBlockIncrement(visibleRect: Rectangle?, orientation: Int, direction: Int): Int {
            return JBUIScale.scale(160)
        }

        override fun getScrollableTracksViewportWidth(): Boolean = false

        override fun getScrollableTracksViewportHeight(): Boolean = false

        override fun getToolTipText(event: MouseEvent?): String? {
            val node = event?.point?.let { findNodeAt(screenToLogical(it)) } ?: return null
            return buildTooltip(node)
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g2.scale(zoomFactor, zoomFactor)
                paintBackgroundGrid(g2)
                for (node in nodes) paintNode(g2, node)
                selected?.let { paintSelection(g2, it, JBColor.BLUE) }
                hovered?.let { paintSelection(g2, it, JBColor(0xDA8B00, 0xE0A13A)) }
            } finally {
                g2.dispose()
            }
        }

        private fun layout(root: GuiElement): List<GuiLayoutNode> {
            val result = mutableListOf<GuiLayoutNode>()
            val rootSize = root.preferredSize
            collectLayout(
                element = root,
                parentBounds = null,
                bounds = Rectangle(padding, padding, rootSize.width.coerceAtLeast(1), rootSize.height.coerceAtLeast(1)),
                depth = 0,
                result = result
            )
            return result
        }

        private fun collectLayout(
            element: GuiElement,
            parentBounds: Rectangle?,
            bounds: Rectangle,
            depth: Int,
            result: MutableList<GuiLayoutNode>
        ) {
            val issues = buildIssues(element, parentBounds, bounds)
            result.add(GuiLayoutNode(element, bounds, depth, issues))
            for (child in element.children) {
                val childSize = child.preferredSize
                val childBounds = Rectangle(
                    bounds.x + child.position.x,
                    bounds.y + child.position.y,
                    childSize.width.coerceAtLeast(1),
                    childSize.height.coerceAtLeast(1)
                )
                collectLayout(child, bounds, childBounds, depth + 1, result)
            }
        }

        private fun buildIssues(
            element: GuiElement,
            parentBounds: Rectangle?,
            bounds: Rectangle
        ): List<GuiPreviewIssue> {
            val issues = mutableListOf<GuiPreviewIssue>()
            val declaredSize = element.size
            if (declaredSize != null && (declaredSize.width <= 0 || declaredSize.height <= 0)) {
                issues.add(GuiPreviewIssue(GuiIssueSeverity.ERROR, "size is ${declaredSize.width} x ${declaredSize.height}"))
            }
            if (element.primarySprite != null && spritePath(element) == null) {
                issues.add(GuiPreviewIssue(GuiIssueSeverity.WARNING, "sprite not resolved: ${element.primarySprite}"))
            }
            if (element.text != null && localizedText(element.text) == null) {
                issues.add(GuiPreviewIssue(GuiIssueSeverity.INFO, "localisation not resolved: ${element.text}"))
            }
            if (parentBounds != null && !parentBounds.contains(bounds)) {
                issues.add(GuiPreviewIssue(GuiIssueSeverity.WARNING, "extends outside parent bounds"))
            }
            return issues
        }

        private fun computeLogicalSize(nodes: List<GuiLayoutNode>): Dimension {
            val maxX = nodes.maxOfOrNull { it.bounds.x + it.bounds.width } ?: JBUIScale.scale(800)
            val maxY = nodes.maxOfOrNull { it.bounds.y + it.bounds.height } ?: JBUIScale.scale(520)
            return Dimension(max(maxX + padding, JBUIScale.scale(800)), max(maxY + padding, JBUIScale.scale(520)))
        }

        private fun paintBackgroundGrid(g: Graphics2D) {
            val step = JBUIScale.scale(32)
            g.color = JBColor(0xF0F0F0, 0x303030)
            var x = 0
            while (x <= logicalSize.width) {
                g.drawLine(x, 0, x, logicalSize.height)
                x += step
            }
            var y = 0
            while (y <= logicalSize.height) {
                g.drawLine(0, y, logicalSize.width, y)
                y += step
            }
        }

        private fun paintNode(g: Graphics2D, node: GuiLayoutNode) {
            val bounds = node.bounds
            val element = node.element
            val hasIssue = node.issues.any { it.severity != GuiIssueSeverity.INFO }
            val image = spritePath(element)?.let { PreviewImageLoader.load(it, imageCache) }
            val paintBounds = if (image != null && element.size == null && element.type == "iconType") {
                Rectangle(bounds.x, bounds.y, image.width, image.height)
            } else {
                bounds
            }

            if (image != null) {
                g.drawImage(image, paintBounds.x, paintBounds.y, paintBounds.width, paintBounds.height, null)
            } else {
                g.color = backgroundFor(element.type)
                g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height)
            }

            g.color = if (hasIssue) JBColor.RED else borderFor(element.type)
            g.stroke = BasicStroke(if (node.depth == 0) 2f else 1f)
            g.drawRect(bounds.x, bounds.y, bounds.width, bounds.height)

            val label = labelFor(element)
            if (label.isNotBlank()) paintLabel(g, label, bounds, element.type)

            if (hasIssue) {
                g.color = JBColor.RED
                g.fillOval(bounds.x + bounds.width - JBUIScale.scale(10), bounds.y + JBUIScale.scale(4), JBUIScale.scale(6), JBUIScale.scale(6))
            }
        }

        private fun paintLabel(g: Graphics2D, label: String, bounds: Rectangle, type: String) {
            val inset = JBUIScale.scale(5)
            val text = fitText(g.fontMetrics, label, (bounds.width - inset * 2).coerceAtLeast(8))
            val y = when (type) {
                "instantTextBoxType", "buttonType", "editBoxType" -> bounds.y + (bounds.height + g.fontMetrics.ascent) / 2 - JBUIScale.scale(3)
                else -> bounds.y + inset + g.fontMetrics.ascent
            }
            g.color = JBColor.foreground()
            g.drawString(text, bounds.x + inset, y.coerceAtMost(bounds.y + bounds.height - inset))
        }

        private fun paintSelection(g: Graphics2D, node: GuiLayoutNode, color: Color) {
            g.color = color
            g.stroke = BasicStroke(2f)
            val b = node.bounds
            g.drawRect(b.x - 2, b.y - 2, b.width + 4, b.height + 4)
        }

        private fun labelFor(element: GuiElement): String {
            val text = element.text?.let { localizedText(it) ?: it.trim('"') }
            return text ?: element.name ?: element.primarySprite ?: element.type
        }

        private fun backgroundFor(type: String): Color {
            return when (type) {
                "containerWindowType" -> JBColor(0xF7F8FA, 0x25272B)
                "buttonType" -> JBColor(0xD9E5F4, 0x33465F)
                "instantTextBoxType" -> JBColor(0xFFF7D6, 0x4A432B)
                "iconType" -> JBColor(0xE9E9E9, 0x3A3A3A)
                else -> JBColor(0xEEF1F4, 0x30343A)
            }
        }

        private fun borderFor(type: String): Color {
            return when (type) {
                "containerWindowType" -> JBColor.border()
                "buttonType" -> JBColor(0x59789E, 0x7895BA)
                "instantTextBoxType" -> JBColor(0x9B8530, 0xBFA74A)
                "iconType" -> JBColor(0x777777, 0x999999)
                else -> JBColor(0x7F8A94, 0x8A96A3)
            }
        }

        private fun spritePath(element: GuiElement): String? {
            val candidates = (element.spriteCandidates + listOfNotNull(element.primarySprite)).distinct()
            for (sprite in candidates) {
                val path = spritePathCache.getOrPut(sprite) { service.resolveSpritePath(sprite) }
                if (path != null) return path
            }
            return null
        }

        private fun localizedText(key: String?): String? {
            if (key == null) return null
            return localisationCache.getOrPut(key) { service.resolveLocalisation(key) }
        }

        private fun findNodeAt(point: Point): GuiLayoutNode? {
            return nodes.asReversed().firstOrNull { it.bounds.contains(point) }
        }

        private fun screenToLogical(point: Point): Point {
            return Point((point.x / zoomFactor).roundToInt(), (point.y / zoomFactor).roundToInt())
        }

        private fun navigateTo(element: GuiElement) {
            val path = element.sourceFilePath ?: return
            val vf = LocalFileSystem.getInstance().findFileByPath(path) ?: return
            if (element.sourceLine > 0) {
                OpenFileDescriptor(project, vf, element.sourceLine - 1, 0).navigate(true)
            } else {
                OpenFileDescriptor(project, vf).navigate(true)
            }
        }

        private fun buildTooltip(node: GuiLayoutNode): String {
            val element = node.element
            val text = element.text?.let { localizedText(it) ?: it.trim('"') }
            val sb = StringBuilder("<html>")
            appendRow(sb, "Type", element.type)
            appendRow(sb, "Name", element.name)
            appendRow(sb, "Position", "${element.position.x}, ${element.position.y}")
            appendRow(sb, "Size", "${node.bounds.width} x ${node.bounds.height}")
            appendRow(sb, "Sprite", element.primarySprite)
            appendRow(sb, "Sprite URL", spritePath(element))
            appendRow(sb, "Text", text)
            appendRow(sb, "Line", element.sourceLine.takeIf { it > 0 }?.toString())
            for (issue in node.issues) appendRow(sb, issue.severity.name, issue.message)
            sb.append("</html>")
            return sb.toString()
        }

        private fun appendRow(sb: StringBuilder, label: String, value: String?) {
            if (value.isNullOrBlank()) return
            sb.append("<b>")
                .append(escapeHtml(label))
                .append(":</b> ")
                .append(escapeHtml(value))
                .append("<br>")
        }

        private fun escapeHtml(value: String): String {
            return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
        }

        private fun fitText(metrics: FontMetrics, value: String, width: Int): String {
            if (metrics.stringWidth(value) <= width) return value
            var text = value
            while (text.length > 1 && metrics.stringWidth("$text...") > width) {
                text = text.dropLast(1)
            }
            return if (text.length <= 1) text else "$text..."
        }

        private fun setScrollBarValue(scrollBar: javax.swing.JScrollBar?, value: Int) {
            if (scrollBar == null) return
            val max = scrollBar.maximum - scrollBar.visibleAmount
            scrollBar.value = value.coerceIn(scrollBar.minimum, max.coerceAtLeast(scrollBar.minimum))
        }

        @Suppress("unused")
        private fun multiClickInterval(): Int {
            val interval = Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval") as? Int
            return (interval ?: 250).coerceAtLeast(150)
        }
    }
}
