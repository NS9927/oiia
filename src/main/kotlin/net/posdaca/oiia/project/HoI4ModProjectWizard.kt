package net.posdaca.oiia.project

import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.GeneratorNewProjectWizard
import com.intellij.ide.wizard.NewProjectWizardBaseStep
import com.intellij.ide.wizard.NewProjectWizardChainStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.RootNewProjectWizardStep
import com.intellij.ide.wizard.setupProjectFromBuilder
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.module.ModuleTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.util.IconUtil
import OiiaBundle
import java.nio.file.InvalidPathException
import java.nio.file.Path
import javax.swing.Icon

class HoI4ModProjectWizard : GeneratorNewProjectWizard {
    override val id: String = "hoi4-mod"
    override val name: String = OiiaBundle.message("projectWizard.hoi4.name")
    override val icon: Icon = IconUtil.resizeSquared(
        IconLoader.getIcon("/META-INF/pluginIcon.svg", HoI4ModProjectWizard::class.java),
        16,
    )
    override val description: String = OiiaBundle.message("projectWizard.hoi4.description")
    override val groupName: String = "Game Modding"

    override fun createStep(context: WizardContext): NewProjectWizardStep {
        val defaultRoot = HoI4ModTemplateGenerator.defaultModRoot()
        val root = RootNewProjectWizardStep(context)
        return NewProjectWizardChainStep(root)
            .nextStep { parent ->
                NewProjectWizardBaseStep(parent).apply {
                    defaultName = "hoi4_mod"
                    path = defaultRoot.toString()
                }
            }
            .nextStep { parent -> HoI4ModProjectStep(parent) }
    }
}

private class HoI4ModProjectStep(
    private val baseStep: NewProjectWizardBaseStep,
) : AbstractNewProjectWizardStep(baseStep) {
    private val modNameProperty = propertyGraph.property(baseStep.name.ifBlank { "HOI4 Mod" })
    private val modVersionProperty = propertyGraph.property("0.1.0")
    private val supportedVersionProperty = propertyGraph.property("1.16.*")
    private val authorsProperty = propertyGraph.property(System.getProperty("user.name") ?: "")
    private val createLauncherDescriptorProperty = propertyGraph.property(true)
    private val launcherDescriptorDirectoryProperty = propertyGraph.property(
        HoI4ModTemplateGenerator.defaultModRoot().toString(),
    )
    private val tagProperties = HoI4ModTemplateGenerator.availableTags.associateWith { tag ->
        propertyGraph.property(tag == "Alternative History")
    }

    override fun setupUI(builder: Panel) {
        builder.group(OiiaBundle.message("projectWizard.hoi4.settings.group"), false) {
            row(OiiaBundle.message("projectWizard.hoi4.mod.name")) {
                textField()
                    .align(AlignX.FILL)
                    .bindText(modNameProperty)
                    .onApply {
                        if (modNameProperty.get().isBlank()) modNameProperty.set(defaultModName())
                    }
                    .errorOnApply(OiiaBundle.message("projectWizard.hoi4.validation.name")) {
                        it.text.isBlank()
                    }
            }
            row(OiiaBundle.message("projectWizard.hoi4.mod.version")) {
                textField()
                    .align(AlignX.FILL)
                    .bindText(modVersionProperty)
                    .errorOnApply(OiiaBundle.message("projectWizard.hoi4.validation.version")) {
                        it.text.isBlank()
                    }
            }
            row(OiiaBundle.message("projectWizard.hoi4.supported.version")) {
                textField()
                    .align(AlignX.FILL)
                    .bindText(supportedVersionProperty)
                    .errorOnApply(OiiaBundle.message("projectWizard.hoi4.validation.supported.version")) {
                        it.text.isBlank()
                    }
            }
            row(OiiaBundle.message("projectWizard.hoi4.authors")) {
                textField()
                    .align(AlignX.FILL)
                    .bindText(authorsProperty)
                    .comment(OiiaBundle.message("projectWizard.hoi4.authors.comment"))
            }
            row {
                comment(OiiaBundle.message("projectWizard.hoi4.mod.content.comment"))
            }.topGap(TopGap.SMALL)
        }

        builder.group(OiiaBundle.message("projectWizard.hoi4.tags"), false) {
            HoI4ModTemplateGenerator.availableTags.chunked(3).forEach { rowTags ->
                threeColumnsRow(
                    { addTagCheckBox(rowTags.getOrNull(0)) },
                    { addTagCheckBox(rowTags.getOrNull(1)) },
                    { addTagCheckBox(rowTags.getOrNull(2)) },
                )
            }
            row {
                comment(OiiaBundle.message("projectWizard.hoi4.tags.comment", HoI4ModTemplateGenerator.MAX_TAG_COUNT))
            }
        }

        builder.group(OiiaBundle.message("projectWizard.hoi4.launcher.group"), false) {
            row {
                checkBox(OiiaBundle.message("projectWizard.hoi4.create.launcher.descriptor"))
                    .bindSelected(createLauncherDescriptorProperty)
            }
            row(OiiaBundle.message("projectWizard.hoi4.launcher.directory")) {
                textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.createSingleFolderDescriptor(),
                    context.project,
                ) { file -> file.path }
                    .align(AlignX.FILL)
                    .bindText(launcherDescriptorDirectoryProperty)
                    .enabledIf(createLauncherDescriptorProperty)
                    .errorOnApply(OiiaBundle.message("projectWizard.hoi4.validation.launcher.directory")) {
                        createLauncherDescriptorProperty.get() && parsePathOrNull(it.text) == null
                    }
            }
        }
    }

    override fun setupProject(project: Project) {
        val projectDirectory = Path.of(baseStep.path).resolve(baseStep.name).normalize()
        val modDirectory = projectDirectory.resolve("src")
        val settings = HoI4ModSettings(
            name = modNameProperty.get().trim().ifBlank { defaultModName() },
            modVersion = modVersionProperty.get().trim().ifBlank { "0.1.0" },
            supportedVersion = supportedVersionProperty.get().trim().ifBlank { "1.16.*" },
            tags = selectedTags().take(HoI4ModTemplateGenerator.MAX_TAG_COUNT),
            authors = HoI4ModTemplateGenerator.parseList(authorsProperty.get()),
            projectDirectory = projectDirectory,
            modDirectory = modDirectory,
            launcherDescriptorDirectory = if (createLauncherDescriptorProperty.get()) {
                parsePathOrNull(launcherDescriptorDirectoryProperty.get())
            } else {
                null
            },
        )

        HoI4ModTemplateGenerator.generate(settings)
        setupGeneralModule(project)
    }

    private fun setupGeneralModule(project: Project) {
        val moduleType = ModuleTypeManager.getInstance().findByID("GENERAL_MODULE") ?: return
        val moduleBuilder = moduleType.createModuleBuilder()
        setupProjectFromBuilder(project, moduleBuilder)
    }

    private fun com.intellij.ui.dsl.builder.Row.addTagCheckBox(tag: String?) {
        if (tag == null) {
            cell()
            return
        }
        checkBox(tag)
            .bindSelected(tagProperties.getValue(tag))
            .errorOnApply(
                OiiaBundle.message("projectWizard.hoi4.validation.tags.limit", HoI4ModTemplateGenerator.MAX_TAG_COUNT),
            ) {
                selectedTags().size > HoI4ModTemplateGenerator.MAX_TAG_COUNT
            }
    }

    private fun defaultModName(): String {
        return modNameProperty.get().ifBlank { baseStep.name.ifBlank { "HOI4 Mod" } }
    }

    private fun selectedTags(): List<String> {
        return tagProperties.mapNotNull { (tag, selected) -> tag.takeIf { selected.get() } }
    }

    private fun parsePathOrNull(value: String): Path? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        return try {
            Path.of(trimmed).normalize()
        } catch (_: InvalidPathException) {
            null
        }
    }
}
