package net.posdaca.oiia.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrefixIconLookupTest {

    @Test
    fun exactAliasWins() {
        val lookup = PrefixIconLookup(mapOf("flag" to "a.dds"))

        assertEquals("a.dds", lookup.find(listOf("flag")))
    }

    @Test
    fun prefixTableServesShortenedAliases() {
        val lookup = PrefixIconLookup(
            linkedMapOf(
                "flag_monarchy" to "monarchy.dds",
                "navy" to "navy.dds"
            )
        )

        // "fla" has no cache entry but is a prefix of the first key in insertion order.
        assertEquals("monarchy.dds", lookup.find(listOf("fla")))
        assertEquals("navy.dds", lookup.find(listOf("nav")))
    }

    @Test
    fun longestExactPrefixWinsOverShorter() {
        val lookup = PrefixIconLookup(
            linkedMapOf(
                "ab" to "ab.dds",
                "ax" to "ax.dds"
            )
        )

        // "abc": length-1 prefix "a" misses, length-2 prefix "ab" hits.
        assertEquals("ab.dds", lookup.find(listOf("abc")))
    }

    @Test
    fun earlierIndexedPrefixWinsOnTies() {
        val lookup = PrefixIconLookup(
            linkedMapOf(
                "ab" to "first.dds",
                "ac" to "second.dds"
            )
        )

        // Both "ab" and "ac" are length-2 prefixes of "abc"; the earlier-indexed one wins.
        assertEquals("first.dds", lookup.find(listOf("abc")))
    }

    @Test
    fun returnsNullWhenNoAliasMatches() {
        val lookup = PrefixIconLookup(mapOf("flag" to "a.dds"))

        assertNull(lookup.find(listOf("zzz", "qq")))
        assertNull(lookup.find(emptyList()))
    }
}
