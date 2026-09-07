package com.sanciopanza.losslesscut_android.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.sanciopanza.losslesscut_android.model.CutSegment
import com.sanciopanza.losslesscut_android.model.SegmentOps
import java.io.File
import java.nio.ByteBuffer

/**
 * Operazioni lossless (come ffmpeg -c copy dell'originale), via
 * MediaExtractor → MediaMuxer HW: nessun re-encode, velocità I/O.
 * - cut/merge/split/extract/remux/speed/loop/timelapse
 */
object LosslessOps {

    const val FORMAT_MP4 = "mp4"
    const val FORMAT_WEBM = "webm"
    const val FORMAT_TS = "ts"

    private const val SEEK = MediaExtractor.SEEK_TO_CLOSEST_SYNC

    data class JobResult(val ok: Boolean, val outFile: File?, val log: String)

    private class MuxSession(out: File, format: String) {
        val muxer = MediaMuxer(
            out.absolutePath,
            if (format == FORMAT_WEBM) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
            else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )
        val map = LinkedHashMap<String, Int>() // "fileIdx:trackIdx" -> muxerIdx
        var started = false

        fun trackId(fileIdx: Int, trackIdx: Int) = "$fileIdx:$trackIdx"

        fun addTrack(fileIdx: Int, trackIdx: Int, format: MediaFormat): Int {
            return map.getOrPut(trackId(fileIdx, trackIdx)) { muxer.addTrack(format) }
        }

        fun start() {
            if (!started) {
                muxer.start()
                started = true
            }
        }

        fun stop() {
            try { if (started) muxer.stop() } catch (_: Throwable) {}
            try { muxer.release() } catch (_: Throwable) {}
        }
    }

    private data class Range(val fromUs: Long, val toUs: Long)

    /**
     * Taglia/mantiene i segmenti (in ordine di output = supporta rearrange),
     * solo tracce abilitate (disposition come nell'originale).
     */
    fun cut(
        src: File,
        dst: File,
        segments: List<CutSegment>,
        enabledTracks: Set<Int>? = null,
        rotationHint: Int = 0,
        format: String = FORMAT_MP4,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): JobResult {
        val segs = SegmentOps.sort(segments)
        if (segs.isEmpty()) return JobResult(false, null, "cut: nessun segmento valido")
        return try {
            dst.parentFile?.mkdirs()
            if (dst.exists()) dst.delete()
            val session = MuxSession(dst, format)
            if (rotationHint != 0) {
                try { session.muxer.setOrientationHint(rotationHint) } catch (_: Throwable) {}
            }
            val ext = MediaExtractor()
            try {
                ext.setDataSource(src.absolutePath)
                val wanted = (0 until ext.trackCount).filter { enabledTracks == null || it in enabledTracks }
                if (wanted.isEmpty()) return JobResult(false, null, "cut: nessuna traccia selezionata")
                for (ti in wanted) {
                    ext.selectTrack(ti)
                    session.addTrack(0, ti, ext.getTrackFormat(ti))
                }
                session.start()
                var baseUs = 0L
                val total = src.length().coerceAtLeast(1)
                var copied = 0L
                for (s in segs) {
                    baseUs = copyRange(ext, session, 0, wanted,
                        Range((s.startSec * 1e6).toLong(), (s.endSec * 1e6).toLong()),
                        baseUs, filterSyncVideoOnly = false)
                    copied += (s.durationSec * 200_000).toLong()
                    onProgress(copied.coerceAtMost(total), total)
                }
                onProgress(total, total)
                session.stop()
                JobResult(true, dst, "cut: ${segs.size} segmenti -> ${dst.name}")
            } finally {
                try { ext.release() } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            try { if (dst.exists() && dst.length() == 0L) dst.delete() } catch (_: Throwable) {}
            JobResult(false, null, "cut fallito: ${t.message}")
        }
    }

    /** Merge/concat di file con stessi codec (come l'originale). */
    fun merge(
        srcs: List<File>,
        dst: File,
        enabledKinds: Set<String> = setOf("video", "audio", "subtitle"),
        rotationHint: Int = 0,
        format: String = FORMAT_MP4,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): JobResult {
        if (srcs.isEmpty()) return JobResult(false, null, "merge: nessun file")
        return try {
            dst.parentFile?.mkdirs()
            if (dst.exists()) dst.delete()
            val session = MuxSession(dst, format)
            if (rotationHint != 0) {
                try { session.muxer.setOrientationHint(rotationHint) } catch (_: Throwable) {}
            }
            val total = srcs.sumOf { it.length() }.coerceAtLeast(1)
            var done = 0L
            var baseUs = 0L
            // Mappa stabile: kind -> muxer track (primo file che la offre).
            val kindToMux = mutableMapOf<String, Int>()
            val kinds = mutableListOf<String>()
            srcs.forEachIndexed { fi, src ->
                val ext = MediaExtractor()
                try {
                    ext.setDataSource(src.absolutePath)
                    val wanted = (0 until ext.trackCount).filter { ti ->
                        kindOf(ext.getTrackFormat(ti)) in enabledKinds
                    }
                    for (ti in wanted) {
                        val kind = kindOf(ext.getTrackFormat(ti))
                        ext.selectTrack(ti)
                        val muxIdx = kindToMux.getOrPut(kind) {
                            val idx = session.addTrack(0, kinds.size, ext.getTrackFormat(ti))
                            kinds.add(kind)
                            idx
                        }
                        session.map[session.trackId(fi, ti)] = muxIdx
                    }
                    session.start()
                    baseUs = copyAllFrom(ext, session, fi, wanted, baseUs)
                    done += src.length()
                    onProgress(done.coerceAtMost(total), total)
                } finally {
                    try { ext.release() } catch (_: Throwable) {}
                }
            }
            onProgress(total, total)
            session.stop()
            JobResult(true, dst, "merge: ${srcs.size} file -> ${dst.name}")
        } catch (t: Throwable) {
            try { if (dst.exists() && dst.length() == 0L) dst.delete() } catch (_: Throwable) {}
            JobResult(false, null, "merge fallito: ${t.message}")
        }
    }

    /** Split: un file per segmento (come "split into files per segment"). */
    fun split(
        src: File,
        outDir: File,
        baseName: String,
        segments: List<CutSegment>,
        enabledTracks: Set<Int>? = null,
        format: String = FORMAT_MP4,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<JobResult> {
        val segs = SegmentOps.sort(segments)
        return segs.mapIndexed { i, s ->
            val ext = when (format) {
                FORMAT_WEBM -> "webm"
                FORMAT_TS -> "ts"
                else -> "mp4"
            }
            val name = if (s.name.isNotEmpty()) "${baseName}_${s.name}" else "${baseName}_part${i + 1}"
            val out = File(outDir, sanitize(name) + ".$ext")
            val r = cut(src, out, listOf(s), enabledTracks, 0, format) { _, _ -> }
            onProgress(i + 1, segs.size)
            r
        }
    }

    /** Estrae una traccia in file separato (video/audio/subtitle). */
    fun extractTrack(
        src: File,
        dst: File,
        trackIndex: Int,
        format: String = FORMAT_MP4
    ): JobResult {
        val ext = MediaExtractor()
        try {
            ext.setDataSource(src.absolutePath)
            if (trackIndex !in 0 until ext.trackCount) {
                return JobResult(false, null, "extract: traccia $trackIndex inesistente")
            }
            val mime = ext.getTrackFormat(trackIndex).getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("text/") || mime.contains("sub", true) || mime.contains("ssa", true)) {
                // Sottotitoli: copia testuale dei campioni (formato bruto del container).
                dst.parentFile?.mkdirs()
                dst.outputStream().buffered().use { out ->
                    ext.selectTrack(trackIndex)
                    val buf = ByteBuffer.allocate(512 * 1024)
                    while (true) {
                        if (ext.sampleTrackIndex < 0) break
                        val n = try { ext.readSampleData(buf, 0) } catch (_: Throwable) { -1 }
                        if (n < 0) break
                        val chunk = ByteArray(n)
                        buf.position(0)
                        buf.get(chunk)
                        out.write(chunk)
                        try { ext.advance() } catch (_: Throwable) { break }
                    }
                }
                return JobResult(true, dst, "extract: sottotitoli -> ${dst.name}")
            }
            dst.parentFile?.mkdirs()
            if (dst.exists()) dst.delete()
            val session = MuxSession(dst, format)
            try {
                ext.selectTrack(trackIndex)
                session.addTrack(0, trackIndex, ext.getTrackFormat(trackIndex))
                session.start()
                copyAllFrom(ext, session, 0, listOf(trackIndex), 0)
                session.stop()
                return JobResult(true, dst, "extract: $mime -> ${dst.name}")
            } catch (t: Throwable) {
                session.stop()
                return JobResult(false, null, "extract fallito: ${t.message}")
            }
        } catch (t: Throwable) {
            return JobResult(false, null, "extract fallito: ${t.message}")
        } finally {
            try { ext.release() } catch (_: Throwable) {}
        }
    }

    /** Remux in altro contenitore senza re-encode. */
    fun remux(src: File, dst: File, format: String, enabledTracks: Set<Int>? = null): JobResult {
        return try {
            val ext = MediaExtractor()
            try {
                ext.setDataSource(src.absolutePath)
                val wanted = (0 until ext.trackCount).filter {
                    enabledTracks == null || it in enabledTracks
                }
                dst.parentFile?.mkdirs()
                if (dst.exists()) dst.delete()
                val session = MuxSession(dst, format)
                try {
                    for (ti in wanted) {
                        ext.selectTrack(ti)
                        session.addTrack(0, ti, ext.getTrackFormat(ti))
                    }
                    session.start()
                    copyAllFrom(ext, session, 0, wanted, 0)
                    session.stop()
                    JobResult(true, dst, "remux -> ${dst.name}")
                } catch (t: Throwable) {
                    session.stop()
                    JobResult(false, null, "remux fallito: ${t.message}")
                }
            } finally {
                try { ext.release() } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            JobResult(false, null, "remux fallito: ${t.message}")
        }
    }

    /** Speed up/slow down (come "changing FPS"): scala i PTS, zero re-encode. */
    fun speed(src: File, dst: File, factor: Double, enabledTracks: Set<Int>? = null): JobResult {
        if (factor <= 0) return JobResult(false, null, "speed: fattore non valido")
        return try {
            dst.parentFile?.mkdirs()
            if (dst.exists()) dst.delete()
            val session = MuxSession(dst, FORMAT_MP4)
            val ext = MediaExtractor()
            try {
                ext.setDataSource(src.absolutePath)
                val wanted = (0 until ext.trackCount).filter {
                    enabledTracks == null || it in enabledTracks
                }
                for (ti in wanted) {
                    ext.selectTrack(ti)
                    session.addTrack(0, ti, ext.getTrackFormat(ti))
                }
                session.start()
                copyAllFrom(ext, session, 0, wanted, 0, ptsScale = 1.0 / factor)
                session.stop()
                JobResult(true, dst, "speed x$factor -> ${dst.name}")
            } finally {
                try { ext.release() } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            JobResult(false, null, "speed fallito: ${t.message}")
        }
    }

    /** Loop X volte senza re-encode (come #284 dell'originale). */
    fun loop(src: File, dst: File, times: Int): JobResult {
        if (times < 2) return JobResult(false, null, "loop: volte < 2")
        return merge(List(times) { src }, dst)
    }

    /** Timelapse: tiene solo i keyframe video (come "remove all non-keyframes"). */
    fun timelapse(src: File, dst: File, keepAudio: Boolean = false): JobResult {
        return try {
            dst.parentFile?.mkdirs()
            if (dst.exists()) dst.delete()
            val session = MuxSession(dst, FORMAT_MP4)
            val ext = MediaExtractor()
            try {
                ext.setDataSource(src.absolutePath)
                val videoTracks = (0 until ext.trackCount).filter {
                    (ext.getTrackFormat(it).getString(MediaFormat.KEY_MIME) ?: "")
                        .startsWith("video/")
                }
                val audioTracks = if (keepAudio) {
                    (0 until ext.trackCount).filter {
                        (ext.getTrackFormat(it).getString(MediaFormat.KEY_MIME) ?: "")
                            .startsWith("audio/")
                    }
                } else emptyList()
                val wanted = videoTracks + audioTracks
                if (videoTracks.isEmpty()) return JobResult(false, null, "timelapse: nessun video")
                for (ti in wanted) {
                    ext.selectTrack(ti)
                    session.addTrack(0, ti, ext.getTrackFormat(ti))
                }
                session.start()
                val buf = ByteBuffer.allocate(4 * 1024 * 1024)
                val info = MediaCodec.BufferInfo()
                var baseUs = 0L
                var lastVideoEnd = 0L
                while (true) {
                    val track = try { ext.sampleTrackIndex } catch (_: Throwable) { -1 }
                    if (track < 0) break
                    val isVideo = track in videoTracks
                    val flags = try { ext.sampleFlags } catch (_: Throwable) { 0 }
                    val size = try { ext.readSampleData(buf, 0) } catch (_: Throwable) { -1 }
                    val pts = try { ext.sampleTime } catch (_: Throwable) { 0L }
                    try { ext.advance() } catch (_: Throwable) {}
                    if (size < 0) continue
                    if (isVideo && flags and MediaExtractor.SAMPLE_FLAG_SYNC == 0) continue
                    val muxIdx = session.map[session.trackId(0, track)] ?: continue
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = (pts + baseUs).coerceAtLeast(0)
                    info.flags = mapFlags(flags)
                    try {
                        session.muxer.writeSampleData(muxIdx, buf, info)
                        if (isVideo) lastVideoEnd = info.presentationTimeUs
                    } catch (_: Throwable) {
                    }
                }
                if (keepAudio) baseUs = lastVideoEnd
                session.stop()
                JobResult(true, dst, "timelapse -> ${dst.name}")
            } finally {
                try { ext.release() } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            JobResult(false, null, "timelapse fallito: ${t.message}")
        }
    }

    // ---------- internals ----------

    private fun kindOf(f: MediaFormat): String {
        val mime = f.getString(MediaFormat.KEY_MIME) ?: return "other"
        return when {
            mime.startsWith("video/") -> "video"
            mime.startsWith("audio/") -> "audio"
            mime.startsWith("text/") || mime.contains("sub", true) -> "subtitle"
            else -> "other"
        }
    }

    private fun mapFlags(sampleFlags: Int): Int {
        var out = 0
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            out = out or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
            out = out or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        }
        return out
    }

    /** Copia i campioni in [range], ribasati su baseUs. Ritorna la nuova base. */
    private fun copyRange(
        ext: MediaExtractor,
        session: MuxSession,
        fileIdx: Int,
        wanted: List<Int>,
        range: Range,
        baseUs: Long,
        filterSyncVideoOnly: Boolean
    ): Long {
        try {
            ext.seekTo(range.fromUs, SEEK)
        } catch (_: Throwable) {
            try { ext.seekTo(range.fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC) } catch (_: Throwable) {}
        }
        val buf = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        var maxEnd = baseUs
        // Se il seek è finito dopo la fine del range, niente da copiare.
        while (true) {
            val track = try { ext.sampleTrackIndex } catch (_: Throwable) { -1 }
            if (track < 0 || track !in wanted) {
                // Campione di traccia non voluta o fine: avanza.
                val adv = try { ext.advance(); true } catch (_: Throwable) { false }
                if (!adv) break
                continue
            }
            val pts = try { ext.sampleTime } catch (_: Throwable) { 0L }
            if (pts >= range.toUs) break
            if (pts < range.fromUs - 1_000_000) {
                // Troppo prima (seek approssimato): salta ma evita loop infiniti.
                try { ext.advance() } catch (_: Throwable) { break }
                continue
            }
            val flags = try { ext.sampleFlags } catch (_: Throwable) { 0 }
            val size = try { ext.readSampleData(buf, 0) } catch (_: Throwable) { -1 }
            try { ext.advance() } catch (_: Throwable) {}
            if (size < 0) continue
            val muxIdx = session.map[session.trackId(fileIdx, track)] ?: continue
            info.offset = 0
            info.size = size
            info.presentationTimeUs = (pts - range.fromUs + baseUs).coerceAtLeast(0)
            info.flags = mapFlags(flags)
            try {
                session.muxer.writeSampleData(muxIdx, buf, info)
                if (info.presentationTimeUs > maxEnd) maxEnd = info.presentationTimeUs
            } catch (_: Throwable) {
            }
        }
        // Base = fine reale copiata (se nulla copiato, avanza di durata nominale).
        return if (maxEnd > baseUs) maxEnd + 40_000
        else baseUs + (range.toUs - range.fromUs)
    }

    private fun copyAllFrom(
        ext: MediaExtractor,
        session: MuxSession,
        fileIdx: Int,
        wanted: List<Int>,
        baseUs: Long,
        ptsScale: Double = 1.0
    ): Long {
        val buf = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        var maxEnd = baseUs
        while (true) {
            val track = try { ext.sampleTrackIndex } catch (_: Throwable) { -1 }
            if (track < 0) break
            if (track !in wanted) {
                try { ext.advance() } catch (_: Throwable) { break }
                continue
            }
            val pts = try { ext.sampleTime } catch (_: Throwable) { 0L }
            val flags = try { ext.sampleFlags } catch (_: Throwable) { 0 }
            val size = try { ext.readSampleData(buf, 0) } catch (_: Throwable) { -1 }
            try { ext.advance() } catch (_: Throwable) {}
            if (size < 0) continue
            val muxIdx = session.map[session.trackId(fileIdx, track)] ?: continue
            info.offset = 0
            info.size = size
            info.presentationTimeUs = ((pts * ptsScale).toLong() + baseUs).coerceAtLeast(0)
            info.flags = mapFlags(flags)
            try {
                session.muxer.writeSampleData(muxIdx, buf, info)
                if (info.presentationTimeUs > maxEnd) maxEnd = info.presentationTimeUs
            } catch (_: Throwable) {
            }
        }
        return maxEnd + 40_000
    }

    fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "output" }
}
