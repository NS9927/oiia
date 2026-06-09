package net.posdaca.oiia.core

import icu.windea.pls.images.ImageService
import java.awt.image.BufferedImage
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.imageio.ImageIO

internal object PreviewImageLoader {
    @Volatile
    private var readersInitialized = false

    fun load(pathOrUrl: String, cache: MutableMap<String, BufferedImage>): BufferedImage? {
        cache[pathOrUrl]?.let { return it }
        ensureImageReaders()
        return readImage(pathOrUrl)?.also { cache[pathOrUrl] = it }
    }

    private fun ensureImageReaders() {
        if (readersInitialized) return
        synchronized(this) {
            if (readersInitialized) return

            val thread = Thread.currentThread()
            val oldClassLoader = thread.contextClassLoader
            thread.contextClassLoader = ImageService::class.java.classLoader
            try {
                runCatching { ImageService.getInstance() }
                runCatching { ImageIO.scanForPlugins() }
            } finally {
                thread.contextClassLoader = oldClassLoader
                readersInitialized = true
            }
        }
    }

    private fun readImage(pathOrUrl: String): BufferedImage? {
        return try {
            val value = pathOrUrl.trim()
            when {
                value.startsWith("file:", ignoreCase = true) -> readLocalFileUrl(value)
                value.startsWith("http:", ignoreCase = true) || value.startsWith("https:", ignoreCase = true) -> {
                    ImageIO.read(URI(value).toURL())
                }
                else -> readPath(Paths.get(value))
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readLocalFileUrl(value: String): BufferedImage? {
        val uriPath = runCatching { Paths.get(URI(value)) }.getOrNull()
        if (uriPath != null) return readPath(uriPath)
        val decoded = URLDecoder.decode(value.removePrefix("file:").removePrefix("//"), StandardCharsets.UTF_8)
        return readPath(Paths.get(decoded))
    }

    private fun readPath(path: Path): BufferedImage? {
        return if (!Files.isRegularFile(path)) null else ImageIO.read(path.toFile())
    }
}
