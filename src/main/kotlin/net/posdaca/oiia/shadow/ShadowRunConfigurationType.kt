package net.posdaca.oiia.shadow

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import OiiaBundle
import net.posdaca.oiia.core.PreviewToolWindowSupport

internal class ShadowRunConfigurationType : ConfigurationTypeBase(
    ID,
    OiiaBundle.message("run.shadow.type.name"),
    OiiaBundle.message("run.shadow.type.description"),
    NotNullLazyValue.createValue { PreviewToolWindowSupport.FocusIcon },
) {
    init {
        addFactory(object : ConfigurationFactory(this) {
            override fun getId(): String = "Hoi4ViaShadowRunConfigurationFactory"

            override fun createTemplateConfiguration(project: Project) =
                ShadowRunConfiguration(project, this, OiiaBundle.message("run.shadow.default.name"))
        })
    }

    companion object {
        const val ID = "Oiia.Hoi4ViaShadow"
    }
}
