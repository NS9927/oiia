package net.posdaca.oiia.shadow

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.LocatableRunConfigurationOptions
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationException
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.components.StoredProperty
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import OiiaBundle
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

internal class ShadowRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String,
) : LocatableConfigurationBase<ShadowRunConfigurationOptions>(project, factory, name) {
    var shadowExecutablePath: String
        get() = options.shadowExecutablePath
        set(value) {
            options.shadowExecutablePath = value
        }

    var allowMissingMods: Boolean
        get() = options.allowMissingMods
        set(value) {
            options.allowMissingMods = value
        }

    var showErrorLog: Boolean
        get() = options.showErrorLog
        set(value) {
            options.showErrorLog = value
        }

    var errorLogPath: String
        get() = options.errorLogPath
        set(value) {
            options.errorLogPath = value
        }

    override fun getOptions(): ShadowRunConfigurationOptions {
        return super.getOptions() as ShadowRunConfigurationOptions
    }

    override fun getOptionsClass(): Class<out RunConfigurationOptions> {
        return ShadowRunConfigurationOptions::class.java
    }

    override fun getConfigurationEditor(): SettingsEditor<out ShadowRunConfiguration> {
        return ShadowRunConfigurationEditor()
    }

    override fun checkConfiguration() {
        if (shadowExecutablePath.isBlank()) {
            throw RuntimeConfigurationException(OiiaBundle.message("run.shadow.validation.executable.required"))
        }

        val executable = Path.of(shadowExecutablePath)
        if (!executable.exists() || !executable.isRegularFile()) {
            throw RuntimeConfigurationException(OiiaBundle.message("run.shadow.validation.executable.invalid"))
        }
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        return ShadowRunProfileState(environment, this)
    }
}

internal class ShadowRunConfigurationOptions : LocatableRunConfigurationOptions() {
    private val shadowExecutablePathProperty: StoredProperty<String?> = string(defaultShadowExecutablePath())
        .provideDelegate(this, "shadowExecutablePath")
    private val errorLogPathProperty: StoredProperty<String?> = string("")
        .provideDelegate(this, "errorLogPath")

    var shadowExecutablePath: String
        get() = shadowExecutablePathProperty.getValue(this) ?: ""
        set(value) {
            shadowExecutablePathProperty.setValue(this, value)
        }

    var allowMissingMods: Boolean by property(false)
    var showErrorLog: Boolean by property(true)

    var errorLogPath: String
        get() = errorLogPathProperty.getValue(this) ?: ""
        set(value) {
            errorLogPathProperty.setValue(this, value)
        }
}

private class ShadowRunProfileState(
    environment: ExecutionEnvironment,
    private val configuration: ShadowRunConfiguration,
) : com.intellij.execution.configurations.CommandLineState(environment) {
    init {
        consoleBuilder = TextConsoleBuilderFactory.getInstance().createBuilder(environment.project)
    }

    override fun startProcess(): ProcessHandler {
        val syncResult = try {
            ShadowPlaysetSync.sync(environment.project)
        } catch (e: ShadowPlaysetSyncException) {
            throw ExecutionException(e.message.orEmpty(), e)
        } catch (e: Exception) {
            throw ExecutionException(e.message ?: e.javaClass.simpleName, e)
        }

        val shadowArguments = buildShadowLaunchArguments(
            playsetId = syncResult.playsetId,
            allowMissingMods = configuration.allowMissingMods,
        )

        val executable = Path.of(configuration.shadowExecutablePath)
        return if (configuration.showErrorLog) {
            ShadowLaunchProcessHandler(
                shadowExecutable = executable,
                shadowArguments = shadowArguments,
                errorLogPath = ShadowGameLogSupport.resolveErrorLogPath(configuration.errorLogPath),
            )
        } else {
            val commandLine = GeneralCommandLine()
                .withExePath(executable.toString())
                .withWorkDirectory(executable.parent?.toFile())
                .withCharset(StandardCharsets.UTF_8)
                .withParameters(shadowArguments)
            val handler = OSProcessHandler(commandLine)
            ProcessTerminatedListener.attach(handler, environment.project)
            handler
        }
    }
}

internal fun buildShadowLaunchArguments(playsetId: String, allowMissingMods: Boolean): List<String> {
    val shadowArguments = mutableListOf("PDXGameLauncher", "hoi4", "-playset", playsetId)
    if (allowMissingMods) {
        shadowArguments.add("-allow-missing-mods")
    }
    return shadowArguments
}

internal fun defaultShadowExecutablePath(): String {
    val userHome = System.getProperty("user.home")
    val candidates = listOf(
        Path.of(userHome, "Projects", "Shadow", "Shadow", "bin", "Debug", "net10.0", "Shadow.exe"),
        Path.of(userHome, "Projects", "Shadow", "Shadow", "bin", "Release", "net10.0", "Shadow.exe"),
    )

    return candidates.firstOrNull { Files.isRegularFile(it) }?.toString() ?: ""
}
