package com.extbridge.helper

import android.content.res.Configuration
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.extbridge.helper.ui.glass.lens
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class TouchpadUi {
    private val speeds = floatArrayOf(1f, 1.6f, 2.3f, 3.2f)
    private var speedIndex by mutableIntStateOf(1)
    val speed get() = speeds[speedIndex]
    var dragLocked by mutableStateOf(false)
    private var inputMode by mutableIntStateOf(-1)
    var desktopWidth by mutableIntStateOf(0)
        private set
    private var desktopHeight by mutableIntStateOf(0)
    private var cursor by mutableStateOf(IntOffset.Zero)

    fun updateCursor(x: Int, y: Int) { cursor = IntOffset(x, y) }

    fun updateStatus(status: BridgeClient.Status) {
        inputMode = if (status.connected) status.inputToExt else -1
        desktopWidth = if (status.connected && status.vdId > 0) status.virtW else 0
        desktopHeight = status.virtH
        updateCursor(status.cursorX, status.cursorY)
    }

    fun show(activity: TouchpadActivity) {
        activity.enableEdgeToEdge()
        activity.setContent {
            val theme = remember { ThemeController() }
            MiuixTheme(controller = theme) { Screen(activity) }
        }
    }

    @Composable
    private fun Screen(activity: TouchpadActivity) {
        val colors = MiuixTheme.colorScheme
        val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
        val glass = remember { activity.getSharedPreferences(MainActivity::class.java.simpleName, 0)
            .getBoolean("liquidGlass", true) }
        var oneHand by rememberSaveable { mutableStateOf(false) }
        var keyboardOpen by rememberSaveable { mutableStateOf(false) }
        var draft by rememberSaveable { mutableStateOf("") }
        var ctrlV by rememberSaveable { mutableStateOf(false) }
        val keyboard = LocalSoftwareKeyboardController.current
        val closeKeyboard = { keyboard?.hide(); keyboardOpen = false }
        val send = { activity.commitImeText(draft, ctrlV); draft = "" }
        BackHandler(keyboardOpen, onBack = closeKeyboard)

        BoxWithConstraints(Modifier.fillMaxSize().background(colors.surface)
            .safeDrawingPadding().imePadding()) {
            val margin = if (maxWidth >= 600.dp) 24.dp else 12.dp
            val compact = maxHeight < 330.dp
            val narrow = maxWidth < 480.dp
            val hint = "单指移动或轻点 · 双指轻点右键 · 双指或右边缘滚动"
            Column(Modifier.fillMaxSize().padding(horizontal = margin, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 10.dp)) {
                if (!compact || !keyboardOpen) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("触摸板", fontSize = if (compact) 22.sp else 30.sp,
                                fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                            if (!compact && !narrow) Text(hint,
                                fontSize = 13.sp, color = colors.onSurfaceVariantSummary)
                        }
                        if (desktopWidth > 0) CursorLabel()
                        PadButton("退出", icon = R.drawable.ic_close, onClick = activity::finish)
                    }
                    if (!compact && narrow) Text(hint, fontSize = 13.sp, color = colors.onSurfaceVariantSummary)
                }
                if (portrait && oneHand && !keyboardOpen) Spacer(Modifier.weight(.72f))

                val padShape = RoundedCornerShape(24.dp)
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.fillMaxWidth().weight(1f).clip(padShape)
                    .border(1.dp, colors.outline.copy(alpha = .2f), padShape)) {
                    val surface = colors.surfaceContainer
                    val ink = colors.onSurface
                    val primary = colors.primary
                    AndroidView(factory = { activity.TouchpadView(it) },
                        modifier = Modifier.fillMaxSize()
                            .then(if (glass) Modifier.layerBackdrop(backdrop) else Modifier)
                            .background(surface),
                        update = { it.setPalette(ink.copy(alpha = .12f).toArgb(), primary.toArgb(),
                            ink.copy(alpha = .045f).toArgb(), ink.copy(alpha = .23f).toArgb()) })
                    if (!keyboardOpen) {
                        val pill = RoundedCornerShape(28.dp)
                        val panel = if (glass) Modifier.drawBackdrop(backdrop = backdrop, shape = { pill },
                            effects = { blur(5.dp.toPx()); lens(12.dp.toPx(), 12.dp.toPx()) },
                            onDrawSurface = { drawRect(surface.copy(alpha = .78f)) })
                            else Modifier.background(surface, pill)
                        Row(Modifier.align(Alignment.BottomCenter).padding(horizontal = 10.dp, vertical = 12.dp)
                            .clip(pill).then(panel).border(1.dp, ink.copy(alpha = .15f), pill)
                            .horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            PadButton("键盘", onClick = { keyboardOpen = true })
                            PadButton("速度 ${speed}×", onClick = { speedIndex = (speedIndex + 1) % speeds.size })
                            PadButton(when (inputMode) { 1 -> "控制扩展屏"; 0 -> "控制本机"; else -> "服务未连接" },
                                icon = R.drawable.ic_display, enabled = inputMode >= 0) {
                                val mode = BridgeClient.toggleInput()
                                if (mode >= 0) inputMode = mode
                                BridgeService.refreshFromReceiver(activity)
                            }
                            if (portrait) PadButton("单手", active = oneHand) { oneHand = !oneHand }
                            PadButton("桌面", icon = R.drawable.ic_home) { BridgeClient.relaunchLauncher() }
                            PadButton("返回") { BridgeClient.sendBack() }
                        }
                    }
                }
                if (!compact || !keyboardOpen) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PadButton("左键", Modifier.weight(1f), filled = true) { activity.sendMouseSync(1, 0f, 0f) }
                        PadButton(if (dragLocked) "拖拽中" else "拖拽锁定", Modifier.weight(1f),
                            icon = R.drawable.ic_lock, active = dragLocked, filled = true,
                            onClick = activity::toggleDragLock)
                        PadButton("右键", Modifier.weight(1f), filled = true) { activity.sendMouseSync(2, 0f, 0f) }
                    }
                }
                if (keyboardOpen) {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PadButton(if (ctrlV) "粘贴方式 Ctrl+V" else "粘贴方式 Paste", filled = true) { ctrlV = !ctrlV }
                        for ((label, key, meta) in shortcuts) {
                            PadButton(label, filled = true) { BridgeClient.sendKey(key, meta) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextField(value = draft, onValueChange = { draft = it }, label = "输入文字",
                            modifier = Modifier.weight(1f).focusRequester(focus), singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { send() }))
                        PadButton("发送", active = true, onClick = send)
                        PadButton("收起", onClick = closeKeyboard)
                    }
                }
            }
        }
    }

    @Composable
    private fun CursorLabel() {
        Column(Modifier.padding(horizontal = 16.dp), horizontalAlignment = Alignment.End) {
            Text("${desktopWidth} × ${desktopHeight}", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text("光标 ${cursor.x}, ${cursor.y}", fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun PadButton(label: String, modifier: Modifier = Modifier, icon: Int? = null,
                      active: Boolean = false, filled: Boolean = false, enabled: Boolean = true,
                      onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val background = when { active -> colors.primary; filled -> colors.surfaceContainer; else -> Color.Transparent }
    val ink = when { !enabled -> colors.disabledOnSurface; active -> colors.onPrimary; else -> colors.onSurface }
    Button(onClick = onClick, modifier = modifier, enabled = enabled, minWidth = 48.dp, minHeight = 48.dp,
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        colors = ButtonDefaults.buttonColors(color = background, disabledColor = background)) {
        if (icon != null) {
            Icon(painter = painterResource(icon), contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(label, fontSize = 14.sp, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private const val CTRL = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
private val shortcuts = listOf(
    Triple("回车", KeyEvent.KEYCODE_ENTER, 0), Triple("退格", KeyEvent.KEYCODE_DEL, 0),
    Triple("空格", KeyEvent.KEYCODE_SPACE, 0), Triple("Esc", KeyEvent.KEYCODE_ESCAPE, 0),
    Triple("Tab", KeyEvent.KEYCODE_TAB, 0), Triple("全选", KeyEvent.KEYCODE_A, CTRL),
    Triple("复制", KeyEvent.KEYCODE_C, CTRL), Triple("粘贴", KeyEvent.KEYCODE_V, CTRL),
    Triple("↑", KeyEvent.KEYCODE_DPAD_UP, 0), Triple("↓", KeyEvent.KEYCODE_DPAD_DOWN, 0),
    Triple("←", KeyEvent.KEYCODE_DPAD_LEFT, 0), Triple("→", KeyEvent.KEYCODE_DPAD_RIGHT, 0),
    Triple("主页", KeyEvent.KEYCODE_HOME, 0),
)
