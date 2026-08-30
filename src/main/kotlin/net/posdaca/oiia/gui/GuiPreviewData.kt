package net.posdaca.oiia.gui

import net.posdaca.oiia.core.preview.PreviewSnapshot
import net.posdaca.oiia.core.ParadoxSpriteResolver.SpriteInfo
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.math.roundToInt

data class GuiPreviewFile(
    val sourceFilePath: String?,
    val roots: List<GuiElement>
)

data class GuiPreviewResources(
    val localisationKey: String,
    val sprites: Map<String, SpriteInfo?> = emptyMap(),
    val localisations: Map<String, String?> = emptyMap(),
    /**
     * Render inputs keyed by image path. Treat as read-only: services only decode into it and
     * panels only draw from it; neither side may mutate the [BufferedImage] pixel data, since
     * these maps are shared between the resource caches and the immutable snapshot contract.
     */
    val images: Map<String, BufferedImage> = emptyMap()
)


data class GuiPreviewSnapshot(
    val file: GuiPreviewFile,
    val resources: GuiPreviewResources
) : PreviewSnapshot {
    override val isEmpty: Boolean get() = file.roots.isEmpty()
}

data class GuiElement(
    val type: String,
    val name: String?,
    val position: GuiPoint = GuiPoint.ZERO,
    val size: GuiSize? = null,
    val text: String? = null,
    val font: String? = null,
    val buttonFont: String? = null,
    val format: String? = null,
    val slotSize: GuiSize? = null,
    val verticalAlignment: String? = null,
    val sprite: String? = null,
    val quadTextureSprite: String? = null,
    val background: String? = null,
    val buttonSprite: String? = null,
    val hoverSprite: String? = null,
    val disabledSprite: String? = null,
    val orientation: String? = null,
    val origo: String? = null,
    val scale: Double? = null,
    val centerPosition: Boolean = false,
    val preserveAspectRatio: Boolean = false,
    val fullscreen: Boolean = false,
    val clipping: Boolean = false,
    val frame: Int? = null,
    val horizontal: Boolean? = null,
    val startValue: Double? = null,
    val maxValue: Double? = null,
    val minValue: Double? = null,
    val maxWidth: GuiValue? = null,
    val maxHeight: GuiValue? = null,
    val fixedSize: Boolean = false,
    val sourceFilePath: String? = null,
    val sourceOffset: Int = -1,
    val sourceLine: Int = 0,
    val properties: Map<String, String> = emptyMap(),
    val spriteCandidates: List<String> = emptyList(),
    val children: List<GuiElement> = emptyList()
) {
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() } ?: "$type @ line $sourceLine"

    val preferredSize: GuiSize
        get() = size ?: defaultSize(type)

    /** Depth-first descendants whose [GuiElement.type] matches, case-insensitively. */
    fun descendants(type: String): Sequence<GuiElement> = sequence {
        for (child in children) {
            if (child.type.equals(type, ignoreCase = true)) yield(child)
            yieldAll(child.descendants(type))
        }
    }

    val primarySprite: String?
        get() = resolvedSpriteCandidates().firstOrNull()

    fun resolvedSpriteCandidates(): List<String> {
        val result = linkedSetOf<String>()
        spriteCandidates.mapNotNullTo(result) { normalisedToken(it) }
        listOf(
            sprite,
            quadTextureSprite,
            background,
            buttonSprite,
            hoverSprite,
            disabledSprite
        ).mapNotNullTo(result) { normalisedToken(it) }
        return result.toList()
    }

    companion object {
        fun defaultSize(type: String): GuiSize {
            return when (type) {
                "instantTextBoxType", "instantTextboxType" -> GuiSize(180, 28)
                "buttonType" -> GuiSize(160, 36)
                "iconType" -> GuiSize(48, 48)
                "listboxType", "gridboxType", "gridBoxType", "gridboxtype", "smoothListboxType", "smoothListBoxType" -> GuiSize(220, 180)
                "scrollbarType" -> GuiSize(18, 160)
                "editBoxType" -> GuiSize(160, 28)
                "checkboxType" -> GuiSize(24, 24)
                else -> GuiSize(320, 220)
            }
        }
    }
}

data class GuiPoint(val xValue: GuiValue, val yValue: GuiValue) {
    val x: Int
        get() = xValue.asFallbackPixels()

    val y: Int
        get() = yValue.asFallbackPixels()

    fun resolveX(parentWidth: Int): Int = xValue.resolve(parentWidth)

    fun resolveY(parentHeight: Int): Int = yValue.resolve(parentHeight)

    companion object {
        val ZERO = GuiPoint(GuiValue.ZERO, GuiValue.ZERO)
    }
}

data class GuiSize(val widthValue: GuiValue, val heightValue: GuiValue) {
    constructor(width: Int, height: Int) : this(GuiValue.pixels(width), GuiValue.pixels(height))

    val width: Int
        get() = widthValue.asFallbackPixels()

    val height: Int
        get() = heightValue.asFallbackPixels()

    fun resolveWidth(parentWidth: Int): Int = widthValue.resolveSize(parentWidth)

    fun resolveHeight(parentHeight: Int): Int = heightValue.resolveSize(parentHeight)
}

data class GuiValue(val value: Double, val percent: Boolean = false) {
    fun resolve(total: Int): Int {
        val resolved = if (percent) total * value / 100.0 else value
        return resolved.roundToInt()
    }

    fun resolveSize(total: Int): Int {
        val resolved = if (percent) {
            val percentage = total * kotlin.math.abs(value) / 100.0
            if (value < 0) total - percentage else percentage
        } else {
            if (value < 0) total + value else value
        }
        return resolved.roundToInt()
    }

    fun asFallbackPixels(): Int = value.roundToInt()

    override fun toString(): String {
        val number = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
        return if (percent) "$number%" else number
    }

    companion object {
        val ZERO = pixels(0)

        fun pixels(value: Int): GuiValue = GuiValue(value.toDouble(), false)

        fun of(value: Double, percent: Boolean): GuiValue = GuiValue(value, percent)
    }
}

data class GuiLayoutNode(
    val element: GuiElement,
    val bounds: Rectangle,
    val clipBounds: Rectangle?,
    val depth: Int,
    val issues: List<GuiPreviewIssue> = emptyList()
)

data class GuiPreviewIssue(
    val severity: GuiIssueSeverity,
    val message: String
)

enum class GuiIssueSeverity {
    INFO,
    WARNING
}

data class GuiLayoutResult(
    val nodes: List<GuiLayoutNode>,
    val logicalSize: Dimension
)

internal enum class GuiFrameDirection { HORIZONTAL, VERTICAL }

internal fun guiFrameDirection(imageWidth: Int, imageHeight: Int, frames: Int): GuiFrameDirection {
    if (frames <= 1) return GuiFrameDirection.VERTICAL
    val horizontalFrameWidth = imageWidth / frames
    val verticalFrameHeight = imageHeight / frames
    if (horizontalFrameWidth <= 0) return GuiFrameDirection.VERTICAL
    if (verticalFrameHeight <= 0) return GuiFrameDirection.HORIZONTAL
    val horizontalRatio = horizontalFrameWidth.toDouble() / imageHeight.toDouble()
    val verticalRatio = imageWidth.toDouble() / verticalFrameHeight.toDouble()
    return if (kotlin.math.abs(horizontalRatio - 1.0) <= kotlin.math.abs(verticalRatio - 1.0)) {
        GuiFrameDirection.HORIZONTAL
    } else {
        GuiFrameDirection.VERTICAL
    }
}

/** One animation frame's native size derived from the texture dimensions. */
internal fun guiNativeSpriteSize(imageWidth: Int, imageHeight: Int, spriteInfo: SpriteInfo): Dimension? {
    if (spriteInfo.usesDeclaredSpriteSize) spriteInfo.size?.toGuiDimension()?.let { return it }
    val frames = spriteInfo.noOfFrames?.coerceAtLeast(1) ?: 1
    if (frames <= 1) return Dimension(imageWidth, imageHeight)
    return when (guiFrameDirection(imageWidth, imageHeight, frames)) {
        GuiFrameDirection.HORIZONTAL -> Dimension((imageWidth / frames).coerceAtLeast(1), imageHeight)
        GuiFrameDirection.VERTICAL -> Dimension(imageWidth, (imageHeight / frames).coerceAtLeast(1))
    }
}

internal val SpriteInfo.usesCompositeTextures: Boolean
    get() = subtype == "progressbar" || subtype == "circular_progressbar" || subtype == "masked_shield"

internal val SpriteInfo.usesDeclaredSpriteSize: Boolean
    get() = subtype == "progressbar" || subtype == "circular_progressbar"

internal fun net.posdaca.oiia.core.ParadoxSpriteResolver.SpriteSize.toGuiDimension(): Dimension? {
    if (width <= 0 || height <= 0) return null
    return Dimension(width, height)
}

private fun normalisedToken(value: String?): String? {
    return value?.trim()?.trim('"')?.takeIf { it.isNotBlank() }
}
