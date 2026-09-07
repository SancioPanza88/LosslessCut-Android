package com.sanciopanza.losslesscut_android.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

/** Traccia rilevata, come il pannello "tracks" dell'originale. */
data class TrackInfo(
    val index: Int,
    val mime: String,
    val kind: String, // video | audio | subtitle | other
    val language: String = "und",
    val width: Int = 0,
    val height: Int = 0,
    val sampleRate: Int = 0,
    val channelCount: Int = 0,
    val bitrate: Int = 0,
    val durationUs: Long = 0,
    val rotation: Int = 0
)

data class MediaInfo(
    val durationSec: Double,
    val tracks: List<TrackInfo>,
    val fileName: String
) {
    val videoTracks: List<TrackInfo> get() = tracks.filter { it.kind == "video" }
    val audioTracks: List<TrackInfo> get() = tracks.filter { it.kind == "audio" }
    val subtitleTracks: List<TrackInfo> get() = tracks.filter { it.kind == "subtitle" }
}

/** Probe via MediaExtractor (come ffprobe: dati tecnici di tutte le tracce). */
object MediaProbe {
    fun probe(context: Context, uri: Uri, fileName: String): MediaInfo {
        val ext = MediaExtractor()
        try {
            ext.setDataSource(context, uri, null)
            var durationUs = 0L
            val tracks = (0 until ext.trackCount).map { i ->
                val f = ext.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: "application/octet-stream"
                val kind = when {
                    mime.startsWith("video/") -> "video"
                    mime.startsWith("audio/") -> "audio"
                    mime.startsWith("text/") || mime == "application/x-subrip" ||
                        mime.contains("subtitle", true) || mime.contains("ssa", true) ||
                        mime.contains("srt", true) || mime.contains("vtt", true) -> "subtitle"
                    else -> "other"
                }
                val d = try {
                    f.getLong(MediaFormat.KEY_DURATION)
                } catch (_: Exception) {
                    0L
                }
                if (d > durationUs) durationUs = d
                TrackInfo(
                    index = i,
                    mime = mime,
                    kind = kind,
                    language = try {
                        f.getString(MediaFormat.KEY_LANGUAGE) ?: "und"
                    } catch (_: Exception) {
                        "und"
                    },
                    width = f.getIntOr(MediaFormat.KEY_WIDTH, 0),
                    height = f.getIntOr(MediaFormat.KEY_HEIGHT, 0),
                    sampleRate = f.getIntOr(MediaFormat.KEY_SAMPLE_RATE, 0),
                    channelCount = f.getIntOr(MediaFormat.KEY_CHANNEL_COUNT, 0),
                    bitrate = f.getIntOr(MediaFormat.KEY_BIT_RATE, 0),
                    durationUs = d,
                    rotation = f.getIntOr("rotation-degrees", 0)
                )
            }
            // Fallback durata dai metadati se le tracce non la espongono.
            if (durationUs <= 0) {
                try {
                    val ret = android.media.MediaMetadataRetriever()
                    ret.setDataSource(context, uri)
                    durationUs = (ret.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
                    )?.toLongOrNull() ?: 0L) * 1000
                    try { ret.release() } catch (_: Throwable) {}
                } catch (_: Throwable) {
                }
            }
            return MediaInfo(durationUs / 1_000_000.0, tracks, fileName)
        } finally {
            try { ext.release() } catch (_: Throwable) {}
        }
    }

    private fun MediaFormat.getIntOr(key: String, def: Int): Int {
        return try {
            if (containsKey(key)) getInteger(key) else def
        } catch (_: Exception) {
            def
        }
    }
}
