package dev.wonderdesk.echo

import android.content.ContentValues
import android.content.Intent
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.wonderdesk.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.*

private fun op(type: String, label: String, build: JSONObject.() -> Unit = {}) = Op(type, JSONObject().apply(build), label)
private fun JSONObject.put(k: String, v: Float): JSONObject = put(k, v.toDouble())

@Composable
fun EditorScreen(project: Project, onClose: () -> Unit) {
    val ctx = LocalContext.current; val p = wonderPalette(); val scope = rememberCoroutineScope()
    val st = remember { EditorState(ctx, project) }
    val toast = remember { mutableStateOf<(String) -> Unit>({}) }
    var panel by rememberSaveable { mutableStateOf("studio") }
    var rename by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    fun renderAll() { scope.launch {
        st.busy = "Applying your edits…"; st.progress = null
        val c = withContext(Dispatchers.Default) { Engine.render(st.original!!, project.ops, ctx) { pr -> st.progress = pr } }
        st.current = c; st.refresh(); st.busy = null; st.progress = null; st.selA = -1; st.selB = -1; st.playhead = st.playhead.coerceAtMost(c.frames)
    } }
    LaunchedEffect(Unit) {
        val o = withContext(Dispatchers.IO) { AudioIo.readWav(project.original) } ?: run { toast.value("Couldn't open this recording"); onClose(); return@LaunchedEffect }
        st.original = o
        st.busy = "Applying your edits…"
        val c = withContext(Dispatchers.Default) { Engine.render(o, project.ops, ctx) { pr -> st.progress = pr } }
        st.current = c; st.busy = null; st.progress = null; st.fitAll(); st.refresh()
    }
    DisposableEffect(Unit) { onDispose { st.stopPlayback() } }
    BackHandler { st.stopPlayback(); onClose() }
    fun apply(o: Op) { val cur = st.current ?: return; st.stopPlayback()
        scope.launch { st.busy = o.label + "…"; st.progress = null
            val res = withContext(Dispatchers.Default) { runCatching { Engine.apply(cur, o, ctx) { pr -> st.progress = pr } } }
            res.onSuccess { project.add(o); st.current = it; st.refresh(); st.selA = -1; st.selB = -1; if (it.frames != cur.frames) st.fitAll(); if (ctx.ep().getBoolean("playAfter", false)) { st.busy = null; st.togglePlay() } }.onFailure { toast.value("Couldn't apply that: ${it.message}") }
            st.busy = null; st.progress = null } }
    val c = st.current
    val joinPick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) scope.launch {
        st.stopPlayback(); st.busy = "Reading the audio to add"; st.progress = null
        val res = withContext(Dispatchers.IO) { runCatching {
            val clip = AudioIo.load(ctx, u) { pr -> st.progress = pr }
            val f = File(project.dir, "join-" + System.currentTimeMillis() + ".wav"); AudioIo.writeWav(clip, f); f } }
        st.busy = null; st.progress = null
        res.onSuccess { f -> apply(op("join", "Adding more audio") { put("file", f.absolutePath); put("gap", 0.0) }) }.onFailure { toast.value("Couldn't open that audio: ${it.message}") }
    } }
    fun splitHere() { val cur = st.current ?: return; val at = st.playhead
        if (at < cur.rate / 10 || at > cur.frames - cur.rate / 10) { toast.value("Move the playhead into the middle of the recording first"); return }
        st.stopPlayback()
        scope.launch { st.busy = "Splitting into two recordings"; st.progress = null
            withContext(Dispatchers.IO) { val np = Project.create(ctx, project.name + " (part 2)"); AudioIo.writeWav(cur.slice(at, cur.frames), np.original) }
            st.busy = null
            apply(op("crop", "Splitting") { put("from", 0); put("to", at) })
            toast.value("Split. The second half is its own recording in your list") } }

    WonderApp(appName = "Echo", about = ABOUT, title = project.name, subtitle = c?.let { "${clock(it.seconds)} · ${it.rate / 1000.0} kHz · ${if (it.ch.size == 2) "stereo" else "mono"}" }, onBack = { st.stopPlayback(); onClose() },
        actions = {
            ToolbarButton(WonderIcons.Undo, "Undo", { st.stopPlayback(); if (project.undo()) renderAll() }, enabled = project.ops.isNotEmpty() && st.busy == null)
            ToolbarButton(WonderIcons.Redo, "Redo", { st.stopPlayback(); if (project.redoOne()) renderAll() }, enabled = project.redo.isNotEmpty() && st.busy == null)
        },
        menu = listOf(WonderMenuItem("Rename", WonderIcons.Edit) { rename = true },
            WonderMenuItem("Back to the original recording", WonderIcons.Refresh) { st.stopPlayback(); project.restoreOriginal(); renderAll() })) {
        toast.value = LocalToast.current
        if (c == null) { Busy(st); return@WonderApp }
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(190.dp)) { WaveView(st) }
            // transport
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledIconButton(onClick = { st.togglePlay() }, Modifier.size(52.dp)) { Icon(if (st.playing) WonderIcons.Pause else WonderIcons.PlayArrow, if (st.playing) "Pause" else "Play", Modifier.size(30.dp)) }
                Column(Modifier.weight(1f)) {
                    Text(clock(st.playhead.toDouble() / c.rate) + " / " + clock(c.seconds), style = MaterialTheme.typography.titleMedium)
                    Text(if (st.hasSel) "Selected ${clock((st.selB - st.selA).toDouble() / c.rate)}" else "Drag in Select mode to choose a part", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
                }
                Chip(if (st.selectMode) "Select" else "Scroll", st.selectMode, { st.selectMode = !st.selectMode })
                Chip("Loop", st.loop, { st.loop = !st.loop })
                IconButton(onClick = { st.zoom(1.6, st.viewW / 2f) }) { Icon(WonderIcons.ZoomIn, "Zoom in") }
                IconButton(onClick = { st.zoom(1 / 1.6, st.viewW / 2f) }) { Icon(WonderIcons.ZoomOut, "Zoom out") }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Select all", false, { st.selA = 0; st.selB = c.frames })
                Chip("Trim to selection", false, { if (st.hasSel) apply(op("crop", "Trimming") { put("from", st.selA); put("to", st.selB) }) else toast.value("Select a part first") }, icon = WonderIcons.Crop)
                Chip("Delete selection", false, { if (st.hasSel) apply(op("cut", "Deleting") { put("from", st.selA); put("to", st.selB) }) else toast.value("Select a part first") }, icon = WonderIcons.ContentCut)
                Chip("Silence it", false, { if (st.hasSel) apply(op("silence_range", "Silencing") { put("from", st.selA); put("to", st.selB) }) else toast.value("Select a part first") })
                Chip("Fade in", false, { apply(op("fade_in", "Fading in") { put("from", st.selFrom); put("to", if (st.hasSel) st.selB else min(c.frames, c.rate * 2)) }) })
                Chip("Fade out", false, { apply(op("fade_out", "Fading out") { put("from", if (st.hasSel) st.selA else max(0, c.frames - c.rate * 2)); put("to", st.selTo) }) })
                Chip("Louder", false, { apply(op("gain", "Gain +3 dB") { put("db", 3.0); put("from", st.selFrom); put("to", st.selTo) }) })
                Chip("Quieter", false, { apply(op("gain", "Gain −3 dB") { put("db", -3.0); put("from", st.selFrom); put("to", st.selTo) }) })
                Chip("Reverse", false, { apply(op("reverse", "Reversing") { put("from", st.selFrom); put("to", st.selTo) }) })
                Chip("Split at playhead", false, { splitHere() }, icon = WonderIcons.ContentCut)
                Chip("Add audio at the end", false, { joinPick.launch(arrayOf("audio/*")) }, icon = WonderIcons.Add)
                Chip("Spectrum", st.spectro, { st.spectro = !st.spectro; st.refresh() })
            }
            Segmented(listOf("Studio", "Repair", "Tone", "Voice", "Text", "Save"), listOf("studio", "repair", "tone", "voice", "text", "save").indexOf(panel).coerceAtLeast(0), Modifier.padding(10.dp)) { panel = listOf("studio", "repair", "tone", "voice", "text", "save")[it] }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (panel) {
                    "studio" -> StudioPanel(st, ::apply)
                    "repair" -> RepairPanel(st, ::apply)
                    "tone" -> TonePanel(st, ::apply)
                    "voice" -> VoicePanel(st, ::apply)
                    "text" -> TextPanel(st, project, ::apply)
                    else -> SavePanel(st, project, toast.value) { exporting = it }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        if (st.busy != null) Busy(st)
        if (exporting) { /* progress shown through st.busy */ }
    }
    if (rename) InputDialog("Rename", "Name", initial = project.name, confirm = "Rename", onDismiss = { rename = false }) { n -> project.name = n; project.save(); st.version++ }
}

private const val ABOUT = "Echo records and polishes sound: voice-overs, podcasts, music and instruments. Tap Studio Voice for clean, natural speech, then trim, tune and export. Everything works offline and your original is never changed."

@Composable
private fun Busy(st: EditorState) {
    WonderDialog(onDismissRequest = {}, title = { Text(st.busy ?: "Working…") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AquaProgress(st.progress, Modifier.fillMaxWidth())
            Text(st.progress?.let { "${(it * 100).toInt()}%" } ?: "This can take a moment for long recordings.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = {})
}

@Composable
private fun Slide(label: String, value: Double, onChange: (Double) -> Unit, range: ClosedFloatingPointRange<Double> = 0.0..1.0, show: (Double) -> String = { "${(it * 100).roundToInt()}%" }) {
    Column { Row { Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Text(show(value), color = wonderPalette().secondaryText, style = MaterialTheme.typography.bodySmall) }
        Slider(value.toFloat(), { onChange(it.toDouble()) }, valueRange = range.start.toFloat()..range.endInclusive.toFloat()) }
}

@Composable
private fun StudioPanel(st: EditorState, apply: (Op) -> Unit) {
    val p = wonderPalette()
    val epf = LocalContext.current.ep()
    var preset by rememberSaveable { mutableStateOf(Voice.presets.getOrElse(epf.getInt("presetIdx", 0)) { Voice.presets[0] }.first) }
    var intensity by rememberSaveable { mutableStateOf(epf.getInt("intensityPct", 100) / 100.0) }
    var advanced by remember { mutableStateOf(false) }
    var v by remember { mutableStateOf(Voice.Narration) }
    LaunchedEffect(preset) { v = Voice.presets.first { it.first == preset }.second }
    val base = v.scaled(intensity)
    Panel(title = "Studio Voice") {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("One tap for clean, natural, professional-sounding speech: it removes noise, hum, clicks and pops, tames harsh S sounds and breaths, evens out the level and sets the loudness.", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { Voice.presets.forEach { (n, _) -> Chip(n, preset == n, { preset = n }) } }
            Slide("Intensity", intensity, { intensity = it }, 0.0..1.4) { if (it < .34) "Gentle" else if (it < .8) "Balanced" else if (it < 1.1) "Full" else "Strong" }
            WButton("Studio Voice", { apply(op("voice", "Studio Voice") { val j = base.toJson(); j.keys().forEach { k -> put(k, j.get(k)) } }) }, Modifier.fillMaxWidth(), icon = WonderIcons.Mic, kind = ButtonKind.Primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(if (st.compare) "Hearing: original" else "Hearing: edited", st.compare, { st.compare = !st.compare; if (st.playing) { st.stopPlayback(); st.togglePlay() } }, icon = WonderIcons.VolumeUp)
                Chip("Fine tune", advanced, { advanced = !advanced })
            }
            Text("Tap “Hearing” while playing to compare before and after.", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
        }
    }
    if (advanced) Panel(title = "Fine tune") {
        Column(Modifier.padding(14.dp)) {
            Slide("Noise reduction", v.denoise, { v = v.copy(denoise = it) }); Slide("Voice isolation", v.isolate, { v = v.copy(isolate = it) })
            Slide("De-ess (harsh S)", v.deess, { v = v.copy(deess = it) }); Slide("Breaths", v.debreath, { v = v.copy(debreath = it) })
            Slide("Mouth clicks", v.declick, { v = v.copy(declick = it) }); Slide("Plosives (p, b pops)", v.deplosive, { v = v.copy(deplosive = it) })
            Slide("Room echo", v.dereverb, { v = v.copy(dereverb = it) }); Slide("Hum and rumble", v.dehum, { v = v.copy(dehum = it) })
            Slide("Evening out (compression)", v.compress, { v = v.copy(compress = it) }); Slide("Warmth", v.warmth, { v = v.copy(warmth = it) }); Slide("Presence", v.presence, { v = v.copy(presence = it) })
            Slide("Loudness target", v.lufs, { v = v.copy(lufs = it) }, -26.0..-10.0) { "%.0f LUFS".format(it) }
            SwitchRow("Shorten long pauses", v.silence, { v = v.copy(silence = it) })
            WButton("Apply these settings", { apply(op("voice", "Studio Voice") { val j = v.toJson(); j.keys().forEach { k -> put(k, j.get(k)) } }) }, Modifier.fillMaxWidth().padding(top = 8.dp), kind = ButtonKind.Primary)
        }
    }
}

@Composable
private fun RepairPanel(st: EditorState, apply: (Op) -> Unit) {
    val p = wonderPalette(); val toast = LocalToast.current
    var nr by rememberSaveable { mutableStateOf(.6) }; var iso by rememberSaveable { mutableStateOf(.3) }; var ds by rememberSaveable { mutableStateOf(.5) }; var rv by rememberSaveable { mutableStateOf(.4) }; var br by rememberSaveable { mutableStateOf(.5) }
    var silDb by rememberSaveable { mutableStateOf(-45.0) }; var silMax by rememberSaveable { mutableStateOf(700.0) }
    Panel(title = "Clean up") {
        Column(Modifier.padding(14.dp)) {
            Slide("Noise reduction", nr, { nr = it }); Slide("Voice isolation", iso, { iso = it }); Slide("De-ess (harsh S, SH)", ds, { ds = it }); Slide("Room echo (de-reverb)", rv, { rv = it }); Slide("Breaths", br, { br = it })
            WButton("Clean up", { apply(op("cleanup", "Cleaning up") { put("denoise", nr); put("isolate", iso); put("deess", ds); put("dereverb", rv); put("debreath", br) }) }, Modifier.fillMaxWidth().padding(top = 6.dp), kind = ButtonKind.Primary)
            Spacer(Modifier.height(6.dp))
            WButton("Learn the noise from my selection", { if (st.hasSel && st.selB - st.selA > 4096) apply(op("cleanup", "Removing that noise") { put("denoise", max(nr, .8)); put("isolate", iso); put("noiseFrom", st.selA); put("noiseTo", st.selB) }) else toast("Select a stretch of only background noise first (about a second)") }, Modifier.fillMaxWidth())
        }
    }
    Panel(title = "Fix") {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WButton("Remove clicks", { apply(op("declick", "Removing clicks") { put("strength", .6) }) }, Modifier.weight(1f))
                WButton("Soften pops", { apply(op("deplosive", "Softening pops") { put("strength", .6) }) }, Modifier.weight(1f))
            }
            WButton("Remove hum (50/60 Hz)", { apply(op("dehum", "Removing hum") { put("strength", .8) }) }, Modifier.fillMaxWidth())
            WButton("Remove DC offset", { apply(op("dc", "Centering")) }, Modifier.fillMaxWidth())
        }
    }
    Panel(title = "Pauses") {
        Column(Modifier.padding(14.dp)) {
            Slide("Counts as silence below", silDb, { silDb = it }, -70.0..-25.0) { "%.0f dB".format(it) }
            Slide("Shorten pauses longer than", silMax, { silMax = it }, 300.0..3000.0) { "%.1f s".format(it / 1000) }
            WButton("Shorten pauses", { apply(op("silence", "Shortening pauses") { put("db", silDb); put("maxMs", silMax); put("keepMs", min(400.0, silMax / 2)) }) }, Modifier.fillMaxWidth().padding(top = 6.dp))
        }
    }
}

@Composable
private fun TonePanel(st: EditorState, apply: (Op) -> Unit) {
    var low by rememberSaveable { mutableStateOf(0.0) }; var mid by rememberSaveable { mutableStateOf(0.0) }; var high by rememberSaveable { mutableStateOf(0.0) }; var hp by rememberSaveable { mutableStateOf(0.0) }
    var thr by rememberSaveable { mutableStateOf(-20.0) }; var ratio by rememberSaveable { mutableStateOf(3.0) }; var gate by rememberSaveable { mutableStateOf(-80.0) }; var make by rememberSaveable { mutableStateOf(0.0) }
    var lufs by rememberSaveable { mutableStateOf(-16.0) }
    Panel(title = "Equalizer") { Column(Modifier.padding(14.dp)) {
        Slide("Low (150 Hz)", low, { low = it }, -12.0..12.0) { "%+.1f dB".format(it) }; Slide("Mid (2.5 kHz)", mid, { mid = it }, -12.0..12.0) { "%+.1f dB".format(it) }; Slide("High (7 kHz)", high, { high = it }, -12.0..12.0) { "%+.1f dB".format(it) }
        Slide("Cut below", hp, { hp = it }, 0.0..300.0) { if (it < 20) "Off" else "%.0f Hz".format(it) }
        WButton("Apply equalizer", { apply(op("eq", "Equalizing") { put("lowDb", low); put("midDb", mid); put("highDb", high); put("hp", if (hp < 20) 0.0 else hp) }) }, Modifier.fillMaxWidth().padding(top = 6.dp), kind = ButtonKind.Primary)
    } }
    Panel(title = "Dynamics") { Column(Modifier.padding(14.dp)) {
        Slide("Noise gate below", gate, { gate = it }, -90.0..-30.0) { if (it <= -88) "Off" else "%.0f dB".format(it) }
        Slide("Compress above", thr, { thr = it }, -40.0..0.0) { "%.0f dB".format(it) }; Slide("Ratio", ratio, { ratio = it }, 1.0..10.0) { "%.1f : 1".format(it) }; Slide("Make-up gain", make, { make = it }, 0.0..12.0) { "+%.0f dB".format(it) }
        WButton("Apply dynamics", { apply(op("dynamics", "Compressing") { put("gate", gate); put("threshold", thr); put("ratio", ratio); put("makeup", make) }) }, Modifier.fillMaxWidth().padding(top = 6.dp), kind = ButtonKind.Primary)
    } }
    Panel(title = "Level") { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Slide("Loudness target", lufs, { lufs = it }, -26.0..-10.0) { "%.0f LUFS".format(it) }
        Text("Podcasts: −16 · Audiobooks: −20 · Streaming music: −14", style = MaterialTheme.typography.bodySmall, color = wonderPalette().secondaryText)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WButton("Set loudness", { apply(op("loudness", "Setting loudness") { put("lufs", lufs) }) }, Modifier.weight(1f), kind = ButtonKind.Primary)
            WButton("Normalize peak", { apply(op("normalize", "Normalizing") { put("peak", -1.0) }) }, Modifier.weight(1f))
        }
        WButton("Limit peaks at −1 dB", { apply(op("limit", "Limiting")) }, Modifier.fillMaxWidth())
    } }
}

@Composable
private fun VoicePanel(st: EditorState, apply: (Op) -> Unit) {
    var st2 by rememberSaveable { mutableStateOf(0.0) }; var speed by rememberSaveable { mutableStateOf(1.0) }
    Panel(title = "Voice changer") { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Make a voice bassier or lighter, or turn it into something else. Works on the whole recording, or select a part first.", style = MaterialTheme.typography.bodySmall, color = wonderPalette().secondaryText)
        CHANGERS.forEachIndexed { i, ch -> ListRow(ch.name, subtitle = ch.blurb, icon = WonderIcons.Mic, tint = Color(0xFFE5483C), chevron = true, onClick = { apply(op("changer", "Voice: ${ch.name}") { put("name", ch.name) }) }) }
    } }
    Panel(title = "Pitch and speed") { Column(Modifier.padding(14.dp)) {
        Slide("Pitch", st2, { st2 = it }, -12.0..12.0) { "%+.1f semitones".format(it) }
        WButton("Change pitch (keeps length)", { apply(op("pitch", "Changing pitch") { put("st", st2) }) }, Modifier.fillMaxWidth().padding(bottom = 8.dp))
        Slide("Speed", speed, { speed = it }, 0.5..2.0) { "%.2f×".format(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WButton("Speed (keeps pitch)", { apply(op("tempo", "Changing speed") { put("factor", speed) }) }, Modifier.weight(1f))
            WButton("Speed + pitch", { apply(op("speed", "Changing speed") { put("factor", speed) }) }, Modifier.weight(1f))
        }
    } }
}

@Composable
private fun SavePanel(st: EditorState, project: Project, toast: (String) -> Unit, setBusy: (Boolean) -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope(); val p = wonderPalette()
    var fmt by rememberSaveable { mutableStateOf(listOf("wav16", "wav24", "wav32", "flac", "m4a", "ogg")[ctx.ep().getInt("exportFmt", 0).coerceIn(0, 5)]) }
    var name by rememberSaveable { mutableStateOf(project.name) }
    val flacOk = remember { AudioIo.hasEncoder(MediaFormat.MIMETYPE_AUDIO_FLAC) }; val opusOk = remember { AudioIo.hasEncoder(MediaFormat.MIMETYPE_AUDIO_OPUS) }
    val formats = listOf("wav16" to "WAV 16-bit", "wav24" to "WAV 24-bit", "wav32" to "WAV 32-bit float") + (if (flacOk) listOf("flac" to "FLAC (lossless)") else emptyList()) + listOf("m4a" to "M4A (AAC)") + (if (opusOk) listOf("ogg" to "OGG (Opus)") else emptyList())
    var kbps by rememberSaveable { mutableStateOf(192) }
    fun export(share: Boolean) {
        val clip = st.current ?: return; st.stopPlayback()
        scope.launch {
            st.busy = "Exporting…"; st.progress = 0f
            val result = withContext(Dispatchers.IO) { runCatching {
                val (ext, mime) = when (fmt) { "flac" -> "flac" to "audio/flac"; "m4a" -> "m4a" to "audio/mp4"; "ogg" -> "ogg" to "audio/ogg"; else -> "wav" to "audio/wav" }
                val tmp = File(ctx.cacheDir, "export.$ext")
                when (fmt) {
                    "wav16" -> AudioIo.writeWav(clip, tmp, 16); "wav24" -> AudioIo.writeWav(clip, tmp, 24); "wav32" -> AudioIo.writeWav(clip, tmp, 32)
                    "flac" -> AudioIo.encodeFlac(clip, tmp) { st.progress = it }
                    "m4a" -> AudioIo.encodeWithMuxer(clip, tmp, MediaFormat.MIMETYPE_AUDIO_AAC, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, kbps * 1000) { st.progress = it }
                    else -> AudioIo.encodeWithMuxer(clip, tmp, MediaFormat.MIMETYPE_AUDIO_OPUS, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG, kbps * 1000) { st.progress = it }
                }
                val fileName = name.trim().ifBlank { "Echo" }.replace(Regex("[\\\\/:*?\"<>|]"), "_") + "." + ext
                val cv = ContentValues().apply { put(MediaStore.Audio.Media.DISPLAY_NAME, fileName); put(MediaStore.Audio.Media.MIME_TYPE, mime); put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Echo"); put(MediaStore.Audio.Media.IS_PENDING, 1) }
                val uri = ctx.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv)!!
                ctx.contentResolver.openOutputStream(uri)!!.use { o -> tmp.inputStream().use { it.copyTo(o) } }
                cv.clear(); cv.put(MediaStore.Audio.Media.IS_PENDING, 0); ctx.contentResolver.update(uri, cv, null, null); tmp.delete(); uri to mime
            } }
            st.busy = null; st.progress = null
            result.onSuccess { (uri, mime) -> toast("Saved to Music › Echo")
                if (share) ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share")) }
                .onFailure { toast("Couldn't export: ${it.message}") }
        }
    }
    Panel(title = "Export") { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Field(name, { name = it }, "File name")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { formats.forEach { (k, n) -> Chip(n, fmt == k, { fmt = k }) } }
        if (fmt == "m4a" || fmt == "ogg") Slide("Quality", kbps.toDouble(), { kbps = it.roundToInt() }, 64.0..320.0) { "${it.roundToInt()} kbps" }
        Text(when { fmt.startsWith("wav") -> "Uncompressed. Best for editing in other programs."; fmt == "flac" -> "Lossless and about half the size of WAV."; fmt == "m4a" -> "Small and plays everywhere."; else -> "Small and very clean sounding at low bitrates." }, style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WButton("Save", { export(false) }, Modifier.weight(1f), icon = WonderIcons.Save, kind = ButtonKind.Primary)
            WButton("Share", { export(true) }, Modifier.weight(1f), icon = WonderIcons.Share)
        }
        Text("Saved into Music › Echo, where Cinema, Memories and every music app can find it.", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
    } }
}
