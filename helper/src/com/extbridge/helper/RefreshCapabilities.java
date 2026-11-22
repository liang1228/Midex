package com.extbridge.helper;

import com.extbridge.refreshunlock.RefreshUnlockContract;
import com.extbridge.refreshunlock.WindowDecorationContract;

/** Permission and module state; independent of Android so the gate can be tested on the host. */
public final class RefreshCapabilities {
    public enum Tier { BASE, UNSUPPORTED, INSTALL, UPDATE, ACTIVATE, READY, ENABLED }

    public final Tier tier;
    public final boolean rootPresent;
    public final boolean installed;
    public final boolean loaded;
    public final boolean enabled;
    public final boolean windowsLoaded;
    public final boolean squareWindows;
    public final boolean nativeCaption;
    public final boolean legacy;

    public RefreshCapabilities(boolean rootPresent, boolean supported, long installedVersion,
            String report, int boot) {
        this(rootPresent, supported, installedVersion, report, null, boot);
    }

    public RefreshCapabilities(boolean rootPresent, boolean supported, long installedVersion,
            String report, String windowsReport, int boot) {
        this(rootPresent, supported, installedVersion, report, windowsReport, boot, false);
    }

    RefreshCapabilities(boolean rootPresent, boolean supported, long installedVersion,
            String report, String windowsReport, int boot, boolean legacy) {
        this.rootPresent = rootPresent;
        this.legacy = legacy;
        installed = installedVersion > 0;
        loaded = rootPresent && supported && installedVersion == RefreshUnlockContract.VERSION
                && RefreshUnlockContract.isLoaded(report, boot);
        enabled = loaded && RefreshUnlockContract.isEnabled(report, boot);
        windowsLoaded = rootPresent && supported && installedVersion == RefreshUnlockContract.VERSION
                && WindowDecorationContract.isLoaded(windowsReport, boot);
        squareWindows = windowsLoaded && WindowDecorationContract.square(windowsReport, boot);
        nativeCaption = windowsLoaded && WindowDecorationContract.caption(windowsReport, boot);
        if (!rootPresent) tier = Tier.BASE;
        else if (!supported) tier = Tier.UNSUPPORTED;
        else if (!installed) tier = Tier.INSTALL;
        else if (installedVersion != RefreshUnlockContract.VERSION) tier = Tier.UPDATE;
        else if (!loaded) tier = Tier.ACTIVATE;
        else tier = enabled ? Tier.ENABLED : Tier.READY;
    }

    public String summary() {
        switch (tier) {
            case UNSUPPORTED: return "当前固件暂无适配";
            case INSTALL: return "内置模块不可用";
            case UPDATE: return "可迁移到主 App 内置扩展";
            case ACTIVATE: return "等待在 LSPosed 中启用并重启设备";
            case READY: return "模块已加载，内屏限刷解锁未开启";
            case ENABLED: return "模块已加载，内屏限刷解锁已开启";
            default: return "使用原有显示设置";
        }
    }
}
