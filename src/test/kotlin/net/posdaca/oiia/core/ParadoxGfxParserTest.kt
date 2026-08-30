package net.posdaca.oiia.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParadoxGfxParserTest {
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

    @Test
    fun parsesParadoxBooleansAndInts() {
        assertEquals(true, "yes".parseParadoxBoolean())
        assertEquals(false, "no".parseParadoxBoolean())
        assertEquals(true, "1".parseParadoxBoolean())
        assertEquals(null, "maybe".parseParadoxBoolean())
        assertEquals(4, " \"4.0\" ".trim().parseParadoxInt())
        assertEquals(null, "abc".parseParadoxInt())
    }
}
