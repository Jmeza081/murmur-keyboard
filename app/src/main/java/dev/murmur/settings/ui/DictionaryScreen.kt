package dev.murmur.settings.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.murmur.dictionary.MurmurDictionary
import helium314.keyboard.latin.R

private enum class DictTab { WORDS, REPLACEMENTS }

/** Dictionary (mockup "Dictionary"): your words, and replacements that also fix dictation. */
@Composable
fun DictionaryScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalSettingsColors.current
    var tab by remember { mutableStateOf(DictTab.WORDS) }
    var words by remember { mutableStateOf(MurmurDictionary.words(ctx)) }
    var reps by remember { mutableStateOf(MurmurDictionary.replacements(ctx)) }
    var query by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    val refresh = { words = MurmurDictionary.words(ctx); reps = MurmurDictionary.replacements(ctx) }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        TopBar("Dictionary", onBack)
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Segmented(DictTab.entries, tab, {
                if (it == DictTab.WORDS) "Words · ${words.size}" else "Replacements · ${reps.size}"
            }) { tab = it }

            // search
            BasicTextField(query, { query = it }, singleLine = true,
                textStyle = TextStyle(fontFamily = RobotoFlex, fontSize = 15.sp, color = c.ink), cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).border(1.dp, c.line, RoundedCornerShape(22.dp)).padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(painterResource(R.drawable.ic_murmur_search), null, tint = c.sub, modifier = Modifier.size(18.dp))
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) Text(if (tab == DictTab.WORDS) "Search words" else "Search replacements",
                                fontFamily = RobotoFlex, fontSize = 15.sp, color = c.sub)
                            inner()
                        }
                    }
                })

            Group {
                Item(if (tab == DictTab.WORDS) "Add a word" else "Add a replacement",
                    if (tab == DictTab.WORDS) "Names, places, slang: anything you want suggested"
                    else "Fixes words the speech model mishears", R.drawable.ic_murmur_plus, onClick = { adding = true }, chevron = false)
            }

            val q = query.trim()
            if (tab == DictTab.WORDS) {
                val shown = words.filter { q.isEmpty() || it.contains(q, ignoreCase = true) }
                if (shown.isNotEmpty()) Group {
                    shown.forEachIndexed { i, w ->
                        if (i > 0) GroupDivider()
                        Item(w, null, chevron = false, trailing = {
                            Badge("Added", accent = true)
                            RemoveButton(w) { MurmurDictionary.removeWord(ctx, w); refresh() }
                        })
                    }
                } else Empty(if (words.isEmpty()) "No words yet. Words you add show up in suggestions, in English and Spanish."
                             else "No words match “$q”.")
            } else {
                val shown = reps.filter { q.isEmpty() || it.from.contains(q, true) || it.to.contains(q, true) }
                if (shown.isNotEmpty()) Group {
                    shown.forEachIndexed { i, r ->
                        if (i > 0) GroupDivider()
                        Item("${r.from}  →  ${r.to}", if (r.alsoTyping) "Fixes dictation and typing" else "Fixes dictation", chevron = false,
                            trailing = { RemoveButton(r.from) { MurmurDictionary.removeReplacement(ctx, r); refresh() } })
                    }
                } else Empty(if (reps.isEmpty()) "No replacements yet. Add one when dictation keeps mishearing a name, like “sherpa onyx” → “sherpa-onnx”."
                             else "No replacements match “$q”.")
            }
        }
    }

    if (adding) {
        var a by remember { mutableStateOf("") }
        var b by remember { mutableStateOf("") }
        val words = tab == DictTab.WORDS
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(if (words) "Add a word" else "Add a replacement") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(a, { a = it }, singleLine = true,
                        label = { Text(if (words) "Word" else "When I say") }, placeholder = { Text(if (words) "Valencia" else "sherpa onyx") })
                    if (!words) OutlinedTextField(b, { b = it }, singleLine = true, label = { Text("Type") }, placeholder = { Text("sherpa-onnx") })
                }
            },
            confirmButton = { TextButton(enabled = a.isNotBlank() && (words || b.isNotBlank()), onClick = {
                if (words) MurmurDictionary.addWord(ctx, a) else MurmurDictionary.addReplacement(ctx, a, b)
                refresh(); adding = false
            }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RemoveButton(what: String, onClick: () -> Unit) {
    val c = LocalSettingsColors.current
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        Icon(painterResource(R.drawable.ic_murmur_close), "Remove $what", tint = c.sub, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun Empty(text: String) = Group {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp)) { Hint(text) }
}
