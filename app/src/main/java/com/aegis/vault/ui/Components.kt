package com.aegis.vault.ui

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.FragmentActivity
import com.aegis.vault.R
import com.aegis.vault.aegis
import com.aegis.vault.data.ItemType
import com.aegis.vault.data.VaultItem
import com.aegis.vault.logic.Strength
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Base for every AEGIS screen: blocks screenshots, screen recording and the recents preview,
 * refuses overlay windows, and feeds user activity to the auto-lock timer.
 */
open class SecureActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyWindowSecurity()
    }

    fun applyWindowSecurity() {
        val block = aegis.prefs.blockScreenshots.value
        if (block) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 31) runCatching { window.setHideOverlayWindows(true) }
        if (Build.VERSION.SDK_INT >= 33) runCatching { setRecentsScreenshotEnabled(!block) }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        aegis.autoLock.touch()
    }
}

fun Context.findActivity(): FragmentActivity {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is FragmentActivity) return c
        c = c.baseContext
    }
    error("Not hosted in a FragmentActivity")
}

fun Context.toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

fun formatDate(ms: Long): String =
    if (ms <= 0) "—" else SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))

fun typeIcon(type: ItemType): ImageVector = when (type) {
    ItemType.LOGIN -> Icons.Rounded.Key
    ItemType.NOTE -> Icons.Rounded.Description
    ItemType.CARD -> Icons.Rounded.CreditCard
    ItemType.IDENTITY -> Icons.Rounded.Badge
    ItemType.TOTP -> Icons.Rounded.Timer
}

fun strengthColor(score: Int): Color = when (score) {
    0, 1 -> AegisColors.Danger
    2 -> AegisColors.Warning
    else -> AegisColors.Success
}

private val LOGO_SHIELD by lazy {
    PathParser().parsePathString("M54 36.5L68.5 42.2V54C68.5 63 62.5 69.8 54 73C45.5 69.8 39.5 63 39.5 54V42.2Z").toPath()
}
private val LOGO_CHECK by lazy { PathParser().parsePathString("M47.3 54.6L52.4 59.7L61.2 50.2").toPath() }
private val LOGO_PETALS = listOf(
    77f to 54f, 70.26f to 70.26f, 54f to 77f, 37.74f to 70.26f,
    31f to 54f, 37.74f to 37.74f, 54f to 31f, 70.26f to 37.74f,
)

/**
 * The AEGIS mark ("Bloom": a scalloped Expressive badge holding a verified shield), drawn in
 * the current theme's colours. Same geometry as branding/logo-5-bloom.svg.
 */
@Composable
fun AegisLogo(size: Dp = 72.dp) {
    val badge = MaterialTheme.colorScheme.primary
    val shield = MaterialTheme.colorScheme.onPrimary
    Canvas(Modifier.size(size)) {
        val k = this.size.height / 71f
        scale(k, k, pivot = Offset.Zero) {
            translate(-18.5f, -18.5f) {
                drawCircle(badge, radius = 27f, center = Offset(54f, 54f))
                LOGO_PETALS.forEach { (x, y) -> drawCircle(badge, radius = 12.5f, center = Offset(x, y)) }
                drawPath(LOGO_SHIELD, shield)
                drawPath(LOGO_CHECK, badge, style = Stroke(width = 4.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** Expressive container: a tonal fill and large corners instead of an outline. */
@Composable
fun AegisCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: Dp = 18.dp,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = color) {
            Column(Modifier.padding(padding), content = content)
        }
    } else {
        Surface(modifier = modifier, shape = shape, color = color) {
            Column(Modifier.padding(padding), content = content)
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 8.dp, top = 24.dp, bottom = 10.dp),
    )
}

/**
 * Expressive shape morph: a control rests as a full pill and squares up while pressed,
 * with a springy return.
 */
@Composable
fun morphShape(source: InteractionSource, rest: Dp = 28.dp, pressed: Dp = 12.dp): Shape {
    val isPressed by source.collectIsPressedAsState()
    val radius by animateDpAsState(
        if (isPressed) pressed else rest,
        spring(dampingRatio = 0.55f, stiffness = 700f), label = "morph",
    )
    return RoundedCornerShape(radius)
}

/** Large primary call to action. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    Button(
        onClick = onClick, enabled = enabled, interactionSource = source, shape = morphShape(source),
        modifier = modifier.fillMaxWidth().height(56.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/** Secondary large action on a tonal fill. */
@Composable
fun TonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    FilledTonalButton(
        onClick = onClick, enabled = enabled, interactionSource = source, shape = morphShape(source),
        modifier = modifier.fillMaxWidth().height(56.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * Material Expressive "cookie" shape: a circle whose edge ripples into soft lobes.
 * Few lobes read as a clover, many as a scalloped badge.
 */
class CookieShape(private val lobes: Int = 9, private val depth: Float = 0.07f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = minOf(cx, cy)
        val steps = lobes * 24
        val path = Path()
        for (i in 0..steps) {
            val t = (2.0 * PI * i / steps)
            val radius = r * (1f - depth + depth * cos(lobes * t).toFloat())
            val x = cx + radius * cos(t - PI / 2).toFloat()
            val y = cy + radius * sin(t - PI / 2).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }
}

/** Icon on a tonal Expressive shape. */
@Composable
fun IconBadge(
    icon: ImageVector,
    size: Dp = 42.dp,
    shape: Shape = CookieShape(8, 0.06f),
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Box(Modifier.size(size).clip(shape).background(container), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = content, modifier = Modifier.size(size * 0.5f))
    }
}

/** Connected pill tabs: one track, the active tab filled with the primary colour. */
@Composable
fun PillTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().clip(CircleShape).background(colors.surfaceContainerHigh).padding(4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val active = i == selected
            val bg by animateColorAsState(if (active) colors.primary else Color.Transparent, label = "tabBg")
            val fg by animateColorAsState(if (active) colors.onPrimary else colors.onSurfaceVariant, label = "tabFg")
            Box(
                Modifier.weight(1f).height(44.dp).clip(CircleShape).background(bg)
                    .selectable(selected = active, role = Role.Tab, onClick = { onSelect(i) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

/** Extended floating action button that morphs from a soft square to a pill while pressed. */
@Composable
fun ExpressiveFab(text: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { Icon(icon, null) },
        text = { Text(text, style = MaterialTheme.typography.titleMedium) },
        interactionSource = source,
        shape = morphShape(source, rest = 20.dp, pressed = 32.dp),
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier.height(64.dp),
    )
}

/** Pill-shaped search bar on a tonal fill. */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    TextField(
        value = value, onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = {
            if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, "Clear search") }
        },
        singleLine = true,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
    )
}

/** Corner shape for row [index] of a grouped list: big outer corners, small inner ones. */
fun segmentShape(index: Int, count: Int): Shape {
    val outer = 24.dp
    val inner = 6.dp
    return RoundedCornerShape(
        topStart = if (index == 0) outer else inner, topEnd = if (index == 0) outer else inner,
        bottomStart = if (index == count - 1) outer else inner, bottomEnd = if (index == count - 1) outer else inner,
    )
}

@Composable
fun ItemAvatar(item: VaultItem, size: Dp = 44.dp) {
    val context = LocalContext.current
    val pkg = item.packages.firstOrNull()
    val icon = remember(pkg) {
        pkg?.let { p ->
            runCatching { context.packageManager.getApplicationIcon(p).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
    val shape = RoundedCornerShape(size * 0.3f)
    if (icon != null) {
        Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(size).clip(shape))
        return
    }
    val hue = ((item.title.lowercase().hashCode() % 360) + 360) % 360
    val brush = Brush.linearGradient(
        listOf(Color.hsv(hue.toFloat(), 0.42f, 0.58f), Color.hsv(((hue + 35) % 360).toFloat(), 0.5f, 0.34f))
    )
    Box(Modifier.size(size).clip(shape).background(brush), contentAlignment = Alignment.Center) {
        if (item.type == ItemType.LOGIN || item.type == ItemType.TOTP) {
            Text(
                item.title.trim().take(1).uppercase().ifEmpty { "?" },
                color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.42f).sp,
            )
        } else {
            Icon(typeIcon(item.type), null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
        }
    }
}

@Composable
fun StrengthBar(strength: Strength, modifier: Modifier = Modifier, bitsOverride: Double? = null) {
    val color = strengthColor(strength.score)
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(4) { i ->
                Box(
                    Modifier.weight(1f).height(5.dp).clip(CircleShape)
                        .background(if (i < strength.score || (i == 0 && strength.bits > 0)) color else MaterialTheme.colorScheme.surfaceContainerHighest)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row {
            Text(strength.label, style = MaterialTheme.typography.labelMedium, color = color)
            Spacer(Modifier.weight(1f))
            Text(
                "≈ ${(bitsOverride ?: strength.bits).toInt()} bits",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(icon, size = 88.dp, shape = CookieShape(12, 0.05f))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Bottom sheet surface used by the translucent autofill and Quick Vault activities. */
@Composable
fun SheetScaffold(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val none = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxSize().clickable(interactionSource = none, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                Modifier.navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 20.dp),
                content = content,
            )
        }
    }
}

@Composable
fun SheetHeader(title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AegisLogo(36.dp)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun CredentialRow(item: VaultItem, onClick: () -> Unit, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ItemAvatar(item, 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                item.subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke()
    }
}

/**
 * Searchable list of logins: [suggested] first, then everything else. Shows only names,
 * usernames and websites; never a password.
 */
@Composable
fun CredentialPicker(
    suggested: List<VaultItem>,
    all: List<VaultItem>,
    selectedId: String? = null,
    selectedContent: @Composable ((VaultItem) -> Unit)? = null,
    onPick: (VaultItem) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    SearchField(query, { query = it }, "Search your vault")
    Spacer(Modifier.height(8.dp))
    val suggestedIds = remember(suggested) { suggested.map { it.id }.toSet() }
    val top = if (query.isBlank()) suggested else emptyList()
    val rest = all.filter { it.matches(query) && (query.isNotBlank() || it.id !in suggestedIds) }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
        if (top.isNotEmpty()) {
            item { SectionLabel("Matching logins", Modifier.padding(top = 0.dp)) }
            items(top, key = { "s" + it.id }) { item ->
                CredentialRow(item, onClick = { onPick(item) })
                if (item.id == selectedId) selectedContent?.invoke(item)
            }
            if (rest.isNotEmpty()) item { SectionLabel("All logins") }
        }
        items(rest, key = { it.id }) { item ->
            CredentialRow(item, onClick = { onPick(item) })
            if (item.id == selectedId) selectedContent?.invoke(item)
        }
        if (top.isEmpty() && rest.isEmpty()) {
            item {
                Text(
                    if (all.isEmpty()) "Your vault has no logins yet." else "No login matches “$query”.",
                    modifier = Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
