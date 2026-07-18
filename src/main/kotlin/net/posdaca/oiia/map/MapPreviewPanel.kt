package net.posdaca.oiia.map

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.JBColor
import com.intellij.ui.LightweightHint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBFont
import OiiaBundle
import net.posdaca.oiia.core.PreviewHintSupport
import java.awt.BorderLayout
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.ToolTipManager
import kotlin.io.path.pathString
import kotlin.math.roundToInt
import kotlin.math.pow

class MapPreviewPanel(private val project: Project) : JBPanel<JBPanel<*>>(BorderLayout()) {

    private val service = MapPreviewService(project)
    private val canvas = MapCanvas()
    private val statusLabel = JBLabel(OiiaBundle.message("toolwindow.MapPreview.loading"))
    private val loadVersion = AtomicInteger(0)
    private val changeCheckInProgress = AtomicBoolean(false)
    private var loadedData: LoadedMapData? = null
    private var loading = false
    private var messageBusConnection: MessageBusConnection? = null
    private var colorMode = MapPreviewMode.PROVINCE
    private var borderMode = MapPreviewMode.PROVINCE
    private var showBorders = true
    private var smoothBorders = false
    private var showLabels = false
    private val reloadTimer = Timer(1200) {
        if (isShowing) reloadIfChanged()
    }.apply { isRepeats = true }

    private fun msg(key: String, vararg params: Any?): String = OiiaBundle.message("toolwindow.MapPreview.$key", *params)

    init {
        background = JBColor.PanelBackground
        add(createToolbar(), BorderLayout.NORTH)

        val scrollPane = JBScrollPane(canvas)
        scrollPane.border = null
        scrollPane.viewport.background = JBColor.PanelBackground
        scrollPane.viewport.scrollMode = JViewport.BLIT_SCROLL_MODE
        scrollPane.horizontalScrollBar.addAdjustmentListener {
            if (!it.valueIsAdjusting) canvas.recenterHorizontalScrollIfNeeded()
        }
        scrollPane.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                canvas.ensureMinimumZoomForViewport()
            }
        })
        add(scrollPane, BorderLayout.CENTER)

        connectVfsListener()
        reloadTimer.start()
        reload()
    }

    private fun connectVfsListener() {
        if (messageBusConnection != null) return
        messageBusConnection = project.messageBus.connect()
        messageBusConnection?.subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (!isShowing) return
                    if (events.any { service.isMapSourcePath(it.path) }) {
                        SwingUtilities.invokeLater { reload() }
                    }
                }
            }
        )
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

        val actions = JPanel()
        actions.isOpaque = false

        val colorSelector = createModeSelector(colorMode) { nextMode ->
            colorMode = nextMode
            canvas.setColorMode(nextMode)
        }
        val borderSelector = createModeSelector(borderMode) { nextMode ->
            borderMode = nextMode
            canvas.setBorderMode(nextMode)
        }

        val borderToggle = JCheckBox(msg("borders"), showBorders)
        borderToggle.isOpaque = false
        borderToggle.addActionListener {
            showBorders = borderToggle.isSelected
            canvas.setShowBorders(showBorders)
        }
        val smoothBorderToggle = JCheckBox(msg("smooth.borders"), smoothBorders)
        smoothBorderToggle.isOpaque = false
        smoothBorderToggle.addActionListener {
            smoothBorders = smoothBorderToggle.isSelected
            canvas.setSmoothBorders(smoothBorders)
        }
        val labelToggle = JCheckBox(msg("labels"), showLabels)
        labelToggle.isOpaque = false
        labelToggle.addActionListener {
            showLabels = labelToggle.isSelected
            canvas.repaint()
        }
        val reloadButton = JButton(msg("reload"))
        reloadButton.addActionListener { reload() }
        actions.add(JBLabel(msg("color.mode")))
        actions.add(colorSelector)
        actions.add(JBLabel(msg("border.mode")))
        actions.add(borderSelector)
        actions.add(borderToggle)
        actions.add(smoothBorderToggle)
        actions.add(labelToggle)
        actions.add(reloadButton)
        panel.add(actions, BorderLayout.EAST)
        return panel
    }

    private fun createModeSelector(
        selectedMode: MapPreviewMode,
        onModeChanged: (MapPreviewMode) -> Unit
    ): ComboBox<MapPreviewMode> {
        val selector = ComboBox(MapPreviewMode.entries.toTypedArray())
        selector.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): java.awt.Component {
                val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                if (value is MapPreviewMode) {
                    text = OiiaBundle.message(value.messageKey)
                }
                return component
            }
        }
        selector.selectedItem = selectedMode
        selector.addActionListener {
            onModeChanged(selector.selectedItem as? MapPreviewMode ?: MapPreviewMode.PROVINCE)
        }
        return selector
    }

    private fun reloadIfChanged() {
        if (loading) return
        val current = loadedData
        if (current == null) {
            reload()
            return
        }
        if (!changeCheckInProgress.compareAndSet(false, true)) return
        val version = loadVersion.get()
        ApplicationManager.getApplication().executeOnPooledThread {
            val changed = try {
                service.currentSourceStamp(current) != current.sourceStamp
            } catch (_: Exception) {
                true
            }
            ApplicationManager.getApplication().invokeLater {
                changeCheckInProgress.set(false)
                if (version == loadVersion.get() && isShowing && !loading && changed) reload()
            }
        }
    }

    private fun reload() {
        if (loading) return
        loading = true
        val version = loadVersion.incrementAndGet()
        statusLabel.text = msg("loading")

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = service.loadMap { step ->
                ApplicationManager.getApplication().invokeLater {
                    if (version == loadVersion.get() && loading) {
                        statusLabel.text = msg(step.messageKey)
                        canvas.repaint()
                    }
                }
            }
            ApplicationManager.getApplication().invokeLater {
                if (version != loadVersion.get()) return@invokeLater
                loading = false
                applyLoadResult(result)
            }
        }
    }

    private fun applyLoadResult(result: MapLoadResult) {
        when (result) {
            is MapLoadResult.Loaded -> {
                loadedData = result.data
                canvas.setData(result.data)
                val image = result.data.provincesImage
                val definitions = result.data.provinceByColor.size
                val states = result.data.stateById.size
                val countries = result.data.countryByTag.size
                val strategicRegions = result.data.strategicRegionById.size
                statusLabel.text =
                    msg(
                        "status.loaded",
                        image.width,
                        image.height,
                        definitions,
                        states,
                        countries,
                        strategicRegions
                    )
            }

            is MapLoadResult.Missing -> {
                loadedData = null
                canvas.setData(null)
                statusLabel.text = msg("missing")
            }

            is MapLoadResult.Failed -> {
                loadedData = null
                canvas.setData(null)
                statusLabel.text = result.message
            }
        }
    }

    override fun addNotify() {
        super.addNotify()
        connectVfsListener()
        reloadTimer.start()
        reloadIfChanged()
    }

    override fun removeNotify() {
        reloadTimer.stop()
        messageBusConnection?.disconnect()
        messageBusConnection = null
        super.removeNotify()
    }

    private inner class MapCanvas : JBPanel<JBPanel<*>>(null), Scrollable {
        private var data: LoadedMapData? = null
        private var zoom = 1.0
        private var fillMode = MapPreviewMode.PROVINCE
        private var outlineMode = MapPreviewMode.PROVINCE
        private var bordersVisible = showBorders
        private var smoothBorders = false
        private var hoverSelection: HoverSelection? = null
        private var hoverOverlay: HoverOverlay? = null
        private var dragStartScreenPoint: Point? = null
        private var dragStartScroll: Point? = null
        private val paintColorCache = mutableMapOf<Int, Color>()
        private var renderChunkIndex: Map<MapTileKey, MapRenderChunk> = emptyMap()
        private var borderChunkIndex: Map<MapPreviewMode, Map<MapTileKey, MapBorderChunk>> = emptyMap()
        private val tileCache = object : LinkedHashMap<MapTileKey, BufferedImage>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MapTileKey, BufferedImage>?): Boolean {
                return size > MAX_TILE_CACHE_SIZE
            }
        }
        private val borderTileCache = object : LinkedHashMap<BorderTileKey, BufferedImage>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<BorderTileKey, BufferedImage>?): Boolean {
                return size > maxBorderTileCacheSize()
            }
        }
        private var lockedHint: LightweightHint? = null
        private var singleClickTimer: Timer? = null
        private var recenteringScroll = false
        private var fitToMinimumAfterLayout = false

        private val scrollPane: JBScrollPane?
            get() = SwingUtilities.getAncestorOfClass(JBScrollPane::class.java, this) as? JBScrollPane

        init {
            isOpaque = true
            background = JBColor.PanelBackground
            ToolTipManager.sharedInstance().registerComponent(this)

            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (e.button != MouseEvent.BUTTON1) return
                    if (e.clickCount > 1) cancelPendingSingleClick()
                    dragStartScreenPoint = Point(e.locationOnScreen)
                    dragStartScroll = Point(
                        scrollPane?.horizontalScrollBar?.value ?: 0,
                        scrollPane?.verticalScrollBar?.value ?: 0
                    )
                    cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                }

                override fun mouseReleased(e: MouseEvent) {
                    cursor = Cursor.getDefaultCursor()
                    dragStartScreenPoint = null
                    dragStartScroll = null
                }

                override fun mouseClicked(e: MouseEvent) {
                    if (e.button != MouseEvent.BUTTON1) return
                    val sample = sampleProvince(e.point)
                    if (sample == null) {
                        if (e.clickCount == 1) hideMapHint()
                        return
                    }
                    if (e.clickCount >= 2) {
                        cancelPendingSingleClick()
                        hideMapHint()
                        navigateToSource(sample)
                    } else if (e.clickCount == 1) {
                        scheduleMapHint(sample, e.point)
                    }
                }
            })

            addMouseMotionListener(object : MouseAdapter() {
                override fun mouseDragged(e: MouseEvent) {
                    cancelPendingSingleClick()
                    val startPoint = dragStartScreenPoint ?: return
                    val startScroll = dragStartScroll ?: return
                    val scroll = scrollPane ?: return
                    val dx = e.locationOnScreen.x - startPoint.x
                    val dy = e.locationOnScreen.y - startPoint.y
                    setHorizontalScrollValue(startScroll.x - dx)
                    setScrollValue(scroll.verticalScrollBar, startScroll.y - dy)
                }

                override fun mouseMoved(e: MouseEvent) {
                    updateHoverStatus(e.point)
                }
            })

            addMouseWheelListener { e: MouseWheelEvent ->
                val rotation = -e.preciseWheelRotation
                fitToMinimumAfterLayout = false
                val nextZoom = zoomInBounds(zoom * 1.15.pow(rotation))
                setZoom(nextZoom, e.point)
            }
        }

        private fun scheduleMapHint(sample: ProvinceSample, point: Point) {
            cancelPendingSingleClick()
            val hintPoint = Point(point)
            singleClickTimer = Timer(PreviewHintSupport.multiClickInterval()) {
                singleClickTimer = null
                if (isShowing) showMapHint(sample, hintPoint)
            }.apply {
                isRepeats = false
                start()
            }
        }

        private fun cancelPendingSingleClick() {
            singleClickTimer?.stop()
            singleClickTimer = null
        }

        private fun showMapHint(sample: ProvinceSample, point: Point) {
            hideMapHint()
            val hint = PreviewHintSupport.showHint(this, point, buildDetailText(sample)) { hiddenHint ->
                if (lockedHint === hiddenHint) lockedHint = null
            }
            lockedHint = hint
        }

        private fun hideMapHint() {
            val hint = lockedHint
            lockedHint = null
            PreviewHintSupport.hideHint(hint)
        }

        fun setColorMode(nextMode: MapPreviewMode) {
            cancelPendingSingleClick()
            hideMapHint()
            if (fillMode == nextMode) return
            fillMode = nextMode
            clearTileCache()
            revalidate()
            repaint()
        }

        fun setBorderMode(nextMode: MapPreviewMode) {
            cancelPendingSingleClick()
            hideMapHint()
            if (outlineMode == nextMode) return
            outlineMode = nextMode
            hoverSelection = null
            hoverOverlay = null
            clearBorderTileCache()
            revalidate()
            repaint()
        }

        fun setShowBorders(enabled: Boolean) {
            if (bordersVisible == enabled) return
            bordersVisible = enabled
            repaint()
        }

        fun setSmoothBorders(enabled: Boolean) {
            if (smoothBorders == enabled) return
            smoothBorders = enabled
            clearBorderTileCache()
            repaint()
        }

        fun setData(nextData: LoadedMapData?) {
            cancelPendingSingleClick()
            hideMapHint()
            data = nextData
            bordersVisible = this@MapPreviewPanel.showBorders
            smoothBorders = this@MapPreviewPanel.smoothBorders
            renderChunkIndex = nextData?.renderChunks
                ?.associateBy { MapTileKey(it.x / MAP_TILE_SIZE, it.y / MAP_TILE_SIZE) }
                .orEmpty()
            borderChunkIndex = nextData?.borderChunks
                ?.mapValues { (_, chunks) ->
                    chunks.associateBy { MapTileKey(it.x / MAP_TILE_SIZE, it.y / MAP_TILE_SIZE) }
                }
                .orEmpty()
            fitToMinimumAfterLayout = nextData != null
            zoom = nextData?.let { zoomInBounds(minZoom(it)) } ?: 1.0
            hoverSelection = null
            hoverOverlay = null
            paintColorCache.clear()
            clearTileCache()
            clearBorderTileCache()
            revalidate()
            repaint()
            SwingUtilities.invokeLater {
                fitToMinimumZoom(center = true)
            }
        }

        fun setZoom(nextZoom: Double, anchor: Point) {
            val oldZoom = zoom
            val clampedZoom = zoomInBounds(nextZoom)
            if (oldZoom == clampedZoom) return
            val viewport = scrollPane?.viewport
            val anchorInViewport = if (viewport != null) SwingUtilities.convertPoint(this, anchor, viewport) else Point(anchor)
            val anchorImageX = anchor.x / oldZoom
            val anchorImageY = anchor.y / oldZoom
            zoom = clampedZoom
            clearBorderTileCache()
            val nextSize = preferredSize
            setSize(nextSize)
            revalidate()
            if (viewport != null) {
                val nextViewX = (anchorImageX * clampedZoom - anchorInViewport.x).roundToInt()
                val nextViewY = (anchorImageY * clampedZoom - anchorInViewport.y).roundToInt()
                viewport.viewPosition = clampedViewPosition(nextViewX, nextViewY, nextSize, viewport.extentSize)
                recenterHorizontalScrollIfNeeded()
            }
            repaint()
        }

        override fun getToolTipText(event: MouseEvent?): String? {
            val eventPoint = event?.point ?: return null
            val sample = sampleProvince(eventPoint) ?: return null
            return buildDetailText(sample)
        }

        override fun getPreferredSize(): Dimension {
            val image = data?.provincesImage ?: return Dimension(JBUIScale.scale(600), JBUIScale.scale(360))
            return Dimension((image.width * zoom * LOOP_COPIES).toInt(), (image.height * zoom).toInt())
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val current = data
            val image = current?.provincesImage
            if (current == null || image == null) {
                paintEmptyMessage(g)
                return
            }

            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED)
            val paintClip = g2.clipBounds ?: visibleRect
            val scaledWidth = (image.width * zoom).toInt()
            for (copy in 0 until LOOP_COPIES) {
                val offsetX = copy * scaledWidth
                paintCachedMapTiles(g2, current, offsetX, paintClip)
            }
            if (bordersVisible) paintCachedBorderTiles(g2, current, paintClip)
            paintHoverHighlight(g2, paintClip)
            if (showLabels) paintMapLabels(g2, paintClip)
            g2.dispose()
        }

        private fun paintCachedMapTiles(g2: Graphics2D, current: LoadedMapData, offsetX: Int, clip: Rectangle) {
            val visible = visibleImageRect(offsetX, clip) ?: return
            val range = visibleTileRange(current, visible) ?: return
            val interpolation = g2.getRenderingHint(RenderingHints.KEY_INTERPOLATION)
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
                for (tileY in range.minTileY..range.maxTileY) {
                    for (tileX in range.minTileX..range.maxTileX) {
                        val key = MapTileKey(tileX, tileY)
                        val tile = tileCache[key] ?: renderMapTile(current, key).also { tileCache[key] = it }
                        drawTile(g2, tile, tileX, tileY, offsetX)
                    }
                }
            } finally {
                if (interpolation != null) {
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation)
                }
            }
        }

        private fun visibleTileRange(current: LoadedMapData, visible: ImageRect): MapTileRange? {
            val image = current.provincesImage
            if (image.width <= 0 || image.height <= 0) return null
            val maxTileX = (image.width - 1) / MAP_TILE_SIZE
            val maxTileY = (image.height - 1) / MAP_TILE_SIZE
            val minTileX = (visible.minX.toInt() / MAP_TILE_SIZE).coerceIn(0, maxTileX)
            val minTileY = (visible.minY.toInt() / MAP_TILE_SIZE).coerceIn(0, maxTileY)
            val lastX = visible.maxX.toInt().coerceIn(0, image.width - 1)
            val lastY = visible.maxY.toInt().coerceIn(0, image.height - 1)
            val rangeMaxTileX = (lastX / MAP_TILE_SIZE).coerceIn(0, maxTileX)
            val rangeMaxTileY = (lastY / MAP_TILE_SIZE).coerceIn(0, maxTileY)
            if (minTileX > rangeMaxTileX || minTileY > rangeMaxTileY) return null
            return MapTileRange(minTileX, minTileY, rangeMaxTileX, rangeMaxTileY)
        }

        private fun paintColor(rgb: Int): Color {
            val normalized = rgb and 0xFFFFFF
            return paintColorCache.getOrPut(normalized) { Color(normalized) }
        }

        private fun drawTile(g2: Graphics2D, tile: BufferedImage, tileX: Int, tileY: Int, offsetX: Int) {
            val sourceX = tileX * MAP_TILE_SIZE
            val sourceY = tileY * MAP_TILE_SIZE
            val destX1 = offsetX + (sourceX * zoom).roundToInt()
            val destY1 = (sourceY * zoom).roundToInt()
            val destX2 = (offsetX + ((sourceX + tile.width) * zoom).roundToInt()).coerceAtLeast(destX1 + 1)
            val destY2 = (((sourceY + tile.height) * zoom).roundToInt()).coerceAtLeast(destY1 + 1)
            g2.drawImage(tile, destX1, destY1, destX2, destY2, 0, 0, tile.width, tile.height, null)
        }

        private fun renderMapTile(current: LoadedMapData, key: MapTileKey): BufferedImage {
            val image = current.provincesImage
            val tileLeft = key.x * MAP_TILE_SIZE
            val tileTop = key.y * MAP_TILE_SIZE
            val tileWidth = minOf(MAP_TILE_SIZE, image.width - tileLeft)
            val tileHeight = minOf(MAP_TILE_SIZE, image.height - tileTop)
            val tile = BufferedImage(tileWidth, tileHeight, BufferedImage.TYPE_INT_RGB)
            val g2 = tile.createGraphics()
            try {
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED)
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
                g2.color = background
                g2.fillRect(0, 0, tileWidth, tileHeight)
                g2.translate(-tileLeft, -tileTop)
                paintTileAreas(g2, key)
            } finally {
                g2.dispose()
            }
            return tile
        }

        private fun paintTileAreas(g2: Graphics2D, key: MapTileKey) {
            val chunk = renderChunkIndex[key] ?: return
            var currentColor = Int.MIN_VALUE
            for (cell in chunk.cells) {
                val color = cell.colorFor(fillMode)
                if (color != currentColor) {
                    g2.color = paintColor(color)
                    currentColor = color
                }
                val zone = cell.zone
                g2.fillRect(zone.x, zone.y, zone.width, zone.height)
            }
        }

        private fun visibleImageRect(offsetX: Int, clip: Rectangle): ImageRect? {
            val currentZoom = zoom.takeIf { it > 0.0 } ?: return null
            val minX = ((clip.x - offsetX).toDouble() / currentZoom).coerceAtLeast(0.0)
            val minY = (clip.y.toDouble() / currentZoom).coerceAtLeast(0.0)
            val maxX = ((clip.x + clip.width - offsetX).toDouble() / currentZoom)
            val maxY = ((clip.y + clip.height).toDouble() / currentZoom)
            val image = data?.provincesImage ?: return null
            if (maxX < 0.0 || maxY < 0.0 || minX > image.width || minY > image.height) return null
            return ImageRect(
                minX = minX.coerceAtMost(image.width.toDouble()),
                minY = minY.coerceAtMost(image.height.toDouble()),
                maxX = maxX.coerceIn(0.0, image.width.toDouble()),
                maxY = maxY.coerceIn(0.0, image.height.toDouble())
            )
        }

        private fun clearTileCache() {
            tileCache.clear()
        }

        private fun paintCachedBorderTiles(g2: Graphics2D, current: LoadedMapData, clip: Rectangle) {
            val imageWidth = current.provincesImage.width
            val scaledWidth = (imageWidth * zoom).toInt()
            for (copy in 0 until LOOP_COPIES) {
                val offsetX = copy * scaledWidth
                val visible = visibleImageRect(offsetX, clip) ?: continue
                val range = visibleTileRange(current, visible) ?: continue
                for (tileY in range.minTileY..range.maxTileY) {
                    for (tileX in range.minTileX..range.maxTileX) {
                        val tileKey = MapTileKey(tileX, tileY)
                        val bounds = tileScreenBounds(current, tileKey)
                        val cacheKey = BorderTileKey(tileX, tileY, outlineMode, smoothBorders, zoom.toBits())
                        val tile = borderTileCache[cacheKey]
                            ?: renderBorderTile(current, tileKey, bounds).also { borderTileCache[cacheKey] = it }
                        g2.drawImage(
                            tile,
                            offsetX + bounds.x - BORDER_TILE_PADDING,
                            bounds.y - BORDER_TILE_PADDING,
                            null
                        )
                    }
                }
            }
        }

        private fun renderBorderTile(current: LoadedMapData, key: MapTileKey, bounds: Rectangle): BufferedImage {
            val tile = BufferedImage(
                bounds.width + BORDER_TILE_PADDING * 2,
                bounds.height + BORDER_TILE_PADDING * 2,
                BufferedImage.TYPE_INT_ARGB
            )
            val g2 = tile.createGraphics()
            try {
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED)
                g2.translate(
                    BORDER_TILE_PADDING.toDouble() - bounds.x.toDouble(),
                    BORDER_TILE_PADDING.toDouble() - bounds.y.toDouble()
                )
                g2.scale(zoom, zoom)
                if (smoothBorders) {
                    paintSmoothBorderTile(g2, current, key)
                } else {
                    paintPixelBorderTile(g2, key)
                }
            } finally {
                g2.dispose()
            }
            return tile
        }

        private fun paintPixelBorderTile(g2: Graphics2D, key: MapTileKey) {
            val chunk = borderChunkIndex[outlineMode]?.get(key) ?: return
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
            g2.color = Color(0, 0, 0, 190)
            g2.stroke = BasicStroke(
                (1.0 / zoom).toFloat(),
                BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_MITER
            )
            for (segment in chunk.segments) {
                g2.drawLine(segment.x1, segment.y1, segment.x2, segment.y2)
            }
        }

        private fun paintSmoothBorderTile(g2: Graphics2D, current: LoadedMapData, key: MapTileKey) {
            val tileRect = tileImageRect(current, key, padding = 1.0 / zoom)
            val path = Path2D.Double()
            for (segment in current.smoothBorderSegmentsFor(outlineMode)) {
                if (!tileRect.intersectsSegment(segment.x1, segment.y1, segment.x2, segment.y2, 1.0)) continue
                path.moveTo(segment.x1, segment.y1)
                path.lineTo(segment.x2, segment.y2)
            }
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g2.color = Color(0, 0, 0, 190)
            g2.stroke = BasicStroke(
                (1.0 / zoom).coerceIn(0.25, 1.0).toFloat(),
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND
            )
            g2.draw(path)
        }

        private fun tileScreenBounds(current: LoadedMapData, key: MapTileKey): Rectangle {
            val image = current.provincesImage
            val tileLeft = key.x * MAP_TILE_SIZE
            val tileTop = key.y * MAP_TILE_SIZE
            val tileWidth = minOf(MAP_TILE_SIZE, image.width - tileLeft)
            val tileHeight = minOf(MAP_TILE_SIZE, image.height - tileTop)
            val x1 = (tileLeft * zoom).roundToInt()
            val y1 = (tileTop * zoom).roundToInt()
            val x2 = ((tileLeft + tileWidth) * zoom).roundToInt().coerceAtLeast(x1 + 1)
            val y2 = ((tileTop + tileHeight) * zoom).roundToInt().coerceAtLeast(y1 + 1)
            return Rectangle(x1, y1, x2 - x1, y2 - y1)
        }

        private fun tileImageRect(current: LoadedMapData, key: MapTileKey, padding: Double = 0.0): ImageRect {
            val image = current.provincesImage
            val tileLeft = key.x * MAP_TILE_SIZE
            val tileTop = key.y * MAP_TILE_SIZE
            val tileWidth = minOf(MAP_TILE_SIZE, image.width - tileLeft)
            val tileHeight = minOf(MAP_TILE_SIZE, image.height - tileTop)
            return ImageRect(
                minX = tileLeft.toDouble() - padding,
                minY = tileTop.toDouble() - padding,
                maxX = (tileLeft + tileWidth).toDouble() + padding,
                maxY = (tileTop + tileHeight).toDouble() + padding
            )
        }

        private fun clearBorderTileCache() {
            borderTileCache.clear()
        }

        private fun maxBorderTileCacheSize(): Int {
            return when {
                zoom >= 4.0 -> 16
                zoom >= 2.0 -> 32
                zoom >= 1.0 -> 64
                else -> MAX_BORDER_TILE_CACHE_SIZE
            }
        }

        private fun paintEmptyMessage(g: Graphics) {
            g.color = JBColor.GRAY
            g.font = JBFont.label().deriveFont(13f)
            val text = if (loading) statusLabel.text else msg("empty")
            val metrics = g.fontMetrics
            g.drawString(text, JBUIScale.scale(20), JBUIScale.scale(32) + metrics.ascent)
        }

        private fun centerOnMiddleCopy() {
            val image = data?.provincesImage ?: return
            val scroll = scrollPane ?: return
            val x = (image.width * zoom).toInt()
            setHorizontalScrollValue(x)
            setScrollValue(scroll.verticalScrollBar, 0)
        }

        fun ensureMinimumZoomForViewport() {
            val current = data ?: return
            if (fitToMinimumAfterLayout && fitToMinimumZoom(center = true)) return
            val nextMin = minZoom(current)
            if (zoom < nextMin) {
                setZoom(nextMin, Point(0, 0))
            } else {
                revalidate()
                recenterHorizontalScrollIfNeeded()
            }
        }

        private fun fitToMinimumZoom(center: Boolean): Boolean {
            val current = data ?: return false
            val viewportHeight = scrollPane?.viewport?.extentSize?.height ?: 0
            if (viewportHeight <= 0) return false
            val nextZoom = zoomInBounds(minZoom(current))
            if (zoom != nextZoom) {
                zoom = nextZoom
                clearBorderTileCache()
            }
            fitToMinimumAfterLayout = false
            revalidate()
            repaint()
            if (center) centerOnMiddleCopy()
            return true
        }

        private fun minZoom(current: LoadedMapData? = data): Double {
            val image = current?.provincesImage ?: return 1.0
            val viewportHeight = scrollPane?.viewport?.extentSize?.height ?: 0
            if (viewportHeight <= 0 || image.height <= 0) return 1.0
            return (viewportHeight.toDouble() / image.height.toDouble()).coerceAtLeast(MIN_ZOOM_FALLBACK)
        }

        private fun zoomInBounds(value: Double): Double {
            val lower = minZoom().coerceAtMost(MAX_ZOOM)
            return value.coerceIn(lower, MAX_ZOOM)
        }

        fun recenterHorizontalScrollIfNeeded() {
            if (recenteringScroll) return
            val image = data?.provincesImage ?: return
            val scroll = scrollPane ?: return
            val scaledWidth = (image.width * zoom).toInt().coerceAtLeast(1)
            val horizontal = scroll.horizontalScrollBar
            val max = horizontal.maximum - horizontal.visibleAmount
            if (max <= scaledWidth) return

            val value = horizontal.value
            val shift = when {
                value < scaledWidth / 2 -> scaledWidth
                value > scaledWidth + scaledWidth / 2 -> -scaledWidth
                else -> 0
            }
            if (shift == 0) return

            recenteringScroll = true
            try {
                setScrollValue(horizontal, value + shift)
                dragStartScroll?.translate(shift, 0)
            } finally {
                recenteringScroll = false
            }
        }

        private fun updateHoverStatus(point: Point) {
            val sample = sampleProvince(point) ?: return
            updateHoverSelection(sample)
            val province = sample.province
            val state = sample.state
            val country = sample.country
            val strategicRegion = sample.strategicRegion
            statusLabel.text = when (outlineMode) {
                MapPreviewMode.PROVINCE -> province?.let {
                    msg("status.province", it.id, sample.x, sample.y, "%06X".format(sample.rgb))
                } ?: msg("status.pixel.no.definition", sample.x, sample.y, "%06X".format(sample.rgb))

                MapPreviewMode.STATE -> state?.let {
                    msg("status.state", it.id, " - ${displayStateName(it)}", province?.id ?: "?")
                } ?: msg("status.no.state", province?.id ?: "?")

                MapPreviewMode.COUNTRY -> country?.let {
                    msg("status.country", "${displayCountryName(it)} (${it.tag})", state?.id ?: "?", province?.id ?: "?")
                } ?: msg("status.no.country", province?.id ?: "?")

                MapPreviewMode.STRATEGIC_REGION -> strategicRegion?.let {
                    msg("status.strategic.region", it.id, " - ${displayStrategicRegionName(it)}", province?.id ?: "?")
                } ?: msg("status.no.strategic.region", province?.id ?: "?")
            }
        }

        private fun sampleProvince(point: Point): ProvinceSample? {
            val current = data ?: return null
            val image = current.provincesImage
            val rawX = (point.x / zoom).toInt()
            val y = (point.y / zoom).toInt()
            if (y !in 0 until image.height) return null
            val x = floorMod(rawX, image.width)
            val rgb = image.getRGB(x, y) and 0xFFFFFF
            val province = current.provinceByColor[rgb]
            val state = province?.let { current.stateByProvinceId[it.id] }
            val country = state?.owner?.uppercase()?.let { current.countryByTag[it] }
            val strategicRegion = province?.let { current.strategicRegionByProvinceId[it.id] }
            return ProvinceSample(x, y, rgb, province, state, country, strategicRegion)
        }

        private fun updateHoverSelection(sample: ProvinceSample) {
            val nextSelection = when (outlineMode) {
                MapPreviewMode.PROVINCE -> sample.province?.let { HoverSelection(outlineMode, it.id) }
                MapPreviewMode.STATE -> sample.state?.let { HoverSelection(outlineMode, it.id) }
                MapPreviewMode.COUNTRY -> sample.country?.let { HoverSelection(outlineMode, it.mapKey) }
                MapPreviewMode.STRATEGIC_REGION -> sample.strategicRegion?.let { HoverSelection(outlineMode, it.id) }
            }
            if (hoverSelection != nextSelection) {
                hoverSelection = nextSelection
                hoverOverlay = nextSelection?.let { createHoverOverlay(it) }
                repaint()
            }
        }

        private fun paintHoverHighlight(g2: Graphics2D, clip: Rectangle) {
            val overlay = hoverOverlay ?: return
            val highlight = JBColor(Color(255, 255, 255, 210), Color(255, 255, 255, 210))
            g2.color = highlight
            val imageWidth = data?.provincesImage?.width ?: return
            val scaledWidth = (imageWidth * zoom).toInt()
            for (copy in 0 until LOOP_COPIES) {
                val copyOffset = copy * scaledWidth
                for (span in overlay.spans) {
                    val x = copyOffset + (span.x * zoom).toInt()
                    val y = (span.y * zoom).toInt()
                    val nextX = copyOffset + ((span.x + span.length) * zoom).toInt()
                        .coerceAtLeast((span.x * zoom).toInt() + 1)
                    val nextY = ((span.y + 1) * zoom).toInt().coerceAtLeast(y + 1)
                    if (nextX < clip.x || x > clip.x + clip.width || nextY < clip.y || y > clip.y + clip.height) continue
                    g2.fillRect(x, y, nextX - x, nextY - y)
                }
            }
        }

        private fun paintMapLabels(g2: Graphics2D, clip: Rectangle) {
            val current = data ?: return
            val labels = labelsForMode(current, outlineMode)
            if (labels.isEmpty()) return
            val imageWidth = current.provincesImage.width
            val scaledWidth = (imageWidth * zoom).toInt()
            g2.font = JBFont.label().deriveFont(java.awt.Font.BOLD, (12f * zoom.toFloat()).coerceIn(11f, 24f))
            val foreground = JBColor(Color(245, 245, 245, 220), Color(245, 245, 245, 230))
            val shadow = JBColor(Color(0, 0, 0, 180), Color(0, 0, 0, 190))
            val metrics = g2.fontMetrics
            for (copy in 0 until LOOP_COPIES) {
                val copyOffset = copy * scaledWidth
                for (label in labels) {
                    val textWidth = metrics.stringWidth(label.text)
                    val x = copyOffset + (label.x * zoom).roundToInt() - textWidth / 2
                    val y = (label.y * zoom).roundToInt() + metrics.ascent / 2
                    if (x + textWidth < clip.x || x > clip.x + clip.width ||
                        y + metrics.descent < clip.y || y - metrics.ascent > clip.y + clip.height
                    ) {
                        continue
                    }
                    g2.color = shadow
                    g2.drawString(label.text, x + 1, y + 1)
                    g2.color = foreground
                    g2.drawString(label.text, x, y)
                }
            }
        }

        private fun labelsForMode(current: LoadedMapData, mode: MapPreviewMode): List<MapLabel> {
            val index = current.pixelIndex
            return when (mode) {
                MapPreviewMode.PROVINCE -> current.provinceById.values.mapNotNull { province ->
                    val bounds = index.provinceBounds[province.id] ?: return@mapNotNull null
                    MapLabel(displayProvinceName(province), bounds.centerX(), bounds.centerY())
                }

                MapPreviewMode.STATE -> current.stateById.values.mapNotNull { state ->
                    val bounds = index.stateBounds[state.id] ?: return@mapNotNull null
                    MapLabel(state.localizedName ?: state.name ?: state.id.toString(), bounds.centerX(), bounds.centerY())
                }

                MapPreviewMode.COUNTRY -> current.countryByTag.values.mapNotNull { country ->
                    val bounds = index.countryBounds[country.mapKey] ?: return@mapNotNull null
                    MapLabel(country.localizedName ?: country.tag, bounds.centerX(), bounds.centerY())
                }

                MapPreviewMode.STRATEGIC_REGION -> current.strategicRegionById.values.mapNotNull { region ->
                    val bounds = index.strategicRegionBounds[region.id] ?: return@mapNotNull null
                    MapLabel(region.localizedName ?: region.name ?: region.id.toString(), bounds.centerX(), bounds.centerY())
                }
            }
        }

        private fun createHoverOverlay(selection: HoverSelection): HoverOverlay? {
            val current = data ?: return null
            val bounds = boundsForSelection(current.pixelIndex, selection) ?: return null
            val keys = keysForSelection(current.pixelIndex, selection)
            val spans = mutableListOf<HoverSpan>()
            for (y in bounds.minY..bounds.maxY) {
                val rowOffset = y * current.pixelIndex.width
                var x = bounds.minX
                while (x <= bounds.maxX) {
                    val index = rowOffset + x
                    if (keys[index] == selection.key && isBoundaryPixel(keys, current.pixelIndex.width, index)) {
                        val startX = x
                        x++
                        while (x <= bounds.maxX &&
                            keys[rowOffset + x] == selection.key &&
                            isBoundaryPixel(keys, current.pixelIndex.width, rowOffset + x)
                        ) {
                            x++
                        }
                        spans.add(HoverSpan(startX, y, x - startX))
                    } else {
                        x++
                    }
                }
            }
            return if (spans.isEmpty()) null else HoverOverlay(spans)
        }

        private fun boundsForSelection(index: MapPixelIndex, selection: HoverSelection): PixelBounds? {
            return when (selection.mode) {
                MapPreviewMode.PROVINCE -> index.provinceBounds[selection.key]
                MapPreviewMode.STATE -> index.stateBounds[selection.key]
                MapPreviewMode.COUNTRY -> index.countryBounds[selection.key]
                MapPreviewMode.STRATEGIC_REGION -> index.strategicRegionBounds[selection.key]
            }
        }

        private fun keysForSelection(index: MapPixelIndex, selection: HoverSelection): IntArray {
            return when (selection.mode) {
                MapPreviewMode.PROVINCE -> index.provinceKeys
                MapPreviewMode.STATE -> index.stateKeys
                MapPreviewMode.COUNTRY -> index.countryKeys
                MapPreviewMode.STRATEGIC_REGION -> index.strategicRegionKeys
            }
        }

        private fun isBoundaryPixel(keys: IntArray, width: Int, index: Int): Boolean {
            val key = keys[index]
            if (key < 0) return false
            val height = keys.size / width
            val x = index % width
            val y = index / width
            val rowOffset = y * width
            val leftIndex = rowOffset + if (x == 0) width - 1 else x - 1
            val rightIndex = rowOffset + if (x + 1 == width) 0 else x + 1
            return y == 0 || y + 1 == height ||
                    keys[leftIndex] != key ||
                    keys[rightIndex] != key ||
                    keys[index - width] != key ||
                    keys[index + width] != key
        }

        private fun buildDetailText(sample: ProvinceSample): String {
            return buildString {
                append("<html><body>")
                when (outlineMode) {
                    MapPreviewMode.PROVINCE -> appendProvinceDetail(sample)
                    MapPreviewMode.STATE -> appendStateDetail(sample)
                    MapPreviewMode.COUNTRY -> appendCountryDetail(sample)
                    MapPreviewMode.STRATEGIC_REGION -> appendStrategicRegionDetail(sample)
                }
                append("</body></html>")
            }
        }

        private fun StringBuilder.appendProvinceDetail(sample: ProvinceSample) {
            val province = sample.province
            append("<b>").append(msg("detail.province")).append(" ")
            append(province?.let { displayProvinceName(it) } ?: msg("detail.unknown"))
            append("</b>")
            province?.let { append("<br>").append(msg("detail.province.id")).append(": ").append(it.id) }
            append("<br>").append(msg("detail.pixel")).append(": ").append(sample.x).append(", ").append(sample.y)
            append("<br>RGB: #").append("%06X".format(sample.rgb))
            province?.type?.let { append("<br>").append(msg("detail.type")).append(": ").append(escapeHtml(displayGameKey(it))) }
            province?.terrain?.let { append("<br>").append(msg("detail.terrain")).append(": ").append(escapeHtml(displayGameKey(it))) }
            province?.continent?.let { append("<br>").append(msg("detail.continent")).append(": ").append(it) }
            province?.coastal?.let { append("<br>").append(msg("detail.coastal")).append(": ").append(it) }
            sample.state?.let {
                append("<br>").append(msg("detail.state")).append(": ").append(displayStateName(it))
            }
            sample.country?.let { append("<br>").append(msg("detail.owner")).append(": ").append(escapeHtml(displayCountryTag(it.tag))) }
            sample.strategicRegion?.let {
                append("<br>").append(msg("detail.strategic.region")).append(": ")
                    .append(escapeHtml(displayStrategicRegionName(it)))
            }
            sample.state?.let { state ->
                state.victoryPoints[province?.id]?.let { append("<br>").append(msg("detail.victory.points")).append(": ").append(it) }
                state.provinceBuildings[province?.id]?.takeIf { it.isNotEmpty() }?.let { buildings ->
                    append("<br>").append(msg("detail.province.buildings")).append(": ")
                    append(formatMap(buildings))
                }
            }
        }

        private fun StringBuilder.appendStateDetail(sample: ProvinceSample) {
            val state = sample.state
            append("<b>").append(msg("detail.state")).append(" ")
            append(state?.let { displayStateName(it) } ?: msg("detail.unknown"))
            append("</b>")
            state?.name?.let { append(" - ").append(escapeHtml(it)) }
            append("<br>").append(msg("detail.province")).append(": ").append(sample.province?.id ?: msg("detail.unknown"))
            state?.owner?.let { append("<br>").append(msg("detail.owner")).append(": ").append(escapeHtml(displayCountryTag(it))) }
            state?.category?.let { append("<br>").append(msg("detail.category")).append(": ").append(escapeHtml(displayGameKey(it))) }
            state?.manpower?.let { append("<br>").append(msg("detail.manpower")).append(": ").append(it) }
            state?.let {
                append("<br>").append(msg("detail.state.id")).append(": ").append(it.id)
                if (it.cores.isNotEmpty()) {
                    append("<br>").append(msg("detail.cores")).append(": ")
                        .append(escapeHtml(it.cores.joinToString(", ") { core -> displayCountryTag(core) }))
                }
                if (it.resources.isNotEmpty()) {
                    append("<br>").append(msg("detail.resources")).append(": ")
                    append(formatMap(it.resources))
                }
                if (it.stateBuildings.isNotEmpty()) {
                    append("<br>").append(msg("detail.state.buildings")).append(": ")
                    append(formatMap(it.stateBuildings))
                }
            }
            state?.path?.let { append("<br>").append(msg("detail.file")).append(": ").append(escapeHtml(it.fileName.toString())) }
        }

        private fun StringBuilder.appendCountryDetail(sample: ProvinceSample) {
            val country = sample.country
            append("<b>").append(msg("detail.country")).append(" ")
            append(country?.let { displayCountryName(it) } ?: msg("detail.unknown"))
            append("</b>")
            country?.let { append("<br>").append(msg("detail.country.tag")).append(": ").append(escapeHtml(it.tag)) }
            append("<br>").append(msg("detail.province")).append(": ").append(sample.province?.id ?: msg("detail.unknown"))
            append("<br>").append(msg("detail.state")).append(": ").append(sample.state?.id ?: msg("detail.unknown"))
            country?.let {
                append("<br>").append(msg("detail.owned.states")).append(": ").append(it.stateIds.size)
                append("<br>").append(msg("detail.owned.provinces")).append(": ").append(it.provinceIds.size)
                it.color?.let { color -> append("<br>").append(msg("detail.color")).append(": #").append("%06X".format(color)) }
                it.colorSourcePath?.let { path -> append("<br>").append(msg("detail.color.file")).append(": ").append(escapeHtml(path.fileName.toString())) }
                append("<br>").append(msg("detail.state.ids")).append(": ").append(escapeHtml(it.stateIds.take(24).joinToString(", ")))
                if (it.stateIds.size > 24) append(", ...")
            }
        }

        private fun StringBuilder.appendStrategicRegionDetail(sample: ProvinceSample) {
            val region = sample.strategicRegion
            append("<b>").append(msg("detail.strategic.region")).append(" ")
            append(region?.let { displayStrategicRegionName(it) } ?: msg("detail.unknown"))
            append("</b>")
            region?.let { append("<br>").append(msg("detail.strategic.region.id")).append(": ").append(it.id) }
            append("<br>").append(msg("detail.province")).append(": ").append(sample.province?.id ?: msg("detail.unknown"))
            region?.let {
                append("<br>").append(msg("detail.province.count")).append(": ").append(it.provinces.size)
                it.navalTerrain?.let { terrain ->
                    append("<br>").append(msg("detail.naval.terrain")).append(": ").append(escapeHtml(displayGameKey(terrain)))
                }
                if (it.weather.isNotEmpty()) {
                    append("<br>").append(msg("detail.weather")).append(": ")
                    append(it.weather.entries.joinToString(", ") { entry -> "${escapeHtml(displayGameKey(entry.key))} ${entry.value}" })
                }
                append("<br>").append(msg("detail.province.ids")).append(": ").append(escapeHtml(it.provinces.take(24).joinToString(", ")))
                if (it.provinces.size > 24) append(", ...")
                append("<br>").append(msg("detail.file")).append(": ").append(escapeHtml(it.path.fileName.toString()))
            }
        }

        private fun navigateToSource(sample: ProvinceSample) {
            val current = data ?: return
            val target = when (outlineMode) {
                MapPreviewMode.PROVINCE -> SourceTarget(current.definitionPath, sample.province?.sourceLine ?: 0)
                MapPreviewMode.STATE -> SourceTarget(sample.state?.path, 0)
                MapPreviewMode.COUNTRY -> SourceTarget(sample.country?.historyPath ?: sample.country?.definitionPath, 0)
                MapPreviewMode.STRATEGIC_REGION -> SourceTarget(sample.strategicRegion?.path, 0)
            }
            val path = target.path ?: return
            val vf = LocalFileSystem.getInstance().findFileByPath(path.pathString) ?: return
            if (target.line > 0) {
                OpenFileDescriptor(project, vf, target.line - 1, 0).navigate(true)
            } else {
                OpenFileDescriptor(project, vf).navigate(true)
            }
        }

        private fun displayProvinceName(province: ProvinceInfo): String {
            return "${msg("detail.province")} ${province.id}"
        }

        private fun displayStateName(state: StateInfo): String {
            return state.localizedName ?: state.name ?: state.id.toString()
        }

        private fun displayCountryName(country: CountryInfo): String {
            return country.localizedName ?: country.tag
        }

        private fun displayStrategicRegionName(region: StrategicRegionInfo): String {
            return region.localizedName ?: region.name ?: region.id.toString()
        }

        private fun formatMap(values: Map<String, Int>): String {
            return values.entries.joinToString(", ") { entry -> "${escapeHtml(displayGameKey(entry.key))} ${entry.value}" }
        }

        private fun displayCountryTag(tag: String): String {
            val country = data?.countryByTag?.get(tag.uppercase())
            return if (country != null) "${displayCountryName(country)} (${country.tag})" else tag
        }

        private fun displayGameKey(key: String): String {
            val normalized = key.trim().trim('"')
            if (normalized.isEmpty()) return normalized
            val localisations = data?.localisations ?: return normalized
            val candidates = linkedSetOf(
                normalized,
                normalized.uppercase(),
                "building_$normalized",
                "building_${normalized.uppercase()}",
                "state_category_$normalized",
                "state_category_${normalized.uppercase()}",
                "terrain_$normalized",
                "terrain_${normalized.uppercase()}"
            )
            val localized = candidates.firstNotNullOfOrNull { localisations[it] }
            return if (localized.isNullOrBlank() || localized == normalized) {
                normalized
            } else {
                "$localized ($normalized)"
            }
        }

        private fun setHorizontalScrollValue(value: Int) {
            val scroll = scrollPane ?: return
            setScrollValue(scroll.horizontalScrollBar, value)
            recenterHorizontalScrollIfNeeded()
        }

        private fun escapeHtml(value: String): String {
            return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
        }

        private fun setScrollValue(scrollBar: javax.swing.JScrollBar, value: Int) {
            val max = scrollBar.maximum - scrollBar.visibleAmount
            scrollBar.value = value.coerceIn(scrollBar.minimum, max.coerceAtLeast(scrollBar.minimum))
        }

        private fun clampedViewPosition(x: Int, y: Int, viewSize: Dimension, extentSize: Dimension): Point {
            val maxX = (viewSize.width - extentSize.width).coerceAtLeast(0)
            val maxY = (viewSize.height - extentSize.height).coerceAtLeast(0)
            return Point(x.coerceIn(0, maxX), y.coerceIn(0, maxY))
        }

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
            JBUIScale.scale(32)

        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int {
            return if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width
        }

        override fun getScrollableTracksViewportWidth(): Boolean = false
        override fun getScrollableTracksViewportHeight(): Boolean = false

        override fun removeNotify() {
            ToolTipManager.sharedInstance().unregisterComponent(this)
            cancelPendingSingleClick()
            hideMapHint()
            super.removeNotify()
        }
    }

    private data class ProvinceSample(
        val x: Int,
        val y: Int,
        val rgb: Int,
        val province: ProvinceInfo?,
        val state: StateInfo?,
        val country: CountryInfo?,
        val strategicRegion: StrategicRegionInfo?
    )

    private data class HoverSelection(val mode: MapPreviewMode, val key: Int)
    private data class HoverSpan(val x: Int, val y: Int, val length: Int)
    private data class HoverOverlay(val spans: List<HoverSpan>)
    private data class MapLabel(val text: String, val x: Int, val y: Int)
    private data class SourceTarget(val path: java.nio.file.Path?, val line: Int)
    private data class ImageRect(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)
    private data class MapTileKey(val x: Int, val y: Int)
    private data class BorderTileKey(
        val x: Int,
        val y: Int,
        val mode: MapPreviewMode,
        val smooth: Boolean,
        val zoomBits: Long
    )
    private data class MapTileRange(
        val minTileX: Int,
        val minTileY: Int,
        val maxTileX: Int,
        val maxTileY: Int
    )

    private fun PixelBounds.centerX(): Int = (minX + maxX) / 2
    private fun PixelBounds.centerY(): Int = (minY + maxY) / 2

    private fun ImageRect.intersectsSegment(x1: Double, y1: Double, x2: Double, y2: Double, padding: Double): Boolean {
        val segmentMinX = minOf(x1, x2) - padding
        val segmentMaxX = maxOf(x1, x2) + padding
        val segmentMinY = minOf(y1, y2) - padding
        val segmentMaxY = maxOf(y1, y2) + padding
        return segmentMaxX >= minX &&
                segmentMinX <= maxX &&
                segmentMaxY >= minY &&
                segmentMinY <= maxY
    }

    private fun floorMod(value: Int, modulus: Int): Int = ((value % modulus) + modulus) % modulus

    private companion object {
        private const val LOOP_COPIES = 3
        private const val MAP_TILE_SIZE = 256
        private const val BORDER_TILE_PADDING = 2
        private const val MAX_TILE_CACHE_SIZE = 512
        private const val MAX_BORDER_TILE_CACHE_SIZE = 192
        private const val MIN_ZOOM_FALLBACK = 0.05
        private const val MAX_ZOOM = 8.0
    }
}
