package net.posdaca.oiia.gui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import icu.windea.pls.lang.resolve.ParadoxLocalisationService
import icu.windea.pls.lang.search.ParadoxLocalisationSearch
import icu.windea.pls.script.psi.ParadoxScriptBlock
import icu.windea.pls.script.psi.ParadoxScriptFile
import icu.windea.pls.script.psi.ParadoxScriptProperty
import icu.windea.pls.script.psi.ParadoxScriptValue
import net.posdaca.oiia.core.HoI4ResourceRoots
import net.posdaca.oiia.core.ParadoxLocalisationPreference
import net.posdaca.oiia.core.ParadoxSpriteResolver
import net.posdaca.oiia.core.ParadoxSpriteResolver.SpriteInfo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

class GuiPreviewService(private val project: Project) {

    private val localisationCache = mutableMapOf<String, String?>()
    private var localisationCachePreferenceKey: String? = null
    private val localisationFallbackLock = Any()
    private var localisationFallbackRootsKey: String? = null
    private var localisationFallbackCache: Map<String, String> = emptyMap()
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
            val preferenceKey = ParadoxLocalisationPreference.preferenceKey()
            if (localisationCachePreferenceKey != preferenceKey) {
                localisationCache.clear()
                localisationCachePreferenceKey = preferenceKey
            }
            if (localisationCache.containsKey(trimmed)) return localisationCache[trimmed]
            val plsResolved = runCatching {
                val selector = ParadoxLocalisationSearch.selector(project, null).distinct()
                val property = ParadoxLocalisationSearch.searchNormal(trimmed, selector).find()
                property?.let { ParadoxLocalisationService.resolveLocalizedText(it) ?: it.value }
            }.getOrNull()?.takeIf { it.isNotBlank() && it != trimmed }
            val resolved = plsResolved ?: resolveLocalisationFromFiles(trimmed)
            if (resolved == null) {
                LOG.info("GUI localisation not resolved: key=$trimmed")
            } else {
                LOG.info("GUI localisation resolved: key=$trimmed source=${if (plsResolved != null) "PLS" else "fallback"} value=$resolved")
            }
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
        var font: String? = null
        var buttonFont: String? = null
        var format: String? = null
        var verticalAlignment: String? = null
        var sprite: String? = null
        var quadTextureSprite: String? = null
        var background: String? = null
        var buttonSprite: String? = null
        var hoverSprite: String? = null
        var disabledSprite: String? = null
        var orientation: String? = null
        var origo: String? = null
        var scale: Double? = null
        var centerPosition = false
        var preserveAspectRatio = false
        var fullscreen = false
        var clipping = false
        var frame: Int? = null
        var horizontal: Boolean? = null
        var startValue: Double? = null
        var maxValue: Double? = null
        var minValue: Double? = null
        var maxWidth: GuiValue? = null
        var maxHeight: GuiValue? = null
        var fixedSize = false
        val properties = linkedMapOf<String, String>()
        val spriteCandidates = mutableListOf<String>()
        val children = mutableListOf<GuiElement>()

        for (field in block.propertyList) {
            when (val key = field.propertyKey.text) {
                "name" -> name = scalarValue(field)
                "position" -> position = parsePoint(field.block) ?: position
                "size" -> size = parseSize(field.block) ?: size
                "text" -> text = scalarValue(field)
                "buttonText", "buttontext" -> text = scalarValue(field)
                "font" -> font = scalarValue(field)
                "buttonFont", "buttonfont" -> buttonFont = scalarValue(field)
                "format" -> format = scalarValue(field)
                "vertical_alignment", "verticalAlignment" -> verticalAlignment = scalarValue(field)
                "spriteType" -> sprite = scalarValue(field).also { it?.let(spriteCandidates::add) }
                "quadTextureSprite" -> quadTextureSprite = scalarValue(field).also { it?.let(spriteCandidates::add) }
                "textureFile" -> scalarValue(field)?.let(spriteCandidates::add)
                "backGround" -> background = scalarValue(field).also { it?.let(spriteCandidates::add) }
                "background", "Background" -> {
                    parseElement(field)?.let { children.add(it) }
                }
                "buttonSpriteType" -> buttonSprite = scalarValue(field).also { it?.let(spriteCandidates::add) }
                "buttonSprite" -> buttonSprite = scalarValue(field).also { it?.let(spriteCandidates::add) }
                "hoverSpriteType" -> hoverSprite = scalarValue(field).also { it?.let(spriteCandidates::add) }
                "hoverSprite" -> hoverSprite = scalarValue(field).also { it?.let(spriteCandidates::add) }
                "disabledSpriteType" -> disabledSprite = scalarValue(field).also { it?.let(spriteCandidates::add) }
                "disabledSprite" -> disabledSprite = scalarValue(field).also { it?.let(spriteCandidates::add) }
                "Orientation" -> orientation = scalarValue(field)
                "orientation" -> orientation = scalarValue(field)
                "origo", "Origo" -> origo = scalarValue(field)
                "scale" -> scale = scalarValue(field)?.parseGuiDouble()
                "centerposition" -> centerPosition = scalarValue(field).parseGuiBoolean() ?: centerPosition
                "centerPosition" -> centerPosition = scalarValue(field).parseGuiBoolean() ?: centerPosition
                "preserve_aspect_ratio" -> preserveAspectRatio = scalarValue(field).parseGuiBoolean() ?: preserveAspectRatio
                "fullscreen" -> fullscreen = scalarValue(field).parseGuiBoolean() ?: fullscreen
                "clipping" -> clipping = scalarValue(field).parseGuiBoolean() ?: clipping
                "frame" -> frame = scalarValue(field)?.parseGuiNumber()
                "horizontal" -> horizontal = scalarValue(field).parseGuiBoolean()
                "startValue" -> startValue = scalarValue(field)?.parseGuiDouble()
                "maxValue" -> maxValue = scalarValue(field)?.parseGuiDouble()
                "minValue" -> minValue = scalarValue(field)?.parseGuiDouble()
                "maxWidth", "maxwidth" -> maxWidth = scalarValue(field)?.parseGuiValue()
                "maxHeight", "maxheight" -> maxHeight = scalarValue(field)?.parseGuiValue()
                "fixedsize", "fixedSize" -> fixedSize = scalarValue(field).parseGuiBoolean() ?: fixedSize
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
            font = font,
            buttonFont = buttonFont,
            format = format,
            verticalAlignment = verticalAlignment,
            sprite = sprite,
            quadTextureSprite = quadTextureSprite,
            background = background,
            buttonSprite = buttonSprite,
            hoverSprite = hoverSprite,
            disabledSprite = disabledSprite,
            orientation = orientation,
            origo = origo,
            scale = scale,
            centerPosition = centerPosition,
            preserveAspectRatio = preserveAspectRatio,
            fullscreen = fullscreen,
            clipping = clipping,
            frame = frame,
            horizontal = horizontal,
            startValue = startValue,
            maxValue = maxValue,
            minValue = minValue,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            fixedSize = fixedSize,
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
                "spriteType",
                "quadTextureSprite",
                "textureFile",
                "buttonSpriteType",
                "buttonSprite",
                "backGround" -> scalarValue(field)
                else -> null
            }
        }
    }

    private fun parsePoint(block: ParadoxScriptBlock?): GuiPoint? {
        val properties = block?.propertyList.orEmpty()
        val x = properties.firstOrNull { it.propertyKey.text == "x" }?.value?.parseGuiValue()
        val y = properties.firstOrNull { it.propertyKey.text == "y" }?.value?.parseGuiValue()
        if (x != null || y != null) return GuiPoint(x ?: GuiValue.ZERO, y ?: GuiValue.ZERO)
        val values = blockValues(block)
        if (values.size < 2) return null
        return GuiPoint(values[0].parseGuiValue() ?: GuiValue.ZERO, values[1].parseGuiValue() ?: GuiValue.ZERO)
    }

    private fun parseSize(block: ParadoxScriptBlock?): GuiSize? {
        val properties = block?.propertyList.orEmpty()
        val widthByAxis = properties.firstOrNull { it.propertyKey.text == "x" }?.value?.parseGuiValue()
        val heightByAxis = properties.firstOrNull { it.propertyKey.text == "y" }?.value?.parseGuiValue()
        if (widthByAxis != null || heightByAxis != null) {
            return GuiSize(widthByAxis ?: GuiValue.ZERO, heightByAxis ?: GuiValue.ZERO)
        }
        val widthByName = properties.firstOrNull { it.propertyKey.text == "width" }?.value?.parseGuiValue()
        val heightByName = properties.firstOrNull { it.propertyKey.text == "height" }?.value?.parseGuiValue()
        if (widthByName != null || heightByName != null) {
            return GuiSize(widthByName ?: GuiValue.ZERO, heightByName ?: GuiValue.ZERO)
        }
        val values = blockValues(block)
        if (values.size < 2) return null
        val width = values[0].parseGuiValue() ?: return null
        val height = values[1].parseGuiValue() ?: return null
        return GuiSize(width, height)
    }

    private fun String.parseGuiNumber(): Int? {
        return parseGuiValue()?.asFallbackPixels()
    }

    private fun String.parseGuiValue(): GuiValue? {
        val clean = trim().trim('"')
        val percent = clean.endsWith("%")
        val number = clean.trimEnd('%').toDoubleOrNull() ?: return null
        return GuiValue.of(number, percent)
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

    private fun resolveLocalisationFromFiles(key: String): String? {
        return loadLocalisationFallbackCache()[key]
    }

    private fun loadLocalisationFallbackCache(): Map<String, String> {
        val roots = HoI4ResourceRoots.resourceRoots(project, projectFirst = true, gameFirst = false)
        val rootsKey = ParadoxLocalisationPreference.preferenceKey() + "|" +
                roots.joinToString("|") { HoI4ResourceRoots.normalizedKey(it) }
        synchronized(localisationFallbackLock) {
            if (localisationFallbackRootsKey == rootsKey) return localisationFallbackCache

            val paths = findLocFilePaths(roots)
            val rootScores = roots.mapIndexed { index, root -> HoI4ResourceRoots.normalizedKey(root) to roots.size - index }
            val scoreByKey = mutableMapOf<String, Int>()
            val result = linkedMapOf<String, String>()

            for (path in paths) {
                val score = localisationScore(path, rootScores)
                parseLocFileText(path).forEach { (locKey, value) ->
                    val existing = scoreByKey[locKey] ?: Int.MIN_VALUE
                    if (score > existing) {
                        scoreByKey[locKey] = score
                        result[locKey] = value
                    }
                }
            }

            localisationFallbackRootsKey = rootsKey
            localisationFallbackCache = result
            LOG.info("GUI localisation fallback loaded: roots=${roots.size} files=${paths.size} entries=${result.size}")
            return result
        }
    }

    private fun findLocFilePaths(roots: List<Path>): List<Path> {
        val files = mutableListOf<Path>()
        val seen = mutableSetOf<String>()
        for (root in roots) {
            for (locDir in listOf(root.resolve("localisation"), root.resolve("localization"))) {
                if (!Files.isDirectory(locDir)) continue
                try {
                    Files.walk(locDir, 4).use { stream ->
                        stream
                            .filter { it.isRegularFile() && it.fileName.toString().endsWith(".yml", ignoreCase = true) }
                            .forEach {
                                val path = it.toAbsolutePath().normalize()
                                if (seen.add(HoI4ResourceRoots.normalizedKey(path))) files.add(path)
                            }
                    }
                } catch (e: Exception) {
                    LOG.warn("GUI localisation directory scan failed: $locDir", e)
                }
            }
        }
        return files
    }

    private fun parseLocFileText(path: Path): Map<String, String> {
        val map = linkedMapOf<String, String>()
        try {
            val content = Files.readString(path).removePrefix("\uFEFF")
            LOCALISATION_REGEX.findAll(content).forEach { match ->
                map[match.groupValues[1]] = unescapeLocalisation(match.groupValues[2])
            }
        } catch (e: Exception) {
            LOG.warn("GUI localisation file parse failed: $path", e)
        }
        return map
    }

    private fun localisationScore(path: Path, rootScores: List<Pair<String, Int>>): Int {
        return languagePriority(path.toString()) * LOCALISATION_SCORE_LANGUAGE_WEIGHT +
                localisationRootScore(path, rootScores)
    }

    private fun localisationRootScore(path: Path, rootScores: List<Pair<String, Int>>): Int {
        val key = HoI4ResourceRoots.normalizedKey(path)
        return rootScores.firstOrNull { key.startsWith(it.first) }?.second ?: 0
    }

    private fun languagePriority(path: String): Int {
        return ParadoxLocalisationPreference.languagePriority(path, LANG_PRIORITY)
    }

    private fun unescapeLocalisation(value: String): String {
        return value
            .replace("\\\"", "\"")
            .replace("\\n", " ")
            .replace("\\t", " ")
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
        private val LOG = Logger.getInstance(GuiPreviewService::class.java)
        private const val ROOT_TYPE = "containerWindowType"
        private const val LOCALISATION_SCORE_LANGUAGE_WEIGHT = 10000
        private val ROOT_WRAPPER_TYPES = setOf("guiTypes", "windowTypes")
        private val LANG_PRIORITY = listOf(
            "simp_chinese",
            "english",
            "braz_por",
            "french",
            "german",
            "polish",
            "russian",
            "spanish",
            "japanese"
        )
        private val GUI_ELEMENT_TYPES = setOf(
            "background",
            "Background",
            "containerWindowType",
            "iconType",
            "buttonType",
            "instantTextBoxType",
            "instantTextboxType",
            "listboxType",
            "scrollbarType",
            "editBoxType",
            "checkboxType",
            "gridboxType",
            "gridBoxType",
            "gridboxtype",
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
        private val LOCALISATION_REGEX = Regex("""^\s*([^\s:#]+)\s*:\d*\s*"((?:\\.|[^"])*)"""", RegexOption.MULTILINE)
    }
}
