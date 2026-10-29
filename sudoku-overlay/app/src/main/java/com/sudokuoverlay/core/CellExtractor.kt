package com.sudokuoverlay.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** An 8-bit greyscale picture, 255 = white. */
class Gray(val w: Int, val h: Int, val px: IntArray)

/** Turns each cell of the board into a clean "black digit on white" picture, or null when the cell is empty. */
object CellExtractor {
    private const val INK = 45

    fun extract(lum: IntArray, w: Int, h: Int, grid: Grid, row: Int, col: Int): Gray? {
        val cs = grid.cell
        val inset = max(2, (cs * 0.12).toInt())
        val x0 = max(0, grid.cellLeft(col).toInt() + inset)
        val y0 = max(0, grid.cellTop(row).toInt() + inset)
        val x1 = min(w, grid.cellLeft(col + 1).toInt() - inset)
        val y1 = min(h, grid.cellTop(row + 1).toInt() - inset)
        val rw = x1 - x0
        val rh = y1 - y0
        if (rw < 8 || rh < 8) return null

        val region = IntArray(rw * rh)
        for (y in 0 until rh) for (x in 0 until rw) region[y * rw + x] = lum[(y0 + y) * w + x0 + x]
        val bg = GridFinder.median(region)

        // Connected blobs of "ink"; the digit is the tallest blob that does not touch the region's edge
        // (edge-touching blobs are leftovers of grid lines). Tiny pencil-mark digits are shorter than that.
        val ink = BooleanArray(rw * rh) { abs(region[it] - bg) >= INK }
        val seen = BooleanArray(rw * rh)
        val stack = IntArray(rw * rh)
        var bestH = 0
        var bMinX = 0; var bMaxX = 0; var bMinY = 0; var bMaxY = 0
        for (start in ink.indices) {
            if (!ink[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start; seen[start] = true
            var minX = rw; var maxX = -1; var minY = rh; var maxY = -1; var count = 0
            while (sp > 0) {
                val p = stack[--sp]
                val px = p % rw; val py = p / rw
                count++
                if (px < minX) minX = px; if (px > maxX) maxX = px
                if (py < minY) minY = py; if (py > maxY) maxY = py
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = px + dx; val ny = py + dy
                    if (nx < 0 || ny < 0 || nx >= rw || ny >= rh) continue
                    val q = ny * rw + nx
                    if (ink[q] && !seen[q]) { seen[q] = true; stack[sp++] = q }
                }
            }
            val touches = minX == 0 || minY == 0 || maxX == rw - 1 || maxY == rh - 1
            val bh = maxY - minY + 1
            if (!touches && count >= 15 && bh > bestH) {
                bestH = bh; bMinX = minX; bMaxX = maxX; bMinY = minY; bMaxY = maxY
            }
        }
        if (bestH < 0.33 * cs) return null

        val cx0 = max(0, bMinX - 2); val cx1 = min(rw - 1, bMaxX + 2)
        val cy0 = max(0, bMinY - 2); val cy1 = min(rh - 1, bMaxY + 2)
        var maxDiff = 0
        for (y in cy0..cy1) for (x in cx0..cx1) maxDiff = max(maxDiff, abs(region[y * rw + x] - bg))
        maxDiff = max(maxDiff, 60)
        val cw = cx1 - cx0 + 1
        val ch = cy1 - cy0 + 1
        val out = IntArray(cw * ch)
        for (y in 0 until ch) for (x in 0 until cw) {
            val d = abs(region[(cy0 + y) * rw + cx0 + x] - bg)
            out[y * cw + x] = 255 - min(255, d * 255 / maxDiff)
        }
        return Gray(cw, ch, out)
    }

    /** A rectangle of the screenshot as dark-on-light, for reading text that is light-on-dark (or coloured). */
    fun normalize(lum: IntArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int, fullInk: Int = 90): Gray {
        val rw = x1 - x0
        val rh = y1 - y0
        val region = IntArray(rw * rh)
        for (y in 0 until rh) for (x in 0 until rw) region[y * rw + x] = lum[(y0 + y) * w + x0 + x]
        val bg = GridFinder.median(region)
        val out = IntArray(rw * rh) { 255 - min(255, abs(region[it] - bg) * 255 / fullInk) }
        return Gray(rw, rh, out)
    }
}

/** Reads the characters an OCR engine returns back into 1..9. */
object DigitMap {
    fun digitOf(c: Char): Int = when (c) {
        in '1'..'9' -> c - '0'
        'l', 'I', '|', 'i', '!' -> 1
        'Z', 'z' -> 2
        'S', 's' -> 5
        'G', 'b' -> 6
        'B' -> 8
        'g', 'q' -> 9
        else -> 0
    }
}

/**
 * Lays the digits of all cells out in a clean 9x9 table of equally sized glyphs on white, so an OCR engine sees
 * a tidy page of numbers instead of 50 isolated characters.
 */
object BoardCanvas {
    const val DIGIT_H = 56
    const val SLOT_W = 112
    const val SLOT_H = 120

    fun build(digits: Array<Gray?>): Gray {
        val w = 9 * SLOT_W
        val h = 9 * SLOT_H
        val px = IntArray(w * h) { 255 }
        for (i in 0 until 81) {
            val g = digits[i] ?: continue
            val s = scaleToHeight(g, DIGIT_H)
            val ox = (i % 9) * SLOT_W + (SLOT_W - s.w) / 2
            val oy = (i / 9) * SLOT_H + (SLOT_H - s.h) / 2
            for (y in 0 until s.h) for (x in 0 until s.w) px[(oy + y) * w + ox + x] = s.px[y * s.w + x]
        }
        return Gray(w, h, px)
    }

    /** One digit centred in a generous white margin. */
    fun single(g: Gray): Gray {
        val s = scaleToHeight(g, DIGIT_H)
        val m = 48
        val w = s.w + 2 * m
        val h = s.h + 2 * m
        val px = IntArray(w * h) { 255 }
        for (y in 0 until s.h) for (x in 0 until s.w) px[(m + y) * w + m + x] = s.px[y * s.w + x]
        return Gray(w, h, px)
    }

    fun scaleToHeight(g: Gray, targetH: Int): Gray {
        val tw = max(1, min(SLOT_W - 8, (g.w.toDouble() * targetH / g.h).roundToInt()))
        val out = IntArray(tw * targetH)
        val sx = g.w.toDouble() / tw
        val sy = g.h.toDouble() / targetH
        for (y in 0 until targetH) for (x in 0 until tw) {
            val fx = ((x + 0.5) * sx - 0.5).coerceIn(0.0, g.w - 1.0)
            val fy = ((y + 0.5) * sy - 0.5).coerceIn(0.0, g.h - 1.0)
            val ix = fx.toInt(); val iy = fy.toInt()
            val jx = min(g.w - 1, ix + 1); val jy = min(g.h - 1, iy + 1)
            val ax = fx - ix; val ay = fy - iy
            val v = g.px[iy * g.w + ix] * (1 - ax) * (1 - ay) + g.px[iy * g.w + jx] * ax * (1 - ay) +
                g.px[jy * g.w + ix] * (1 - ax) * ay + g.px[jy * g.w + jx] * ax * ay
            out[y * tw + x] = v.roundToInt()
        }
        return Gray(tw, targetH, out)
    }
}

/** Fallback for digits the OCR engine returns nothing for (it often drops a lone "1"). */
object DigitGuess {
    private const val W = 48

    private fun norm(g: Gray): IntArray {
        val s = BoardCanvas.scaleToHeight(g, BoardCanvas.DIGIT_H)
        val out = IntArray(W * BoardCanvas.DIGIT_H) { 255 }
        val ox = (W - minOf(W, s.w)) / 2
        for (y in 0 until s.h) for (x in 0 until minOf(W, s.w)) out[y * W + ox + x] = s.px[y * s.w + x]
        return out
    }

    /** [known] are glyphs already read from this board, with their digit. Returns 1..9 or null. */
    fun guess(g: Gray, known: List<Pair<Int, Gray>>): Int? {
        if (g.w.toDouble() / g.h < 0.5 && known.none { it.first == 1 && it.second.w.toDouble() / it.second.h >= 0.5 }) return 1
        if (known.isEmpty()) return null
        val a = norm(g)
        var best = Int.MAX_VALUE
        var digit: Int? = null
        for ((d, k) in known) {
            val b = norm(k)
            var sum = 0
            for (i in a.indices) { val diff = a[i] - b[i]; sum += diff * diff / 255 }
            if (sum < best) { best = sum; digit = d }
        }
        return digit
    }
}
