package io.github.jqssun.airplay.ui

import android.text.format.DateFormat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import io.github.jqssun.airplay.R
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Date

// 10-foot palette shared by the TV screens: near-black, translucent "glass", white focus
object TvColors {
    val Background = Color(0xFF07080C)
    val Text = Color(0xFFF5F6F8)
    val Muted = Text.copy(alpha = 0.62f)
    val Faint = Text.copy(alpha = 0.38f)
    val Glass = Color.White.copy(alpha = 0.075f)
    val Stroke = Color.White.copy(alpha = 0.12f)
    val FocusText = Color(0xFF0B0D12)
    val Ok = Color(0xFF34D399)
    val Busy = Color(0xFFFBBF24)
    val Warn = Color(0xFFF87171)
    val Idle = Color(0xFF9CA3AF)
}

// Outfit, the CleanYourTV launcher's face, so this app matches the TV's home screen.
// One variable font; Android 8 and older draw its default instance (SIL OFL, see app/FONT-LICENSE-OFL.txt).
@OptIn(ExperimentalTextApi::class)
private fun outfit(w: Int) = Font(
    R.font.outfit, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w))
)
val Outfit = FontFamily(outfit(300), outfit(400), outfit(500), outfit(600))

// soft spring for the focus lift: settles quickly without a visible bounce
private val liftSpring = spring<Float>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
private val fadeSpec = tween<Color>(durationMillis = 220)

@Composable
fun TvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = TvColors.Text,
            onPrimary = TvColors.FocusText,
            background = TvColors.Background,
            surface = Color(0xFF15171D),
            surfaceVariant = Color(0xFF1C1F27),
            surfaceContainerHighest = Color(0xFF242833),
            onSurface = TvColors.Text,
            onSurfaceVariant = TvColors.Muted,
        ),
        content = content
    )
}

fun Modifier.tvGlass(shape: Shape): Modifier =
    clip(shape).background(TvColors.Glass).border(1.dp, TvColors.Stroke, shape)

/** Full-screen backdrop: soft colour glows on near-black; colours glide when they change. */
@Composable
fun TvBackdrop(glows: List<Color>, modifier: Modifier = Modifier) {
    val spots = listOf(Offset(0.10f, 0.15f) to 0.42f, Offset(0.92f, 0.55f) to 0.38f, Offset(0.52f, 1.15f) to 0.34f)
    val colors = spots.indices.map { i ->
        animateColorAsState(glows.getOrElse(i) { Color.Transparent }, tween(700), label = "glow$i")
    }
    // the colours are read while drawing, not composing, and the three full-screen gradients are kept in
    // their own offscreen layer, so they are only repainted while the colours change
    Box(
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .background(TvColors.Background)
            .drawBehind {
                spots.forEachIndexed { i, (at, r) ->
                    val center = Offset(size.width * at.x, size.height * at.y)
                    val radius = size.width * r
                    val c = colors[i].value
                    drawCircle(
                        Brush.radialGradient(listOf(c.copy(alpha = c.alpha * 0.55f), Color.Transparent), center, radius),
                        radius, center
                    )
                }
            }
    )
}

/**
 * Focusable surface that lifts, turns white and casts a shadow when focused, like a TV home screen.
 * [content] gets whether it is focused so it can swap icons or colours.
 */
@Composable
fun TvFocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    focusScale: Float = 1.08f,
    glass: Boolean = true,
    selected: Boolean = false,
    onFocus: (Boolean) -> Unit = {},
    content: @Composable (focused: Boolean) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusScale else 1f, liftSpring, label = "lift")
    val bg = animateColorAsState(
        when {
            focused -> TvColors.Text
            selected -> Color.White.copy(alpha = 0.16f)
            glass -> TvColors.Glass
            else -> Color.Transparent
        }, fadeSpec, label = "surfaceBg"
    )
    // fade the label with the fill, or it flashes white-on-white while focus moves
    val fg by animateColorAsState(
        if (focused) TvColors.FocusText else if (selected) TvColors.Text else TvColors.Muted, fadeSpec, label = "surfaceFg"
    )
    val stroke = animateColorAsState(if (glass && !focused) TvColors.Stroke else Color.Transparent, fadeSpec, label = "stroke")
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(if (focused) 18.dp else 0.dp, shape, clip = false)
            .clip(shape)
            // fill and rim are read while drawing, so the 220 ms fade does not recompose the content
            .drawBehind {
                val outline = shape.createOutline(size, layoutDirection, this)
                drawOutline(outline, bg.value)
                drawOutline(outline, stroke.value, style = Stroke(1.dp.toPx()))
            }
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides fg) { content(focused) }
    }
}

// the launcher's round top-bar button (pill_bg.xml, TopButtonIcon): a frosted gradient with a thin rim at rest,
// near-white with a dark icon when focused, and a 4 % zoom, no shadow
private val PillTop = Color.White.copy(alpha = 0x2E / 255f)
private val PillBottom = Color.White.copy(alpha = 0x14 / 255f)
private val PillRim = Color.White.copy(alpha = 0x38 / 255f)
private val PillFocus = Color.White.copy(alpha = 0xF5 / 255f)
private val PillFocusIcon = Color(0xFF111111)

/** An icon-only round button drawn as the CleanYourTV launcher draws its Wi-Fi and Settings buttons. */
@Composable
fun TvPillButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 42.dp,
    iconSize: Dp = 22.dp,
    iconModifier: Modifier = Modifier,
    onFocus: (Boolean) -> Unit = {}
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, tween(160), label = "pillScale")
    val focus = animateFloatAsState(if (focused) 1f else 0f, tween(160), label = "pillFocus")
    val iconTint by animateColorAsState(if (focused) PillFocusIcon else Color.White, tween(160), label = "pillIcon")
    Box(
        modifier = modifier
            .size(diameter)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .drawBehind {
                val r = size.minDimension / 2f
                val f = focus.value
                drawCircle(Brush.verticalGradient(listOf(PillTop, PillBottom)), r, alpha = 1f - f)
                drawCircle(PillRim, r - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()), alpha = 1f - f)
                drawCircle(PillFocus, r, alpha = f)
            }
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, description, tint = iconTint, modifier = iconModifier.size(iconSize))
    }
}

/** Requests [requester] once the window can take input focus; an earlier request is dropped. */
@Composable
fun TvInitialFocus(requester: FocusRequester, isFocused: () -> Boolean) {
    var landed by remember(requester) { mutableStateOf(false) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(requester, windowFocused) {
        if (windowFocused && !landed) landed = requester.requestFocusUntilLanded(attempts = 30, isFocused = isFocused)
    }
}

/** The launcher's clock: large light time, small AM/PM, a thin divider and the spaced-out date. */
@Composable
fun TvClock(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(60_000L - System.currentTimeMillis() % 60_000L)
        }
    }
    val is24 = DateFormat.is24HourFormat(context)
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(
            DateFormat.format(if (is24) "HH:mm" else "h:mm", now).toString(), color = TvColors.Text,
            fontFamily = Outfit, fontWeight = FontWeight.Light, fontSize = 52.sp, letterSpacing = (-2).sp,
            lineHeight = 52.sp,
            // equal-width digits, as the launcher's clock: the divider and date hold still as the minutes change
            style = TextStyle(fontFeatureSettings = "tnum"),
            modifier = Modifier.alignByBaseline()
        )
        if (!is24) {
            Text(
                DateFormat.format("a", now).toString().uppercase(), color = TvColors.Text.copy(alpha = 0.7f),
                fontFamily = Outfit, fontWeight = FontWeight.Medium, fontSize = 15.sp, letterSpacing = 1.2.sp,
                // on the digits' baseline, as the launcher sets it; the start gap was measured against its screenshot
                // on a 1080p TV, to the pixel
                modifier = Modifier.alignByBaseline().padding(start = 5.5.dp)
            )
        }
        Box(Modifier.padding(start = 16.dp, end = 15.5.dp, bottom = 8.5.dp).width(1.dp).height(26.dp)
            .background(Color.White.copy(alpha = 0.2f)))
        Text(
            DateFormat.format("EEEE d MMMM", now).toString().uppercase(), color = TvColors.Text.copy(alpha = 0.65f),
            fontFamily = Outfit, fontWeight = FontWeight.Normal, fontSize = 14.sp, letterSpacing = 2.sp,
            modifier = Modifier.alignByBaseline()
        )
    }
}

/**
 * The home screen's big mark: a glass tile with [icon] in the status [color], a soft glow of the same colour
 * behind it, and a slow pulse while [busy]. Colours glide when the state changes.
 */
@Composable
fun TvStatusIcon(icon: ImageVector, color: Color, busy: Boolean, description: String, modifier: Modifier = Modifier) {
    val tint by animateColorAsState(color, tween(500), label = "statusTint")
    val pulse = if (busy) {
        rememberInfiniteTransition(label = "statusPulse").animateFloat(
            0.35f, 0.85f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "statusGlow"
        ).value
    } else 0.6f
    val shape = RoundedCornerShape(34.dp)
    // the glow is drawn past the tile's bounds, so it takes no layout space
    Box(
        modifier
            .size(128.dp)
            .drawBehind {
                val r = size.minDimension * 0.72f
                drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = 0.45f * pulse), Color.Transparent), radius = r), r)
            }
            .clip(shape)
            .background(TvColors.Glass)
            .border(1.5.dp, tint.copy(alpha = 0.35f + 0.4f * pulse), shape),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(68.dp))
    }
}
