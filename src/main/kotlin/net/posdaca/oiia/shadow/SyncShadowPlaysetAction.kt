package net.posdaca.oiia.shadow

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import net.posdaca.OiiaBundle
import javax.swing.Icon

internal class SyncShadowPlaysetAction : AnAction(
    OiiaBundle.lazyMessage("action.shadow.sync.text"),
    OiiaBundle.lazyMessage("action.shadow.sync.description"),
    null as Icon?,
) {
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            OiiaBundle.message("action.shadow.sync.progress"),
            false,
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    val result = ShadowPlaysetSync.sync(project)
                    val message = if (result.missingMods.isEmpty()) {
                        OiiaBundle.message(
                            "action.shadow.sync.success",
                            result.playsetName,
                            result.matchedModCount,
                            result.playsetPath,
                        )
                    } else {
                        OiiaBundle.message(
                            "action.shadow.sync.partial",
                            result.playsetName,
                            result.matchedModCount,
                            result.missingMods.size,
                            result.playsetPath,
                        )
                    }
                    notify(project, NotificationType.INFORMATION, message)
                } catch (e: ShadowPlaysetSyncException) {
                    notify(project, NotificationType.WARNING, e.message.orEmpty())
                } catch (e: Exception) {
                    notify(project, NotificationType.ERROR, e.message ?: e.javaClass.simpleName)
                }
            }
        })
    }

    private fun notify(project: com.intellij.openapi.project.Project, type: NotificationType, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Oiia")
            .createNotification(OiiaBundle.message("action.shadow.sync.notification.title"), content, type)
            .notify(project)
    }
}
