package net.posdaca.oiia.core

import com.intellij.openapi.project.Project
import net.posdaca.oiia.core.files.ResourceFiles
import java.nio.file.Path

/** Reads the layout values that HOI4 itself uses from an effective interface `.gui` file. */
internal class GameGuiTemplateLoader(private val project: Project) {
    private data class CachedFile(
        val path: Path,
        val modifiedAt: Long,
        val size: Long,
        val roots: List<GameGuiElement>
    )

    private val cache = mutableMapOf<String, CachedFile>()

    fun load(relativePath: String): List<GameGuiElement> {
        val normalizedRelativePath = relativePath.replace('\\', '/').trimStart('/')
        val path = findEffectiveFile(normalizedRelativePath) ?: return emptyList()
        val stamp = ResourceFiles.fileStamp(path)
        val modifiedAt = stamp
        val size = stamp
        synchronized(cache) {
            cache[normalizedRelativePath]?.let { cached ->
                if (cached.path == path && cached.modifiedAt == modifiedAt && cached.size == size) {
                    return cached.roots
                }
            }
        }

        val content = ResourceFiles.readText(path) ?: return emptyList()
        val roots = parseGuiRoots(ParadoxTextParser.parse(content))
        synchronized(cache) {
            cache[normalizedRelativePath] = CachedFile(path, modifiedAt, size, roots)
        }
        return roots
    }

    private fun findEffectiveFile(relativePath: String): Path? {
        return ResourceFiles.findEffectivePath(project, relativePath)
    }

    private fun parseGuiRoots(entries: List<ParadoxTextEntry>): List<GameGuiElement> {
        val result = mutableListOf<GameGuiElement>()
        collectGuiRoots(entries, result)
        return result
    }

    private fun collectGuiRoots(entries: List<ParadoxTextEntry>, result: MutableList<GameGuiElement>) {
        for (entry in entries) {
            val block = entry.blockEntries()
            if (block.isEmpty()) continue
            if (entry.key.lowercase() in GUI_ELEMENT_TYPES) {
                result.add(parseElement(entry, block))
            } else {
                collectGuiRoots(block, result)
            }
        }
    }

    private fun parseElement(entry: ParadoxTextEntry, fields: List<ParadoxTextEntry>): GameGuiElement {
        val position = fields.firstEntry("position")?.blockEntries()?.toPoint() ?: (0 to 0)
        val size = fields.firstEntry("size")?.blockEntries()?.toSize() ?: (null to null)
        val sprite = SPRITE_KEYS.firstNotNullOfOrNull { fields.firstAtom(it) }
        val children = fields.mapNotNull { field ->
            val childBlock = field.blockEntries()
            if (childBlock.isNotEmpty() && field.key.lowercase() in GUI_ELEMENT_TYPES) {
                parseElement(field, childBlock)
            } else {
                null
            }
        }
        return GameGuiElement(
            type = entry.key,
            name = fields.firstAtom("name"),
            x = position.first,
            y = position.second,
            width = size.first,
            height = size.second,
            sprite = sprite,
            frame = fields.firstAtom("frame")?.toIntOrNull(),
            centerPosition = fields.firstAtom("centerposition").toParadoxBoolean()
                ?: fields.firstAtom("centerPosition").toParadoxBoolean()
                ?: false,
            orientation = fields.firstAtom("orientation") ?: fields.firstAtom("Orientation"),
            font = fields.firstAtom("font") ?: fields.firstAtom("buttonFont"),
            format = fields.firstAtom("format"),
            maxWidth = fields.firstAtom("maxWidth").parseGuiNumber(),
            maxHeight = fields.firstAtom("maxHeight").parseGuiNumber(),
            sourceLine = entry.lineNumber,
            children = children
        )
    }

    private fun List<ParadoxTextEntry>.toPoint(): Pair<Int, Int> {
        val x = firstAtom("x").parseGuiNumber() ?: directNumber(0) ?: 0
        val y = firstAtom("y").parseGuiNumber() ?: directNumber(1) ?: 0
        return x to y
    }

    private fun List<ParadoxTextEntry>.toSize(): Pair<Int?, Int?> {
        val width = (firstAtom("width") ?: firstAtom("x")).parseGuiNumber() ?: directNumber(0)
        val height = (firstAtom("height") ?: firstAtom("y")).parseGuiNumber() ?: directNumber(1)
        return width to height
    }

    private fun List<ParadoxTextEntry>.directNumber(index: Int): Int? =
        getOrNull(index)?.scalarOrKey().parseGuiNumber()

    private fun String?.parseGuiNumber(): Int? = this
        ?.trim()
        ?.trim('"')
        ?.removeSuffix("%%")
        ?.removeSuffix("%")
        ?.toDoubleOrNull()
        ?.toInt()

    private fun String?.toParadoxBoolean(): Boolean? = when (this?.trim()?.trim('"')?.lowercase()) {
        "yes", "true", "1" -> true
        "no", "false", "0" -> false
        else -> null
    }

    companion object {
        private val SPRITE_KEYS = listOf(
            "spriteType",
            "quadTextureSprite",
            "textureFile",
            "backGround",
            "buttonSpriteType",
            "buttonSprite"
        )
        private val GUI_ELEMENT_TYPES = setOf(
            "containerwindowtype",
            "background",
            "icontype",
            "instanttextboxtype",
            "buttontype",
            "positiontype",
            "gridboxtype",
            "smoothlistboxtype",
            "listboxtype",
            "overlappingelementsboxtype"
        )
    }
}

internal data class GameGuiElement(
    val type: String,
    val name: String?,
    val x: Int = 0,
    val y: Int = 0,
    val width: Int? = null,
    val height: Int? = null,
    val sprite: String? = null,
    val frame: Int? = null,
    val centerPosition: Boolean = false,
    val orientation: String? = null,
    val font: String? = null,
    val format: String? = null,
    val maxWidth: Int? = null,
    val maxHeight: Int? = null,
    val sourceLine: Int = 0,
    val children: List<GameGuiElement> = emptyList()
) {
    fun allElements(): Sequence<GameGuiElement> = sequence {
        yield(this@GameGuiElement)
        for (child in children) yieldAll(child.allElements())
    }

    fun child(name: String): GameGuiElement? = children.firstOrNull { it.name.equals(name, ignoreCase = true) }
}

internal fun List<GameGuiElement>.allGuiElements(): Sequence<GameGuiElement> =
    asSequence().flatMap { it.allElements() }

