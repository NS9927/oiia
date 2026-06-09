package net.posdaca.oiia.gui

import java.awt.Rectangle

data class GuiPreviewFile(
    val sourceFilePath: String?,
    val roots: List<GuiElement>
)

data class GuiElement(
    val type: String,
    val name: String?,
    val position: GuiPoint = GuiPoint.ZERO,
    val size: GuiSize? = null,
    val text: String? = null,
    val sprite: String? = null,
    val quadTextureSprite: String? = null,
    val background: String? = null,
    val buttonSprite: String? = null,
    val hoverSprite: String? = null,
    val disabledSprite: String? = null,
    val orientation: String? = null,
    val sourceFilePath: String? = null,
    val sourceLine: Int = 0,
    val properties: Map<String, String> = emptyMap(),
    val spriteCandidates: List<String> = emptyList(),
    val children: List<GuiElement> = emptyList()
) {
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() } ?: "$type @ line $sourceLine"

    val preferredSize: GuiSize
        get() = size ?: defaultSize(type)

    val primarySprite: String?
        get() = spriteCandidates.firstOrNull()
            ?: sprite
            ?: quadTextureSprite
            ?: background
            ?: buttonSprite
            ?: hoverSprite
            ?: disabledSprite

    companion object {
        fun defaultSize(type: String): GuiSize {
            return when (type) {
                "instantTextBoxType" -> GuiSize(180, 28)
                "buttonType" -> GuiSize(160, 36)
                "iconType" -> GuiSize(48, 48)
                "listboxType" -> GuiSize(220, 180)
                "scrollbarType" -> GuiSize(18, 160)
                "editBoxType" -> GuiSize(160, 28)
                "checkboxType" -> GuiSize(24, 24)
                else -> GuiSize(320, 220)
            }
        }
    }
}

data class GuiPoint(val x: Int, val y: Int) {
    companion object {
        val ZERO = GuiPoint(0, 0)
    }
}

data class GuiSize(val width: Int, val height: Int)

data class GuiLayoutNode(
    val element: GuiElement,
    val bounds: Rectangle,
    val depth: Int,
    val issues: List<GuiPreviewIssue> = emptyList()
)

data class GuiPreviewIssue(
    val severity: GuiIssueSeverity,
    val message: String
)

enum class GuiIssueSeverity {
    INFO,
    WARNING,
    ERROR
}
