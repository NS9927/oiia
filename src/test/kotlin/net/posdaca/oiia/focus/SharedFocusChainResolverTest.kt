package net.posdaca.oiia.focus

import org.junit.Assert.assertEquals
import org.junit.Test

class SharedFocusChainResolverTest {
    @Test
    fun `reference loads shared focus and following definitions from same file`() {
        val fileA = listOf(
            focus("A1"),
            focus("A2"),
            focus("A3"),
        )
        val fileB = listOf(
            focus("B1"),
            focus("B2"),
        )

        val expanded = SharedFocusChainResolver.expand(
            referencedIds = listOf("A2"),
            definitionsByFile = mapOf(
                "a.txt" to fileA,
                "b.txt" to fileB,
            ),
        )

        assertEquals(listOf("A2", "A3"), expanded.map { it.id })
        assertEquals(true, expanded.all { it.isSharedFocus })
    }

    @Test
    fun `multiple references union chains and keep first occurrence`() {
        val fileA = listOf(focus("A1"), focus("A2"), focus("A3"))
        val fileB = listOf(focus("B1"), focus("B2"))

        val expanded = SharedFocusChainResolver.expand(
            referencedIds = listOf("A2", "B1", "A1"),
            definitionsByFile = mapOf(
                "a.txt" to fileA,
                "b.txt" to fileB,
            ),
            inlineSharedFocuses = listOf(focus("INLINE", shared = false)),
        )

        assertEquals(listOf("INLINE", "A2", "A3", "B1", "B2", "A1"), expanded.map { it.id })
        assertEquals(true, expanded.first { it.id == "INLINE" }.isSharedFocus)
    }

    @Test
    fun `unknown reference is ignored`() {
        val expanded = SharedFocusChainResolver.expand(
            referencedIds = listOf("missing"),
            definitionsByFile = mapOf("a.txt" to listOf(focus("A1"))),
        )
        assertEquals(emptyList<String>(), expanded.map { it.id })
    }

    private fun focus(id: String, shared: Boolean = true): FocusData {
        return FocusData(id = id, isSharedFocus = shared)
    }
}
