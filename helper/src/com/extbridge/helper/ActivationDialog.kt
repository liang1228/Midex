package com.extbridge.helper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun ActivationDialog(activity: MainActivity, show: Boolean, onDismiss: () -> Unit) {
    var mode by remember(show) { mutableIntStateOf(0) }
    var host by remember(show) { mutableStateOf(AdbBootstrap.getLocalIpv4()) }
    var pairPort by remember(show) { mutableStateOf("") }
    var pairCode by remember(show) { mutableStateOf("") }
    var connectPort by remember(show) { mutableStateOf("") }
    var hostEdited by remember(show) { mutableStateOf(false) }
    var pairEdited by remember(show) { mutableStateOf(false) }
    var connectEdited by remember(show) { mutableStateOf(false) }
    var discovery by remember(show) { mutableStateOf("正在查找无线调试服务…") }
    var status by remember(show) { mutableStateOf("") }
    var working by remember(show) { mutableStateOf(false) }

    DisposableEffect(show, mode) {
        if (show && mode == 1) {
            AdbBootstrap.startDiscovery(activity) { state ->
                val pair = if (state.pairingPort > 0) "${state.pairingHost}:${state.pairingPort}" else "未开启配对弹窗"
                val connection = if (state.connectPort > 0) "${state.connectHost}:${state.connectPort}" else "待检测"
                discovery = "配对：$pair\n连接：$connection"
                val bestHost = state.pairingHost.ifEmpty { state.connectHost }
                if (!hostEdited && bestHost.isNotEmpty()) host = bestHost
                if (!pairEdited && state.pairingPort > 0) pairPort = state.pairingPort.toString()
                if (!connectEdited && state.connectPort > 0) connectPort = state.connectPort.toString()
            }
        }
        onDispose { if (show && mode == 1) AdbBootstrap.stopDiscovery() }
    }

    val listener = object : AdbBootstrap.ProgressListener {
        override fun onStep(message: String) { status = message }
        override fun onResult(success: Boolean, message: String) {
            working = false
            status = message
            if (success && BridgeClient.getStatus().connected) {
                AdbBootstrap.cancelPairingNotification(activity)
                onDismiss()
            }
        }
    }

    WindowDialog(show = show, title = "ADB 激活",
        summary = "使用无线调试或电脑 ADB 启动 ExtBridge。设备重启后需重新激活。",
        onDismissRequest = onDismiss) {
        val scroll = key(mode) { rememberScrollState() }
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (mode) {
                0 -> {
                    BasicComponent(title = "无线调试", summary = "在本机配对并激活", onClick = { mode = 1 })
                    BasicComponent(title = "ADB 命令", summary = "在电脑执行命令激活", onClick = { mode = 2 })
                }
                1 -> Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("自动发现\n$discovery", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    TextButton(text = "打开无线调试设置", modifier = Modifier.fillMaxWidth(), onClick = {
                        activity.ensureNotifPermAndShowPairNotif()
                        try { activity.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                        catch (_: Exception) { activity.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                    })
                    TextButton(text = "发送配对通知", modifier = Modifier.fillMaxWidth(), onClick = {
                        activity.ensureNotifPermAndShowPairNotif()
                        Toast.makeText(activity, "请在通知中输入配对码", Toast.LENGTH_SHORT).show()
                    })
                    TextField(value = host, onValueChange = { host = it; hostEdited = true }, label = "本机 IP 地址",
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextField(value = pairPort, onValueChange = { pairPort = it; pairEdited = true }, label = "配对端口",
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    TextField(value = pairCode, onValueChange = { pairCode = it }, label = "6 位配对码",
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    TextField(value = connectPort, onValueChange = { connectPort = it; connectEdited = true }, label = "连接端口",
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    Text("已配对的设备可直接填写连接端口。也可通过通知输入配对码。", fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    TextButton(text = "配对并激活", enabled = !working, modifier = Modifier.fillMaxWidth(), onClick = {
                        working = true
                        status = "正在配对并启动服务…"
                        AdbBootstrap.pairAndBootstrapAsync(activity, host.trim(), pairPort.toIntOrNull() ?: 0,
                            pairCode.trim(), host.trim(), connectPort.toIntOrNull() ?: 0, listener)
                    })
                    TextButton(text = "已配对，直接连接", enabled = !working, modifier = Modifier.fillMaxWidth(), onClick = {
                        working = true
                        status = "正在连接并启动服务…"
                        AdbBootstrap.connectAndBootstrapAsync(activity, host.trim(), connectPort.toIntOrNull() ?: 0, listener)
                    })
                    if (status.isNotEmpty()) Text(status, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                else -> Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("在电脑通过 USB 或无线 ADB 连接本机后，复制并执行以下命令。", fontSize = 14.sp)
                    val command = remember { AdbBootstrap.buildPcAdbCommand() }
                    SelectionContainer { Text(command, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                    TextButton(text = "复制 ADB 激活命令", modifier = Modifier.fillMaxWidth(), onClick = {
                        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("extbridge_adb", command))
                        Toast.makeText(activity, "已复制命令到剪贴板", Toast.LENGTH_SHORT).show()
                    })
                    Text("命令执行成功后将自动关闭此窗口。", fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            if (mode != 0) TextButton(text = "更换激活方式", modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), onClick = { mode = 0 })
            TextButton(text = "关闭", modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), onClick = onDismiss)
        }
    }
}
