package net.posdaca.oiia.core

/**
 * Pure text-level parsing for `.gfx` sprite definitions and sprite name normalisation.
 *
 * No IntelliJ / project / filesystem dependencies, so the sprite fallback path stays unit-testable;
 * [ParadoxSpriteResolver] owns IO, caching and the PSI-based path on top of this.
 */
internal object ParadoxGfxParser {

    data class SpriteBlock(val key: String, val body: String)

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

    private val SPRITE_BLOCK_START_REGEX = Regex(
        """(?i)\b(spriteType|frameAnimatedSpriteType|corneredTileSpriteType|maskedShieldType|textSpriteType|progressbartype|circularProgressBarType|pieChartType|LineChartType|quadTextureSprite)\s*=\s*\{"""
    )
    private val NUMBER_REGEX = Regex("""-?\d+(?:\.\d+)?""")

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

    fun findSpriteBlocks(content: String): Sequence<SpriteBlock> {
        val searchableContent = stripLineComments(content)
        return sequence {
            var index = 0
            while (index < searchableContent.length) {
                val match = SPRITE_BLOCK_START_REGEX.find(searchableContent, index) ?: break
                val key = match.groupValues[1]
                val openBraceIndex = match.range.last
                val closeBraceIndex = findMatchingBrace(searchableContent, openBraceIndex)
                if (closeBraceIndex == null) {
                    index = openBraceIndex + 1
                    continue
                }
                yield(SpriteBlock(key, content.substring(openBraceIndex + 1, closeBraceIndex)))
                index = closeBraceIndex + 1
            }
        }
    }

    fun stripLineComments(content: String): String {
        val result = StringBuilder(content.length)
        var inString = false
        var index = 0
        while (index < content.length) {
            val char = content[index]
            if (inString) {
                result.append(char)
                if (char == '\\' && index + 1 < content.length) {
                    index++
                    result.append(content[index])
                } else if (char == '"') {
                    inString = false
                }
            } else {
                when (char) {
                    '"' -> {
                        inString = true
                        result.append(char)
                    }
                    '#' -> {
                        result.append(' ')
                        while (index + 1 < content.length && content[index + 1] != '\n' && content[index + 1] != '\r') {
                            index++
                            result.append(' ')
                        }
                    }
                    else -> result.append(char)
                }
            }
            index++
        }
        return result.toString()
    }

    fun parseAssignmentValue(body: String, key: String): String? {
        return Regex("""(?im)\b${Regex.escape(key)}\s*=\s*("[^"]+"|[^\s#{}]+)""")
            .find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::cleanToken)
    }

    fun parseBorderSize(body: String): ParadoxSpriteResolver.SpriteInsets? {
        val block = Regex("""(?is)\bborderSize\s*=\s*\{(.*?)\}""").find(body)?.groupValues?.getOrNull(1)
            ?: return null
        val sideLeft = parseAssignmentValue(block, "left")?.toDoubleOrNull()?.toInt()
        val sideTop = parseAssignmentValue(block, "top")?.toDoubleOrNull()?.toInt()
        val sideRight = parseAssignmentValue(block, "right")?.toDoubleOrNull()?.toInt()
        val sideBottom = parseAssignmentValue(block, "bottom")?.toDoubleOrNull()?.toInt()
        if (sideLeft != null || sideTop != null || sideRight != null || sideBottom != null) {
            return ParadoxSpriteResolver.SpriteInsets(
                left = sideLeft ?: 0,
                top = sideTop ?: 0,
                right = sideRight ?: sideLeft ?: 0,
                bottom = sideBottom ?: sideTop ?: 0
            )
        }
        val dimensions = bodyDimensionValues(block)
        return ParadoxSpriteResolver.SpriteInsets(
            left = dimensions.horizontalOrFirst,
            top = dimensions.verticalOrSecond,
            right = dimensions.horizontalOrThird,
            bottom = dimensions.verticalOrFourth
        )
    }

    fun parseSpriteSize(body: String): ParadoxSpriteResolver.SpriteSize? {
        val block = Regex("""(?is)\bsize\s*=\s*\{(.*?)\}""").find(body)?.groupValues?.getOrNull(1)
            ?: return null
        val dimensions = bodyDimensionValues(block)
        return ParadoxSpriteResolver.SpriteSize(
            width = dimensions.horizontalOrFirst,
            height = dimensions.verticalOrSecond
        )
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

    private fun findMatchingBrace(content: String, openBraceIndex: Int): Int? {
        var depth = 0
        var inString = false
        var index = openBraceIndex
        while (index < content.length) {
            val c = content[index]
            if (inString) {
                if (c == '\\') {
                    index += 2
                    continue
                }
                if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '#' -> {
                        index = content.indexOf('\n', index).takeIf { it >= 0 } ?: content.length
                        continue
                    }
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return index
                    }
                }
            }
            index++
        }
        return null
    }

    private fun bodyDimensionValues(body: String): DimensionValues {
        return DimensionValues(
            x = parseAssignmentValue(body, "x")?.toDoubleOrNull()?.toInt(),
            y = parseAssignmentValue(body, "y")?.toDoubleOrNull()?.toInt(),
            width = parseAssignmentValue(body, "width")?.toDoubleOrNull()?.toInt(),
            height = parseAssignmentValue(body, "height")?.toDoubleOrNull()?.toInt(),
            values = NUMBER_REGEX.findAll(body).mapNotNull { it.value.toDoubleOrNull()?.toInt() }.toList()
        )
    }

    private data class DimensionValues(
        val x: Int?,
        val y: Int?,
        val width: Int?,
        val height: Int?,
        val values: List<Int>
    ) {
        val horizontalOrFirst: Int
            get() = x ?: width ?: values.getOrNull(0) ?: 0
        val verticalOrSecond: Int
            get() = y ?: height ?: values.getOrNull(1) ?: values.getOrNull(0) ?: 0
        val horizontalOrThird: Int
            get() = x ?: width ?: values.getOrNull(2) ?: values.getOrNull(0) ?: 0
        val verticalOrFourth: Int
            get() = y ?: height ?: values.getOrNull(3) ?: values.getOrNull(1) ?: values.getOrNull(0) ?: 0
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
