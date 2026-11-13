package dev.murmur.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.murmur.cleanup.WritingStyle
import dev.murmur.cleanup.WritingStyle.Style

/** Writing style (design/styles-snippets.html, 01): how dictation is written in each app. */
@Composable
fun WritingStyleScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) } // bumps to re-read prefs after a change
    val pm = ctx.packageManager
    fun installed(pkg: String) = runCatching { pm.getApplicationInfo(pkg, 0); true }.getOrDefault(false)
    fun label(pkg: String) = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    Column(Modifier.verticalScroll(rememberScrollState())) {
        TopBar("Writing style", onBack)
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            tick.let { }
            GroupLabel("Other apps")
            Group {
                Box(Modifier.fillMaxWidth().padding(14.dp)) {
                    Segmented(Style.entries, WritingStyle.default(ctx), { it.label }) { WritingStyle.setDefault(ctx, it); tick++ }
                }
            }
            GroupLabel("By app")
            val apps = (WritingStyle.CHAT_APPS + WritingStyle.EMAIL_APPS + WritingStyle.WRITING_APPS + WritingStyle.chosenApps(ctx))
                .distinct().filter { installed(it) }.sortedBy { label(it).lowercase() }
            Group {
                apps.forEachIndexed { i, pkg ->
                    if (i > 0) GroupDivider()
                    val chosen = WritingStyle.chosen(ctx, pkg)
                    val s = WritingStyle.forApp(ctx, pkg)
                    Item(label(pkg), if (chosen != null) "You changed this · tap to change" else "Picked for ${WritingStyle.kindOf(ctx, pkg).label} · tap to change",
                        chevron = false, onClick = { WritingStyle.setForApp(ctx, pkg, WritingStyle.next(s)); tick++ },
                        trailing = { Badge(s.label, accent = true) })
                }
            }
            GroupLabel("Casual")
            Group {
                Item("Lowercase start", "“yeah i'm down” instead of “Yeah I'm down”", chevron = false, trailing = {
                    MurmurSwitch(WritingStyle.lowercaseStart(ctx)) { WritingStyle.setFlag(ctx, WritingStyle.LOWERCASE, it); tick++ }
                })
                GroupDivider()
                Item("Drop the last period", "Questions keep their “?”", chevron = false, trailing = {
                    MurmurSwitch(WritingStyle.dropPeriod(ctx)) { WritingStyle.setFlag(ctx, WritingStyle.DROP_PERIOD, it); tick++ }
                })
            }
            Group {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Hint("Casual: lowercase, no end period, slang as spoken. Neutral: proper sentences, your words untouched. " +
                        "Polished: full punctuation, “gonna” written out, texting slang dropped; with Claude cleanup on, Claude also smooths the wording. " +
                        "While dictating, tap the style chip to change it for that app. Styles need cleanup on (Light or Claude).")
                }
            }
        }
    }
}
