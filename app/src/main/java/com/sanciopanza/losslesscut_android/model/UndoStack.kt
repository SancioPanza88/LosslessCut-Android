package com.sanciopanza.losslesscut_android.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Undo/redo della lista segmenti (come in LosslessCut). Snapshot immutabili.
 */
class UndoStack {
    private val undo = ArrayDeque<List<CutSegment>>()
    private val redo = ArrayDeque<List<CutSegment>>()

    fun push(state: List<CutSegment>) {
        undo.addLast(state.toList())
        if (undo.size > 100) undo.removeFirst()
        redo.clear()
    }

    fun canUndo(): Boolean = undo.size > 1
    fun canRedo(): Boolean = redo.isNotEmpty()

    fun undo(): List<CutSegment>? {
        if (!canUndo()) return null
        redo.addLast(undo.removeLast())
        return undo.last().toList()
    }

    fun redo(): List<CutSegment>? {
        if (!canRedo()) return null
        val s = redo.removeLast()
        undo.addLast(s)
        return s.toList()
    }

    fun reset(initial: List<CutSegment>) {
        undo.clear()
        redo.clear()
        undo.addLast(initial.toList())
    }
}
