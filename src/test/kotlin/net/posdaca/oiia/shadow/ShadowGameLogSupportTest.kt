package net.posdaca.oiia.shadow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class ShadowGameLogSupportTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `reads game user directory from Shadow launcher state`() {
        val statePath = temporaryFolder.newFile("launcher-state.json").toPath()
        val gameUserDirectory = temporaryFolder.newFolder("hoi4").toPath()
        Files.writeString(
            statePath,
            """{"gameUserDirectory":"${gameUserDirectory.toString().replace("\\", "\\\\")}"}""",
        )

        assertEquals(gameUserDirectory.toAbsolutePath().normalize(), ShadowGameLogSupport.readGameUserDirectory(statePath))
    }

    @Test
    fun `uses override path for error log`() {
        val errorLog = temporaryFolder.root.toPath().resolve("logs").resolve("error.log")

        assertEquals(errorLog.toAbsolutePath().normalize(), ShadowGameLogSupport.resolveErrorLogPath(errorLog.toString()))
    }

    @Test
    fun `reads tail text and returns next offset`() {
        val log = temporaryFolder.newFile("error.log").toPath()
        Files.writeString(log, (1..500).joinToString("\n") { "line $it" })

        val snapshot = ShadowGameLogSupport.tailText(log, lineCount = 10)

        assertTrue(snapshot.text.contains("line 500"))
        assertTrue(snapshot.text.contains("line 491"))
        assertTrue(!snapshot.text.contains("line 100"))
        assertEquals(Files.size(log), snapshot.offset)
    }

    @Test
    fun `reads new text from offset`() {
        val log = temporaryFolder.newFile("error.log").toPath()
        Files.writeString(log, "before\n")
        val offset = Files.size(log)
        Files.writeString(log, "before\nafter\n")

        val snapshot = ShadowGameLogSupport.readNewText(log, offset)

        assertEquals("after\n", snapshot.text)
        assertEquals(Files.size(log), snapshot.offset)
    }
}
