package net.posdaca.oiia.gui

import net.posdaca.oiia.core.preview.PreviewGraphCanvas
import net.posdaca.oiia.core.preview.PreviewHintHtml

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.ImageUtil
import com.intellij.util.ui.JBFont
import OiiaBundle
import net.posdaca.oiia.core.ParadoxSpriteResolver.SpriteInfo
import net.posdaca.oiia.core.ParadoxSpriteResolver.SpriteInsets
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.Arc2D
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.max
import kotlin.math.roundToInt

class GuiPreviewPanel(
    project: Project,
    snapshot: GuiPreviewSnapshot,
    service: GuiPreviewService
) : JBPanel<JBPanel<*>>(BorderLayout()) {

    private val roots = snapshot.file.roots
    private val selector = ComboBox(roots.toTypedArray())
    private val statusLabel = JBLabel()
    private val canvas = GuiCanvas(project, service, snapshot.resources)
    val statusComponent: JComponent = createStatusComponent()
    val rootSelectorActions: JComponent = createRootSelectorActions()

    init {
        background = JBColor.PanelBackground
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

    private fun createStatusComponent(): JComponent {
        statusLabel.font = JBFont.label()
        return statusLabel
    }

    private fun createRootSelectorActions(): JComponent {
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
        return actions
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
        project: Project,
        private val service: GuiPreviewService,
        initialResources: GuiPreviewResources
    ) : PreviewGraphCanvas<GuiLayoutNode>(project) {

        override val minZoom: Double = 0.2
        override val scrollUnit: Int = JBUIScale.scale(32)
        override val scrollBlock: Int = JBUIScale.scale(160)

        override val padding = JBUIScale.scale(48)
        private val imageCache = mutableMapOf<String, BufferedImage>()
        private val processedImageCache = mutableMapOf<SpriteRenderKey, BufferedImage>()
        private val spriteInfoCache = mutableMapOf<String, SpriteInfo?>()
        private val localisationCache = mutableMapOf<String, String?>()
        private var localisationCacheKey: String? = null
        private var root: GuiElement? = null
        private var nodes = emptyList<GuiLayoutNode>()
        private var logicalSize = Dimension(JBUIScale.scale(800), JBUIScale.scale(520))
        private var dragBasePosition: GuiPoint? = null
        private var dragDeltaX = 0
        private var dragDeltaY = 0
        private val preloadVersion = AtomicInteger()
        private var ready = false

        companion object {
            private const val PREVIEW_SCREEN_WIDTH = 1920
            private const val PREVIEW_SCREEN_HEIGHT = 1080
            private val LOG = Logger.getInstance(GuiCanvas::class.java)
        }

        private data class SpriteRenderKey(
            val name: String,
            val subtype: String?,
            val primaryPath: String?,
            val path1: String?,
            val path2: String?,
            val width: Int,
            val height: Int,
            val frame: Int?,
            val noOfFrames: Int?,
            val defaultFrame: Int?,
            val border: String?,
            val spriteSize: String?,
            val effectFile: String?,
            val tilingCenter: Boolean,
            val horizontal: Boolean?,
            val progressRatio: Int,
            val rotation: Int?,
            val amount: Int?,
            val steps: Int?,
            val alwaysTransparent: Boolean?
        )

        private enum class FrameDirection {
            HORIZONTAL,
            VERTICAL
        }

        private enum class ProgressEffect {
            NORMAL,
            REVERSE,
            START_END,
            MIN_MAX,
            RADIAL
        }

        private data class GuiAnchor(val xFactor: Double, val yFactor: Double) {
            companion object {
                val UPPER_LEFT = GuiAnchor(0.0, 0.0)
                val UP = GuiAnchor(0.5, 0.0)
                val UPPER_RIGHT = GuiAnchor(1.0, 0.0)
                val LEFT = GuiAnchor(0.0, 0.5)
                val CENTER = GuiAnchor(0.5, 0.5)
                val RIGHT = GuiAnchor(1.0, 0.5)
                val LOWER_LEFT = GuiAnchor(0.0, 1.0)
                val DOWN = GuiAnchor(0.5, 1.0)
                val LOWER_RIGHT = GuiAnchor(1.0, 1.0)
            }
        }

        init {
            mergeResources(initialResources)
            ready = true
        }
        fun setRoot(nextRoot: GuiElement) {
            resetHoverAndHints()
            val version = preloadVersion.incrementAndGet()
            root = nextRoot
            refreshLocalisationPreference()
            if (isRootReady(nextRoot)) {
                ready = true
                nodes = layout(nextRoot)
                logicalSize = computeLogicalSize(nodes)
                revalidate()
                repaint()
            } else {
                ready = false
                nodes = emptyList()
                logicalSize = Dimension(JBUIScale.scale(800), JBUIScale.scale(520))
                revalidate()
                repaint()
                preloadResources(nextRoot, version)
            }
            SwingUtilities.invokeLater { resetScroll() }
        }

        override fun isDocumentEmpty(): Boolean = !ready || nodes.isEmpty()

        override fun computeLogicalSize(): Dimension = logicalSize

        override fun findHit(logical: Point): GuiLayoutNode? = findNodeAt(logical)

        override fun hitId(hit: GuiLayoutNode): String {
            val element = hit.element
            return if (element.sourceOffset >= 0) {
                "${element.sourceFilePath}#${element.sourceOffset}"
            } else {
                "${element.sourceFilePath}:${element.sourceLine}:${element.type}:${element.name}"
            }
        }

        override fun navigateTo(hit: GuiLayoutNode) {
            navigateToSource(hit.element.sourceFilePath, hit.element.sourceLine)
        }

        override fun buildHintHtml(hit: GuiLayoutNode): String {
            val element = hit.element
            val text = element.text?.let { cachedLocalizedText(it) ?: it.trim('"') }
            val info = cachedSpriteInfo(element)
            val html = PreviewHintHtml()
                .header(element.type, element.name ?: element.type, element.name ?: element.type)
                .description(text)
                .escapedRow("Position", "${element.position.xValue}, ${element.position.yValue}")
                .escapedRow("Bounds", "${hit.bounds.x}, ${hit.bounds.y}, ${hit.bounds.width} x ${hit.bounds.height}")
                .escapedRow("Size", "${hit.bounds.width} x ${hit.bounds.height}")
                .escapedRow("Orientation", element.orientation)
                .escapedRow("Origo", element.origo)
                .row("Scale", element.scale?.toString())
                .row("Center Position", element.centerPosition.takeIf { it }?.toString())
                .row("Preserve Aspect", element.preserveAspectRatio.takeIf { it }?.toString())
                .escapedRow("Sprite", element.primarySprite)
                .escapedRow("Subtype", info?.subtype)
                .row("Frame", element.frame?.toString() ?: info?.defaultFrame?.toString())
                .row("Frame Index", info?.noOfFrames?.let { resolveFrameIndex(element.frame ?: info.defaultFrame, it.coerceAtLeast(1)).toString() })
                .row("No Of Frames", info?.noOfFrames?.toString())
                .escapedRow("Sprite URL", info?.primaryImagePath)
                .escapedRow("Texture", info?.textureFile)
                .escapedRow("Texture 1", info?.textureFile1)
                .escapedRow("Texture 1 URL", info?.imagePath1)
                .escapedRow("Texture 2", info?.textureFile2)
                .escapedRow("Texture 2 URL", info?.imagePath2)
                .escapedRow("Effect", info?.effectFile)
                .escapedRow("Sprite Size", info?.size?.let { "${it.width}, ${it.height}" })
                .escapedRow("Border", info?.borderSize?.let { "${it.left}, ${it.top}, ${it.right}, ${it.bottom}" })
                .row("Always Transparent", info?.alwaysTransparent?.takeIf { it }?.toString())
                .row("Line", element.sourceLine.takeIf { it > 0 }?.toString())
            for ((severity, message) in hit.issues) {
                html.escapedRow(severity.name, message)
            }
            return html.build()
        }

        override fun getPreferredSize(): Dimension {
            return Dimension(
                (logicalSize.width * zoomFactor).roundToInt(),
                (logicalSize.height * zoomFactor).roundToInt()
            )
        }

        override fun canDragNode(hit: GuiLayoutNode): Boolean {
            if (hit.element.sourceOffset < 0) return false
            if (hit.element.sourceFilePath.isNullOrBlank()) return false
            if (hit.element.fullscreen) return false
            if (hit.element.type.equals("background", ignoreCase = true)) return false
            if (hit.element.position.xValue.percent || hit.element.position.yValue.percent) return false
            return true
        }

        override fun onNodeDragStarted(hit: GuiLayoutNode) {
            dragBasePosition = hit.element.position
            dragDeltaX = 0
            dragDeltaY = 0
        }

        override fun onNodeDragged(hit: GuiLayoutNode, startLogical: Point, currentLogical: Point) {
            dragDeltaX = currentLogical.x - startLogical.x
            dragDeltaY = currentLogical.y - startLogical.y
        }

        override fun onNodeDragFinished(hit: GuiLayoutNode): Boolean {
            val basePosition = dragBasePosition
            val dx = dragDeltaX
            val dy = dragDeltaY
            dragBasePosition = null
            dragDeltaX = 0
            dragDeltaY = 0
            if (basePosition != null && (dx != 0 || dy != 0)) {
                commitElementDrag(hit, basePosition, dx, dy)
            } else {
                repaint()
            }
            return true
        }

        override fun paintGraph(g2d: Graphics2D) {
            if (!ready) return
            paintBackgroundGrid(g2d)
            for (node in nodes) {
                val boundsOffset = dragPaintOffset(node)
                if (boundsOffset != null) {
                    paintNode(g2d, offsetNode(node, boundsOffset))
                } else {
                    paintNode(g2d, node)
                }
            }
            nodeById(selectedId)?.let { paintSelection(g2d, offsetNodeForPaint(it), JBColor.BLUE) }
            nodeById(hoveredId)?.let { paintSelection(g2d, offsetNodeForPaint(it), JBColor(0xDA8B00, 0xE0A13A)) }
        }

        override fun addNotify() {
            super.addNotify()
            root?.let {
                refreshLocalisationPreference()
                if (!isRootReady(it)) preloadResources(it, preloadVersion.incrementAndGet())
            }
        }

        override fun removeNotify() {
            preloadVersion.incrementAndGet()
            super.removeNotify()
            processedImageCache.clear()
        }

        private fun nodeById(id: String?): GuiLayoutNode? =
            id?.let { key -> nodes.firstOrNull { hitId(it) == key } }

        private fun offsetNode(node: GuiLayoutNode, offset: Point): GuiLayoutNode {
            return node.copy(
                bounds = Rectangle(
                    node.bounds.x + offset.x,
                    node.bounds.y + offset.y,
                    node.bounds.width,
                    node.bounds.height
                ),
                clipBounds = node.clipBounds?.let {
                    Rectangle(it.x + offset.x, it.y + offset.y, it.width, it.height)
                }
            )
        }

        private fun layout(root: GuiElement): List<GuiLayoutNode> {
            val result = mutableListOf<GuiLayoutNode>()
            val viewport = Rectangle(padding, padding, PREVIEW_SCREEN_WIDTH, PREVIEW_SCREEN_HEIGHT)
            val rootSize = resolveElementSize(root, viewport, null)
            val rootBounds = Rectangle(padding, padding, rootSize.width.coerceAtLeast(1), rootSize.height.coerceAtLeast(1))
            val rootClip = if (root.clipping || root.fullscreen) rootBounds else null
            collectLayout(
                element = root,
                parentBounds = null,
                bounds = rootBounds,
                inheritedClip = rootClip,
                depth = 0,
                result = result
            )
            return result
        }

        private fun collectLayout(
            element: GuiElement,
            parentBounds: Rectangle?,
            bounds: Rectangle,
            inheritedClip: Rectangle?,
            depth: Int,
            result: MutableList<GuiLayoutNode>
        ) {
            val issues = buildIssues(element, parentBounds, bounds)
            val paintClip = intersectRect(inheritedClip, bounds)
            val childClip = if (element.clipping) paintClip else inheritedClip
            result.add(GuiLayoutNode(element, bounds, paintClip, depth, issues))
            for (child in element.children) {
                val childSize = resolveElementSize(child, bounds, element)
                val childBounds = resolveElementBounds(child, bounds, childSize)
                collectLayout(child, bounds, childBounds, childClip, depth + 1, result)
            }
        }

        private fun intersectRect(a: Rectangle?, b: Rectangle): Rectangle? {
            if (a == null) return Rectangle(b)
            val intersection = a.intersection(b)
            return if (intersection.width <= 0 || intersection.height <= 0) null else intersection
        }

        private fun resolveElementSize(
            element: GuiElement,
            parentBounds: Rectangle,
            parentElement: GuiElement?
        ): Dimension {
            if (element.fullscreen) return Dimension(parentBounds.width.coerceAtLeast(1), parentBounds.height.coerceAtLeast(1))
            if (element.type.equals("background", ignoreCase = true) && element.size == null) {
                return Dimension(parentBounds.width.coerceAtLeast(1), parentBounds.height.coerceAtLeast(1))
            }
            val declared = element.size
            val spriteSize = cachedSpriteInfo(element)?.let { nativeElementSize(it) }
            val textSize = textSizeFallback(element, parentBounds)
            val baseWidth = declared?.resolveWidth(parentBounds.width)
                ?: textSize?.width
                ?: spriteSize?.width
                ?: defaultElementSize(element).width
            val baseHeight = declared?.resolveHeight(parentBounds.height)
                ?: textSize?.height
                ?: spriteSize?.height
                ?: defaultElementSize(element).height
            val scale = element.scale?.takeIf { it > 0.0 } ?: 1.0
            var width = (baseWidth * scale).roundToInt().coerceAtLeast(1)
            var height = (baseHeight * scale).roundToInt().coerceAtLeast(1)

            if (element.preserveAspectRatio && declared != null && spriteSize != null && spriteSize.width > 0 && spriteSize.height > 0) {
                val ratio = spriteSize.width.toDouble() / spriteSize.height.toDouble()
                if (declared.widthValue.percent && !declared.heightValue.percent) {
                    height = (width / ratio).roundToInt().coerceAtLeast(1)
                } else if (declared.heightValue.percent && !declared.widthValue.percent) {
                    width = (height * ratio).roundToInt().coerceAtLeast(1)
                } else {
                    val fitted = fitAspectRatio(width, height, ratio)
                    width = fitted.width
                    height = fitted.height
                }
            }

            if (parentElement?.type == "extendedScrollbarType" && element.size == null && element.type in setOf("slider", "track", "increaseButton", "decreaseButton")) {
                if (spriteSize != null) return Dimension(spriteSize.width.coerceAtLeast(1), spriteSize.height.coerceAtLeast(1))
            }
            return Dimension(width, height)
        }

        private fun textSizeFallback(element: GuiElement, parentBounds: Rectangle): Dimension? {
            if (!isTextElement(element)) return null
            val width = element.maxWidth?.resolveSize(parentBounds.width)
            val height = element.maxHeight?.resolveSize(parentBounds.height)
            if (width == null && height == null) return null
            val fallback = element.preferredSize
            return Dimension(
                (width ?: fallback.width).coerceAtLeast(1),
                (height ?: fallback.height).coerceAtLeast(1)
            )
        }

        private fun isTextElement(element: GuiElement): Boolean {
            return element.type.equals("instantTextBoxType", ignoreCase = true) ||
                    element.type.equals("instantTextboxType", ignoreCase = true)
        }

        private fun fitAspectRatio(width: Int, height: Int, ratio: Double): Dimension {
            val widthFromHeight = (height * ratio).roundToInt().coerceAtLeast(1)
            if (widthFromHeight <= width) return Dimension(widthFromHeight, height)
            val heightFromWidth = (width / ratio).roundToInt().coerceAtLeast(1)
            return Dimension(width, heightFromWidth)
        }

        private fun resolveElementBounds(
            element: GuiElement,
            parentBounds: Rectangle,
            size: Dimension
        ): Rectangle {
            val anchor = orientationAnchor(element.orientation, parentBounds)
            val offsetX = element.position.resolveX(parentBounds.width)
            val offsetY = element.position.resolveY(parentBounds.height)
            val origo = if (element.centerPosition) GuiAnchor.CENTER else origoAnchor(element.origo)
            val x = anchor.x + offsetX - (size.width * origo.xFactor).roundToInt()
            val y = anchor.y + offsetY - (size.height * origo.yFactor).roundToInt()
            return Rectangle(x, y, size.width.coerceAtLeast(1), size.height.coerceAtLeast(1))
        }

        private fun orientationAnchor(value: String?, parentBounds: Rectangle): Point {
            val anchor = anchorFromGuiValue(value) ?: GuiAnchor.UPPER_LEFT
            return Point(
                parentBounds.x + (parentBounds.width * anchor.xFactor).roundToInt(),
                parentBounds.y + (parentBounds.height * anchor.yFactor).roundToInt()
            )
        }

        private fun origoAnchor(value: String?): GuiAnchor {
            return anchorFromGuiValue(value) ?: GuiAnchor.UPPER_LEFT
        }

        private fun anchorFromGuiValue(value: String?): GuiAnchor? {
            val normalized = value?.trim()?.trim('"')?.lowercase()?.replace('-', '_') ?: return null
            return when (normalized) {
                "upper_left" -> GuiAnchor.UPPER_LEFT
                "up", "upper", "center_up", "center_upper", "upper_center" -> GuiAnchor.UP
                "upper_right" -> GuiAnchor.UPPER_RIGHT
                "left", "center_left" -> GuiAnchor.LEFT
                "center", "centre" -> GuiAnchor.CENTER
                "right", "center_right" -> GuiAnchor.RIGHT
                "lower_left" -> GuiAnchor.LOWER_LEFT
                "down", "lower", "center_down", "center_lower", "lower_center" -> GuiAnchor.DOWN
                "lower_right" -> GuiAnchor.LOWER_RIGHT
                else -> null
            }
        }

        private fun buildIssues(
            element: GuiElement,
            parentBounds: Rectangle?,
            bounds: Rectangle
        ): List<GuiPreviewIssue> {
            val issues = mutableListOf<GuiPreviewIssue>()
            val declaredSize = element.size
            if (declaredSize != null && declaredSize.width == 0 && declaredSize.height == 0) {
                issues.add(GuiPreviewIssue(GuiIssueSeverity.WARNING, "size is 0 x 0"))
            }
            if (element.primarySprite != null && isSpriteKnownMissing(element)) {
                issues.add(GuiPreviewIssue(GuiIssueSeverity.WARNING, "sprite not resolved: ${element.primarySprite}"))
            }
            if (element.text != null && isLocalisationKnownMissing(element.text)) {
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
            val clipBounds = node.clipBounds ?: return
            val oldClip = g.clip
            g.clip = intersectClip(oldClip, clipBounds)
            val bounds = node.bounds
            val element = node.element
            try {
                val spriteInfo = cachedSpriteInfo(element)
                val nativeSize = spriteInfo?.let { nativeElementSize(it) }
                val paintBounds = if (nativeSize != null && element.size == null && element.type == "iconType") {
                    Rectangle(bounds.x, bounds.y, nativeSize.width, nativeSize.height)
                } else {
                    bounds
                }
                val image = spriteInfo?.let { preprocessedSpriteImage(element, it, paintBounds.width, paintBounds.height) }

                if (image != null) {
                    g.drawImage(image, paintBounds.x, paintBounds.y, null)
                }
                paintElementText(g, element, bounds, clipBounds)
            } finally {
                g.clip = oldClip
            }
        }

        private fun paintElementText(
            g: Graphics2D,
            element: GuiElement,
            bounds: Rectangle,
            clipBounds: Rectangle
        ) {
            val value = cachedLocalizedText(element.text) ?: element.text?.trim()?.trim('"') ?: return
            if (value.isBlank()) return
            val textBounds = logicalToScreen(bounds)
            val textClip = intersectRect(clipBounds, bounds)?.let(::logicalToScreen) ?: return
            if (textBounds.width <= 0 || textBounds.height <= 0) return
            val textGraphics = g.create() as Graphics2D
            try {
                textGraphics.scale(1.0 / zoomFactor, 1.0 / zoomFactor)
                textGraphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                textGraphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
                textGraphics.clip = textClip
                textGraphics.font = textFont(element, zoomFactor)
                textGraphics.color = JBColor.foreground()
                val metrics = textGraphics.fontMetrics
                val horizontalPadding = screenLength(JBUIScale.scale(4))
                val lines = wrapText(metrics, value, textBounds.width - horizontalPadding * 2)
                if (lines.isEmpty()) return
                val lineHeight = metrics.height
                val textHeight = lineHeight * lines.size
                var y = when (normalized(element.verticalAlignment)) {
                    "center", "centre" -> textBounds.y + ((textBounds.height - textHeight) / 2).coerceAtLeast(0) + metrics.ascent
                    "bottom", "lower" -> textBounds.y + (textBounds.height - textHeight).coerceAtLeast(0) + metrics.ascent
                    else -> textBounds.y + screenLength(JBUIScale.scale(3)) + metrics.ascent
                }
                for (line in lines) {
                    val textWidth = metrics.stringWidth(line)
                    val x = when (normalized(element.format)) {
                        "right" -> textBounds.x + textBounds.width - textWidth - horizontalPadding
                        "center", "centre" -> textBounds.x + (textBounds.width - textWidth) / 2
                        else -> textBounds.x + horizontalPadding
                    }
                    textGraphics.drawString(line, x, y)
                    y += lineHeight
                    if (y - metrics.ascent > textBounds.y + textBounds.height) break
                }
            } finally {
                textGraphics.dispose()
            }
        }

        private fun textFont(element: GuiElement, scale: Double = 1.0): Font {
            val size = when {
                (element.buttonFont ?: element.font)?.contains("18") == true -> 13f
                (element.buttonFont ?: element.font)?.contains("22") == true -> 16f
                else -> 12f
            }
            return JBFont.label().deriveFont((size * scale).coerceAtLeast(1.0).toFloat())
        }

        private fun logicalToScreen(rectangle: Rectangle): Rectangle {
            val x = (rectangle.x * zoomFactor).roundToInt()
            val y = (rectangle.y * zoomFactor).roundToInt()
            val maxX = ((rectangle.x + rectangle.width) * zoomFactor).roundToInt()
            val maxY = ((rectangle.y + rectangle.height) * zoomFactor).roundToInt()
            return Rectangle(x, y, (maxX - x).coerceAtLeast(1), (maxY - y).coerceAtLeast(1))
        }

        private fun screenLength(logicalLength: Int): Int {
            return (logicalLength * zoomFactor).roundToInt().coerceAtLeast(1)
        }

        private fun wrapText(metrics: java.awt.FontMetrics, text: String, maxWidth: Int): List<String> {
            val width = maxWidth.coerceAtLeast(JBUIScale.scale(12))
            val words = text.replace("\\n", "\n").lines().flatMapIndexed { index, line ->
                val tokens = line.split(Regex("""\s+""")).filter { it.isNotBlank() }
                if (index == 0) tokens else listOf("\n") + tokens
            }
            if (words.isEmpty()) return emptyList()
            val result = mutableListOf<String>()
            var current = ""
            for (word in words) {
                if (word == "\n") {
                    if (current.isNotBlank()) result.add(current)
                    current = ""
                    continue
                }
                val candidate = if (current.isBlank()) word else "$current $word"
                if (metrics.stringWidth(candidate) <= width) {
                    current = candidate
                } else {
                    if (current.isNotBlank()) result.add(current)
                    current = fitText(metrics, word, width)
                }
            }
            if (current.isNotBlank()) result.add(current)
            return result
        }

        private fun fitText(metrics: java.awt.FontMetrics, value: String, width: Int): String {
            if (metrics.stringWidth(value) <= width) return value
            var text = value
            while (text.length > 1 && metrics.stringWidth("$text...") > width) {
                text = text.dropLast(1)
            }
            return if (text.length <= 1) text else "$text..."
        }

        private fun normalized(value: String?): String? {
            return value?.trim()?.trim('"')?.lowercase()?.replace('-', '_')
        }

        private fun nativeSpriteSize(image: BufferedImage, spriteInfo: SpriteInfo): Dimension {
            if (spriteInfo.usesDeclaredSpriteSize) {
                spriteInfo.size?.toDimension()?.let { return it }
            }
            val frames = spriteInfo.noOfFrames?.coerceAtLeast(1) ?: 1
            if (frames <= 1) return Dimension(image.width, image.height)
            return when (frameDirection(image, frames)) {
                FrameDirection.HORIZONTAL -> Dimension((image.width / frames).coerceAtLeast(1), image.height)
                FrameDirection.VERTICAL -> Dimension(image.width, (image.height / frames).coerceAtLeast(1))
            }
        }

        private fun nativeElementSize(spriteInfo: SpriteInfo): Dimension? {
            val image = spriteInfo.primaryImagePath?.let { imageCache[it] }
            val native = image?.let { nativeSpriteSize(it, spriteInfo) }
            if (native != null) return native
            if (spriteInfo.usesDeclaredSpriteSize) return spriteInfo.size?.toDimension()
            return null
        }

        private fun defaultElementSize(element: GuiElement): Dimension {
            if (element.type == "containerWindowType" && element.size == null && element.primarySprite == null) {
                return Dimension(1, 1)
            }
            val preferred = element.preferredSize
            return Dimension(preferred.width, preferred.height)
        }

        private fun preprocessedSpriteImage(
            element: GuiElement,
            spriteInfo: SpriteInfo,
            width: Int,
            height: Int
        ): BufferedImage? {
            val targetWidth = width.coerceAtLeast(1)
            val targetHeight = height.coerceAtLeast(1)
            val rawImage = spriteInfo.primaryImagePath?.let { imageCache[it] } ?: return null
            val sourceImage = if (spriteInfo.usesCompositeTextures) rawImage else frameImage(rawImage, element, spriteInfo)
            val key = spriteRenderKey(element, spriteInfo, targetWidth, targetHeight)
            processedImageCache[key]?.let { return it }

            val output = ImageUtil.createImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB)
            val g = output.createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val spriteBounds = if (element.preserveAspectRatio) {
                    aspectFitBounds(sourceImage, targetWidth, targetHeight)
                } else {
                    Rectangle(0, 0, targetWidth, targetHeight)
                }
                paintSpriteImage(g, sourceImage, spriteBounds, element, spriteInfo)
            } finally {
                g.dispose()
            }
            processedImageCache[key] = output
            return output
        }

        private fun frameImage(
            image: BufferedImage,
            element: GuiElement,
            spriteInfo: SpriteInfo
        ): BufferedImage {
            val frames = spriteInfo.noOfFrames?.coerceAtLeast(1) ?: 1
            if (frames <= 1) return image
            val direction = frameDirection(image, frames)
            val frame = resolveFrameIndex(element.frame ?: spriteInfo.defaultFrame, frames)
            return when (direction) {
                FrameDirection.HORIZONTAL -> {
                    cropFrame(image, spriteInfo, element, frames, frame, direction, horizontal = true)
                }
                FrameDirection.VERTICAL -> {
                    cropFrame(image, spriteInfo, element, frames, frame, direction, horizontal = false)
                }
            }
        }

        private fun cropFrame(
            image: BufferedImage,
            spriteInfo: SpriteInfo,
            element: GuiElement,
            frames: Int,
            frame: Int,
            direction: FrameDirection,
            horizontal: Boolean
        ): BufferedImage {
            val total = if (horizontal) image.width else image.height
            val frameSize = (total / frames).coerceAtLeast(1)
            val start = (frame * frameSize).coerceAtMost(total - 1)
            val span = if (frame == frames - 1) total - start else frameSize.coerceAtMost(total - start).coerceAtLeast(1)
            val sx = if (horizontal) start else 0
            val sy = if (horizontal) 0 else start
            val width = if (horizontal) span else image.width
            val height = if (horizontal) image.height else span
            LOG.info("GUI sprite frame crop: sprite=${spriteInfo.name} element=${element.name} raw=${image.width}x${image.height} frames=$frames frame=${element.frame} index=$frame direction=$direction crop=$sx,$sy ${width}x$height")
            return image.getSubimage(sx, sy, width, height)
        }

        private fun frameDirection(image: BufferedImage, frames: Int): FrameDirection {
            if (frames <= 1) return FrameDirection.VERTICAL
            val horizontalFrameWidth = image.width / frames
            val verticalFrameHeight = image.height / frames
            if (horizontalFrameWidth <= 0) return FrameDirection.VERTICAL
            if (verticalFrameHeight <= 0) return FrameDirection.HORIZONTAL
            val horizontalRatio = horizontalFrameWidth.toDouble() / image.height.toDouble()
            val verticalRatio = image.width.toDouble() / verticalFrameHeight.toDouble()
            return if (kotlin.math.abs(horizontalRatio - 1.0) <= kotlin.math.abs(verticalRatio - 1.0)) {
                FrameDirection.HORIZONTAL
            } else {
                FrameDirection.VERTICAL
            }
        }

        private fun resolveFrameIndex(frame: Int?, frames: Int): Int {
            if (frame == null) return 0
            if (frame <= 0) return 0
            return (frame - 1).coerceIn(0, frames - 1)
        }

        private fun aspectFitBounds(sourceImage: BufferedImage, targetWidth: Int, targetHeight: Int): Rectangle {
            val ratio = sourceImage.width.toDouble() / sourceImage.height.toDouble()
            val fitted = fitAspectRatio(targetWidth, targetHeight, ratio)
            return Rectangle(
                (targetWidth - fitted.width) / 2,
                (targetHeight - fitted.height) / 2,
                fitted.width,
                fitted.height
            )
        }

        private fun spriteRenderKey(
            element: GuiElement,
            spriteInfo: SpriteInfo,
            width: Int,
            height: Int
        ): SpriteRenderKey {
            val border = spriteInfo.borderSize?.let { "${it.left},${it.top},${it.right},${it.bottom}" }
            val spriteSize = spriteInfo.size?.let { "${it.width},${it.height}" }
            return SpriteRenderKey(
                name = spriteInfo.name,
                subtype = spriteInfo.subtype,
                primaryPath = spriteInfo.primaryImagePath,
                path1 = spriteInfo.imagePath1,
                path2 = spriteInfo.imagePath2,
                width = width,
                height = height,
                frame = element.frame,
                noOfFrames = spriteInfo.noOfFrames,
                defaultFrame = spriteInfo.defaultFrame,
                border = border,
                spriteSize = spriteSize,
                effectFile = spriteInfo.effectFile,
                tilingCenter = spriteInfo.tilingCenter,
                horizontal = element.horizontal ?: spriteInfo.horizontal,
                progressRatio = (progressRatio(element) * 10_000).roundToInt(),
                rotation = spriteInfo.rotation,
                amount = spriteInfo.amount,
                steps = spriteInfo.steps,
                alwaysTransparent = spriteInfo.alwaysTransparent
            )
        }

        private fun paintSpriteImage(
            g: Graphics2D,
            image: BufferedImage,
            bounds: Rectangle,
            element: GuiElement,
            spriteInfo: SpriteInfo
        ) {
            when {
                spriteInfo.subtype == "progressbar" -> paintProgressBar(g, image, bounds, element, spriteInfo)
                spriteInfo.subtype == "circular_progressbar" -> paintCircularProgressBar(g, image, bounds, spriteInfo)
                spriteInfo.subtype == "masked_shield" -> paintLayeredSprite(g, image, bounds, spriteInfo)
                spriteInfo.borderSize != null -> {
                    paintNinePatch(g, image, bounds, effectiveBorderInsets(image, spriteInfo), spriteInfo.tilingCenter)
                }
                spriteInfo.subtype == "quad_texture" -> tileImage(g, image, bounds)
                else -> g.drawImage(image, bounds.x, bounds.y, bounds.width, bounds.height, null)
            }
        }

        private fun effectiveBorderInsets(image: BufferedImage, spriteInfo: SpriteInfo): SpriteInsets {
            val insets = spriteInfo.borderSize ?: return SpriteInsets(0, 0, 0, 0)
            val size = spriteInfo.size
            if (size == null || size.width <= 0 || size.height <= 0) return insets.coerceFor(image)
            if (size.width == image.width && size.height == image.height) return insets.coerceFor(image)

            fun scaleX(value: Int): Int = scaleBorderValue(value, size.width, image.width)
            fun scaleY(value: Int): Int = scaleBorderValue(value, size.height, image.height)
            return SpriteInsets(
                left = scaleX(insets.left),
                top = scaleY(insets.top),
                right = scaleX(insets.right),
                bottom = scaleY(insets.bottom)
            ).coerceFor(image)
        }

        private fun scaleBorderValue(value: Int, declaredTotal: Int, imageTotal: Int): Int {
            if (value <= 0 || declaredTotal <= 0 || imageTotal <= 0) return value.coerceAtLeast(0)
            val scaled = (value * imageTotal.toDouble() / declaredTotal.toDouble()).roundToInt()
            return scaled.coerceAtLeast(1)
        }

        private fun SpriteInsets.coerceFor(image: BufferedImage): SpriteInsets {
            val left = left.coerceIn(0, image.width / 2)
            val right = right.coerceIn(0, image.width - left)
            val top = top.coerceIn(0, image.height / 2)
            val bottom = bottom.coerceIn(0, image.height - top)
            return SpriteInsets(left, top, right, bottom)
        }

        private fun paintProgressLayers(
            g: Graphics2D,
            fallbackImage: BufferedImage,
            bounds: Rectangle,
            spriteInfo: SpriteInfo
        ): BufferedImage {
            val foreground = spriteInfo.imagePath1?.let { imageCache[it] } ?: fallbackImage
            val background = spriteInfo.imagePath2?.let { imageCache[it] }
            if (background != null) {
                g.drawImage(background, bounds.x, bounds.y, bounds.width, bounds.height, null)
            }
            return foreground
        }

        private fun paintProgressBar(
            g: Graphics2D,
            fallbackImage: BufferedImage,
            bounds: Rectangle,
            element: GuiElement,
            spriteInfo: SpriteInfo
        ) {
            val foreground = paintProgressLayers(g, fallbackImage, bounds, spriteInfo)

            val ratio = steppedProgressRatio(element, spriteInfo)
            if (ratio <= 0.0) return
            when (spriteInfo.progressEffect) {
                ProgressEffect.REVERSE -> paintProgressForeground(g, foreground, bounds, progressClip(bounds, ratio, horizontal = true, reverse = true))
                ProgressEffect.START_END -> paintStartEndProgress(g, foreground, bounds, ratio)
                ProgressEffect.MIN_MAX -> paintMinMaxProgress(g, foreground, bounds, ratio)
                ProgressEffect.RADIAL -> paintRadialProgress(g, foreground, bounds, ratio)
                ProgressEffect.NORMAL -> {
                    val horizontal = element.horizontal ?: spriteInfo.horizontal ?: true
                    paintProgressForeground(g, foreground, bounds, progressClip(bounds, ratio, horizontal = horizontal, reverse = false))
                }
            }
        }

        private fun paintCircularProgressBar(
            g: Graphics2D,
            fallbackImage: BufferedImage,
            bounds: Rectangle,
            spriteInfo: SpriteInfo
        ) {
            val foreground = paintProgressLayers(g, fallbackImage, bounds, spriteInfo)

            val ratio = ((spriteInfo.amount ?: 50).toDouble() / 100.0).coerceIn(0.0, 1.0)
            if (ratio <= 0.0) return
            val start = (spriteInfo.rotation ?: -90).toDouble()
            val oldClip = g.clip
            val arc = Arc2D.Double(
                bounds.x.toDouble(),
                bounds.y.toDouble(),
                bounds.width.toDouble(),
                bounds.height.toDouble(),
                start,
                -360.0 * ratio,
                Arc2D.PIE
            )
            try {
                g.clip = intersectClip(oldClip, arc)
                g.drawImage(foreground, bounds.x, bounds.y, bounds.width, bounds.height, null)
            } finally {
                g.clip = oldClip
            }
        }

        private fun paintLayeredSprite(
            g: Graphics2D,
            fallbackImage: BufferedImage,
            bounds: Rectangle,
            spriteInfo: SpriteInfo
        ) {
            val base = spriteInfo.imagePath1?.let { imageCache[it] } ?: fallbackImage
            val overlay = spriteInfo.imagePath2?.let { imageCache[it] }
            g.drawImage(base, bounds.x, bounds.y, bounds.width, bounds.height, null)
            if (overlay != null) g.drawImage(overlay, bounds.x, bounds.y, bounds.width, bounds.height, null)
        }

        private val SpriteInfo.usesCompositeTextures: Boolean
            get() = subtype == "progressbar" || subtype == "circular_progressbar" || subtype == "masked_shield"

        private val SpriteInfo.usesDeclaredSpriteSize: Boolean
            get() = subtype == "progressbar" || subtype == "circular_progressbar"

        private fun net.posdaca.oiia.core.ParadoxSpriteResolver.SpriteSize.toDimension(): Dimension? {
            if (width <= 0 || height <= 0) return null
            return Dimension(width, height)
        }

        private fun steppedProgressRatio(element: GuiElement, spriteInfo: SpriteInfo): Double {
            val ratio = progressRatio(element).coerceIn(0.0, 1.0)
            val steps = spriteInfo.steps?.takeIf { it > 1 } ?: return ratio
            return (ratio * steps).roundToInt().toDouble() / steps.toDouble()
        }

        private val SpriteInfo.progressEffect: ProgressEffect
            get() {
                val normalized = effectFile
                    ?.replace('\\', '/')
                    ?.substringAfterLast('/')
                    ?.substringBeforeLast('.')
                    ?.lowercase()
                return when (normalized) {
                    "progress_reverse" -> ProgressEffect.REVERSE
                    "progress_startend" -> ProgressEffect.START_END
                    "progress_minmax" -> ProgressEffect.MIN_MAX
                    "progress_radial" -> ProgressEffect.RADIAL
                    else -> ProgressEffect.NORMAL
                }
            }

        private fun progressClip(bounds: Rectangle, ratio: Double, horizontal: Boolean, reverse: Boolean): Rectangle {
            return if (horizontal) {
                val width = (bounds.width * ratio).roundToInt().coerceIn(0, bounds.width)
                if (reverse) {
                    Rectangle(bounds.x + bounds.width - width, bounds.y, width, bounds.height)
                } else {
                    Rectangle(bounds.x, bounds.y, width, bounds.height)
                }
            } else {
                val height = (bounds.height * ratio).roundToInt().coerceIn(0, bounds.height)
                Rectangle(bounds.x, bounds.y + bounds.height - height, bounds.width, height)
            }
        }

        private fun paintProgressForeground(g: Graphics2D, foreground: BufferedImage, bounds: Rectangle, clip: Shape) {
            val oldClip = g.clip
            try {
                g.clip = intersectClip(oldClip, clip)
                g.drawImage(foreground, bounds.x, bounds.y, bounds.width, bounds.height, null)
            } finally {
                g.clip = oldClip
            }
        }

        private fun paintStartEndProgress(
            g: Graphics2D,
            foreground: BufferedImage,
            bounds: Rectangle,
            ratio: Double
        ) {
            val width = (bounds.width * ratio).roundToInt().coerceIn(0, bounds.width)
            if (width <= 0) return
            val target = Rectangle(bounds.x, bounds.y, width, bounds.height)
            if (foreground.height < 3) {
                paintProgressForeground(g, foreground, bounds, target)
                return
            }
            val sourceHeight = (foreground.height / 3).coerceAtLeast(1)
            val middleY = (sourceHeight * 2).coerceAtMost(foreground.height - 1)
            val stopY = (sourceHeight * 3).coerceAtMost(foreground.height)
            val oldClip = g.clip
            try {
                g.clip = intersectClip(oldClip, target)
                g.drawImage(
                    foreground,
                    target.x,
                    target.y,
                    target.x + target.width,
                    target.y + target.height,
                    0,
                    middleY,
                    foreground.width,
                    stopY,
                    null
                )
                g.drawImage(
                    foreground,
                    target.x,
                    target.y,
                    (target.x + foreground.width).coerceAtMost(target.x + target.width),
                    target.y + target.height,
                    0,
                    sourceHeight,
                    foreground.width.coerceAtMost(foreground.width),
                    middleY,
                    null
                )
                g.drawImage(
                    foreground,
                    (target.x + target.width - foreground.width).coerceAtLeast(target.x),
                    target.y,
                    target.x + target.width,
                    target.y + target.height,
                    0,
                    middleY,
                    foreground.width,
                    stopY,
                    null
                )
            } finally {
                g.clip = oldClip
            }
        }

        private fun paintMinMaxProgress(g: Graphics2D, foreground: BufferedImage, bounds: Rectangle, ratio: Double) {
            val center = bounds.x + bounds.width / 2
            val current = bounds.x + (bounds.width * ratio).roundToInt().coerceIn(0, bounds.width)
            val x = minOf(center, current)
            val width = kotlin.math.abs(current - center)
            if (width <= 0) return
            paintProgressForeground(g, foreground, bounds, Rectangle(x, bounds.y, width, bounds.height))
        }

        private fun paintRadialProgress(g: Graphics2D, foreground: BufferedImage, bounds: Rectangle, ratio: Double) {
            val arc = Arc2D.Double(
                bounds.x.toDouble(),
                bounds.y.toDouble(),
                bounds.width.toDouble(),
                bounds.height.toDouble(),
                90.0,
                -360.0 * ratio,
                Arc2D.PIE
            )
            paintProgressForeground(g, foreground, bounds, arc)
        }

        private fun tileImage(g: Graphics2D, image: BufferedImage, bounds: Rectangle) {
            var y = bounds.y
            while (y < bounds.y + bounds.height) {
                var x = bounds.x
                val drawHeight = (bounds.y + bounds.height - y).coerceAtMost(image.height)
                while (x < bounds.x + bounds.width) {
                    val drawWidth = (bounds.x + bounds.width - x).coerceAtMost(image.width)
                    g.drawImage(
                        image,
                        x,
                        y,
                        x + drawWidth,
                        y + drawHeight,
                        0,
                        0,
                        drawWidth,
                        drawHeight,
                        null
                    )
                    x += image.width
                }
                y += image.height
            }
        }

        private fun progressRatio(element: GuiElement): Double {
            val start = element.startValue ?: return 0.5
            val min = element.minValue ?: 0.0
            val max = element.maxValue ?: 100.0
            if (max <= min) return 0.5
            return (start - min) / (max - min)
        }

        private fun intersectClip(oldClip: Shape?, newClip: Shape): Shape {
            if (oldClip == null) return newClip
            return java.awt.geom.Area(oldClip).apply {
                intersect(java.awt.geom.Area(newClip))
            }
        }

        private fun paintNinePatch(
            g: Graphics2D,
            image: BufferedImage,
            bounds: Rectangle,
            insets: SpriteInsets,
            tileCenter: Boolean
        ) {
            val left = insets.left.coerceIn(0, image.width / 2)
            val right = insets.right.coerceIn(0, image.width - left)
            val top = insets.top.coerceIn(0, image.height / 2)
            val bottom = insets.bottom.coerceIn(0, image.height - top)
            val centerSourceWidth = (image.width - left - right).coerceAtLeast(1)
            val centerSourceHeight = (image.height - top - bottom).coerceAtLeast(1)
            val (destLeft, destRight) = fitInsetsToSize(left, right, bounds.width)
            val (destTop, destBottom) = fitInsetsToSize(top, bottom, bounds.height)
            val centerDestWidth = (bounds.width - destLeft - destRight).coerceAtLeast(0)
            val centerDestHeight = (bounds.height - destTop - destBottom).coerceAtLeast(0)

            drawPatch(g, image, bounds.x, bounds.y, destLeft, destTop, 0, 0, left, top)
            drawPatch(g, image, bounds.x + bounds.width - destRight, bounds.y, destRight, destTop, image.width - right, 0, image.width, top)
            drawPatch(g, image, bounds.x, bounds.y + bounds.height - destBottom, destLeft, destBottom, 0, image.height - bottom, left, image.height)
            drawPatch(g, image, bounds.x + bounds.width - destRight, bounds.y + bounds.height - destBottom, destRight, destBottom, image.width - right, image.height - bottom, image.width, image.height)

            drawPatch(g, image, bounds.x + destLeft, bounds.y, centerDestWidth, destTop, left, 0, image.width - right, top)
            drawPatch(g, image, bounds.x + destLeft, bounds.y + bounds.height - destBottom, centerDestWidth, destBottom, left, image.height - bottom, image.width - right, image.height)
            drawPatch(g, image, bounds.x, bounds.y + destTop, destLeft, centerDestHeight, 0, top, left, image.height - bottom)
            drawPatch(g, image, bounds.x + bounds.width - destRight, bounds.y + destTop, destRight, centerDestHeight, image.width - right, top, image.width, image.height - bottom)

            val centerBounds = Rectangle(bounds.x + destLeft, bounds.y + destTop, centerDestWidth, centerDestHeight)
            if (centerBounds.width > 0 && centerBounds.height > 0 && tileCenter) {
                val centerImage = image.getSubimage(left, top, centerSourceWidth, centerSourceHeight)
                tileImage(g, centerImage, centerBounds)
            } else if (centerBounds.width > 0 && centerBounds.height > 0) {
                drawPatch(g, image, centerBounds.x, centerBounds.y, centerBounds.width, centerBounds.height, left, top, image.width - right, image.height - bottom)
            }
        }

        private fun fitInsetsToSize(start: Int, end: Int, total: Int): Pair<Int, Int> {
            if (total <= 0) return 0 to 0
            val sum = start + end
            if (sum <= total) return start to end
            val fittedStart = (total.toDouble() * start / sum).roundToInt().coerceIn(0, total)
            return fittedStart to (total - fittedStart)
        }

        private fun drawPatch(
            g: Graphics2D,
            image: BufferedImage,
            dx: Int,
            dy: Int,
            dw: Int,
            dh: Int,
            sx1: Int,
            sy1: Int,
            sx2: Int,
            sy2: Int
        ) {
            if (dw <= 0 || dh <= 0 || sx2 <= sx1 || sy2 <= sy1) return
            g.drawImage(image, dx, dy, dx + dw, dy + dh, sx1, sy1, sx2, sy2, null)
        }

        private fun paintSelection(g: Graphics2D, node: GuiLayoutNode, color: Color) {
            g.color = color
            g.stroke = BasicStroke(2f)
            val b = node.bounds
            g.drawRect(b.x - 2, b.y - 2, b.width + 4, b.height + 4)
        }

        private fun preloadResources(preloadRoot: GuiElement, version: Int) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val result = service.loadResources(listOf(preloadRoot)) {
                    version != preloadVersion.get()
                }
                ApplicationManager.getApplication().invokeLater({
                    applyPreloadResult(preloadRoot, version, result)
                }, ModalityState.any())
            }
        }

        private fun applyPreloadResult(
            preloadRoot: GuiElement,
            version: Int,
            result: GuiPreviewResources
        ) {
            if (version != preloadVersion.get() || root !== preloadRoot) return

            mergeResources(result)
            ready = true
            nodes = layout(preloadRoot)
            logicalSize = computeLogicalSize(nodes)
            revalidate()
            repaint()
        }

        private fun cachedSpriteInfo(element: GuiElement): SpriteInfo? {
            for (sprite in spriteCandidates(element)) {
                val info = spriteInfoCache[sprite]
                if (info?.primaryImagePath != null) return info
            }
            return null
        }

        private fun isSpriteKnownMissing(element: GuiElement): Boolean {
            val candidates = spriteCandidates(element)
            return candidates.isNotEmpty() && candidates.all { sprite ->
                spriteInfoCache.containsKey(sprite) && spriteInfoCache[sprite]?.primaryImagePath == null
            }
        }

        private fun cachedLocalizedText(key: String?): String? {
            return normalisedLocalisationKey(key)?.let { localisationCache[it] }
        }

        private fun isLocalisationKnownMissing(key: String): Boolean {
            val normalised = normalisedLocalisationKey(key) ?: return false
            return localisationCache.containsKey(normalised) && localisationCache[normalised] == null
        }

        private fun refreshLocalisationPreference() {
            val currentKey = GuiPreviewService.localisationCacheKey()
            if (localisationCacheKey != currentKey) {
                localisationCache.clear()
                localisationCacheKey = currentKey
            }
        }

        private fun mergeResources(resources: GuiPreviewResources) {
            if (resources.localisationKey != localisationCacheKey) {
                localisationCache.clear()
                localisationCacheKey = resources.localisationKey
            }
            spriteInfoCache.putAll(resources.sprites)
            localisationCache.putAll(resources.localisations)
            for ((path, image) in resources.images) {
                imageCache.putIfAbsent(path, image)
            }
        }

        private fun isRootReady(element: GuiElement): Boolean {
            if (spriteCandidates(element).any { it !in spriteInfoCache }) return false
            normalisedLocalisationKey(element.text)?.let {
                if (it !in localisationCache) return false
            }
            return element.children.all(::isRootReady)
        }

        private fun spriteCandidates(element: GuiElement): List<String> {
            return element.resolvedSpriteCandidates()
        }

        private fun normalisedLocalisationKey(value: String?): String? {
            return value?.trim()?.trim('"')?.takeIf { it.isNotBlank() }
        }

        private fun findNodeAt(point: Point): GuiLayoutNode? {
            return nodes.asReversed().firstOrNull { node ->
                node.bounds.contains(point) && node.clipBounds?.contains(point) == true
            }
        }

        private fun isDescendantOfDragged(node: GuiLayoutNode): Boolean {
            val dragged = pressedHit ?: return false
            if (!draggingNode) return false
            if (sameElement(node.element, dragged.element)) return true
            return containsElement(dragged.element, node.element)
        }

        private fun containsElement(parent: GuiElement, target: GuiElement): Boolean {
            for (child in parent.children) {
                if (sameElement(child, target) || containsElement(child, target)) return true
            }
            return false
        }

        private fun dragPaintOffset(node: GuiLayoutNode): Point? {
            if (!draggingNode || pressedHit == null) return null
            if (!isDescendantOfDragged(node)) return null
            if (dragDeltaX == 0 && dragDeltaY == 0) return null
            return Point(dragDeltaX, dragDeltaY)
        }

        private fun offsetNodeForPaint(node: GuiLayoutNode): GuiLayoutNode {
            val offset = dragPaintOffset(node) ?: return node
            return offsetNode(node, offset)
        }

        private fun commitElementDrag(node: GuiLayoutNode, basePosition: GuiPoint, dx: Int, dy: Int) {
            if (dx == 0 && dy == 0) return
            if (basePosition.xValue.percent || basePosition.yValue.percent) {
                repaint()
                return
            }
            val writeX = basePosition.xValue.asFallbackPixels() + dx
            val writeY = basePosition.yValue.asFallbackPixels() + dy
            val ok = service.updateElementPosition(node.element, writeX, writeY)
            if (!ok) {
                repaint()
                return
            }
            applyLocalPositionUpdate(node.element, GuiPoint(GuiValue.pixels(writeX), GuiValue.pixels(writeY)))
            val currentRoot = root
            if (currentRoot != null) {
                nodes = layout(currentRoot)
                logicalSize = computeLogicalSize(nodes)
                revalidate()
            }
            repaint()
        }

        private fun sameElement(a: GuiElement, b: GuiElement): Boolean {
            return when {
                a.sourceOffset >= 0 &&
                    b.sourceOffset >= 0 &&
                    a.sourceOffset == b.sourceOffset &&
                    a.sourceFilePath == b.sourceFilePath -> true
                else -> a.type == b.type &&
                    a.name == b.name &&
                    a.sourceLine == b.sourceLine &&
                    a.sourceFilePath == b.sourceFilePath
            }
        }

        private fun applyLocalPositionUpdate(target: GuiElement, position: GuiPoint) {
            val currentRoot = root ?: return
            root = replaceElementPosition(currentRoot, target, position)
        }

        private fun replaceElementPosition(element: GuiElement, target: GuiElement, position: GuiPoint): GuiElement {
            if (sameElement(element, target)) {
                return element.copy(position = position)
            }
            if (element.children.isEmpty()) return element
            var changed = false
            val nextChildren = element.children.map { child ->
                val updated = replaceElementPosition(child, target, position)
                if (updated !== child) changed = true
                updated
            }
            return if (changed) element.copy(children = nextChildren) else element
        }


    }
}
