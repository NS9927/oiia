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
            id = "steam:123456"
            shadowId = "123456"
            remoteFileId = "123456"
            contentPath = "C:/Other/Mod"
            identityKeys = listOf("steam:123456", "shadow:123456")
        }

        val result = ShadowPlaysetSync.matchMods(
            listOf(ShadowRequestedMod(Path.of("C:/Projects/Local"), "123456")),
            listOf(indexMod),
        )

        assertEquals(listOf("steam:123456"), result.matchedModIds)
        assertTrue(result.missingMods.isEmpty())
    }

    @Test
    fun `matches steam identity key when remote file id is not populated`() {
        val indexMod = ShadowModIndexEntry().apply {
            id = "steam:123456"
            shadowId = "123456"
            identityKeys = listOf("steam:123456", "shadow:123456")
        }

        val result = ShadowPlaysetSync.matchMods(
            listOf(ShadowRequestedMod(Path.of("C:/Projects/Local"), "123456")),
            listOf(indexMod),
        )

        assertEquals(listOf("steam:123456"), result.matchedModIds)
        assertTrue(result.missingMods.isEmpty())
    }

    @Test
    fun `matches normalized content path`() {
        val modDirectory = temporaryFolder.newFolder("My Mod").toPath()
        val indexMod = ShadowModIndexEntry().apply {
            id = "local-content:stable"
            shadowId = "C:/Users/User/Documents/Paradox Interactive/Hearts of Iron IV/mod/my_mod.mod"
            contentPath = modDirectory.toString().replace('\\', '/')
            identityKeys = listOf(id, "shadow:$shadowId")
        }

        val result = ShadowPlaysetSync.matchMods(
            listOf(ShadowRequestedMod(modDirectory, null)),
            listOf(indexMod),
        )

        assertEquals(listOf("local-content:stable"), result.matchedModIds)
        assertTrue(result.missingMods.isEmpty())
    }

    @Test
    fun `matches local content identity key without using shadow id`() {
        val modDirectory = temporaryFolder.newFolder("Identity Mod").toPath()
        val indexMod = ShadowModIndexEntry().apply {
            id = "local-content:stable"
            shadowId = "SHADOW_OLD_ID"
            identityKeys = listOf(ShadowPlaysetSync.localContentIdentityKey(modDirectory)!!, "shadow:SHADOW_OLD_ID")
        }

        val result = ShadowPlaysetSync.matchMods(
            listOf(ShadowRequestedMod(modDirectory, null)),
            listOf(indexMod),
        )

        assertEquals(listOf("local-content:stable"), result.matchedModIds)
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

    @Test
    fun `workspace prefers the Shadow root over flat Posdaca layouts`() {
        val appData = temporaryFolder.newFolder("appdata").toPath()
        val shadowIndex = appData.resolve("Posdaca").resolve("Shadow").resolve("Hearts of Iron IV").resolve("mods").resolve("index.json")
        val currentIndex = appData.resolve("Posdaca").resolve("Hearts of Iron IV").resolve("mods").resolve("index.json")
        Files.createDirectories(shadowIndex.parent)
        Files.createDirectories(currentIndex.parent)
        Files.writeString(shadowIndex, "{}")
        Files.writeString(currentIndex, "{}")

        assertEquals(
            appData.resolve("Posdaca").resolve("Shadow").resolve("Hearts of Iron IV"),
            ShadowPlaysetSync.resolveWorkspaceDirectory(appData),
        )
    }

    @Test
    fun `workspace prefers Hearts of Iron IV over legacy Hoi4Workspace`() {
        val appData = temporaryFolder.newFolder("appdata").toPath()
        val currentIndex = appData.resolve("Posdaca").resolve("Hearts of Iron IV").resolve("mods").resolve("index.json")
        val legacyIndex = appData.resolve("Posdaca").resolve("Hoi4Workspace").resolve("mods").resolve("index.json")
        Files.createDirectories(currentIndex.parent)
        Files.createDirectories(legacyIndex.parent)
        Files.writeString(currentIndex, "{}")
        Files.writeString(legacyIndex, "{}")

        assertEquals(
            appData.resolve("Posdaca").resolve("Hearts of Iron IV"),
            ShadowPlaysetSync.resolveWorkspaceDirectory(appData),
        )
    }

    @Test
    fun `workspace falls back to Hoi4Workspace when the new index is missing`() {
        val appData = temporaryFolder.newFolder("legacy-appdata").toPath()
        val legacyIndex = appData.resolve("Posdaca").resolve("Hoi4Workspace").resolve("mods").resolve("index.json")
        Files.createDirectories(legacyIndex.parent)
        Files.writeString(legacyIndex, "{}")

        assertEquals(
            appData.resolve("Posdaca").resolve("Hoi4Workspace"),
            ShadowPlaysetSync.resolveWorkspaceDirectory(appData),
        )
    }

    @Test
    fun `workspace defaults to the Shadow root when no index exists`() {
        val appData = temporaryFolder.newFolder("empty-appdata").toPath()
        assertEquals(
            appData.resolve("Posdaca").resolve("Shadow").resolve("Hearts of Iron IV"),
            ShadowPlaysetSync.resolveWorkspaceDirectory(appData),
        )
    }
}
