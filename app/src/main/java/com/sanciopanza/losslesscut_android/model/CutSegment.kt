package com.sanciopanza.losslesscut_android.model

/** Un segmento di taglio [startSec, endSec), come in LosslessCut. */
data class CutSegment(
    val startSec: Double,
    val endSec: Double,
    val name: String = "",
    val tags: List<String> = emptyList()
) {
    val durationSec: Double get() = (endSec - startSec).coerceAtLeast(0.0)
    fun isValid(): Boolean = endSec > startSec && startSec >= 0
}
