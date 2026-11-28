package dev.wonderdesk.echo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.wonderdesk.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val FILLERS = setOf("um", "uh", "uhm", "umm", "er", "erm", "ah", "hmm", "mm", "huh")

/** Speech to text with timestamps, and editing the recording by editing the words. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TextPanel(st: EditorState, project: Project, apply: (Op) -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope(); val p = wonderPalette(); val toast = LocalToast.current
    var lang by rememberSaveable { mutableIntStateOf(ctx.ep().getInt("speechLang", 0).coerceIn(0, Speech.LANGS.size - 1)) }
    var tr by remember(project.ops.size) { mutableStateOf(Transcript.load(project)) }
    var picked by remember(project.ops.size) { mutableStateOf(setOf<Int>()) }
    val t = tr
    val stale = t != null && t.edits != project.ops.size
    val rate = st.current?.rate ?: 44100

    fun run() { scope.launch {
        val id = Speech.LANGS[lang].second
        try {
            if (!Speech.has(ctx, id)) {
                st.busy = "Getting the speech model ready"; st.progress = null
                withContext(Dispatchers.IO) { Speech.download(ctx, id) { pr, step -> st.progress = pr; st.busy = step } }
            }
            st.busy = "Listening to your recording"; st.progress = 0f
            val clip = st.current ?: return@launch
            val words = withContext(Dispatchers.Default) { Speech.transcribe(ctx, id, clip) { st.progress = it } }
            val made = Transcript(id, project.ops.size, words)
            withContext(Dispatchers.IO) { made.save(project) }
            tr = made; picked = emptySet()
            if (words.isEmpty()) toast("No speech was found in this recording")
        } catch (e: Exception) { toast("Couldn't transcribe: ${e.message}") }
        st.busy = null; st.progress = null
    } }

    Panel(title = "Speech to text") {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Write out what was said, with the time of every word. Tap words to pick them, then delete them from the recording. It all runs on this phone.", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
            Row(Modifier.scrollRow(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Speech.LANGS.forEachIndexed { i, (n, _) -> Chip(n, lang == i, { lang = i; ctx.ep().edit().putInt("speechLang", i).apply() }) }
            }
            val has = Speech.has(ctx, Speech.LANGS[lang].second)
            WButton(if (t == null) (if (has) "Transcribe" else "Download the model and transcribe") else "Transcribe again", { run() }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, icon = WonderIcons.Mic)
            if (!has) Text("The ${Speech.LANGS[lang].first} model is about 40 MB and is downloaded once. After that this works with no internet.", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
            if (has) TextButton(onClick = { Speech.delete(ctx, Speech.LANGS[lang].second); toast("Removed the ${Speech.LANGS[lang].first} model") }) { Text("Remove the ${Speech.LANGS[lang].first} model from this phone") }
        }
    }
    if (t != null) Panel(title = if (t.words.isEmpty()) "Nothing heard" else "${t.words.size} words") {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (stale) Text("You edited the recording after this was written, so the times no longer match. Transcribe again to edit by words.", style = MaterialTheme.typography.bodySmall, color = p.danger)
            // lines: a new line at each long pause
            val lines = ArrayList<List<Int>>(); var cur = ArrayList<Int>()
            t.words.forEachIndexed { i, w -> if (cur.isNotEmpty() && (w.start - t.words[cur.last()].end > 0.9 || cur.size >= 14)) { lines.add(cur); cur = ArrayList() }; cur.add(i) }
            if (cur.isNotEmpty()) lines.add(cur)
            lines.forEach { line ->
                Column {
                    val s = t.words[line.first()].start.toInt()
                    Text("%d:%02d".format(s / 60, s % 60), style = MaterialTheme.typography.labelSmall, color = p.accent)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        line.forEach { i -> val w = t.words[i]; val on = i in picked
                            Text(w.text, Modifier.clip(RoundedCornerShape(6.dp)).background(if (on) p.accent else Color0).clickable {
                                picked = if (on) picked - i else picked + i; st.playhead = (w.start * rate).toInt().coerceAtLeast(0)
                            }.padding(horizontal = 6.dp, vertical = 3.dp), color = if (on) p.onAccent else p.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (t.words.isNotEmpty()) {
                Row(Modifier.scrollRow(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WButton("Delete ${picked.size} picked", {
                        val ranges = JSONArray(); picked.sorted().forEach { i -> val w = t.words[i]; ranges.put(JSONArray().put((w.start * rate).toInt()).put((w.end * rate).toInt())) }
                        apply(Op("cut_many", JSONObject().put("ranges", ranges), "Deleting words")); picked = emptySet()
                    }, icon = WonderIcons.Delete, kind = ButtonKind.Danger, enabled = picked.isNotEmpty() && !stale)
                    WButton("Pick ums and uhs", { picked = t.words.indices.filter { t.words[it].text.lowercase() in FILLERS }.toSet(); if (picked.isEmpty()) toast("No filler sounds were heard") }, kind = ButtonKind.Secondary, enabled = !stale)
                    WButton("Select in waveform", { val a = picked.minOf { t.words[it].start }; val b = picked.maxOf { t.words[it].end }; st.selA = (a * rate).toInt(); st.selB = (b * rate).toInt() }, kind = ButtonKind.Secondary, enabled = picked.isNotEmpty())
                    WButton("Clear", { picked = emptySet() }, kind = ButtonKind.Plain, enabled = picked.isNotEmpty())
                }
            }
        }
    }
}

private val Color0 = androidx.compose.ui.graphics.Color.Transparent
