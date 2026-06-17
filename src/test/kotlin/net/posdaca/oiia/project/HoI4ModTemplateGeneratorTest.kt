package net.posdaca.oiia.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class HoI4ModTemplateGeneratorTest {
    @Test
    fun `generates descriptor files without starter directories`() {
        val tempRoot = Files.createTempDirectory("oiia-hoi4-mod-test")
        try {
            val projectDirectory = tempRoot.resolve("my_mod")
            val modDirectory = projectDirectory.resolve("src")
            val launcherDirectory = tempRoot.resolve("launcher")
            HoI4ModTemplateGenerator.generate(
                HoI4ModSettings(
                    name = "My Mod",
                    modVersion = "0.2.0",
                    supportedVersion = "1.16.*",
                    tags = listOf("Alternative History", "Gameplay"),
                    authors = listOf("Tester"),
                    projectDirectory = projectDirectory,
                    modDirectory = modDirectory,
                    launcherDescriptorDirectory = launcherDirectory,
                ),
            )

            val descriptor = modDirectory.resolve("descriptor.mod").readText()
            assertTrue(descriptor.contains("name=\"My Mod\""))
            assertTrue(descriptor.contains("version=\"0.2.0\""))
            assertTrue(descriptor.contains("supported_version=\"1.16.*\""))
            assertTrue(descriptor.contains("\"Alternative History\""))
            assertFalse(descriptor.contains("path="))
            assertFalse(descriptor.contains("author="))

            val launcherDescriptor = launcherDirectory.resolve("my_mod.mod").readText()
            assertTrue(launcherDescriptor.contains("path=\"${modDirectory.toString().replace('\\', '/')}\""))
            assertFalse(launcherDescriptor.contains("author="))
            assertFalse(Files.exists(modDirectory.resolve("common")))
            assertFalse(Files.exists(modDirectory.resolve("localisation/english")))
            assertTrue(Files.isRegularFile(projectDirectory.resolve("README.md")))
            assertTrue(Files.isRegularFile(projectDirectory.resolve(".gitignore")))
        } finally {
            deleteRecursively(tempRoot)
        }
    }

    @Test
    fun `sanitizes descriptor id`() {
        assertEquals("my_mod_1", HoI4ModTemplateGenerator.sanitizeModId(" My Mod 1! "))
        assertEquals("hoi4_mod", HoI4ModTemplateGenerator.sanitizeModId("!!!"))
    }

    @Test
    fun `limits descriptor tags to launcher maximum`() {
        val settings = HoI4ModSettings(
            name = "My Mod",
            modVersion = "0.2.0",
            supportedVersion = "1.16.*",
            tags = (1..12).map { "Tag $it" },
            authors = emptyList(),
            projectDirectory = Path.of("project"),
            modDirectory = Path.of("project/src"),
            launcherDescriptorDirectory = null,
        )

        val descriptor = HoI4ModTemplateGenerator.descriptorContent(settings, includePath = false)
        assertTrue(descriptor.contains("\"Tag 10\""))
        assertFalse(descriptor.contains("\"Tag 11\""))
    }

    private fun deleteRecursively(root: Path) {
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
