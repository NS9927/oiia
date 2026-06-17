package net.posdaca.oiia.shadow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class ShadowPlaysetSyncTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `matches remote file id before content path`() {
        val indexMod = ShadowModIndexEntry().apply {
            id = "123456"
            remoteFileId = "123456"
            contentPath = "C:/Other/Mod"
        }

        val result = ShadowPlaysetSync.matchMods(
            listOf(ShadowRequestedMod(Path.of("C:/Projects/Local"), "123456")),
            listOf(indexMod),
        )

        assertEquals(listOf("123456"), result.matchedModIds)
        assertTrue(result.missingMods.isEmpty())
    }

    @Test
    fun `matches normalized content path`() {
        val modDirectory = temporaryFolder.newFolder("My Mod").toPath()
        val indexMod = ShadowModIndexEntry().apply {
            id = "LOCAL_MOD"
            contentPath = modDirectory.toString().replace('\\', '/')
        }

        val result = ShadowPlaysetSync.matchMods(
            listOf(ShadowRequestedMod(modDirectory, null)),
            listOf(indexMod),
        )

        assertEquals(listOf("LOCAL_MOD"), result.matchedModIds)
        assertTrue(result.missingMods.isEmpty())
    }

    @Test
    fun `writes playset with shadow compatible file name`() {
        val workspace = temporaryFolder.newFolder("workspace").toPath()
        val path = ShadowPlaysetSync.writePlayset(
            workspace,
            ShadowPlaysetDocument(
                id = "oiia:abcdef",
                name = "Oiia - Test",
                modIds = listOf("LOCAL_MOD"),
                enabledModIds = listOf("LOCAL_MOD"),
            ),
        )

        assertEquals(workspace.resolve("playsets").resolve("oiia_abcdef.json"), path)
        val json = Files.readString(path)
        assertTrue(json.contains("\"id\": \"oiia:abcdef\""))
        assertTrue(json.contains("\"source\": \"Oiia\""))
        assertTrue(json.contains("\"isExternal\": true"))
        assertTrue(json.contains("\"can_edit\": false"))
        assertTrue(json.contains("\"LOCAL_MOD\""))
    }
}
