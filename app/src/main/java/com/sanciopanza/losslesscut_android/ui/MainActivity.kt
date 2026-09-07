package com.sanciopanza.losslesscut_android.ui

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.android.material.button.MaterialButton
import com.sanciopanza.losslesscut_android.media.Analysis
import com.sanciopanza.losslesscut_android.media.FfmpegLog
import com.sanciopanza.losslesscut_android.media.KeyframeIndex
import com.sanciopanza.losslesscut_android.media.LosslessOps
import com.sanciopanza.losslesscut_android.media.MediaInfo
import com.sanciopanza.losslesscut_android.media.MediaProbe
import com.sanciopanza.losslesscut_android.media.Snapshots
import com.sanciopanza.losslesscut_android.model.ChapterIO
import com.sanciopanza.losslesscut_android.model.CutSegment
import com.sanciopanza.losslesscut_android.model.SegmentOps
import com.sanciopanza.losslesscut_android.model.TimeUtils
import com.sanciopanza.losslesscut_android.model.UndoStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Port Android di LosslessCut (mifi, GPL-2.0): stesse funzioni dell'originale —
 * taglio lossless, split, merge, extract, remux, snapshot, frame, chapters,
 * metadati, rotazione, offset, divide, detect, undo/redo, shortcut tastiera —
 * via MediaExtractor/MediaMuxer HW + ExoPlayer. Vedi README "Tabella parità".
 */
class MainActivity : AppCompatActivity() {

    private lateinit var store: SettingsStore
    private lateinit var settings: SettingsStore.Settings
    private lateinit var timeline: TimelineView
    private lateinit var posLabel: TextView
    private lateinit var segList: TextView
    private lateinit var logView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null

    private var workFile: File? = null
    private var fileName: String = "video.mp4"
    private var info: MediaInfo? = null
    private var durationSec: Double = 0.0
    private var segments: MutableList<CutSegment> = mutableListOf()
    private val undo = UndoStack()
    private val enabledTracks = mutableSetOf<Int>()
    private var keyframes: List<Double> = emptyList()
    private var pendingIn: Double? = null
    private var rotation: Int = 0
    private var metaTitle: String = ""
    private var metaAuthor: String = ""
    private var pendingImportFormat: String = "csv"

    private val handler = Handler(Looper.getMainLooper())
    private val posUpdater = object : Runnable {
        override fun run() {
            player?.let { p ->
                if (p.playbackState != Player.STATE_IDLE) {
                    val pos = p.currentPosition / 1000.0
                    timeline.positionSec = pos
                    timeline.refresh()
                    posLabel.text = "${TimeUtils.format(pos)} / ${TimeUtils.format(durationSec)}"
                }
            }
            handler.postDelayed(this, 250)
        }
    }

    private val openOne = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openUri(uri)
    }
    private val openMany = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) mergeUris(uris)
    }
    private val openText = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importChapters(uri, pendingImportFormat)
    }
    private val openProject = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadProject(uri)
    }
    private val openExternalSub = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) attachExternalFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(applicationContext)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        fun btn(label: String): MaterialButton {
            val b = MaterialButton(this).apply { text = label }
            root.addView(b)
            return b
        }

        fun row(vararg labels: String, onClick: (Int) -> Unit) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            labels.forEachIndexed { i, l ->
                val b = MaterialButton(r.context).apply {
                    text = l
                    setOnClickListener { onClick(i) }
                }
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                r.addView(b, lp)
            }
            root.addView(r)
        }

        btn("Apri file video/audio").setOnClickListener {
            openOne.launch(arrayOf("*/*"))
        }

        playerView = PlayerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 700
            )
        }
        root.addView(playerView)

        posLabel = TextView(this).apply { text = "00:00:00.000 / 00:00:00.000" }
        root.addView(posLabel)

        timeline = TimelineView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 220
            )
            onSeek = { t -> player?.seekTo((t * 1000).toLong()) }
        }
        root.addView(timeline)

        row("⏮ -5s", "◀ frame", "▶/⏸", "frame ▶", "+5s ⏭") { i ->
            when (i) {
                0 -> relSeek(-5.0)
                1 -> relSeek(-0.04)
                2 -> togglePlay()
                3 -> relSeek(0.04)
                4 -> relSeek(5.0)
            }
        }
        row("KF ◀", "KF ▶", "I in", "O out", "S split") { i ->
            when (i) {
                0 -> jumpKf(-1)
                1 -> jumpKf(1)
                2 -> setIn()
                3 -> setOut()
                4 -> splitAtPos()
            }
        }
        row("Esporta (E)", "Dividi file", "Undo (Z)", "Redo (Y)") { i ->
            when (i) {
                0 -> export()
                1 -> splitFiles()
                2 -> doUndo()
                3 -> doRedo()
            }
        }
        row("Estrai traccia", "Remux", "Snapshot", "Frame") { i ->
            when (i) {
                0 -> extractDialog()
                1 -> remuxDialog()
                2 -> snapshot()
                3 -> exportFramesDialog()
            }
        }
        row("Velocità", "Loop", "Timelapse", "Rotazione") { i ->
            when (i) {
                0 -> speedDialog()
                1 -> loopDialog()
                2 -> timelapse()
                3 -> rotationDialog()
            }
        }
        row("Dividi timeline", "Silenzi", "Neri/Scene", "Offset tempo") { i ->
            when (i) {
                0 -> divideDialog()
                1 -> detectSilence()
                2 -> detectBlackScenes()
                3 -> offsetDialog()
            }
        }
        row("Segmenti: lista", "Capitoli", "Progetto", "Tracce") { i ->
            when (i) {
                0 -> segmentsDialog()
                1 -> chaptersDialog()
                2 -> projectDialog()
                3 -> tracksDialog()
            }
        }
        row("Metadati", "ffmpeg log", "Scarica URL", "Unisci file") { i ->
            when (i) {
                0 -> metadataDialog()
                1 -> ffmpegLogDialog()
                2 -> downloadUrlDialog()
                3 -> openMany.launch(arrayOf("video/*", "audio/*"))
            }
        }
        row("Allega esterno", "Impostazioni") { i ->
            when (i) {
                0 -> openExternalSub.launch(arrayOf("*/*"))
                1 -> settingsDialog()
            }
        }

        segList = TextView(this).apply { text = "Nessun file aperto." }
        root.addView(segList)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(progress)
        logView = TextView(this).apply { text = "LosslessCut Android • port di mifi/lossless-cut (GPL-2.0).\n" }
        root.addView(ScrollView(this).apply { addView(logView) })

        setContentView(ScrollView(this).apply { addView(root) })

        lifecycleScope.launch {
            settings = store.load()
            timeline.removeMode = !settings.keepMode
        }
        handler.post(posUpdater)

        val shared: Uri? = try {
            if (intent?.action == android.content.Intent.ACTION_SEND) {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
            } else {
                intent?.data
            }
        } catch (_: Throwable) {
            null
        }
        if (shared != null) {
            try { openUri(shared) } catch (_: Throwable) {}
        }
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(posUpdater)
        try { player?.release() } catch (_: Throwable) {}
        player = null
        super.onDestroy()
    }

    // ---------- tastiera Chromebook (come le shortcut dell'originale) ----------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event?.action != KeyEvent.ACTION_DOWN) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> { togglePlay(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                relSeek(if (event.isShiftPressed) -0.04 else -5.0); return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                relSeek(if (event.isShiftPressed) 0.04 else 5.0); return true
            }
            KeyEvent.KEYCODE_I -> { setIn(); return true }
            KeyEvent.KEYCODE_O -> { setOut(); return true }
            KeyEvent.KEYCODE_S -> { splitAtPos(); return true }
            KeyEvent.KEYCODE_E -> { export(); return true }
            KeyEvent.KEYCODE_Z -> { doUndo(); return true }
            KeyEvent.KEYCODE_Y -> { doRedo(); return true }
            KeyEvent.KEYCODE_N -> { jumpKf(-1); return true }
            KeyEvent.KEYCODE_M -> { jumpKf(1); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------- apertura ----------

    private fun outDir(): File {
        val d = File(getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "LosslessCut")
        d.mkdirs()
        return d
    }

    private fun openUri(uri: Uri) {
        lifecycleScope.launch {
            appendLog("Apertura…\n")
            withContext(Dispatchers.IO) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Throwable) {
                }
                val doc = try { DocumentFile.fromSingleUri(applicationContext, uri) } catch (_: Throwable) { null }
                fileName = doc?.name?.takeIf { it.isNotEmpty() } ?: "video.mp4"
                val ext = fileName.substringAfterLast(".", "mp4")
                val work = File(File(cacheDir, "work").also { it.mkdirs() }, "input.$ext")
                contentResolver.openInputStream(uri)?.use { ins ->
                    work.outputStream().use { ins.copyTo(it) }
                }
                workFile = work
            }
            loadWorkFile()
        }
    }

    private fun loadWorkFile() {
        val work = workFile ?: return
        lifecycleScope.launch {
            val probe = withContext(Dispatchers.IO) {
                MediaProbe.probe(applicationContext, Uri.fromFile(work), fileName)
            }
            info = probe
            durationSec = if (probe.durationSec > 0) probe.durationSec else 60.0
            enabledTracks.clear()
            enabledTracks.addAll(probe.tracks.filter { it.kind != "subtitle" }.map { it.index })
            segments = mutableListOf(CutSegment(0.0, durationSec, name = "Full"))
            undo.reset(segments)
            pendingIn = null
            rotation = 0
            // Player
            try { player?.release() } catch (_: Throwable) {}
            player = ExoPlayer.Builder(applicationContext).build()
            playerView.player = player
            player?.setMediaItem(MediaItem.fromUri(Uri.fromFile(work)))
            player?.prepare()
            // Keyframe + waveform in background
            timeline.durationSec = durationSec
            timeline.segments = segments
            timeline.positionSec = 0.0
            refreshSegList()
            appendLog("Aperto: $fileName (${TimeUtils.format(durationSec)}, ${probe.tracks.size} tracce)\n")
            lifecycleScope.launch(Dispatchers.IO) {
                val vIdx = probe.videoTracks.firstOrNull()?.index ?: -1
                val kf = if (vIdx >= 0) {
                    KeyframeIndex.build(applicationContext, Uri.fromFile(work), vIdx)
                } else emptyList()
                val aIdx = probe.audioTracks.firstOrNull()?.index ?: -1
                val wave = if (aIdx >= 0) {
                    Analysis.waveform(applicationContext, Uri.fromFile(work), aIdx)
                } else FloatArray(0)
                withContext(Dispatchers.Main) {
                    keyframes = kf
                    timeline.keyframes = kf
                    timeline.waveform = wave
                    timeline.refresh()
                    appendLog("Keyframe: ${kf.size}, waveform: ${if (wave.isNotEmpty()) "OK" else "n/d"}\n")
                }
            }
        }
    }

    // ---------- trasporto ----------

    private fun curPos(): Double = (player?.currentPosition ?: 0) / 1000.0

    private fun relSeek(d: Double) {
        player?.let { p ->
            p.seekTo(((curPos() + d).coerceIn(0.0, durationSec) * 1000).toLong())
        }
    }

    private fun togglePlay() {
        player?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    private fun jumpKf(dir: Int) {
        val t = curPos()
        val k = if (dir > 0) KeyframeIndex.next(keyframes, t) else KeyframeIndex.prev(keyframes, t)
        if (k != null) player?.seekTo((k * 1000).toLong())
        else appendLog(if (dir > 0) "Nessun keyframe dopo\n" else "Nessun keyframe prima\n")
    }

    private fun snap(t: Double): Double {
        if (!settings.snapToKeyframe || keyframes.isEmpty()) return t
        return KeyframeIndex.snapDown(keyframes, t)
    }

    private fun setIn() {
        pendingIn = snap(curPos())
        appendLog("In: ${TimeUtils.format(pendingIn!!)} (O per chiudere)\n")
    }

    private fun setOut() {
        val s = pendingIn
        if (s == null) {
            appendLog("Prima premi I (in)\n")
            return
        }
        val e = snap(curPos())
        if (e <= s) {
            appendLog("Out deve essere dopo In\n")
            return
        }
        undo.push(segments)
        segments.add(CutSegment(s, e))
        segments = SegmentOps.sort(segments).toMutableList()
        pendingIn = null
        afterSegChange("Aggiunto segmento ${TimeUtils.format(s)} → ${TimeUtils.format(e)}\n")
    }

    private fun splitAtPos() {
        val t = snap(curPos())
        val i = segments.indexOfFirst { t > it.startSec + 0.001 && t < it.endSec - 0.001 }
        if (i < 0) {
            appendLog("Split: nessun segmento qui\n")
            return
        }
        undo.push(segments)
        val s = segments[i]
        segments[i] = s.copy(endSec = t)
        segments.add(i + 1, s.copy(startSec = t, name = if (s.name.isEmpty()) "" else s.name + "b"))
        afterSegChange("Split a ${TimeUtils.format(t)}\n")
    }

    private fun doUndo() {
        val s = undo.undo()
        if (s == null) appendLog("Niente da annullare\n")
        else {
            segments = s.toMutableList()
            afterSegChange("Undo\n")
        }
    }

    private fun doRedo() {
        val s = undo.redo()
        if (s == null) appendLog("Niente da ripetere\n")
        else {
            segments = s.toMutableList()
            afterSegChange("Redo\n")
        }
    }

    private fun afterSegChange(msg: String) {
        timeline.segments = segments
        timeline.refresh()
        refreshSegList()
        appendLog(msg)
    }

    private fun refreshSegList() {
        val b = StringBuilder()
        b.append("Modalità: ${if (settings.keepMode) "KEEP (esporta)" else "REMOVE (ritaglia via)"} • ${segments.size} segmenti\n")
        segments.forEachIndexed { i, s ->
            b.append("${i + 1}. ${TimeUtils.format(s.startSec)} → ${TimeUtils.format(s.endSec)}")
            if (s.name.isNotEmpty()) b.append(" \"${s.name}\"")
            if (s.tags.isNotEmpty()) b.append(" [${s.tags.joinToString(",")}]")
            b.append("\n")
        }
        segList.text = b.toString()
    }

    private fun effectiveSegs(): List<CutSegment> {
        val list = SegmentOps.sort(segments)
        return if (settings.keepMode) list else SegmentOps.invert(list, durationSec)
    }

    private fun requireFile(): File? {
        if (workFile == null) {
            appendLog("Apri prima un file\n")
            return null
        }
        return workFile
    }

    // ---------- operazioni ----------

    private fun export() {
        val src = requireFile() ?: return
        val eff = effectiveSegs()
        if (eff.isEmpty()) {
            appendLog("Nessun segmento da esportare\n")
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val ext = if (settings.outFormat == "webm") "webm" else "mp4"
            val out = File(outDir(), "${baseOf(fileName)}_cut.$ext")
            val r = LosslessOps.cut(src, out, eff, enabledTracks.ifEmpty { null },
                rotation, settings.outFormat) { d, t ->
                runOnUiThread { progress.max = t.toInt(); progress.progress = d.toInt() }
            }
            FfmpegLog.push(FfmpegLog.cutCmd(src, out, eff))
            withContext(Dispatchers.Main) {
                appendLog((if (r.ok) "OK: " else "ERRORE: ") + r.log + "\nffmpeg: ${FfmpegLog.last()}\n")
                writeSidecar(out)
            }
        }
    }

    private fun splitFiles() {
        val src = requireFile() ?: return
        val eff = effectiveSegs()
        if (eff.isEmpty()) {
            appendLog("Nessun segmento\n")
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val results = LosslessOps.split(src, outDir(), baseOf(fileName), eff,
                enabledTracks.ifEmpty { null }, settings.outFormat) { d, t ->
                runOnUiThread { progress.max = t; progress.progress = d }
            }
            val ok = results.count { it.ok }
            withContext(Dispatchers.Main) {
                appendLog("Split: $ok/${results.size} file in ${outDir().absolutePath}\n")
            }
        }
    }

    private fun mergeUris(uris: List<Uri>) {
        lifecycleScope.launch(Dispatchers.IO) {
            appendLog("Merge di ${uris.size} file…\n")
            val files = uris.mapIndexed { i, u ->
                val f = File(File(cacheDir, "work").also { it.mkdirs() }, "merge_$i.mp4")
                contentResolver.openInputStream(u)?.use { ins ->
                    f.outputStream().use { ins.copyTo(it) }
                }
                f
            }
            val out = File(outDir(), "merged.mp4")
            val r = LosslessOps.merge(files, out) { d, t ->
                runOnUiThread { progress.max = t.toInt(); progress.progress = d.toInt() }
            }
            FfmpegLog.push(FfmpegLog.mergeCmd(files, out))
            withContext(Dispatchers.Main) {
                appendLog((if (r.ok) "OK: " else "ERRORE: ") + r.log + "\n")
            }
        }
    }

    private fun extractDialog() {
        val tracks = info?.tracks ?: return appendLog("Apri prima un file\n")
        val names = tracks.map {
            "#${it.index} ${it.kind} ${it.mime} ${it.language} " +
                if (it.kind == "video" && it.width > 0) "${it.width}x${it.height}" else ""
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Estrai traccia (extract)")
            .setItems(names) { _, which ->
                val tr = tracks[which]
                lifecycleScope.launch(Dispatchers.IO) {
                    val ext = extFor(tr.mime)
                    val out = File(outDir(), "${baseOf(fileName)}_t${tr.index}.$ext")
                    val r = LosslessOps.extractTrack(workFile!!, out, tr.index)
                    FfmpegLog.push(FfmpegLog.extractCmd(workFile!!, tr.index, out))
                    withContext(Dispatchers.Main) {
                        appendLog((if (r.ok) "OK: " else "ERRORE: ") + r.log + "\n")
                    }
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun extFor(mime: String): String {
        val m = mime.lowercase()
        return when {
            m.contains("mp4a") || m.contains("aac") -> "aac"
            m.contains("mp3") || m.contains("mpeg") -> "mp3"
            m.contains("opus") -> "opus"
            m.contains("vorbis") -> "ogg"
            m.contains("flac") -> "flac"
            m.contains("pcm") || m.contains("wav") -> "wav"
            m.contains("avc") -> "h264"
            m.contains("hevc") || m.contains("h265") -> "h265"
            m.contains("vp9") -> "ivf"
            m.startsWith("text/") || m.contains("sub") -> "srt"
            else -> "bin"
        }
    }

    private fun remuxDialog() {
        AlertDialog.Builder(this)
            .setTitle("Remux contenitore")
            .setItems(arrayOf("mp4", "webm")) { _, which ->
                val fmt = if (which == 1) "webm" else "mp4"
                lifecycleScope.launch(Dispatchers.IO) {
                    val out = File(outDir(), "${baseOf(fileName)}_remux.$fmt")
                    val r = LosslessOps.remux(workFile!!, out, fmt, enabledTracks.ifEmpty { null })
                    FfmpegLog.push(FfmpegLog.remuxCmd(workFile!!, out))
                    withContext(Dispatchers.Main) {
                        appendLog((if (r.ok) "OK: " else "ERRORE: ") + r.log + "\n")
                    }
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun snapshot() {
        val work = requireFile() ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val bmp = Snapshots.grabFrame(applicationContext, Uri.fromFile(work), curPos())
            if (bmp == null) {
                withContext(Dispatchers.Main) { appendLog("Snapshot fallito\n") }
                return@launch
            }
            val fmt = settings.snapFormat
            val out = File(outDir(), "${baseOf(fileName)}_${"%.3f".format(curPos())}.$fmt")
            val ok = Snapshots.saveSnapshot(bmp, out, fmt, settings.snapQuality)
            FfmpegLog.push(FfmpegLog.snapshotCmd(work, curPos(), out))
            withContext(Dispatchers.Main) {
                appendLog(if (ok) "Snapshot: ${out.absolutePath}\n" else "Snapshot fallito\n")
            }
        }
    }

    private fun askTimes(title: String, vararg hints: String, cb: (List<String>) -> Unit) {
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val edits = hints.map { h ->
            EditText(this).apply { hint = h }.also { lay.addView(it) }
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(lay)
            .setPositiveButton("OK") { _, _ -> cb(edits.map { it.text.toString() }) }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun exportFramesDialog() {
        askTimes("Esporta frame", "da (sec)", "a (sec)", "ogni N sec") { v ->
            val from = TimeUtils.parse(v.getOrNull(0) ?: "") ?: 0.0
            val to = TimeUtils.parse(v.getOrNull(1) ?: "") ?: durationSec
            val step = TimeUtils.parse(v.getOrNull(2) ?: "") ?: 1.0
            lifecycleScope.launch(Dispatchers.IO) {
                val files = Snapshots.exportFrames(applicationContext, Uri.fromFile(workFile!!),
                    outDir(), baseOf(fileName), from, to, step,
                    settings.snapFormat, settings.snapQuality, true) { d, t ->
                    runOnUiThread { progress.max = t; progress.progress = d }
                }
                withContext(Dispatchers.Main) {
                    appendLog("Frame esportati: ${files.size}\n")
                }
            }
        }
    }

    private fun speedDialog() {
        askTimes("Velocità (es. 2 oppure 0.5)", "fattore") { v ->
            val f = v.getOrNull(0)?.toDoubleOrNull() ?: return@askTimes
            lifecycleScope.launch(Dispatchers.IO) {
                val out = File(outDir(), "${baseOf(fileName)}_x$f.mp4")
                val r = LosslessOps.speed(workFile!!, out, f, enabledTracks.ifEmpty { null })
                FfmpegLog.push(FfmpegLog.speedCmd(workFile!!, f, out))
                withContext(Dispatchers.Main) {
                    appendLog((if (r.ok) "OK: " else "ERRORE: ") + r.log + "\n")
                }
            }
        }
    }

    private fun loopDialog() {
        askTimes("Loop (ripeti N volte)", "volte") { v ->
            val n = v.getOrNull(0)?.toIntOrNull() ?: return@askTimes
            lifecycleScope.launch(Dispatchers.IO) {
                val out = File(outDir(), "${baseOf(fileName)}_x$n.mp4")
                val r = LosslessOps.loop(workFile!!, out, n)
                withContext(Dispatchers.Main) {
                    appendLog((if (r.ok) "OK: " else "ERRORE: ") + r.log + "\n")
                }
            }
        }
    }

    private fun timelapse() {
        lifecycleScope.launch(Dispatchers.IO) {
            val out = File(outDir(), "${baseOf(fileName)}_timelapse.mp4")
            val r = LosslessOps.timelapse(workFile!!, out, keepAudio = false)
            withContext(Dispatchers.Main) {
                appendLog((if (r.ok) "OK: " else "ERRORE: ") + r.log + " (solo keyframe)\n")
            }
        }
    }

    private fun rotationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Rotazione metadati (0/90/180/270)")
            .setItems(arrayOf("0°", "90°", "180°", "270°")) { _, which ->
                rotation = intArrayOf(0, 90, 180, 270)[which]
                appendLog("Rotazione: $rotation° (applicata al prossimo export)\n")
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun divideDialog() {
        askTimes("Dividi timeline", "lunghezza sec (vuoto=per numero)", "numero parti") { v ->
            val len = TimeUtils.parse(v.getOrNull(0) ?: "")
            val n = v.getOrNull(1)?.toIntOrNull() ?: 0
            val div = when {
                len != null && len > 0 -> SegmentOps.divideByLength(durationSec, len)
                n > 0 -> SegmentOps.divideByCount(durationSec, n)
                else -> return@askTimes
            }
            undo.push(segments)
            segments = div.toMutableList()
            afterSegChange("Timeline divisa: ${div.size} parti\n")
        }
    }

    private fun detectSilence() {
        lifecycleScope.launch(Dispatchers.IO) {
            appendLog("Analisi silenzi…\n")
            val aIdx = info?.audioTracks?.firstOrNull()?.index ?: -1
            if (aIdx < 0) {
                withContext(Dispatchers.Main) { appendLog("Nessun audio\n") }
                return@launch
            }
            var wave = timeline.waveform
            if (wave.isEmpty()) {
                wave = Analysis.waveform(applicationContext, Uri.fromFile(workFile!!), aIdx)
            }
            val found = Analysis.detectSilence(wave, durationSec)
            withContext(Dispatchers.Main) {
                if (found.isEmpty()) appendLog("Nessun silenzio trovato\n")
                else {
                    undo.push(segments)
                    segments.addAll(found)
                    segments = SegmentOps.sort(segments).toMutableList()
                    afterSegChange("Silenzi: ${found.size} segmenti aggiunti\n")
                }
            }
        }
    }

    private fun detectBlackScenes() {
        lifecycleScope.launch(Dispatchers.IO) {
            appendLog("Analisi neri/scene (campionatura 1fps)…\n")
            val (blacks, cuts) = Analysis.detectBlackAndScenes(
                applicationContext, Uri.fromFile(workFile!!), durationSec, stepSec = 1.0
            )
            withContext(Dispatchers.Main) {
                if (blacks.isEmpty() && cuts.isEmpty()) appendLog("Nulla trovato\n")
                else {
                    undo.push(segments)
                    segments.addAll(blacks)
                    if (cuts.size >= 2) {
                        val pts = listOf(0.0) + cuts + listOf(durationSec)
                        pts.zipWithNext { a, b -> CutSegment(a, b, name = "Scene") }
                            .filter { it.isValid() }
                            .forEach { segments.add(it) }
                    }
                    segments = SegmentOps.sort(segments).toMutableList()
                    afterSegChange("Neri: ${blacks.size}, tagli scena: ${cuts.size}\n")
                }
            }
        }
    }

    private fun offsetDialog() {
        askTimes("Timecode offset (sec, + o -)", "offset") { v ->
            val off = TimeUtils.parse((v.getOrNull(0) ?: "").replace("+", "")) ?: return@askTimes
            val signed = if ((v.getOrNull(0) ?: "").trim().startsWith("-")) -off else off
            undo.push(segments)
            segments = segments.map {
                it.copy(startSec = (it.startSec + signed).coerceAtLeast(0.0),
                    endSec = it.endSec + signed)
            }.filter { it.isValid() }.toMutableList()
            afterSegChange("Offset $signed s applicato\n")
        }
    }

    // ---------- segmenti / capitoli / progetto ----------

    private fun segmentsDialog() {
        askTimes("Segmento # + azione", "numero (1..N)", "azione: edit/del/up/down") { v ->
            val i = (v.getOrNull(0)?.toIntOrNull() ?: return@askTimes) - 1
            if (i !in segments.indices) {
                appendLog("Indice non valido\n")
                return@askTimes
            }
            when ((v.getOrNull(1) ?: "").lowercase()) {
                "del" -> {
                    undo.push(segments)
                    segments.removeAt(i)
                    afterSegChange("Eliminato #${i + 1}\n")
                }
                "up" -> {
                    undo.push(segments)
                    segments = SegmentOps.reorder(segments, i, (i - 1).coerceAtLeast(0)).toMutableList()
                    afterSegChange("Spostato su #${i + 1}\n")
                }
                "down" -> {
                    undo.push(segments)
                    segments = SegmentOps.reorder(segments, i, (i + 1).coerceAtMost(segments.size - 1)).toMutableList()
                    afterSegChange("Spostato giù #${i + 1}\n")
                }
                else -> editSegmentDialog(i)
            }
        }
    }

    private fun editSegmentDialog(i: Int) {
        val s = segments[i]
        askTimes("Modifica #${i + 1} (tempi: SS / MM:SS / HH:MM:SS)",
            "in (${TimeUtils.format(s.startSec)})",
            "out (${TimeUtils.format(s.endSec)})",
            "nome", "tag (a,b)") { v ->
            val ns = v.getOrNull(0)?.takeIf { it.isNotEmpty() }?.let { TimeUtils.parse(it) } ?: s.startSec
            val ne = v.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { TimeUtils.parse(it) } ?: s.endSec
            val nn = v.getOrNull(2) ?: s.name
            val nt = v.getOrNull(3)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: s.tags
            val upd = CutSegment(ns, ne, nn, nt)
            if (!upd.isValid()) {
                appendLog("Tempi non validi\n")
                return@askTimes
            }
            undo.push(segments)
            segments[i] = upd
            segments = SegmentOps.sort(segments).toMutableList()
            afterSegChange("Modificato #${i + 1}\n")
        }
    }

    private fun chaptersDialog() {
        AlertDialog.Builder(this)
            .setTitle("Capitoli / EDL")
            .setItems(arrayOf(
                "Importa CSV", "Importa CUE/YouTube (testo)", "Importa progetto JSON",
                "Esporta CSV", "Esporta CUE", "Esporta YouTube", "Esporta JSON"
            )) { _, which ->
                when (which) {
                    0 -> { pendingImportFormat = "csv"; openText.launch(arrayOf("text/*")) }
                    1 -> { pendingImportFormat = "yt"; openText.launch(arrayOf("text/*")) }
                    2 -> openProject.launch(arrayOf("application/json"))
                    3 -> exportText("csv", ChapterIO.toCsv(segments))
                    4 -> exportText("cue", ChapterIO.toCue(fileName, segments))
                    5 -> exportText("yt.txt", ChapterIO.toYouTube(segments))
                    6 -> exportText("llc.json", ChapterIO.toProjectJson(fileName, durationSec, segments))
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun exportText(ext: String, content: String) {
        try {
            val f = File(outDir(), "${baseOf(fileName)}_chapters.$ext")
            f.writeText(content)
            appendLog("Esportato: ${f.absolutePath}\n")
        } catch (t: Throwable) {
            appendLog("Export fallito: ${t.message}\n")
        }
    }

    private fun importChapters(uri: Uri, format: String) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return
            val list = when (format) {
                "csv" -> ChapterIO.fromCsv(text)
                else -> ChapterIO.fromYouTube(text).ifEmpty { ChapterIO.fromCsv(text) }
            }
            if (list.isEmpty()) appendLog("Nessun capitolo riconosciuto\n")
            else {
                undo.push(segments)
                segments = list.toMutableList()
                afterSegChange("Importati ${list.size} capitoli\n")
            }
        } catch (t: Throwable) {
            appendLog("Import fallito: ${t.message}\n")
        }
    }

    private fun projectDialog() {
        AlertDialog.Builder(this)
            .setTitle("Progetto (segmenti salvati)")
            .setItems(arrayOf("Salva progetto", "Carica progetto")) { _, which ->
                if (which == 0) {
                    try {
                        val f = File(outDir(), "${baseOf(fileName)}.llc.json")
                        f.writeText(ChapterIO.toProjectJson(fileName, durationSec, segments))
                        appendLog("Progetto: ${f.absolutePath}\n")
                    } catch (t: Throwable) {
                        appendLog("Salvataggio fallito: ${t.message}\n")
                    }
                } else openProject.launch(arrayOf("application/json"))
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun loadProject(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return
            val list = ChapterIO.fromProjectJson(text)
            if (list.isEmpty()) appendLog("Progetto vuoto/non valido\n")
            else {
                undo.push(segments)
                segments = list.toMutableList()
                afterSegChange("Progetto caricato: ${list.size} segmenti\n")
            }
        } catch (t: Throwable) {
            appendLog("Caricamento fallito: ${t.message}\n")
        }
    }

    private fun tracksDialog() {
        val tracks = info?.tracks ?: return appendLog("Apri prima un file\n")
        val names = tracks.map {
            "${if (it.index in enabledTracks) "[x]" else "[ ]"} #${it.index} ${it.kind} ${it.mime} ${it.language}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Tracce in output (disposition)")
            .setItems(names) { _, which ->
                val idx = tracks[which].index
                if (idx in enabledTracks) enabledTracks.remove(idx) else enabledTracks.add(idx)
                appendLog("Traccia $idx: ${if (idx in enabledTracks) "inclusa" else "esclusa"}\n")
            }
            .setPositiveButton("Chiudi", null)
            .show()
    }

    private fun metadataDialog() {
        val b = StringBuilder()
        info?.tracks?.forEach { t ->
            b.append("#${t.index} ${t.kind} ${t.mime} lang=${t.language}")
            if (t.width > 0) b.append(" ${t.width}x${t.height}")
            if (t.sampleRate > 0) b.append(" ${t.sampleRate}Hz/${t.channelCount}ch")
            if (t.bitrate > 0) b.append(" ${t.bitrate / 1000}kbps")
            if (t.rotation != 0) b.append(" rot=${t.rotation}")
            b.append("\n")
        }
        askTimes("Metadati (sidecar JSON all'export)\n$b", "titolo", "autore") { v ->
            metaTitle = v.getOrNull(0) ?: metaTitle
            metaAuthor = v.getOrNull(1) ?: metaAuthor
            appendLog("Metadati: titolo=\"$metaTitle\" autore=\"$metaAuthor\"\n")
        }
    }

    private fun writeSidecar(out: File) {
        if (metaTitle.isEmpty() && metaAuthor.isEmpty()) return
        try {
            File(out.parent, out.nameWithoutExtension + ".meta.json").writeText(
                "{\"title\":\"${metaTitle}\",\"author\":\"${metaAuthor}\",\"rotation\":$rotation}"
            )
        } catch (_: Throwable) {
        }
    }

    private fun ffmpegLogDialog() {
        val items = FfmpegLog.all().toTypedArray()
        if (items.isEmpty()) {
            appendLog("ffmpeg log vuoto: fai prima un'operazione\n")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("ffmpeg — ultimi comandi (come l'originale)")
            .setItems(items) { _, _ -> }
            .setPositiveButton("Chiudi", null)
            .show()
    }

    private fun downloadUrlDialog() {
        askTimes("Scarica video via HTTP (come l'originale: es. HLS)", "URL") { v ->
            val url = v.getOrNull(0)?.trim().orEmpty()
            if (!url.startsWith("http")) {
                appendLog("URL non valido\n")
                return@askTimes
            }
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    appendLog("Download remoto…\n")
                    val client = OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(100, TimeUnit.SECONDS)
                        .build()
                    val req = Request.Builder().url(url).build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                        val name = url.substringAfterLast("/").substringBefore("?")
                            .ifEmpty { "remote.mp4" }
                        val ext = name.substringAfterLast(".", "mp4")
                        val f = File(File(cacheDir, "work").also { it.mkdirs() }, "dl.$ext")
                        resp.body?.byteStream()?.use { ins ->
                            f.outputStream().use { ins.copyTo(it) }
                        }
                        FfmpegLog.push(FfmpegLog.downloadCmd(url, f))
                        workFile = f
                        fileName = name
                        withContext(Dispatchers.Main) { loadWorkFile() }
                    }
                } catch (t: Throwable) {
                    withContext(Dispatchers.Main) { appendLog("Download fallito: ${t.message}\n") }
                }
            }
        }
    }

    private fun attachExternalFile(uri: Uri) {
        // Come "combine tracks from multiple files": copia accanto all'output + log.
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val doc = try { DocumentFile.fromSingleUri(applicationContext, uri) } catch (_: Throwable) { null }
                val name = doc?.name?.takeIf { it.isNotEmpty() } ?: "external.bin"
                val dst = File(outDir(), name)
                contentResolver.openInputStream(uri)?.use { ins ->
                    dst.outputStream().use { ins.copyTo(it) }
                }
                withContext(Dispatchers.Main) {
                    appendLog("Allegato: ${dst.absolutePath}\n" +
                        "(uniscilo in export con Merge, come 'combine tracks' dell'originale)\n")
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { appendLog("Allegato fallito: ${t.message}\n") }
            }
        }
    }

    private fun settingsDialog() {
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val fmt = EditText(this).apply { hint = "formato output mp4|webm [${settings.outFormat}]" }
        val qual = EditText(this).apply { hint = "qualità snapshot 1-100 [${settings.snapQuality}]" }
        val snapF = EditText(this).apply { hint = "snapshot jpg|png [${settings.snapFormat}]" }
        val snapKf = CheckBox(this).apply { text = "snap ai keyframe"; isChecked = settings.snapToKeyframe }
        val keep = CheckBox(this).apply { text = "modalità KEEP (off=REMOVE)"; isChecked = settings.keepMode }
        lay.addView(fmt); lay.addView(qual); lay.addView(snapF); lay.addView(snapKf); lay.addView(keep)
        AlertDialog.Builder(this)
            .setTitle("Impostazioni")
            .setView(lay)
            .setPositiveButton("Salva") { _, _ ->
                val f = fmt.text.toString().trim().lowercase()
                settings = settings.copy(
                    outFormat = if (f == "webm") "webm" else "mp4",
                    snapQuality = qual.text.toString().toIntOrNull()?.coerceIn(1, 100) ?: settings.snapQuality,
                    snapFormat = snapF.text.toString().trim().lowercase()
                        .let { if (it == "png") "png" else "jpg" },
                    snapToKeyframe = snapKf.isChecked,
                    keepMode = keep.isChecked
                )
                timeline.removeMode = !settings.keepMode
                timeline.refresh()
                refreshSegList()
                lifecycleScope.launch { store.save(settings) }
                appendLog("Impostazioni salvate\n")
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    // ---------- util ----------

    private fun baseOf(name: String): String {
        val b = name.substringBeforeLast(".", name).ifEmpty { "video" }
        return b.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    private fun appendLog(s: String) {
        runOnUiThread { logView.append(s) }
    }
}
