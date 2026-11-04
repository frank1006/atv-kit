package io.github.jqssun.airplay.ui

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Airplay
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jqssun.airplay.Prefs
import kotlinx.coroutines.launch
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.service.AirPlayService.ServerState
import io.github.jqssun.airplay.viewmodel.LocalNetworkAccess
import io.github.jqssun.airplay.viewmodel.MainViewModel

private enum class TvRoute { HOME, NOW_PLAYING, SETTINGS, LOGS }

private val homeGlows = listOf(Color(0xFF1D4ED8), Color(0xFF7C3AED), Color(0xFF0E7490))
private val settingsGlows = listOf(Color(0xFF334155), Color(0xFF1E3A8A), Color(0xFF0F172A))
private val logsGlows = listOf(Color(0xFF1F2937), Color(0xFF115E59), Color(0xFF0F172A))

data class TvAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

/** Everything the TV shows outside full-screen video: home, now playing, settings and logs. */
@Composable
fun TvRoot(viewModel: MainViewModel, onShowMirroring: () -> Unit, onLocalNetwork: () -> Unit) {
    val state by viewModel.serverState.collectAsState()
    val connections by viewModel.connectionCount.collectAsState()
    val audioOnly by viewModel.audioOnly.collectAsState()
    val audioActive = audioOnly && connections > 0 && state == ServerState.RUNNING

    var route by remember { mutableStateOf(TvRoute.HOME) }
    val scope = rememberCoroutineScope()
    // audio takes over the screen until the viewer backs out of it
    var audioDismissed by remember { mutableStateOf(false) }
    // held here, above the routes, so rebuilding the now playing screen cannot lose it
    var playRequest by remember { mutableStateOf<PlayRequest?>(null) }
    // Settings' scroll and focused row survive a visit to Logs, so Back lands where the viewer left
    val settingsList = rememberLazyListState()
    val settingsFocus = remember { SettingsFocus() }
    LaunchedEffect(audioActive) {
        if (!audioActive) {
            audioDismissed = false
            if (route == TvRoute.NOW_PLAYING) route = TvRoute.HOME
        }
    }
    val shown = if (route == TvRoute.HOME && audioActive && !audioDismissed) TvRoute.NOW_PLAYING else route

    BackHandler(enabled = shown != TvRoute.HOME) {
        when (shown) {
            TvRoute.NOW_PLAYING -> { audioDismissed = true; route = TvRoute.HOME }
            TvRoute.LOGS -> route = TvRoute.SETTINGS
            else -> route = TvRoute.HOME
        }
    }

    Box(Modifier.fillMaxSize()) {
        TvBackdrop(
            when (shown) {
                TvRoute.SETTINGS -> settingsGlows
                TvRoute.LOGS -> logsGlows
                else -> homeGlows
            },
            Modifier.fillMaxSize()
        )
        AnimatedContent(
            targetState = shown,
            // a short cross-fade: zooming whole pages redrew the full screen at a new size every frame, which a
            // Mi Box's GPU cannot do in time
            transitionSpec = {
                fadeIn(tween(220, delayMillis = 60, easing = FastOutSlowInEasing)) togetherWith fadeOut(tween(140))
            },
            label = "tvRoute"
        ) { r ->
            when (r) {
                TvRoute.HOME -> TvHomeScreen(
                    viewModel,
                    onSettings = {
                        // a fresh visit from home starts at the top
                        settingsFocus.title = null
                        scope.launch { settingsList.scrollToItem(0) }
                        route = TvRoute.SETTINGS
                    },
                    onShowMirroring = onShowMirroring,
                    onNowPlaying = { audioDismissed = false; route = TvRoute.HOME },
                    onLocalNetwork = onLocalNetwork
                )
                TvRoute.NOW_PLAYING -> TvNowPlaying(viewModel, playRequest) { playRequest = it }
                TvRoute.SETTINGS -> TvSettings(
                    viewModel, settingsList, settingsFocus,
                    onLogs = { route = TvRoute.LOGS }
                )
                TvRoute.LOGS -> TvLogs(viewModel)
            }
        }
    }
}

@Composable
private fun TvHomeScreen(
    viewModel: MainViewModel,
    onSettings: () -> Unit,
    onShowMirroring: () -> Unit,
    onNowPlaying: () -> Unit,
    onLocalNetwork: () -> Unit
) {
    val state by viewModel.serverState.collectAsState()
    val localNetwork by viewModel.localNetwork.collectAsState()
    val connections by viewModel.connectionCount.collectAsState()
    val audioOnly by viewModel.audioOnly.collectAsState()
    val mirroring by viewModel.mirroringActive.collectAsState()
    val serverName by viewModel.pref(Prefs.SERVER_NAME).collectAsState()
    val linked = rememberLocalLink()
    val grant = state != ServerState.RUNNING && localNetwork != LocalNetworkAccess.GRANTED
    val running = state == ServerState.RUNNING

    val stop = TvAction(Icons.Default.Stop, stringResource(R.string.btn_stop), viewModel::stopServer)
    val start = TvAction(Icons.Default.PlayArrow, stringResource(R.string.btn_start), viewModel::startServer)
    // status: dot colour, whether it pulses, text; then the actions, the first one taking focus
    data class Status(
        val color: Color, val busy: Boolean, val text: String, val actions: List<TvAction>, val focusAction: Boolean
    )
    val s = when {
        // nothing can reach the receiver: say so before anything about the server, which keeps its own actions
        !linked -> Status(
            TvColors.Warn, false, stringResource(R.string.tv_status_no_network),
            listOf(if (running) stop else start), false
        )
        grant -> {
            val settings = localNetwork == LocalNetworkAccess.SETTINGS
            Status(
                TvColors.Warn, false, stringResource(R.string.local_network_label),
                listOf(TvAction(
                    if (settings) Icons.Default.Settings else Icons.Default.LockOpen,
                    stringResource(if (settings) R.string.btn_open_settings else R.string.btn_allow), onLocalNetwork
                )), true
            )
        }
        state == ServerState.ERROR -> Status(TvColors.Warn, false, stringResource(R.string.error_starting_server), listOf(start), true)
        !running -> Status(TvColors.Idle, false, stringResource(R.string.server_stopped), listOf(start), true)
        mirroring -> Status(
            TvColors.Ok, false, stringResource(R.string.tv_status_mirroring),
            listOf(TvAction(Icons.Default.Fullscreen, stringResource(R.string.tv_show_mirroring), onShowMirroring), stop), true
        )
        audioOnly && connections > 0 -> Status(
            TvColors.Ok, false, stringResource(R.string.tv_status_audio),
            listOf(TvAction(Icons.Default.MusicNote, stringResource(R.string.tv_now_playing), onNowPlaying), stop), true
        )
        connections > 0 -> Status(TvColors.Busy, true, stringResource(R.string.tv_status_connecting), listOf(stop), false)
        else -> Status(TvColors.Ok, false, stringResource(R.string.tv_status_ready), listOf(stop), false)
    }

    val gearFocus = remember { FocusRequester() }
    val actionFocus = remember { FocusRequester() }
    var gearFocused by remember { mutableStateOf(false) }
    var actionFocused by remember { mutableStateOf(false) }
    if (s.focusAction) TvInitialFocus(actionFocus) { actionFocused } else TvInitialFocus(gearFocus) { gearFocused }

    Box(Modifier.fillMaxSize()) {
        // top left: the launcher's clock and date
        TvClock(Modifier.align(Alignment.TopStart).padding(start = 68.dp, top = 26.dp))
        // top right: the actions, then settings; the launcher's round buttons, at its size, spacing and height
        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 68.dp, top = 39.5.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            s.actions.forEachIndexed { i, a ->
                TvPillButton(
                    a.icon, a.label, a.onClick,
                    modifier = if (i == 0) Modifier.focusRequester(actionFocus) else Modifier,
                    onFocus = { if (i == 0) actionFocused = it }
                )
            }
            TvPillButton(
                Icons.Default.Settings, stringResource(R.string.tab_settings), onSettings,
                modifier = Modifier.focusRequester(gearFocus),
                onFocus = { gearFocused = it }
            )
        }

        // receiver: the AirPlay mark alone carries the status, so nothing below the name ever moves
        Column(Modifier.align(Alignment.Center).padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            TvStatusIcon(Icons.Default.Airplay, s.color, s.busy, s.text)
            Spacer(Modifier.height(30.dp))
            Text(
                serverName, color = TvColors.Text, fontFamily = Outfit, fontWeight = FontWeight.Light,
                fontSize = 64.sp, letterSpacing = (-1.3).sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 80.dp)
            )
        }
    }
}

/** The TV's networks AirPlay can be reached on: Wi-Fi or Ethernet, with or without internet. */
private val localLinks = NetworkRequest.Builder()
    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    .build()

/** Whether the TV has a Wi-Fi or Ethernet link now, following it as it comes and goes. */
@Composable
private fun rememberLocalLink(): Boolean {
    val cm = LocalContext.current.getSystemService(ConnectivityManager::class.java) ?: return true
    // read once up front: with no link at all the callback below never fires
    var linked by remember {
        mutableStateOf(cm.allNetworks.any { n ->
            cm.getNetworkCapabilities(n)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            } == true
        })
    }
    DisposableEffect(cm) {
        val up = mutableSetOf<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { up += network; linked = true }
            override fun onLost(network: Network) { up -= network; linked = up.isNotEmpty() }
        }
        // callbacks arrive one at a time on the connectivity thread; Compose state may be written from there
        cm.registerNetworkCallback(localLinks, callback)
        onDispose { cm.unregisterNetworkCallback(callback) }
    }
    return linked
}
