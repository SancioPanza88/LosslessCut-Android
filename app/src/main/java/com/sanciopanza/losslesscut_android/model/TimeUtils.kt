package com.sanciopanza.losslesscut_android.model

import kotlin.math.abs

/** Parsing/formattazione tempi, come il dialogo "manual input of cutpoint times". */
object TimeUtils {

    /** Accetta "SS", "MM:SS", "HH:MM:SS", con decimali e virgola. Ritorna secondi o null. */
    fun parse(s: String): Double? {
        val clean = s.trim().replace(",", ".")
        if (clean.isEmpty()) return null
        return try {
            if (!clean.contains(":")) clean.toDouble()
            else {
                val p = clean.split(":")
                if (p.size > 3) return null
                var mult = 1.0
                var total = 0.0
                for (part in p.reversed()) {
                    total += part.toDouble() * mult
                    mult *= 60
                }
                total
            }.takeIf { it >= 0 }
        } catch (_: Exception) {
            null
        }
    }

    /** "HH:MM:SS.mmm" come nella timeline dell'originale. */
    fun format(sec: Double): String {
        val totalMs = (sec * 1000).toLong().coerceAtLeast(0)
        val ms = totalMs % 1000
        val s = totalMs / 1000 % 60
        val m = totalMs / 60000 % 60
        val h = totalMs / 3600000
        return "%02d:%02d:%02d.%03d".format(h, m, s, ms)
    }

    fun formatShort(sec: Double): String {
        val totalS = sec.toLong().coerceAtLeast(0)
        val s = totalS % 60
        val m = totalS / 60 % 60
        val h = totalS / 3600
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun equals(a: Double, b: Double, eps: Double = 0.001): Boolean = abs(a - b) <= eps
}
