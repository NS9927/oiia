package net.posdaca.oiia.gfx

import net.posdaca.oiia.core.preview.PreviewSnapshot

data class GfxSpriteEntry(
    val name: String,
    val textureFile: String?,
    val textureFile1: String?,
    val textureFile2: String?,
    val noOfFrames: Int?,
    val defaultFrame: Int?,
    val sourceLine: Int
) {
    val textureFiles: List<String>
        get() = listOfNotNull(textureFile, textureFile1, textureFile2)
}

data class GfxPreviewSnapshot(
    val filePath: String?,
    val sprites: List<GfxSpriteEntry>,
    /** Resolved texture path per sprite name; filled in asynchronously by [GfxPreviewService.resolve]. */
    val imagePaths: Map<String, String> = emptyMap(),
    val images: Map<String, java.awt.image.BufferedImage> = emptyMap()
) : PreviewSnapshot {
    override val isEmpty: Boolean
        get() = sprites.isEmpty()
}
