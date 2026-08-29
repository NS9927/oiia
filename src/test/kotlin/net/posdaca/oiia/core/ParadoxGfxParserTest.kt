package net.posdaca.oiia.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParadoxGfxParserTest {
    @Test
    fun findsSpriteBlocksAcrossCommentsAndTypes() {
        val content = """
            # a comment mentioning spriteType = { fake
            spriteType = {
                name = "GFX_test_icon"
                texturefile = "gfx/interface/test_icon.dds"
                noOfFrames = 4
            }
            corneredTileSpriteType = {
                name = "GFX_tiled"
                texturefile = "gfx/tiled.dds"
                borderSize = { left = 5 top = 6 right = 7 bottom = 8 }
            }
        """.trimIndent()

        val blocks = ParadoxGfxParser.findSpriteBlocks(content).toList()

        assertEquals(2, blocks.size)
        assertEquals("spriteType", blocks[0].key)
        assertEquals("GFX_test_icon", ParadoxGfxParser.parseAssignmentValue(blocks[0].body, "name"))
        assertEquals(4, ParadoxGfxParser.parseAssignmentValue(blocks[0].body, "noOfFrames")?.parseParadoxInt())
        assertEquals("GFX_tiled", ParadoxGfxParser.parseAssignmentValue(blocks[1].body, "name"))
    }

    @Test
    fun parsesBorderSizeFromNamedSidesOrPositionalValues() {
        val named = ParadoxGfxParser.parseBorderSize("borderSize = { left = 1 top = 2 right = 3 bottom = 4 }")
        assertEquals(ParadoxSpriteResolver.SpriteInsets(1, 2, 3, 4), named)

        val positional = ParadoxGfxParser.parseBorderSize("borderSize = { 9 8 7 6 }")
        assertEquals(ParadoxSpriteResolver.SpriteInsets(9, 8, 7, 6), positional)

        val pairs = ParadoxGfxParser.parseBorderSize("borderSize = { size = { 2 3 } }")
        assertEquals(ParadoxSpriteResolver.SpriteInsets(2, 3, 2, 3), pairs)
    }

    @Test
    fun parsesSpriteSize() {
        val size = ParadoxGfxParser.parseSpriteSize("size = { width = 120 height = 40 }")
        assertEquals(ParadoxSpriteResolver.SpriteSize(120, 40), size)
        assertNull(ParadoxGfxParser.parseSpriteSize("noSizeHere"))
    }

    @Test
    fun ignoresCommentedAssignments() {
        val body = ParadoxGfxParser.stripLineComments("texturefile = \"a.dds\" # texturefile = \"b.dds\"")
        assertEquals("a.dds", ParadoxGfxParser.parseAssignmentValue(body, "texturefile"))
    }

    @Test
    fun buildsNormalisedAliases() {
        assertEquals(
            setOf("gfx/interface/foo", "interface/foo", "foo"),
            ParadoxGfxParser.spriteAliases("gfx/interface/foo.dds")
        )
        assertEquals(
            setOf("gfx_tech_dog", "tech_dog"),
            ParadoxGfxParser.spriteAliases("GFX_tech_dog")
        )
        assertEquals(setOf("plain"), ParadoxGfxParser.spriteAliases("Plain"))
        assertEquals(emptySet<String>(), ParadoxGfxParser.spriteAliases("   "))
    }

    @Test
    fun mapsPropertyKeysToSubtypes() {
        assertEquals("normal", ParadoxGfxParser.subtypeFromPropertyKey("spriteType"))
        assertEquals("text_sprite", ParadoxGfxParser.subtypeFromPropertyKey("textSpriteType"))
        assertNull(ParadoxGfxParser.subtypeFromPropertyKey("somethingElse"))
        assertEquals(
            ParadoxGfxParser.SPRITE_TYPE_KEYS,
            setOf(
                "spritetype", "frameanimatedspritetype", "corneredtilespritetype", "maskedshieldtype",
                "textspritetype", "progressbartype", "circularprogressbartype", "piecharttype",
                "linecharttype", "quadtexturesprite"
            )
        )
    }
}
