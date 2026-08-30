package net.posdaca.oiia.gfx

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import icu.windea.pls.script.psi.ParadoxScriptFile
import icu.windea.pls.script.psi.ParadoxScriptProperty
import net.posdaca.oiia.core.ParadoxGfxParser
import net.posdaca.oiia.core.ParadoxSpriteResolver
import net.posdaca.oiia.core.PreviewImageLoader
import net.posdaca.oiia.core.files.ResourceFiles
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/** Parses the open `.gfx` file and resolves its sprite textures for the grid preview. */
class GfxPreviewService(private val project: Project) {

    private val resolutionVersion = AtomicInteger()
    private val spriteResolver = ParadoxSpriteResolver(project)

    /** Must run inside a read action. */
    fun loadSnapshot(psiFile: PsiFile): GfxPreviewSnapshot {
        val entries = mutableListOf<GfxSpriteEntry>()
        if (psiFile is ParadoxScriptFile) {
            val rootHint = psiFile.virtualFile?.path?.let { fileRootHint(it) }
            val rootBlock = psiFile.block
            if (rootBlock != null) {
                collectSpriteEntries(rootBlock.propertyList, rootHint, entries)
            }
        }
        return GfxPreviewSnapshot(psiFile.virtualFile?.path, entries)
    }

    private fun collectSpriteEntries(
        properties: List<ParadoxScriptProperty>,
        rootHint: Path?,
        out: MutableList<GfxSpriteEntry>
    ) {
        for (prop in properties) {
            val inner = prop.block ?: continue
            if (prop.propertyKey.text.lowercase() in ParadoxGfxParser.SPRITE_TYPE_KEYS) {
                val definition = spriteResolver.spriteDefinition(prop, rootHint) ?: continue
                out += GfxSpriteEntry(
                    name = definition.name,
                    textureFile = definition.textureFile,
                    textureFile1 = definition.textureFile1,
                    textureFile2 = definition.textureFile2,
                    noOfFrames = definition.noOfFrames,
                    defaultFrame = definition.defaultFrame,
                    sourceLine = sourceLine(prop)
                )
            } else {
                collectSpriteEntries(inner.propertyList, rootHint, out)
            }
        }
    }

    private fun sourceLine(prop: ParadoxScriptProperty): Int {
        val vf = prop.containingFile.virtualFile ?: return 0
        val doc = PsiManager.getInstance(project).findViewProvider(vf)?.document ?: return 0
        return doc.getLineNumber(prop.textOffset) + 1
    }

    /** The content root containing the .gfx file; relative textureFile paths resolve against it. */
    private fun fileRootHint(filePath: String): Path? {
        val normalizedFile = ResourceFiles.normalizedKey(filePath)
        return ResourceFiles.resourceRoots(project, projectFirst = true, gameFirst = false)
            .firstOrNull { root -> normalizedFile.startsWith(ResourceFiles.normalizedKey(root)) }
    }

    /** Resolves every sprite's texture path and decodes its image off the EDT. */
    fun resolve(snapshot: GfxPreviewSnapshot, onReady: (GfxPreviewSnapshot) -> Unit) {
        if (snapshot.sprites.isEmpty()) {
            onReady(snapshot)
            return
        }
        val version = resolutionVersion.incrementAndGet()
        ApplicationManager.getApplication().executeOnPooledThread {
            var next = snapshot
            try {
                val candidates = snapshot.sprites.associate { it.name to listOf(it.name) }
                val paths = spriteResolver.resolveForCandidates(candidates)
                val images = mutableMapOf<String, java.awt.image.BufferedImage>()
                val pathCache = mutableMapOf<String, java.awt.image.BufferedImage>()
                var decodedBytes = 0L
                for (entry in snapshot.sprites) {
                    val path = paths[entry.name] ?: continue
                    val image = PreviewImageLoader.load(path, pathCache) ?: continue
                    // A .gfx file can reference hundreds of large textures; stop decoding before
                    // the decoded images alone can exhaust the IDE heap.
                    val bytes = image.width.toLong() * image.height * 4L
                    if (decodedBytes + bytes > MAX_DECODED_BYTES) {
                        LOG.info("GFX preview stopped decoding at image budget: name=${entry.name} decoded=$decodedBytes")
                        break
                    }
                    decodedBytes += bytes
                    images[entry.name] = image
                }
                next = snapshot.copy(imagePaths = paths, images = images)
            } catch (e: Exception) {
                LOG.warn("GFX preview resolution failed", e)
            }
            if (version != resolutionVersion.get()) return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater { onReady(next) }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(GfxPreviewService::class.java)
        private const val MAX_DECODED_BYTES = 192L * 1024 * 1024
    }
}
