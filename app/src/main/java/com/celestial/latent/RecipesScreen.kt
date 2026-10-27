@file:OptIn(ExperimentalFoundationApi::class)

package com.celestial.latent

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celestial.latent.develop.Develop
import com.celestial.latent.develop.RecipeShare
import com.celestial.latent.ui.Glyph
import com.celestial.latent.ui.GlyphIcon
import com.celestial.latent.ui.LatentColors
import com.celestial.latent.ui.accentFill
import com.celestial.latent.ui.glow
import com.celestial.latent.ui.raised
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 90a: sending recipes to another phone and taking them in (approved mock recipe-share/proof/).
 * [importing] false = Export: tick what to send, then share it or save it to Files. True = Import:
 * pick the file, see what is in it, choose what to do with names already here, then add.
 */
@Composable
fun RecipesScreen(importing: Boolean, onBack: () -> Unit, onOpenCamera: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(LatentColors.Background).glow().statusBarsPadding().navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(Modifier.combinedClickable(onClick = onBack).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.BACK, LatentColors.Text, size = 18.dp)
            Text("back", color = LatentColors.Text, fontSize = 14.sp, modifier = Modifier.padding(start = 2.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(if (importing) "Import recipes" else "Export recipes", color = LatentColors.TextBright, fontSize = 24.sp)
        if (importing) ImportPart(onOpenCamera) else ExportPart()
    }
}

@Composable
private fun ColumnScope.ExportPart() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<RecipeShare.Item>?>(null) }
    val ticked = remember { mutableStateMapOf<String, Boolean>() }
    var note by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { items = withContext(Dispatchers.IO) { RecipeShare.local(context) }.also { l -> l.forEach { ticked[it.name] = true } } }
    val chosen = items.orEmpty().filter { ticked[it.name] == true }
    fun text() = RecipeShare.write(chosen)
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text().toByteArray()) } != null }.getOrDefault(false) }
            note = if (ok) "Saved. Open it on the other phone with Import recipes." else "Could not save the file."
        }
    }
    Text("Tick what to send. Built films carry their film profile and the air and light saved with them.", color = LatentColors.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    val all = items
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        if (all == null) Note("Reading your recipes…")
        else if (all.isEmpty()) Note("No saved recipes yet. Save one in the darkroom (DEVELOP → Save recipe) or build a film in the Film Builder.")
        else {
            val (built, own) = all.partition { it.built }
            if (own.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) { Section("Your recipes") }
                    val allOn = all.all { ticked[it.name] == true }
                    Text(if (allOn) "none" else "all", color = LatentColors.Amber, fontSize = 12.sp,
                        modifier = Modifier.combinedClickable(onClick = { all.forEach { ticked[it.name] = !allOn } }).padding(6.dp))
                }
                own.forEach { ItemRow(it, ticked[it.name] == true) { ticked[it.name] = ticked[it.name] != true } }
            }
            if (built.isNotEmpty()) {
                Section("Built here")
                built.forEach { ItemRow(it, ticked[it.name] == true) { ticked[it.name] = ticked[it.name] != true } }
            }
        }
    }
    if (note.isNotEmpty()) Text(note, color = LatentColors.Text, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    val nOwn = chosen.count { !it.built }; val nBuilt = chosen.count { it.built }
    Text(listOfNotNull(
        "$nOwn ${if (nOwn == 1) "recipe" else "recipes"}",
        if (nBuilt > 0) "$nBuilt built ${if (nBuilt == 1) "film" else "films"}" else null,
        if (chosen.isNotEmpty()) remember(chosen.map { it.name }) { "${maxOf(1, text().length / 1024)} KB" } else null,
    ).joinToString(" · "), color = LatentColors.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Pill("Share", true, Glyph.SHARE, Modifier.weight(1f), enabled = chosen.isNotEmpty()) {
            scope.launch {
                val uri = withContext(Dispatchers.IO) {
                    runCatching {
                        val dir = File(context.cacheDir, "share").apply { mkdirs() }
                        val f = File(dir, "celestial-recipes.json").apply { writeText(text()) }
                        androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", f)
                    }.getOrNull()
                }
                if (uri == null) { note = "Could not make the file."; return@launch }
                val send = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, "Celestial Darkroom recipes").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { context.startActivity(Intent.createChooser(send, "Send recipes").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
        Pill("Save to Files", false, Glyph.FOLDER, Modifier.weight(1f), enabled = chosen.isNotEmpty()) { saver.launch("celestial-recipes.json") }
    }
}

@Composable
private fun ColumnScope.ImportPart(onOpenCamera: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fileName by remember { mutableStateOf("") }
    var pack by remember { mutableStateOf<RecipeShare.Pack?>(null) }
    var problem by remember { mutableStateOf("") }
    var added by remember { mutableStateOf<List<RecipeShare.Item>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val ticked = remember { mutableStateMapOf<Int, Boolean>() }
    val clash = remember { mutableStateMapOf<Int, RecipeShare.Clash>() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val (name, p) = withContext(Dispatchers.IO) {
                val n = runCatching { context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull()
                val t = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
                (n ?: "recipes file") to t?.let { RecipeShare.read(it) }
            }
            fileName = name; pack = p; added = null; ticked.clear(); clash.clear()
            problem = if (p == null) "This file isn't a Celestial Darkroom recipes file." else if (p.items.isEmpty()) "This file has no recipes in it." else ""
            p?.items?.indices?.forEach { ticked[it] = true }
        }
    }
    // straight to the file picker the first time
    LaunchedEffect(Unit) { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
    val done = added
    if (done != null) {
        Column(Modifier.weight(1f).fillMaxWidth().padding(top = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            GlyphIcon(Glyph.FILM, LatentColors.Amber, size = 64.dp)
            Spacer(Modifier.height(18.dp))
            Text("${done.size} ADDED", color = LatentColors.TextBright, fontSize = 13.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(8.dp))
            val film = done.firstOrNull { it.built }?.name
            Text("They're on the camera's film strip and in the darkroom now." + (film?.let { " $it is ready to shoot with." } ?: ""),
                color = LatentColors.Text, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(20.dp))
            // what the camera's film strip now holds: built films first, as the strip lists them
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(LatentColors.Mat).horizontalScroll(rememberScrollState()).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                done.sortedBy { !it.built }.forEachIndexed { k, it ->
                    Column(Modifier.width(118.dp).clip(RoundedCornerShape(6.dp)).accentFill(k == 0, LatentColors.Surface).padding(10.dp)) {
                        val ink = if (k == 0) LatentColors.AmberInk else LatentColors.TextBright
                        Text(it.name.uppercase(), color = ink, fontSize = 10.sp, letterSpacing = 1.sp, maxLines = 2)
                        Text(if (it.built) "built here" else "your recipe", color = ink.copy(alpha = 0.7f), fontSize = 8.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            Text("the camera's film strip", color = LatentColors.TextDim, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
        }
        Pill("Open camera", true, Glyph.CAMERA, Modifier.fillMaxWidth().padding(bottom = 8.dp), onClick = onOpenCamera)
        return
    }
    val p = pack
    if (p == null) {
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
            Note(problem.ifEmpty { "Pick the recipes file you sent from the other phone (Quick Share puts it in Downloads)." })
        }
        Pill("Choose file", true, Glyph.IMPORT, Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(LatentColors.Panel)
        .combinedClickable(onClick = { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        GlyphIcon(Glyph.IMPORT, LatentColors.Amber, size = 22.dp)
        Column(Modifier.padding(start = 12.dp)) {
            Text(fileName, color = LatentColors.TextBright, fontSize = 13.sp)
            val day = if (p.date > 0) java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(p.date)) else ""
            Text(listOf(if (p.from.isNotEmpty()) "from ${p.from}" else "", day, "${p.items.size} ${if (p.items.size == 1) "item" else "items"}")
                .filter { it.isNotEmpty() }.joinToString(" · "), color = LatentColors.TextDim, fontSize = 10.sp)
        }
    }
    val taken = p.items.indices.filter { com.celestial.latent.develop.Recipes.exists(context, p.items[it].name) }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Section("In this file")
        p.items.forEachIndexed { i, it -> if (i !in taken) ItemRow(it, ticked[i] != false) { ticked[i] = ticked[i] == false } }
        if (taken.isNotEmpty()) {
            Section("Already on this phone")
            taken.forEach { i ->
                val it = p.items[i]
                val c = clash[i] ?: RecipeShare.Clash.KEEP_BOTH
                ItemRow(it, null, "a different one with this name")
                Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Keep both" to RecipeShare.Clash.KEEP_BOTH, "Replace" to RecipeShare.Clash.REPLACE, "Skip" to RecipeShare.Clash.SKIP).forEach { (label, v) ->
                        Pill(label, c == v, null, Modifier.weight(1f)) { clash[i] = v }
                    }
                }
                if (c == RecipeShare.Clash.KEEP_BOTH) Text("Keep both adds it as \"${RecipeShare.freeName(context, it.name)}\".", color = LatentColors.TextDim, fontSize = 12.sp)
            }
        }
    }
    val chosen = p.items.indices.filter { i -> if (i in taken) clash[i] != RecipeShare.Clash.SKIP else ticked[i] != false }
    Pill(if (busy) "Adding…" else "Add ${chosen.size} to this phone", true, Glyph.ADD, Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp),
        enabled = chosen.isNotEmpty() && !busy) {
        busy = true
        scope.launch {
            val n = withContext(Dispatchers.IO) { chosen.filter { i -> runCatching { RecipeShare.add(context, p.items[i], clash[i] ?: RecipeShare.Clash.KEEP_BOTH) }.getOrDefault(false) } }
            busy = false; added = n.map { p.items[it] }
        }
    }
}

/** One recipe: its glyph (a look, or a film canister for a built film), name, and what it is. [on] null = no tick. */
@Composable
private fun ItemRow(item: RecipeShare.Item, on: Boolean?, detail: String? = null, onTap: () -> Unit = {}) {
    val film = Develop.FILMS.firstOrNull { it.first == item.recipe.film }?.second
    val what = detail ?: if (item.built) listOfNotNull("film profile", item.air?.let { "air" }, item.light?.let { "light" }).joinToString(" · ") else film ?: ""
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).raised(RoundedCornerShape(12.dp), 3.dp).clip(RoundedCornerShape(12.dp))
            .background(LatentColors.Surface).combinedClickable(onClick = onTap).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(if (item.built) Glyph.FILM else Glyph.LOOK, LatentColors.TextBright, size = 20.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(item.name.uppercase(), color = LatentColors.TextBright, fontSize = 12.sp, letterSpacing = 1.sp)
            Text(listOf(if (item.built) "built here" else "your recipe", what).filter { it.isNotEmpty() }.joinToString(" · "), color = LatentColors.TextDim, fontSize = 10.sp)
        }
        if (on != null) Box(Modifier.size(22.dp).clip(CircleShape).accentFill(on, Color.Transparent)
            .then(if (on) Modifier else Modifier.border(1.dp, LatentColors.Line, CircleShape)), contentAlignment = Alignment.Center) {
            if (on) Text("✓", color = LatentColors.AmberInk, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Pill(text: String, main: Boolean, glyph: Glyph?, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        modifier.clip(RoundedCornerShape(999.dp)).accentFill(main && enabled, LatentColors.Surface)
            .combinedClickable(enabled = enabled, onClick = { Haptics.tick(context); onClick() }).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        val ink = if (!enabled) LatentColors.TextDim else if (main) LatentColors.AmberInk else LatentColors.Text
        if (glyph != null) { GlyphIcon(glyph, ink, size = 16.dp, accent = if (main && enabled) LatentColors.AmberInk else LatentColors.Amber); Spacer(Modifier.width(8.dp)) }
        Text(text, color = ink, fontSize = 13.sp)
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(14.dp))
    Text(title.uppercase(), color = LatentColors.TextDim, fontSize = 11.sp, letterSpacing = 2.sp)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Note(text: String) = Text(text, color = LatentColors.Text, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(vertical = 16.dp))
