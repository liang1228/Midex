package com.extbridge.helper

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.extbridge.helper.ui.glass.LiquidGlassNavigationBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.window.WindowDialog

object ConsoleUi {
    @JvmStatic
    fun show(activity: MainActivity) {
        activity.enableEdgeToEdge()
        activity.setContent {
            val theme = remember { ThemeController() }
            MiuixTheme(controller = theme) { Console(activity) }
        }
    }
}

private data class Snapshot(
    val status: BridgeClient.Status = BridgeClient.Status(),
    val runtime: BridgeClient.RuntimeInfo = BridgeClient.RuntimeInfo(),
    val modes: Map<String, Int> = emptyMap(),
    val internalRefresh: String = "",
    val extension: RefreshCapabilities? = null,
)

private const val ROOT_MODE = "rootMode"

@Composable
private fun Console(activity: MainActivity) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var driver by rememberSaveable { mutableStateOf(false) }
    val preferences = remember { activity.getPreferences(0) }
    var glass by remember { mutableStateOf(preferences.getBoolean("liquidGlass", true)) }
    var rootPreference by remember { mutableStateOf(
        if (preferences.contains(ROOT_MODE)) preferences.getBoolean(ROOT_MODE, false) else null) }
    var hasRoot by remember { mutableStateOf(RootBootstrap.hasVerifiedRoot()) }
    var checkingAccess by remember { mutableStateOf(false) }
    val checkAccess = {
        checkingAccess = true
        RootBootstrap.checkRootAccessAsync { granted ->
            hasRoot = granted
            checkingAccess = false
            if (!granted && !activity.isDestroyed)
                Toast.makeText(activity, "未获得额外权限，可继续使用 ADB 激活", Toast.LENGTH_SHORT).show()
        }
    }
    var snapshot by remember { mutableStateOf(Snapshot()) }
    val rootMode = hasRoot && rootPreference != false
    var activationOpen by remember { mutableStateOf(false) }
    val activateAdb = { activationOpen = true }
    val activateRoot = {
        rootPreference = true
        preferences.edit().putBoolean(ROOT_MODE, true).apply()
        activity.runRootActivation()
    }
    val activate = if (rootMode) activateRoot else activateAdb
    var appsVisited by remember { mutableStateOf(page == 2) }
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, appsVisited) {
        if (!appsVisited) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            apps = withContext(Dispatchers.IO) {
                val pm = activity.packageManager
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .filter { it.activityInfo.packageName != activity.packageName }
                    .map { InstalledApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                    .distinctBy { it.pkg }.sortedBy { it.name.lowercase() }
            }
        }
    }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                snapshot = withContext(Dispatchers.IO) {
                    val status = BridgeClient.getStatus()
                    if (!status.connected) BridgeClient.requestBinder(activity)
                    val runtime = BridgeClient.getRuntimeInfo()
                    Snapshot(status, runtime,
                        if (status.connected) BridgeClient.getAllAppModes() else emptyMap(),
                        extension = RefreshExtension.read(activity))
                }.copy(internalRefresh = WindowRefreshRate.summary(activity))
                hasRoot = snapshot.extension?.rootPresent == true
                if (activationOpen && snapshot.status.connected && snapshot.runtime.valid && snapshot.runtime.uid == 2000) {
                    activationOpen = false
                    AdbBootstrap.cancelPairingNotification(activity)
                }
                delay(2000)
            }
        }
    }
    ActivationDialog(activity, activationOpen) { activationOpen = false }
    BackHandler(driver || page != 0) { if (driver) driver = false else page = 0 }
    val backdrop = rememberLayerBackdrop()
    val pageStates = rememberSaveableStateHolder()
    val visitedTabs = remember { mutableStateListOf(0, page).apply { if (page == 0) removeAt(1) } }
    val tabs = listOf(
        NavigationItem("首页", ImageVector.vectorResource(R.drawable.ic_home)),
        NavigationItem("显示", ImageVector.vectorResource(R.drawable.ic_display)),
        NavigationItem("应用", ImageVector.vectorResource(R.drawable.ic_apps)),
        NavigationItem("设置", ImageVector.vectorResource(R.drawable.ic_settings)),
    )
    BoxWithConstraints(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
        val wide = maxWidth >= 1000.dp
        // Only the page is recorded. The glass bar is a sibling, preventing self-sampling.
        val pageWidth = if (maxWidth > maxHeight) Modifier
            else Modifier.widthIn(max = if (wide) 1200.dp else 840.dp)
        Box(Modifier.align(Alignment.TopCenter).then(pageWidth)
            .fillMaxSize()
            .then(if (glass) Modifier.layerBackdrop(backdrop) else Modifier)
            .background(MiuixTheme.colorScheme.background)) {
        val pane: @Composable (Int, Boolean) -> Unit = { tab, isDriver ->
        pageStates.SaveableStateProvider(if (isDriver) "driver" else "tab-$tab") {
            val scroll = MiuixScrollBehavior()
            val title = if (isDriver) "驱动信息" else if (tab == 0) stringResource(R.string.app_name) else tabs[tab].label
            Scaffold(
                modifier = Modifier.layoutId(if (isDriver) -1 else tab).fillMaxSize(),
                topBar = { TopAppBar(title = title, scrollBehavior = scroll) },
            ) { padding ->
                val listState = rememberLazyListState()
                val direction = LocalLayoutDirection.current
                val contentPadding = PaddingValues(
                        start = (if (wide) 24.dp else 12.dp) + padding.calculateStartPadding(direction),
                        end = (if (wide) 24.dp else 12.dp) + padding.calculateEndPadding(direction),
                        top = padding.calculateTopPadding() + 12.dp,
                        bottom = padding.calculateBottomPadding() + 108.dp,
                    )
                if (tab == 2 && !isDriver) {
                    AppsPage(activity, snapshot, apps, contentPadding,
                        Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection))
                } else LazyColumn(
                    modifier = Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
                    state = listState,
                    contentPadding = contentPadding,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when {
                        isDriver -> driverPage(activity, rootMode)
                        tab == 0 -> item { HomePage(activity, snapshot, wide, rootMode, activate) { driver = true } }
                        tab == 1 -> item { DisplayPage(activity, snapshot.status, wide,
                            snapshot.extension.takeIf { rootMode }) }
                        else -> settingsPage(activity, hasRoot, rootMode, snapshot.runtime,
                            checkingAccess, checkAccess, activateRoot, activateAdb, { value ->
                            rootPreference = value
                            preferences.edit().putBoolean(ROOT_MODE, value).apply()
                        }, glass, { value ->
                            glass = value
                            preferences.edit().putBoolean("liquidGlass", value).apply()
                        }) { driver = true }
                    }
                }
            }
        }
        }
        // Keep visited pages composed. Only the selected page takes part in layout and drawing.
        Layout(content = {
            visitedTabs.forEach { tab -> key(tab) { pane(tab, false) } }
            if (driver) pane(page, true)
        }, modifier = Modifier.fillMaxSize()) { children, constraints ->
            val visible = children.first { it.layoutId == if (driver) -1 else page }.measure(constraints)
            layout(visible.width, visible.height) { visible.placeRelative(0, 0) }
        }
        }
        LiquidGlassNavigationBar(
            items = tabs, selectedIndex = page,
            onItemClick = {
                if (it !in visitedTabs) visitedTabs.add(it)
                page = it
                driver = false
                if (it == 2) appsVisited = true
            },
            backdrop = backdrop, isBlurActive = glass,
            modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = 620.dp),
        )
    }
}

@Composable
private fun HomePage(activity: MainActivity, state: Snapshot, wide: Boolean, rootMode: Boolean,
                    activate: () -> Unit, openDriver: () -> Unit) {
    val status = state.status
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(onClick = if (status.connected) null else activate) {
            BasicComponent(
                title = when {
                    !status.connected -> "未激活"
                    status.mirrorMode == 1 -> "镜像模式"
                    status.vdId > 0 -> "扩展桌面已连接"
                    else -> "服务已就绪"
                },
                summary = when {
                    !status.connected -> if (rootMode) "点击使用 Root 激活" else "点击使用 ADB 激活"
                    status.extId < 0 -> "等待连接外接显示器"
                    else -> BridgeClient.hostModeLabel(status.hostMode)
                },
                startAction = { RowIcon(if (status.connected) R.drawable.ic_check else R.drawable.ic_info) },
            )
        }
    val device: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SmallTitle("本机")
        Card {
            InfoRow(R.drawable.ic_layers, "管理器版本", "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            InfoRow(R.drawable.ic_chip, "内核版本", System.getProperty("os.version") ?: "未知")
            InfoRow(R.drawable.ic_monitor, "设备型号", "${Build.MANUFACTURER} ${Build.MODEL}")
            InfoRow(R.drawable.ic_shield, "运行模式", if (state.runtime.valid)
                BridgeClient.hostModeLabel(state.runtime.hostModeName) else "服务未运行")
            InfoRow(R.drawable.ic_refresh, "内屏刷新率", state.internalRefresh)
        }
        }
    }
    val desktop: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SmallTitle("扩展桌面")
        Card {
            InfoRow(R.drawable.ic_display, "外接显示器", when {
                !status.connected -> "激活服务后自动检测"
                status.extId < 0 -> "未连接"
                !status.physicalModeConfirmed -> "等待确认外屏输出"
                else -> "${status.physW} × ${status.physH} · ${formatRate(status.physFps)} Hz"
            })
            if (status.vdId > 0) InfoRow(R.drawable.ic_refresh, "桌面同步",
                "${formatRate(status.virtFps)} Hz · ${status.virtDpi} dpi")
            InfoRow(R.drawable.ic_touchpad, "输入设备", when {
                !status.connected -> "等待服务连接"
                status.extId < 0 -> "连接外屏后自动接管键鼠"
                else -> "${status.extInputCnt.coerceAtLeast(0)} 个设备 · ${if (status.inputToExt == 1) "控制扩展屏" else "控制主屏"}"
            })
        }
        Card {
            ActionRow(R.drawable.ic_apps, "打开扩展桌面", "在外接显示器上打开启动器",
                enabled = status.connected && status.vdId > 0, onClick = activity::openDesktop)
            ActionRow(R.drawable.ic_touchpad, "触摸板", "使用本机控制扩展屏", onClick = activity::openTouchpad)
            ActionRow(R.drawable.ic_chip, "驱动信息", "显示、GPU、面板与 Type-C", onClick = openDriver)
        }
        }
    }
    if (wide) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Box(Modifier.weight(1f)) { device() }
        Box(Modifier.weight(1f)) { desktop() }
    } else {
        desktop()
        device()
    }
    }
}

private fun formatRate(rate: Float): String = java.math.BigDecimal.valueOf(rate.toDouble())
    .setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

private data class OutputMode(val id: Int, val width: Int, val height: Int, val rate: Float)

private fun outputModes(raw: String?): List<OutputMode> = raw.orEmpty().split(';').mapNotNull {
    val parts = it.split(':', 'x', '@')
    if (parts.size != 4) return@mapNotNull null
    val id = parts[0].toIntOrNull() ?: return@mapNotNull null
    val width = parts[1].toIntOrNull() ?: return@mapNotNull null
    val height = parts[2].toIntOrNull() ?: return@mapNotNull null
    val rate = parts[3].toFloatOrNull() ?: return@mapNotNull null
    if (!rate.isFinite() || rate <= 0) null else OutputMode(id, width, height, rate)
}

@Composable
private fun RowIcon(resource: Int) {
    Icon(painter = painterResource(resource), contentDescription = null,
        modifier = Modifier.padding(end = 4.dp).size(24.dp), tint = MiuixTheme.colorScheme.onSurface)
}

@Composable
private fun InfoRow(icon: Int, title: String, value: String) {
    BasicComponent(title = title, summary = value, startAction = { RowIcon(icon) })
}

@Composable
private fun ActionRow(icon: Int, title: String, summary: String? = null,
                      enabled: Boolean = true, onClick: () -> Unit) {
    ArrowPreference(title = title, summary = summary, startAction = { RowIcon(icon) },
        enabled = enabled, onClick = onClick)
}

@Composable
private fun DisplayPage(activity: MainActivity, status: BridgeClient.Status, wide: Boolean,
                        extension: RefreshCapabilities?) {
    var width by rememberSaveable { mutableStateOf("2560") }
    var height by rememberSaveable { mutableStateOf("1440") }
    var dpi by rememberSaveable { mutableIntStateOf(240) }
    var fps by rememberSaveable { mutableFloatStateOf(60f) }
    var physicalMode by rememberSaveable { mutableIntStateOf(-1) }
    var initializedSource by rememberSaveable { mutableStateOf("") }
    var picker by remember { mutableStateOf("") }
    var applying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val modes = remember(status.modesRaw) { outputModes(status.modesRaw) }
    val requestedWidth = width.toIntOrNull()
    val requestedHeight = height.toIntOrNull()
    val matching = modes.filter { it.width == requestedWidth && it.height == requestedHeight }
    val rateModes = matching.ifEmpty { modes.filter { it.width == status.physW && it.height == status.physH } }
    val rates = rateModes.map { it.rate }.distinct().sortedDescending()
    val selectedFps = if (rates.isNotEmpty() && rates.none { kotlin.math.abs(it - fps) < 0.02f }) 0f else fps
    val hasExternalDisplay = status.connected && status.extId > 0
    val currentOutput = if (status.physicalModeConfirmed)
        "${status.physW} × ${status.physH} · ${formatRate(status.physFps)} Hz" else "暂无法确认实际输出"
    val targetPending = status.targetPhysFps > 0f && (!status.physicalModeConfirmed ||
        status.targetPhysW != status.physW || status.targetPhysH != status.physH ||
        kotlin.math.abs(status.targetPhysFps - status.physFps) >= 0.02f)
    LaunchedEffect(status.connected, status.extId, status.daemonVersion) {
        physicalMode = -1
        val source = "${status.daemonVersion}:${status.extId}"
        if (hasExternalDisplay && initializedSource != source) {
            width = status.virtW.toString(); height = status.virtH.toString()
            dpi = status.virtDpi; fps = status.requestedFps; initializedSource = source
        }
        if (!hasExternalDisplay) {
            initializedSource = ""
            if (picker == "外屏刷新率" || picker == "物理输出模式") picker = ""
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card {
            InfoRow(R.drawable.ic_monitor, "外屏当前模式", if (hasExternalDisplay)
                currentOutput else "未连接外接显示器")
            if (hasExternalDisplay && targetPending) {
                InfoRow(R.drawable.ic_refresh, "外屏请求模式",
                    "${status.targetPhysW} × ${status.targetPhysH} · ${formatRate(status.targetPhysFps)} Hz · 尚未确认生效")
            }
            InfoRow(R.drawable.ic_layers, "扩展桌面", if (status.vdId > 0)
                "${status.virtW} × ${status.virtH} · ${status.virtDpi} dpi · ${formatRate(status.virtFps)} Hz" else "尚未创建")
        }
        if (extension?.rootPresent == true) RootRefreshExtension(activity, extension, status)
        val configuration: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SmallTitle("桌面配置")
        Card {
            ArrowPreference("外屏刷新率", summary = when {
                !hasExternalDisplay -> "接上显示器后可选档"
                selectedFps == 0f -> "自动 · 请求同分辨率最高档"
                else -> "手动 · ${formatRate(selectedFps)} Hz"
            }, onClick = { picker = "外屏刷新率" }, enabled = hasExternalDisplay)
            ArrowPreference("分辨率", summary = "$width × $height", onClick = { picker = "分辨率" })
            ArrowPreference("显示密度", summary = "$dpi dpi", onClick = { picker = "显示密度" })
            ArrowPreference("物理输出模式", summary = if (physicalMode == -1) "自动" else "模式 $physicalMode",
                onClick = { picker = "物理输出模式" }, enabled = hasExternalDisplay)
        }
        Button(onClick = {
            val w = width.toIntOrNull(); val h = height.toIntOrNull()
            if (w == null || h == null || w !in 640..7680 || h !in 480..4320) {
                error = "宽度需为 640–7680，高度需为 480–4320"; return@Button
            }
            applying = true
            error = ""
            val requestedDpi = dpi
            val requestedRate = selectedFps
            val requestedMode = physicalMode
            val expectedExtId = status.extId
            scope.launch {
                val requestId = withContext(Dispatchers.IO) {
                    BridgeClient.applyConfig(w, h, requestedDpi, requestedRate, requestedMode, expectedExtId)
                }
                when {
                    requestId == BridgeClient.CONFIG_DISPLAY_CHANGED -> error = "外屏连接已变化，请重新选择档位"
                    requestId < 0 -> error = "应用失败，请检查后台服务和所选模式"
                    !hasExternalDisplay -> error = "配置已保存，连接外屏后自动尝试应用"
                    requestId == 0L -> error = "配置已提交，重新激活新版后台服务后可确认切换结果"
                    else -> {
                        var current = BridgeClient.Status()
                        for (attempt in 0 until 10) {
                            delay(500)
                            current = withContext(Dispatchers.IO) { BridgeClient.getStatus() }
                            if (current.connected && (current.configStage == "SUBMIT_FAILED" ||
                                current.confirmedConfigRequestId == requestId ||
                                current.configRequestId != requestId)) break
                        }
                        error = when {
                            !current.connected -> "配置已提交，但服务连接中断，无法确认是否生效"
                            current.configRequestId != requestId -> "配置已保存，外屏连接或配置请求已变化"
                            current.confirmedConfigRequestId == requestId -> ""
                            current.configStage == "SUBMIT_FAILED" -> "配置已保存，后台未能向系统提交外屏档位"
                            current.extId <= 0 -> "配置已保存，等待外屏就绪"
                            current.configStage == "WAITING_FOR_RENDER" ->
                                "SurfaceFlinger 已读取目标档位，渲染率尚未同步"
                            current.configStage == "WAITING_FOR_DRM" ->
                                "SurfaceFlinger 已读取目标档位，DRM 尚未确认实际输出"
                            current.configStage == "WAITING_FOR_SF" && !current.physicalModeConfirmed ->
                                "系统已接收请求，SurfaceFlinger 尚未确认目标档位"
                            current.drmReadback && !current.physicalModeConfirmed ->
                                "配置已保存，DRM 与 SurfaceFlinger 尚未确认同一输出模式"
                            current.targetPhysFps <= 0f || !current.physicalModeConfirmed ->
                                "配置已保存，暂无法确认外屏实际模式"
                            current.targetPhysW != current.physW || current.targetPhysH != current.physH ||
                                kotlin.math.abs(current.targetPhysFps - current.physFps) >= 0.02f ->
                                "外屏请求 ${current.targetPhysW} × ${current.targetPhysH} · ${formatRate(current.targetPhysFps)} Hz，当前仍为 ${current.physW} × ${current.physH} · ${formatRate(current.physFps)} Hz"
                            else -> "外屏档位已确认，扩展桌面配置尚未同步"
                        }
                        if (error.isEmpty()) {
                            val message = when {
                                current.drmReadback && current.mirrorMode != 0 -> "外屏模式已生效"
                                current.drmReadback -> "外屏与扩展桌面配置已生效"
                                else -> "系统已确认目标显示配置"
                            }
                            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
                applying = false
                BridgeService.refreshFromReceiver(activity)
            }
        }, modifier = Modifier.fillMaxWidth(), enabled = status.connected && !applying,
            colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.primary,
                contentColor = MiuixTheme.colorScheme.onPrimary)) {
            Text(if (applying) "正在应用" else "应用配置")
        }
        if (hasExternalDisplay && status.drmReadback) {
            Card {
                ActionRow(R.drawable.ic_refresh, "强制最高刷新率",
                    "保持外屏分辨率 · 使用 OPlusAutoHR Canary 1.0", enabled = !applying) {
                    applying = true
                    error = ""
                    val expectedExtId = status.extId
                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) {
                                BridgeClient.forceHighestExternalRefresh(expectedExtId)
                            }
                            when (result) {
                                "CONFIRMED", "ALREADY_HIGHEST" -> Toast.makeText(activity,
                                    if (result == "CONFIRMED") "外屏最高刷新率已生效" else "外屏已在当前分辨率的最高档",
                                    Toast.LENGTH_LONG).show()
                                "DISPLAY_CHANGED" -> error = "外屏连接已变化，请重新尝试"
                                "ROOT_REQUIRED" -> error = "请使用 Root 激活后重试"
                                "MODULE_REQUIRED" -> error = "此功能需要已启用的 OPlusAutoHR Canary 1.0 模块"
                                "ENGINE_UNAVAILABLE" -> error = "OPlusAutoHR 引擎尚未就绪，请在模块界面查看状态"
                                "CURRENT_MODE_UNVERIFIED" -> error = "暂无法确认外屏当前输出，请等待显示稳定"
                                "READBACK_MISMATCH" -> error = "SurfaceFlinger 与 DRM 未共同确认最高档，预览将自动回退"
                                "SWITCH_UNCONFIRMED" -> error = "未确认切换成功，请检查 OPlusAutoHR 和外屏输出"
                                "OLD_DAEMON" -> error = "请重新激活新版 Root 后台"
                                "NOT_CONNECTED" -> error = "后台已断开，请重新连接"
                                else -> error = "无法读取 OPlusAutoHR 的切档结果"
                            }
                        } finally {
                            applying = false
                            BridgeService.refreshFromReceiver(activity)
                        }
                    }
                }
            }
        }
        if (!status.connected) Text("激活服务后可应用显示配置", modifier = Modifier.padding(horizontal = 16.dp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 14.sp)
        if (error.isNotEmpty()) Text(error, modifier = Modifier.padding(horizontal = 16.dp),
            color = MiuixTheme.colorScheme.primary, fontSize = 14.sp)
        }
        }
        val controls: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SmallTitle("显示与输入")
        Card {
            SwitchPreference(checked = status.mirrorMode == 1, title = "镜像模式", summary = "同步主屏画面",
                enabled = status.connected, onCheckedChange = {
                    scope.launch { withContext(Dispatchers.IO) { BridgeClient.toggleMirrorMode() }; BridgeService.refreshFromReceiver(activity) }
                })
            SwitchPreference(checked = status.inputToExt == 1, title = "键鼠控制扩展屏",
                enabled = status.connected, onCheckedChange = {
                    scope.launch { withContext(Dispatchers.IO) { BridgeClient.toggleInput() }; BridgeService.refreshFromReceiver(activity) }
                })
            SwitchPreference(checked = status.fullscreenPolicy == 1, title = "全屏策略", summary = "隐藏扩展屏的系统栏",
                enabled = status.connected, onCheckedChange = {
                    scope.launch { withContext(Dispatchers.IO) { BridgeClient.toggleFullscreenPolicy() }; BridgeService.refreshFromReceiver(activity) }
                })
            ActionRow(R.drawable.ic_refresh, "重建扩展桌面", enabled = status.connected, onClick = activity::rebuildDesktop)
        }
        }
        }
        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.weight(1f)) { configuration() }
            Box(Modifier.weight(1f)) { controls() }
        } else {
            configuration()
            controls()
        }
    }
    WindowDialog(show = picker.isNotEmpty(), title = picker, onDismissRequest = { picker = "" }) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (picker) {
                "分辨率" -> {
                    listOf(3840 to 2160, 2560 to 1600, 2560 to 1440, 1920 to 1200,
                        1920 to 1080, 1600 to 900, 1280 to 720).forEach { (w, h) ->
                        BasicComponent(title = "$w × $h", onClick = { width = "$w"; height = "$h"; picker = "" })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextField(value = width, onValueChange = { width = it; error = "" }, label = "宽度",
                            modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                        TextField(value = height, onValueChange = { height = it; error = "" }, label = "高度",
                            modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                    }
                }
                "显示密度" -> listOf(160, 200, 240, 280, 320).forEach { value ->
                    BasicComponent(title = "$value dpi", onClick = { dpi = value; picker = "" })
                }
                "外屏刷新率" -> {
                    Text("当前输出  $currentOutput", modifier = Modifier.padding(horizontal = 16.dp), fontSize = 14.sp)
                    BasicComponent(title = "自动", summary = "请求同分辨率最高档",
                        endActions = { if (selectedFps == 0f) RowIcon(R.drawable.ic_check) },
                        onClick = { fps = 0f; physicalMode = -1; picker = "" })
                    rates.forEach { rate ->
                        BasicComponent(title = "${formatRate(rate)} Hz", summary = "手动",
                            endActions = { if (kotlin.math.abs(selectedFps - rate) < 0.02f) RowIcon(R.drawable.ic_check) },
                            onClick = { fps = rate; physicalMode = -1; picker = "" })
                    }
                    val targetSize = rateModes.firstOrNull()?.let { "${it.width} × ${it.height}" }
                    val hint = when (rates.size) {
                        0 -> "暂未读取到此分辨率的手动档位。"
                        1 -> "$targetSize 仅提供 ${formatRate(rates.first())} Hz。"
                        else -> "$targetSize 提供 ${rates.size} 个档位。"
                    }
                    Text("$hint\n选择后点「应用配置」。", modifier = Modifier.padding(16.dp), fontSize = 14.sp)
                }
                "物理输出模式" -> {
                    BasicComponent(title = "自动", onClick = { physicalMode = -1; picker = "" })
                    status.modesRaw.orEmpty().split(";").filter { it.isNotBlank() }.forEach { raw ->
                        val parts = raw.split(":", limit = 2)
                        val id = parts.firstOrNull()?.toIntOrNull()
                        val description = parts.getOrNull(1).orEmpty()
                        val dimensions = description.substringBefore('@').split('x')
                        val rate = description.substringAfter('@', "").toFloatOrNull()
                        if (id != null) BasicComponent(title = description,
                            onClick = {
                                physicalMode = id
                                if (dimensions.size == 2 && rate != null && rate.isFinite() && rate > 0) {
                                    width = dimensions[0]; height = dimensions[1]; fps = rate
                                }
                                picker = ""
                            })
                    }
                }
            }
            TextButton(text = "完成", onClick = { picker = "" }, modifier = Modifier.fillMaxWidth())
        }
    }
}

private data class InstalledApp(val name: String, val pkg: String)

@Composable
private fun RootRefreshExtension(activity: MainActivity, extension: RefreshCapabilities, status: BridgeClient.Status) {
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var openManager by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!extension.legacy || extension.loaded || extension.windowsLoaded) {
            SmallTitle("Root 扩展")
            Card {
                if (!extension.legacy) {
                    BasicComponent(title = "内置显示与窗口扩展", summary = extension.summary())
                    if (extension.tier != RefreshCapabilities.Tier.UNSUPPORTED) {
                        ActionRow(R.drawable.ic_layers,
                            "配置内置 LSPosed 扩展", "系统框架和系统界面", enabled = !busy, onClick = {
                                busy = true
                                scope.launch {
                                    val error = withContext(Dispatchers.IO) { RefreshExtension.prepareIntegrated() }
                                    openManager = error == null
                                    message = error ?: "在 LSPosed 中启用「Midex」，勾选「系统框架」和「系统界面」，然后重启设备。"
                                    busy = false
                                }
                            })
                    }
                }
                if (extension.loaded) {
                    SwitchPreference(checked = extension.enabled, title = "解除内屏外接限刷",
                        summary = "连接外屏时保留内屏高刷，沿用系统刷新率偏好", enabled = !busy,
                        onCheckedChange = { enabled ->
                            busy = true
                            scope.launch {
                                message = withContext(Dispatchers.IO) { RefreshExtension.setEnabled(activity, enabled) }
                                busy = false
                            }
                        })
                }
                if (extension.windowsLoaded) {
                    listOf(
                        Triple(com.extbridge.refreshunlock.WindowDecorationContract.SQUARE, "去掉窗口圆角", extension.squareWindows),
                        Triple(com.extbridge.refreshunlock.WindowDecorationContract.CAPTION, "内置窗口栏", extension.nativeCaption)
                    ).forEach { (key, title, checked) ->
                        SwitchPreference(checked = checked, title = title,
                            summary = if (key == com.extbridge.refreshunlock.WindowDecorationContract.SQUARE)
                                "外屏桌面使用直角窗口，保留内屏样式"
                            else "窗口内预留标题栏，可拖动、最小化、最大化或还原、关闭",
                            enabled = !busy, onCheckedChange = { enabled ->
                                busy = true
                                scope.launch {
                                    message = withContext(Dispatchers.IO) { RefreshExtension.setWindowFeature(activity, key, enabled) }
                                    busy = false
                                }
                            })
                    }
                }
            }
        }
        if (RefreshExtension.supportsExternalRefreshTools()) {
            SmallTitle("外屏刷新率工具")
            Card {
                ActionRow(R.drawable.ic_layers, "安装内置外屏工具", "随 App 提供直连 DP 与手动刷新率工具",
                    enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            openManager = false
                            message = withContext(Dispatchers.IO) { RefreshExtension.installRootTools(activity) }
                            busy = false
                        }
                    })
                ExternalRefreshControl(status.extId, status.vdId)
                ManualRefreshControl(status.extId, status.vdId)
            }
        }
    }
    val dismissMessage = { message = ""; openManager = false }
    WindowDialog(show = message.isNotEmpty(), title = "Root 扩展", onDismissRequest = dismissMessage) {
        Text(message, modifier = Modifier.padding(16.dp), fontSize = 15.sp)
        if (openManager) TextButton(text = "打开 LSPosed", onClick = {
            dismissMessage()
            RefreshExtension.openManager(activity)
        }, modifier = Modifier.fillMaxWidth())
        TextButton(text = "知道了", onClick = dismissMessage, modifier = Modifier.fillMaxWidth())
    }
}

private val externalRefreshWaiting = setOf("STARTING", "WAIT_UNPLUG", "READY_TO_RECONNECT")
private val externalRefreshReady = setOf("IDLE", "FAILED_RESTORED")
private val externalRefreshOptions = listOf(
    Triple(144, RefreshExtension.ExternalRefreshAction.CHECK_144, RefreshExtension.ExternalRefreshAction.START_144),
    Triple(165, RefreshExtension.ExternalRefreshAction.CHECK_165, RefreshExtension.ExternalRefreshAction.START_165)
)

@Composable
private fun ExternalRefreshControl(extId: Int, desktopId: Int) {
    var open by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var checks by remember(extId, desktopId) { mutableStateOf<Map<Int, String>?>(null) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val waiting = state in externalRefreshWaiting
    LaunchedEffect(open, busy, lifecycle, extId, desktopId) {
        if (!open || busy) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            checks = null
            do {
                state = withContext(Dispatchers.IO) {
                    RefreshExtension.externalRefresh(RefreshExtension.ExternalRefreshAction.STATUS)
                }
                if (state !in externalRefreshWaiting) break
                delay(1000)
            } while (true)
            if (extId > 0 && state in externalRefreshReady) {
                checks = withContext(Dispatchers.IO) {
                    externalRefreshOptions.associate { (rate, action) ->
                        rate to RefreshExtension.externalRefresh(action)
                    }
                }
            }
        }
    }
    val runAction: (RefreshExtension.ExternalRefreshAction) -> Unit = { action ->
        busy = true
        error = ""
        scope.launch {
            val result = withContext(Dispatchers.IO) { RefreshExtension.externalRefresh(action) }
            if (result.startsWith("ERROR")) error = result else if (result.isNotEmpty()) state = result
            busy = false
        }
    }
    ActionRow(R.drawable.ic_refresh, "直连 DP 高刷新率", "2560 × 1440 · 按本次连接启用",
        onClick = { error = ""; state = ""; checks = null; open = true })
    WindowDialog(show = open, title = "直连 DP 高刷新率", onDismissRequest = { open = false }) {
        val message = when (state) {
            "" -> "正在读取高刷状态…"
            "STARTING" -> "正在检查设备和显示器…"
            "WAIT_UNPLUG" -> "请拔下平板的 USB-C → DP 线，等页面提示后再插回。"
            "READY_TO_RECONNECT" -> "现在插回同一根 DP 线，随后查看显示器提示的刷新率。"
            "CONFIGURED 144", "CONFIGURED 165" ->
                "输出已设为 2560 × 1440 @ ${state.substringAfter(' ')} Hz。重新接线可恢复常规模式。"
            "IDLE" -> "选择 2560 × 1440 的刷新率，再按提示重新插线。"
            "FAILED_RESTORED" -> "未能启用高刷，临时改动已恢复。"
            "RESTORE_ERROR" -> "高刷工具未能确认恢复，请查看设备端日志。"
            "NOT_INSTALLED" -> "此设备尚未安装 Midex 外屏高刷工具。"
            "UNSUPPORTED" -> "当前固件暂无适配。"
            else -> "读取高刷状态失败。$state"
        }
        val results = checks
        val available = externalRefreshOptions.filter { (rate) ->
            results?.get(rate)?.lineSequence()?.lastOrNull() == "CHECK_OK"
        }
        val statusMessage = when {
            state !in externalRefreshReady -> message
            extId <= 0 -> "请先通过 USB-C → DP 直连显示器。"
            results == null -> "正在检查当前连接可用的高刷档位…"
            available.isNotEmpty() -> message
            results.values.any { it.contains("Monitor EDID mismatch") } ->
                "当前连接未通过直连 DP 的显示器校验。可在「外屏刷新率」中选择此连接提供的档位。"
            else -> "当前设备或连接未通过高刷检查。\n${results.values.first()}"
        }
        Text(statusMessage, modifier = Modifier.padding(16.dp), fontSize = 15.sp)
        if (error.isNotEmpty()) Text(error, modifier = Modifier.padding(horizontal = 16.dp), fontSize = 14.sp)
        if (waiting) {
            TextButton(text = "取消本次切换", enabled = !busy, modifier = Modifier.fillMaxWidth(),
                onClick = { runAction(RefreshExtension.ExternalRefreshAction.CANCEL) })
        } else if (state in externalRefreshReady && extId > 0) {
            available.forEach { (rate, _, action) ->
                TextButton(text = "启用 $rate Hz", enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    onClick = { runAction(action) })
            }
        }
        TextButton(text = "关闭", onClick = { open = false }, modifier = Modifier.fillMaxWidth())
    }
}

private data class ManualRefreshBudget(val clockKHz: Int, val minimum: Int, val maximum: Int)

@Composable
private fun ManualRefreshControl(extId: Int, desktopId: Int) {
    var open by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf("") }
    var budget by remember(extId, desktopId) { mutableStateOf<ManualRefreshBudget?>(null) }
    var issue by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val waiting = state in externalRefreshWaiting
    val requested = input.toIntOrNull()

    LaunchedEffect(open, busy, lifecycle, extId, desktopId) {
        if (!open || busy) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            budget = null
            do {
                state = withContext(Dispatchers.IO) {
                    RefreshExtension.manualRefresh(RefreshExtension.ManualRefreshAction.STATUS, 0)
                }
                if (state !in externalRefreshWaiting) break
                delay(700)
            } while (true)
            if (extId <= 0 || !(state == "IDLE" || state.startsWith("CONFIGURED ") ||
                        state.startsWith("FAILED_RESTORED "))) return@repeatOnLifecycle
            val result = withContext(Dispatchers.IO) {
                RefreshExtension.manualRefresh(RefreshExtension.ManualRefreshAction.BUDGET, 0)
            }
            val fields = result.split(' ')
            val clock = fields.getOrNull(1)?.toIntOrNull()
            val minimum = fields.getOrNull(2)?.toIntOrNull()
            val maximum = fields.getOrNull(3)?.toIntOrNull()
            if (fields.first() == "BUDGET" && clock != null && minimum != null && maximum != null) {
                budget = ManualRefreshBudget(clock, minimum, maximum)
            } else {
                issue = result
            }
        }
    }

    val runAction: (RefreshExtension.ManualRefreshAction) -> Unit = { action ->
        busy = true
        issue = ""
        scope.launch {
            val result = withContext(Dispatchers.IO) { RefreshExtension.manualRefresh(action, requested ?: 0) }
            if (result.startsWith("ERROR")) issue = result
            else if (result.isNotEmpty()) state = result
            busy = false
        }
    }
    ActionRow(R.drawable.ic_refresh, "自定义外屏刷新率", "2560 × 1440 · 自动计算链路预算",
        onClick = { issue = ""; budget = null; open = true })
    WindowDialog(show = open, title = "自定义外屏刷新率", onDismissRequest = { open = false }) {
        val limits = budget
        val message = when {
            state == "STARTING" -> "正在复查当前链路和目标刷新率…"
            state == "WAIT_UNPLUG" -> "请拔下平板侧的 USB-C 线，等页面提示后再插回。"
            state == "READY_TO_RECONNECT" -> "请插回同一条线，保持扩展坞和显示器的连接方式。"
            state.startsWith("CONFIGURED ") ->
                "已检测到 2560 × 1440 @ ${state.split(' ')[1]} Hz。重插可恢复常规模式。"
            state == "FAILED_RESTORED OUT_OF_BUDGET" -> "目标超出当前预算。请选择范围内的刷新率。"
            state.startsWith("FAILED_RESTORED ") -> "切换未完成。临时设置已恢复，重插可恢复常规模式。"
            state == "RESTORE_ERROR" -> "清理未确认。请查看设备端高刷日志。"
            state == "NOT_INSTALLED" -> "需要安装 Midex 自定义刷新率 Root 组件。"
            state == "UNSUPPORTED" -> "当前固件暂无适配。"
            state.startsWith("ERROR ") -> "读取状态失败。请检查 Root 授权。"
            state == "" -> "正在读取外屏状态…"
            extId <= 0 -> "请先连接外屏。"
            limits == null -> if (issue.isEmpty()) "正在读取当前 DP 链路预算…" else ""
            else -> "使用此连接强制设置 2560 × 1440 的刷新率。"
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (message.isNotEmpty()) Text(message, fontSize = 15.sp)
            if (issue.isNotEmpty()) Text(when (issue) {
                "ERROR NEED_2K" -> "请先将外屏物理输出设为 2560 × 1440。"
                "ERROR SESSION_BUSY" -> "另一次刷新率切换正在进行。"
                "ERROR LINK_BUDGET", "ERROR NO_LINK" -> "暂时无法读取链路预算。请检查外屏连接。"
                else -> "无法读取或应用自定义刷新率。$issue"
            }, fontSize = 14.sp)
            if (!waiting && limits != null) {
                Text("链路像素时钟预算 ${limits.clockKHz / 1000.0} MHz", fontSize = 15.sp)
                if (limits.maximum == 0) {
                    Text("当前链路和显示器范围内没有 60 Hz 以上的自定义档位。", fontSize = 14.sp)
                } else {
                    Text("估算上限 ${limits.maximum} Hz · 可输入 ${limits.minimum}–${limits.maximum} Hz",
                        fontSize = 14.sp)
                    TextField(value = input, onValueChange = { value ->
                        input = value.filter { it in '0'..'9' }.take(3)
                    }, label = "目标刷新率（Hz）", singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth())
                    if (requested != null && requested !in limits.minimum..limits.maximum) {
                        Text("目标刷新率超出当前可试范围。", fontSize = 14.sp)
                    }
                }
            }
        }
        if (waiting) {
            TextButton(text = "取消本次切换", enabled = !busy, modifier = Modifier.fillMaxWidth(),
                onClick = { runAction(RefreshExtension.ManualRefreshAction.CANCEL) })
        } else if (limits != null && limits.maximum > 0) {
            TextButton(text = "应用并按提示重插",
                enabled = !busy && requested != null && requested in limits.minimum..limits.maximum,
                modifier = Modifier.fillMaxWidth(),
                onClick = { runAction(RefreshExtension.ManualRefreshAction.START) })
        }
        TextButton(text = "关闭", onClick = { open = false }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun AppsPage(activity: MainActivity, snapshot: Snapshot, apps: List<InstalledApp>?,
                     padding: PaddingValues, modifier: Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<InstalledApp?>(null) }
    var overrides by remember { mutableStateOf(snapshot.modes) }
    var reset by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(snapshot.modes) { overrides = snapshot.modes }
    val filtered = remember(apps, query) {
        apps.orEmpty().filter { it.name.contains(query, true) || it.pkg.contains(query, true) }
    }
    LazyVerticalGrid(columns = GridCells.Adaptive(360.dp), modifier = modifier, contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
        TextField(value = query, onValueChange = { query = it }, label = "搜索应用", singleLine = true,
            modifier = Modifier.fillMaxWidth(), leadingIcon = {
                Icon(painterResource(R.drawable.ic_search), null,
                    Modifier.padding(start = 16.dp, end = 8.dp).size(20.dp))
            })
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
        Card {
            BasicComponent(title = "分应用显示模式", summary = if (snapshot.status.connected)
                "${overrides.count { it.value != 0 }} 个应用使用独立策略" else "激活服务后可配置应用显示策略")
            ArrowPreference(title = "重置所有应用", onClick = { reset = true }, enabled = snapshot.status.connected)
        }
        }
        if (filtered.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Card { BasicComponent(title = if (apps == null) "正在读取应用" else "未找到匹配的应用") }
        }
        items(filtered, key = { it.pkg }) { app ->
            Card {
                BasicComponent(enabled = snapshot.status.connected,
                    endActions = { Text(BridgeClient.appModeLabel(overrides[app.pkg] ?: 0),
                        modifier = Modifier.widthIn(max = 88.dp).padding(start = 8.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) },
                    onClick = { selected = app }) {
                    Text(app.name, fontSize = MiuixTheme.textStyles.headline1.fontSize,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(app.pkg, fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    WindowDialog(show = selected != null, title = selected?.name, onDismissRequest = { selected = null }) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            listOf("跟随系统默认", "强制横屏全屏", "竖屏居中兼容", "自由小窗模式").forEachIndexed { mode, label ->
                BasicComponent(title = label, onClick = {
                    val app = selected ?: return@BasicComponent
                    selected = null
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { BridgeClient.setAppMode(app.pkg, mode, true) }
                        if (ok) overrides = overrides + (app.pkg to mode)
                        Toast.makeText(activity, if (ok) "已更新 ${app.name}" else "设置失败，请检查后台服务", Toast.LENGTH_SHORT).show()
                        BridgeService.refreshFromReceiver(activity)
                    }
                })
            }
        }
    }
    WindowDialog(show = reset, title = "重置所有应用", summary = "清除自定义显示策略，恢复为跟随系统默认。",
        onDismissRequest = { reset = false }) {
        TextButton(text = "重置", onClick = {
            reset = false
            scope.launch {
                val ok = withContext(Dispatchers.IO) { BridgeClient.resetAllAppModes() }
                if (ok) overrides = emptyMap()
                Toast.makeText(activity, if (ok) "已重置所有应用" else "重置失败，请检查后台服务", Toast.LENGTH_SHORT).show()
                BridgeService.refreshFromReceiver(activity)
            }
        }, modifier = Modifier.fillMaxWidth())
    }
}

private fun LazyListScope.settingsPage(activity: MainActivity, hasRoot: Boolean, rootMode: Boolean,
                                        runtime: BridgeClient.RuntimeInfo, checkingAccess: Boolean,
                                        checkAccess: () -> Unit, activate: () -> Unit, activateAdb: () -> Unit,
                                        setRootMode: (Boolean) -> Unit, glass: Boolean,
                                        setGlass: (Boolean) -> Unit, openDriver: () -> Unit) {
    item { SmallTitle("运行模式") }
    item {
        Card {
            if (hasRoot) SwitchPreference(checked = rootMode, onCheckedChange = setRootMode, title = "Root 模式",
                summary = "Root 激活、开机常驻和内置扩展")
            if (runtime.valid) BasicComponent(title = "当前服务", summary = BridgeClient.hostModeLabel(runtime.hostModeName))
            ActionRow(R.drawable.ic_power, "Root 一键激活", onClick = activate)
            ActionRow(R.drawable.ic_power, "ADB 激活", "无线调试或电脑 ADB", onClick = activateAdb)
            if (rootMode) ActionRow(R.drawable.ic_refresh, "外屏即插即用",
                "安装或关闭内置开机模块", onClick = activity::toggleRootAutostart)
        }
    }
    item { SmallTitle("应用外观") }
    item {
        var highRefresh by remember { mutableStateOf(WindowRefreshRate.isEnabled(activity)) }
        Card {
            SwitchPreference(checked = glass, onCheckedChange = setGlass, title = "液态玻璃底栏",
                summary = "背景模糊、边缘折射与交互高光")
            BasicComponent(title = "颜色模式", summary = "跟随系统")
            SwitchPreference(checked = highRefresh, title = "内屏高刷新率",
                summary = "为控制台和触摸板请求屏幕最高刷新率，系统策略仍可能限制",
                onCheckedChange = { highRefresh = it; activity.setHighRefresh(it) })
        }
    }
    item { SmallTitle("权限") }
    item {
        Card {
            ActionRow(R.drawable.ic_display, "悬浮窗", if (Settings.canDrawOverlays(activity)) "已授权" else "用于光标与浮动控制条") {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}")))
            }
            ActionRow(R.drawable.ic_info, "通知", if (activity.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
                "已授权" else "用于快捷控制与无线配对") {
                activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
            }
            ActionRow(R.drawable.ic_touchpad, "辅助手势", if (NavAssistService.isRunning()) "已开启" else "可选") {
                activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            if (!hasRoot) ActionRow(R.drawable.ic_shield, if (checkingAccess) "正在检查权限" else "检查设备权限",
                enabled = !checkingAccess, onClick = checkAccess)
        }
    }
    item {
        Card {
            ActionRow(R.drawable.ic_chip, "驱动信息", onClick = openDriver)
            InfoRow(R.drawable.ic_info, stringResource(R.string.app_name), "v${BuildConfig.VERSION_NAME}")
        }
    }
}

private fun LazyListScope.driverPage(activity: MainActivity, rootMode: Boolean) {
    item {
        Card {
            BasicComponent(title = "本机驱动信息", summary = "读取显示、DRM / EDID、GPU、面板、Type-C 与输入设备信息")
            ActionRow(R.drawable.ic_refresh, "采集驱动信息", onClick = { activity.collectDriverInfo(rootMode) })
        }
    }
    item {
        Card {
            if (rootMode) ActionRow(R.drawable.ic_export, "保存 JSON", onClick = activity::exportDriverJson)
            ActionRow(R.drawable.ic_info, "分享采集结果", onClick = activity::shareDriverJson)
        }
    }
}
