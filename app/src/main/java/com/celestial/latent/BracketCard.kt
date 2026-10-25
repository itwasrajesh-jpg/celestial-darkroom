@file:OptIn(ExperimentalFoundationApi::class)

package com.celestial.latent

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celestial.latent.camera.Bracket
import com.celestial.latent.ui.LatentColors

/**
 * 86b: the HDR BRACKET card over the viewfinder (hold the HDR button). The user dials shutter and ISO
 * on the strip below until the sky looks right and taps "Set as darkest", then dials the shadows and
 * taps "Set as brightest", and picks how many frames. The app fills in the frames between in even
 * stops and says exactly what it will take; it never picks a shutter or ISO of its own.
 */
@Composable
fun BracketCard(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    /** what the strip is exposing at right now (shutter ns, ISO), or null while the camera has not said */
    current: () -> Pair<Long, Int>?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val darkSet = settings.bracketDarkNs > 0 && settings.bracketDarkIso > 0
    val brightSet = settings.bracketBrightNs > 0 && settings.bracketBrightIso > 0
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xEE2C2C2A))
        // taps on the card stay on the card (they would otherwise focus the viewfinder underneath)
        .pointerInput(Unit) { detectTapGestures { } }
        .padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("HDR BRACKET", color = LatentColors.Amber, fontSize = 12.sp, letterSpacing = 3.sp, modifier = Modifier.weight(1f))
            Text("×", color = LatentColors.Text, fontSize = 18.sp,
                modifier = Modifier.combinedClickable(onClick = { Haptics.tick(context); onClose() }).padding(horizontal = 6.dp, vertical = 2.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EndBox("DARKEST", "for the sky", settings.bracketDarkNs, settings.bracketDarkIso, null,
                armed = !darkSet, setLabel = if (darkSet) "Set again" else "Set as darkest", modifier = Modifier.weight(1f)) {
                current()?.let { (ns, iso) -> Haptics.tick(context); onSettingsChange(settings.copy(bracketDarkNs = ns, bracketDarkIso = iso)) }
            }
            val stops = if (darkSet && brightSet) Bracket.stops(Bracket.Step(settings.bracketDarkNs, settings.bracketDarkIso),
                Bracket.Step(settings.bracketBrightNs, settings.bracketBrightIso)) else null
            EndBox("BRIGHTEST", "for the shadows", settings.bracketBrightNs, settings.bracketBrightIso, stops,
                armed = darkSet && !brightSet, setLabel = if (brightSet) "Set again" else "Set as brightest", modifier = Modifier.weight(1f)) {
                current()?.let { (ns, iso) -> Haptics.tick(context); onSettingsChange(settings.copy(bracketBrightNs = ns, bracketBrightIso = iso)) }
            }
        }
        Spacer(Modifier.height(10.dp))
        val ready = darkSet && brightSet
        val (dark, bright) = if (ready) Bracket.ends(settings.bracketDarkNs, settings.bracketDarkIso, settings.bracketBrightNs, settings.bracketBrightIso)
            else Bracket.Step(1, 1) to Bracket.Step(1, 1)
        val mode = Bracket.mode(dark, bright)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("FRAMES", color = LatentColors.TextDim, fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.padding(end = 6.dp))
            Bracket.FRAME_CHOICES.forEach { n ->
                val on = settings.bracketFrames == n
                Text("$n", color = if (on) LatentColors.AmberInk else LatentColors.TextBright, fontSize = 14.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 2.dp).clip(RoundedCornerShape(999.dp)).background(if (on) LatentColors.Amber else Color.Transparent)
                        .combinedClickable(onClick = { Haptics.tick(context); onSettingsChange(settings.copy(bracketFrames = n)) })
                        .padding(horizontal = 10.dp, vertical = 2.dp))
            }
            Spacer(Modifier.weight(1f))
            if (ready) Text(mode.label, color = LatentColors.Amber, fontSize = 10.sp)
        }
        Spacer(Modifier.height(8.dp))
        if (ready) {
            val plan = Bracket.plan(dark, bright, settings.bracketFrames)
            // the ladder: darkest to brightest, each tile as bright as its frame is exposed relative to the others
            val span = Bracket.stops(dark, bright).coerceAtLeast(0.01)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                plan.forEach { st ->
                    val t = (Bracket.stops(dark, st) / span).toFloat().coerceIn(0f, 1f)
                    val v = 0.18f + 0.72f * t
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(3.dp)).background(Color(v, v, v * 0.97f)))
                        Text(Bracket.stepLabel(st, mode), color = LatentColors.Text, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(Bracket.planLine(plan), color = LatentColors.TextBright, fontSize = 12.sp)
            if (mode == Bracket.Mode.SAME) Text("Both ends are the same exposure: set the brightest brighter.", color = LatentColors.Amber, fontSize = 11.sp)
        } else Text(if (!darkSet) "Dial S and ISO below until the sky looks right, then Set as darkest."
            else "Now dial until the shadows look right, then Set as brightest.", color = LatentColors.Text, fontSize = 12.sp)
    }
}

@Composable
private fun EndBox(title: String, hint: String, ns: Long, iso: Int, stops: Double?, armed: Boolean, setLabel: String,
                   modifier: Modifier = Modifier, onSet: () -> Unit) {
    val set = ns > 0 && iso > 0
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF1E1E1C))
        .border(if (armed) 1.dp else 0.5.dp, if (armed) LatentColors.Amber else LatentColors.Line, RoundedCornerShape(10.dp))
        .padding(horizontal = 10.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = LatentColors.TextDim, fontSize = 9.sp, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            Text(hint, color = LatentColors.TextDim, fontSize = 9.sp)
        }
        Text(if (set) "${Bracket.shutterLabel(ns)} s · ISO $iso" else "not set", color = if (set) LatentColors.TextBright else LatentColors.TextDim,
            fontSize = 15.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
        Text(stops?.let { "%+.1f stops".format(java.util.Locale.US, it) } ?: "", color = LatentColors.Text, fontSize = 11.sp)
        Text(setLabel, color = if (armed) LatentColors.AmberInk else LatentColors.TextBright, fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(999.dp)).background(if (armed) LatentColors.Amber else LatentColors.Surface)
                .combinedClickable(onClick = onSet).padding(horizontal = 10.dp, vertical = 5.dp))
    }
}

/** The bracket the user set, as frames, or null while either end is missing. */
fun bracketPlan(s: AppSettings): List<Bracket.Step>? {
    if (s.bracketDarkNs <= 0 || s.bracketDarkIso <= 0 || s.bracketBrightNs <= 0 || s.bracketBrightIso <= 0) return null
    val (d, b) = Bracket.ends(s.bracketDarkNs, s.bracketDarkIso, s.bracketBrightNs, s.bracketBrightIso)
    if (Bracket.mode(d, b) == Bracket.Mode.SAME) return null
    return Bracket.plan(d, b, s.bracketFrames.coerceIn(2, 9))
}
