package com.sudokuoverlay

import android.graphics.Bitmap
import android.graphics.PointF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.sudokuoverlay.core.BoardCanvas
import com.sudokuoverlay.core.CellExtractor
import com.sudokuoverlay.core.DigitGuess
import com.sudokuoverlay.core.DigitMap
import com.sudokuoverlay.core.Gray
import com.sudokuoverlay.core.InkKind
import com.sudokuoverlay.core.PadGlyphs
import com.sudokuoverlay.core.Grid
import com.sudokuoverlay.core.GridFinder
import java.util.concurrent.TimeUnit

/** Reads a screenshot of a Sudoku app: the grid, the digits already on it and where the 1-9 buttons are. */
class BoardReader {
    /** [kind]: per cell 0 = empty, else InkKind.GIVEN / ENTRY / SELECTED. [board] holds every digit read, typed or not. */
    class Result(val grid: Grid?, val board: IntArray?, val pad: Array<PointF>?, val error: String?, val kind: IntArray? = null)

    private class Ch(val ch: Char, val x: Double, val y: Double, val h: Double)

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun close() = recognizer.close()

    fun read(bmp: Bitmap): Result {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val w = bmp.width
        val h = bmp.height
        val argb = IntArray(w * h)
        bmp.getPixels(argb, 0, w, 0, 0, w, h)
        val lum = IntArray(w * h) {
            val p = argb[it]
            (((p shr 16) and 255) * 299 + ((p shr 8) and 255) * 587 + (p and 255) * 114) / 1000
        }
        val grid = GridFinder.find(lum, w, h)
            ?: return Result(null, null, null, "Can't see a Sudoku grid. Is the board on screen?").also { DebugLog.i("read ${w}x$h: no grid found") }

        val crops = arrayOfNulls<Gray>(81)
        for (i in 0 until 81) crops[i] = CellExtractor.extract(lum, w, h, grid, i / 9, i % 9)
        val present = crops.count { it != null }
        if (present < 10) return Result(grid, null, null, "Only $present digits found. Is the board hidden or blank?")

        val s = BoardCanvas
        // 1. OCR the whole board once: a (possibly imperfect) label per cell.
        val label = IntArray(81)
        for (c in recognize(s.build(crops))) {
            val col = (c.x / s.SLOT_W).toInt()
            val row = (c.y / s.SLOT_H).toInt()
            if (col !in 0..8 || row !in 0..8) continue
            val i = row * 9 + col
            val d = DigitMap.digitOf(c.ch)
            if (d > 0 && crops[i] != null && label[i] == 0) label[i] = d
        }
        // 2. In one game every copy of a digit renders (almost) identically, so group look-alike glyphs and let
        //    each group vote. One misread or unread cell is outvoted by its identical neighbours.
        val cells = (0 until 81).filter { crops[it] != null }
        val group = DigitGuess.cluster(cells.map { crops[it]!! }, 300.0)
        val board = IntArray(81)
        // The 1-9 buttons use the board's own font: matching a group of digits against them needs no OCR at all.
        val pad = findPad(lum, w, h, grid)
        val glyphs = pad?.let { PadGlyphs.read(lum, w, h, grid, it[0].y.toDouble()) }
        val resolved = HashMap<Int, Int?>() // group -> digit (null = not known yet)
        val how = StringBuilder()
        for (root in group.toSet()) {
            val members = cells.filterIndexed { k, _ -> group[k] == root }
            val votes = IntArray(10)
            for (i in members) votes[label[i]]++
            votes[0] = 0
            val voted = (1..9).maxByOrNull { votes[it] }!!.takeIf { votes[it] > 0 }
            // distance to the closest button glyph is under ~900 for the right digit and over ~3500 for any other
            val byPad = glyphs?.let { PadGlyphs.nearest(crops[members[0]]!!, it) }?.takeIf { it.second < 1800 }?.first
            if (byPad != null && voted != null && byPad != voted) how.append(" r${members[0] / 9 + 1}c${members[0] % 9 + 1}:pad$byPad/ocr$voted")
            resolved[root] = byPad ?: voted
        }
        for (root in resolved.keys) { // nothing known yet: read one member alone, then "a thin glyph is a 1"
            if (resolved[root] != null) continue
            val members = cells.filterIndexed { k, _ -> group[k] == root }
            var d: Int? = null
            for (i in members) {
                d = recognize(s.single(crops[i]!!)).firstNotNullOfOrNull { DigitMap.digitOf(it.ch).takeIf { v -> v > 0 } }
                if (d != null) break
            }
            if (d == null) { val g = crops[members[0]]!!; if (g.w.toDouble() / g.h < 0.5) d = 1 }
            resolved[root] = d
        }
        // Exactly one group left and exactly one digit unused: it can only be that one.
        val open = resolved.filter { it.value == null }.keys
        val unusedDigits = (1..9).filter { d -> resolved.values.none { it == d } }
        if (open.size == 1 && unusedDigits.size == 1) resolved[open.first()] = unusedDigits[0]
        for ((root, d) in resolved) {
            val members = cells.filterIndexed { k, _ -> group[k] == root }
            if (d == null) {
                val i = members[0]
                return Result(grid, null, null, "Couldn't read the digit at row ${i / 9 + 1}, column ${i % 9 + 1}").also { DebugLog.i("read: unreadable digit r${i / 9 + 1}c${i % 9 + 1}") }
            }
            for (i in members) board[i] = d
        }
        if (how.isNotEmpty()) DebugLog.i("read: pad glyphs and OCR disagreed (pad wins):$how")
        // A digit can't look like two different things: if two groups got the same digit while exactly one digit
        // is unused (all nine are normally on the board), the smaller group is the one that was misread.
        val byDigit = HashMap<Int, MutableSet<Int>>() // digit -> group roots
        cells.forEachIndexed { k, i -> byDigit.getOrPut(board[i]) { HashSet() }.add(group[k]) }
        val unused = (1..9).filter { it !in byDigit }
        val dupes = byDigit.filter { it.value.size == 2 }
        if (unused.size == 1 && dupes.size == 1 && byDigit.values.sumOf { it.size } == 9) {
            val roots = dupes.values.first().toList()
            val weaker = roots.minByOrNull { r -> group.count { it == r } }!!
            cells.forEachIndexed { k, i -> if (group[k] == weaker) board[i] = unused[0] }
        }
        val kind = IntArray(81) { if (board[it] == 0) 0 else InkKind.classify(argb, w, h, grid, it / 9, it % 9) }
        DebugLog.i("read ${w}x$h in ${android.os.SystemClock.elapsedRealtime() - t0} ms: grid=(${grid.left.toInt()},${grid.top.toInt()},${grid.size.toInt()}) " +
            "digits=$present groups=${group.toSet().size}\n  ocr   =${DebugLog.rows(label)}\n  board =${DebugLog.rows(board)}\n  kind  =${DebugLog.rows(kind)} (1 clue,2 typed,3 selected)\n" +
            "  pad   =${pad?.joinToString(" ") { "${it.x.toInt()},${it.y.toInt()}" } ?: "NOT FOUND"}")
        return Result(grid, board, pad, null, kind)
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
        // The buttons normally line up with the nine columns of the grid; if the text positions agree, use the exact spacing.
        val colX = { d: Int -> grid.left + (d - 0.5) * grid.size / 9 }
        if (found.all { Math.abs(it.value.x - colX(it.key)) < 0.6 * grid.cell }) {
            return Array(9) { PointF(colX(it + 1).toFloat(), (y0 + y).toFloat()) }
        }
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
