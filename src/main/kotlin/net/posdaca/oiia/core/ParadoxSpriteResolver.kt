package net.posdaca.oiia.core

import com.intellij.openapi.project.Project
import icu.windea.pls.lang.search.ParadoxDefinitionSearch
import icu.windea.pls.lang.util.ParadoxDefinitionManager
import icu.windea.pls.lang.util.ParadoxImageManager
import icu.windea.pls.script.psi.ParadoxScriptProperty
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

class ParadoxSpriteResolver(private val project: Project) {

    private data class SpriteDefinition(val name: String, val textureFile: String, val root: Path)

    private val resolvedSpriteUrls = mutableMapOf<String, String?>()
    private val iconFiles = mutableMapOf<String, String>()
    private val spriteIconFiles = mutableMapOf<String, String>()
    private var cacheRootsKey: String? = null

    fun resolveDefinitionImage(definition: ParadoxScriptProperty): String? {
        return runCatching {
            ParadoxImageManager.resolveUrlByDefinition(definition)
                ?: ParadoxDefinitionManager.getPrimaryImages(definition).firstOrNull()?.virtualFile?.let { vf ->
                    ParadoxImageManager.resolveUrlByFile(vf, project) ?: vf.path
                }
        }.getOrNull()
    }

    fun resolveSprite(name: String?): String? {
        val normalized = name?.trim()?.trim('"')?.takeIf { it.isNotBlank() } ?: return null
        synchronized(resolvedSpriteUrls) {
            if (resolvedSpriteUrls.containsKey(normalized)) return resolvedSpriteUrls[normalized]
            val resolved = resolveSpriteWithPls(normalized)
                ?: findCachedSpriteIconPath(normalized)
                ?: findCachedIconPath(normalized)
            resolvedSpriteUrls[normalized] = resolved
            return resolved
        }
    }

    fun resolveFirst(names: Iterable<String?>): String? {
        for (name in names) {
            val resolved = resolveSprite(name)
            if (resolved != null) return resolved
        }
        return null
    }

    fun resolveForCandidates(candidatesById: Map<String, List<String>>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for ((id, candidates) in candidatesById) {
            resolveFirst(candidates)?.let { result[id] = it }
        }
        return result
    }

    private fun resolveSpriteWithPls(name: String): String? {
        return runCatching {
            val selector = ParadoxDefinitionSearch.selector(project, null).distinct()
            val definitions = ParadoxDefinitionSearch.searchProperty(name, "sprite", selector).findAll()
                .ifEmpty { ParadoxDefinitionSearch.searchProperty(name, null, selector).findAll() }
            definitions
                .asSequence()
                .filter { it.isSpriteDefinition() }
                .mapNotNull(::resolveDefinitionImage)
                .firstOrNull()
        }.getOrNull()
    }

    private fun ParadoxScriptProperty.isSpriteDefinition(): Boolean {
        val key = propertyKey.text
        if (key in SPRITE_DEFINITION_PROPERTY_KEYS) return true
        val type = runCatching { ParadoxDefinitionManager.getType(this) }.getOrNull()
        return type != null && type.contains("sprite", ignoreCase = true)
    }

    private fun ensureIconCache() {
        val roots = HoI4ResourceRoots.resourceRoots(project, projectFirst = true, gameFirst = false)
        val rootsKey = roots.joinToString("|") { HoI4ResourceRoots.normalizedKey(it) }
        if (cacheRootsKey == rootsKey) return
        synchronized(this) {
            if (cacheRootsKey == rootsKey) return
            iconFiles.clear()
            spriteIconFiles.clear()

            val spriteDefinitions = mutableListOf<SpriteDefinition>()
            for (root in roots.asReversed()) {
                cacheImages(root)
                cacheSpriteDefinitions(root, spriteDefinitions)
            }

            for (sprite in spriteDefinitions) {
                val directPath = resolveGamePath(sprite.root, sprite.textureFile)
                val iconPath = if (directPath.isRegularFile()) {
                    directPath.toAbsolutePath().normalize().toString()
                } else {
                    lookupCachedPath(iconFiles, sprite.textureFile)
                }
                if (iconPath != null) putAliases(spriteIconFiles, sprite.name, iconPath)
            }

            cacheRootsKey = rootsKey
            resolvedSpriteUrls.clear()
        }
    }

    private fun cacheImages(root: Path) {
        val gfxDir = root.resolve("gfx")
        if (!Files.isDirectory(gfxDir)) return
        runCatching {
            Files.walk(gfxDir, 6).use { stream ->
                stream
                    .filter { it.isRegularFile() }
                    .filter { path -> ICON_EXTENSIONS.any { path.fileName.toString().endsWith(it, ignoreCase = true) } }
                    .forEach { cacheIconFile(root, it) }
            }
        }
    }

    private fun cacheSpriteDefinitions(root: Path, spriteDefinitions: MutableList<SpriteDefinition>) {
        for (dir in listOf(root.resolve("interface"), root.resolve("gfx"))) {
            if (!Files.isDirectory(dir)) continue
            runCatching {
                Files.walk(dir, 6).use { stream ->
                    stream
                        .filter { it.isRegularFile() && it.fileName.toString().endsWith(".gfx", ignoreCase = true) }
                        .forEach { parseGfxFile(root, it, spriteDefinitions) }
                }
            }
        }
    }

    private fun parseGfxFile(root: Path, path: Path, spriteDefinitions: MutableList<SpriteDefinition>) {
        val content = runCatching { Files.readString(path) }.getOrNull() ?: return
        SPRITE_BLOCK_REGEX.findAll(content).forEach { match ->
            val body = match.groupValues[2]
            val name = NAME_ASSIGNMENT_REGEX.find(body)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                ?: return@forEach
            val texture = TEXTURE_ASSIGNMENT_REGEX.find(body)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                ?: return@forEach
            spriteDefinitions.add(SpriteDefinition(cleanToken(name), cleanToken(texture), root))
        }
    }

    private fun cacheIconFile(root: Path, file: Path) {
        val absolutePath = file.toAbsolutePath().normalize().toString()
        putAliases(iconFiles, file.fileName.toString().substringBeforeLast("."), absolutePath)
        runCatching {
            putAliases(iconFiles, root.relativize(file).toString().substringBeforeLast("."), absolutePath)
        }
    }

    private fun findCachedSpriteIconPath(name: String): String? {
        ensureIconCache()
        return lookupCachedPath(spriteIconFiles, name)
    }

    private fun findCachedIconPath(name: String): String? {
        ensureIconCache()
        return lookupCachedPath(iconFiles, name)
    }

    private fun lookupCachedPath(cache: Map<String, String>, name: String): String? {
        for (alias in aliases(name)) {
            cache[alias]?.let { return it }
        }
        return null
    }

    private fun putAliases(cache: MutableMap<String, String>, name: String, path: String) {
        for (alias in aliases(name)) {
            cache.putIfAbsent(alias, path)
        }
    }

    private fun aliases(name: String): Set<String> {
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

    private fun removeExtension(name: String): String {
        for (extension in ICON_EXTENSIONS) {
            if (name.endsWith(extension)) return name.removeSuffix(extension)
        }
        return name
    }

    private fun resolveGamePath(root: Path, rawPath: String): Path {
        val normalizedPath = cleanToken(rawPath).replace('\\', '/')
        val path = runCatching { Path.of(normalizedPath) }.getOrNull()
        return if (path != null && path.isAbsolute) path.normalize() else root.resolve(normalizedPath).normalize()
    }

    private fun cleanToken(value: String): String {
        return value.trim().trim('"')
    }

    companion object {
        private val ICON_EXTENSIONS = setOf(".dds", ".tga", ".png")
        private val SPRITE_DEFINITION_PROPERTY_KEYS = setOf("spriteType", "quadTextureSprite")
        private val SPRITE_BLOCK_REGEX = Regex(
            """(?is)\b(spriteType|quadTextureSprite)\s*=\s*\{(.*?)\n\s*\}"""
        )
        private val NAME_ASSIGNMENT_REGEX = Regex("""(?im)\bname\s*=\s*("[^"]+"|[^\s#]+)""")
        private val TEXTURE_ASSIGNMENT_REGEX = Regex("""(?im)\btexturefile\s*=\s*("[^"]+"|[^\s#]+)""")
    }
}
