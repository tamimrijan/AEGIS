package com.aegis.vault.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aegis.vault.aegis
import com.aegis.vault.data.AuthOutcome
import com.aegis.vault.data.ItemType
import com.aegis.vault.data.LogEntry
import com.aegis.vault.data.LogEvent
import kotlinx.coroutines.launch

private fun scoreColor(score: Int): Color = when {
    score >= 90 -> AegisColors.Success
    score >= 75 -> AegisColors.Info
    score >= 50 -> AegisColors.Warning
    else -> AegisColors.Danger
}

@Composable
private fun ScoreRing(score: Int?) {
    val progress by animateFloatAsState(targetValue = (score ?: 0) / 100f, animationSpec = tween(900), label = "score")
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val color = if (score == null) track else scoreColor(score)
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(176.dp)) {
            val stroke = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
            val inset = stroke.width / 2
            val arcSize = Size(size.width - stroke.width, size.height - stroke.width)
            drawArc(track, 135f, 270f, false, Offset(inset, inset), arcSize, style = stroke)
            if (progress > 0f) drawArc(color, 135f, 270f * progress, false, Offset(inset, inset), arcSize, style = stroke)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(score?.toString() ?: "—", style = MaterialTheme.typography.displaySmall)
            Text("security score", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, color: Color, modifier: Modifier) {
    AegisCard(modifier, padding = 14.dp) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = color)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SecurityTab(nav: Nav) {
    val context = LocalContext.current
    val app = context.aegis
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()
    val report by app.vault.health.collectAsState()
    val items by app.vault.items.collectAsState()
    val logins = items.count { it.type == ItemType.LOGIN }
    var busy by remember { mutableStateOf(false) }
    val neutral = MaterialTheme.colorScheme.onSurface

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        AegisCard(Modifier.fillMaxWidth().padding(top = 4.dp), padding = 20.dp) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                ScoreRing(report?.score)
                Spacer(Modifier.height(8.dp))
                Text(report?.grade ?: "Not checked yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    report?.let { "Checked ${formatDate(it.checkedAt)}" }
                        ?: "Run a check to see your score.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                PrimaryButton(
                    if (report == null) "Run security check" else "Run again",
                    icon = Icons.Rounded.Fingerprint,
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            when (val r = app.vault.authorize(activity, "Security check", "Confirm your fingerprint to analyse your passwords on this device")) {
                                is AuthOutcome.Ok -> try {
                                    app.vault.runHealthCheck(r.keys)
                                } catch (e: Exception) {
                                    context.toast("The check could not be completed")
                                } finally {
                                    r.keys.wipe()
                                }
                                is AuthOutcome.Fail -> r.message?.let { context.toast(it) }
                            }
                            busy = false
                        }
                    },
                )
            }
        }

        SectionLabel("Overview")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Saved logins", "$logins", neutral, Modifier.weight(1f))
            StatTile("Weak", report?.weak?.toString() ?: "—", if ((report?.weak ?: 0) > 0) AegisColors.Danger else neutral, Modifier.weight(1f))
            StatTile("Reused", report?.reused?.toString() ?: "—", if ((report?.reused ?: 0) > 0) AegisColors.Warning else neutral, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Duplicates", report?.duplicate?.toString() ?: "—", if ((report?.duplicate ?: 0) > 0) AegisColors.Warning else neutral, Modifier.weight(1f))
            StatTile("Over a year old", report?.old?.toString() ?: "—", if ((report?.old ?: 0) > 0) AegisColors.Warning else neutral, Modifier.weight(1f))
            StatTile("All entries", "${items.size}", neutral, Modifier.weight(1f))
        }

        report?.let { r ->
            SectionLabel("Recommendations")
            AegisCard(Modifier.fillMaxWidth()) {
                r.recommendations.forEachIndexed { i, text ->
                    if (i > 0) Spacer(Modifier.height(10.dp))
                    Row {
                        Icon(
                            if (r.issues.isEmpty()) Icons.Rounded.CheckCircle else Icons.Rounded.Info, null,
                            tint = if (r.issues.isEmpty()) AegisColors.Success else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (r.issues.isNotEmpty()) {
                SectionLabel("Logins to fix")
                r.issues.forEach { issue ->
                    AegisCard(Modifier.fillMaxWidth().padding(vertical = 4.dp), onClick = { nav.push(Route.Detail(issue.itemId)) }, padding = 14.dp) {
                        Text(issue.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (issue.username.isNotBlank()) Text(
                            issue.username, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (issue.weak) Tag("Weak", AegisColors.Danger)
                            if (issue.reused) Tag("Reused", AegisColors.Warning)
                            if (issue.duplicate) Tag("Duplicate", AegisColors.Warning)
                            if (issue.old) Tag("Old", AegisColors.Neutral)
                        }
                    }
                }
            }
        }

        SectionLabel("Activity")
        AegisCard(Modifier.fillMaxWidth(), onClick = { nav.push(Route.Log) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.History)
                Spacer(Modifier.width(14.dp))
                Text("Activity log", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

private fun eventIcon(e: LogEvent?): ImageVector = when (e) {
    LogEvent.UNLOCK, LogEvent.VAULT_CREATED -> Icons.Rounded.LockOpen
    LogEvent.UNLOCK_FAILED -> Icons.Rounded.Warning
    LogEvent.ITEM_ADDED, LogEvent.TOTP_ADDED, LogEvent.AUTOFILL_SAVE -> Icons.Rounded.Add
    LogEvent.ITEM_EDITED -> Icons.Rounded.Edit
    LogEvent.ITEM_DELETED, LogEvent.TOTP_DELETED, LogEvent.LOG_CLEARED -> Icons.Rounded.Delete
    LogEvent.SECRET_REVEALED -> Icons.Rounded.Visibility
    LogEvent.SECRET_COPIED -> Icons.Rounded.ContentCopy
    LogEvent.AUTOFILL -> Icons.Rounded.Bolt
    LogEvent.HEALTH_CHECK -> Icons.Rounded.VerifiedUser
    LogEvent.BACKUP_CREATED -> Icons.Rounded.Backup
    LogEvent.BACKUP_RESTORED -> Icons.Rounded.Restore
    null -> Icons.Rounded.Info
}

@Composable
fun LogScreen(nav: Nav) {
    val app = LocalContext.current.aegis
    val scope = rememberCoroutineScope()
    val version by app.vault.logVersion.collectAsState()
    var logs by remember { mutableStateOf(emptyList<LogEntry>()) }
    LaunchedEffect(version) { logs = app.vault.readLogs() }
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Security activity") },
                navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = { TextButton(onClick = { confirmClear = true }, enabled = logs.isNotEmpty()) { Text("Clear") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            if (logs.isEmpty()) item { EmptyState(Icons.Rounded.History, "No activity yet", "Important vault events will appear here.") }
            items(logs) { entry ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(
                        eventIcon(entry.event), size = 40.dp, shape = CookieShape(6, 0.07f),
                        container = MaterialTheme.colorScheme.surfaceContainerHigh,
                        content = if (entry.event == LogEvent.UNLOCK_FAILED) AegisColors.Warning else MaterialTheme.colorScheme.secondary,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.event?.label ?: entry.rawEvent, style = MaterialTheme.typography.titleSmall)
                        entry.detail?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text(formatDate(entry.time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (confirmClear) {
        ConfirmDialog(
            title = "Clear activity history?",
            text = "All recorded events are deleted.",
            confirm = "Clear", danger = true,
            onConfirm = {
                confirmClear = false
                scope.launch { app.vault.clearLogs() }
            },
            onDismiss = { confirmClear = false },
        )
    }
}
