package com.extbridge.helper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

public class ControlReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String act = intent.getAction();
        if ("com.extbridge.helper.TOGGLE_INPUT".equals(act)) {
            int mode = BridgeClient.toggleInput();
            if (mode >= 0) {
                Toast.makeText(context,
                        mode == 1 ? "键鼠已切换至：扩展屏" : "键鼠已切换至：平板内屏",
                        Toast.LENGTH_SHORT).show();
            }
            BridgeService.refreshFromReceiver(context);
        } else if ("com.extbridge.helper.STATE_SYNC".equals(act)) {
            BridgeService.refreshFromReceiver(context);
        } else if ("com.extbridge.helper.REBUILD".equals(act)) {
            BridgeClient.rebuild();
            Toast.makeText(context, "正在刷新扩展屏输出...", Toast.LENGTH_SHORT).show();
        } else if ("com.extbridge.helper.LAUNCH_EXT_HOME".equals(act)) {
            BridgeClient.relaunchLauncher();
            Toast.makeText(context, "已返回扩展屏桌面", Toast.LENGTH_SHORT).show();
        } else if ("com.extbridge.helper.TOGGLE_MIRROR".equals(act)) {
            int next = BridgeClient.toggleMirrorMode();
            BridgeService.refreshFromReceiver(context);
            Toast.makeText(context,
                    next == 1 ? "已切换为【原生镜像模式】" : "已切换为【Midex 扩展桌面】",
                    Toast.LENGTH_SHORT).show();
        }
    }
}
