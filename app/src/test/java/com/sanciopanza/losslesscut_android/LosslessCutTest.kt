package com.sanciopanza.losslesscut_android

import com.sanciopanza.losslesscut_android.media.FfmpegLog
import com.sanciopanza.losslesscut_android.media.KeyframeIndex
import com.sanciopanza.losslesscut_android.model.ChapterIO
import com.sanciopanza.losslesscut_android.model.CutSegment
import com.sanciopanza.losslesscut_android.model.SegmentOps
import com.sanciopanza.losslesscut_android.model.TimeUtils
import com.sanciopanza.losslesscut_android.model.UndoStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SegmentOpsTest {

    @Test
    fun invert_complement() {
        val keep = listOf(CutSegment(10.0, 20.0), CutSegment(30.0, 40.0))
        val inv = SegmentOps.invert(keep, 50.0)
        assertEquals(3, inv.size)
        assertEquals(0.0, inv[0].startSec, 0.001)
        assertEquals(10.0, inv[0].endSec, 0.001)
        assertEquals(20.0, inv[1].startSec, 0.001)
        assertEquals(40.0, inv[2].startSec, 0.001)
        assertEquals(50.0, inv[2].endSec, 0.001)
    }

    @Test
    fun mergeOverlapping_joins() {
        val out = SegmentOps.mergeOverlapping(
            listOf(CutSegment(0.0, 10.0), CutSegment(9.0, 20.0), CutSegment(30.0, 40.0))
        )
        assertEquals(2, out.size)
        assertEquals(20.0, out[0].endSec, 0.001)
    }

    @Test
    fun divideByCount_even() {
        val out = SegmentOps.divideByCount(100.0, 4)
        assertEquals(4, out.size)
        assertEquals(25.0, out[1].startSec, 0.001)
        assertEquals(100.0, out[3].endSec, 0.001)
    }

    @Test
    fun divideByLength_lastShort() {
        val out = SegmentOps.divideByLength(65.0, 30.0)
        assertEquals(3, out.size)
        assertEquals(5.0, out[2].durationSec, 0.001)
    }

    @Test
    fun reorder_moves() {
        val a = CutSegment(0.0, 1.0, "a")
        val b = CutSegment(1.0, 2.0, "b")
        val out = SegmentOps.reorder(listOf(a, b), 0, 1)
        assertEquals("b", out[0].name)
        assertEquals("a", out[1].name)
    }

    @Test
    fun filterByTag_caseInsensitive() {
        val segs = listOf(
            CutSegment(0.0, 1.0, tags = listOf("good")),
            CutSegment(1.0, 2.0, tags = listOf("bad"))
        )
        assertEquals(1, SegmentOps.filterByTag(segs, "GOOD").size)
    }
}

class TimeUtilsTest {

    @Test
    fun parse_formats() {
        assertEquals(90.5, TimeUtils.parse("90.5")!!, 0.001)
        assertEquals(90.5, TimeUtils.parse("1:30.5")!!, 0.001)
        assertEquals(3723.0, TimeUtils.parse("1:02:03")!!, 0.001)
        assertEquals(90.5, TimeUtils.parse("1:30,5")!!, 0.001)
        assertNull(TimeUtils.parse("nope"))
    }

    @Test
    fun format_roundtrip() {
        assertEquals("01:02:03.500", TimeUtils.format(3723.5))
        assertEquals("1:02:03", TimeUtils.formatShort(3723.0))
    }
}

class ChapterIOTest {

    private val segs = listOf(
        CutSegment(0.0, 10.5, "Intro", listOf("good")),
        CutSegment(61.0, 120.0, "Main")
    )

    @Test
    fun projectJson_roundtrip() {
        val json = ChapterIO.toProjectJson("video.mp4", 120.0, segs)
        val back = ChapterIO.fromProjectJson(json)
        assertEquals(2, back.size)
        assertEquals("Intro", back[0].name)
        assertEquals(listOf("good"), back[0].tags)
        assertEquals(61.0, back[1].startSec, 0.001)
    }

    @Test
    fun csv_roundtrip() {
        val back = ChapterIO.fromCsv(ChapterIO.toCsv(segs))
        assertEquals(2, back.size)
        assertEquals(10.5, back[0].endSec, 0.001)
        assertEquals(listOf("good"), back[0].tags)
    }

    @Test
    fun cue_hasTracks() {
        val cue = ChapterIO.toCue("video.mp4", segs)
        assertTrue(cue.contains("TRACK 01"))
        assertTrue(cue.contains("TRACK 02"))
    }

    @Test
    fun youTube_roundtrip() {
        val yt = ChapterIO.toYouTube(segs)
        assertTrue(yt.contains("0:00 Intro"))
        val back = ChapterIO.fromYouTube(yt)
        assertEquals(2, back.size)
        assertEquals("Intro", back[0].name)
    }
}

class UndoStackTest {

    @Test
    fun undoRedo_flow() {
        val u = UndoStack()
        val s1 = listOf(CutSegment(0.0, 1.0))
        val s2 = listOf(CutSegment(0.0, 1.0), CutSegment(2.0, 3.0))
        u.reset(s1)
        assertFalse(u.canUndo())
        u.push(s2)
        assertTrue(u.canUndo())
        assertEquals(1, u.undo()!!.size)
        assertTrue(u.canRedo())
        assertEquals(2, u.redo()!!.size)
    }
}

class KeyframeSnapTest {

    private val keys = listOf(0.0, 2.0, 4.0, 8.0)

    @Test
    fun snapDown_prev_next() {
        assertEquals(4.0, KeyframeIndex.snapDown(keys, 5.0), 0.001)
        assertEquals(8.0, KeyframeIndex.next(keys, 5.0)!!, 0.001)
        assertEquals(4.0, KeyframeIndex.prev(keys, 5.0)!!, 0.001)
        assertNull(KeyframeIndex.prev(keys, 0.0))
    }
}

class FfmpegLogTest {

    @Test
    fun cutCmd_hasRanges() {
        val cmd = FfmpegLog.cutCmd(
            File("/a/in.mp4"), File("/a/out.mp4"),
            listOf(CutSegment(1.0, 2.0))
        )
        assertTrue(cmd.startsWith("ffmpeg"))
        assertTrue(cmd.contains("-ss 1.000"))
        assertTrue(cmd.contains("-c copy"))
    }
}
