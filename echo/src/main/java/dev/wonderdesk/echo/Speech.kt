package dev.wonderdesk.echo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** One recognised word and where it sits in the recording, in seconds. */
data class Word(val text: String, val start: Double, val end: Double)

/** A transcript saved next to a project: the words, the language, and how many edits the recording had when it was made. */
class Transcript(val lang: String, val edits: Int, val words: List<Word>) {
    val plain get() = words.joinToString(" ") { it.text }
    fun toJson() = JSONObject().put("lang", lang).put("edits", edits).put("words", JSONArray(words.map { JSONObject().put("w", it.text).put("s", it.start).put("e", it.end) }))
    companion object {
        fun from(j: JSONObject) = Transcript(j.optString("lang"), j.optInt("edits"), j.getJSONArray("words").let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> Word(o.getString("w"), o.getDouble("s"), o.getDouble("e")) } } })
        fun load(p: Project): Transcript? = runCatching { from(JSONObject(File(p.dir, "transcript.json").readText())) }.getOrNull()
        fun text(p: Project): String = load(p)?.plain.orEmpty()
    }
    fun save(p: Project) { File(p.dir, "transcript.json").writeText(toJson().toString()) }
}

/** Turns speech into text on the phone with Vosk, an open-source engine. Nothing is uploaded; the model is downloaded once. */
object Speech {
    val LANGS = listOf("English" to "vosk-model-small-en-us-0.15", "Spanish" to "vosk-model-small-es-0.42", "French" to "vosk-model-small-fr-0.22",
        "German" to "vosk-model-small-de-0.15", "Portuguese" to "vosk-model-small-pt-0.3", "Italian" to "vosk-model-small-it-0.22")
    private fun dir(c: Context, id: String) = File(File(c.filesDir, "models"), id)
    fun has(c: Context, id: String) = File(dir(c, id), "am").exists()

    /** Downloads and unpacks a model (about 40 MB). [progress] gets a 0..1 value and a short description of the step. */
    fun download(c: Context, id: String, progress: (Float?, String) -> Unit) {
        val zip = File(c.cacheDir, "$id.zip")
        val con = URL("https://alphacephei.com/vosk/models/$id.zip").openConnection() as HttpURLConnection
        con.connectTimeout = 15000; con.readTimeout = 30000
        val total = con.contentLengthLong
        progress(0f, "Step 1 of 2: downloading the speech model")
        con.inputStream.use { i -> zip.outputStream().use { o -> val b = ByteArray(64 * 1024); var n = 0L; while (true) { val r = i.read(b); if (r < 0) break; o.write(b, 0, r); n += r; if (total > 0) progress(n.toFloat() / total, "Step 1 of 2: downloading the speech model, ${n / 1_000_000} of ${total / 1_000_000} MB") } } }
        progress(null, "Step 2 of 2: unpacking it")
        val root = File(c.filesDir, "models").also { it.mkdirs() }
        ZipInputStream(zip.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break
            val out = File(root, e.name); if (!out.canonicalPath.startsWith(root.canonicalPath)) continue
            if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { z.copyTo(it) } } } }
        zip.delete()
        if (!has(c, id)) throw IllegalStateException("The speech model didn't unpack correctly")
    }

    fun delete(c: Context, id: String) { dir(c, id).deleteRecursively() }

    /** Recognises the speech in [clip]. [progress] runs from 0 to 1. */
    fun transcribe(c: Context, id: String, clip: Clip, progress: (Float) -> Unit): List<Word> {
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        val mono = clip.mono()
        val x = if (clip.rate == 16000) mono else Fx.resample(mono, clip.rate / 16000.0)
        val pcm = ShortArray(x.size) { (x[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
        val words = ArrayList<Word>()
        Model(dir(c, id).path).use { model -> Recognizer(model, 16000f).use { rec ->
            rec.setWords(true)
            fun take(json: String) { val a = JSONObject(json).optJSONArray("result") ?: return; for (i in 0 until a.length()) a.getJSONObject(i).let { o -> words.add(Word(o.getString("word"), o.getDouble("start"), o.getDouble("end"))) } }
            val step = 8000
            var i = 0
            while (i < pcm.size) { val n = minOf(step, pcm.size - i); val chunk = pcm.copyOfRange(i, i + n)
                if (rec.acceptWaveForm(chunk, n)) take(rec.result); i += n; progress(i.toFloat() / pcm.size) }
            take(rec.finalResult)
        } }
        return words
    }
}
