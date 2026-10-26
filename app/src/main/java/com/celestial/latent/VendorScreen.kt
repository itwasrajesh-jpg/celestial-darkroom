@file:OptIn(ExperimentalFoundationApi::class)

package com.celestial.latent

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celestial.latent.camera.Lenses
import com.celestial.latent.ui.LatentColors
import com.celestial.latent.ui.accentFill

/**
 * One page per lens: the tags and the session opmode for that lens only.
 * Nothing from another lens is shown, and nothing added here reaches another lens.
 */
@Composable
fun VendorScreen(settings: AppSettings, exposedKeys: List<Pair<String, String>>, lastEcho: String, onChange: (AppSettings) -> Unit, onBack: () -> Unit) {
    var lensId by remember { mutableStateOf(settings.defaultLensId) }
    val lens = Lenses.ALL.firstOrNull { it.physicalId == lensId } ?: Lenses.DEFAULT
    var search by remember { mutableStateOf("") }

    // Tags belonging to this lens, with their index in the full list so edits land on the right one.
    val mine = settings.vendorTags.withIndex().filter { it.value.lens == lens.physicalId }
    val opmodeHere = if (settings.opmodeLens == lens.physicalId) settings.opmode else 0
    var opmodeText by remember(lensId) { mutableStateOf(if (opmodeHere == 0) "0" else "0x" + Integer.toHexString(opmodeHere)) }

    fun edit(index: Int, tag: VendorTag) = onChange(settings.copy(vendorTags = settings.vendorTags.toMutableList().also { it[index] = tag }))

    Column(
        Modifier.fillMaxSize().background(LatentColors.Background).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text("‹ settings", color = LatentColors.Text, fontSize = 14.sp, modifier = Modifier.combinedClickable(onClick = onBack).padding(vertical = 6.dp))
        Spacer(Modifier.height(8.dp))
        Text("Vendor codes", color = LatentColors.TextBright, fontSize = 24.sp)
        Text("Each lens has its own codes. Nothing on this page is sent to any other lens.", color = LatentColors.TextDim, fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))

        // Lens selector
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Lenses.ALL.forEach { l ->
                val on = l.physicalId == lensId
                val count = settings.vendorTags.count { it.lens == l.physicalId && it.name.isNotBlank() }
                Text(l.label + (if (count > 0) " ·$count" else ""), color = if (on) LatentColors.AmberInk else LatentColors.Text, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).accentFill(on, LatentColors.Surface)
                        .combinedClickable(onClick = { lensId = l.physicalId }).padding(horizontal = 14.dp, vertical = 7.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("${lens.name} · ${lens.mm} mm · camera ${lens.physicalId} · path ${settings.cameraPath}", color = LatentColors.TextDim, fontSize = 12.sp)

        Spacer(Modifier.height(16.dp))
        Text("SESSION OPMODE FOR THIS LENS", color = LatentColors.TextDim, fontSize = 11.sp, letterSpacing = 2.sp)
        Text("0 keeps the regular session. Decimal or hex (0x8001).", color = LatentColors.TextDim, fontSize = 12.sp)
        Field(opmodeText, { t ->
            opmodeText = t
            val v = t.trim().let { if (it.startsWith("0x", true)) it.substring(2).toIntOrNull(16) else it.toIntOrNull() }
            if (v != null) onChange(settings.copy(opmode = v, opmodeLens = if (v == 0) "all" else lens.physicalId))
        }, "0", KeyboardType.Text)

        Spacer(Modifier.height(16.dp))
        Text("TAGS FOR ${lens.label.uppercase()}", color = LatentColors.TextDim, fontSize = 11.sp, letterSpacing = 2.sp)
        if (mine.isEmpty()) Text("No codes for this lens yet.", color = LatentColors.TextDim, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
        mine.forEach { (i, tag) ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(8.dp)).background(LatentColors.Surface).padding(10.dp)) {
                Field(tag.name, { edit(i, tag.copy(name = it)) }, "org.codeaurora.qcamera3.…", KeyboardType.Text)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chips(listOf("session", "request"), tag.scope) { edit(i, tag.copy(scope = it)) }
                    Chips(listOf("i32", "i64", "f32", "f64", "u8"), tag.type) { edit(i, tag.copy(type = it)) }
                }
                Row {
                    Column(Modifier.weight(1f)) { Field(tag.value, { edit(i, tag.copy(value = it)) }, "value, or 4/12/24 for arrays", KeyboardType.Text) }
                    Spacer(Modifier.width(8.dp))
                    Text("✕", color = LatentColors.Text, fontSize = 18.sp,
                        modifier = Modifier.combinedClickable(onClick = { onChange(settings.copy(vendorTags = settings.vendorTags.filterIndexed { j, _ -> j != i })) }).padding(12.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("copy to:", color = LatentColors.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
                    Lenses.ALL.filter { it.physicalId != lens.physicalId }.forEach { other ->
                        Text(other.label, color = LatentColors.Amber, fontSize = 11.sp,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(LatentColors.Background)
                                .combinedClickable(onClick = { onChange(settings.copy(vendorTags = settings.vendorTags + tag.copy(lens = other.physicalId))) })
                                .padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
            }
        }
        Text("+ add code for ${lens.label}", color = LatentColors.AmberInk, fontSize = 13.sp,
            modifier = Modifier.padding(vertical = 8.dp).clip(RoundedCornerShape(999.dp)).accentFill()
                .combinedClickable(onClick = { onChange(settings.copy(vendorTags = settings.vendorTags + VendorTag("", "session", "i32", "1", lens.physicalId))) })
                .padding(horizontal = 14.dp, vertical = 7.dp))

        Spacer(Modifier.height(16.dp))
        Text("ECHO FROM LAST CAPTURE", color = LatentColors.TextDim, fontSize = 11.sp, letterSpacing = 2.sp)
        Text(if (lastEcho.isEmpty()) "Take a photo; what the driver reported appears here." else lastEcho, color = LatentColors.Text, fontSize = 12.sp, lineHeight = 16.sp)

        Spacer(Modifier.height(16.dp))
        Text("KEYS THE DRIVER EXPOSES (${exposedKeys.size})", color = LatentColors.TextDim, fontSize = 11.sp, letterSpacing = 2.sp)
        Text("Tapping one adds it to ${lens.label} only.", color = LatentColors.TextDim, fontSize = 12.sp)
        Field(search, { search = it }, "search keys", KeyboardType.Text)
        val shown = exposedKeys.filter { search.isBlank() || it.first.contains(search, ignoreCase = true) }.take(60)
        shown.forEach { (name, scope) ->
            Column(Modifier.fillMaxWidth().combinedClickable(onClick = {
                onChange(settings.copy(vendorTags = settings.vendorTags + VendorTag(name, if (scope == "request") "request" else "session", "i32", "1", lens.physicalId)))
            }).padding(vertical = 6.dp)) {
                Text(name.substringAfterLast('.'), color = LatentColors.TextBright, fontSize = 13.sp)
                Text("$name · $scope", color = LatentColors.TextDim, fontSize = 10.sp)
            }
        }
        if (exposedKeys.size > shown.size) Text("… ${exposedKeys.size - shown.size} more, refine the search", color = LatentColors.TextDim, fontSize = 11.sp)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String, kb: KeyboardType) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        placeholder = { Text(hint, color = LatentColors.TextDim, fontSize = 12.sp) },
        keyboardOptions = KeyboardOptions(keyboardType = kb),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = LatentColors.TextBright, unfocusedTextColor = LatentColors.TextBright,
            focusedBorderColor = LatentColors.Amber, unfocusedBorderColor = LatentColors.Line, cursorColor = LatentColors.Amber,
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun Chips(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o ->
            val on = o == selected
            Text(o, color = if (on) LatentColors.AmberInk else LatentColors.Text, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).accentFill(on, LatentColors.Background)
                    .combinedClickable(onClick = { onSelect(o) }).padding(horizontal = 10.dp, vertical = 5.dp))
        }
    }
}
