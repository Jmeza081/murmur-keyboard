package dev.murmur.settings.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.latin.R

private val colors @Composable get() = LocalSettingsColors.current

/** 30 sp display title for top-level screens (mockup .big-title). */
@Composable
fun BigTitle(text: String, sub: String? = null) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 12.dp)) {
        Text(text, fontFamily = RobotoFlex, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = colors.ink, lineHeight = 33.sp)
        if (sub != null) Text(sub, fontFamily = RobotoFlex, fontSize = 13.5.sp, color = colors.sub, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Back arrow and a 20 sp title for sub-screens (mockup .stop-bar). */
@Composable
fun TopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_murmur_arrow_left), "Back", tint = colors.ink) }
        Text(title, fontFamily = RobotoFlex, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = colors.ink, modifier = Modifier.padding(start = 4.dp))
    }
}

/** A rounded 20 dp surface holding rows, separated by hairlines (mockup .group). */
@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.surface), content = content)
}

@Composable
fun GroupDivider() = HorizontalDivider(thickness = 1.dp, color = colors.line)

/** Accent, uppercase section label (mockup .group-label). */
@Composable
fun GroupLabel(text: String) {
    Text(text.uppercase(), fontFamily = RobotoFlex, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp,
        color = colors.accent, modifier = Modifier.padding(start = 8.dp, top = 4.dp))
}

/** Rounded icon tile on the tint color. */
@Composable
fun IconTile(@DrawableRes icon: Int, size: Int = 36, filled: Boolean = false) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.3f).dp)).background(if (filled) colors.accent else colors.tint),
        contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, tint = if (filled) colors.onAccent else colors.accent, modifier = Modifier.size((size * 0.56f).dp))
    }
}

/** A settings row: optional icon tile, title, value line, and a trailing control (mockup .item). */
@Composable
fun Item(
    title: String, value: String? = null, @DrawableRes icon: Int? = null,
    onClick: (() -> Unit)? = null, chevron: Boolean = onClick != null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) IconTile(icon)
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = RobotoFlex, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.ink, lineHeight = 19.5.sp)
            if (value != null) Text(value, fontFamily = RobotoFlex, fontSize = 12.5.sp, color = colors.sub, lineHeight = 17.sp)
            below?.invoke(this)
        }
        trailing?.invoke(this)
        if (chevron && trailing == null) Icon(painterResource(R.drawable.ic_murmur_chev), null, tint = colors.sub, modifier = Modifier.size(20.dp))
    }
}

/** Equal-width segments with a tinted selected segment (mockup .seg). */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(22.dp)).border(1.dp, colors.line, RoundedCornerShape(22.dp))) {
        options.forEachIndexed { i, o ->
            if (i > 0) Box(Modifier.width(1.dp).height(40.dp).background(colors.line))
            val on = o == selected
            Box(Modifier.weight(1f).height(40.dp).background(if (on) colors.tint else colors.surface).clickable { onSelect(o) },
                contentAlignment = Alignment.Center) {
                Text(label(o), fontFamily = RobotoFlex, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (on) colors.accent else colors.ink)
            }
        }
    }
}

/** Plain explanatory text inside a group. */
@Composable
fun Hint(text: String) = Text(text, fontFamily = RobotoFlex, fontSize = 12.5.sp, color = colors.sub, lineHeight = 18.sp)

/** Small rounded word chip; [add] styles it as the dashed "Add" chip (mockup .tag). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagRow(tags: List<String>, onAdd: (() -> Unit)? = null, onRemove: ((String) -> Unit)? = null) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tags.forEach { t ->
            Tag(t, onClick = onRemove?.let { { it(t) } })
        }
        if (onAdd != null) Tag("Add", add = true, onClick = onAdd)
    }
}

@Composable
private fun Tag(text: String, add: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.height(30.dp).clip(RoundedCornerShape(15.dp)).background(colors.bg)
            .border(BorderStroke(1.dp, if (add) colors.accent.copy(alpha = 0.6f) else colors.line), RoundedCornerShape(15.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (add) Icon(painterResource(R.drawable.ic_murmur_plus), null, tint = colors.accent, modifier = Modifier.size(14.dp))
        Text(text, fontFamily = RobotoFlex, fontSize = 12.5.sp, color = if (add) colors.accent else colors.ink)
    }
}

/** A small rounded label, accent-tinted when [accent] (mockup .badge). */
@Composable
fun Badge(text: String, accent: Boolean = true) {
    Text(text, fontFamily = RobotoFlex, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        color = if (accent) colors.accent else colors.sub,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (accent) colors.tint else colors.bg).padding(horizontal = 8.dp, vertical = 3.dp))
}

/** A label/value tile for the model card (mockup .stats). */
@Composable
fun RowScope.Stat(label: String, value: String) {
    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(colors.bg).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(label, fontFamily = RobotoFlex, fontSize = 11.sp, color = colors.sub)
        Text(value, fontFamily = RobotoFlex, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
    }
}

/** The mockup's slider: 4 dp track, accent fill, 20 dp knob with a soft halo (mockup .slider). Snaps to [steps]. */
@Composable
fun MurmurSlider(value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit, onDone: () -> Unit = {}) {
    val c = colors
    val span = range.endInclusive - range.start
    fun snap(f: Float): Float {
        val raw = range.start + f.coerceIn(0f, 1f) * span
        if (steps <= 0) return raw
        val step = span / (steps + 1)
        return range.start + kotlin.math.round((raw - range.start) / step) * step
    }
    val fraction = ((value - range.start) / span).coerceIn(0f, 1f)
    androidx.compose.foundation.Canvas(
        Modifier.fillMaxWidth().height(32.dp)
            .androidx_pointer { x, width, done -> if (done) onDone() else onChange(snap(x / width)) }
    ) {
        val y = size.height / 2
        val r = 10.dp.toPx()
        val x0 = r; val x1 = size.width - r
        val kx = x0 + (x1 - x0) * fraction
        drawLine(c.line, androidx.compose.ui.geometry.Offset(x0, y), androidx.compose.ui.geometry.Offset(x1, y), 4.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(c.accent, androidx.compose.ui.geometry.Offset(x0, y), androidx.compose.ui.geometry.Offset(kx, y), 4.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
        drawCircle(c.accent.copy(alpha = 0.18f), r + 6.dp.toPx(), androidx.compose.ui.geometry.Offset(kx, y))
        drawCircle(c.accent, r, androidx.compose.ui.geometry.Offset(kx, y))
    }
}

/** Tap or drag horizontally; reports x within the track, and done=true on release. */
private fun Modifier.androidx_pointer(on: (x: Float, width: Float, done: Boolean) -> Unit) = this.then(
    Modifier.pointerInput(Unit) {
        val inset = 10.dp.toPx()
        fun report(x: Float, done: Boolean) = on((x - inset).coerceIn(0f, size.width - 2 * inset), size.width - 2 * inset, done)
        awaitEachGesture {
            val down = awaitFirstDown()
            report(down.position.x, false)
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: break
                if (!change.pressed) { report(change.position.x, true); break }
                change.consume()
                report(change.position.x, false)
            }
        }
    })

@Composable
fun MurmurSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(checked, onChange, colors = SwitchDefaults.colors(
        checkedTrackColor = colors.accent, checkedThumbColor = colors.onAccent,
        uncheckedTrackColor = colors.line, uncheckedThumbColor = colors.sub, uncheckedBorderColor = colors.line))
}

/** Pill button, filled or tonal (mockup .small-btn). */
@Composable
fun SmallButton(text: String, tonal: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(if (tonal) colors.tint else colors.accent)
        .clickable(onClick = onClick).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text(text, fontFamily = RobotoFlex, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (tonal) colors.accent else colors.onAccent)
    }
}

@Composable
fun Gap(h: Int) = Spacer(Modifier.height(h.dp))
