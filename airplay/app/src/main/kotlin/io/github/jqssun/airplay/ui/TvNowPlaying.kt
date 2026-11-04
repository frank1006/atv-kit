package io.github.jqssun.airplay.ui

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.core.graphics.scale
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.viewmodel.MainViewModel
import kotlinx.coroutines.delay

@Composable
fun TvNowPlaying(viewModel: MainViewModel, request: PlayRequest?, onRequest: (PlayRequest?) -> Unit) {
    val track by viewModel.trackInfo.collectAsState()
    val art = track.coverArt
    val blurred = remember(art) { art?.let { softBlur(it).asImageBitmap() } }

    Box(Modifier.fillMaxSize()) {
        Crossfade(blurred, animationSpec = tween(800), label = "artBg") { bg ->
            if (bg != null) {
                Image(
                    bg, null, contentScale = ContentScale.Crop, filterQuality = FilterQuality.High,
                    modifier = Modifier.fillMaxSize(), alpha = 0.85f
                )
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Black.copy(alpha = 0.72f)))
            )
        )

        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 76.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(300.dp)
                    .shadow(28.dp, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF242833)),
                contentAlignment = Alignment.Center
            ) {
                Crossfade(art, animationSpec = tween(500), label = "art") { a ->
                    if (a != null) {
                        Image(
                            a.asImageBitmap(), stringResource(R.string.cd_cover_art),
                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.MusicNote, null, tint = TvColors.Faint, modifier = Modifier.size(96.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.width(56.dp))
            Column(Modifier.weight(1f)) {
                // the sender's details arrive a second or two after the audio: until then a quiet
                // "Now playing" rather than "Unknown", and the real title fades in over it
                Crossfade(track.title, animationSpec = tween(400), label = "title") { title ->
                    Text(
                        title.ifEmpty { stringResource(R.string.tv_now_playing) },
                        color = if (title.isEmpty()) TvColors.Muted else TvColors.Text,
                        fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.8).sp,
                        lineHeight = 48.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
                if (track.artist.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(track.artist, color = TvColors.Muted, fontSize = 21.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (track.album.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(track.album, color = TvColors.Faint, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val player = viewModel.dacpPlayer
                if (player != null) {
                    Spacer(Modifier.height(30.dp))
                    TvTransport(player, request, onRequest)
                }
            }
        }
    }
}

@Composable
private fun TvTransport(player: Player, request: PlayRequest?, onRequest: (PlayRequest?) -> Unit) {
    var playWhenReady by remember(player) { mutableStateOf(player.playWhenReady) }
    var duration by remember(player) { mutableLongStateOf(player.duration) }
    var position by remember(player) { mutableLongStateOf(player.currentPosition) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                playWhenReady = p.playWhenReady
                duration = p.duration
                position = p.currentPosition
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    // the position moves without events, so tick it while playing, as Media3's own position text does
    LaunchedEffect(player, playWhenReady) {
        while (playWhenReady) {
            position = player.currentPosition
            delay(POSITION_TICK_MS)
        }
    }
    // drop the viewer's request once the sender has settled on it (see PlayRequest)
    LaunchedEffect(request) {
        while (request != null) {
            if (request.settled(player.playWhenReady, SystemClock.elapsedRealtime())) {
                onRequest(null)
                break
            }
            delay(REQUEST_CHECK_MS)
        }
    }
    // playWhenReady, as Media3's own button: isPlaying is false while the sender reports buffering
    val playing = request?.playing ?: playWhenReady
    // likewise the service moves the clock on during that stale "playing"; hold it where the pause was asked
    val frozenAt = remember(request) { if (request?.playing == false) player.currentPosition else null }
    val shownPosition = frozenAt ?: position
    val known = duration > 0
    val longForm = isLongForm(duration)
    val progress = animateFloatAsState(progressFraction(shownPosition, duration), tween(500), label = "progress")

    // the animated fraction is read only while drawing, so the bar animates without recomposing the row
    Box(
        Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f))
            .drawBehind {
                drawRoundRect(
                    TvColors.Text, size = size.copy(width = size.width * progress.value),
                    cornerRadius = CornerRadius(size.height / 2)
                )
            }
    )
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatTime(shownPosition), color = TvColors.Faint, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text(if (known) "−" + formatTime(duration - shownPosition) else "", color = TvColors.Faint, fontSize = 12.sp,
            fontWeight = FontWeight.Medium)
    }
    Spacer(Modifier.height(24.dp))

    val playFocus = remember { FocusRequester() }
    var playFocused by remember { mutableStateOf(false) }
    TvInitialFocus(playFocus) { playFocused }
    // the launcher's round buttons, as on the home screen, a size up for the 10-foot transport
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
        TvPillButton(
            if (longForm) Icons.Default.Replay else Icons.Default.SkipPrevious,
            stringResource(if (longForm) R.string.tv_cd_skip_back else R.string.tv_cd_previous),
            { player.seekToPrevious() }, diameter = 54.dp, iconSize = 28.dp
        )
        TvPillButton(
            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
            stringResource(R.string.cd_play_pause),
            {
                if (playing) player.pause() else player.play()
                onRequest(PlayRequest(!playing, SystemClock.elapsedRealtime()))
            },
            modifier = Modifier.focusRequester(playFocus), diameter = 66.dp, iconSize = 34.dp,
            onFocus = { playFocused = it }
        )
        TvPillButton(
            if (longForm) Icons.Default.Replay else Icons.Default.SkipNext,
            stringResource(if (longForm) R.string.tv_cd_skip_forward else R.string.tv_cd_next),
            { player.seekToNext() }, diameter = 54.dp, iconSize = 28.dp,
            // Material has no circular forward arrow; the replay arrow mirrored reads as one
            iconModifier = if (longForm) Modifier.graphicsLayer { scaleX = -1f } else Modifier
        )
    }
}

private const val REQUEST_CHECK_MS = 250L
private const val POSITION_TICK_MS = 500L

/**
 * A soft blur of [art] for the backdrop that works on any Android version (RenderEffect needs 12):
 * shrink, then grow in steps, so each bilinear pass smooths the last. One big stretch from a tiny copy
 * shows blocks on some TV GPUs.
 */
private fun softBlur(art: Bitmap): Bitmap {
    var b = art.scale(16, 16)
    for (size in intArrayOf(48, 24, 96, 48, 192)) b = b.scale(size, size)
    return b
}
