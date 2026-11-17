package dev.wonderdesk.echo

import android.content.Context
import android.media.*
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Reading and writing sound: WAV by hand (so a recording that was cut short still opens), everything else through Android's codecs. */
object AudioIo {
    /* ----------------------------------------------------------- WAV ----------------------------------------------- */

    fun writeWav(c: Clip, f: File, bits: Int = 16) {
        RandomAccessFile(f, "rw").use { raf ->
            raf.setLength(0)
            val ch = c.ch.size; val bytes = bits / 8; val dataLen = c.frames.toLong() * ch * bytes
            val hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            hdr.put("RIFF".toByteArray()).putInt((36 + dataLen).toInt()).put("WAVEfmt ".toByteArray()).putInt(16).putShort(if (bits == 32) 3 else 1).putShort(ch.toShort())
                .putInt(c.rate).putInt(c.rate * ch * bytes).putShort((ch * bytes).toShort()).putShort(bits.toShort()).put("data".toByteArray()).putInt(dataLen.toInt())
            raf.write(hdr.array())
            val chunk = 16384; val buf = ByteBuffer.allocate(chunk * ch * bytes).order(ByteOrder.LITTLE_ENDIAN)
            var i = 0
            while (i < c.frames) {
                buf.clear(); val end = min(c.frames, i + chunk)
                for (j in i until end) for (k in 0 until ch) { val v = c.ch[k][j].coerceIn(-1f, 1f)
                    when (bits) { 16 -> buf.putShort((v * 32767f).roundToInt().toShort()); 24 -> { val s = (v * 8388607f).roundToInt(); buf.put(s.toByte()).put((s shr 8).toByte()).put((s shr 16).toByte()) }; else -> buf.putFloat(v) } }
                raf.write(buf.array(), 0, buf.position()); i = end
            }
        }
    }

    /** Parses a WAV file (PCM 8/16/24/32 or float). A data size of 0 or "unknown" means "to the end of the file", which is what a crashed recording leaves behind. */
    fun readWav(f: File): Clip? {
        RandomAccessFile(f, "r").use { raf ->
            if (raf.length() < 44) return null
            val head = ByteArray(12); raf.readFully(head)
            if (String(head, 0, 4) != "RIFF" || String(head, 8, 4) != "WAVE") return null
            var fmtTag = 1; var ch = 1; var rate = 44100; var bits = 16; var dataPos = -1L; var dataLen = 0L
            var pos = 12L
            while (pos + 8 <= raf.length()) {
                raf.seek(pos); val ck = ByteArray(8); raf.readFully(ck)
                val id = String(ck, 0, 4); val size = ByteBuffer.wrap(ck, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                if (id == "fmt ") { val b = ByteArray(16); raf.readFully(b); val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                    fmtTag = bb.short.toInt() and 0xFFFF; ch = bb.short.toInt(); rate = bb.int; bb.int; bb.short; bits = bb.short.toInt()
                    if (fmtTag == 0xFFFE && size >= 40) { raf.seek(pos + 8 + 24); fmtTag = raf.readUnsignedByte() or (raf.readUnsignedByte() shl 8) } }
                if (id == "data") { dataPos = pos + 8; dataLen = if (size == 0L || size == 0xFFFFFFFFL || dataPos + size > raf.length()) raf.length() - dataPos else size; break }
                pos += 8 + size + (size and 1)
            }
            if (dataPos < 0 || ch < 1 || ch > 2 || rate < 4000) return null
            val bytes = bits / 8; val frames = (dataLen / (ch * bytes)).toInt()
            val out = Array(ch) { FloatArray(frames) }
            raf.seek(dataPos)
            val chunk = 16384; val buf = ByteArray(chunk * ch * bytes); var done = 0
            while (done < frames) {
                val n = min(chunk, frames - done); raf.readFully(buf, 0, n * ch * bytes)
                val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
                for (j in 0 until n) for (k in 0 until ch) out[k][done + j] = when {
                    fmtTag == 3 && bits == 32 -> bb.float
                    bits == 8 -> ((bb.get().toInt() and 255) - 128) / 128f
                    bits == 16 -> bb.short / 32768f
                    bits == 24 -> { val a = bb.get().toInt() and 255; val b = bb.get().toInt() and 255; val c = bb.get().toInt(); ((c shl 16) or (b shl 8) or a) / 8388608f }
                    else -> bb.int / 2147483648f }
                done += n
            }
            return Clip(rate, out)
        }
    }

    /* ---------------------------------------------------- any format -------------------------------------------- */

    /** Decodes MP3, AAC, M4A, OGG, Opus, FLAC, AMR, WAV and anything else Android understands. [progress] gets 0..1. */
    fun decode(c: Context, uri: Uri, progress: (Float) -> Unit = {}): Clip {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(c, uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: error("No sound found in that file")
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track); val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            val codec = MediaCodec.createDecoderByType(mime); codec.configure(fmt, null, null, 0); codec.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var chans = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEnc = AudioFormat.ENCODING_PCM_16BIT
            var store = ShortArray(max(1, (durUs / 1_000_000.0 * rate * chans).toInt().coerceAtLeast(rate * chans))); var used = 0
            val info = MediaCodec.BufferInfo(); var inDone = false; var outDone = false
            while (!outDone) {
                if (!inDone) { val i = codec.dequeueInputBuffer(5000)
                    if (i >= 0) { val b = codec.getInputBuffer(i)!!; val n = ex.readSampleData(b, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); if (durUs > 0) progress((ex.sampleTime.toFloat() / durUs).coerceIn(0f, 1f)); ex.advance() } } }
                val o = codec.dequeueOutputBuffer(info, 5000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { val nf = codec.outputFormat; rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE); chans = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmEnc = if (nf.containsKey(MediaFormat.KEY_PCM_ENCODING)) nf.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT }
                    o >= 0 -> { val b = codec.getOutputBuffer(o)!!; b.position(info.offset); b.limit(info.offset + info.size); b.order(ByteOrder.nativeOrder())
                        if (pcmEnc == AudioFormat.ENCODING_PCM_FLOAT) { val fb = b.asFloatBuffer(); val n = fb.remaining(); if (used + n > store.size) store = store.copyOf(max(store.size * 2, used + n)); for (k in 0 until n) store[used + k] = (fb.get().coerceIn(-1f, 1f) * 32767f).toInt().toShort(); used += n }
                        else { val sb = b.asShortBuffer(); val n = sb.remaining(); if (used + n > store.size) store = store.copyOf(max(store.size * 2, used + n)); sb.get(store, used, n); used += n }
                        codec.releaseOutputBuffer(o, false); if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true }
                }
            }
            codec.stop(); codec.release()
            chans = chans.coerceIn(1, 2).let { if (chans > 2) 2 else it }
            val src = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val frames = used / src
            val out = Array(chans) { FloatArray(frames) }
            for (i in 0 until frames) for (k in 0 until chans) out[k][i] = store[i * src + k] / 32768f
            return Clip(rate, out)
        } finally { ex.release() }
    }

    /** Loads a file the best way: our own WAV reader first, then Android's decoders. */
    fun load(c: Context, uri: Uri, progress: (Float) -> Unit = {}): Clip {
        if (uri.scheme == "file") { runCatching { readWav(File(uri.path!!)) }.getOrNull()?.let { return it } }
        return decode(c, uri, progress)
    }

    /* ------------------------------------------------------ encoders ------------------------------------------- */

    fun toInterleaved16(c: Clip, from: Int, to: Int): ByteArray {
        val ch = c.ch.size; val bb = ByteBuffer.allocate((to - from) * ch * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in from until to) for (k in 0 until ch) bb.putShort((c.ch[k][i].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
        return bb.array()
    }

    fun hasEncoder(mime: String) = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } } }.getOrDefault(false)

    /** AAC in an .m4a, or Opus in an .ogg. */
    fun encodeWithMuxer(c: Clip, f: File, mime: String, muxerFormat: Int, bitrate: Int, progress: (Float) -> Unit = {}) {
        val clip = if (mime == MediaFormat.MIMETYPE_AUDIO_OPUS && c.rate != 48000) Clip(48000, Array(c.ch.size) { Fx.resample(c.ch[it], c.rate / 48000.0) }) else c
        val fmt = MediaFormat.createAudioFormat(mime, clip.rate, clip.ch.size).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate); if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 16) }
        val codec = MediaCodec.createEncoderByType(mime); codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
        val muxer = MediaMuxer(f.path, muxerFormat); var track = -1; var started = false
        val info = MediaCodec.BufferInfo(); var pos = 0; var inDone = false; var outDone = false; val ch = clip.ch.size; val perChunk = 4096
        try {
            while (!outDone) {
                if (!inDone) { val i = codec.dequeueInputBuffer(5000)
                    if (i >= 0) { val b = codec.getInputBuffer(i)!!; b.clear()
                        val end = min(clip.frames, pos + min(perChunk, b.capacity() / (2 * ch)))
                        if (pos >= clip.frames) { codec.queueInputBuffer(i, 0, 0, (pos.toLong() * 1_000_000 / clip.rate), MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { val bytes = toInterleaved16(clip, pos, end); b.put(bytes); codec.queueInputBuffer(i, 0, bytes.size, pos.toLong() * 1_000_000 / clip.rate, 0); pos = end; progress(pos.toFloat() / clip.frames) } } }
                val o = codec.dequeueOutputBuffer(info, 5000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true }
                    o >= 0 -> { val b = codec.getOutputBuffer(o)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && started) { b.position(info.offset); b.limit(info.offset + info.size); muxer.writeSampleData(track, b, info) }
                        codec.releaseOutputBuffer(o, false); if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true }
                }
            }
        } finally { runCatching { codec.stop() }; codec.release(); if (started) runCatching { muxer.stop() }; muxer.release() }
    }

    /** FLAC: Android's encoder gives the stream header and then the frames; a .flac file is just those, one after the other. */
    fun encodeFlac(c: Clip, f: File, progress: (Float) -> Unit = {}) {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, c.rate, c.ch.size).apply { setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5); setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 16) }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC); codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
        val info = MediaCodec.BufferInfo(); var pos = 0; var inDone = false; var outDone = false; val ch = c.ch.size
        f.outputStream().buffered().use { os ->
            try {
                while (!outDone) {
                    if (!inDone) { val i = codec.dequeueInputBuffer(5000)
                        if (i >= 0) { val b = codec.getInputBuffer(i)!!; b.clear()
                            if (pos >= c.frames) { codec.queueInputBuffer(i, 0, 0, pos.toLong() * 1_000_000 / c.rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                            else { val end = min(c.frames, pos + min(4096, b.capacity() / (2 * ch))); val bytes = toInterleaved16(c, pos, end); b.put(bytes)
                                codec.queueInputBuffer(i, 0, bytes.size, pos.toLong() * 1_000_000 / c.rate, 0); pos = end; progress(pos.toFloat() / c.frames) } } }
                    val o = codec.dequeueOutputBuffer(info, 5000)
                    if (o >= 0) { val b = codec.getOutputBuffer(o)!!; if (info.size > 0) { val arr = ByteArray(info.size); b.position(info.offset); b.get(arr); os.write(arr) }
                        codec.releaseOutputBuffer(o, false); if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true }
                }
            } finally { runCatching { codec.stop() }; codec.release() }
        }
    }
}

/* ---------------------------------------------------------- recorder -------------------------------------------------- */

/**
 * Records straight into a WAV file on disk, with its header refreshed every second: if the phone dies mid-take, what was recorded still opens.
 */
class Recorder(private val file: File, private val rate: Int, private val channels: Int, private val source: Int, private val device: AudioDeviceInfo?) {
    @Volatile var peak = 0f; @Volatile var rms = 0f; @Volatile var clipped = 0; @Volatile var frames = 0L; @Volatile var paused = false; @Volatile var failed: String? = null
    @Volatile private var running = false
    private var thread: Thread? = null
    /** Input gain as a multiplier; 1 leaves the sound alone. */
    @Volatile var gain = 1f

    @Suppress("MissingPermission")
    fun start(onLevel: (Float, Float) -> Unit = { _, _ -> }) {
        val chMask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBuf = AudioRecord.getMinBufferSize(rate, chMask, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(source, rate, chMask, AudioFormat.ENCODING_PCM_16BIT, max(minBuf * 4, rate * channels / 2))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { failed = "The microphone couldn't be opened"; return }
        if (device != null) rec.setPreferredDevice(device)
        running = true
        thread = Thread {
            val raf = RandomAccessFile(file, "rw"); raf.setLength(0)
            fun header() { val len = frames * channels * 2; val pos = raf.filePointer; raf.seek(0)
                val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                h.put("RIFF".toByteArray()).putInt((36 + len).toInt()).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate).putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16).put("data".toByteArray()).putInt(len.toInt())
                raf.write(h.array()); raf.seek(pos) }
            header(); raf.seek(44)
            val buf = ShortArray(rate / 20 * channels); val bytes = ByteBuffer.allocate(buf.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            var lastHeader = System.currentTimeMillis()
            try {
                rec.startRecording()
                while (running) {
                    val n = rec.read(buf, 0, buf.size); if (n <= 0) { if (n < 0) { failed = "The microphone stopped"; break }; continue }
                    if (gain != 1f) for (i in 0 until n) buf[i] = (buf[i] * gain).coerceIn(-32768f, 32767f).toInt().toShort()
                    var pk = 0; var sum = 0.0
                    for (i in 0 until n) { val v = abs(buf[i].toInt()); if (v > pk) pk = v; sum += v.toDouble() * v; if (v >= 32500) clipped++ }
                    peak = pk / 32768f; rms = sqrt(sum / n).toFloat() / 32768f; onLevel(peak, rms)
                    if (paused) continue
                    bytes.clear(); for (i in 0 until n) bytes.putShort(buf[i]); raf.write(bytes.array(), 0, n * 2); frames += n / channels
                    if (System.currentTimeMillis() - lastHeader > 1000) { header(); lastHeader = System.currentTimeMillis() }
                }
            } catch (e: Exception) { failed = e.message } finally { runCatching { rec.stop() }; rec.release(); header(); raf.close() }
        }.also { it.start() }
    }

    fun stop() { running = false; thread?.join(3000) }
}

/** A click track for keeping time. It plays through the speaker or headphones and is never part of the recording. */
class Metronome {
    @Volatile private var running = false
    private var thread: Thread? = null
    fun start(bpm: Int, beatsPerBar: Int, volume: Float, onBeat: (Int) -> Unit = {}) {
        stop(); running = true
        thread = Thread {
            val rate = 44100; val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setBufferSizeInBytes(rate).setTransferMode(AudioTrack.MODE_STREAM).build()
            fun click(f: Double): ShortArray = ShortArray((rate * 0.045).toInt()) { i -> (sin(2 * PI * f * i / rate) * exp(-i / (rate * 0.008)) * 32000 * volume).toInt().toShort() }
            val hi = click(1600.0); val lo = click(1000.0); val beatLen = (rate * 60.0 / bpm).toInt(); var beat = 0
            track.play()
            try { while (running) { val c = if (beat % beatsPerBar == 0) hi else lo; val b = ShortArray(beatLen); System.arraycopy(c, 0, b, 0, min(c.size, beatLen)); onBeat(beat % beatsPerBar); track.write(b, 0, b.size); beat++ } }
            finally { runCatching { track.stop() }; track.release() }
        }.also { it.start() }
    }
    val isRunning get() = running
    fun stop() { running = false; thread = null }
}
