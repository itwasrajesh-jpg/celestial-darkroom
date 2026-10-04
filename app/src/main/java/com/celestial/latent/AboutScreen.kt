@file:OptIn(ExperimentalFoundationApi::class)

package com.celestial.latent

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celestial.latent.ui.LatentColors

private const val ENGINE_COMMIT = "3c8080415d7bff2e915919e199a38ca40257eb4b"

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    Column(
        Modifier.fillMaxSize().background(LatentColors.Background).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text("‹ settings", color = LatentColors.Text, fontSize = 14.sp, modifier = Modifier.combinedClickable(onClick = onBack).padding(vertical = 6.dp))
        Spacer(Modifier.height(10.dp))
        // Two lines, as on the opening screen: the full name in one would not fit at this size.
        Text("CELESTIAL", color = LatentColors.TextDim, fontSize = 11.sp, letterSpacing = 5.sp)
        Text("DARKROOM", color = LatentColors.TextBright, fontSize = 22.sp, letterSpacing = 6.sp)
        Text("A film camera for Android · v" + BuildConfig.VERSION_NAME, color = LatentColors.TextDim, fontSize = 12.sp)
        Text("formerly Latent", color = LatentColors.TextDim, fontSize = 11.sp)

        Spacer(Modifier.height(22.dp))
        Text("ATTRIBUTION", color = LatentColors.TextDim, fontSize = 11.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        // Required notice under GPLv3 §7(b) — both lines, with clickable links.
        Text("Spektrafilm for Android by Akshay Sharma —", color = LatentColors.TextBright, fontSize = 14.sp, lineHeight = 19.sp)
        Link("https://github.com/thetechgeekko/Spektrafilm-android") { open(it) }
        Spacer(Modifier.height(12.dp))
        Text("Film modeling powered by spektrafilm (Andrea Volpato) —", color = LatentColors.TextBright, fontSize = 14.sp, lineHeight = 19.sp)
        Link("https://github.com/andreavolpato/spektrafilm") { open(it) }
        Spacer(Modifier.height(12.dp))
        // This app's own notice, on the same terms it asks of others. (The two above are the
        // upstream notices and stay word for word.)
        Text("Celestial Darkroom —", color = LatentColors.TextBright, fontSize = 14.sp, lineHeight = 19.sp)
        Link("https://github.com/itwasrajesh-jpg/celestial-darkroom") { open(it) }
        Spacer(Modifier.height(10.dp))
        Spacer(Modifier.height(18.dp))
        Text("TALK TO US", color = LatentColors.TextDim, fontSize = 10.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "Questions, feedback, or a shot you're proud of — the Discord is where it happens.",
            color = LatentColors.TextBright, fontSize = 14.sp, lineHeight = 19.sp,
        )
        Link("https://discord.gg/HfCeunz8Cs") { open(it) }
        Spacer(Modifier.height(18.dp))
        Text(
            "All three notices must be kept in any distribution of Celestial Darkroom or of a work derived from it.",
            color = LatentColors.TextDim, fontSize = 12.sp, lineHeight = 17.sp,
        )

        Spacer(Modifier.height(22.dp))
        Text("LICENCES", color = LatentColors.TextDim, fontSize = 11.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        Text("Celestial Darkroom is free software under the GNU General Public License v3.0. The film engine and its Android port are GPLv3; the film profiles and LUTs are CC BY-SA 4.0. RAW decoding uses LibRaw (LGPL-2.1 / CDDL-1.0).",
            color = LatentColors.Text, fontSize = 12.sp, lineHeight = 17.sp)
        Spacer(Modifier.height(8.dp))
        Link("Read the GPLv3 text") { open("https://www.gnu.org/licenses/gpl-3.0.html") }

        Spacer(Modifier.height(22.dp))
        Text("ENGINE BUILD", color = LatentColors.TextDim, fontSize = 11.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        Text("The engine is fetched at build time from a mirror of the Android port, pinned to commit ${ENGINE_COMMIT.take(10)}. Celestial Darkroom's source, including any local changes to the engine, is public.",
            color = LatentColors.Text, fontSize = 12.sp, lineHeight = 17.sp)

        Spacer(Modifier.height(22.dp))
        Text("CELESTIAL DARKROOM", color = LatentColors.TextDim, fontSize = 11.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        Text("Camera, develop flow and darkroom by Celestial.", color = LatentColors.Text, fontSize = 12.sp)
        Link("https://github.com/itwasrajesh-jpg/celestial-darkroom") { open(it) }
        Spacer(Modifier.height(8.dp))
        Text("Written with Anthropic's Claude, in conversation with the author.", color = LatentColors.TextDim, fontSize = 12.sp)

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun Link(text: String, onClick: (String) -> Unit) {
    Text(text, color = LatentColors.Amber, fontSize = 12.sp, textDecoration = TextDecoration.Underline, lineHeight = 17.sp,
        modifier = Modifier.combinedClickable(onClick = { onClick(text) }).padding(vertical = 4.dp))
}
