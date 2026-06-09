package net.posdaca.oiia.core

import icu.windea.pls.images.ImageService
import java.awt.image.BufferedImage
import java.net.URI
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
            if (value.startsWith("file:", ignoreCase = true) ||
                value.startsWith("http:", ignoreCase = true) ||
                value.startsWith("https:", ignoreCase = true)
            ) {
                ImageIO.read(URI(value).toURL())
            } else {
                val file = Paths.get(value).toFile()
                if (!file.exists()) null else ImageIO.read(file)
            }
        } catch (_: Exception) {
            null
        }
    }
}
