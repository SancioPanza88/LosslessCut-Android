package com.sanciopanza.losslesscut_android.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.sanciopanza.losslesscut_android.model.CutSegment
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Analisi locale HW-assistita (come black/silence/scene detection dell'originale):
 * - waveform: decode audio HW (MediaCodec) -> bucket RMS per la timeline
 * - black scenes: campionatura frame -> luminanza media
 * - scene change: differenza istogrammi grigi tra frame campionati
 * - silence: dagli stessi bucket RMS della waveform
 */
object Analysis {

    /** Bucket RMS 0..1 per la timeline (waveform come nell'originale). */
    fun waveform(
        context: Context,
        uri: Uri,
        audioTrackIndex: Int,
        buckets: Int = 200
    ): FloatArray {
        val out = FloatArray(buckets.coerceAtLeast(1))
        var ext: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            ext = MediaExtractor()
            ext.setDataSource(context, uri, null)
            if (audioTrackIndex !in 0 until ext.trackCount) return out
            val format = ext.getTrackFormat(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return out
            var durationUs = try {
                format.getLong(MediaFormat.KEY_DURATION)
            } catch (_: Exception) {
                0L
            }
            if (durationUs <= 0) durationUs = 60_000_000L
            ext.selectTrack(audioTrackIndex)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val sums = DoubleArray(out.size)
            val counts = LongArray(out.size)
            var sawInputEos = false
            val info = MediaCodec.BufferInfo()
            var guard = 0
            while (guard < 200_000) {
                guard++
                if (!sawInputEos) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val n = ext.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, ext.sampleTime, 0)
                            ext.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        val buf = codec.getOutputBuffer(outIdx)!!
                        val rms = pcmRms(buf, info.offset, info.size)
                        val b = ((info.presentationTimeUs.toDouble() / durationUs) * out.size)
                            .toInt().coerceIn(0, out.size - 1)
                        sums[b] += rms * rms
                        counts[b]++
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // ignora
                }
                if (sawInputEos && outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) break
            }
            for (i in out.indices) {
                out[i] = if (counts[i] > 0) {
                    sqrt(sums[i] / counts[i]).toFloat().coerceIn(0f, 1f)
                } else 0f
            }
        } catch (_: Throwable) {
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { ext?.release() } catch (_: Throwable) {}
        }
        return out
    }

    private fun pcmRms(buf: ByteBuffer, offset: Int, size: Int): Double {
        // Assume 16-bit PCM (formato decode più comune); fallback a byte.
        return try {
            val dup = buf.duplicate().order(ByteOrder.nativeOrder())
            dup.position(offset)
            val shorts = size / 2
            if (shorts <= 0) return 0.0
            var sum = 0.0
            for (i in 0 until shorts) {
                val v = dup.short / 32768.0
                sum += v * v
            }
            sqrt(sum / shorts)
        } catch (_: Exception) {
            0.0
        }
    }

    /** Segmenti silenziosi (rms < threshold per almeno minLenSec). */
    fun detectSilence(
        buckets: FloatArray,
        durationSec: Double,
        threshold: Float = 0.02f,
        minLenSec: Double = 1.0
    ): List<CutSegment> {
        if (buckets.isEmpty() || durationSec <= 0) return emptyList()
        val out = mutableListOf<CutSegment>()
        var start = -1.0
        for (i in buckets.indices) {
            val t = i * durationSec / buckets.size
            if (buckets[i] < threshold) {
                if (start < 0) start = t
            } else {
                if (start >= 0) {
                    if (t - start >= minLenSec) out.add(CutSegment(start, t, name = "Silence"))
                    start = -1.0
                }
            }
        }
        if (start >= 0 && durationSec - start >= minLenSec) {
            out.add(CutSegment(start, durationSec, name = "Silence"))
        }
        return out
    }

    private fun frameLuma(bmp: Bitmap, step: Int = 16): Double {
        var sum = 0L
        var n = 0L
        for (y in 0 until bmp.height step step) {
            for (x in 0 until bmp.width step step) {
                val p = bmp.getPixel(x, y)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                sum += (0.299 * r + 0.587 * g + 0.114 * b).toLong()
                n++
            }
        }
        return if (n == 0L) 0.0 else sum.toDouble() / n
    }

    private fun grayHist(bmp: Bitmap, bins: Int = 16): IntArray {
        val h = IntArray(bins)
        val step = 24
        for (y in 0 until bmp.height step step) {
            for (x in 0 until bmp.width step step) {
                val p = bmp.getPixel(x, y)
                val l = ((0.299 * ((p shr 16) and 0xFF) +
                    0.587 * ((p shr 8) and 0xFF) +
                    0.114 * (p and 0xFF)) / 16).toInt().coerceIn(0, bins - 1)
                h[l]++
            }
        }
        return h
    }

    private fun histDiff(a: IntArray, b: IntArray): Double {
        var d = 0.0
        var tot = 0.0
        for (i in a.indices) {
            d += abs(a[i] - b[i])
            tot += a[i] + b[i]
        }
        return if (tot == 0.0) 0.0 else d / tot
    }

    /**
     * Scansiona frame ogni stepSec: ritorna (blackSegments, sceneCuts).
     * black = luma < blackLuma per almeno minLen; scene = diff istogramma > threshold.
     */
    fun detectBlackAndScenes(
        context: Context,
        uri: Uri,
        durationSec: Double,
        stepSec: Double = 0.5,
        blackLuma: Double = 12.0,
        minBlackLen: Double = 1.0,
        sceneThreshold: Double = 0.45
    ): Pair<List<CutSegment>, List<Double>> {
        val blacks = mutableListOf<CutSegment>()
        val cuts = mutableListOf<Double>()
        if (durationSec <= 0 || stepSec <= 0) return blacks to cuts
        var blackStart = -1.0
        var prevHist: IntArray? = null
        var t = 0.0
        while (t < durationSec) {
            val bmp = Snapshots.grabFrame(context, uri, t)
            if (bmp != null) {
                try {
                    val luma = frameLuma(bmp)
                    if (luma < blackLuma) {
                        if (blackStart < 0) blackStart = t
                    } else {
                        if (blackStart >= 0) {
                            if (t - blackStart >= minBlackLen) {
                                blacks.add(CutSegment(blackStart, t, name = "Black"))
                            }
                            blackStart = -1.0
                        }
                    }
                    val h = grayHist(bmp)
                    val prev = prevHist
                    if (prev != null && histDiff(prev, h) > sceneThreshold && t > 0.5) {
                        cuts.add(t)
                    }
                    prevHist = h
                } catch (_: Throwable) {
                } finally {
                    try { bmp.recycle() } catch (_: Throwable) {}
                }
            }
            t += stepSec
        }
        if (blackStart >= 0 && durationSec - blackStart >= minBlackLen) {
            blacks.add(CutSegment(blackStart, durationSec, name = "Black"))
        }
        return blacks to cuts
    }
}
