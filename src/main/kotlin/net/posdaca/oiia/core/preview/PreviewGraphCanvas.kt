package net.posdaca.oiia.core.preview

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBFont
import net.posdaca.oiia.core.PreviewImageLoader
import net.posdaca.oiia.core.PreviewTextLayout
import java.awt.AWTEvent
import java.awt.BasicStroke
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Polygon
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.swing.Scrollable
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager
import kotlin.math.pow
import kotlin.math.roundToInt

internal abstract class PreviewGraphCanvas<THit>(
    protected val project: Project
) : JBPanel<JBPanel<*>>(null), Scrollable {

    protected val nodeWidth = JBUIScale.scale(132)
    protected val nodeHeight = JBUIScale.scale(116)
    protected open val padding = JBUIScale.scale(40)
    protected var zoomFactor = 1.0

    protected var selectedId: String? = null
    protected var hoveredId: String? = null
    protected val clickHint = PreviewClickHint(this)
    private var dragPressScreenPoint: Point? = null
    private var dragScrollStart: Point? = null
    protected var dragStartLogicalPoint: Point? = null
    protected var draggingView = false
    protected var draggingNode = false
    protected var pressedHit: THit? = null

    protected val logicalPositions = mutableMapOf<String, Point>()
    protected val logicalNodeBounds = mutableMapOf<String, Rectangle>()
    private val iconCache = mutableMapOf<String, BufferedImage>()

    protected open val horizontalGap: Int = 0
    protected open val verticalGap: Int = 0
    protected open val minZoom: Double = 0.15
    protected open val maxZoom: Double = 4.0
    protected open val scrollUnit: Int = JBUIScale.scale(20)
    protected open val scrollBlock: Int = JBUIScale.scale(160)

    protected abstract fun isDocumentEmpty(): Boolean
    protected abstract fun computeLogicalSize(): Dimension
    protected abstract fun findHit(logical: Point): THit?
    protected abstract fun hitId(hit: THit): String
    protected abstract fun navigateTo(hit: THit)
    protected abstract fun buildHintHtml(hit: THit): String
    protected abstract fun paintGraph(g2d: Graphics2D)

    protected open fun canDragNode(hit: THit): Boolean = false
    protected open fun onNodeDragStarted(hit: THit) {}
    protected open fun onNodeDragged(hit: THit, startLogical: Point, currentLogical: Point) {}
    protected open fun onNodeDragFinished(hit: THit): Boolean = false

    init {
        isOpaque = true
        background = JBColor.PanelBackground
        enableEvents(AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_WHEEL_EVENT_MASK)
        ToolTipManager.sharedInstance().registerComponent(this)
        installInteraction()
    }

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

    private fun installInteraction() {
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1) return
                if (e.clickCount > 1) clickHint.cancel()
                val logicalPt = screenToLogical(e)
                pressedHit = findHit(logicalPt)
                dragPressScreenPoint = Point(e.locationOnScreen)
                dragScrollStart = Point(scrollableHsb?.value ?: 0, scrollableVsb?.value ?: 0)
                dragStartLogicalPoint = logicalPt
                draggingView = false
                draggingNode = false
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1) return
                val wasDraggingView = draggingView
                val wasDraggingNode = draggingNode
                val dragged = pressedHit
                cursor = Cursor.getDefaultCursor()
                dragPressScreenPoint = null
                dragScrollStart = null
                dragStartLogicalPoint = null
                draggingView = false
                draggingNode = false

                val consumed = wasDraggingNode && dragged != null && onNodeDragFinished(dragged)
                if (!consumed && !wasDraggingView && !wasDraggingNode) {
                    val clicked = findHit(screenToLogical(e))
                    if (clicked != null && dragged != null && hitId(clicked) == hitId(dragged)) {
                        selectedId = hitId(clicked)
                        if (e.clickCount >= 2) {
                            clickHint.cancel()
                            clickHint.hide()
                            navigateTo(clicked)
                        } else {
                            clickHint.schedule(e.point) { buildHintHtml(clicked) }
                        }
                    } else if (clicked == null) {
                        clickHint.cancel()
                        selectedId = null
                        clickHint.hide()
                    }
                    repaint()
                }
                pressedHit = null
            }

            override fun mouseExited(e: MouseEvent) {
                if (draggingNode || draggingView) return
                if (hoveredId != null) {
                    hoveredId = null
                    repaint()
                }
            }
        })

        addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val hovered = findHit(screenToLogical(e))
                val prevId = hoveredId
                hoveredId = hovered?.let(::hitId)
                if (prevId != hoveredId) repaint()
                cursor = if (hovered != null && canDragNode(hovered)) {
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
                if (!draggingView && !draggingNode &&
                    screenDx * screenDx + screenDy * screenDy < threshold * threshold
                ) return

                val startLogical = dragStartLogicalPoint
                val hit = pressedHit
                if (hit != null && canDragNode(hit) && startLogical != null) {
                    if (!draggingNode) {
                        clickHint.cancel()
                        clickHint.hide()
                        selectedId = hitId(hit)
                        draggingNode = true
                        onNodeDragStarted(hit)
                    }
                    onNodeDragged(hit, startLogical, screenToLogical(e))
                    cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                    repaint()
                    return
                }

                val scrollStart = dragScrollStart ?: return
                if (!draggingView) clickHint.cancel()
                draggingView = true
                cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                setScrollBarValue(scrollableHsb, scrollStart.x - screenDx)
                setScrollBarValue(scrollableVsb, scrollStart.y - screenDy)
            }
        })

        addMouseWheelListener { e: MouseWheelEvent ->
            val oldZoom = zoomFactor
            zoomFactor = (zoomFactor * 1.15.pow(-e.wheelRotation.toDouble())).coerceIn(minZoom, maxZoom)
            val hsb = scrollableHsb
            val vsb = scrollableVsb
            val oldVx = hsb?.value ?: 0
            val oldVy = vsb?.value ?: 0
            revalidate()
            val scaleRatio = zoomFactor / oldZoom
            setScrollBarValue(hsb, (e.x * scaleRatio - (e.x - oldVx)).toInt().coerceAtLeast(0))
            setScrollBarValue(vsb, (e.y * scaleRatio - (e.y - oldVy)).toInt().coerceAtLeast(0))
            repaint()
        }
    }

    override fun updateUI() {
        super.updateUI()
        background = JBColor.PanelBackground
    }

    override fun getToolTipText(event: MouseEvent?): String? {
        if (clickHint.isVisible || event == null) return null
        val hit = findHit(screenToLogical(event)) ?: return null
        return buildHintHtml(hit)
    }

    protected fun setScrollBarValue(scrollBar: javax.swing.JScrollBar?, value: Int) {
        if (scrollBar == null) return
        scrollBar.value = value.coerceIn(scrollBar.minimum, (scrollBar.maximum - scrollBar.visibleAmount).coerceAtLeast(scrollBar.minimum))
    }

    protected fun navigateToSource(path: String?, line: Int) {
        PreviewNavigation.open(project, path, line)
    }

    protected fun isHighlighted(vararg ids: String): Boolean =
        ids.any { it == selectedId || it == hoveredId }

    protected fun drawOrthogonalLink(
        g2d: Graphics2D,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        active: Boolean,
        horizontalFirst: Boolean = false
    ) {
        g2d.color = if (active) PreviewNodeStyle.linkActive else PreviewNodeStyle.link
        g2d.stroke = BasicStroke(if (active) JBUIScale.scale(2.5f) else JBUIScale.scale(1.5f))
        val path = Path2D.Double()
        if (horizontalFirst) {
            val midX = (startX + endX) / 2.0
            path.moveTo(startX.toDouble(), startY.toDouble())
            path.lineTo(midX, startY.toDouble())
            path.lineTo(midX, endY.toDouble())
            path.lineTo(endX.toDouble(), endY.toDouble())
        } else {
            val midY = (startY + endY) / 2.0
            path.moveTo(startX.toDouble(), startY.toDouble())
            path.lineTo(startX.toDouble(), midY)
            path.lineTo(endX.toDouble(), midY)
            path.lineTo(endX.toDouble(), endY.toDouble())
        }
        g2d.draw(path)
        val arrowSize = JBUIScale.scale(8)
        val arrow = Polygon()
        if (horizontalFirst && endX != startX) {
            val sign = if (endX > startX) -1 else 1
            arrow.addPoint(endX, endY)
            arrow.addPoint(endX + sign * arrowSize, endY - arrowSize / 2)
            arrow.addPoint(endX + sign * arrowSize, endY + arrowSize / 2)
        } else {
            arrow.addPoint(endX, endY)
            arrow.addPoint(endX - arrowSize / 2, endY + arrowSize)
            arrow.addPoint(endX + arrowSize / 2, endY + arrowSize)
        }
        g2d.fill(arrow)
    }

    protected fun resetScroll() {
        setScrollBarValue(scrollableHsb, 0)
        setScrollBarValue(scrollableVsb, 0)
    }

    protected fun resetHoverAndHints() {
        clickHint.dispose()
        selectedId = null
        hoveredId = null
    }

    protected fun screenToLogical(e: MouseEvent): Point = screenToLogical(e.point)

    protected fun screenToLogical(point: Point): Point =
        Point((point.x / zoomFactor).roundToInt(), (point.y / zoomFactor).roundToInt())

    protected fun loadIcon(path: String): BufferedImage? = PreviewImageLoader.load(path, iconCache)

    protected fun drawNodeCard(
        g2d: Graphics2D,
        pos: Point,
        title: String,
        iconPath: String?,
        fallbackText: String?,
        selected: Boolean,
        hovered: Boolean,
        shared: Boolean = false,
        badge: Boolean = false
    ) {
        val bgColor = when {
            selected -> PreviewNodeStyle.selectedBackground
            hovered -> PreviewNodeStyle.hoverBackground
            shared -> PreviewNodeStyle.sharedBackground
            else -> PreviewNodeStyle.background
        }
        val borderColor = when {
            selected -> PreviewNodeStyle.selectedBorder
            hovered -> PreviewNodeStyle.hoverBorder
            shared -> PreviewNodeStyle.sharedBorder
            else -> PreviewNodeStyle.border
        }
        val bw = if (selected || hovered) JBUIScale.scale(2.5f) else JBUIScale.scale(1.5f)
        val arc = JBUIScale.scale(8).toDouble()
        val card = RoundRectangle2D.Double(
            pos.x.toDouble(),
            pos.y.toDouble(),
            nodeWidth.toDouble(),
            nodeHeight.toDouble(),
            arc,
            arc
        )
        g2d.color = bgColor
        g2d.fill(card)
        g2d.color = borderColor
        g2d.stroke = BasicStroke(bw)
        g2d.draw(card)

        val iconSz = JBUIScale.scale(72)
        val iconX = pos.x + (nodeWidth - iconSz) / 2
        val iconY = pos.y + JBUIScale.scale(9)
        val iconArc = JBUIScale.scale(6).toDouble()
        val iconShape = RoundRectangle2D.Double(
            iconX.toDouble(),
            iconY.toDouble(),
            iconSz.toDouble(),
            iconSz.toDouble(),
            iconArc,
            iconArc
        )
        val image = iconPath?.let(::loadIcon)
        if (image != null) {
            val oldClip = g2d.clip
            g2d.clip = intersectClip(oldClip, iconShape)
            g2d.drawImage(image, iconX, iconY, iconSz, iconSz, null)
            g2d.clip = oldClip
            g2d.color = borderColor
            g2d.stroke = BasicStroke(JBUIScale.scale(0.5f))
            g2d.draw(iconShape)
        } else {
            g2d.color = PreviewNodeStyle.iconFallbackBackground
            g2d.fill(iconShape)
            if (!fallbackText.isNullOrBlank()) {
                g2d.color = PreviewNodeStyle.iconFallbackText
                g2d.font = JBFont.label().deriveFont(Font.PLAIN, 10f)
                val text = if (fallbackText.length > 8) fallbackText.take(6) + ".." else fallbackText
                val metrics = g2d.fontMetrics
                g2d.drawString(
                    text,
                    iconX + (iconSz - metrics.stringWidth(text)) / 2,
                    iconY + iconSz / 2 + metrics.ascent / 3
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
        g2d.color = PreviewNodeStyle.titleBandBackground
        g2d.fill(titleShape)
        g2d.color = borderColor
        g2d.stroke = BasicStroke(JBUIScale.scale(0.8f))
        g2d.drawLine(pos.x, titleBandY, pos.x + nodeWidth, titleBandY)

        if (badge) {
            g2d.color = PreviewNodeStyle.badge
            g2d.fillOval(
                pos.x + nodeWidth - JBUIScale.scale(13),
                pos.y + JBUIScale.scale(5),
                JBUIScale.scale(8),
                JBUIScale.scale(8)
            )
        }

        g2d.color = PreviewNodeStyle.title
        g2d.font = JBFont.label().deriveFont(Font.BOLD, 12f)
        PreviewTextLayout.drawCenteredTitle(
            g2d,
            title,
            Rectangle(
                pos.x + JBUIScale.scale(8),
                titleBandY + JBUIScale.scale(4),
                nodeWidth - JBUIScale.scale(16),
                titleBandHeight - JBUIScale.scale(8)
            ),
            PreviewNodeStyle.TITLE_MAX_LINES
        )
    }

    private fun intersectClip(currentClip: Shape?, shape: Shape): Shape {
        if (currentClip == null) return shape
        val area = Area(currentClip)
        area.intersect(Area(shape))
        return area
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2d = g.create() as Graphics2D
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        val original = g2d.transform
        val zoom = AffineTransform()
        zoom.scale(zoomFactor, zoomFactor)
        g2d.transform(zoom)
        paintGraph(g2d)
        g2d.transform = original
        g2d.dispose()
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = scrollUnit

    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = scrollBlock

    override fun getScrollableTracksViewportWidth(): Boolean = false
    override fun getScrollableTracksViewportHeight(): Boolean = false

    override fun getPreferredSize(): Dimension {
        if (isDocumentEmpty()) return Dimension(400, 300)
        val logicalSize = computeLogicalSize()
        val width = ((logicalSize.width + padding) * zoomFactor + padding).roundToInt() + 50
        val height = ((logicalSize.height + padding) * zoomFactor + padding).roundToInt() + 50
        return Dimension(width.coerceAtLeast(400), height.coerceAtLeast(300))
    }

    override fun removeNotify() {
        ToolTipManager.sharedInstance().unregisterComponent(this)
        clickHint.dispose()
        super.removeNotify()
        iconCache.clear()
    }
}