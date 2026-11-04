package io.github.jqssun.airplay.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@Composable
fun TvLogs(viewModel: MainViewModel) {
    val logs by viewModel.logs.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { 120.dp.toPx() }
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

    val panelFocus = remember { FocusRequester() }
    val exportFocus = remember { FocusRequester() }
    val clearFocus = remember { FocusRequester() }
    var panelFocused by remember { mutableStateOf(false) }
    TvInitialFocus(panelFocus) { panelFocused }
    val panelStroke by animateColorAsState(
        if (panelFocused) TvColors.Text.copy(alpha = 0.55f) else Color.Transparent, tween(220), label = "panel"
    )

    Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 34.dp, bottom = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.tab_logs), color = TvColors.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            // round icon-only buttons, like the home screen's
            LogButton(Icons.Outlined.Share, stringResource(R.string.btn_export), viewModel::exportLogs, exportFocus)
            Spacer(Modifier.width(10.dp))
            LogButton(Icons.Outlined.Delete, stringResource(R.string.btn_clear), viewModel::clearLogs, clearFocus)
        }
        Spacer(Modifier.height(20.dp))
        // the panel takes focus so Up and Down scroll it; Up at the top moves on to the buttons, and
        // Left/Right reach Export/Clear straight away instead of scrolling all the way up first
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .tvGlass(RoundedCornerShape(16.dp))
                .border(1.5.dp, panelStroke, RoundedCornerShape(16.dp))
                .focusRequester(panelFocus)
                .onFocusChanged { panelFocused = it.isFocused }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionDown -> { scope.launch { listState.animateScrollBy(step) }; true }
                        Key.DirectionUp -> if (listState.canScrollBackward) {
                            scope.launch { listState.animateScrollBy(-step) }; true
                        } else false
                        Key.DirectionLeft -> { exportFocus.requestFocus(); true }
                        Key.DirectionRight -> { clearFocus.requestFocus(); true }
                        else -> false
                    }
                }
                .focusable(),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            items(logs) {
                Text(it, color = TvColors.Muted, fontSize = 13.sp, fontFamily = FontFamily.Monospace, lineHeight = 18.sp)
            }
        }
    }
}

@Composable
private fun LogButton(icon: ImageVector, label: String, onClick: () -> Unit, focus: FocusRequester) {
    TvPillButton(icon, label, onClick, Modifier.focusRequester(focus))
}
