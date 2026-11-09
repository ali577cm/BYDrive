package com.sudokuoverlay

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A small on-phone log plus the two latest screenshots, so a bad run can be replayed and fixed later. */
object DebugLog {
    private var dir: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized fun init(ctx: Context) {
        if (dir != null) return
        dir = (ctx.getExternalFilesDir("log") ?: File(ctx.filesDir, "log")).also { it.mkdirs() }
    }

    @Synchronized fun i(msg: String) {
        val d = dir ?: return
        try {
            val f = File(d, "log.txt")
            if (f.length() > 300_000) f.writeText("")
            f.appendText("${fmt.format(Date())} $msg\n")
        } catch (_: Exception) {}
    }

    /** Keeps only the newest copy of each named screenshot. */
    fun shot(name: String, bmp: Bitmap) {
        val d = dir ?: return
        try { File(d, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 75, it) } } catch (_: Exception) {}
    }

    fun files(): List<File> = dir?.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()

    @Synchronized fun clear() { files().forEach { it.delete() } }

    fun rows(a: IntArray) = (0 until 9).joinToString("/") { r -> (0 until 9).joinToString("") { a[r * 9 + it].toString() } }
}
