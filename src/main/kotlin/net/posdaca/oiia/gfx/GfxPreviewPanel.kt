package net.posdaca.oiia.gfx

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBFont
import net.posdaca.oiia.core.preview.PreviewNavigation
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.GridLayout
import java.awt.image.BufferedImage
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/** Grid of every sprite declared by the open `.gfx` file, rendered from its resolved textures. */
class GfxPreviewPanel(
    private val project: Project,
    snapshot: GfxPreviewSnapshot,
    private val service: GfxPreviewService
) : JBPanel<JBPanel<*>>(BorderLayout()) {

    private val grid = JPanel(GridLayout(0, COLUMNS, JBUIScale.scale(8), JBUIScale.scale(8)))

    init {
        background = JBColor.PanelBackground
        grid.isOpaque = false
        rebuild(snapshot)
        val scrollPane = JBScrollPane(grid)
        scrollPane.border = null
        scrollPane.verticalScrollBar.unitIncrement = JBUIScale.scale(16)
        add(scrollPane, BorderLayout.CENTER)
        service.resolve(snapshot) { next ->
            if (next.images.isNotEmpty() || next.imagePaths.isNotEmpty()) rebuild(next)
        }
    }

    private fun rebuild(snapshot: GfxPreviewSnapshot) {
        grid.removeAll()
        if (snapshot.sprites.isEmpty()) {
            grid.layout = BorderLayout()
            grid.add(JLabel(OiiaBundle.message("toolwindow.GfxPreview.no.sprites"), SwingConstants.CENTER))
        } else {
            grid.layout = GridLayout(0, COLUMNS, JBUIScale.scale(8), JBUIScale.scale(8))
            for (entry in snapshot.sprites) {
                grid.add(createCell(entry, snapshot))
            }
        }
        revalidate()
        repaint()
    }

    private fun createCell(entry: GfxSpriteEntry, snapshot: GfxPreviewSnapshot): JComponent {
        val image = snapshot.images[entry.name]
        val canvas = object : JComponent() {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val img = image ?: return
                val cell = size
                val scale = minOf(
                    (cell.width - JBUIScale.scale(8)).toDouble() / img.width,
                    (cell.height - JBUIScale.scale(8)).toDouble() / img.height,
                    1.0
                )
                val w = (img.width * scale).toInt().coerceAtLeast(1)
                val h = (img.height * scale).toInt().coerceAtLeast(1)
                g.drawImage(
                    img,
                    (cell.width - w) / 2,
                    (cell.height - h) / 2,
                    w,
                    h,
                    null
                )
            }

            override fun getPreferredSize(): Dimension {
                return Dimension(JBUIScale.scale(120), JBUIScale.scale(96))
            }
        }
        canvas.isOpaque = false
        canvas.toolTipText = buildToolTip(entry, snapshot.imagePaths[entry.name])

        val nameLabel = JLabel(entry.name, SwingConstants.CENTER)
        nameLabel.font = JBFont.label().deriveFont(11f)
        nameLabel.verticalAlignment = SwingConstants.TOP

        val cell = JPanel(BorderLayout())
        cell.isOpaque = false
        cell.border = BorderFactory.createEmptyBorder()
        cell.add(canvas, BorderLayout.CENTER)
        cell.add(nameLabel, BorderLayout.SOUTH)
        cell.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) {
                    PreviewNavigation.open(project, snapshot.filePath, entry.sourceLine)
                }
            }
        })
        return cell
    }

    private fun buildToolTip(entry: GfxSpriteEntry, imagePath: String?): String {
        val html = StringBuilder("<html><b>").append(entry.name).append("</b>")
        entry.textureFile?.let { html.append("<br>texture: ").append(it) }
        if (entry.textureFile1 != null || entry.textureFile2 != null) {
            entry.textureFile1?.let { html.append("<br>texture1: ").append(it) }
            entry.textureFile2?.let { html.append("<br>texture2: ").append(it) }
        }
        entry.noOfFrames?.let { html.append("<br>frames: ").append(it) }
        entry.defaultFrame?.let { html.append("<br>default frame: ").append(it) }
        html.append("<br>line: ").append(entry.sourceLine)
        imagePath?.let { html.append("<br>").append(it) }
        html.append("</html>")
        return html.toString()
    }

    private companion object {
        private const val COLUMNS = 6
    }
}
