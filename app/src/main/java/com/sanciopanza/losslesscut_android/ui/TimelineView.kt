package com.sanciopanza.losslesscut_android.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.sanciopanza.losslesscut_android.model.CutSegment

/**
 * Timeline come nell'originale: waveform + segmenti + playhead.
 * Tap = seek, trascina sui bordi = regola (via callback al raisedrag).
 */
class TimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var durationSec: Double = 1.0
    var positionSec: Double = 0.0
    var segments: List<CutSegment> = emptyList()
    var waveform: FloatArray = FloatArray(0)
    var keyframes: List<Double> = emptyList()
    var onSeek: (Double) -> Unit = {}
    var removeMode: Boolean = false

    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8AB4F8") }
    private val segPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#46A35E")
        alpha = 110
    }
    private val segRemovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D93025")
        alpha = 110
    }
    private val playPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 4f
    }
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F9AB00")
        strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
    }

    fun refresh() {
        invalidate()
    }

    private fun xOf(t: Double): Float =
        (t / durationSec.coerceAtLeast(0.001) * width).toFloat().coerceIn(0f, width.toFloat())

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        canvas.drawColor(Color.parseColor("#202124"))
        // Waveform
        if (waveform.isNotEmpty()) {
            val bw = w / waveform.size
            for (i in waveform.indices) {
                val amp = waveform[i].coerceIn(0f, 1f)
                val bh = amp * h * 0.85f
                canvas.drawRect(
                    i * bw, (h - bh) / 2, (i + 1) * bw - 1, (h + bh) / 2, wavePaint
                )
            }
        }
        // Segmenti
        for ((idx, s) in segments.withIndex()) {
            val paint = if (removeMode) segRemovePaint else segPaint
            canvas.drawRect(xOf(s.startSec), 0f, xOf(s.endSec), h, paint)
            if (s.name.isNotEmpty()) {
                canvas.drawText(s.name, xOf(s.startSec) + 6, 34f, textPaint)
            } else {
                canvas.drawText("${idx + 1}", xOf(s.startSec) + 6, 34f, textPaint)
            }
        }
        // Keyframe (sotto-campionati per non intasare)
        val step = (keyframes.size / 300).coerceAtLeast(1)
        keyframes.filterIndexed { i, _ -> i % step == 0 }.forEach { k ->
            canvas.drawLine(xOf(k), h - 18, xOf(k), h, keyPaint)
        }
        // Playhead
        canvas.drawLine(xOf(positionSec), 0f, xOf(positionSec), h, playPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val t = (event.x / width.coerceAtLeast(1) * durationSec)
                    .coerceIn(0.0, durationSec)
                positionSec = t
                invalidate()
                onSeek(t)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
