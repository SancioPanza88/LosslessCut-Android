package com.sanciopanza.losslesscut_android.media

import android.content.Context
import android.media.MediaExtractor
import android.net.Uri

/**
 * Indice dei keyframe (come il "keyframe jumping" dell'originale):
 * scansiona i campioni video e registra i timestamp SYNC. Su file grandi
 * la scansione è I/O-bound veloce (niente decode).
 */
object KeyframeIndex {

    fun build(context: Context, uri: Uri, videoTrackIndex: Int, maxSamples: Int = 2_000_000): List<Double> {
        val ext = MediaExtractor()
        val out = mutableListOf<Double>()
        try {
            ext.setDataSource(context, uri, null)
            if (videoTrackIndex !in 0 until ext.trackCount) return emptyList()
            ext.selectTrack(videoTrackIndex)
            var n = 0
            while (n < maxSamples) {
                val track = try { ext.sampleTrackIndex } catch (_: Throwable) { -1 }
                if (track < 0) break
                val flags = try { ext.sampleFlags } catch (_: Throwable) { 0 }
                if (track == videoTrackIndex &&
                    flags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC != 0
                ) {
                    out.add(ext.sampleTime / 1_000_000.0)
                }
                try {
                    if (!ext.advance()) break
                } catch (_: Throwable) {
                    break
                }
                n++
            }
        } catch (_: Throwable) {
        } finally {
            try { ext.release() } catch (_: Throwable) {}
        }
        return out
    }

    /** Keyframe <= t (snap del punto di taglio, come "cut around keyframes"). */
    fun snapDown(keys: List<Double>, t: Double): Double {
        var best = 0.0
        for (k in keys) {
            if (k <= t + 1e-6) best = k else break
        }
        return best
    }

    /** Keyframe > t (salto avanti). */
    fun next(keys: List<Double>, t: Double): Double? = keys.firstOrNull { it > t + 1e-6 }

    /** Keyframe < t (salto indietro). */
    fun prev(keys: List<Double>, t: Double): Double? = keys.lastOrNull { it < t - 1e-6 }
}
