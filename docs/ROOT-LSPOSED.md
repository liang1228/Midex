# Root / LSPosed 双模式说明

## 三种运行模式

| 模式 | 启动方式 | 进程 | 权限 | 适用 |
|---|---|---|---|---|
| `shell` | 无线调试 / 电脑 ADB | `app_process` (uid 2000) | shell | 原版免 Root 路径 |
| `root` | App 内 `su -c` | `app_process` (uid 0) | root / KernelSU / Magisk | 本机默认推荐 |
| `system_server` | LSPosed 注入 | `system_server` (uid 1000) | 系统框架 | 不想每次开机拉起进程 |

守护进程启动时通过反射写入 `extbridge.host` / `extbridge.root` / `extbridge.lsposed`
系统属性（非持久），用于两种模式互斥：

- `RootBootstrap` 在启动 root 守护进程前先查 `BridgeClient` 的运行模式；如果是
  `system_server` 就拒绝重复启动。
- Root 开机自启脚本会检查 `extbridge.lsposed`，LSPosed 已接管时直接退出。

## Root 模式

```
App → su -c "cp asset-jar /data/local/tmp/extbridge.jar && app_process ... ExtBridge"
    → daemon 通过 ContentProvider 把 Binder 回传给 App
```

`RootBootstrap` 只做四件事：释放 jar、拷贝到 `/data/local/tmp`、以 root 启动
`app_process`、等待 Binder 握手。jar 从 APK 的 `assets/extbridge.jar` 解出，App 自身
不需要存储权限。

开机自启脚本安装到 `/data/adb/service.d/99-extbridge.sh`，等待
`sys.boot_completed` 后再拉起；删除该文件即可关闭。

## LSPosed 模式

同一个 APK 既是控制台也是模块：

- `helper/AndroidManifest.xml` 声明 `xposedmodule` / `xposeddescription` / `xposedminversion`；
- `helper/assets/xposed_init` 指向 `com.extbridge.lsposed.ExtBridgeModule`；
- 模块只处理 `packageName == "android"` 且 `processName == "android"`、uid 1000 的进程；
- 挂 `SystemServer.startOtherServices`，在结束后延迟 4 秒调用
  `ExtBridge.startInSystemServer(systemContext)`；另有 15 秒兜底；
- `startInSystemServer` 不调用 `Looper.prepareMainLooper()` / `Looper.loop()`，
  也不允许 `System.exit()`，只把 `tick` 投递到 system_server 已有主线程；
- 紧急停用：`/data/adb/extbridge/disable_lsposed` 文件存在时模块直接返回。

### 构建

`build_linux.sh` 编译时把 `ExtBridge` / `DriverProbe` 同时打进：

- `assets/extbridge.jar`（app_process 用）；
- `classes.dex`（LSPosed 模块类加载器用）。

Xposed API 只作为编译期 classpath（`lsposed/libs/api-82.jar`），不会打进 APK。

### 启用

1. LSPosed 管理器 → 模块 → 勾选 ExtBridge；
2. 作用域勾选「系统框架 (android)」；
3. 重启（或软重启 system_server）；
4. 控制台「设置」或「驱动」页应显示「LSPosed 系统框架模式」。

## 驱动信息

`DriverProbe.collectJson(DisplayManager, hostMode)` 只使用公开 Android API + 文件读取 +
少量 shell，输出稳定 JSON。root / system_server 下比 shell 多出：

- `/sys/class/drm/card*-*/edid`（完整 EDID 与解析）；
- `/sys/kernel/debug/dri/0/{name,clients}`；
- dmesg 中 DPU / DSI / Type-C / USB 相关行；
- 更完整的 Type-C、USB、GPU 节点。

CLI：

```bash
CLASSPATH=/data/local/tmp/extbridge.jar \
  app_process /data/local/tmp com.extbridge.daemon.ExtBridge probe
```

## 已验证 / 未验证

- 已验证：本机 KernelSU root 模式启动、外接 DP 屏接管、输入注入、驱动 JSON 采集；
- 已验证：APK 安装、LSPosed 模块元数据与 dex 类存在；
- 未验证：system_server 注入后的长时间稳定性（需要启用模块并重启后观察）；
- 未验证：非小米 / 非 Android 17 的 OEM 兼容性。
