package net.posdaca.oiia.core

import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindow
import javax.swing.Icon

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
}
