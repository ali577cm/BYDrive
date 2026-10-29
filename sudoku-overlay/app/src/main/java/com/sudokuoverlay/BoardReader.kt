package com.sudokuoverlay

import android.graphics.Bitmap
import android.graphics.PointF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.sudokuoverlay.core.BoardCanvas
import com.sudokuoverlay.core.CellExtractor
import com.sudokuoverlay.core.DigitMap
import com.sudokuoverlay.core.Gray
import com.sudokuoverlay.core.Grid
import com.sudokuoverlay.core.GridFinder
import java.util.concurrent.TimeUnit

/** Reads a screenshot of a Sudoku app: the grid, the digits already on it and where the 1-9 buttons are. */
class BoardReader {
    class Result(val grid: Grid?, val board: IntArray?, val pad: Array<PointF>?, val error: String?)

    private class Ch(val ch: Char, val x: Double, val y: Double, val h: Double)

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun close() = recognizer.close()

    fun read(bmp: Bitmap): Result {
        val w = bmp.width
        val h = bmp.height
        val argb = IntArray(w * h)
        bmp.getPixels(argb, 0, w, 0, 0, w, h)
        val lum = IntArray(w * h) {
            val p = argb[it]
            (((p shr 16) and 255) * 299 + ((p shr 8) and 255) * 587 + (p and 255) * 114) / 1000
        }
        val grid = GridFinder.find(lum, w, h)
            ?: return Result(null, null, null, "Can't see a Sudoku grid. Is the board on screen?")

        val crops = arrayOfNulls<Gray>(81)
        for (i in 0 until 81) crops[i] = CellExtractor.extract(lum, w, h, grid, i / 9, i % 9)
        val present = crops.count { it != null }
        if (present < 10) return Result(grid, null, null, "Only $present digits found. Is the board hidden or blank?")

        val board = IntArray(81)
        val s = BoardCanvas
        for (c in recognize(s.build(crops))) {
            val col = (c.x / s.SLOT_W).toInt()
            val row = (c.y / s.SLOT_H).toInt()
            if (col !in 0..8 || row !in 0..8) continue
            val i = row * 9 + col
            val d = DigitMap.digitOf(c.ch)
            if (d > 0 && crops[i] != null && board[i] == 0) board[i] = d
        }
        for (i in 0 until 81) {
            if (crops[i] == null || board[i] != 0) continue
            val d = recognize(s.single(crops[i]!!)).firstNotNullOfOrNull { DigitMap.digitOf(it.ch).takeIf { v -> v > 0 } }
            if (d == null) return Result(grid, null, null, "Couldn't read the digit at row ${i / 9 + 1}, column ${i % 9 + 1}")
            board[i] = d
        }
        return Result(grid, board, findPad(lum, w, h, grid), null)
    }

    private fun recognize(g: Gray): List<Ch> {
        val bmp = Bitmap.createBitmap(g.w, g.h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(IntArray(g.px.size) { 0xFF000000.toInt() or (g.px[it] * 0x010101) }, 0, g.w, 0, 0, g.w, g.h)
        val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)), 30, TimeUnit.SECONDS)
        bmp.recycle()
        val out = ArrayList<Ch>()
        for (b in text.textBlocks) for (l in b.lines) for (e in l.elements) {
            val r = e.boundingBox ?: continue
            val t = e.text
            for ((k, ch) in t.withIndex()) {
                // spread the element's characters evenly over its box
                out.add(Ch(ch, r.left + (k + 0.5) * r.width() / t.length, r.exactCenterY().toDouble(), r.height().toDouble()))
            }
        }
        return out
    }

    /** Finds the big 1-9 buttons below the grid by reading the text there; fills gaps by equal spacing. */
    private fun findPad(lum: IntArray, w: Int, h: Int, grid: Grid): Array<PointF>? {
        val y0 = (grid.bottom + 4).toInt()
        if (h - y0 < 40) return null
        val strip = CellExtractor.normalize(lum, w, 0, y0, w, h)
        val cands = recognize(strip).filter { DigitMap.digitOf(it.ch) in 1..9 && it.ch in '1'..'9' }
        if (cands.isEmpty()) return null
        val maxH = cands.maxOf { it.h }
        val big = cands.filter { it.h >= 0.6 * maxH }
        val y = big.map { it.y }.sorted()[big.size / 2]
        val row = big.filter { Math.abs(it.y - y) < 0.6 * maxH }.sortedBy { it.x }
        val found = HashMap<Int, Ch>()
        for (c in row) found.putIfAbsent(c.ch - '0', c)
        if (found.size < 3) return null
        // least squares x = a + b*digit
        val n = found.size.toDouble()
        val sd = found.keys.sumOf { it.toDouble() }
        val sx = found.values.sumOf { it.x }
        val sdd = found.keys.sumOf { it.toDouble() * it }
        val sdx = found.entries.sumOf { it.key * it.value.x }
        val b = (n * sdx - sd * sx) / (n * sdd - sd * sd)
        val a = (sx - b * sd) / n
        if (b <= 0) return null
        return Array(9) { i ->
            val f = found[i + 1]
            PointF((f?.x ?: (a + b * (i + 1))).toFloat(), (y0 + y).toFloat())
        }
    }
}
