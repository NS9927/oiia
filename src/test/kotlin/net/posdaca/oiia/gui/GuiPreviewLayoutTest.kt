package net.posdaca.oiia.gui

import com.intellij.openapi.project.Project
import net.posdaca.oiia.core.ParadoxSpriteResolver.SpriteInfo
import net.posdaca.oiia.core.ParadoxSpriteResolver.SpriteSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.image.BufferedImage

/**
 * Anchors the GUI layout engine that lives in [GuiPreviewService] (pure over the element tree and
 * resolved resources, no IDE platform needed on these paths).
 */
class GuiPreviewLayoutTest {

    private val service = newService()
    private val padding = 10
    private val minimumSize = Dimension(800, 520)

    @Test
    fun declaredSizeAndPositionPlaceChildInsideRoot() {
        val root = root(
            child("iconType", size = size(100, 50), position = point(10, 20))
        )

        val result = service.layoutRoot(root, emptyResources(), padding, minimumSize)

        val child = result.nodes.last()
        assertEquals(Rectangle(padding + 10, padding + 20, 100, 50), child.bounds)
    }

    @Test
    fun centerPositionShiftsBoundsByHalfSize() {
        val root = root(
            child("iconType", size = size(100, 50), position = point(100, 100), centerPosition = true)
        )

        val result = service.layoutRoot(root, emptyResources(), padding, minimumSize)

        val child = result.nodes.last()
        assertEquals(Rectangle(padding + 100 - 50, padding + 100 - 25, 100, 50), child.bounds)
    }

    @Test
    fun orientationUpperRightAlignsToParentRightEdge() {
        val root = root(
            child("iconType", size = size(100, 50), position = point(0, 0), orientation = "UPPER_RIGHT")
        )

        val result = service.layoutRoot(root, emptyResources(), padding, minimumSize)

        val child = result.nodes.last()
        // With the default upper-left origo the element's left edge sits at the parent's right edge.
        assertEquals(padding + root.declaredWidth, child.bounds.x)
    }

    @Test
    fun fullscreenChildFillsParent() {
        val root = root(
            child("iconType", fullscreen = true)
        )

        val result = service.layoutRoot(root, emptyResources(), padding, minimumSize)

        val child = result.nodes.last()
        val rootBounds = result.nodes.first().bounds
        assertEquals(rootBounds.width, child.bounds.width)
        assertEquals(rootBounds.height, child.bounds.height)
    }

    @Test
    fun spriteNativeSizeIsUsedWhenSizeIsNotDeclared() {
        val image = BufferedImage(40, 20, BufferedImage.TYPE_INT_ARGB)
        val info = SpriteInfo(name = "GFX_test", imagePath = "gfx/test.dds")
        val resources = GuiPreviewResources(
            "key",
            sprites = mapOf("GFX_test" to info),
            images = mapOf("gfx/test.dds" to image)
        )
        val root = root(
            child("iconType", sprite = "GFX_test", position = point(0, 0))
        )

        val result = service.layoutRoot(root, resources, padding, minimumSize)

        val child = result.nodes.last()
        assertEquals(40, child.bounds.width)
        assertEquals(20, child.bounds.height)
    }

    @Test
    fun missingSpriteAndLocalisationProduceIssues() {
        val root = root(
            child(
                "iconType",
                sprite = "GFX_missing",
                size = size(10, 10),
                text = "SOME_KEY"
            )
        )
        val resources = GuiPreviewResources(
            "key",
            sprites = mapOf("GFX_missing" to null),
            localisations = mapOf("SOME_KEY" to null)
        )

        val result = service.layoutRoot(root, resources, padding, minimumSize)

        val messages = result.nodes.last().issues.map { it.message }
        assertTrue(messages.any { it.startsWith("sprite not resolved") })
        assertTrue(messages.any { it.startsWith("localisation not resolved") })
    }

    private fun root(vararg children: GuiElement): GuiElement {
        return element("containerWindowType", name = "root", size = size(1920, 1080), children = children.toList())
    }

    private fun child(
        type: String,
        size: GuiSize? = null,
        position: GuiPoint = GuiPoint.ZERO,
        orientation: String? = null,
        centerPosition: Boolean = false,
        fullscreen: Boolean = false,
        sprite: String? = null,
        text: String? = null
    ): GuiElement {
        return element(
            type,
            size = size,
            position = position,
            orientation = orientation,
            centerPosition = centerPosition,
            fullscreen = fullscreen,
            sprite = sprite,
            text = text
        )
    }

    private fun element(
        type: String,
        name: String? = null,
        size: GuiSize? = null,
        position: GuiPoint = GuiPoint.ZERO,
        orientation: String? = null,
        centerPosition: Boolean = false,
        fullscreen: Boolean = false,
        sprite: String? = null,
        text: String? = null,
        children: List<GuiElement> = emptyList()
    ): GuiElement {
        return GuiElement(
            type = type,
            name = name,
            position = position,
            size = size,
            text = text,
            orientation = orientation,
            sprite = sprite,
            centerPosition = centerPosition,
            fullscreen = fullscreen,
            children = children
        )
    }

    private fun size(width: Int, height: Int): GuiSize = GuiSize(width, height)

    private fun point(x: Int, y: Int): GuiPoint = GuiPoint(GuiValue.pixels(x), GuiValue.pixels(y))

    private fun emptyResources(): GuiPreviewResources = GuiPreviewResources("key")

    private val GuiElement.declaredWidth: Int
        get() = size?.width ?: 0

    private fun newService(): GuiPreviewService {
        val proxy = java.lang.reflect.Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java)
        ) { _, _, _ -> null }
        @Suppress("UNCHECKED_CAST")
        return GuiPreviewService(Project::class.java.cast(proxy) as Project)
    }
}
