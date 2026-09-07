package com.sanciopanza.losslesscut_android.model

/**
 * Import/export segmenti come l'originale: progetto LLC JSON, CSV EDL,
 * CUE sheet, capitoli YouTube (descrizione). JSON manuale (niente dipendenze,
 * testabile su JVM pura).*/
object ChapterIO {

    private fun esc(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")

    private fun unesc(s: String): String = s
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\\\", "\\")

    fun toProjectJson(fileName: String, durationSec: Double, segs: List<CutSegment>): String = buildString {
        append("{\"app\":\"LosslessCut-Android\",\"fileName\":\"${esc(fileName)}\",")
        append("\"durationSec\":$durationSec,\"cutSegments\":[")
        segs.forEachIndexed { i, s ->
            if (i > 0) append(",")
            append("{\"start\":${s.startSec},\"end\":${s.endSec},\"name\":\"${esc(s.name)}\",")
            append("\"tags\":[${s.tags.joinToString(",") { "\"${esc(it)}\"" }}]}")
        }
        append("]}")
    }

    fun fromProjectJson(json: String): List<CutSegment> {
        return try {
            val out = mutableListOf<CutSegment>()
            val re = Regex("""\{"start":([0-9.eE+\-]+),"end":([0-9.eE+\-]+),"name":"((?:[^"\\]|\\.)*)","tags":\[([^\]]*)]}""")
            for (m in re.findAll(json)) {
                val tags = Regex(""""((?:[^"\\]|\\.)*)"""").findAll(m.groupValues[4])
                    .map { unesc(it.groupValues[1]) }.toList()
                val s = CutSegment(
                    startSec = m.groupValues[1].toDouble(),
                    endSec = m.groupValues[2].toDouble(),
                    name = unesc(m.groupValues[3]),
                    tags = tags
                )
                if (s.isValid()) out.add(s)
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** CSV EDL: start,end,name,tags per riga (tempi in secondi). */
    fun toCsv(segs: List<CutSegment>): String = buildString {
        append("start,end,name,tags\n")
        for (s in segs) {
            append("%.3f,%.3f,\"%s\",\"%s\"\n".format(
                s.startSec, s.endSec,
                s.name.replace("\"", "\"\""),
                s.tags.joinToString("|").replace("\"", "\"\"")
            ))
        }
    }

    fun fromCsv(csv: String): List<CutSegment> {
        val out = mutableListOf<CutSegment>()
        for (line in csv.lines().drop(1)) {
            if (line.isBlank()) continue
            // parser CSV minimale con virgolette
            val cols = mutableListOf<String>()
            val cur = StringBuilder()
            var inQ = false
            for (ch in line) {
                when {
                    ch == '"' -> inQ = !inQ
                    ch == ',' && !inQ -> { cols.add(cur.toString()); cur.clear() }
                    else -> cur.append(ch)
                }
            }
            cols.add(cur.toString())
            val start = cols.getOrNull(0)?.toDoubleOrNull() ?: continue
            val end = cols.getOrNull(1)?.toDoubleOrNull() ?: continue
            out.add(CutSegment(start, end, cols.getOrNull(2) ?: "",
                cols.getOrNull(3)?.split("|")?.filter { it.isNotEmpty() } ?: emptyList()))
        }
        return out.filter { it.isValid() }
    }

    /** CUE sheet (FILE + TRACK/INDEX), una traccia per segmento. */
    fun toCue(fileName: String, segs: List<CutSegment>): String = buildString {
        append("FILE \"$fileName\" MP4\n")
        segs.forEachIndexed { i, s ->
            append("  TRACK %02d AUDIO\n".format(i + 1))
            append("    TITLE \"${s.name.ifEmpty { "Chapter ${i + 1}" }}\"\n")
            append("    INDEX 01 ${cueTime(s.startSec)}\n")
        }
    }

    private fun cueTime(sec: Double): String {
        val totalFrames = (sec * 75).toLong()
        val f = totalFrames % 75
        val s = totalFrames / 75 % 60
        val m = totalFrames / 4500
        return "%02d:%02d:%02d".format(m, s, f)
    }

    /** Capitoli YouTube: "MM:SS titolo" per riga (come i commenti importati dall'originale). */
    fun toYouTube(segs: List<CutSegment>): String = buildString {
        for (s in segs) append("${TimeUtils.formatShort(s.startSec)} ${s.name.ifEmpty { "Chapter" }}\n")
    }

    fun fromYouTube(text: String): List<CutSegment> {
        val times = mutableListOf<Pair<Double, String>>()
        val re = Regex("""^((?:\d+:)?\d+:\d+)\s+(.+)$""")
        for (line in text.lines()) {
            val m = re.find(line.trim()) ?: continue
            val t = TimeUtils.parse(m.groupValues[1]) ?: continue
            times.add(t to m.groupValues[2])
        }
        if (times.isEmpty()) return emptyList()
        return times.mapIndexed { i, (t, name) ->
            val end = times.getOrNull(i + 1)?.first ?: (t + 60)
            CutSegment(t, end, name)
        }.filter { it.isValid() }
    }
}
