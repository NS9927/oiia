package net.posdaca.oiia.shadow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class ShadowRunConfigurationTest {
    @Test
    fun `default shadow executable points at the local Shadow build when present`() {
        val path = defaultShadowExecutablePath()
        if (path.isNotBlank()) {
            assertTrue(path.endsWith(Path.of("Shadow", "bin", "Debug", "net10.0", "Shadow.exe").toString()) ||
                    path.endsWith(Path.of("Shadow", "bin", "Release", "net10.0", "Shadow.exe").toString()))
        }
    }

    @Test
    fun `launch arguments use the PDXGameLauncher CLI entry`() {
        assertEquals(
            listOf("PDXGameLauncher", "hoi4", "-playset", "oiia:demo"),
            buildShadowLaunchArguments(playsetId = "oiia:demo", allowMissingMods = false),
        )
    }

    @Test
    fun `launch arguments include allow missing mods flag when enabled`() {
        assertEquals(
            listOf("PDXGameLauncher", "hoi4", "-playset", "oiia:demo", "-allow-missing-mods"),
            buildShadowLaunchArguments(playsetId = "oiia:demo", allowMissingMods = true),
        )
    }
}
