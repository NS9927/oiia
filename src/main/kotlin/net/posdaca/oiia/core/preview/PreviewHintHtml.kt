package net.posdaca.oiia.core.preview

internal class PreviewHintHtml(private val width: Int = DEFAULT_WIDTH) {
    private val header = StringBuilder()
    private val description = StringBuilder()
    private val rows = StringBuilder()
    private val footer = StringBuilder()

    fun header(type: String, title: String, id: String): PreviewHintHtml {
        header.append("<tr><td><font color='#808080'>&lt;").append(escape(type)).append("&gt;</font> ")
            .append("<b>").append(escape(title)).append("</b></td></tr>")
        header.append("<tr><td><font color='#808080' size='-2'>ID: ").append(escape(id))
            .append("</font></td></tr>")
        return this
    }

    fun description(text: String?): PreviewHintHtml {
        if (text.isNullOrBlank()) return this
        description.append("<tr><td><br><font color='#99ccff'><i>").append(escape(text))
            .append("</i></font></td></tr>")
        return this
    }

    fun row(label: String, value: String?): PreviewHintHtml {
        if (value.isNullOrBlank()) return this
        // Fixed width + nowrap keeps the label column from being squeezed into wrapping
        // by long values; valign aligns the label with the first value line.
        rows.append("<tr><td width='96' valign='top' nowrap><font color='#808080'>")
            .append(escape(label))
            .append(":</font></td><td valign='top'>")
            .append(value)
            .append("</td></tr>")
        return this
    }

    fun escapedRow(label: String, value: String?): PreviewHintHtml = row(label, value?.let(::escape))

    fun footer(text: String?): PreviewHintHtml {
        if (text.isNullOrBlank()) return this
        footer.append("<tr><td><br>").append(escape(text)).append("</td></tr>")
        return this
    }

    fun build(): String {
        val sb = StringBuilder()
        sb.append("<html><table width='").append(width).append("' cellspacing='0' cellpadding='2'>")
        sb.append(header)
        sb.append(description)
        if (rows.isNotEmpty()) {
            sb.append("<tr><td><br><table cellspacing='0' cellpadding='2'>")
            sb.append(rows)
            sb.append("</table></td></tr>")
        }
        sb.append(footer)
        sb.append("</table></html>")
        return sb.toString()
    }

    companion object {
        const val DEFAULT_WIDTH = 340

        fun escape(value: String): String =
            value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}