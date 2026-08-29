package net.posdaca.oiia.core.preview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewHintHtmlTest {
    @Test
    fun buildsEscapedHintDocument() {
        val html = PreviewHintHtml()
            .header("focus", "陆军 <甲>", "army_focus")
            .description("desc & more")
            .escapedRow("Req", "a, b")
            .row("Cost", "70")
            .footer("done")
            .build()

        assertTrue(html.contains("&lt;focus&gt;"))
        assertTrue(html.contains("陆军 &lt;甲&gt;"))
        assertTrue(html.contains("desc &amp; more"))
        assertTrue(html.contains("Req"))
        assertTrue(html.contains("a, b"))
        assertTrue(html.contains("Cost"))
        assertTrue(html.contains("70"))
        assertTrue(html.contains("done"))
        assertFalse(html.contains("<甲>"))
    }

    @Test
    fun skipsBlankOptionalRows() {
        val html = PreviewHintHtml()
            .header("technology", "Infantry", "infantry_weapons")
            .description(null)
            .escapedRow("Path", null)
            .build()

        assertTrue(html.contains("infantry_weapons"))
        assertFalse(html.contains("Path"))
        assertFalse(html.contains("#99ccff"))
    }
}