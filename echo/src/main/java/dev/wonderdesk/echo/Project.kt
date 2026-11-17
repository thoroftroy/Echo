package dev.wonderdesk.echo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.*

/** One thing done to the sound. Edits are kept as a list and replayed on the untouched original, so nothing is ever lost. */
data class Op(val type: String, val args: JSONObject = JSONObject(), val label: String = type) {
    fun toJson() = JSONObject().put("type", type).put("args", args).put("label", label)
    companion object { fun from(o: JSONObject) = Op(o.getString("type"), o.optJSONObject("args") ?: JSONObject(), o.optString("label", o.getString("type"))) }
}

/** Studio Voice and its presets: every number is a strength from 0 to 1 unless noted. */
data class Voice(
    val denoise: Double, val isolate: Double, val deess: Double, val debreath: Double, val declick: Double, val deplosive: Double, val dereverb: Double, val dehum: Double,
    val silence: Boolean, val compress: Double, val warmth: Double, val presence: Double, val lufs: Double,
) {
    fun scaled(k: Double) = copy(denoise = denoise * k, isolate = isolate * k, deess = deess * k, debreath = debreath * k, declick = declick * k, deplosive = deplosive * k, dereverb = dereverb * k, dehum = dehum * k, compress = compress * k)
    fun toJson() = JSONObject().put("denoise", denoise).put("isolate", isolate).put("deess", deess).put("debreath", debreath).put("declick", declick).put("deplosive", deplosive).put("dereverb", dereverb).put("dehum", dehum)
        .put("silence", silence).put("compress", compress).put("warmth", warmth).put("presence", presence).put("lufs", lufs)
    companion object {
        fun from(o: JSONObject) = Voice(o.optDouble("denoise"), o.optDouble("isolate"), o.optDouble("deess"), o.optDouble("debreath"), o.optDouble("declick"), o.optDouble("deplosive"), o.optDouble("dereverb"), o.optDouble("dehum"),
            o.optBoolean("silence"), o.optDouble("compress"), o.optDouble("warmth"), o.optDouble("presence"), o.optDouble("lufs", -16.0))
        val Narration = Voice(.7, .5, .5, .4, .5, .5, .35, .5, false, .55, .3, .4, -19.0)
        val Audiobook = Voice(.75, .5, .55, .6, .6, .5, .4, .5, true, .45, .25, .3, -20.0)
        val Podcast = Voice(.65, .45, .5, .4, .5, .5, .3, .5, true, .65, .35, .45, -16.0)
        val Dialogue = Voice(.55, .35, .4, .3, .4, .4, .25, .4, false, .5, .2, .3, -18.0)
        val General = Voice(.55, .3, .35, .2, .4, .4, .2, .4, false, .4, .2, .3, -16.0)
        val Music = Voice(.25, 0.0, 0.0, 0.0, .3, 0.0, 0.0, .5, false, .2, 0.0, 0.0, -14.0)
        val presets = listOf("Narration" to Narration, "Audiobook" to Audiobook, "Podcast" to Podcast, "Dialogue" to Dialogue, "General" to General, "Music" to Music)
    }
}

/** A voice changer preset: pitch in semitones, a gentle tone shape and speed. */
data class Changer(val name: String, val blurb: String, val semitones: Double, val bassDb: Double, val trebleDb: Double, val midDb: Double = 0.0, val tempo: Double = 1.0, val band: Boolean = false, val ring: Double = 0.0)
val CHANGERS = listOf(
    Changer("Deeper", "A fuller, lower voice", -2.5, 4.0, -1.5),
    Changer("Bassy", "More low-end warmth, same voice", 0.0, 6.0, -1.0),
    Changer("Tenor", "Lighter and brighter", 1.5, -2.0, 2.5, 1.5),
    Changer("Giant", "Very low and slow", -6.0, 5.0, -3.0),
    Changer("Chipmunk", "High and quick", 6.0, -4.0, 3.0),
    Changer("Radio", "Narrow, punchy broadcast sound", 0.0, -8.0, -4.0, 4.0, band = true),
    Changer("Telephone", "Thin phone line", 0.0, -12.0, -8.0, 5.0, band = true),
    Changer("Robot", "Metallic ring", 0.0, 0.0, 0.0, 0.0, ring = 70.0),
)

object Engine {
    fun apply(c: Clip, op: Op, ctx: Context, progress: (Float) -> Unit = {}): Clip {
        val a = op.args; val r = c.rate
        fun s(k: String, def: Int = 0): Int = a.optInt(k, def).coerceIn(0, c.frames)
        fun d(k: String, def: Double = 0.0): Double = a.optDouble(k, def)
        return when (op.type) {
            "crop" -> c.slice(s("from"), s("to", c.frames))
            "cut" -> { val f = s("from"); val t = s("to", c.frames); c.map { x -> x.copyOfRange(0, f) + x.copyOfRange(t, x.size) } }
            "silence_range" -> { val f = s("from"); val t = s("to", c.frames); c.map { x -> x.copyOf().also { java.util.Arrays.fill(it, f, t, 0f) } } }
            "gain" -> c.map { Fx.gain(it, d("db"), s("from"), s("to", c.frames)) }
            "fade_in" -> c.map { Fx.fade(it, s("from"), s("to", c.frames), true, d("curve", 1.0)) }
            "fade_out" -> c.map { Fx.fade(it, s("from"), s("to", c.frames), false, d("curve", 1.0)) }
            "reverse" -> { val f = s("from"); val t = s("to", c.frames); c.map { x -> x.copyOf().also { y -> for (i in f until t) y[i] = x[t - 1 - (i - f)] } } }
            "normalize" -> Fx.normalizePeak(c, d("peak", -1.0))
            "loudness" -> Fx.normalizeLoudness(c, d("lufs", -16.0), d("ceiling", -1.0))
            "dc" -> c.map { x -> val m = x.average().toFloat(); FloatArray(x.size) { x[it] - m } }
            "voice" -> studio(c, Voice.from(a), progress)
            "cleanup" -> Spectral.process(c, Cleanup(d("denoise"), d("isolate"), d("deess"), d("dereverb"), d("debreath")), progress)
            "declick" -> c.map { Fx.declick(it, r, d("strength", .6)) }
            "deplosive" -> c.map { Fx.deplosive(it, r, d("strength", .6)) }
            "dehum" -> c.map { Fx.dehum(it, r, d("strength", .7), d("hz", 0.0)) }
            "silence" -> Fx.trimSilence(c, d("db", -45.0), d("maxMs", 700.0), d("keepMs", 350.0))
            "eq" -> eq(c, d("lowDb"), d("midDb"), d("highDb"), d("hp"), d("lp"))
            "dynamics" -> Fx.dynamics(c, Fx.Dyn(d("gate", -90.0), d("threshold", -20.0), d("ratio", 3.0), d("attack", 8.0), d("release", 120.0), 6.0, d("makeup", 0.0)))
            "limit" -> Fx.limit(c, d("ceiling", -1.0))
            "speed" -> Clip(r, Array(c.ch.size) { Fx.resample(c.ch[it], d("factor", 1.0)) })
            "tempo" -> Clip(r, Array(c.ch.size) { Fx.stretch(c.ch[it], r, 1.0 / d("factor", 1.0)) })
            "pitch" -> Clip(r, Array(c.ch.size) { Fx.pitch(c.ch[it], r, d("st")) })
            "changer" -> change(c, CHANGERS.first { it.name == a.optString("name") })
            "join" -> { val other = AudioIo.readWav(File(a.getString("file")))!!.let { o -> if (o.rate != r) Clip(r, Array(o.ch.size) { Fx.resample(o.ch[it], o.rate.toDouble() / r) }) else o }
                val gap = (d("gap", 0.0) * r).toInt(); Clip(r, Array(c.ch.size) { k -> c.ch[k] + FloatArray(gap) + other.ch[min(k, other.ch.size - 1)] }) }
            "cut_many" -> {
                val arr = a.getJSONArray("ranges")
                val rs = (0 until arr.length()).map { arr.getJSONArray(it).let { r2 -> r2.getInt(0) to r2.getInt(1) } }.sortedBy { it.first }
                val fade = (r * 0.004).toInt()
                c.map { x -> val parts = ArrayList<FloatArray>(); var pos = 0
                    for ((f, t) in rs) { val ff = f.coerceIn(pos, x.size); if (ff > pos) parts.add(x.copyOfRange(pos, ff)); pos = maxOf(pos, t.coerceIn(0, x.size)) }
                    if (pos < x.size) parts.add(x.copyOfRange(pos, x.size))
                    // a few milliseconds of fade at each join so the cut does not click
                    parts.forEachIndexed { k, p -> for (i in 0 until minOf(fade, p.size / 2)) { val g = i / fade.toFloat(); if (k > 0) p[i] *= g; if (k < parts.size - 1) p[p.size - 1 - i] *= g } }
                    val out = FloatArray(parts.sumOf { it.size }); var o = 0; parts.forEach { System.arraycopy(it, 0, out, o, it.size); o += it.size }; out }
            }
            "mono" -> Clip(r, arrayOf(c.mono()))
            "stereo_swap" -> if (c.ch.size == 2) Clip(r, arrayOf(c.ch[1], c.ch[0])) else c
            else -> c
        }
    }

    fun eq(c: Clip, low: Double, mid: Double, high: Double, hp: Double, lp: Double): Clip = c.map { x ->
        var y = x
        if (hp > 0) y = Biquad.highpass(hp, 0.707, c.rate).process(y)
        if (lp > 0) y = Biquad.lowpass(lp, 0.707, c.rate).process(y)
        if (low != 0.0) y = Biquad.lowShelf(150.0, low, c.rate).process(y)
        if (mid != 0.0) y = Biquad.peaking(2500.0, 0.9, mid, c.rate).process(y)
        if (high != 0.0) y = Biquad.highShelf(7000.0, high, c.rate).process(y)
        y
    }

    fun change(c: Clip, p: Changer): Clip {
        var y = Clip(c.rate, Array(c.ch.size) { Fx.pitch(c.ch[it], c.rate, p.semitones) })
        if (p.band) y = y.map { x -> Biquad.lowpass(3400.0, 0.9, c.rate).process(Biquad.highpass(300.0, 0.9, c.rate).process(x)) }
        y = eq(y, p.bassDb, p.midDb, p.trebleDb, 0.0, 0.0)
        if (p.ring > 0) y = y.map { x -> FloatArray(x.size) { i -> (x[i] * (0.35f + 0.65f * sin(2 * PI * p.ring * i / c.rate).toFloat())) } }
        if (p.band) y = Fx.limit(Fx.dynamics(y, Fx.Dyn(thresholdDb = -24.0, ratio = 4.0, makeupDb = 6.0)), -1.0)
        return y
    }

    /** The one-tap chain: hum and rumble, clicks and pops, noise and room, sibilance and breaths, then tone, level and loudness. */
    fun studio(c0: Clip, v: Voice, progress: (Float) -> Unit = {}): Clip {
        var c = c0
        if (v.dehum > 0) c = c.map { Fx.dehum(it, c.rate, v.dehum) }
        if (v.declick > 0) c = c.map { Fx.declick(it, c.rate, v.declick) }
        if (v.deplosive > 0) c = c.map { Fx.deplosive(it, c.rate, v.deplosive) }
        c = Spectral.process(c, Cleanup(v.denoise, v.isolate, v.deess, v.dereverb, v.debreath), progress)
        if (v.silence) c = Fx.trimSilence(c, -48.0, 800.0, 400.0)
        if (v.warmth > 0 || v.presence > 0) c = eq(c, v.warmth * 3.0, v.presence * 3.5, v.presence * 1.5, 70.0, 0.0)
        if (v.compress > 0) c = Fx.dynamics(c, Fx.Dyn(gateDb = -70.0, thresholdDb = -24.0 + 4 * (1 - v.compress), ratio = 1.5 + 3.0 * v.compress, attackMs = 10.0, releaseMs = 140.0, kneeDb = 8.0))
        return Fx.normalizeLoudness(c, v.lufs, -1.0)
    }

    fun render(original: Clip, ops: List<Op>, ctx: Context, progress: (Float) -> Unit = {}): Clip {
        var c = original
        ops.forEachIndexed { i, op -> c = apply(c, op, ctx) { p -> progress((i + p) / ops.size) } }
        return c
    }
}

/** A recording or imported file with its edit history. */
class Project(val dir: File) {
    var name: String = "Untitled"; var created = System.currentTimeMillis(); var ops = mutableListOf<Op>(); var redo = mutableListOf<Op>()
    var note = ""
    val original get() = File(dir, "original.wav")
    val id get() = dir.name

    fun save() { dir.mkdirs(); File(dir, "project.json").writeText(JSONObject().put("name", name).put("created", created).put("note", note).put("ops", JSONArray(ops.map { it.toJson() })).put("redo", JSONArray(redo.map { it.toJson() })).toString()) }
    fun add(op: Op) { ops.add(op); redo.clear(); save() }
    fun undo(): Boolean { if (ops.isEmpty()) return false; redo.add(ops.removeAt(ops.size - 1)); save(); return true }
    fun redoOne(): Boolean { if (redo.isEmpty()) return false; ops.add(redo.removeAt(redo.size - 1)); save(); return true }
    fun restoreOriginal() { redo.clear(); ops.clear(); save() }

    companion object {
        fun root(c: Context) = File(c.filesDir, "projects").also { it.mkdirs() }
        fun create(c: Context, name: String): Project { val p = Project(File(root(c), UUID.randomUUID().toString())); p.name = name; p.dir.mkdirs(); p.save(); return p }
        fun open(dir: File): Project? = runCatching {
            val o = JSONObject(File(dir, "project.json").readText()); val p = Project(dir)
            p.name = o.optString("name", "Untitled"); p.created = o.optLong("created"); p.note = o.optString("note")
            o.optJSONArray("ops")?.let { a -> for (i in 0 until a.length()) p.ops.add(Op.from(a.getJSONObject(i))) }
            o.optJSONArray("redo")?.let { a -> for (i in 0 until a.length()) p.redo.add(Op.from(a.getJSONObject(i))) }
            p
        }.getOrNull()
        fun all(c: Context): List<Project> = root(c).listFiles()?.mapNotNull { open(it) }?.filter { it.original.exists() }?.sortedByDescending { it.created }.orEmpty()
    }
}
