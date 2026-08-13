package net.posdaca.oiia.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class ResourceFilesTest {
    @Test
    fun normalizedKeyLowercasesAndStabilizes() {
        val dir = Files.createTempDirectory("oiia-rf-norm")
        try {
            val path = Files.createFile(dir.resolve("Foo.TXT"))
            val key = ResourceFiles.normalizedKey(path)
            assertTrue(key == key.lowercase())
            assertEquals(key, ResourceFiles.normalizedKey(path.toAbsolutePath()))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun listFilesFindsExtensionsUnderRelativeDirs() {
        val root = Files.createTempDirectory("oiia-rf-list")
        try {
            val loc = root.resolve("localisation")
            Files.createDirectories(loc)
            Files.write(loc.resolve("a.yml"), "KEY:0 \"v\"".toByteArray(StandardCharsets.UTF_8))
            Files.write(loc.resolve("b.txt"), "nope".toByteArray(StandardCharsets.UTF_8))
            val nested = loc.resolve("english")
            Files.createDirectories(nested)
            Files.write(nested.resolve("c.yml"), "K2:0 \"x\"".toByteArray(StandardCharsets.UTF_8))

            val found = ResourceFiles.listFiles(
                roots = listOf(root),
                relativeDirectories = listOf("localisation"),
                extensions = setOf(".yml"),
                maxDepth = 3,
            )

            assertEquals(2, found.size)
            assertTrue(found.any { it.fileName.toString() == "a.yml" })
            assertTrue(found.any { it.fileName.toString() == "c.yml" })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rootScorePrefersEarlierRoots() {
        val base = Files.createTempDirectory("oiia-rf-score")
        try {
            val first = Files.createDirectories(base.resolve("mod"))
            val second = Files.createDirectories(base.resolve("game"))
            val file = first.resolve("localisation").resolve("x.yml")
            Files.createDirectories(file.parent)
            Files.write(file, byteArrayOf(1))

            val score = ResourceFiles.rootScore(file, listOf(first, second))
            assertEquals(2, score)
            assertEquals(1, ResourceFiles.rootScore(second.resolve("localisation").resolve("y.yml"), listOf(first, second)))
            assertEquals(0, ResourceFiles.rootScore(base.resolve("other").resolve("z.yml"), listOf(first, second)))
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun readTextStripsBom() {
        val dir = Files.createTempDirectory("oiia-rf-bom")
        try {
            val file = dir.resolve("bom.txt")
            Files.write(file, "\uFEFFhello".toByteArray(StandardCharsets.UTF_8))
            assertEquals("hello", ResourceFiles.readText(file))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}