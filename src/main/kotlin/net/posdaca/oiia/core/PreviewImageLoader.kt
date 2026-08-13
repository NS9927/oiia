package net.posdaca.oiia.core

import net.posdaca.oiia.core.files.ResourceFiles

import icu.windea.pls.images.ImageService
import java.awt.image.BufferedImage
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.imageio.ImageIO
import kotlin.math.max

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
        if (!ResourceFiles.isRegularFile(path)) return null
        return readUncompressedDds(path) ?: ImageIO.read(path.toFile())?.toArgbImage()
    }

    private fun readUncompressedDds(path: Path): BufferedImage? {
        if (!path.fileName.toString().endsWith(".dds", ignoreCase = true)) return null
        val bytes = ResourceFiles.readBytes(path) ?: return null
        if (bytes.size < DDS_HEADER_SIZE || bytes.decodeAscii(0, 4) != "DDS ") return null

        val height = bytes.leInt(12)
        val width = bytes.leInt(16)
        val pixelFormatFlags = bytes.leInt(80)
        val fourCc = bytes.decodeAscii(84, 4)
        val rgbBits = bytes.leInt(88)
        if (width <= 0 || height <= 0 || rgbBits != 32 || fourCc.any { it.code != 0 }) return null
        if (pixelFormatFlags and DDPF_RGB == 0) return null

        val redMask = bytes.leUInt(92)
        val greenMask = bytes.leUInt(96)
        val blueMask = bytes.leUInt(100)
        val alphaMask = bytes.leUInt(104)
        val bytesPerPixel = rgbBits / 8
        val pixelBytes = width * height * bytesPerPixel
        if (bytes.size < DDS_HEADER_SIZE + pixelBytes) return null

        val binaryAlpha = alphaMask != 0L && isBinaryAlpha(bytes, alphaMask, width, height, bytesPerPixel)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        var offset = DDS_HEADER_SIZE
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bytes.leUInt(offset)
                val r = extractMaskedChannel(pixel, redMask)
                val g = extractMaskedChannel(pixel, greenMask)
                val b = extractMaskedChannel(pixel, blueMask)
                val a = when {
                    alphaMask == 0L -> 255
                    binaryAlpha -> if (extractMaskedRaw(pixel, alphaMask) == 0L) 0 else 255
                    else -> extractMaskedChannel(pixel, alphaMask)
                }
                image.setRGB(x, y, (a shl 24) or (r shl 16) or (g shl 8) or b)
                offset += bytesPerPixel
            }
        }
        return image
    }

    private fun BufferedImage.toArgbImage(): BufferedImage {
        if (type == BufferedImage.TYPE_INT_ARGB && colorModel.hasAlpha()) return this
        val output = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = output.createGraphics()
        try {
            g.drawImage(this, 0, 0, null)
        } finally {
            g.dispose()
        }
        return output
    }

    private fun extractMaskedChannel(pixel: Long, mask: Long): Int {
        if (mask == 0L) return 0
        val maxValue = normalizedMaskMax(mask)
        val value = extractMaskedRaw(pixel, mask)
        return ((value * 255L + max(1L, maxValue / 2L)) / max(1L, maxValue)).toInt().coerceIn(0, 255)
    }

    private fun isBinaryAlpha(bytes: ByteArray, alphaMask: Long, width: Int, height: Int, bytesPerPixel: Int): Boolean {
        var maxAlpha = 0L
        var offset = DDS_HEADER_SIZE
        val end = DDS_HEADER_SIZE + width * height * bytesPerPixel
        while (offset < end) {
            val alpha = extractMaskedRaw(bytes.leUInt(offset), alphaMask)
            if (alpha > maxAlpha) maxAlpha = alpha
            if (maxAlpha > 1L) return false
            offset += bytesPerPixel
        }
        return true
    }

    private fun extractMaskedRaw(pixel: Long, mask: Long): Long {
        if (mask == 0L) return 0
        val shift = java.lang.Long.numberOfTrailingZeros(mask)
        return (pixel and mask) ushr shift
    }

    private fun normalizedMaskMax(mask: Long): Long {
        val shift = java.lang.Long.numberOfTrailingZeros(mask)
        return mask ushr shift
    }

    private fun ByteArray.leInt(offset: Int): Int {
        return (this[offset].toInt() and 0xFF) or
                ((this[offset + 1].toInt() and 0xFF) shl 8) or
                ((this[offset + 2].toInt() and 0xFF) shl 16) or
                ((this[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun ByteArray.leUInt(offset: Int): Long {
        return leInt(offset).toLong() and 0xFFFF_FFFFL
    }

    private fun ByteArray.decodeAscii(offset: Int, length: Int): String {
        return String(this, offset, length, StandardCharsets.US_ASCII)
    }

    private const val DDS_HEADER_SIZE = 128
    private const val DDPF_RGB = 0x40
}
