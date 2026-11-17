package dev.wonderdesk.echo

import kotlin.math.*

/** Audio held in memory: one FloatArray (-1..1) per channel. Every effect here is a plain function from audio to audio. */
class Clip(val rate: Int, val ch: Array<FloatArray>) {
    val frames get() = if (ch.isEmpty()) 0 else ch[0].size
    val seconds get() = frames.toDouble() / rate
    fun copy() = Clip(rate, Array(ch.size) { ch[it].copyOf() })
    fun map(f: (FloatArray) -> FloatArray) = Clip(rate, Array(ch.size) { f(ch[it]) })
    fun slice(a: Int, b: Int) = Clip(rate, Array(ch.size) { ch[it].copyOfRange(a.coerceIn(0, frames), b.coerceIn(0, frames)) })
    fun peak(): Float { var p = 0f; for (c in ch) for (x in c) p = max(p, abs(x)); return p }
    /** Mono mix for analysis and drawing. */
    fun mono(): FloatArray = if (ch.size == 1) ch[0] else FloatArray(frames) { i -> var s = 0f; for (c in ch) s += c[i]; s / ch.size }
}

fun dbToLin(db: Double) = 10.0.pow(db / 20.0).toFloat()
fun linToDb(x: Double) = 20 * log10(max(x, 1e-9))

/* ---------------------------------------------------- biquads ---------------------------------------------------- */

class Biquad(private val b0: Double, private val b1: Double, private val b2: Double, a0: Double, private val a1: Double, private val a2: Double) {
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
    private val nb0 = b0 / a0; private val nb1 = b1 / a0; private val nb2 = b2 / a0; private val na1 = a1 / a0; private val na2 = a2 / a0
    fun run(x: Float): Float {
        val y = nb0 * x + nb1 * x1 + nb2 * x2 - na1 * y1 - na2 * y2
        x2 = x1; x1 = x.toDouble(); y2 = y1; y1 = y
        return y.toFloat()
    }
    fun process(x: FloatArray): FloatArray { val o = FloatArray(x.size); for (i in x.indices) o[i] = run(x[i]); return o }

    companion object {
        private fun w(f: Double, r: Int) = 2 * PI * (f.coerceIn(10.0, r * .49)) / r
        fun lowpass(f: Double, q: Double, r: Int): Biquad { val w = w(f, r); val al = sin(w) / (2 * q); val c = cos(w)
            return Biquad((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al) }
        fun highpass(f: Double, q: Double, r: Int): Biquad { val w = w(f, r); val al = sin(w) / (2 * q); val c = cos(w)
            return Biquad((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al) }
        fun notch(f: Double, q: Double, r: Int): Biquad { val w = w(f, r); val al = sin(w) / (2 * q); val c = cos(w)
            return Biquad(1.0, -2 * c, 1.0, 1 + al, -2 * c, 1 - al) }
        fun peaking(f: Double, q: Double, db: Double, r: Int): Biquad { val w = w(f, r); val a = 10.0.pow(db / 40); val al = sin(w) / (2 * q); val c = cos(w)
            return Biquad(1 + al * a, -2 * c, 1 - al * a, 1 + al / a, -2 * c, 1 - al / a) }
        fun lowShelf(f: Double, db: Double, r: Int): Biquad { val w = w(f, r); val a = 10.0.pow(db / 40); val c = cos(w); val al = sin(w) / 2 * sqrt(2.0); val sq = 2 * sqrt(a) * al
            return Biquad(a * ((a + 1) - (a - 1) * c + sq), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - sq), (a + 1) + (a - 1) * c + sq, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - sq) }
        fun highShelf(f: Double, db: Double, r: Int): Biquad { val w = w(f, r); val a = 10.0.pow(db / 40); val c = cos(w); val al = sin(w) / 2 * sqrt(2.0); val sq = 2 * sqrt(a) * al
            return Biquad(a * ((a + 1) + (a - 1) * c + sq), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - sq), (a + 1) - (a - 1) * c + sq, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - sq) }
    }
}

/* ------------------------------------------------------- FFT -------------------------------------------------------- */

class Fft(val n: Int) {
    private val cosT = DoubleArray(n / 2) { cos(2 * PI * it / n) }
    private val sinT = DoubleArray(n / 2) { -sin(2 * PI * it / n) }
    private val rev = IntArray(n).also { r -> val bits = Integer.numberOfTrailingZeros(n); for (i in 0 until n) r[i] = Integer.reverse(i) ushr (32 - bits) }
    fun forward(re: DoubleArray, im: DoubleArray) = run(re, im, false)
    fun inverse(re: DoubleArray, im: DoubleArray) { run(re, im, true); for (i in 0 until n) { re[i] = re[i] / n; im[i] = im[i] / n } }
    private fun run(re: DoubleArray, im: DoubleArray, inv: Boolean) {
        for (i in 0 until n) { val j = rev[i]; if (j > i) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t } }
        var len = 2
        while (len <= n) {
            val half = len / 2; val step = n / len
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val wr = cosT[k]; val wi = if (inv) -sinT[k] else sinT[k]
                    val xr = re[j + half] * wr - im[j + half] * wi; val xi = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - xr; im[j + half] = im[j] - xi; re[j] += xr; im[j] += xi
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/* ------------------------------------------------ spectral cleanup ----------------------------------------------- */

/** What the single spectral pass does. All strengths are 0..1. */
data class Cleanup(
    val denoise: Double = 0.0, val isolate: Double = 0.0, val deess: Double = 0.0, val dereverb: Double = 0.0, val debreath: Double = 0.0,
    /** A noise sample chosen by the user (mono, same rate), or null to learn the noise from the quietest parts. */
    val noiseSample: FloatArray? = null,
)

object Spectral {
    private const val HOP_DIV = 4

    /** One shot: noise reduction, voice isolation, de-ess, de-reverb and de-breath, in a single analysis/synthesis pass per channel. */
    fun process(clip: Clip, c: Cleanup, progress: (Float) -> Unit = {}): Clip {
        if (c.denoise <= 0 && c.isolate <= 0 && c.deess <= 0 && c.dereverb <= 0 && c.debreath <= 0) return clip
        val out = Array(clip.ch.size) { ci -> processChannel(clip.ch[ci], clip.rate, c) { progress((ci + it) / clip.ch.size) } }
        return Clip(clip.rate, out)
    }

    private fun frameSize(rate: Int) = if (rate >= 32000) 2048 else 1024

    private fun processChannel(x: FloatArray, rate: Int, c: Cleanup, prog: (Float) -> Unit): FloatArray {
        val n = frameSize(rate); val hop = n / HOP_DIV; val bins = n / 2 + 1
        if (x.size < n * 2) return x
        val fft = Fft(n)
        val win = DoubleArray(n) { sqrt(0.5 - 0.5 * cos(2 * PI * it / n)) }
        val frames = (x.size - n) / hop + 1
        val padded = x
        // ---- analysis pass: power spectrum of every frame (kept as floats to stay small)
        val power = Array(frames) { FloatArray(bins) }
        val re = DoubleArray(n); val im = DoubleArray(n)
        for (f in 0 until frames) {
            val o = f * hop
            for (i in 0 until n) { re[i] = padded[o + i] * win[i]; im[i] = 0.0 }
            fft.forward(re, im)
            val p = power[f]; for (k in 0 until bins) p[k] = (re[k] * re[k] + im[k] * im[k]).toFloat()
            if (f % 64 == 0) prog(f.toFloat() / frames * .3f)
        }
        // ---- noise floor per bin
        val noise = FloatArray(bins)
        val sample = c.noiseSample
        if (sample != null && sample.size >= n) {
            val cnt = (sample.size - n) / hop + 1
            for (f in 0 until cnt) {
                val o = f * hop
                for (i in 0 until n) { re[i] = sample[o + i] * win[i]; im[i] = 0.0 }
                fft.forward(re, im)
                for (k in 0 until bins) noise[k] += ((re[k] * re[k] + im[k] * im[k]) / cnt).toFloat()
            }
        } else {
            // Quietest frames carry the noise: take a low percentile of each bin over time, then smooth along frequency.
            val tmp = FloatArray(frames)
            val pick = (frames * 0.12).toInt().coerceIn(0, frames - 1)
            for (k in 0 until bins) {
                for (f in 0 until frames) tmp[f] = power[f][k]
                tmp.sort(); noise[k] = tmp[pick] * 1.6f
            }
        }
        val sm = FloatArray(bins); for (k in 0 until bins) { var s = 0f; var w = 0; for (d in -3..3) { val j = k + d; if (j in 0 until bins) { s += noise[j]; w++ } }; sm[k] = s / w }
        for (k in 0 until bins) noise[k] = sm[k] + 1e-12f
        // ---- frame level statistics for speech/breath decisions
        val lvl = FloatArray(frames) { f -> var s = 0.0; for (k in 1 until bins) s += power[f][k]; (10 * log10(s / bins + 1e-12)).toFloat() }
        val sorted = lvl.sortedArray(); val hi = sorted[(frames * 0.9).toInt().coerceIn(0, frames - 1)]; val lo = sorted[(frames * 0.1).toInt().coerceIn(0, frames - 1)]
        val flat = FloatArray(frames) { f -> // spectral flatness 400 Hz..6 kHz: breaths and hiss are flat, voiced speech is peaky
            val k0 = (400.0 / rate * n).toInt(); val k1 = min(bins - 1, (6000.0 / rate * n).toInt()); var g = 0.0; var a = 0.0
            for (k in k0..k1) { g += ln(power[f][k] + 1e-12); a += power[f][k] }
            val cnt = k1 - k0 + 1; (exp(g / cnt) / (a / cnt + 1e-12)).toFloat()
        }
        // ---- per-frame gains, smoothed in time (fast open, slow close)
        val sibLo = (4500.0 / rate * n).toInt(); val sibHi = min(bins - 1, (9500.0 / rate * n).toInt())
        val sibDb = FloatArray(frames) { f -> var s = 0.0; for (k in sibLo..sibHi) s += power[f][k]; (10 * log10(s / (sibHi - sibLo + 1) + 1e-12)).toFloat() }
        val sibSorted = sibDb.sortedArray(); val sibRef = sibSorted[(frames * 0.5).toInt().coerceIn(0, frames - 1)]
        val alpha = 1.0 + 2.2 * c.denoise; val floor = 10.0.pow(-(8.0 + 20.0 * c.denoise + 6.0 * c.isolate) / 20.0)
        val decay = 10.0.pow(-6.0 * hop / (rate * 0.45)); val gammaR = 0.6 * c.dereverb
        val rev = FloatArray(bins)
        val prevG = FloatArray(bins) { 1f }
        val ola = DoubleArray(x.size + n); val norm = DoubleArray(x.size + n)
        val gain = FloatArray(bins)
        val delayFrames = max(1, (0.05 * rate / hop).toInt())
        for (f in 0 until frames) {
            val p = power[f]
            val speech = ((lvl[f] - lo) / max(8f, hi - lo - 8f)).coerceIn(0f, 1f)
            for (k in 0 until bins) {
                var g2 = 1.0
                if (c.denoise > 0) { val snr = p[k] / noise[k]; g2 = max(floor * floor, 1.0 - alpha / max(snr, 1e-3f)) }
                if (c.dereverb > 0) {
                    val old = power[max(0, f - delayFrames)][k]
                    rev[k] = max(rev[k] * decay.toFloat(), old * 0.5f)
                    g2 *= max(0.12, 1.0 - gammaR * rev[k] / (p[k] + 1e-12f))
                }
                gain[k] = sqrt(max(g2, 0.0)).toFloat()
            }
            if (c.isolate > 0) { val g = (1.0 - c.isolate * 0.8 * (1 - speech) * (1 - speech)).toFloat(); for (k in 0 until bins) gain[k] *= g }
            if (c.deess > 0) {
                val ex = sibDb[f] - (sibRef + 5f)
                if (ex > 0) { val cut = dbToLin(-(ex * 0.85 * c.deess).coerceAtMost(12.0 * c.deess + 2)); for (k in sibLo until bins) { val t = min(1f, (k - sibLo + 1f) / 6f); gain[k] *= 1f - t * (1f - cut) } }
            }
            if (c.debreath > 0 && lvl[f] < hi - 13f && lvl[f] > lo + 3f && flat[f] > 0.22f) {
                val cut = dbToLin(-14.0 * c.debreath); for (k in 0 until bins) gain[k] *= cut
            }
            for (k in 0 until bins) {
                val smoothed = if (gain[k] > prevG[k]) prevG[k] + (gain[k] - prevG[k]) * 0.75f else prevG[k] + (gain[k] - prevG[k]) * 0.35f
                gain[k] = smoothed; prevG[k] = smoothed
            }
            // light smoothing across frequency removes "musical noise" bubbles
            val o = f * hop
            for (i in 0 until n) { re[i] = padded[o + i] * win[i]; im[i] = 0.0 }
            fft.forward(re, im)
            for (k in 0 until bins) {
                val g = if (k in 1 until bins - 1) (gain[k - 1] * 0.25f + gain[k] * 0.5f + gain[k + 1] * 0.25f) else gain[k]
                re[k] = re[k] * g; im[k] = im[k] * g; if (k in 1 until n / 2) { re[n - k] = re[k]; im[n - k] = -im[k] }
            }
            fft.inverse(re, im)
            for (i in 0 until n) { ola[o + i] += re[i] * win[i]; norm[o + i] += win[i] * win[i] }
            if (f % 64 == 0) prog(0.3f + f.toFloat() / frames * .7f)
        }
        val out = FloatArray(x.size)
        for (i in x.indices) out[i] = if (norm[i] > 1e-6) (ola[i] / norm[i]).toFloat() else x[i]
        return out
    }
}

/* --------------------------------------------------- time domain --------------------------------------------------- */

object Fx {
    fun gain(x: FloatArray, db: Double, from: Int = 0, to: Int = x.size): FloatArray {
        val g = dbToLin(db); val o = x.copyOf(); for (i in from.coerceAtLeast(0) until to.coerceAtMost(x.size)) o[i] *= g; return o
    }
    fun fade(x: FloatArray, from: Int, to: Int, fadeIn: Boolean, curve: Double = 1.0): FloatArray {
        val o = x.copyOf(); val a = from.coerceAtLeast(0); val b = to.coerceAtMost(x.size); val len = (b - a).coerceAtLeast(1)
        for (i in a until b) { val t = ((i - a).toDouble() / len).pow(curve); o[i] *= (if (fadeIn) t else 1 - t).toFloat() }
        return o
    }

    /** Clicks and mouth noises: short spikes far above their neighbourhood are replaced by a straight line across the gap. */
    fun declick(x: FloatArray, rate: Int, strength: Double): FloatArray {
        if (strength <= 0 || x.size < 64) return x
        val o = x.copyOf(); val win = (rate * 0.02).toInt().coerceAtLeast(16); val maxGap = (rate * 0.0025).toInt().coerceAtLeast(4)
        val k = 9.0 - 5.0 * strength
        val d = FloatArray(x.size) { i -> if (i < 2 || i >= x.size - 2) 0f else x[i] - (x[i - 2] + x[i + 2]) * 0.5f }
        var i = 2
        while (i < x.size - maxGap - 3) {
            // local deviation from a short window
            if (i % win == 2) { /* refresh statistic */ }
            val a = max(0, i - win); val b = min(x.size, i + win)
            var s = 0.0; for (j in a until b step 4) s += abs(d[j]); val mad = s / ((b - a) / 4 + 1) + 1e-5
            if (abs(d[i]) > k * 4 * mad && abs(d[i]) > 0.02f) {
                var e = i + 1; while (e < i + maxGap && abs(d[e]) > k * 2 * mad) e++
                val s0 = i - 1; val e0 = e + 1
                if (e0 < x.size) { for (j in s0..e0) o[j] = x[s0] + (x[e0] - x[s0]) * (j - s0).toFloat() / (e0 - s0) }
                i = e0 + 1
            } else i++
        }
        return o
    }

    /** Plosives ("p", "b" pops): the very low band is ducked only while it bursts, the rest of the voice is left alone. */
    fun deplosive(x: FloatArray, rate: Int, strength: Double): FloatArray {
        if (strength <= 0) return x
        val lp = Biquad.lowpass(140.0, 0.707, rate); val low = lp.process(x)
        val rest = FloatArray(x.size) { x[it] - low[it] }
        var env = 0f; val att = exp(-1.0 / (rate * 0.002)).toFloat(); val rel = exp(-1.0 / (rate * 0.06)).toFloat()
        val envs = FloatArray(x.size); for (i in x.indices) { val a = abs(low[i]); env = if (a > env) a + (env - a) * att else a + (env - a) * rel; envs[i] = env }
        val med = envs.filterIndexed { i, _ -> i % 64 == 0 }.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] } + 1e-4f
        val thr = med * (4.0f - 2.5f * strength.toFloat())
        val o = FloatArray(x.size); var g = 1f
        for (i in x.indices) { val tg = if (envs[i] > thr) thr / envs[i] else 1f; g = if (tg < g) tg else g + (tg - g) * 0.004f; o[i] = rest[i] + low[i] * g }
        return o
    }

    /** Mains hum: notch filters at 50 or 60 Hz (whichever is stronger) and its harmonics, plus a rumble filter. */
    fun dehum(x: FloatArray, rate: Int, strength: Double, forced: Double = 0.0): FloatArray {
        if (strength <= 0) return x
        val base = if (forced > 0) forced else detectHum(x, rate)
        var y = Biquad.highpass(60.0 + 20.0 * strength, 0.707, rate).process(x)
        var h = 1
        while (base * h < rate * 0.45 && h <= 8) { val q = 10.0 + 25.0 * strength; y = Biquad.notch(base * h, q, rate).process(y); h++ }
        return y
    }
    private fun detectHum(x: FloatArray, rate: Int): Double {
        val seg = x.copyOfRange(0, min(x.size, rate * 8)); fun e(f: Double): Double { var re = 0.0; var im = 0.0; val w = 2 * PI * f / rate; for (i in seg.indices step 2) { re += seg[i] * cos(w * i); im += seg[i] * sin(w * i) }; return re * re + im * im }
        return if (e(60.0) + e(120.0) > e(50.0) + e(100.0)) 60.0 else 50.0
    }

    class Dyn(val gateDb: Double = -90.0, val thresholdDb: Double = -20.0, val ratio: Double = 3.0, val attackMs: Double = 8.0, val releaseMs: Double = 120.0, val kneeDb: Double = 6.0, val makeupDb: Double = 0.0)

    /** Gate/expander plus compressor, linked across channels so the stereo image stays put. */
    fun dynamics(c: Clip, d: Dyn): Clip {
        val n = c.frames; val gains = FloatArray(n)
        val att = exp(-1.0 / (c.rate * d.attackMs / 1000)); val rel = exp(-1.0 / (c.rate * d.releaseMs / 1000))
        var env = 0.0; var g = 0.0; val mk = d.makeupDb
        for (i in 0 until n) {
            var a = 0f; for (ch in c.ch) a = max(a, abs(ch[i]))
            env = if (a > env) a + (env - a) * att else a + (env - a) * rel
            val lvl = linToDb(env)
            var red = 0.0
            val over = lvl - d.thresholdDb
            if (d.ratio > 1) red = when { over <= -d.kneeDb / 2 -> 0.0; over >= d.kneeDb / 2 -> over * (1 - 1 / d.ratio); else -> (over + d.kneeDb / 2).pow(2) / (2 * d.kneeDb) * (1 - 1 / d.ratio) }
            if (lvl < d.gateDb) red += (d.gateDb - lvl) * 0.6
            val target = -red + mk
            g = if (target < g) target + (g - target) * att else target + (g - target) * rel
            gains[i] = dbToLin(g)
        }
        return Clip(c.rate, Array(c.ch.size) { ci -> FloatArray(n) { i -> c.ch[ci][i] * gains[i] } })
    }

    /** Look-ahead peak limiter: never lets the output pass [ceilingDb]. */
    fun limit(c: Clip, ceilingDb: Double = -1.0): Clip {
        val n = c.frames; val ceil = dbToLin(ceilingDb); val look = (c.rate * 0.005).toInt().coerceAtLeast(8)
        val need = FloatArray(n) { 1f }
        for (i in 0 until n) { var a = 0f; for (ch in c.ch) a = max(a, abs(ch[i])); if (a > ceil) need[i] = ceil / a }
        // minimum filter over the look-ahead window, then smooth
        val g = FloatArray(n) { 1f }; val dq = ArrayDeque<Int>()
        for (i in 0 until n + look) {
            if (i < n) { while (dq.isNotEmpty() && need[dq.last()] >= need[i]) dq.removeLast(); dq.addLast(i) }
            while (dq.isNotEmpty() && dq.first() < i - look) dq.removeFirst()
            val t = i - look; if (t >= 0) g[t] = need[dq.first()]
        }
        // Trailing average of the look-ahead minimum: it ramps the gain down before a peak and back up after it, and never exceeds `need`.
        val sm = FloatArray(n); var acc = 0.0; val w = look + 1
        for (i in 0 until n) { acc += g[i]; if (i >= w) acc -= g[i - w]; sm[i] = (acc / min(i + 1, w)).toFloat() }
        return Clip(c.rate, Array(c.ch.size) { ci -> FloatArray(n) { i -> (c.ch[ci][i] * sm[i]).coerceIn(-ceil, ceil) } })
    }

    /* ------------------------------------------------------ loudness ------------------------------------------------ */

    /** Integrated loudness (ITU-R BS.1770 style: K-weighting, 400 ms blocks, absolute and relative gates), in LUFS. */
    fun loudness(c: Clip): Double {
        val rate = c.rate
        val perCh = c.ch.map { x -> Biquad.highShelf(1681.0, 4.0, rate).process(Biquad.highpass(38.0, 0.5, rate).process(x)) }
        val block = (rate * 0.4).toInt(); val step = block / 4
        if (c.frames < block) return -70.0
        val energies = ArrayList<Double>()
        var i = 0
        while (i + block <= c.frames) { var e = 0.0; for (x in perCh) { var s = 0.0; for (j in i until i + block) s += x[j] * x[j].toDouble(); e += s / block }; energies += e; i += step }
        val abs = energies.filter { -0.691 + 10 * log10(it + 1e-12) > -70 }
        if (abs.isEmpty()) return -70.0
        val rel = -0.691 + 10 * log10(abs.average()) - 10
        val gated = abs.filter { -0.691 + 10 * log10(it + 1e-12) > rel }
        return if (gated.isEmpty()) -70.0 else -0.691 + 10 * log10(gated.average())
    }

    fun normalizePeak(c: Clip, peakDb: Double): Clip { val p = c.peak(); if (p < 1e-6) return c; val g = dbToLin(peakDb) / p; return c.map { x -> FloatArray(x.size) { x[it] * g } } }

    fun normalizeLoudness(c: Clip, lufs: Double, ceilingDb: Double = -1.0): Clip {
        val cur = loudness(c); if (cur <= -69) return c
        val g = dbToLin(lufs - cur)
        return limit(c.map { x -> FloatArray(x.size) { x[it] * g } }, ceilingDb)
    }

    /* ------------------------------------------------------- repair ------------------------------------------------- */

    /** Long pauses become short ones. Returns the shortened audio. */
    fun trimSilence(c: Clip, thresholdDb: Double, maxPauseMs: Double, keepMs: Double): Clip {
        val rate = c.rate; val m = c.mono(); val win = (rate * 0.02).toInt().coerceAtLeast(8)
        val thr = dbToLin(thresholdDb); val blocks = m.size / win
        val quiet = BooleanArray(blocks) { b -> var mx = 0f; for (j in b * win until (b + 1) * win) mx = max(mx, abs(m[j])); mx < thr }
        val maxB = (maxPauseMs / 1000 * rate / win).toInt().coerceAtLeast(2); val keepB = (keepMs / 1000 * rate / win).toInt().coerceAtLeast(1)
        val keep = BooleanArray(blocks) { true }
        var b = 0
        while (b < blocks) {
            if (quiet[b]) { var e = b; while (e < blocks && quiet[e]) e++
                if (e - b > maxB) { val from = b + keepB / 2; val to = e - (keepB - keepB / 2); for (k in from until to) if (k in 0 until blocks) keep[k] = false }
                b = e } else b++
        }
        val fade = min(win, 64)
        return c.map { x ->
            val o = ArrayList<Float>(x.size); var prevKept = true
            val res = FloatArray(x.size); var w = 0
            for (bl in 0 until blocks) {
                if (keep[bl]) { for (j in bl * win until (bl + 1) * win) { var v = x[j]
                    val rel = j - bl * win; if (!prevKept && rel < fade) v *= rel.toFloat() / fade
                    res[w++] = v }
                } else if (prevKept && w > fade) { for (k in 0 until fade) res[w - 1 - k] *= k.toFloat() / fade }
                prevKept = keep[bl]
            }
            for (j in blocks * win until x.size) res[w++] = x[j]
            res.copyOf(w).also { o.clear() }
        }
    }

    /* --------------------------------------------------- pitch and speed ------------------------------------------ */

    fun resample(x: FloatArray, ratio: Double): FloatArray {
        // ratio > 1 plays faster (shorter, higher). Windowed-sinc would be ideal; cubic Hermite is plenty for voice.
        val n = (x.size / ratio).toInt(); val o = FloatArray(n)
        for (i in 0 until n) {
            val p = i * ratio; val k = p.toInt(); val t = (p - k).toFloat()
            val y0 = x[max(k - 1, 0)]; val y1 = x[min(k, x.size - 1)]; val y2 = x[min(k + 1, x.size - 1)]; val y3 = x[min(k + 2, x.size - 1)]
            o[i] = y1 + 0.5f * t * (y2 - y0 + t * (2f * y0 - 5f * y1 + 4f * y2 - y3 + t * (3f * (y1 - y2) + y3 - y0)))
        }
        return o
    }

    /** Waveform-similarity overlap-add: changes length by [stretch] (>1 is longer) without changing pitch. */
    fun stretch(x: FloatArray, rate: Int, stretch: Double): FloatArray {
        if (abs(stretch - 1.0) < 0.001 || x.size < 4096) return x
        val win = (rate * 0.04).toInt().let { it - it % 2 }.coerceAtLeast(512); val hop = win / 2; val seek = win / 6
        val outLen = (x.size * stretch).toInt(); val o = FloatArray(outLen + win); val wsum = FloatArray(outLen + win)
        val w = FloatArray(win) { (0.5 - 0.5 * cos(2 * PI * it / win)).toFloat() }
        var outPos = 0; var prevIn = 0
        while (outPos < outLen) {
            val ideal = (outPos / stretch).toInt()
            var best = ideal.coerceIn(0, max(0, x.size - win - 1))
            if (outPos > 0) {
                val target = prevIn + hop; var bestC = -Double.MAX_VALUE
                val from = max(0, ideal - seek); val to = min(x.size - win - 1, ideal + seek)
                var cand = from
                while (cand <= to) {
                    var cc = 0.0; var j = 0
                    while (j < hop) { if (target + j < x.size) cc += x[cand + j] * x[target + j]; j += 3 }
                    if (cc > bestC) { bestC = cc; best = cand }; cand += 2
                }
            }
            for (j in 0 until win) { if (best + j >= x.size || outPos + j >= o.size) break; o[outPos + j] += x[best + j] * w[j]; wsum[outPos + j] += w[j] }
            prevIn = best; outPos += hop
        }
        return FloatArray(outLen) { if (wsum[it] > 1e-3f) o[it] / wsum[it] else o[it] }
    }

    /** Pitch in semitones with the length kept (slow stretch, then resample to bring the length back). */
    fun pitch(x: FloatArray, rate: Int, semitones: Double): FloatArray {
        if (abs(semitones) < 0.01) return x
        val r = 2.0.pow(semitones / 12.0)
        return resample(stretch(x, rate, r), r).let { if (it.size > x.size) it.copyOf(x.size) else it }
    }
}
