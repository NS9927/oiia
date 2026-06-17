package net.posdaca.oiia.shadow

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.util.Key
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class ShadowLaunchProcessHandler(
    private val shadowExecutable: Path,
    private val shadowArguments: List<String>,
    private val errorLogPath: Path?,
) : ProcessHandler() {
    private val stopped = AtomicBoolean(false)
    private val terminated = CountDownLatch(1)
    private var shadowHandler: OSProcessHandler? = null
    private var workerThread: Thread? = null

    override fun startNotify() {
        super.startNotify()
        workerThread = Thread(::runShadowAndLog, "Oiia Shadow launcher").also {
            it.isDaemon = true
            it.start()
        }
    }

    override fun destroyProcessImpl() {
        stopped.set(true)
        shadowHandler?.destroyProcess()
        workerThread?.interrupt()
        terminate(0)
    }

    override fun detachProcessImpl() {
        stopped.set(true)
        shadowHandler?.detachProcess()
        workerThread?.interrupt()
        notifyProcessDetached()
        terminated.countDown()
    }

    override fun detachIsDefault(): Boolean = false

    override fun waitFor(): Boolean {
        terminated.await()
        return true
    }

    override fun waitFor(timeoutInMilliseconds: Long): Boolean {
        return terminated.await(timeoutInMilliseconds, TimeUnit.MILLISECONDS)
    }

    override fun getProcessInput(): OutputStream? {
        return shadowHandler?.processInput
    }

    private fun runShadowAndLog() {
        try {
            val exitCode = runShadow()
            if (exitCode != 0 || stopped.get()) {
                terminate(exitCode)
                return
            }

            if (errorLogPath == null) {
                terminate(0)
                return
            }

            followErrorLog(errorLogPath)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            terminate(0)
        } catch (e: Exception) {
            notifyTextAvailable("${e.message ?: e.javaClass.simpleName}\n", ProcessOutputTypes.STDERR)
            terminate(if (e is ExecutionException) 1 else -1)
        }
    }

    private fun runShadow(): Int {
        val commandLine = GeneralCommandLine()
            .withExePath(shadowExecutable.toString())
            .withWorkDirectory(shadowExecutable.parent?.toFile())
            .withCharset(StandardCharsets.UTF_8)
            .withParameters(shadowArguments)

        val handler = OSProcessHandler(commandLine)
        shadowHandler = handler
        val finished = CountDownLatch(1)
        var exitCode = -1
        handler.addProcessListener(object : ProcessAdapter() {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                notifyTextAvailable(event.text, outputType)
            }

            override fun processTerminated(event: ProcessEvent) {
                exitCode = event.exitCode
                finished.countDown()
            }
        })
        handler.startNotify()
        finished.await()
        return exitCode
    }

    private fun followErrorLog(path: Path) {
        notifyTextAvailable("\n----- HOI4 error.log: $path -----\n", ProcessOutputTypes.SYSTEM)

        while (!stopped.get() && !java.nio.file.Files.isRegularFile(path)) {
            notifyTextAvailable("error.log was not found yet. Waiting for the game to create it...\n", ProcessOutputTypes.SYSTEM)
            sleep(1000)
        }

        if (stopped.get()) {
            terminate(0)
            return
        }

        var snapshot = ShadowGameLogSupport.tailText(path)
        if (snapshot.text.isNotEmpty()) {
            notifyTextAvailable(snapshot.text.ensureTrailingNewline(), ProcessOutputTypes.STDOUT)
        }

        var offset = snapshot.offset
        while (!stopped.get()) {
            sleep(500)
            snapshot = ShadowGameLogSupport.readNewText(path, offset)
            offset = snapshot.offset
            if (snapshot.text.isNotEmpty()) {
                notifyTextAvailable(snapshot.text, ProcessOutputTypes.STDOUT)
            }
        }

        terminate(0)
    }

    private fun sleep(milliseconds: Long) {
        try {
            Thread.sleep(milliseconds)
        } catch (e: InterruptedException) {
            stopped.set(true)
            throw e
        }
    }

    private fun terminate(exitCode: Int) {
        if (terminated.count > 0) {
            notifyProcessTerminated(exitCode)
            terminated.countDown()
        }
    }

    private fun String.ensureTrailingNewline(): String {
        return if (endsWith('\n') || endsWith('\r')) this else "$this\n"
    }
}
