package com.sanciopanza.losslesscut_android.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * Snapshot full-resolution JPEG/PNG ed export range di frame come immagini
 * (come l'originale: ogni N frame/secondi, con timestamp nei nomi).
 */
object Snapshots {

    fun grabFrame(context: Context, uri: Uri, timeSec: Double): Bitmap? {
        val ret = MediaMetadataRetriever()
        return try {
            ret.setDataSource(context, uri)
            ret.getFrameAtTime(
                (timeSec * 1_000_000).toLong(),
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            )
        } catch (_: Throwable) {
            null
        } finally {
            try { ret.release() } catch (_: Throwable) {}
        }
    }

    fun saveSnapshot(
        bmp: Bitmap,
        outFile: File,
        format: String = "jpg", // jpg | png
        quality: Int = 90
    ): Boolean {
        return try {
            outFile.parentFile?.mkdirs()
            FileOutputStream(outFile).use { fos ->
                if (format.equals("png", true)) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
                } else {
                    bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), fos)
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Esporta frame ogni stepSec nell'intervallo [fromSec, toSec]. Ritorna i file. */
    fun exportFrames(
        context: Context,
        uri: Uri,
        outDir: File,
        baseName: String,
        fromSec: Double,
        toSec: Double,
        stepSec: Double,
        format: String = "jpg",
        quality: Int = 90,
        includeTimestamps: Boolean = true,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<File> {
        val out = mutableListOf<File>()
        if (stepSec <= 0 || toSec <= fromSec) return out
        val total = ((toSec - fromSec) / stepSec).toInt() + 1
        var t = fromSec
        var i = 0
        val ret = MediaMetadataRetriever()
        try {
            ret.setDataSource(context, uri)
            while (t <= toSec + 1e-6) {
                val bmp = try {
                    ret.getFrameAtTime(
                        (t * 1_000_000).toLong(),
                        MediaMetadataRetriever.OPTION_CLOSEST
                    )
                } catch (_: Throwable) {
                    null
                }
                if (bmp != null) {
                    val stamp = if (includeTimestamps) "_${"%.3f".format(t)}" else ""
                    val f = File(outDir, "$baseName$stamp-${"%05d".format(i)}.$format")
                    if (saveSnapshot(bmp, f, format, quality)) out.add(f)
                    try { bmp.recycle() } catch (_: Throwable) {}
                }
                i++
                onProgress(i, total)
                t += stepSec
            }
        } catch (_: Throwable) {
        } finally {
            try { ret.release() } catch (_: Throwable) {}
        }
        return out
    }
}
