package net.posdaca.oiia.core

import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.Icon
import javax.swing.JPanel

object PreviewToolWindowSupport {
    val FocusIcon: Icon = IconLoader.getIcon("/icons/focusPreview.svg", PreviewToolWindowSupport::class.java)
    val TechnologyIcon: Icon = IconLoader.getIcon("/icons/technologyPreview.svg", PreviewToolWindowSupport::class.java)
    val GuiIcon: Icon = IconLoader.getIcon("/icons/guiPreview.svg", PreviewToolWindowSupport::class.java)
    val MapIcon: Icon = IconLoader.getIcon("/icons/mapPreview.svg", PreviewToolWindowSupport::class.java)

    fun configure(toolWindow: ToolWindow, titleProvider: () -> String, icon: Icon) {
        toolWindow.setStripeTitleProvider(titleProvider)
        toolWindow.setTitle(titleProvider())
        toolWindow.setIcon(icon)
    }

    fun createReloadToolbar(
        label: String,
        onReload: () -> Unit,
        centerContent: JComponent? = null,
        extraActions: JComponent? = null
    ): JComponent {
        val panel = JPanel(BorderLayout(JBUIScale.scale(8), 0))
        panel.background = JBColor.PanelBackground
        panel.border = BorderFactory.createEmptyBorder(
            JBUIScale.scale(6),
            JBUIScale.scale(8),
            JBUIScale.scale(6),
            JBUIScale.scale(8)
        )
        if (centerContent != null) panel.add(centerContent, BorderLayout.CENTER)

        val actions = JPanel()
        actions.isOpaque = false
        val reloadButton = JButton(label)
        reloadButton.addActionListener { onReload() }
        actions.add(reloadButton)
        if (extraActions != null) actions.add(extraActions)
        panel.add(actions, BorderLayout.EAST)
        return panel
    }
}
