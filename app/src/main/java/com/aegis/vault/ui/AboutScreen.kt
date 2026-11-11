package com.aegis.vault.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aegis.vault.BuildConfig
import com.aegis.vault.R

/**
 * Developer details shown on the About tab. Edit, rebuild, done. A blank link hides its row.
 * The photo is res/drawable-nodpi/developer_photo.jpg.
 */
object AboutInfo {
    const val DEVELOPER_NAME = "Tamim Ahmed Rijan"
    const val DEVELOPER_ROLE = "Developer"
    const val GITHUB_URL = "https://github.com/tamimrijan"
    const val INSTAGRAM_URL = "https://www.instagram.com/tamim_ahmed_rijan/"
    const val LICENSE = "MIT License · © 2026 Tamim Ahmed Rijan"
}

@Composable
private fun LinkRow(icon: ImageVector, label: String, url: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, size = 40.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Text(
                    url.removePrefix("https://").removePrefix("www.").trimEnd('/'),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
            Icon(Icons.Rounded.OpenInNew, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(end = 10.dp).size(18.dp))
        }
    }
}

@Composable
private fun Pill(text: String) {
    Box(Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onTertiaryContainer)
    }
}

/** The About tab of Settings. */
@Composable
fun AboutContent() {
    val context = LocalContext.current
    val main = remember(context) { context.findActivity() as? MainActivity }
    fun open(url: String) = main?.openSystemScreen(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    AegisCard(Modifier.fillMaxWidth(), padding = 24.dp, color = MaterialTheme.colorScheme.primaryContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(84.dp).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) { AegisLogo(58.dp) }
            Spacer(Modifier.width(18.dp))
            Column {
                Text("AEGIS", style = MaterialTheme.typography.headlineMedium, letterSpacing = 2.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    AegisCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.developer_photo),
                contentDescription = AboutInfo.DEVELOPER_NAME,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(92.dp).clip(CookieShape(9, 0.06f)),
            )
            Spacer(Modifier.width(18.dp))
            Column {
                Text(AboutInfo.DEVELOPER_NAME, style = MaterialTheme.typography.titleLarge)
                Text(AboutInfo.DEVELOPER_ROLE, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (AboutInfo.GITHUB_URL.isNotBlank()) LinkRow(Icons.Rounded.Code, "GitHub", AboutInfo.GITHUB_URL) { open(AboutInfo.GITHUB_URL) }
        if (AboutInfo.INSTAGRAM_URL.isNotBlank()) LinkRow(Icons.Rounded.PhotoCamera, "Instagram", AboutInfo.INSTAGRAM_URL) { open(AboutInfo.INSTAGRAM_URL) }
    }

    Spacer(Modifier.height(12.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("No account")
        Pill("No cloud")
        Pill("No internet permission")
        Pill("No tracking")
    }

    SectionLabel("Licences")
    AegisCard(Modifier.fillMaxWidth()) {
        Text(AboutInfo.LICENSE, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Google Sans Flex, Google Sans Code · SIL OFL 1.1\nAndroidX, Compose, Kotlin · Apache 2.0",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
