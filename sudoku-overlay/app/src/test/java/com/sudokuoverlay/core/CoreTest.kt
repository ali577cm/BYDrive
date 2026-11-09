package com.sudokuoverlay.core

import java.io.File
import java.io.DataInputStream
import java.util.zip.GZIPInputStream
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
        DataInputStream(GZIPInputStream(javaClass.getResourceAsStream("/sample_lum.gz"))).use { i ->
            val w = i.readInt(); val h = i.readInt()
            val raw = ByteArray(w * h); i.readFully(raw)
            return Triple(IntArray(w * h) { raw[it].toInt() and 255 }, w, h)
        }
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

        val c = BoardCanvas.build(crops)
        val out = File("build/test-out"); out.mkdirs()
        File(out, "canvas.pgm").outputStream().use { o ->
            o.write("P5 ${c.w} ${c.h} 255\n".toByteArray()); o.write(ByteArray(c.px.size) { c.px[it].toByte() })
        }
    }

    @Test fun guessesDigitsFromOtherGlyphs() {
        val (lum, w, h) = loadLum()
        val grid = GridFinder.find(lum, w, h)!!
        val crops = Array(81) { CellExtractor.extract(lum, w, h, grid, it / 9, it % 9) }
        var wrong = 0; var total = 0
        for (i in 0 until 81) {
            val g = crops[i] ?: continue
            val known = (0 until 81).filter { it != i && crops[it] != null }.map { sample[it] to crops[it]!! }
            total++
            if (DigitGuess.guess(g, known) != sample[i]) wrong++
        }
        println("guess wrong=$wrong of $total")
        assertTrue(wrong <= 1)
    }

    @Test fun countsSolutions() {
        assertEquals(1, SudokuSolver.countSolutions(sample))
        val loose = sample.copyOf(); for (i in 0 until 30) loose[i] = 0
        assertEquals(2, SudokuSolver.countSolutions(loose))
    }

    @Test fun printDistances() {
        val (lum, w, h) = loadLum()
        val grid = GridFinder.find(lum, w, h)!!
        val idx = (0 until 81).filter { sample[it] != 0 }
        val norms = idx.map { DigitGuess.norm(CellExtractor.extract(lum, w, h, grid, it / 9, it % 9)!!) }
        var maxSame = 0.0; var minDiff = Double.MAX_VALUE
        for (i in idx.indices) for (j in i + 1 until idx.size) {
            val d = DigitGuess.distance(norms[i], norms[j])
            if (sample[idx[i]] == sample[idx[j]]) maxSame = maxOf(maxSame, d) else minDiff = minOf(minDiff, d)
        }
        println("DIST maxSame=$maxSame minDiff=$minDiff")
    }

    @Test fun lookAlikeGlyphsFormOneGroupPerDigit() {
        val (lum, w, h) = loadLum()
        val grid = GridFinder.find(lum, w, h)!!
        val idx = (0 until 81).filter { sample[it] != 0 }
        val g = DigitGuess.cluster(idx.map { CellExtractor.extract(lum, w, h, grid, it / 9, it % 9)!! }, 300.0)
        assertEquals(9, g.toSet().size)
        for (a in idx.indices) for (b in idx.indices) assertEquals(sample[idx[a]] == sample[idx[b]], g[a] == g[b])
    }

    private fun loadRgb(name: String): Triple<IntArray, Int, Int> {
        DataInputStream(GZIPInputStream(javaClass.getResourceAsStream(name))).use { i ->
            val w = i.readInt(); val h = i.readInt()
            val raw = ByteArray(w * h * 3); i.readFully(raw)
            return Triple(IntArray(w * h) { (0xFF shl 24) or ((raw[it * 3].toInt() and 255) shl 16) or ((raw[it * 3 + 1].toInt() and 255) shl 8) or (raw[it * 3 + 2].toInt() and 255) }, w, h)
        }
    }

    private fun lumOf(argb: IntArray) = IntArray(argb.size) { val p = argb[it]; (((p shr 16) and 255) * 299 + ((p shr 8) and 255) * 587 + (p and 255) * 114) / 1000 }

    @Test fun firstBoardHasOnlyClues() {
        val (rgb, w, h) = loadRgb("/rgb_board1.gz")
        val grid = GridFinder.find(lumOf(rgb), w, h)!!
        for (i in 0 until 81) if (sample[i] != 0) assertEquals("cell $i", InkKind.GIVEN, InkKind.classify(rgb, w, h, grid, i / 9, i % 9))
    }

    @Test fun typedDigitsAreNotClues() {
        val (rgb, w, h) = loadRgb("/rgb_board2.gz")
        val grid = GridFinder.find(lumOf(rgb), w, h)!!
        fun k(r: Int, c: Int) = InkKind.classify(rgb, w, h, grid, r - 1, c - 1)
        // blue digits typed by the earlier Fill
        assertEquals(InkKind.ENTRY, k(1, 9)); assertEquals(InkKind.ENTRY, k(2, 3))
        assertEquals(InkKind.ENTRY, k(2, 7)); assertEquals(InkKind.ENTRY, k(7, 8))
        // the game's own clues
        assertEquals(InkKind.GIVEN, k(1, 5)); assertEquals(InkKind.GIVEN, k(2, 2)); assertEquals(InkKind.GIVEN, k(4, 9))
        assertEquals(InkKind.GIVEN, k(8, 4)); assertEquals(InkKind.GIVEN, k(7, 7))
    }

    @Test fun findsGridWithTopEdgeCovered() {
        // my own bar covers the top border in this screenshot
        val (rgb, w, h) = loadRgb("/rgb_board3.gz")
        val lum = lumOf(rgb)
        val grid = GridFinder.find(lum, w, h)
        assertNotNull(grid)
        println("COVERED grid=${grid!!.left},${grid.top},${grid.size}")
        assertEquals(17.0, grid.left, 8.0); assertEquals(504.0, grid.top, 8.0); assertEquals(1044.0, grid.size, 12.0)
        assertTrue(GridFinder.verify(lum, w, h, grid))
        assertTrue(!GridFinder.verify(lum, w, h, Grid(grid.left, grid.top + 300, grid.size))) // a shifted board is not "there"
    }

    private fun padMatchReport(res: String, truth: IntArray, padY: Double): Pair<Int, Int> {
        val (rgb, w, h) = loadRgb(res)
        val lum = lumOf(rgb)
        val grid = GridFinder.find(lum, w, h)!!
        val pad = PadGlyphs.read(lum, w, h, grid, padY)
        println("PAD $res glyphs=" + pad.map { if (it == null) 0 else 1 })
        var ok = 0; var n = 0; var maxBest = 0.0; var minSecond = Double.MAX_VALUE
        for (i in 0 until 81) {
            if (truth[i] == 0) continue
            val g = CellExtractor.extract(lum, w, h, grid, i / 9, i % 9) ?: continue
            val m = PadGlyphs.nearest(g, pad) ?: continue
            n++; if (m.first == truth[i]) ok++
            maxBest = maxOf(maxBest, m.second); minSecond = minOf(minSecond, m.third)
            if (m.first != truth[i] || m.second > 0.4 * m.third) println("PAD  cell $i truth=${truth[i]} got=${m.first} d=${m.second.toInt()} second=${m.third.toInt()}")
        }
        println("PAD $res correct $ok of $n maxCorrectDist=${maxBest.toInt()} minRunnerUp=${minSecond.toInt()}")
        return ok to n
    }

    @Test fun padGlyphsLabelBoardDigits() {
        val b4 = ("700000000" + "000760000" + "009000620" + "000000000" + "800000050" +
            "090541030" + "040800273" + "982376400" + "007100006").map { it - '0' }.toIntArray()
        val (ok1, n1) = padMatchReport("/rgb_board1.gz", sample, 1962.0)
        val (ok4, n4) = padMatchReport("/rgb_board4.gz", b4, 1962.0)
        assertEquals(n1, ok1); assertEquals(n4, ok4)
    }
}
