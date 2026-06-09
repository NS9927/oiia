package net.posdaca.oiia.technology

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
import net.posdaca.OiiaBundle
import java.awt.BorderLayout
import javax.swing.SwingConstants
import javax.swing.Timer

class TechnologyPreviewToolWindowFactory : ToolWindowFactory {

    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = TechnologyToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }

    private class TechnologyToolWindowPanel(
        private val project: Project
    ) : JBPanel<JBPanel<*>>(BorderLayout()) {

        private var currentPanel: JBPanel<JBPanel<*>> = createEmptyPanel()
        private var messageBusConnection: MessageBusConnection? = null
        private var renderedFilePath: String? = null
        private val service = TechnologyService(project)
        private val refreshTimer = Timer(350) {
            if (isShowing) refreshFromCurrentFileIfChanged()
        }.apply { isRepeats = true }

        init {
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
            if (selectedFile?.path != renderedFilePath) refreshFromFile(selectedFile)
        }

        private fun refreshFromCurrentFile() {
            refreshFromFile(getSelectedFile(), force = true)
        }

        private fun refreshFromFile(file: VirtualFile?, force: Boolean = false) {
            val filePath = file?.path
            if (!force && filePath == renderedFilePath) return
            renderedFilePath = filePath

            val selectedFile = file ?: run {
                updatePanel(createEmptyPanel())
                return
            }

            if (!isTechnologyFile(selectedFile)) {
                updatePanel(createEmptyPanel())
                return
            }

            val parent = selectedFile.parent
            if (parent == null) {
                updatePanel(createNoTechnologyPanel())
                return
            }

            val allTechnologyTrees = mutableListOf<TechnologyTreeData>()
            val psiFile: PsiFile? = PsiManager.getInstance(project).findFile(selectedFile)
            if (psiFile != null) {
                allTechnologyTrees.addAll(service.parseTechnologyTreesFromFile(psiFile))
            }

            if (allTechnologyTrees.isEmpty()) {
                for (child in parent.children) {
                    val childPsi = PsiManager.getInstance(project).findFile(child)
                    if (childPsi != null) {
                        allTechnologyTrees.addAll(service.parseTechnologyTreesFromFile(childPsi))
                    }
                }
            }

            if (allTechnologyTrees.isEmpty()) {
                updatePanel(createNoTechnologyPanel())
                return
            }

            updatePanel(TechnologyPreviewPanel(project, mergeTrees(allTechnologyTrees), service))
        }

        @Suppress("UnstableApiUsage")
        private fun getSelectedFile(): VirtualFile? {
            val fileEditorManager = FileEditorManager.getInstance(project)
            return fileEditorManager.currentFile
                ?: fileEditorManager.selectedEditors.firstOrNull()?.file
                ?: fileEditorManager.selectedFiles.firstOrNull()
                ?: fileEditorManager.selectedEditor?.file
        }

        private fun mergeTrees(trees: List<TechnologyTreeData>): List<TechnologyTreeData> {
            return trees.flatMap { tree -> tree.technologies }
                .groupBy { it.folderName ?: "Technologies" }
                .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                .map { (folder, technologies) -> TechnologyTreeData(folder, technologies.distinctBy { it.id }) }
        }

        private fun isTechnologyFile(file: VirtualFile): Boolean {
            val path = file.path
            return path.contains("/common/technologies/", ignoreCase = true) ||
                    path.contains("\\common\\technologies\\", ignoreCase = true)
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
                OiiaBundle.message("toolwindow.TechnologyPreview.empty"),
                SwingConstants.CENTER
            )
            panel.add(label, BorderLayout.CENTER)
            return panel
        }

        private fun createNoTechnologyPanel(): JBPanel<JBPanel<*>> {
            val panel = JBPanel<JBPanel<*>>(BorderLayout())
            val label = JBLabel(
                OiiaBundle.message("toolwindow.TechnologyPreview.no.technology"),
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
