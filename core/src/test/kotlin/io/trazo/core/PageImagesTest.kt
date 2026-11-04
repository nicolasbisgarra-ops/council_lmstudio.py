package io.trazo.core

import io.trazo.core.fixtures.SyntheticPen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImageProcessingTest {
    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun red(p: Int) = (p shr 16) and 0xFF
    private fun blue(p: Int) = p and 0xFF

    @Test fun `full quad warp keeps the image`() {
        val w = 20; val h = 10
        val src = IntArray(w * h) { argb(it % w * 10, it / w * 20, 7) }
        val out = ImageProcessing.warp(src, w, h, floatArrayOf(0f, 0f, 20f, 0f, 20f, 10f, 0f, 10f), w, h)
        for (i in src.indices) assertEquals(red(src[i]).toFloat(), red(out[i]).toFloat(), 1f)
    }

    @Test fun `crop to the right half keeps only that half`() {
        val w = 40; val h = 20
        val src = IntArray(w * h) { if (it % w < 20) argb(255, 0, 0) else argb(0, 0, 255) }
        val out = ImageProcessing.warp(src, w, h, floatArrayOf(21f, 0f, 40f, 0f, 40f, 20f, 21f, 20f), 19, 20)
        assertTrue(out.all { blue(it) > 240 && red(it) < 15 })
    }

    @Test fun `homography sends the unit square corners to the quad corners`() {
        val q = floatArrayOf(10f, 5f, 90f, 20f, 80f, 70f, 5f, 60f)
        val m = ImageProcessing.squareToQuad(q)
        fun map(u: Double, v: Double): Pair<Double, Double> {
            val d = m[6] * u + m[7] * v + 1
            return (m[0] * u + m[1] * v + m[2]) / d to (m[3] * u + m[4] * v + m[5]) / d
        }
        listOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0, 0.0 to 1.0).forEachIndexed { i, (u, v) ->
            val (x, y) = map(u, v)
            assertEquals(q[2 * i].toDouble(), x, 1e-3)
            assertEquals(q[2 * i + 1].toDouble(), y, 1e-3)
        }
    }

    @Test fun `straightening a tilted sheet makes a rectangle of the right size`() {
        val (w, h) = ImageProcessing.outputSize(floatArrayOf(0f, 0f, 300f, 0f, 300f, 400f, 0f, 400f))
        assertEquals(300, w); assertEquals(400, h)
        val (bw, bh) = ImageProcessing.outputSize(floatArrayOf(0f, 0f, 6000f, 0f, 6000f, 3000f, 0f, 3000f))
        assertEquals(PageImages.MAX_SIDE, bw); assertEquals(PageImages.MAX_SIDE / 2, bh)
    }

    /** Hoja con sombra (de 210 a la izquierda a 110 a la derecha) y renglones de texto oscuro. */
    private fun shadowedPage(w: Int, h: Int): Pair<IntArray, BooleanArray> {
        val text = BooleanArray(w * h) { i -> val x = i % w; val y = i / w; y % 20 in 8..10 && x % 9 < 6 && x in 10 until w - 10 }
        val px = IntArray(w * h) { i ->
            val paper = 210 - 100 * (i % w) / w
            val v = if (text[i]) paper * 30 / 100 else paper
            argb(v, v, v)
        }
        return px to text
    }

    @Test fun `document preset removes the shadow white paper and black text`() {
        val w = 200; val h = 120
        val (px, text) = shadowedPage(w, h)
        val g = ImageProcessing.document(px, w, h)
        val paper = g.indices.filter { !text[it] && (it / w) % 20 !in 6..12 }
        val ink = g.indices.filter { text[it] && (it % w) % 9 in 2..3 }
        assertTrue("papel blanco en todo el ancho", paper.all { g[it] >= 235 })
        assertTrue("texto negro también en la sombra", ink.all { g[it] <= 60 })
        val out = ImageProcessing.apply(px, w, h, ImagePreset.DOCUMENT)
        assertTrue(out.all { (it ushr 24) == 0xFF && red(it) == blue(it) })
    }

    @Test fun `dark preset is a light ink mask over a transparent background`() {
        val w = 200; val h = 120
        val (px, text) = shadowedPage(w, h)
        val out = ImageProcessing.apply(px, w, h, ImagePreset.DARK)
        assertTrue(out.all { it and 0xFFFFFF == 0xFFFFFF })
        val paper = out.indices.filter { !text[it] && (it / w) % 20 !in 6..12 }
        val ink = out.indices.filter { text[it] && (it % w) % 9 in 2..3 }
        assertTrue(paper.all { (it.let { i -> out[i] } ushr 24) <= 20 })
        assertTrue(ink.all { (out[it] ushr 24) >= 195 })
    }

    @Test fun `none preset copies`() {
        val px = intArrayOf(argb(1, 2, 3), argb(4, 5, 6))
        val out = ImageProcessing.apply(px, 2, 1, ImagePreset.NONE)
        assertTrue(px.contentEquals(out)); assertFalse(px === out)
    }

    @Test fun `quads must be convex and inside the image`() {
        assertTrue(PageImages.isValidQuad(PageImages.FULL_QUAD))
        assertTrue(PageImages.isValidQuad(listOf(0.1f, 0.05f, 0.9f, 0.1f, 0.95f, 0.9f, 0.05f, 0.95f)))
        assertFalse("cruzado", PageImages.isValidQuad(listOf(0f, 0f, 1f, 1f, 1f, 0f, 0f, 1f)))
        assertFalse(PageImages.isValidQuad(listOf(0f, 0f, 2f, 0f, 1f, 1f, 0f, 1f)))
        assertFalse(PageImages.isValidQuad(null))
    }

    @Test fun `placement keeps the aspect and stays in the page`() {
        val b = PageImages.placement(400, 200, 990f, 10f)
        assertEquals(2f, b.width / b.height, 0.01f)
        assertEquals(Notebook.PAGE_WIDTH * PageImages.DEFAULT_WIDTH_FRACTION, b.width, 0.5f)
        assertTrue(b.left >= 0f && b.top >= 0f && b.right <= Notebook.PAGE_WIDTH && b.bottom <= Notebook.PAGE_HEIGHT)
        val tall = PageImages.placement(100, 1000, 500f, 700f)
        assertTrue(tall.height <= Notebook.PAGE_HEIGHT * 0.8f + 0.5f)
    }
}

class PageImagesEditingTest {
    private lateinit var controller: InkController
    private lateinit var pen: SyntheticPen
    private val name = "a".repeat(64) + ".webp"

    @Before fun setUp() {
        controller = InkController(patterns = { DefaultPatterns.all() }, ids = IdGenerator.sequential())
        pen = SyntheticPen(controller)
    }

    private fun image(id: String = "img", l: Float = 100f, t: Float = 100f) = PageImage(id, name, l, t, l + 300f, t + 200f, ImagePreset.DOCUMENT)

    @Test fun `insert is undoable and selects the image`() {
        controller.insertImage(image())
        assertEquals(listOf("img"), controller.selection)
        assertEquals("img", controller.selectedImage?.id)
        assertEquals(Bounds(100f, 100f, 400f, 300f), controller.selectionBounds)
        assertTrue(controller.undo())
        assertTrue(controller.page.images.isEmpty())
        assertTrue(controller.redo())
        assertEquals(1, controller.page.images.size)
    }

    @Test fun `lasso selects image and ink together and they move as one step`() {
        controller.insertImage(image())
        controller.clearSelection()
        pen.stroke(listOf(150f to 150f, 250f to 180f))
        controller.tool = Tool.LASSO
        pen.stroke(listOf(50f to 50f, 450f to 50f, 450f to 350f, 50f to 350f, 50f to 50f))
        assertEquals(2, controller.selection.size)
        val before = controller.selectionBounds!!
        pen.stroke(listOf(before.centerX to before.centerY, before.centerX + 100f to before.centerY + 50f))
        val img = controller.page.images.single()
        assertEquals(200f, img.left, 1f); assertEquals(150f, img.top, 1f)
        assertEquals(300f, img.bounds.width, 0.01f)
        controller.undo()
        assertEquals(image(), controller.page.images.single())
    }

    @Test fun `scaling the selection scales the image with the same aspect`() {
        controller.insertImage(image())
        controller.scaleSelection(2f)
        val b = controller.page.images.single().bounds
        assertEquals(600f, b.width, 0.01f); assertEquals(400f, b.height, 0.01f)
        assertEquals(250f, b.centerX, 0.01f)
    }

    @Test fun `tapping an image with the lasso selects it`() {
        controller.insertImage(image())
        controller.clearSelection()
        controller.tool = Tool.LASSO
        pen.stroke(listOf(200f to 200f))
        assertEquals(listOf("img"), controller.selection)
    }

    @Test fun `delete duplicate and clear page include images`() {
        controller.insertImage(image())
        val copies = controller.duplicateSelection()
        assertEquals(2, controller.page.images.size)
        assertEquals(124f, controller.page.findImage(copies.single())!!.left, 0.01f)
        controller.deleteSelection()
        assertEquals(listOf("img"), controller.page.images.map { it.id })
        controller.clearPage()
        assertTrue(controller.page.images.isEmpty())
        controller.undo()
        assertEquals(1, controller.page.images.size)
    }

    @Test fun `replace image keeps the place in one undoable step`() {
        controller.insertImage(image())
        val other = "b".repeat(64) + ".webp"
        controller.replaceImage(image().copy(asset = other, preset = ImagePreset.DARK))
        assertEquals(other, controller.page.images.single().asset)
        controller.undo()
        assertEquals(name, controller.page.images.single().asset)
    }

    @Test fun `eraser never removes an image`() {
        controller.insertImage(image())
        controller.tool = Tool.STROKE_ERASER
        pen.stroke(listOf(120f to 120f, 380f to 280f))
        assertEquals(1, controller.page.images.size)
    }

    @Test fun `page state keeps images in the saved page`() {
        controller.insertImage(image())
        val page = controller.page.toPage("p")
        assertEquals(listOf(image()), page.images)
        assertNull(page.ai)
    }
}

class PageImagesStorageTest {
    private val a = "a".repeat(64) + ".webp"
    private val b = "b".repeat(64) + ".png"
    private val webp = "RIFF".toByteArray() + byteArrayOf(9, 0, 0, 0) + "WEBPVP8 x".toByteArray()
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2)

    private fun notebook() = Notebook(
        "n", "Fotos",
        listOf(Page("p1", images = listOf(PageImage("i1", a, 0f, 0f, 10f, 10f, ImagePreset.DOCUMENT, source = b, quad = PageImages.FULL_QUAD)))),
    )

    @Test fun `images survive a json round trip and old files read without them`() {
        val n = notebook()
        assertEquals(n, TrazoFormat.decodeNotebook(TrazoFormat.encodeNotebook(n)))
        val lazy = TrazoFormat.decodeLazy(TrazoFormat.encodeNotebook(n))
        assertEquals(n.pages, lazy.toNotebook().pages)
        val old = TrazoFormat.decodePage("""{"id":"x","strokes":[]}""")
        assertTrue(old.images.isEmpty())
    }

    @Test fun `duplicating a page copies its images with new ids`() {
        val n = notebook().withPageDuplicated(0, IdGenerator.sequential("d"))
        assertEquals(2, n.pages.size)
        assertEquals(a, n.pages[1].images.single().asset)
        assertTrue(n.pages[1].images.single().id != "i1")
        val lazy = LazyNotebook.of(notebook()).withPageDuplicated(0, IdGenerator.sequential("d"))
        assertEquals(a, lazy.slots[1].page().images.single().asset)
    }

    @Test fun `archive carries the image files and drops images without them`() {
        val out = ByteArrayOutputStream()
        TrazoFormat.exportArchive(notebook(), emptyList(), out, images = mapOf(a to webp, b to png))
        val back = TrazoFormat.importArchive(ByteArrayInputStream(out.toByteArray()))
        assertEquals(notebook().pages, back.notebook.pages)
        assertTrue(back.images.getValue(a).contentEquals(webp))
        assertTrue(back.images.getValue(b).contentEquals(png))

        val noSource = ByteArrayOutputStream()
        TrazoFormat.exportArchive(notebook(), emptyList(), noSource, images = mapOf(a to webp))
        val img = TrazoFormat.importArchive(ByteArrayInputStream(noSource.toByteArray())).notebook.pages[0].images.single()
        assertNull(img.source); assertNull(img.quad)

        val none = ByteArrayOutputStream()
        TrazoFormat.exportArchive(notebook(), emptyList(), none)
        assertTrue(TrazoFormat.importArchive(ByteArrayInputStream(none.toByteArray())).notebook.pages[0].images.isEmpty())
    }

    @Test fun `an image whose bytes do not match its name is dropped on import`() {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { z ->
            z.putNextEntry(ZipEntry("notebook.json")); z.write(TrazoFormat.encodeNotebook(notebook()).toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("image-$a")); z.write(png); z.closeEntry()
            z.putNextEntry(ZipEntry("image-$b")); z.write(ByteArray(PageImages.MAX_BYTES + 10)); z.closeEntry()
        }
        val back = TrazoFormat.importArchive(ByteArrayInputStream(zip.toByteArray()))
        assertTrue(back.notebook.pages[0].images.isEmpty())
        assertTrue(back.images.isEmpty())
    }

    @Test fun `image assets lists the files the notebook uses`() {
        assertEquals(setOf(a, b), notebook().imageAssets())
    }
}
