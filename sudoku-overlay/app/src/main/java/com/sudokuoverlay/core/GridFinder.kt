package com.sudokuoverlay.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The 9x9 board's square on the screenshot, in pixels. */
class Grid(val left: Double, val top: Double, val size: Double) {
    val cell: Double get() = size / 9.0
    fun cellLeft(col: Int) = left + col * cell
    fun cellTop(row: Int) = top + row * cell
    fun centerX(col: Int) = left + (col + 0.5) * cell
    fun centerY(row: Int) = top + (row + 0.5) * cell
    val bottom: Double get() = top + size
}

/**
 * Finds the board from its long horizontal lines: a square whose top and bottom edges (and, ideally, the inner
 * lines at multiples of 1/9) are long runs of pixels that differ from the screen's background. Works for light and
 * dark themes because it only cares about "different from the usual background", not about a colour.
 */
object GridFinder {
    private const val GAP = 2

    private class Band(val center: Double, val start: Int, val end: Int)

    fun find(lum: IntArray, w: Int, h: Int): Grid? {
        val bg = median(lum)
        // Strong lines first; faint thin lines (light themes) as a fallback.
        return find(lum, w, h, bg, 40) ?: find(lum, w, h, bg, 18)
    }

    private fun find(lum: IntArray, w: Int, h: Int, bg: Int, diff: Int): Grid? {
        val minRun = max(150, (0.3 * min(w, h)).toInt())
        val maxThick = (0.012 * max(w, h)).toInt() + 3
        val runLen = IntArray(h)
        val runStart = IntArray(h)
        for (y in 0 until h) {
            var best = 0; var bestStart = 0
            var start = -1; var last = -1
            val o = y * w
            for (x in 0 until w) {
                if (abs(lum[o + x] - bg) >= diff) {
                    if (start < 0) start = x
                    last = x
                } else if (start >= 0 && x - last > GAP) {
                    if (last - start + 1 > best) { best = last - start + 1; bestStart = start }
                    start = -1
                }
            }
            if (start >= 0 && last - start + 1 > best) { best = last - start + 1; bestStart = start }
            runLen[y] = best; runStart[y] = bestStart
        }

        val bands = ArrayList<Band>()
        var y = 0
        while (y < h) {
            if (runLen[y] < minRun) { y++; continue }
            var e = y
            while (e + 1 < h && runLen[e + 1] >= minRun) e++
            if (e - y + 1 <= maxThick) { // taller bands are tinted rows/cells, not lines
                var bi = y
                for (k in y..e) if (runLen[k] > runLen[bi]) bi = k
                bands.add(Band((y + e) / 2.0, runStart[bi], runStart[bi] + runLen[bi] - 1))
            }
            y = e + 1
        }

        var bestScore = 0
        var best: Grid? = null
        for (i in bands.indices) for (j in i + 1 until bands.size) {
            val a = bands[i]; val b = bands[j]
            val s = b.center - a.center
            if (s < minRun) continue
            val tol = 0.03 * s
            if (abs(a.start - b.start) > tol || abs(a.end - b.end) > tol) continue
            val width = ((a.end + b.end) - (a.start + b.start)) / 2.0 + 1
            if (abs(width - s) > 0.05 * s) continue // the board is square
            var score = 0
            for (k in 0..9) {
                val expected = a.center + k * s / 9.0
                if (bands.any { abs(it.center - expected) <= 0.12 * s / 9.0 && abs(it.start - a.start) <= tol && abs(it.end - a.end) <= tol }) score++
            }
            val left = (a.start + a.end + b.start + b.end) / 4.0 - s / 2.0
            if (score > bestScore || (score == bestScore && best != null && s > best.size)) {
                bestScore = score
                best = Grid(left, a.center, s)
            }
        }
        // 4 = outer border plus the two thick box separators; thin lines are a bonus.
        return if (bestScore >= 4) best else null
    }

    fun median(lum: IntArray): Int {
        val hist = IntArray(256)
        var n = 0
        var i = 0
        while (i < lum.size) { hist[lum[i].coerceIn(0, 255)]++; n++; i += 7 }
        var acc = 0
        for (v in 0..255) { acc += hist[v]; if (acc * 2 >= n) return v }
        return 128
    }
}
