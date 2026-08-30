package net.posdaca.oiia.gfx

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.messages.MessageBusConnection
import OiiaBundle
import net.posdaca.oiia.core.PreviewToolWindowSupport
import java.awt.BorderLayout
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingConstants
import javax.swing.Timer

class GfxPreviewToolWindowFactory : ToolWindowFactory {

    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        PreviewToolWindowSupport.configure(
            toolWindow,
            { OiiaBundle.message("toolwindow.GfxPreview.display.name") },
            PreviewToolWindowSupport.GfxIcon
        )
        val panel = GfxToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }

    private class GfxToolWindowPanel(
        private val project: Project
    ) : JBPanel<JBPanel<*>>(BorderLayout()) {

        private var currentPanel: JBPanel<JBPanel<*>> = createEmptyPanel()
        private var messageBusConnection: MessageBusConnection? = null
        private var renderedFilePath: String? = null
        private var renderedStamp: Long = -1
        private val refreshVersion = AtomicInteger()
        private val service = GfxPreviewService(project)
        private val refreshTimer = Timer(350) {
            if (isShowing) refreshFromCurrentFileIfChanged()
        }.apply { isRepeats = true }

        init {
            add(
                PreviewToolWindowSupport.createReloadToolbar(
                    OiiaBundle.message("toolwindow.GfxPreview.reload"),
                    onReload = { refreshFromCurrentFile() }
                ),
                BorderLayout.NORTH
            )
            add(currentPanel, BorderLayout.CENTER)
            connectEditorListener()
            refreshTimer.start()
            refreshFromCurrentFile()
        }

        private fun connectEditorListener() {
            if (messageBusConnection != null) return
            messageBusConnection = project.messageBus.connect()
            messageBusConnection?.subscribe(
                FileEditorManagerListener.FILE_EDITOR_MANAGER,
                object : FileEditorManagerListener {
                    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                        refreshFromFile(file, force = true)
                    }

                    override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                        refreshFromCurrentFile()
                    }

                    override fun selectionChanged(event: FileEditorManagerEvent) {
                        refreshFromFile(event.newFile ?: currentFile(), force = true)
                    }
                }
            )
        }

        private fun refreshFromCurrentFileIfChanged() {
            val selectedFile = currentFile()
            val stamp = selectedFile?.modificationStamp ?: -1
            if (selectedFile?.path != renderedFilePath || stamp != renderedStamp) {
                refreshFromFile(selectedFile)
            }
        }

        private fun refreshFromCurrentFile() {
            refreshFromFile(currentFile(), force = true)
        }

        private fun refreshFromFile(file: VirtualFile?, force: Boolean = false) {
            val filePath = file?.path
            val stamp = file?.modificationStamp ?: -1
            if (!force && filePath == renderedFilePath && stamp == renderedStamp) return
            val version = refreshVersion.incrementAndGet()
            renderedFilePath = filePath
            renderedStamp = stamp

            if (file == null || !isGfxFile(file)) {
                updatePanel(createEmptyPanel())
                return
            }

            ApplicationManager.getApplication().executeOnPooledThread {
                val snapshot = ApplicationManager.getApplication().runReadAction<GfxPreviewSnapshot?> {
                    if (!file.isValid || file.modificationStamp != stamp) return@runReadAction null
                    val psiFile: PsiFile = PsiManager.getInstance(project).findFile(file)
                        ?: return@runReadAction null
                    service.loadSnapshot(psiFile)
                }

                ApplicationManager.getApplication().invokeLater({
                    if (version != refreshVersion.get()) return@invokeLater
                    if (file.path != renderedFilePath || file.modificationStamp != stamp) return@invokeLater

                    if (snapshot == null || snapshot.isEmpty) {
                        updatePanel(createNoSpritesPanel())
                        return@invokeLater
                    }

                    updatePanel(GfxPreviewPanel(project, snapshot, service))
                }, ModalityState.any())
            }
        }

        private fun currentFile(): VirtualFile? {
            val fileEditorManager = FileEditorManager.getInstance(project)
            return fileEditorManager.currentFile
                ?: fileEditorManager.selectedEditors.firstOrNull()?.file
                ?: fileEditorManager.selectedFiles.firstOrNull()
        }

        private fun isGfxFile(file: VirtualFile): Boolean {
            return file.path.endsWith(".gfx", ignoreCase = true) || file.name.endsWith(".gfx")
        }

        private fun updatePanel(newPanel: JBPanel<JBPanel<*>>) {
            if (currentPanel != newPanel) {
                remove(currentPanel)
                currentPanel = newPanel
                add(currentPanel, BorderLayout.CENTER)
                revalidate()
                repaint()
            }
        }

        private fun createEmptyPanel(): JBPanel<JBPanel<*>> {
            val panel = JBPanel<JBPanel<*>>(BorderLayout())
            val label = JBLabel(
                OiiaBundle.message("toolwindow.GfxPreview.empty"),
                SwingConstants.CENTER
            )
            panel.add(label, BorderLayout.CENTER)
            return panel
        }

        private fun createNoSpritesPanel(): JBPanel<JBPanel<*>> {
            val panel = JBPanel<JBPanel<*>>(BorderLayout())
            val label = JBLabel(
                OiiaBundle.message("toolwindow.GfxPreview.no.sprites"),
                SwingConstants.CENTER
            )
            panel.add(label, BorderLayout.CENTER)
            return panel
        }

        override fun addNotify() {
            super.addNotify()
            connectEditorListener()
            refreshTimer.start()
            refreshFromCurrentFileIfChanged()
        }

        override fun removeNotify() {
            refreshTimer.stop()
            messageBusConnection?.disconnect()
            messageBusConnection = null
            super.removeNotify()
        }
    }
}
