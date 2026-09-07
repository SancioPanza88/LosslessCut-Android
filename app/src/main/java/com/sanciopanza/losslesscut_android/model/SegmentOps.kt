package com.sanciopanza.losslesscut_android.model

import kotlin.math.max
import kotlin.math.min

/**
 * Operazioni sui segmenti, stessa semantica di LosslessCut:
 * keep/remove, inverti, riordina, unisci adiacenti, dividi per lunghezza/numero.
 * Pure Kotlin, coperto da unit test.
 */
object SegmentOps {

    fun sort(segs: List<CutSegment>): List<CutSegment> =
        segs.filter { it.isValid() }.sortedBy { it.startSec }

    /** Complemento (parti da rimuovere) rispetto alla durata totale: come "cut out". */
    fun invert(keep: List<CutSegment>, durationSec: Double): List<CutSegment> {
        val k = sort(keep)
        val out = mutableListOf<CutSegment>()
        var cursor = 0.0
        for (s in k) {
            if (s.startSec > cursor) out.add(CutSegment(cursor, min(s.startSec, durationSec)))
            cursor = max(cursor, s.endSec)
        }
        if (cursor < durationSec) out.add(CutSegment(cursor, durationSec))
        return out
    }

    /** Unisce segmenti sovrapposti o adiacenti (tolleranza in secondi). */
    fun mergeOverlapping(segs: List<CutSegment>, toleranceSec: Double = 0.001): List<CutSegment> {
        val sorted = sort(segs)
        if (sorted.isEmpty()) return emptyList()
        val out = mutableListOf(sorted[0])
        for (s in sorted.drop(1)) {
            val last = out.last()
            if (s.startSec <= last.endSec + toleranceSec) {
                val tags = (last.tags + s.tags).distinct()
                val name = listOf(last.name, s.name).filter { it.isNotEmpty() }.joinToString("+")
                out[out.lastIndex] = last.copy(endSec = max(last.endSec, s.endSec), name = name, tags = tags)
            } else out.add(s)
        }
        return out
    }

    /** Divide la timeline in N parti uguali (come "divide into N segments"). */
    fun divideByCount(durationSec: Double, count: Int): List<CutSegment> {
        if (count <= 0 || durationSec <= 0) return emptyList()
        val len = durationSec / count
        return (0 until count).map { i ->
            CutSegment(i * len, if (i == count - 1) durationSec else (i + 1) * len, name = "Part ${i + 1}")
        }
    }

    /** Divide la timeline in parti lunghe L secondi (come "segments of length L"). */
    fun divideByLength(durationSec: Double, lengthSec: Double): List<CutSegment> {
        if (lengthSec <= 0 || durationSec <= 0) return emptyList()
        val out = mutableListOf<CutSegment>()
        var t = 0.0
        var i = 1
        while (t < durationSec) {
            out.add(CutSegment(t, min(t + lengthSec, durationSec), name = "Part $i"))
            t += lengthSec
            i++
        }
        return out
    }

    /** Riordina: sposta il segmento da fromIndex a toIndex. */
    fun reorder(segs: List<CutSegment>, fromIndex: Int, toIndex: Int): List<CutSegment> {
        if (fromIndex !in segs.indices || toIndex !in segs.indices) return segs
        val m = segs.toMutableList()
        val s = m.removeAt(fromIndex)
        m.add(toIndex, s)
        return m
    }

    /** Filtra per tag (come l'organizzazione per tag dell'originale). */
    fun filterByTag(segs: List<CutSegment>, tag: String): List<CutSegment> {
        if (tag.isBlank()) return segs
        return segs.filter { s -> s.tags.any { it.equals(tag, ignoreCase = true) } }
    }
}
