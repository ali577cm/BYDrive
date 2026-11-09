package com.sudokuoverlay.core

/** Backtracking solver using bitmasks and "fewest candidates first". Boards are 81 ints, 0 = empty. */
object SudokuSolver {
    private const val ALL = 0b1111111110

    /** Returns the solved board, or null if the givens contradict each other or there is no solution. */
    fun solve(board: IntArray): IntArray? {
        require(board.size == 81)
        val g = board.copyOf()
        val rows = IntArray(9)
        val cols = IntArray(9)
        val boxes = IntArray(9)
        for (i in 0 until 81) {
            val v = g[i]
            if (v == 0) continue
            if (v !in 1..9) return null
            val bit = 1 shl v
            val r = i / 9
            val c = i % 9
            val b = r / 3 * 3 + c / 3
            if (((rows[r] or cols[c] or boxes[b]) and bit) != 0) return null
            rows[r] = rows[r] or bit
            cols[c] = cols[c] or bit
            boxes[b] = boxes[b] or bit
        }
        return if (search(g, rows, cols, boxes)) g else null
    }

    /** Number of solutions, stopping at [limit]. Real puzzles have exactly one; more than one means a misread digit. */
    fun countSolutions(board: IntArray, limit: Int = 2): Int {
        require(board.size == 81)
        val g = board.copyOf()
        val rows = IntArray(9); val cols = IntArray(9); val boxes = IntArray(9)
        for (i in 0 until 81) {
            val v = g[i]; if (v == 0) continue
            val bit = 1 shl v; val r = i / 9; val c = i % 9; val b = r / 3 * 3 + c / 3
            if (((rows[r] or cols[c] or boxes[b]) and bit) != 0) return 0
            rows[r] = rows[r] or bit; cols[c] = cols[c] or bit; boxes[b] = boxes[b] or bit
        }
        return count(g, rows, cols, boxes, limit)
    }

    private fun count(g: IntArray, rows: IntArray, cols: IntArray, boxes: IntArray, limit: Int): Int {
        var best = -1; var bestMask = 0; var bestCount = 10
        for (i in 0 until 81) {
            if (g[i] != 0) continue
            val avail = ALL and (rows[i / 9] or cols[i % 9] or boxes[i / 27 * 3 + i % 9 / 3]).inv()
            val n = Integer.bitCount(avail)
            if (n == 0) return 0
            if (n < bestCount) { best = i; bestMask = avail; bestCount = n; if (n == 1) break }
        }
        if (best == -1) return 1
        val r = best / 9; val c = best % 9; val b = r / 3 * 3 + c / 3
        var total = 0
        var m = bestMask
        while (m != 0 && total < limit) {
            val bit = m and -m; m = m xor bit
            g[best] = Integer.numberOfTrailingZeros(bit)
            rows[r] = rows[r] or bit; cols[c] = cols[c] or bit; boxes[b] = boxes[b] or bit
            total += count(g, rows, cols, boxes, limit - total)
            rows[r] = rows[r] xor bit; cols[c] = cols[c] xor bit; boxes[b] = boxes[b] xor bit
        }
        g[best] = 0
        return total
    }

    private fun search(g: IntArray, rows: IntArray, cols: IntArray, boxes: IntArray): Boolean {
        var best = -1
        var bestMask = 0
        var bestCount = 10
        for (i in 0 until 81) {
            if (g[i] != 0) continue
            val r = i / 9
            val c = i % 9
            val avail = ALL and (rows[r] or cols[c] or boxes[r / 3 * 3 + c / 3]).inv()
            val n = Integer.bitCount(avail)
            if (n == 0) return false
            if (n < bestCount) {
                best = i; bestMask = avail; bestCount = n
                if (n == 1) break
            }
        }
        if (best == -1) return true
        val r = best / 9
        val c = best % 9
        val b = r / 3 * 3 + c / 3
        var m = bestMask
        while (m != 0) {
            val bit = m and -m
            m = m xor bit
            g[best] = Integer.numberOfTrailingZeros(bit)
            rows[r] = rows[r] or bit; cols[c] = cols[c] or bit; boxes[b] = boxes[b] or bit
            if (search(g, rows, cols, boxes)) return true
            rows[r] = rows[r] xor bit; cols[c] = cols[c] xor bit; boxes[b] = boxes[b] xor bit
        }
        g[best] = 0
        return false
    }

    /** True if no digit repeats in a row, column or box. */
    fun isConsistent(board: IntArray): Boolean {
        for (unit in 0 until 9) {
            var row = 0; var col = 0; var box = 0
            for (k in 0 until 9) {
                for ((idx, which) in listOf(unit * 9 + k to 0, k * 9 + unit to 1,
                    (unit / 3 * 3 + k / 3) * 9 + unit % 3 * 3 + k % 3 to 2)) {
                    val v = board[idx]
                    if (v == 0) continue
                    val bit = 1 shl v
                    when (which) {
                        0 -> { if (row and bit != 0) return false; row = row or bit }
                        1 -> { if (col and bit != 0) return false; col = col or bit }
                        else -> { if (box and bit != 0) return false; box = box or bit }
                    }
                }
            }
        }
        return true
    }
}
