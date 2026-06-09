package net.posdaca.oiia.gui

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import icu.windea.pls.lang.resolve.ParadoxLocalisationService
import icu.windea.pls.lang.search.ParadoxLocalisationSearch
import icu.windea.pls.script.psi.ParadoxScriptBlock
import icu.windea.pls.script.psi.ParadoxScriptFile
import icu.windea.pls.script.psi.ParadoxScriptProperty
import icu.windea.pls.script.psi.ParadoxScriptValue
import net.posdaca.oiia.core.ParadoxSpriteResolver
import net.posdaca.oiia.core.ParadoxSpriteResolver.SpriteInfo

class GuiPreviewService(private val project: Project) {

    private val localisationCache = mutableMapOf<String, String?>()
    private val spriteResolver = ParadoxSpriteResolver(project)

    fun parseGuiFile(psiFile: PsiFile): GuiPreviewFile {
        val roots = if (psiFile is ParadoxScriptFile) {
            parseFromPlsPsi(psiFile)
        } else {
            emptyList()
        }
        return GuiPreviewFile(psiFile.virtualFile?.path, roots)
    }

    fun resolveLocalisation(key: String?): String? {
        val trimmed = key?.trim()?.trim('"')?.takeIf { it.isNotBlank() } ?: return null
        synchronized(localisationCache) {
            if (localisationCache.containsKey(trimmed)) return localisationCache[trimmed]
            val resolved = runCatching {
                val selector = ParadoxLocalisationSearch.selector(project, null).distinct()
                val property = ParadoxLocalisationSearch.searchNormal(trimmed, selector).find()
                property?.let { ParadoxLocalisationService.resolveLocalizedText(it) ?: it.value }
            }.getOrNull()
            localisationCache[trimmed] = resolved
            return resolved
        }
    }

    fun resolveSpritePath(spriteName: String?): String? {
        return spriteResolver.resolveSprite(spriteName)
    }

    fun resolveSpriteInfo(spriteName: String?): SpriteInfo? {
        return spriteResolver.resolveSpriteInfo(spriteName)
    }

    private fun parseFromPlsPsi(psiFile: ParadoxScriptFile): List<GuiElement> {
        val rootBlock = psiFile.block
        val properties = mutableListOf<ParadoxScriptProperty>()
        if (rootBlock != null) properties.addAll(rootBlock.propertyList)
        if (properties.isEmpty()) {
            for (child in psiFile.children) {
                if (child is ParadoxScriptProperty) properties.add(child)
            }
        }
        return collectRootContainers(properties)
            .mapNotNull { parseElement(it) }
            .renameDuplicateRoots()
    }

    private fun collectRootContainers(properties: List<ParadoxScriptProperty>): List<ParadoxScriptProperty> {
        val directRoots = properties.filter { it.propertyKey.text == ROOT_TYPE && it.block != null }
        if (directRoots.isNotEmpty()) return directRoots

        val wrappedRoots = properties
            .filter { it.propertyKey.text in ROOT_WRAPPER_TYPES }
            .flatMap { wrapper ->
                wrapper.block?.propertyList
                    ?.filter { it.propertyKey.text == ROOT_TYPE && it.block != null }
                    .orEmpty()
            }
        if (wrappedRoots.isNotEmpty()) return wrappedRoots

        return properties.flatMap { collectNestedRootContainers(it) }
    }

    private fun collectNestedRootContainers(prop: ParadoxScriptProperty): List<ParadoxScriptProperty> {
        val block = prop.block ?: return emptyList()
        return block.propertyList.flatMap { child ->
            if (child.propertyKey.text == ROOT_TYPE && child.block != null) {
                listOf(child)
            } else if (child.propertyKey.text in ROOT_WRAPPER_TYPES) {
                collectNestedRootContainers(child)
            } else {
                emptyList()
            }
        }
    }

    private fun parseElement(prop: ParadoxScriptProperty): GuiElement? {
        val block = prop.block ?: return null
        val type = prop.propertyKey.text
        if (type !in GUI_ELEMENT_TYPES) return null

        var name: String? = null
        var position = GuiPoint.ZERO
        var size: GuiSize? = null
        var text: String? = null
        var sprite: String? = null
        var quadTextureSprite: String? = null
        var background: String? = null
        var buttonSprite: String? = null
        var hoverSprite: String? = null
        var disabledSprite: String? = null
        var orientation: String? = null
        var frame: Int? = null
        var horizontal: Boolean? = null
        var startValue: Double? = null
        var maxValue: Double? = null
        var minValue: Double? = null
        val properties = linkedMapOf<String, String>()
        val spriteCandidates = mutableListOf<String>()
        val children = mutableListOf<GuiElement>()

        for (field in block.propertyList) {
            when (val key = field.propertyKey.text) {
                "name" -> name = scalarValue(field)
                "position" -> position = parsePoint(field.block) ?: position
                "size" -> size = parseSize(field.block) ?: size
                "text" -> text = scalarValue(field)
                "spriteType" -> sprite = scalarValue(field).also { it?.let(spriteCandidates::add) }
                "quadTextureSprite" -> quadTextureSprite = scalarValue(field).also { it?.let(spriteCandidates::add) }
                "textureFile" -> scalarValue(field)?.let(spriteCandidates::add)
                "backGround" -> background = scalarValue(field).also { it?.let(spriteCandidates::add) }
                "background" -> {
                    val backgroundSprite = parseSpriteFromImageBlock(field) ?: scalarValue(field)
                    background = backgroundSprite
                    backgroundSprite?.let(spriteCandidates::add)
                }
                "buttonSpriteType" -> buttonSprite = scalarValue(field).also { it?.let(spriteCandidates::add) }
                "buttonSprite" -> buttonSprite = scalarValue(field).also { it?.let(spriteCandidates::add) }
                "hoverSpriteType" -> hoverSprite = scalarValue(field).also { it?.let(spriteCandidates::add) }
                "hoverSprite" -> hoverSprite = scalarValue(field).also { it?.let(spriteCandidates::add) }
                "disabledSpriteType" -> disabledSprite = scalarValue(field).also { it?.let(spriteCandidates::add) }
                "disabledSprite" -> disabledSprite = scalarValue(field).also { it?.let(spriteCandidates::add) }
                "Orientation" -> orientation = scalarValue(field)
                "orientation" -> orientation = scalarValue(field)
                "frame" -> frame = scalarValue(field)?.parseGuiNumber()
                "horizontal" -> horizontal = scalarValue(field).parseGuiBoolean()
                "startValue" -> startValue = scalarValue(field)?.parseGuiDouble()
                "maxValue" -> maxValue = scalarValue(field)?.parseGuiDouble()
                "minValue" -> minValue = scalarValue(field)?.parseGuiDouble()
                in GUI_ELEMENT_TYPES -> parseElement(field)?.let { children.add(it) }
                else -> scalarValue(field)?.let { properties[key] = it }
            }
        }

        val vf = prop.containingFile.virtualFile
        val doc = vf?.let { PsiManager.getInstance(project).findViewProvider(it)?.document }
        val line = if (doc != null) doc.getLineNumber(prop.textOffset) + 1 else 0

        return GuiElement(
            type = type,
            name = name,
            position = position,
            size = size,
            text = text,
            sprite = sprite,
            quadTextureSprite = quadTextureSprite,
            background = background,
            buttonSprite = buttonSprite,
            hoverSprite = hoverSprite,
            disabledSprite = disabledSprite,
            orientation = orientation,
            frame = frame,
            horizontal = horizontal,
            startValue = startValue,
            maxValue = maxValue,
            minValue = minValue,
            sourceFilePath = vf?.path,
            sourceLine = line,
            properties = properties,
            spriteCandidates = spriteCandidates.distinct(),
            children = children
        )
    }

    private fun scalarValue(prop: ParadoxScriptProperty): String? {
        return prop.value
            ?: prop.block?.propertyList?.firstOrNull { it.propertyKey.text == "value" }?.value
            ?: prop.block?.propertyList?.firstOrNull { it.propertyKey.text == "name" }?.value
            ?: prop.block?.valueList?.firstOrNull()?.text?.trim()
            ?: prop.propertyValue?.text?.trim()
    }

    private fun parseSpriteFromImageBlock(prop: ParadoxScriptProperty): String? {
        val block = prop.block ?: return null
        return block.propertyList.firstNotNullOfOrNull { field ->
            when (field.propertyKey.text) {
                "spriteType", "quadTextureSprite", "textureFile" -> scalarValue(field)
                else -> null
            }
        }
    }

    private fun parsePoint(block: ParadoxScriptBlock?): GuiPoint? {
        val properties = block?.propertyList.orEmpty()
        val x = properties.firstOrNull { it.propertyKey.text == "x" }?.value?.parseGuiNumber()
        val y = properties.firstOrNull { it.propertyKey.text == "y" }?.value?.parseGuiNumber()
        if (x != null || y != null) return GuiPoint(x ?: 0, y ?: 0)
        val values = blockValues(block)
        if (values.size < 2) return null
        return GuiPoint(values[0].parseGuiNumber() ?: 0, values[1].parseGuiNumber() ?: 0)
    }

    private fun parseSize(block: ParadoxScriptBlock?): GuiSize? {
        val properties = block?.propertyList.orEmpty()
        val widthByName = properties.firstOrNull { it.propertyKey.text == "width" }?.value?.parseGuiNumber()
        val heightByName = properties.firstOrNull { it.propertyKey.text == "height" }?.value?.parseGuiNumber()
        if (widthByName != null || heightByName != null) {
            return GuiSize(widthByName ?: 0, heightByName ?: 0)
        }
        val values = blockValues(block)
        if (values.size < 2) return null
        val width = values[0].parseGuiNumber() ?: return null
        val height = values[1].parseGuiNumber() ?: return null
        return GuiSize(width, height)
    }

    private fun String.parseGuiNumber(): Int? {
        val clean = trim().trim('"').removeSuffix("%")
        return clean.toDoubleOrNull()?.toInt()
    }

    private fun String?.parseGuiDouble(): Double? {
        val clean = this?.trim()?.trim('"')?.removeSuffix("%") ?: return null
        return clean.toDoubleOrNull()
    }

    private fun String?.parseGuiBoolean(): Boolean? {
        return when (this?.trim()?.trim('"')?.lowercase()) {
            "yes", "true", "1" -> true
            "no", "false", "0" -> false
            else -> null
        }
    }

    private fun blockValues(block: ParadoxScriptBlock?): List<String> {
        if (block == null) return emptyList()
        val fromValues = block.valueList.map { it.cleanValueText() }
        if (fromValues.isNotEmpty()) return fromValues
        val fromProperties = block.propertyList.mapNotNull { field ->
            field.value ?: field.propertyKey.text
        }
        if (fromProperties.isNotEmpty()) return fromProperties.map { it.trim().trim('"') }
        return TOKEN_REGEX.findAll(block.text)
            .map { it.value.trim('"') }
            .filterNot { it == "{" || it == "}" }
            .toList()
    }

    private fun ParadoxScriptValue.cleanValueText(): String {
        return text.trim().trim('"')
    }

    private fun List<GuiElement>.renameDuplicateRoots(): List<GuiElement> {
        val counts = groupingBy { it.name.orEmpty() }.eachCount()
        return map { root ->
            if (root.name.isNullOrBlank() || counts[root.name] == 1) root else root.copy(
                properties = root.properties + ("previewDisplayName" to "${root.name} (line ${root.sourceLine})")
            )
        }
    }

    companion object {
        private const val ROOT_TYPE = "containerWindowType"
        private val ROOT_WRAPPER_TYPES = setOf("guiTypes", "windowTypes")
        private val GUI_ELEMENT_TYPES = setOf(
            "containerWindowType",
            "iconType",
            "buttonType",
            "instantTextBoxType",
            "listboxType",
            "scrollbarType",
            "editBoxType",
            "checkboxType",
            "gridboxType",
            "smoothListboxType",
            "smoothListBoxType",
            "overlappingElementsBoxType",
            "OverlappingElementsBoxType",
            "dropDownBoxType",
            "browserType",
            "positionType",
            "extendedScrollbarType",
            "guiButtonType",
            "slider",
            "track",
            "increaseButton",
            "decreaseButton"
        )
        private val TOKEN_REGEX = Regex(""""[^"]*"|[^\s{}=]+""")
    }
}
