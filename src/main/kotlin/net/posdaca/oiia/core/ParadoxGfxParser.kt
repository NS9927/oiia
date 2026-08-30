package net.posdaca.oiia.core

/**
 * Pure helpers for sprite name normalisation and sprite-type keys.
 *
 * `.gfx` structure parsing is done through Paradox-script PSI in [ParadoxSpriteResolver]
 * (`gfx` is one of PLS's script file extensions); these helpers stay unit-testable and
 * independent of IntelliJ PSI.
 */
internal object ParadoxGfxParser {

    val ICON_EXTENSIONS = setOf(".dds", ".tga", ".png")

    val SPRITE_TYPE_KEYS = setOf(
        "spritetype",
        "frameanimatedspritetype",
        "corneredtilespritetype",
        "maskedshieldtype",
        "textspritetype",
        "progressbartype",
        "circularprogressbartype",
        "piecharttype",
        "linecharttype",
        "quadtexturesprite"
    )

    fun cleanToken(value: String): String {
        return value.trim().trim('"')
    }

    fun spriteAliases(name: String): Set<String> {
        val normalized = removeExtension(cleanToken(name).replace('\\', '/').lowercase())
        if (normalized.isBlank()) return emptySet()

        val aliases = linkedSetOf(normalized)
        if (normalized.startsWith("gfx/")) aliases.add(normalized.removePrefix("gfx/"))
        if (normalized.startsWith("interface/")) aliases.add(normalized.removePrefix("interface/"))
        aliases.add(normalized.substringAfterLast('/'))

        val strippedGfxAliases = aliases.toList().mapNotNull { alias ->
            alias.takeIf { it.startsWith("gfx_") }?.removePrefix("gfx_")
        }
        aliases.addAll(strippedGfxAliases)
        return aliases.filterTo(linkedSetOf()) { it.isNotBlank() }
    }

    fun subtypeFromPropertyKey(key: String): String? {
        return when (key.lowercase()) {
            "spritetype" -> "normal"
            "frameanimatedspritetype" -> "frame_animated_sprite"
            "corneredtilespritetype" -> "cornered_tile_sprite"
            "maskedshieldtype" -> "masked_shield"
            "textspritetype" -> "text_sprite"
            "progressbartype" -> "progressbar"
            "circularprogressbartype" -> "circular_progressbar"
            "piecharttype" -> "pie_chart"
            "linecharttype" -> "line_chart"
            "quadtexturesprite" -> "quad_texture"
            else -> null
        }
    }

    private fun removeExtension(name: String): String {
        for (extension in ICON_EXTENSIONS) {
            if (name.endsWith(extension)) return name.removeSuffix(extension)
        }
        return name
    }
}

internal fun String.parseParadoxInt(): Int? {
    return ParadoxGfxParser.cleanToken(this).toDoubleOrNull()?.toInt()
}

internal fun String?.parseParadoxBoolean(): Boolean? {
    return when (this?.let { ParadoxGfxParser.cleanToken(it) }?.lowercase()) {
        "yes", "true", "1" -> true
        "no", "false", "0" -> false
        else -> null
    }
}
