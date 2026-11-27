package dev.wonderdesk.echo

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.wonderdesk.shared.*
import kotlin.math.*

/** Plays a Clip through an AudioTrack, tracking the position, optionally looping a range. */
class Player {
    @Volatile var pos = 0; @Volatile var playing = false
    private var thread: Thread? = null
    fun play(clip: Clip, from: Int, to: Int = clip.frames, loop: Boolean = false, onEnd: () -> Unit = {}) {
        stop(); if (clip.frames == 0) return
        playing = true; pos = from.coerceIn(0, clip.frames - 1)
        thread = Thread {
            val ch = clip.ch.size; val mask = if (ch == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val min = AudioTrack.getMinBufferSize(clip.rate, mask, AudioFormat.ENCODING_PCM_FLOAT)
            val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(clip.rate).setChannelMask(mask).build()).setBufferSizeInBytes(max(min, 8192 * ch * 4) * 2).setTransferMode(AudioTrack.MODE_STREAM).build()
            val chunk = 2048; val buf = FloatArray(chunk * ch); val end = to.coerceIn(1, clip.frames)
            try {
                track.play()
                while (playing) {
                    if (pos >= end) { if (loop) pos = from.coerceIn(0, end - 1) else break }
                    val n = min(chunk, end - pos)
                    var sum = 0.0
                    for (i in 0 until n) for (k in 0 until ch) { val v = clip.ch[k][pos + i]; buf[i * ch + k] = v; sum += v * v }
                    // The scene's waveform follows what is playing.
                    dev.wonderdesk.shared.SceneFeed.waveOn = true
                    dev.wonderdesk.shared.SceneFeed.push((kotlin.math.sqrt(sum / (n * ch).coerceAtLeast(1)) * 3.2).toFloat())
                    track.write(buf, 0, n * ch, AudioTrack.WRITE_BLOCKING); pos += n
                }
                if (playing) { /* natural end: let the buffer drain */ Thread.sleep(150) }
            } finally { runCatching { track.stop() }; track.release(); val was = playing; playing = false; dev.wonderdesk.shared.SceneFeed.waveOn = false; dev.wonderdesk.shared.SceneFeed.amp = 0f; if (was) onEnd() }
        }.also { it.start() }
    }
    fun stop() { playing = false; thread?.join(500); thread = null }
}

/** Everything the editor screen shows and changes. */
class EditorState(val ctx: Context, val project: Project) {
    var original by mutableStateOf<Clip?>(null)
    var current by mutableStateOf<Clip?>(null)
    var busy by mutableStateOf<String?>("Opening…")
    var progress by mutableStateOf<Float?>(null)
    var selA by mutableIntStateOf(-1); var selB by mutableIntStateOf(-1)
    var playhead by mutableIntStateOf(0)
    var spp by mutableDoubleStateOf(200.0); var scroll by mutableDoubleStateOf(0.0)
    var viewW by mutableIntStateOf(1000)
    var selectMode by mutableStateOf(false); var spectro by mutableStateOf(false); var compare by mutableStateOf(false)
    var loop by mutableStateOf(ctx.ep().getBoolean("loopDefault", false)); var playing by mutableStateOf(false)
    var version by mutableIntStateOf(0)
    var pyr: Pyramid? = null; var specBmp by mutableStateOf<Bitmap?>(null)
    val player = Player()
    val hasSel get() = selA >= 0 && selB > selA
    val selFrom get() = if (hasSel) selA else 0
    val selTo get() = if (hasSel) selB else (current?.frames ?: 0)

    class Pyramid(val min: FloatArray, val max: FloatArray, val block: Int)

    fun fitAll() { val n = current?.frames ?: return; spp = max(1.0, n.toDouble() / max(200, viewW)); scroll = 0.0 }
    fun refresh() {
        val c = current ?: return; val m = c.mono(); val block = 256; val nb = (m.size + block - 1) / block
        val mn = FloatArray(nb); val mx = FloatArray(nb)
        for (b in 0 until nb) { var lo = 1f; var hi = -1f; for (i in b * block until min(m.size, (b + 1) * block)) { val v = m[i]; if (v < lo) lo = v; if (v > hi) hi = v }; mn[b] = lo; mx[b] = hi }
        pyr = Pyramid(mn, mx, block); version++
        specBmp = null
        if (spectro) specBmp = spectrogram(c)
    }

    fun spectrogram(c: Clip): Bitmap {
        val n = 1024; val m = c.mono(); val cols = min(1800, max(1, m.size / 256)); val kmax = min(n / 2, (9000.0 / c.rate * n).toInt()); val fft = Fft(n)
        val bmp = Bitmap.createBitmap(cols, kmax, Bitmap.Config.ARGB_8888); val re = DoubleArray(n); val im = DoubleArray(n); val px = IntArray(cols * kmax)
        val win = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / n) }
        for (x in 0 until cols) {
            val o = (x.toLong() * (m.size - n).coerceAtLeast(1) / cols).toInt()
            for (i in 0 until n) { re[i] = if (o + i < m.size) m[o + i] * win[i] else 0.0; im[i] = 0.0 }
            fft.forward(re, im)
            for (k in 0 until kmax) { val db = 10 * log10((re[k] * re[k] + im[k] * im[k]) / n + 1e-12); val t = ((db + 95) / 70).coerceIn(0.0, 1.0).toFloat()
                val r = (255 * min(1f, t * 2f)).toInt(); val g = (255 * (t * t)).toInt(); val b = (255 * (0.35f + 0.5f * (1 - t)) * min(1f, t * 3f)).toInt()
                px[(kmax - 1 - k) * cols + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b }
        }
        bmp.setPixels(px, 0, cols, 0, 0, cols, kmax); return bmp
    }

    fun frameAt(x: Float) = (scroll + x * spp).toInt().coerceIn(0, current?.frames ?: 0)
    fun clampScroll() { val n = current?.frames ?: 0; scroll = scroll.coerceIn(0.0, max(0.0, n - viewW * spp * 0.2)) }
    fun zoom(factor: Double, focusX: Float) {
        val n = current?.frames ?: return; val f = scroll + focusX * spp
        spp = (spp / factor).coerceIn(1.0, max(2.0, n.toDouble() / 100)); scroll = f - focusX * spp; clampScroll()
    }
    fun stopPlayback() { player.stop(); playing = false }
    fun togglePlay() {
        if (playing) { stopPlayback(); return }
        val src = (if (compare) original else current) ?: return
        val from = if (hasSel) selA else playhead.let { if (it >= src.frames - 1) 0 else it }
        playing = true
        player.play(src, from, if (hasSel) selB else src.frames, loop && hasSel) { playing = false }
    }
}

/** The waveform (or spectrogram) with a playhead and a selection. Drag to scroll or select, pinch to zoom, tap to place the playhead. */
@Composable
fun WaveView(st: EditorState, modifier: Modifier = Modifier) {
    val p = wonderPalette(); val density = LocalDensity.current.density
    // follow the playhead while playing
    LaunchedEffect(st.playing) {
        while (st.playing) { st.playhead = st.player.pos
            val x = (st.playhead - st.scroll) / st.spp; if (x > st.viewW * 0.9 || x < 0) { st.scroll = (st.playhead - st.viewW * st.spp * 0.1); st.clampScroll() }
            kotlinx.coroutines.delay(33) }
    }
    val wave = when (androidx.compose.ui.platform.LocalContext.current.ep().getInt("waveColor", 0)) { 1 -> Color(0xFF3DDC84); 2 -> Color(0xFFFFA24C); 3 -> Color(0xFFFF5FA2); 4 -> Color.White; else -> p.accent }; val bg = Color(0xFF0E1622)
    Canvas(modifier.fillMaxSize().pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            st.viewW = size.width
            val handle = 30f * density
            var mode = 0; var moved = false; var anchor = 0
            if (st.hasSel) { val ax = (st.selA - st.scroll) / st.spp; val bx = (st.selB - st.scroll) / st.spp
                if (abs(down.position.x - ax) < handle) mode = 3 else if (abs(down.position.x - bx) < handle) mode = 4 }
            do {
                val ev = awaitPointerEvent(PointerEventPass.Main); val pressed = ev.changes.filter { it.pressed }
                if (pressed.size >= 2) { mode = 5; moved = true
                    val z = ev.calculateZoom(); val pan = ev.calculatePan(); val c = ev.calculateCentroid()
                    if (z != 1f) st.zoom(z.toDouble(), c.x); st.scroll -= pan.x * st.spp; st.clampScroll(); ev.changes.forEach { it.consume() }
                } else if (pressed.size == 1) { val c = pressed[0]
                    if (!moved && (c.position - down.position).getDistance() > viewConfiguration.touchSlop) { moved = true
                        if (mode == 0) { mode = if (st.selectMode) 2 else 1; if (mode == 2) anchor = st.frameAt(down.position.x) } }
                    if (moved) { val dx = c.position.x - c.previousPosition.x
                        when (mode) {
                            1 -> { st.scroll -= dx * st.spp; st.clampScroll() }
                            2 -> { val f = st.frameAt(c.position.x); st.selA = min(anchor, f); st.selB = max(anchor, f) }
                            3 -> st.selA = st.frameAt(c.position.x).coerceAtMost(st.selB - 1)
                            4 -> st.selB = st.frameAt(c.position.x).coerceAtLeast(st.selA + 1)
                        }
                        if (c.positionChanged()) c.consume() }
                }
            } while (ev.changes.any { it.pressed })
            if (!moved) { val f = st.frameAt(down.position.x); st.playhead = f; if (st.playing) { st.stopPlayback(); st.togglePlay() } else if (st.hasSel && (f < st.selA || f > st.selB)) { st.selA = -1; st.selB = -1 } }
        }
    }) {
        st.viewW = size.width.toInt(); val h = size.height; val w = size.width
        drawRect(bg)
        val c = st.current ?: return@Canvas
        val v = st.version // read so the canvas redraws when the audio changes
        val spp = st.spp; val sc = st.scroll
        if (st.spectro && st.specBmp != null) {
            val bmp = st.specBmp!!; val s0 = (sc / c.frames * bmp.width).toInt().coerceIn(0, bmp.width - 1)
            val sw = max(1, min(bmp.width - s0, (w * spp / c.frames * bmp.width).toInt()))
            drawImage(bmp.asImageBitmap(), IntOffset(s0, 0), IntSize(sw, bmp.height), IntOffset.Zero, IntSize(w.toInt(), h.toInt()))
        } else {
            val mid = h / 2f; val pyr = st.pyr; val m by lazy { c.mono() }
            drawLine(Color.White.copy(alpha = .12f), Offset(0f, mid), Offset(w, mid), 1f)
            var x = 0
            val grad = Brush.verticalGradient(listOf(wave, Color(0xFFFFA24C), wave))
            while (x < w) {
                val a = (sc + x * spp).toInt(); val b = (sc + (x + 1) * spp).toInt().coerceAtLeast(a + 1)
                if (a >= c.frames) break
                var lo = 1f; var hi = -1f
                if (spp >= 512 && pyr != null) { for (k in a / pyr.block..min(pyr.min.size - 1, b / pyr.block)) { if (pyr.min[k] < lo) lo = pyr.min[k]; if (pyr.max[k] > hi) hi = pyr.max[k] } }
                else for (i in a until min(b, c.frames)) { val s = m[i]; if (s < lo) lo = s; if (s > hi) hi = s }
                if (hi < lo) { x++; continue }
                val clip = hi >= .99f || lo <= -.99f
                drawLine(if (clip) Color(0xFFFF3B30) else wave, Offset(x.toFloat(), mid - hi.coerceAtMost(1f) * mid * .95f), Offset(x.toFloat(), mid - lo.coerceAtLeast(-1f) * mid * .95f - 1f), 1.2f)
                x++
            }
        }
        // time ruler
        val secPerPx = spp / c.rate; val step = listOf(.01, .02, .05, .1, .2, .5, 1.0, 2.0, 5.0, 10.0, 30.0, 60.0, 120.0, 300.0, 600.0).firstOrNull { it / secPerPx > 70 } ?: 600.0
        var t = ceil(sc / c.rate / step) * step
        while ((t * c.rate - sc) / spp < w) { val x = ((t * c.rate - sc) / spp).toFloat(); drawLine(Color.White.copy(alpha = .25f), Offset(x, 0f), Offset(x, 10f * density), 1f)
            drawContext.canvas.nativeCanvas.drawText(clock(t), x + 3 * density, 20f * density, android.graphics.Paint().apply { color = 0x99FFFFFF.toInt(); textSize = 10f * density; isAntiAlias = true }); t += step }
        if (st.hasSel) {
            val ax = ((st.selA - sc) / spp).toFloat(); val bx = ((st.selB - sc) / spp).toFloat()
            drawRect(Color(0x553D9BFF), Offset(ax, 0f), Size(bx - ax, h))
            for (x in listOf(ax, bx)) { drawLine(Color(0xFF6EB6FF), Offset(x, 0f), Offset(x, h), 3f); drawCircle(Color(0xFF6EB6FF), 9f * density, Offset(x, h / 2)) }
        }
        val px = ((st.playhead - sc) / spp).toFloat()
        if (px in 0f..w) { drawLine(Color.White, Offset(px, 0f), Offset(px, h), 2f); drawCircle(Color.White, 5f * density, Offset(px, 6f * density)) }
    }
}

fun clock(sec: Double): String { val ms = ((sec - floor(sec)) * 100).toInt(); val s = sec.toInt(); return if (sec < 10) "%d.%02d".format(s, ms) else "%d:%02d".format(s / 60, s % 60) }
