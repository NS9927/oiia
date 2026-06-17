package net.posdaca.oiia.shadow

import com.google.gson.JsonParser
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.max

internal object ShadowGameLogSupport {
    private const val TAIL_LINE_COUNT = 400

    fun resolveErrorLogPath(overridePath: String): Path {
        if (overridePath.isNotBlank()) {
            return Path.of(expandHome(overridePath)).toAbsolutePath().normalize()
        }

        val stateGameUserDirectory = readGameUserDirectory(defaultLauncherStatePath())
        val gameUserDirectory = stateGameUserDirectory ?: defaultGameUserDirectory()
        return gameUserDirectory.resolve("logs").resolve("error.log").toAbsolutePath().normalize()
    }

    fun readGameUserDirectory(statePath: Path): Path? {
        if (!Files.isRegularFile(statePath)) return null

        return runCatching {
            Files.newBufferedReader(statePath, StandardCharsets.UTF_8).use { reader ->
                val element = JsonParser.parseReader(reader)
                val value = element.asJsonObject.get("gameUserDirectory")?.asString
                value?.takeIf { it.isNotBlank() }?.let { Path.of(expandHome(it)).toAbsolutePath().normalize() }
            }
        }.getOrNull()
    }

    fun defaultLauncherStatePath(): Path {
        val localAppData = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
            ?: Path.of(System.getProperty("user.home"), "AppData", "Local").toString()
        return Path.of(localAppData, "Shadow", "Plugins", "Shadow.Hoi4Launcher", "launcher-state.json")
    }

    fun defaultGameUserDirectory(): Path {
        return Path.of(
            System.getProperty("user.home"),
            "Documents",
            "Paradox Interactive",
            "Hearts of Iron IV",
        )
    }

    fun tailText(path: Path, lineCount: Int = TAIL_LINE_COUNT): TailSnapshot {
        if (!Files.isRegularFile(path)) return TailSnapshot("", 0L)

        RandomAccessFile(path.toFile(), "r").use { file ->
            val length = file.length()
            var pointer = length - 1
            var lines = 0
            while (pointer >= 0 && lines <= lineCount) {
                file.seek(pointer)
                if (file.readByte().toInt().toChar() == '\n') lines++
                pointer--
            }

            val start = max(0L, pointer + 2)
            file.seek(start)
            val bytes = ByteArray((length - start).toInt())
            file.readFully(bytes)
            return TailSnapshot(bytes.toString(Charsets.UTF_8), length)
        }
    }

    fun readNewText(path: Path, offset: Long): TailSnapshot {
        if (!Files.isRegularFile(path)) return TailSnapshot("", offset)

        RandomAccessFile(path.toFile(), "r").use { file ->
            val length = file.length()
            val safeOffset = offset.coerceIn(0L, length)
            if (length <= safeOffset) return TailSnapshot("", length)

            file.seek(safeOffset)
            val bytes = ByteArray((length - safeOffset).toInt())
            file.readFully(bytes)
            return TailSnapshot(bytes.toString(Charsets.UTF_8), length)
        }
    }

    private fun expandHome(value: String): String {
        val trimmed = value.trim()
        val userHome = System.getProperty("user.home")
        return when {
            trimmed == "~" -> userHome
            trimmed.startsWith("~/") || trimmed.startsWith("~\\") -> userHome + trimmed.substring(1)
            else -> trimmed.replace("\$USER_HOME\$", userHome)
        }
    }
}

internal data class TailSnapshot(
    val text: String,
    val offset: Long,
)
