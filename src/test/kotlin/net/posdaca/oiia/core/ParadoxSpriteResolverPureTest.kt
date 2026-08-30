package net.posdaca.oiia.core

import com.intellij.openapi.project.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

/**
 * Pure-logic anchors for the sprite resolver's path/stamp seams. Runs without the IDE platform:
 * the PLS fallback inside [ParadoxSpriteResolver.resolveTexturePathIn] is inert here because
 * `ApplicationManager.getApplication()` is null outside the IDE.
 */
class ParadoxSpriteResolverPureTest {

    @Test
    fun computeGfxStampIsDeterministicAndTracksGfxFiles() {
        val emptyRoot = Files.createTempDirectory("sprite-stamp")
        val gfxRoot = Files.createTempDirectory("sprite-stamp-gfx")
        val interfaceDir = Files.createDirectories(gfxRoot.resolve("gfx/interface"))
        Files.writeString(interfaceDir.resolve("a.gfx"), "spriteTypes = {\n}\n")

        val resolver = newResolver()
        val stampGfx = resolver.computeGfxStamp(listOf(gfxRoot))
        assertEquals(stampGfx, resolver.computeGfxStamp(listOf(gfxRoot)))
        assertNotEquals(stampGfx, resolver.computeGfxStamp(listOf(emptyRoot)))
    }

    @Test
    fun computeGfxStampIgnoresNonGfxFiles() {
        val root = Files.createTempDirectory("sprite-stamp")
        val interfaceDir = Files.createDirectories(root.resolve("gfx/interface"))
        Files.writeString(interfaceDir.resolve("a.gfx"), "spriteTypes = {\n}\n")
        val stamp = newResolver().computeGfxStamp(listOf(root))

        Files.writeString(interfaceDir.resolve("notes.txt"), "ignored")
        assertEquals(stamp, newResolver().computeGfxStamp(listOf(root)))
    }

    @Test
    fun resolveTexturePathInPrefersExistingRootHintFile() {
        val root = Files.createTempDirectory("sprite-root")
        val interfaceDir = Files.createDirectories(root.resolve("gfx/interface"))
        val dds = interfaceDir.resolve("flag.dds")
        Files.writeString(dds, "dds")

        val resolved = newResolver().resolveTexturePathIn("gfx\\interface\\flag.dds", root, emptyMap())

        assertEquals(dds.toAbsolutePath().normalize().toString(), resolved)
    }

    @Test
    fun resolveTexturePathInFallsBackToIconCacheAlias() {
        val resolved = newResolver().resolveTexturePathIn(
            "flag.dds",
            null,
            mapOf("flag" to "C:/game/gfx/flag.dds")
        )

        assertEquals("C:/game/gfx/flag.dds", resolved)
    }

    @Test
    fun resolveTexturePathInReturnsNullWhenNothingMatches() {
        val resolver = newResolver()
        assertNull(resolver.resolveTexturePathIn(null, null, emptyMap()))
        assertNull(resolver.resolveTexturePathIn("", null, emptyMap()))
        assertNull(resolver.resolveTexturePathIn("missing.dds", null, emptyMap()))
    }

    /**
     * The resolver never dereferences the project on the pure seams under test, so a no-op
     * proxy stands in for the IDE project in plain unit tests.
     */
    private fun newResolver(): ParadoxSpriteResolver {
        val proxy = java.lang.reflect.Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java)
        ) { _, _, _ -> null }
        @Suppress("UNCHECKED_CAST")
        return ParadoxSpriteResolver(Project::class.java.cast(proxy) as Project)
    }
}
