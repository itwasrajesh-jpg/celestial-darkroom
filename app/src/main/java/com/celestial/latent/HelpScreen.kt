@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package com.celestial.latent

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celestial.latent.ui.LatentColors

/**
 * App help (74a): an index of topics, and one page per darkroom tab, print room and camera. Opened
 * from Settings, and from the darkroom's "?" straight at the tab in use — drawn in the darkroom's
 * own window there, so the edit underneath is never left. Words live in [HelpText].
 */
@Composable
fun HelpScreen(start: String? = null, onBack: () -> Unit) {
    var topic by remember { mutableStateOf(start) }
    // back from a page opened directly (the darkroom's "?") closes help; from the index, it returns there
    fun back() { if (topic == null || topic == start) onBack() else topic = null }
    androidx.activity.compose.BackHandler { back() }
    val shown = HelpText.topic(topic)
    // each page opens at its top
    androidx.compose.runtime.key(topic) {
    val scroll = rememberScrollState()
    Column(
        Modifier.fillMaxSize().background(LatentColors.Background).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = LatentColors.Text, fontSize = 24.sp, modifier = Modifier.combinedClickable(onClick = { back() }).padding(horizontal = 8.dp))
            Text(shown?.title ?: "APP HELP", color = LatentColors.Text, fontSize = 12.sp, letterSpacing = 4.sp)
            // a page opened from the darkroom can still reach every other topic
            if (shown != null && topic == start) Text("all", color = LatentColors.Amber, fontSize = 12.sp,
                modifier = Modifier.combinedClickable(onClick = { topic = null }).padding(8.dp))
            else Spacer(Modifier.width(32.dp))
        }
        if (shown == null) HelpIndex { topic = it } else HelpPage(shown)
        Spacer(Modifier.height(24.dp))
    }
    }
}

@Composable
private fun HelpIndex(onOpen: (String) -> Unit) {
    Text("What every control does, tab by tab, with when to reach for it. In the darkroom, tap ? at the top to open the page for the tab you are in.",
        color = LatentColors.Text, fontSize = 13.sp, lineHeight = 19.sp)
    HelpText.TOPICS.groupBy { it.group }.forEach { (group, topics) ->
        Text(group, color = LatentColors.TextDim, fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 6.dp))
        topics.forEach { t ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(10.dp)).background(LatentColors.Surface)
                    .combinedClickable(onClick = { onOpen(t.id) }).padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(t.title, color = LatentColors.TextBright, fontSize = 13.sp, letterSpacing = 1.5.sp)
                    Text(t.sub, color = LatentColors.Text, fontSize = 11.sp)
                }
                Text("›", color = LatentColors.Amber, fontSize = 20.sp)
            }
        }
    }
}

@Composable
private fun HelpPage(t: HelpText.Topic) {
    Text(t.intro, color = LatentColors.Text, fontSize = 13.sp, lineHeight = 19.sp)
    if (t.filters.isNotEmpty()) FilterPicker(t.filters)
    t.sections.forEach { s ->
        Text(s.title, color = LatentColors.TextDim, fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 10.dp))
        if (s.intro.isNotEmpty()) Text(s.intro, color = LatentColors.Text, fontSize = 12.sp, lineHeight = 18.sp)
        s.items.forEach { HelpCard(it) }
    }
}

@Composable
private fun HelpCard(i: HelpText.Item) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LatentColors.Surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Text(i.name, color = LatentColors.TextBright, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (i.range.isNotEmpty()) Text(i.range, color = LatentColors.TextDim, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Text(i.what, color = LatentColors.Text, fontSize = 12.5.sp, lineHeight = 18.sp)
        if (i.lower.isNotEmpty() || i.higher.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Ends(i.lowLabel, i.lower, Modifier.weight(1f))
            Ends(i.highLabel, i.higher, Modifier.weight(1f))
        }
        if (i.use.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Text("USE IT", color = LatentColors.AmberInk, fontSize = 9.sp, letterSpacing = 1.5.sp,
                modifier = Modifier.padding(top = 1.dp).clip(RoundedCornerShape(999.dp)).background(LatentColors.Amber).padding(horizontal = 7.dp, vertical = 3.dp))
            Text(i.use, color = LatentColors.TextBright, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun Ends(label: String, text: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(LatentColors.Background).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = LatentColors.TextDim, fontSize = 9.sp, letterSpacing = 1.5.sp)
        Text(text, color = LatentColors.Text, fontSize = 11.5.sp, lineHeight = 16.sp)
    }
}

/** The diffusion filters side by side: tap one to see the shape of its glow and when to use it. */
@Composable
private fun FilterPicker(filters: List<HelpText.Filter>) {
    var picked by remember { mutableStateOf("black_pro_mist") }
    val f = filters.firstOrNull { it.id == picked } ?: filters.first()
    Text("CHOOSE A FILTER", color = LatentColors.TextDim, fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        filters.forEach { x ->
            val on = x.id == picked
            Text(x.label, color = if (on) LatentColors.AmberInk else LatentColors.Text, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) LatentColors.Amber else Color.Transparent)
                    .then(if (on) Modifier else Modifier.border(0.5.dp, LatentColors.Line, RoundedCornerShape(999.dp)))
                    .combinedClickable(onClick = { picked = x.id }).padding(horizontal = 14.dp, vertical = 9.dp))
        }
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LatentColors.Surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(8.dp)).background(Color(f.floor))) {
            Canvas(Modifier.fillMaxSize()) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val tint = Color(f.tint)
                val outer = f.bloom * 1.6f * density
                fun at(px: Float) = (px * density / outer).coerceIn(0f, 1f)
                drawCircle(Brush.radialGradient(
                    0f to Color(0xFFFFFBF0), at(5f) to Color(0xFFFFFBF0),
                    at(f.core) to tint.copy(alpha = 0.85f), at(f.halo) to tint.copy(alpha = 0.35f),
                    at(f.bloom) to tint.copy(alpha = 0.12f), 1f to tint.copy(alpha = 0f),
                    center = c, radius = outer,
                ), radius = outer, center = c)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(f.label, color = LatentColors.TextBright, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(f.tag, color = LatentColors.TextDim, fontSize = 10.sp)
        }
        Text(f.what, color = LatentColors.Text, fontSize = 12.5.sp, lineHeight = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Text("USE IT", color = LatentColors.AmberInk, fontSize = 9.sp, letterSpacing = 1.5.sp,
                modifier = Modifier.padding(top = 1.dp).clip(RoundedCornerShape(999.dp)).background(LatentColors.Amber).padding(horizontal = 7.dp, vertical = 3.dp))
            Text(f.use, color = LatentColors.TextBright, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Text("The drawing shows the glow's shape, not a render.", color = LatentColors.TextDim, fontSize = 10.sp)
    }
}
