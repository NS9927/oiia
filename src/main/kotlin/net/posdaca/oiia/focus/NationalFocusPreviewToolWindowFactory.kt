package net.posdaca.oiia.focus

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

class NationalFocusPreviewToolWindowFactory : ToolWindowFactory {

    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        PreviewToolWindowSupport.configure(
            toolWindow,
            { OiiaBundle.message("toolwindow.NationalFocusPreview.display.name") },
            PreviewToolWindowSupport.FocusIcon
        )
        val panel = NationalFocusToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }

    private class NationalFocusToolWindowPanel(
        private val project: Project
    ) : JBPanel<JBPanel<*>>(BorderLayout()) {

        private var currentPanel: JBPanel<JBPanel<*>> = createEmptyPanel()
        private var messageBusConnection: MessageBusConnection? = null
        private var renderedFilePath: String? = null
        private var renderedStamp: Long = -1
        private var renderedLocalisationKey: String? = null
        private val refreshVersion = AtomicInteger()
        private val service = NationalFocusService(project)
        private val refreshTimer = Timer(350) {
            if (isShowing) refreshFromCurrentFileIfChanged()
        }.apply { isRepeats = true }

        init {
            add(
                PreviewToolWindowSupport.createReloadToolbar(
                    OiiaBundle.message("toolwindow.NationalFocusPreview.reload"),
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
                        refreshFromFile(event.newFile ?: getSelectedFile(), force = true)
                    }
                }
            )
        }

        private fun refreshFromCurrentFileIfChanged() {
            val selectedFile = getSelectedFile()
            val stamp = selectedFile?.modificationStamp ?: -1
            val localisationKey = NationalFocusService.localisationCacheKey()
            if (selectedFile?.path != renderedFilePath ||
                stamp != renderedStamp ||
                localisationKey != renderedLocalisationKey
            ) {
                refreshFromFile(selectedFile, localisationKey = localisationKey)
            }
        }

        private fun refreshFromCurrentFile() {
            refreshFromFile(getSelectedFile(), force = true)
        }

        private fun refreshFromFile(
            file: VirtualFile?,
            force: Boolean = false,
            localisationKey: String = NationalFocusService.localisationCacheKey()
        ) {
            val filePath = file?.path
            val stamp = file?.modificationStamp ?: -1
            if (!force && filePath == renderedFilePath && stamp == renderedStamp && localisationKey == renderedLocalisationKey) return
            val version = refreshVersion.incrementAndGet()
            renderedFilePath = filePath
            renderedStamp = stamp
            renderedLocalisationKey = localisationKey

            val selectedFile = file ?: run {
                updatePanel(createEmptyPanel())
                return
            }

            if (!isNationalFocusFile(selectedFile)) {
                updatePanel(createEmptyPanel())
                return
            }

            if (selectedFile.parent == null) {
                updatePanel(createNoFocusTreePanel())
                return
            }

            ApplicationManager.getApplication().executeOnPooledThread {
                val snapshot = ApplicationManager.getApplication().runReadAction<FocusPreviewSnapshot?> {
                    if (!selectedFile.isValid || selectedFile.modificationStamp != stamp) return@runReadAction null
                    val psiFile: PsiFile = PsiManager.getInstance(project).findFile(selectedFile)
                        ?: return@runReadAction null
                    service.loadSnapshot(psiFile)
                }

                ApplicationManager.getApplication().invokeLater({
                    if (version != refreshVersion.get()) return@invokeLater
                    if (selectedFile.path != renderedFilePath || selectedFile.modificationStamp != stamp) return@invokeLater

                    if (snapshot == null || snapshot.isEmpty) {
                        updatePanel(createNoFocusTreePanel())
                        return@invokeLater
                    }

                    updatePanel(NationalFocusPreviewPanel(project, snapshot, service))
                }, ModalityState.any())
            }
        }

        @Suppress("UnstableApiUsage")
        private fun getSelectedFile(): VirtualFile? {
            val fileEditorManager = FileEditorManager.getInstance(project)
            return fileEditorManager.currentFile
                ?: fileEditorManager.selectedEditors.firstOrNull()?.file
                ?: fileEditorManager.selectedFiles.firstOrNull()
                ?: fileEditorManager.selectedEditor?.file
        }

        private fun isNationalFocusFile(file: VirtualFile): Boolean {
            val path = file.path
            return path.contains("/common/national_focus/", ignoreCase = true) ||
                    path.contains("/common/continuous_focus/", ignoreCase = true) ||
                    path.contains("\\common\\national_focus\\", ignoreCase = true) ||
                    path.contains("\\common\\continuous_focus\\", ignoreCase = true)
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
                OiiaBundle.message("toolwindow.NationalFocusPreview.empty"),
                SwingConstants.CENTER
            )
            panel.add(label, BorderLayout.CENTER)
            return panel
        }

        private fun createNoFocusTreePanel(): JBPanel<JBPanel<*>> {
            val panel = JBPanel<JBPanel<*>>(BorderLayout())
            val label = JBLabel(
                OiiaBundle.message("toolwindow.NationalFocusPreview.no.focus.tree"),
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
