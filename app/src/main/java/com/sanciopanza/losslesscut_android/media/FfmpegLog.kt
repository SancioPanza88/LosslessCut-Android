package com.sanciopanza.losslesscut_android.media

import com.sanciopanza.losslesscut_android.model.CutSegment
import java.io.File

/**
 * Come "View FFmpeg last command log" dell'originale: per ogni operazione
 * genera il comando ffmpeg equivalente, così l'utente può riusarlo su PC.
 */
object FfmpegLog {

    private val entries = ArrayDeque<String>()

    fun push(cmd: String) {
        entries.addLast(cmd)
        if (entries.size > 50) entries.removeFirst()
    }

    fun all(): List<String> = entries.toList()
    fun last(): String = entries.lastOrNull() ?: "(nessun comando ancora)"

    fun cutCmd(input: File, out: File, segs: List<CutSegment>): String = buildString {
        append("ffmpeg")
        var i = 0
        for (s in segs) {
            append(" -ss %.3f -to %.3f -i \"%s\"".format(s.startSec, s.endSec, input.absolutePath))
            i++
        }
        append(" -map 0 -c copy \"${out.absolutePath}\"")
    }

    fun mergeCmd(inputs: List<File>, out: File): String =
        "ffmpeg " + inputs.joinToString(" ") { "-i \"${it.absolutePath}\"" } +
            " -filter_complex \"concat=n=${inputs.size}:v=1:a=1\" -c copy \"${out.absolutePath}\"" +
            "  # (l'app usa concat demuxer lossless: vedi log operazione)"

    fun extractCmd(input: File, trackIndex: Int, out: File): String =
        "ffmpeg -i \"${input.absolutePath}\" -map 0:$trackIndex -c copy \"${out.absolutePath}\""

    fun remuxCmd(input: File, out: File): String =
        "ffmpeg -i \"${input.absolutePath}\" -c copy \"${out.absolutePath}\""

    fun speedCmd(input: File, factor: Double, out: File): String =
        "ffmpeg -i \"${input.absolutePath}\" -filter_complex \"[0:v]setpts=PTS/${factor}[v];[0:a]atempo=${factor}[a]\" -map \"[v]\" -map \"[a]\" \"${out.absolutePath}\"" +
            "  # (l'app scala i PTS senza re-encode)"

    fun snapshotCmd(input: File, timeSec: Double, out: File): String =
        "ffmpeg -ss %.3f -i \"%s\" -frames:v 1 -q:v 2 \"%s\"".format(timeSec, input.absolutePath, out.absolutePath)

    fun downloadCmd(url: String, out: File): String =
        "ffmpeg -i \"$url\" -c copy \"${out.absolutePath}\""
}
