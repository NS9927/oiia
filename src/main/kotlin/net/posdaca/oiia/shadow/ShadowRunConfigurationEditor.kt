package net.posdaca.oiia.shadow

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import net.posdaca.OiiaBundle
import javax.swing.JComponent

internal class ShadowRunConfigurationEditor : SettingsEditor<ShadowRunConfiguration>() {
    private val executableField = TextFieldWithBrowseButton()
    private val errorLogPathField = TextFieldWithBrowseButton()
    private val allowMissingModsCheckBox = JBCheckBox(OiiaBundle.message("run.shadow.allow.missing.mods"))
    private val showErrorLogCheckBox = JBCheckBox(OiiaBundle.message("run.shadow.show.error.log"))

    init {
        executableField.addBrowseFolderListener(
            OiiaBundle.message("run.shadow.executable.chooser.title"),
            null,
            null,
            FileChooserDescriptorFactory.createSingleFileDescriptor("exe"),
        )
        errorLogPathField.addBrowseFolderListener(
            OiiaBundle.message("run.shadow.error.log.chooser.title"),
            null,
            null,
            FileChooserDescriptorFactory.createSingleFileDescriptor("log"),
        )
    }

    override fun resetEditorFrom(settings: ShadowRunConfiguration) {
        executableField.text = settings.shadowExecutablePath
        errorLogPathField.text = settings.errorLogPath
        allowMissingModsCheckBox.isSelected = settings.allowMissingMods
        showErrorLogCheckBox.isSelected = settings.showErrorLog
    }

    override fun applyEditorTo(settings: ShadowRunConfiguration) {
        settings.shadowExecutablePath = executableField.text.trim()
        settings.errorLogPath = errorLogPathField.text.trim()
        settings.allowMissingMods = allowMissingModsCheckBox.isSelected
        settings.showErrorLog = showErrorLogCheckBox.isSelected
    }

    override fun createEditor(): JComponent {
        return panel {
            row(OiiaBundle.message("run.shadow.executable")) {
                cell(executableField)
                    .align(AlignX.FILL)
            }
            row(OiiaBundle.message("run.shadow.error.log")) {
                cell(errorLogPathField)
                    .align(AlignX.FILL)
                    .comment(OiiaBundle.message("run.shadow.error.log.comment"))
            }
            row {
                cell(showErrorLogCheckBox)
            }
            row {
                cell(allowMissingModsCheckBox)
            }
        }
    }
}
