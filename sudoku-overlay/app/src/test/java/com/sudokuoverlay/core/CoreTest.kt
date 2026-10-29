package com.sudokuoverlay.core

import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    // The board in sample_board.jpg (0 = empty).
    private val sample = ("800200069" + "900136085" + "230859170" + "003612497" + "020005816" +
        "009007523" + "740903650" + "090560708" + "605708900").map { it - '0' }.toIntArray()

    private fun loadLum(): Triple<IntArray, Int, Int> {
        val img = ImageIO.read(javaClass.getResourceAsStream("/sample_board.jpg"))
        val w = img.width; val h = img.height
        val lum = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val p = img.getRGB(x, y)
            lum[y * w + x] = (((p shr 16) and 255) * 299 + ((p shr 8) and 255) * 587 + (p and 255) * 114) / 1000
        }
        return Triple(lum, w, h)
    }

    @Test fun solverSolvesSample() {
        val s = SudokuSolver.solve(sample)
        assertNotNull(s)
        assertTrue(SudokuSolver.isConsistent(s!!))
        assertTrue(s.all { it in 1..9 })
        for (i in 0 until 81) if (sample[i] != 0) assertEquals(sample[i], s[i])
    }

    @Test fun solverRejectsConflicts() {
        val bad = sample.copyOf(); bad[1] = 8 // two 8s in row 1
        assertNull(SudokuSolver.solve(bad))
    }

    @Test fun solverHandlesHardPuzzle() {
        val hard = ("000000907" + "000420180" + "000705026" + "100904000" + "050000040" +
            "000507009" + "920108000" + "034059000" + "507000000").map { it - '0' }.toIntArray()
        val s = SudokuSolver.solve(hard)
        assertNotNull(s)
        assertTrue(SudokuSolver.isConsistent(s!!))
    }

    @Test fun findsGridAndEmptyCells() {
        val (lum, w, h) = loadLum()
        val grid = GridFinder.find(lum, w, h)
        assertNotNull(grid)
        println("grid left=${grid!!.left} top=${grid.top} size=${grid.size}")
        // ~ (18, 503) and ~1043 px wide on this 1080x2340 screenshot
        assertEquals(18.0, grid.left, 8.0)
        assertEquals(503.0, grid.top, 8.0)
        assertEquals(1043.0, grid.size, 12.0)

        val crops = Array(81) { CellExtractor.extract(lum, w, h, grid, it / 9, it % 9) }
        val found = IntArray(81) { if (crops[it] != null) 1 else 0 }
        val expected = IntArray(81) { if (sample[it] != 0) 1 else 0 }
        assertArrayEquals(expected, found)

        val out = File("build/test-out"); out.mkdirs()
        writePng(BoardCanvas.build(crops), File(out, "canvas.png"))
    }

    private fun writePng(g: Gray, f: File) {
        val img = java.awt.image.BufferedImage(g.w, g.h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (y in 0 until g.h) for (x in 0 until g.w) { val v = g.px[y * g.w + x]; img.setRGB(x, y, (v shl 16) or (v shl 8) or v) }
        ImageIO.write(img, "png", f)
    }
}
