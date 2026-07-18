package net.posdaca.oiia.gui

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
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

class GuiPreviewToolWindowFactory : ToolWindowFactory {

    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        PreviewToolWindowSupport.configure(
            toolWindow,
            { OiiaBundle.message("toolwindow.GuiPreview.display.name") },
            PreviewToolWindowSupport.GuiIcon
        )
        val panel = GuiToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }

    private class GuiToolWindowPanel(
        private val project: Project
    ) : JBPanel<JBPanel<*>>(BorderLayout()) {

        private var currentPanel: JBPanel<JBPanel<*>> = createEmptyPanel()
        private var toolbar = createToolbar()
        private var messageBusConnection: MessageBusConnection? = null
        private var renderedFilePath: String? = null
        private var renderedStamp: Long = -1
        private val refreshVersion = AtomicInteger()
        private val service = GuiPreviewService(project)
        private val refreshTimer = Timer(350) {
            if (isShowing) refreshFromCurrentFileIfChanged()
        }.apply { isRepeats = true }

        init {
            add(toolbar, BorderLayout.NORTH)
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
                        refreshFromFile(event.newFile ?: getSelectedFile(), force = true)
                    }
                }
            )
        }

        private fun refreshFromCurrentFileIfChanged() {
            val selectedFile = getSelectedFile()
            val stamp = selectedFile?.modificationStamp ?: -1
            if (selectedFile?.path != renderedFilePath || stamp != renderedStamp) {
                refreshFromFile(selectedFile)
            }
        }

        private fun refreshFromCurrentFile() {
            refreshFromFile(getSelectedFile(), force = true)
        }

        private fun refreshFromFile(file: VirtualFile?, force: Boolean = false) {
            val filePath = file?.path
            val stamp = file?.modificationStamp ?: -1
            if (!force && filePath == renderedFilePath && stamp == renderedStamp) return
            val version = refreshVersion.incrementAndGet()
            renderedFilePath = filePath
            renderedStamp = stamp

            val selectedFile = file ?: run {
                updateToolbar()
                updatePanel(createEmptyPanel())
                return
            }

            if (!isGuiFile(selectedFile)) {
                updateToolbar()
                updatePanel(createEmptyPanel())
                return
            }

            updateToolbar()
            updatePanel(createLoadingPanel())

            ApplicationManager.getApplication().executeOnPooledThread {
                val previewFile = ApplicationManager.getApplication().runReadAction<GuiPreviewFile?> {
                    if (!selectedFile.isValid || selectedFile.modificationStamp != stamp) return@runReadAction null
                    val psiFile: PsiFile = PsiManager.getInstance(project).findFile(selectedFile) ?: return@runReadAction null
                    service.parseGuiFile(psiFile)
                }

                val resources = if (previewFile != null && previewFile.roots.isNotEmpty()) {
                    service.loadResources(previewFile.roots) { version != refreshVersion.get() }
                } else {
                    null
                }

                ApplicationManager.getApplication().invokeLater({
                    if (version != refreshVersion.get()) return@invokeLater
                    if (selectedFile.modificationStamp != stamp || selectedFile.path != renderedFilePath) return@invokeLater

                    if (previewFile == null || previewFile.roots.isEmpty()) {
                        updateToolbar()
                        updatePanel(createNoContainerPanel())
                        return@invokeLater
                    }
                    if (resources == null) return@invokeLater

                    val previewPanel = GuiPreviewPanel(project, previewFile, service, resources)
                    updateToolbar(previewPanel.statusComponent, previewPanel.rootSelectorActions)
                    updatePanel(previewPanel)
                }, ModalityState.any())
            }
        }

        private fun createToolbar(
            centerContent: javax.swing.JComponent? = null,
            extraActions: javax.swing.JComponent? = null
        ): javax.swing.JComponent {
            return PreviewToolWindowSupport.createReloadToolbar(
                OiiaBundle.message("toolwindow.GuiPreview.reload"),
                { refreshFromCurrentFile() },
                centerContent,
                extraActions
            )
        }

        private fun updateToolbar(
            centerContent: javax.swing.JComponent? = null,
            extraActions: javax.swing.JComponent? = null
        ) {
            val nextToolbar = createToolbar(centerContent, extraActions)
            remove(toolbar)
            toolbar = nextToolbar
            add(toolbar, BorderLayout.NORTH)
        }

        @Suppress("UnstableApiUsage")
        private fun getSelectedFile(): VirtualFile? {
            val fileEditorManager = FileEditorManager.getInstance(project)
            return fileEditorManager.currentFile
                ?: fileEditorManager.selectedEditors.firstOrNull()?.file
                ?: fileEditorManager.selectedFiles.firstOrNull()
                ?: fileEditorManager.selectedEditor?.file
        }

        private fun isGuiFile(file: VirtualFile): Boolean {
            val path = file.path
            return file.name.endsWith(".gui", ignoreCase = true) &&
                    (path.contains("/interface/", ignoreCase = true) ||
                            path.contains("\\interface\\", ignoreCase = true))
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
                OiiaBundle.message("toolwindow.GuiPreview.empty"),
                SwingConstants.CENTER
            )
            panel.add(label, BorderLayout.CENTER)
            return panel
        }

        private fun createNoContainerPanel(): JBPanel<JBPanel<*>> {
            val panel = JBPanel<JBPanel<*>>(BorderLayout())
            val label = JBLabel(
                OiiaBundle.message("toolwindow.GuiPreview.no.container"),
                SwingConstants.CENTER
            )
            panel.add(label, BorderLayout.CENTER)
            return panel
        }

        private fun createLoadingPanel(): JBPanel<JBPanel<*>> {
            val panel = JBPanel<JBPanel<*>>(BorderLayout())
            val label = JBLabel(
                OiiaBundle.message("toolwindow.GuiPreview.loading"),
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
