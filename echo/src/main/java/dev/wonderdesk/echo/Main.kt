package dev.wonderdesk.echo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import dev.wonderdesk.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

class MainActivity : WonderActivity() {
    @Composable override fun Content() = EchoApp()
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); incoming.value = intent }
    companion object { val incoming = mutableStateOf<Intent?>(null) }
}

private val APP_NEEDS by lazy { listOf(
    WonderNeed("Microphone", "Echo records your voice and instruments with it", permissions = listOf(Manifest.permission.RECORD_AUDIO)),
) }

private const val ABOUT = "Echo records and cleans up voice and sound. Press Studio Voice for clean, natural narration, then trim, repair, change the tone, " +
    "add a metronome while you record, and save as WAV, FLAC, AAC or OGG. Every edit can be undone and the original recording is always kept."

internal fun Context.ep() = getSharedPreferences("echo", Context.MODE_PRIVATE)

@Composable
private fun rememberPrefInt(k: String, d: Int): MutableIntState {
    val ep = LocalContext.current.ep(); val st = remember { mutableIntStateOf(ep.getInt(k, d)) }
    LaunchedEffect(st.intValue) { ep.edit().putInt(k, st.intValue).apply() }; return st
}
@Composable
private fun rememberPrefBool(k: String, d: Boolean): MutableState<Boolean> {
    val ep = LocalContext.current.ep(); val st = remember { mutableStateOf(ep.getBoolean(k, d)) }
    LaunchedEffect(st.value) { ep.edit().putBoolean(k, st.value).apply() }; return st
}

@Composable
fun EchoApp() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<Project>?>(null) }
    var editing by remember { mutableStateOf<Project?>(null) }
    var tab by rememberSaveable { mutableStateOf("projects") }
    var importing by remember { mutableStateOf<Triple<String, Float?, String>?>(null) }
    val toastRef = remember { mutableStateOf<(String) -> Unit>({}) }
    fun reload() { scope.launch { projects = withContext(Dispatchers.IO) { Project.all(ctx) } } }
    LaunchedEffect(editing) { if (editing == null) reload() }

    fun importAudio(uri: Uri) {
        val name = ctx.displayName(uri).substringBeforeLast('.')
        scope.launch {
            importing = Triple("Reading $name", null, "Decoding the audio so it can be edited")
            val res = withContext(Dispatchers.IO) { runCatching {
                val clip = AudioIo.load(ctx, uri) { pr -> importing = Triple("Reading $name", pr, "Decoding the audio so it can be edited") }
                val p = Project.create(ctx, name)
                importing = Triple("Saving $name", null, "Keeping the original safe")
                AudioIo.writeWav(clip, p.original)
                p
            } }
            importing = null
            res.onSuccess { editing = it }.onFailure { toastRef.value("Couldn't open that audio: ${it.message}") }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) importAudio(u) }
    val incoming by MainActivity.incoming
    LaunchedEffect(incoming) {
        val i = incoming ?: (ctx as? android.app.Activity)?.intent
        val u: Uri? = i?.data ?: (i?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        MainActivity.incoming.value = null
        if (u != null && (i?.action == Intent.ACTION_VIEW || i?.action == Intent.ACTION_EDIT || i?.action == Intent.ACTION_SEND)) { (ctx as? android.app.Activity)?.intent = Intent(); importAudio(u) }
    }

    editing?.let { p -> WonderKitTheme { EditorScreen(p, onClose = { editing = null }) }; return }
    importing?.let { (step, progress, detail) -> WonderApp(appName = "Echo", about = ABOUT, title = "Echo") { LoadingPane(step, progress = progress, detail = detail) }; return }

    WonderApp(needs = APP_NEEDS, appName = "Echo", about = ABOUT,
        tabs = listOf(WonderTab("projects", "Recordings", WonderIcons.MusicNote), WonderTab("record", "Record", WonderIcons.Mic)),
        selectedTab = tab, onTab = { tab = it }, title = "Echo",
        actions = { ToolbarButton(WonderIcons.FolderOpen, "Open an audio file", { pick.launch(arrayOf("audio/*")) }) },
        settings = { EchoSettings() }) {
        toastRef.value = LocalToast.current
        if (tab == "record") RecordPanel(onDone = { p -> if (ctx.ep().getBoolean("autoOpen", true)) editing = p; tab = "projects"; reload() })
        else ProjectList(projects, onOpen = { editing = it }, onImport = { pick.launch(arrayOf("audio/*")) }, onRecord = { tab = "record" }, onChanged = { reload() })
    }
}

@Composable
private fun ProjectList(list: List<Project>?, onOpen: (Project) -> Unit, onImport: () -> Unit, onRecord: () -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val p = wonderPalette()
    if (list == null) { LoadingPane("Finding your recordings", detail = "Reading the projects saved in Echo"); return }
    if (list.isEmpty()) {
        EmptyState("Record or open something", "Record your voice, or open any audio file, then tap Studio Voice to clean it up. The original is always kept.", icon = WonderIcons.Mic,
            action = { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { WButton("Record", onRecord, kind = ButtonKind.Primary, icon = WonderIcons.Mic); WButton("Open a file", onImport, icon = WonderIcons.FolderOpen) } })
        return
    }
    var menu by remember { mutableStateOf<Project?>(null) }
    var rename by remember { mutableStateOf<Project?>(null) }
    var delete by remember { mutableStateOf<Project?>(null) }
    rename?.let { pr -> InputDialog("Rename", "Name", initial = pr.name, confirm = "Rename", onDismiss = { rename = null }) { n -> pr.name = n; pr.save(); rename = null; onChanged() } }
    delete?.let { pr -> WonderDialog(onDismissRequest = { delete = null }, title = { Text("Delete this recording?") }, text = { Text("\"${pr.name}\" and its original are removed from Echo. Files you saved elsewhere are not touched.") },
        confirmButton = { TextButton(onClick = { pr.dir.deleteRecursively(); delete = null; onChanged() }) { Text("Delete", color = p.danger) } }, dismissButton = { TextButton(onClick = { delete = null }) { Text("Keep") } }) }
    var q by rememberSaveable { mutableStateOf("") }
    val said = remember(list) { list.associate { it.id to Transcript.text(it).lowercase() } }
    val shown = if (q.isBlank()) list else list.filter { it.name.contains(q, true) || said[it.id]?.contains(q.trim().lowercase()) == true }
    Column(Modifier.fillMaxSize()) {
    if (list.size > 1) Box(Modifier.padding(16.dp, 12.dp, 16.dp, 0.dp)) { Field(q, { q = it }, "Search names and what was said") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(shown, key = { it.id }) { pr ->
            Panel {
                ListRow(pr.name, subtitle = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(pr.created)) + " · " + (if (pr.ops.isEmpty()) "Untouched" else "${pr.ops.size} edit${if (pr.ops.size == 1) "" else "s"}") +
                    " · " + formatBytes(pr.original.length()), icon = WonderIcons.MusicNote, tint = Color(0xFF1E7FE8), onClick = { onOpen(pr) },
                    trailing = { Box { IconButton(onClick = { menu = pr }) { Icon(WonderIcons.MoreVert, "More") }
                        DropdownMenu(menu?.id == pr.id, { menu = null }) {
                            DropdownMenuItem({ Text("Rename") }, { menu = null; rename = pr }, leadingIcon = { Icon(WonderIcons.Edit, null) })
                            DropdownMenuItem({ Text("Delete") }, { menu = null; delete = pr }, leadingIcon = { Icon(WonderIcons.Delete, null) })
                        } } })
            }
        }
    }
    }
}

/** Recording: level meter with clipping warning, metronome, microphone choice, pause and stop. Saved as you go, so a crash loses at most a second. */
@Composable
private fun RecordPanel(onDone: (Project) -> Unit) {
    val ctx = LocalContext.current
    val p = wonderPalette()
    val view = LocalView.current
    var rate by rememberPrefInt("rate", 48000)
    var channels by rememberPrefInt("channels", 1)
    var raw by rememberPrefBool("raw", false)
    var metro by rememberPrefBool("metro", false)
    var bpm by rememberPrefInt("bpm", 100)
    var beats by rememberPrefInt("beats", 4)
    val scope = rememberCoroutineScope()
    var countingIn by remember { mutableStateOf(false) }
    var deviceId by remember { mutableIntStateOf(-1) }
    val devices = remember { ctx.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.type != AudioDeviceInfo.TYPE_TELEPHONY } }
    var rec by remember { mutableStateOf<Recorder?>(null) }
    var project by remember { mutableStateOf<Project?>(null) }
    var peak by remember { mutableFloatStateOf(0f) }
    var level by remember { mutableFloatStateOf(0f) }
    var seconds by remember { mutableDoubleStateOf(0.0) }
    var paused by remember { mutableStateOf(false) }
    var beat by remember { mutableIntStateOf(-1) }
    val metronome = remember { Metronome() }
    val running = rec != null
    // Recording: the scene goes red and moves with your voice (louder is livelier). Paused it holds its breath; counting in, it ticks amber.
    val loud = ((level.coerceIn(0f, 1f) * 5).toInt()) / 5f
    SceneMoodEffect(when {
        countingIn -> SceneMood(hue = 38f, tint = android.graphics.Color.argb(60, 245, 175, 60), energy = 1.3f, pulse = .4f, glyphs = listOf("1", "2", "3", "4"))
        running && paused -> SceneMood(energy = .2f, hush = .35f, glyphs = listOf("⏸", "•"))
        running -> SceneMood(hue = 352f, tint = android.graphics.Color.argb(70, 230, 60, 70), energy = 1f + 2.2f * loud, pulse = .35f + .5f * loud, glyphs = listOf("♪", "●", "∿", "♫"))
        else -> SceneMood()
    })
    DisposableEffect(running) { view.keepScreenOn = running && ctx.ep().getBoolean("keepAwake", true); onDispose { view.keepScreenOn = false } }
    DisposableEffect(Unit) { onDispose { metronome.stop(); rec?.stop() } }
    LaunchedEffect(running) { while (running) { seconds = (rec?.frames ?: 0L).toDouble() / rate; delay(100) } }

    fun begin() {
        val pr = Project.create(ctx, "Recording " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date()))
        val device = devices.firstOrNull { it.id == deviceId }
        val source = if (raw && Build.VERSION.SDK_INT >= 24) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC
        val r = Recorder(pr.original, rate, channels, source, device)
        r.gain = Math.pow(10.0, ctx.ep().getInt("gainDb", 0) / 20.0).toFloat()
        val vol = ctx.ep().getInt("metroVol", 80) / 100f
        fun go() {
            r.start { pk, rms -> peak = pk; level = rms }
            if (r.failed != null) { pr.dir.deleteRecursively(); metronome.stop(); countingIn = false; return }
            project = pr; rec = r; paused = false; seconds = 0.0; countingIn = false
            if (metro && !metronome.isRunning) metronome.start(bpm, beats, vol) { b -> beat = b }
        }
        if (metro && ctx.ep().getBoolean("countIn", false)) {
            // One bar of clicks first, so you can come in on the beat; none of it is recorded.
            countingIn = true; metronome.start(bpm, beats, vol) { b -> beat = b }
            scope.launch { delay(beats * 60_000L / bpm); go() }
        } else go()
    }
    fun finish() {
        val r = rec ?: return; val pr = project ?: return
        metronome.stop(); r.stop(); rec = null; project = null; beat = -1
        if (r.frames < rate / 4) { pr.dir.deleteRecursively(); return }
        onDone(pr)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) begin() }
    fun startClicked() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) begin() else ask.launch(Manifest.permission.RECORD_AUDIO)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Panel(title = if (!running) "Ready to record" else if (paused) "Paused" else "Recording") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(clockText(seconds), style = MaterialTheme.typography.displaySmall, color = if (running && !paused) p.danger else p.text)
                // level meter: loudness bar with the loudest recent peak
                Canvas(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(11.dp)).background(Color(0x22000000))) {
                    val w = size.width
                    drawRect(Color(0xFF2E9E44), Offset.Zero, Size(w * (level * 3f).coerceIn(0f, 1f).coerceAtMost(.7f), size.height))
                    if (level * 3f > .7f) drawRect(Color(0xFFF2A516), Offset(w * .7f, 0f), Size(w * ((level * 3f).coerceIn(.7f, .9f) - .7f), size.height))
                    if (level * 3f > .9f) drawRect(Color(0xFFE5483C), Offset(w * .9f, 0f), Size(w * ((level * 3f).coerceIn(.9f, 1f) - .9f), size.height))
                    drawRect(Color.White, Offset((w * peak.coerceIn(0f, 1f) - 2f).coerceAtLeast(0f), 0f), Size(4f, size.height))
                }
                if (running && ctx.ep().getBoolean("clipWarn", true) && (rec?.clipped ?: 0) > 0) Text("The sound is too loud and is distorting. Move back from the microphone or turn the volume down.", color = p.danger, style = MaterialTheme.typography.bodySmall)
                else Text(if (running) "Speak at your normal volume. The bar should stay mostly in the green." else "Tap the button to start. Use headphones if the metronome is on so it is not picked up.", color = p.secondaryText, style = MaterialTheme.typography.bodySmall)
                if (metro && running) Text("Beat " + (if (beat >= 0) beat + 1 else 1), color = p.accent)
                if (countingIn) Text("Count-in: beat " + (if (beat >= 0) beat + 1 else 1), color = p.accent, style = MaterialTheme.typography.titleMedium)
                if (!running && !countingIn) WButton("Start recording", { startClicked() }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, icon = WonderIcons.Mic)
                else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WButton(if (paused) "Resume" else "Pause", { paused = !paused; rec?.paused = paused }, icon = if (paused) WonderIcons.PlayArrow else WonderIcons.Pause)
                    WButton("Stop and edit", { finish() }, kind = ButtonKind.Primary, icon = WonderIcons.Stop)
                }
            }
        }
        if (!running) {
            Panel(title = "Quality", footer = "Higher rates keep more detail. Raw skips the phone's own voice processing, best for music, instruments and for Studio Voice cleanup.") {
                Text("Sample rate", Modifier.padding(start = 16.dp, top = 10.dp), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(44100 to "44.1 kHz", 48000 to "48 kHz").forEach { (v, l) -> Chip(l, rate == v, { rate = v }) }
                }
                PanelDivider()
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Mono", channels == 1, { channels = 1 }); Chip("Stereo", channels == 2, { channels = 2 })
                    Chip("Phone processing", !raw, { raw = false }); Chip("Raw sound", raw, { raw = true })
                }
            }
            Panel(title = "Microphone") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    var gdb by remember { mutableIntStateOf(ctx.ep().getInt("gainDb", 0)) }
                    Text("Input boost: ${if (gdb > 0) "+" else ""}$gdb dB", style = MaterialTheme.typography.labelLarge)
                    Slider(gdb.toFloat(), { gdb = it.toInt() }, valueRange = -12f..24f, onValueChangeFinished = { ctx.ep().edit().putInt("gainDb", gdb).apply() })
                    Text("Makes a quiet microphone louder before it is recorded. Watch the meter so it does not clip.", style = MaterialTheme.typography.bodySmall, color = p.secondaryText, modifier = Modifier.padding(bottom = 8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Automatic", deviceId == -1, { deviceId = -1 })
                        devices.forEach { d -> Chip((d.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Input") + " (" + deviceKind(d.type) + ")", deviceId == d.id, { deviceId = d.id }) }
                    }
                    Text("A USB microphone shows up here when it is plugged in.", Modifier.padding(top = 6.dp), color = p.secondaryText, style = MaterialTheme.typography.bodySmall)
                }
            }
            Panel(title = "Metronome", footer = "The click plays through your speaker or headphones and is never part of the recording.") {
                SwitchRow("Play a metronome while recording", metro, { metro = it }, icon = WonderIcons.Timer, tint = Color(0xFFE39A12))
                if (metro) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row { Text("Tempo", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge); Text("$bpm BPM", color = p.accent, style = MaterialTheme.typography.labelMedium) }
                        Slider(bpm.toFloat(), { bpm = it.toInt() }, valueRange = 40f..220f)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(2, 3, 4, 6).forEach { b -> Chip("$b beats", beats == b, { beats = b }) } }
                    }
                }
            }
        }
    }
}

private fun clockText(sec: Double): String { val s = sec.toInt(); return "%d:%02d.%d".format(s / 60, s % 60, ((sec - s) * 10).toInt()) }

private fun deviceKind(t: Int) = when (t) {
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "this phone"; AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired"; AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"; else -> "input"
}

/** Everything you can change about how Echo records, edits and looks. */
@Composable
private fun EchoSettings() {
    val c = LocalContext.current; val p = wonderPalette()
    var autoOpen by rememberPrefBool("autoOpen", true); var awake by rememberPrefBool("keepAwake", true); var countIn by rememberPrefBool("countIn", false)
    var vol by rememberPrefInt("metroVol", 80); var wave by rememberPrefInt("waveColor", 0); var step by rememberPrefInt("skipSec", 5)
    var fmt by rememberPrefInt("exportFmt", 0); var preset by rememberPrefInt("presetIdx", 0); var intensity by rememberPrefInt("intensityPct", 100)
    var rate by rememberPrefInt("rate", 48000); var channels by rememberPrefInt("channels", 1); var raw by rememberPrefBool("raw", false)
    var clip by rememberPrefBool("clipWarn", true); var autoPlay by rememberPrefBool("playAfter", false); var loopSel by rememberPrefBool("loopDefault", false)
    Panel(title = "Recording") {
        SwitchRow("Open the editor when you stop", autoOpen, { autoOpen = it }, icon = WonderIcons.Edit, tint = Color(0xFF1E7FE8)); PanelDivider()
        SwitchRow("Keep the screen on while recording", awake, { awake = it }, icon = WonderIcons.Visibility, tint = Color(0xFF8E44AD)); PanelDivider()
        SwitchRow("Count in one bar before recording", countIn, { countIn = it }, subtitle = "Needs the metronome turned on in the Record tab", icon = WonderIcons.Timer, tint = Color(0xFFE39A12)); PanelDivider()
        SwitchRow("Warn when the sound is too loud", clip, { clip = it }, icon = WonderIcons.VolumeUp, tint = Color(0xFFE5483C), help = "Shows a warning when the recording clips and sounds distorted."); PanelDivider()
        Column(Modifier.padding(16.dp, 10.dp)) { Text("Metronome volume: $vol%", style = MaterialTheme.typography.labelLarge); Slider(vol.toFloat(), { vol = it.toInt() }, valueRange = 10f..100f) }
        PanelDivider()
        Column(Modifier.padding(16.dp, 10.dp)) { Text("Start with", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Chip("44.1 kHz", rate == 44100, { rate = 44100 }); Chip("48 kHz", rate == 48000, { rate = 48000 }); Chip("Mono", channels == 1, { channels = 1 }); Chip("Stereo", channels == 2, { channels = 2 }); Chip("Raw sound", raw, { raw = !raw }) } }
    }
    Panel(title = "Editing") {
        Column(Modifier.padding(16.dp, 10.dp)) { Text("Waveform colour", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Theme", "Green", "Orange", "Pink", "White").forEachIndexed { i, n -> Chip(n, wave == i, { wave = i }) } } }
        PanelDivider()
        Column(Modifier.padding(16.dp, 10.dp)) { Text("Studio Voice starts on", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Voice.presets.forEachIndexed { i, (n, _) -> Chip(n, preset == i, { preset = i }) } }
            Text("Intensity: $intensity%", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelLarge); Slider(intensity.toFloat(), { intensity = it.toInt() }, valueRange = 0f..140f) }
        PanelDivider()
        Column(Modifier.padding(16.dp, 10.dp)) { Text("Save as", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("WAV 16", "WAV 24", "WAV 32 float", "FLAC", "M4A", "OGG").forEachIndexed { i, n -> Chip(n, fmt == i, { fmt = i }) } } }
        PanelDivider()
        SwitchRow("Loop the selection while playing", loopSel, { loopSel = it }, icon = WonderIcons.Repeat, tint = Color(0xFF2E9E44)); PanelDivider()
        SwitchRow("Play straight after an edit", autoPlay, { autoPlay = it }, icon = WonderIcons.PlayArrow, tint = Color(0xFF1E7FE8))
    }
}
