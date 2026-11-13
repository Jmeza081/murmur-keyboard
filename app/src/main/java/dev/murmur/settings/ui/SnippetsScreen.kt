package dev.murmur.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.murmur.snippets.Snippets
import helium314.keyboard.latin.R

/** Snippets (design/styles-snippets.html, 02): text you insert often, offered by field, from the tools row, or by voice. */
@Composable
fun SnippetsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(Snippets.all(ctx)) }
    var editing by remember { mutableStateOf<Snippets.Snippet?>(null) }
    val refresh = { list = Snippets.all(ctx) }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        TopBar("Snippets", onBack)
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Group {
                Item("Add a snippet", "Your email, phone, address, a link, a reply you send a lot", R.drawable.ic_murmur_plus,
                    onClick = { editing = Snippets.Snippet(0, "", "", Snippets.Kind.NONE) }, chevron = false)
            }
            if (list.isNotEmpty()) Group {
                list.forEachIndexed { i, s ->
                    if (i > 0) GroupDivider()
                    Item(s.label, s.text.lineSequence().first(), chevron = false, onClick = { editing = s }, trailing = {
                        if (s.kind != Snippets.Kind.NONE) Badge(s.kind.name.lowercase().replaceFirstChar { it.uppercase() }, accent = true)
                        IconButton(onClick = { Snippets.remove(ctx, s.id); refresh() }, modifier = Modifier.size(44.dp)) {
                            Icon(painterResource(R.drawable.ic_murmur_close), "Remove ${s.label}", tint = LocalSettingsColors.current.sub, modifier = Modifier.size(18.dp))
                        }
                    })
                }
            }
            Group {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Hint("Three ways to use them: fields that ask for an email, phone, address or link show the matching snippet in the strip. " +
                        "The snippets button in the tools row lists them all. While dictating, say “insert my …” or “paste my …” with the snippet’s name, like “paste my address”. " +
                        "Snippets are never offered in password fields, stay on this phone, and aren’t included in backups.")
                }
            }
        }
    }

    editing?.let { start ->
        var label by remember(start.id) { mutableStateOf(start.label) }
        var text by remember(start.id) { mutableStateOf(start.text) }
        var kind by remember(start.id) { mutableStateOf(start.kind) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (start.id == 0L) "Add a snippet" else "Edit snippet") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(label, { label = it }, singleLine = true, label = { Text("Name") }, placeholder = { Text("Home") })
                    OutlinedTextField(text, { text = it }, minLines = 2, label = { Text("Text") }, placeholder = { Text("1428 Elm St, Springfield") })
                    Text("Suggest in", fontFamily = RobotoFlex, fontSize = 13.sp, color = LocalSettingsColors.current.sub)
                    Snippets.Kind.entries.forEach { k ->
                        Row(Modifier.fillMaxWidth().clickable { kind = k }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = kind == k, onClick = { kind = k })
                            Text(if (k == Snippets.Kind.NONE) "No fields, just the list and voice" else k.label, fontFamily = RobotoFlex, fontSize = 15.sp)
                        }
                    }
                    val name = label.trim().lowercase().ifEmpty { "…" }
                    Hint("Say “insert my $name”" + (kind.spoken.firstOrNull()?.let { " or “paste my $it”" } ?: "") + " while dictating.")
                }
            },
            confirmButton = { TextButton(enabled = label.isNotBlank() && text.isNotBlank(), onClick = {
                Snippets.put(ctx, Snippets.Snippet(if (start.id == 0L) System.currentTimeMillis() else start.id, label.trim(), text.trim(), kind))
                refresh(); editing = null
            }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}
