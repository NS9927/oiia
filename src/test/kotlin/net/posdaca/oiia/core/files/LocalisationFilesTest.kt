package net.posdaca.oiia.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class LocalisationFilesTest {
    @Test
    fun mergePreferredPicksHigherScoreAndFiltersKeys() {
        val root = Files.createTempDirectory("oiia-loc-merge")
        try {
            val english = root.resolve("localisation").resolve("english")
            val chinese = root.resolve("localisation").resolve("simp_chinese")
            Files.createDirectories(english)
            Files.createDirectories(chinese)
            Files.write(
                english.resolve("game_l_english.yml"),
                "l_english:\n KEY:0 \"en\"\n OTHER:0 \"keep\"\n".toByteArray(StandardCharsets.UTF_8)
            )
            Files.write(
                chinese.resolve("game_l_simp_chinese.yml"),
                "l_simp_chinese:\n KEY:0 \"zh\"\n OTHER:0 \"ignored\"\n".toByteArray(StandardCharsets.UTF_8)
            )

            val merged = LocalisationFiles.mergeFromRoots(
                roots = listOf(root),
                languages = listOf("simp_chinese", "english"),
                keys = setOf("KEY"),
            )

            assertEquals("zh", merged["KEY"])
            assertFalse(merged.containsKey("OTHER"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun mergePreferredCanUnescapeValues() {
        val root = Files.createTempDirectory("oiia-loc-unescape")
        try {
            val loc = root.resolve("localisation")
            Files.createDirectories(loc)
            Files.write(
                loc.resolve("game_l_english.yml"),
                "l_english:\n KEY:0 \"hello\\nworld\"\n".toByteArray(StandardCharsets.UTF_8)
            )

            val merged = LocalisationFiles.mergePreferred(
                LocalisationFiles.findFiles(listOf(root)),
                score = { 1 },
                unescapeValues = true,
            )
            assertEquals("hello world", merged["KEY"])
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
