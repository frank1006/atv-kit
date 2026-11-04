package io.github.jqssun.airplay.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.jqssun.airplay.BoolPref
import io.github.jqssun.airplay.Pref
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.viewmodel.MainViewModel

// Every option of SettingsScreen, in its sections and with the same developer-options gate, as rows a
// remote can drive: OK toggles or cycles, Left/Right steps a slider value, and text opens an editor.
private sealed class TvRow(val title: String, val desc: String?) {
    class Switch(title: String, desc: String?, val on: Boolean, val toggle: () -> Unit) : TvRow(title, desc)
    class Value(title: String, desc: String?, val value: String, val onClick: () -> Unit) : TvRow(title, desc)
    class Stepper(title: String, desc: String?, val value: String, val step: (Int) -> Unit) : TvRow(title, desc)
}

private class TextEdit(val title: String, val initial: String, val numeric: Boolean, val commit: (String) -> Boolean)

private const val CUSTOM = "\u0000custom"

@Composable
fun TvSettings(
    viewModel: MainViewModel,
    listState: LazyListState,
    lastFocus: SettingsFocus,
    onLogs: () -> Unit,
) {
    @Composable
    fun <T : Any> v(p: Pref<T>): T = viewModel.pref(p).collectAsState().value
    var editing by remember { mutableStateOf<TextEdit?>(null) }

    fun switch(title: String, desc: String?, p: BoolPref, on: Boolean) = TvRow.Switch(title, desc, on) { viewModel.set(p, !on) }
    // a free-text pref: the editor refuses what the pref would not accept
    fun <T : Any> edit(title: String, desc: String?, p: Pref<T>, value: T, numeric: Boolean, show: String = value.toString()) =
        TvRow.Value(title, desc, show) {
            editing = TextEdit(title, value.toString(), numeric) { t -> parseFor(p, t)?.also { viewModel.set(p, it) } != null }
        }
    // preset values cycled with OK; reaching CUSTOM, when offered, opens the editor instead
    fun cycle(title: String, desc: String?, current: String, options: List<Pair<String, String>>, set: (String) -> Unit,
              custom: TextEdit? = null): TvRow.Value {
        val keys = options.map { it.first } + listOfNotNull(custom?.let { CUSTOM })
        val shown = options.firstOrNull { it.first == current }?.second ?: current
        return TvRow.Value(title, desc, shown) {
            val next = nextOption(keys, current)
            if (next == CUSTOM) editing = custom else set(next)
        }
    }

    val tri = listOf(
        Prefs.AUTO to stringResource(R.string.chip_auto),
        Prefs.ON to stringResource(R.string.chip_on),
        Prefs.OFF to stringResource(R.string.chip_off),
    )
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasOverlayPermission by remember { mutableStateOf(canAutoLaunch(ctx)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hasOverlayPermission = canAutoLaunch(ctx)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val serverName = v(Prefs.SERVER_NAME)
    val usbOnly = v(Prefs.USB_ONLY)
    val launchOnConnect = v(Prefs.LAUNCH_ON_CONNECT)
    val resolution = v(Prefs.RESOLUTION)
    val maxFps = v(Prefs.MAX_FPS)
    val developer = v(Prefs.DEVELOPER_OPTIONS)
    val resolutionLabel = stringResource(R.string.setting_resolution)
    val maxFpsLabel = stringResource(R.string.setting_max_fps)

    val sections = buildList {
        add(stringResource(R.string.section_server) to listOf(
            edit(stringResource(R.string.setting_server_name), null, Prefs.SERVER_NAME, serverName, numeric = false),
            edit(stringResource(R.string.setting_server_port), null, Prefs.SERVER_PORT, v(Prefs.SERVER_PORT), numeric = true),
            switch(stringResource(R.string.setting_boot_auto_start), stringResource(R.string.setting_boot_auto_start_desc),
                Prefs.BOOT_AUTO_START, v(Prefs.BOOT_AUTO_START)),
            switch(stringResource(R.string.setting_run_in_background), stringResource(R.string.setting_run_in_background_desc),
                Prefs.RUN_IN_BACKGROUND, v(Prefs.RUN_IN_BACKGROUND)),
        ))
        add(stringResource(R.string.section_connection) to listOf(
            TvRow.Value(
                stringResource(R.string.setting_discovery),
                stringResource(if (usbOnly) R.string.setting_discovery_usb_desc else R.string.setting_discovery_all_desc),
                stringResource(if (usbOnly) R.string.chip_usb_only else R.string.chip_all_networks)
            ) { viewModel.set(Prefs.USB_ONLY, !usbOnly) },
            switch(stringResource(R.string.setting_require_pin), stringResource(R.string.setting_require_pin_desc),
                Prefs.REQUIRE_PIN, v(Prefs.REQUIRE_PIN)),
            switch(stringResource(R.string.setting_allow_new_conn), stringResource(R.string.setting_allow_new_conn_desc),
                Prefs.ALLOW_NEW_CONN, v(Prefs.ALLOW_NEW_CONN)),
            // as on the phone: on without the overlay permission reads as off and asks for it
            TvRow.Switch(
                stringResource(R.string.setting_launch_on_connect),
                stringResource(
                    if (launchOnConnect && !hasOverlayPermission) R.string.setting_launch_on_connect_no_permission
                    else R.string.setting_launch_on_connect_desc
                ),
                launchOnConnect && hasOverlayPermission
            ) {
                val on = !(launchOnConnect && hasOverlayPermission)
                viewModel.set(Prefs.LAUNCH_ON_CONNECT, on)
                if (on && !canAutoLaunch(ctx)) _openOverlaySettings(ctx)
            },
        ))
        add(stringResource(R.string.section_display) to listOf(
            switch(stringResource(R.string.setting_auto_fullscreen), stringResource(R.string.setting_auto_fullscreen_desc),
                Prefs.AUTO_FULLSCREEN, v(Prefs.AUTO_FULLSCREEN)),
            cycle(
                resolutionLabel, stringResource(R.string.setting_resolution_desc), resolution,
                listOf(
                    Prefs.AUTO to stringResource(R.string.chip_auto),
                    "1280x720" to "1280x720", "1920x1080" to "1920x1080", "3840x2160" to "3840x2160",
                    Prefs.PORTRAIT to stringResource(R.string.chip_device_portrait),
                    Prefs.LANDSCAPE to stringResource(R.string.chip_device_landscape),
                ),
                set = { viewModel.set(Prefs.RESOLUTION, it) },
                custom = TextEdit(resolutionLabel, if ('x' in resolution) resolution else "", numeric = false) { t ->
                    parseFor(Prefs.RESOLUTION, t)?.also { viewModel.set(Prefs.RESOLUTION, it) } != null
                }
            ),
            cycle(
                maxFpsLabel, stringResource(R.string.setting_max_fps_desc), maxFps.toString(),
                listOf("24", "30", "60", "120").map { it to it },
                set = { viewModel.set(Prefs.MAX_FPS, it.toInt()) },
                custom = TextEdit(maxFpsLabel, maxFps.toString(), numeric = true) { t ->
                    parseFor(Prefs.MAX_FPS, t)?.also { viewModel.set(Prefs.MAX_FPS, it) } != null
                }
            ),
            switch(stringResource(R.string.setting_overscanned), stringResource(R.string.setting_overscanned_desc),
                Prefs.OVERSCANNED, v(Prefs.OVERSCANNED)),
        ))
        add(stringResource(R.string.section_decode) to listOf(
            cycle(stringResource(R.string.setting_h265), stringResource(R.string.setting_h265_desc), v(Prefs.H265), tri,
                set = { viewModel.set(Prefs.H265, it) }),
            switch(stringResource(R.string.setting_sw_alac), stringResource(R.string.setting_sw_alac_desc),
                Prefs.FORCE_SW_ALAC, v(Prefs.FORCE_SW_ALAC)),
            switch(stringResource(R.string.setting_sw_aac), stringResource(R.string.setting_sw_aac_desc),
                Prefs.FORCE_SW_AAC, v(Prefs.FORCE_SW_AAC)),
            switch(stringResource(R.string.setting_sync_client_volume), stringResource(R.string.setting_sync_client_volume_desc),
                Prefs.SYNC_CLIENT_VOLUME, v(Prefs.SYNC_CLIENT_VOLUME)),
        ))
        add(stringResource(R.string.section_developer) to buildList {
            add(switch(stringResource(R.string.setting_developer_options), stringResource(R.string.setting_developer_options_desc),
                Prefs.DEVELOPER_OPTIONS, developer))
            if (developer) addAll(developerRows(viewModel, ::switch, ::cycle, tri) { p, title, desc, value ->
                edit(title, desc, p, value, numeric = true)
            })
        })
        add(stringResource(R.string.tv_section_more) to listOf(
            TvRow.Value(stringResource(R.string.tab_logs), stringResource(R.string.tv_logs_desc), "", onLogs),
        ))
    }

    // read only by the description panel, so a focus move redraws that text and nothing else
    val focusedDesc = remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }
    var firstFocused by remember { mutableStateOf(false) }
    TvInitialFocus(firstFocus) { firstFocused }
    // back from Logs, focus returns to the row the viewer left; otherwise the first row
    val focusTitle = lastFocus.title?.takeIf { t -> sections.any { (_, rows) -> rows.any { it.title == t } } }
        ?: sections.first().second.first().title

    Row(Modifier.fillMaxSize().padding(start = 60.dp, end = 52.dp)) {
        // left: icon, title and what the focused row does
        Column(
            Modifier.width(280.dp).fillMaxHeight().padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(Modifier.size(150.dp).tvGlass(RoundedCornerShape(36.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Settings, null, tint = TvColors.Text, modifier = Modifier.size(80.dp))
            }
            Spacer(Modifier.height(22.dp))
            Text(stringResource(R.string.tab_settings), color = TvColors.Text, fontFamily = Outfit,
                fontSize = 26.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            FocusedDescription(focusedDesc)
        }
        Spacer(Modifier.width(48.dp))
        // right: grouped rows
        LazyColumn(
            Modifier.weight(1f).fillMaxHeight(),
            state = listState,
            contentPadding = PaddingValues(top = 40.dp, bottom = 60.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            sections.forEach { (header, rows) ->
                item(key = "h:$header") {
                    Text(
                        header.uppercase(), color = TvColors.Faint, fontFamily = Outfit, fontSize = 11.sp,
                        fontWeight = FontWeight.Medium, letterSpacing = 1.sp,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp)
                    )
                }
                rows.forEach { row ->
                    val isFirst = row.title == focusTitle
                    item(key = "r:${row.title}") {
                        SettingRow(
                            row,
                            modifier = if (isFirst) Modifier.focusRequester(firstFocus) else Modifier,
                            onFocus = { f ->
                                if (isFirst) firstFocused = f
                                if (f) {
                                    focusedDesc.value = row.desc
                                    lastFocus.title = row.title
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    editing?.let { e -> TextDialog(e, onDismiss = { editing = null }) }
}

// SettingsScreen's developer section; shown only while developer options are on
@Composable
private fun developerRows(
    viewModel: MainViewModel,
    switch: (String, String?, BoolPref, Boolean) -> TvRow,
    cycle: (String, String?, String, List<Pair<String, String>>, (String) -> Unit, TextEdit?) -> TvRow,
    tri: List<Pair<String, String>>,
    edit: (io.github.jqssun.airplay.IntPref, String, String?, Int) -> TvRow,
): List<TvRow> {
    @Composable
    fun <T : Any> v(p: Pref<T>): T = viewModel.pref(p).collectAsState().value
    @Composable
    fun sw(title: Int, desc: Int, p: BoolPref) = switch(stringResource(title), stringResource(desc), p, v(p))

    val latency = v(Prefs.AUDIO_LATENCY_MS)
    val autoBuffer = v(Prefs.AUDIO_AUTO_BUFFER)
    val step = v(Prefs.AUDIO_ADAPTIVE_STEP)
    val stepNames = stringArrayResource(R.array.audio_adaptive_step_names)
    val maxStep = Prefs.AUDIO_ADAPTIVE_STEP.range.last
    return buildList {
        add(sw(R.string.setting_auto_start, R.string.setting_auto_start_desc, Prefs.AUTO_START))
        add(sw(R.string.setting_keep_screen_on, R.string.setting_keep_screen_on_desc, Prefs.KEEP_SCREEN_ON))
        add(sw(R.string.setting_idle_preview, R.string.setting_idle_preview_desc, Prefs.IDLE_PREVIEW))
        add(sw(R.string.setting_advertise_video, R.string.setting_advertise_video_desc, Prefs.ADVERTISE_VIDEO))
        add(sw(R.string.setting_advertise_mirroring, R.string.setting_advertise_mirroring_desc, Prefs.ADVERTISE_MIRRORING))
        add(sw(R.string.setting_advertise_audio, R.string.setting_advertise_audio_desc, Prefs.ADVERTISE_AUDIO))
        add(sw(R.string.setting_alac, R.string.setting_alac_desc, Prefs.ALAC_ENABLED))
        add(sw(R.string.setting_aac, R.string.setting_aac_desc, Prefs.AAC_ENABLED))
        add(sw(R.string.setting_key_allow_frame_drop, R.string.setting_key_allow_frame_drop_desc, Prefs.KEY_ALLOW_FRAME_DROP))
        add(sw(R.string.setting_enforce_sdr, R.string.setting_enforce_sdr_desc, Prefs.ENFORCE_SDR))
        add(sw(R.string.setting_realtime_decoder_priority, R.string.setting_realtime_decoder_priority_desc, Prefs.KEY_PRIORITY))
        add(cycle(stringResource(R.string.setting_operating_rate), stringResource(R.string.setting_operating_rate_desc),
            v(Prefs.OPERATING_RATE), tri, { viewModel.set(Prefs.OPERATING_RATE, it) }, null))
        add(sw(R.string.setting_low_latency, R.string.setting_low_latency_desc, Prefs.LOW_LATENCY))
        add(sw(R.string.setting_scheduled_output_buffer_release, R.string.setting_scheduled_output_buffer_release_desc,
            Prefs.SCHEDULED_OUTPUT_BUFFER_RELEASE))
        // the phone's switch + slider: on starts at 250 ms, Left/Right move it by 50 ms
        add(TvRow.Switch(stringResource(R.string.setting_audio_delay), stringResource(R.string.setting_audio_delay_desc),
            latency >= 0) { viewModel.set(Prefs.AUDIO_LATENCY_MS, if (latency >= 0) -1 else 250) })
        if (latency >= 0) {
            add(TvRow.Stepper(stringResource(R.string.tv_audio_delay_value), stringResource(R.string.setting_audio_delay_desc),
                stringResource(R.string.audio_delay_value, latency)) { dir ->
                viewModel.set(Prefs.AUDIO_LATENCY_MS, (latency + 50 * dir).coerceIn(0, 1000))
            })
        }
        add(sw(R.string.setting_audio_auto_buffer, R.string.setting_audio_auto_buffer_desc, Prefs.AUDIO_AUTO_BUFFER))
        if (autoBuffer) {
            add(TvRow.Stepper(stringResource(R.string.tv_audio_buffer_target), stringResource(R.string.setting_audio_auto_buffer_desc),
                stringResource(R.string.audio_adaptive_value, Prefs.ADAPTIVE_PERCENTILES[step], stepNames[step])) { dir ->
                viewModel.set(Prefs.AUDIO_ADAPTIVE_STEP, (step + dir).coerceIn(0, maxStep))
            })
        } else {
            add(edit(Prefs.AUDIO_CUSHION_MS, stringResource(R.string.setting_audio_cushion_ms),
                stringResource(R.string.setting_audio_cushion_ms_desc), v(Prefs.AUDIO_CUSHION_MS)))
        }
        add(edit(Prefs.OBOE_BUFFER_FRAMES, stringResource(R.string.setting_oboe_buffer_frames),
            stringResource(R.string.setting_oboe_buffer_frames_desc), v(Prefs.OBOE_BUFFER_FRAMES)))
        add(sw(R.string.setting_debug_overlay, R.string.setting_debug_overlay_desc, Prefs.DEBUG_ENABLED))
        add(sw(R.string.setting_benchmark_log, R.string.setting_benchmark_log_desc, Prefs.BENCHMARK_LOG))
    }
}

/**
 * Where focus was in Settings, so Back from Logs lands on the same row. A plain field, not Compose
 * state: it is only read when Settings is entered, so writing it on every move must not recompose.
 */
class SettingsFocus {
    var title: String? = null
}

@Composable
private fun FocusedDescription(desc: State<String?>) {
    AnimatedContent(desc.value, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) }, label = "desc") { d ->
        Text(
            d ?: stringResource(R.string.tv_settings_desc),
            color = TvColors.Faint, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center,
            modifier = Modifier.heightIn(min = 60.dp)
        )
    }
}

@Composable
private fun SettingRow(row: TvRow, modifier: Modifier, onFocus: (Boolean) -> Unit) {
    val stepper = row as? TvRow.Stepper
    TvFocusSurface(
        onClick = {
            when (row) {
                is TvRow.Switch -> row.toggle()
                is TvRow.Value -> row.onClick()
                is TvRow.Stepper -> row.step(1)
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .then(if (stepper == null) Modifier else Modifier.onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { stepper.step(-1); true }
                    Key.DirectionRight -> { stepper.step(1); true }
                    else -> false
                }
            }),
        shape = RoundedCornerShape(12.dp),
        focusScale = 1.03f,
        onFocus = onFocus
    ) { focused ->
        val titleColor by animateColorAsState(if (focused) TvColors.FocusText else TvColors.Text, tween(220), label = "title")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                row.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = titleColor,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(16.dp))
            when (row) {
                is TvRow.Switch -> Switch(row.on, focused)
                is TvRow.Value -> Value(row.value, chevron = true)
                is TvRow.Stepper -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, modifier = Modifier.size(20.dp))
                    Text(row.value, fontSize = 15.sp, color = LocalContentColor.current, maxLines = 1)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun Value(text: String, chevron: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (text.isNotEmpty()) Text(text, fontSize = 15.sp, color = LocalContentColor.current, maxLines = 1)
        if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Switch(on: Boolean, focused: Boolean) {
    val track by animateColorAsState(
        if (on) TvColors.Ok else if (focused) Color.Black.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.22f),
        tween(200), label = "track"
    )
    val knob by animateDpAsState(if (on) 16.dp else 2.dp, tween(200), label = "knob")
    Box(Modifier.width(34.dp).height(20.dp).background(track, CircleShape)) {
        Box(Modifier.padding(start = knob, top = 2.dp).size(16.dp).background(Color.White, CircleShape))
    }
}

/** Edits one text or number setting; input the setting would refuse keeps the dialog open, outlined red. */
@Composable
private fun TextDialog(edit: TextEdit, onDismiss: () -> Unit) {
    var text by remember(edit) { mutableStateOf(edit.initial) }
    var invalid by remember(edit) { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { fieldFocus.requestFocusUntilLanded() }
    val save = { if (edit.commit(text)) onDismiss() else invalid = true }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(420.dp)
                .background(Color(0xFF15171D), RoundedCornerShape(20.dp))
                .padding(28.dp)
        ) {
            Text(edit.title, color = TvColors.Text, fontFamily = Outfit, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(48); invalid = false },
                singleLine = true,
                isError = invalid,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (edit.numeric) KeyboardType.Number else KeyboardType.Text,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fieldFocus)
                    .then(if (invalid) Modifier.border(1.dp, TvColors.Warn, RoundedCornerShape(4.dp)) else Modifier)
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvFocusSurface(onClick = onDismiss) {
                    Text(stringResource(R.string.tv_cancel), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 9.dp))
                }
                TvFocusSurface(onClick = save) {
                    Text(stringResource(R.string.tv_save), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 9.dp))
                }
            }
        }
    }
}
