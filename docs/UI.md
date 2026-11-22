# 控制台 UI

主控制台直接使用 Jetpack Compose + Miuix 0.9.4，与 KernelSU 默认界面使用相同组件库。
默认跟随系统浅色/深色；液态玻璃底栏默认开启。不使用表情符号，图标使用 Lucide 矢量资源。

4.7.0 的切页过渡为 180ms 进入、90ms 退出，保留页面状态及同级玻璃采样层；抽屉进入180ms、退出120ms，从当前状态反向衔接。键盘搜索及Esc直接响应，任务栏按压只缩至0.97。动画关闭时立即落到终态，不保留透明遮罩或帧循环。设置中的“模块即插即用”管理可选 Root 启动模块，控制台自身不承担插拔保活。

## 结构

- `helper/src/com/extbridge/helper/ConsoleUi.kt`：Miuix ThemeController、Scaffold、可折叠大标题、分组卡片、四个主页面与驱动信息页。
- `helper/src/com/extbridge/helper/ui/glass/`：Miuix 官方示例的 Apache-2.0 液态玻璃导航，保留 blur、lens、vibrancy、交互高光和阻尼拖动；修改仅限应用包名、函数名和系统深色判断。
- `MainActivity.java`：Activity 生命周期和现有 Root、无线调试、LSPosed、驱动导出操作；复杂激活与采集结果对话框暂用原生 View。
- `Ui.java`：上述原生对话框的颜色和辅助组件，主页面不依赖它。
- `BridgeClient.java`：Binder 状态和控制协议；Compose 在 STARTED 生命周期中通过 IO 协程读取，暂停时停止轮询。

## 视觉与布局

首页按参考应用组织为状态卡、管理器/设备信息卡、显示/输入卡和操作入口。使用库的默认字号、圆角和组件边距；不另造大面板或指标仪表盘。

底栏的采样源是独立、持续存在的页面容器，内部 Scaffold 可随标签页切换；容器带有不透明背景，底栏作为同级覆盖层绘制，避免把自身反复采样或切页后失去图层。它有真实背景模糊和边缘折射，设置中可关闭玻璃效果。点击、拖动和键盘选择由同一导航实现处理。

页面在窗口宽度至少1000dp时最大1200dp，首页和显示页分为双列；其余最大840dp。应用列表按360dp最小列宽自适应，底栏最大620dp。窄屏单列，内容底部留出导航空间。

## 构建

使用 JDK 21、Gradle wrapper、Android SDK Platform 37.0、build-tools 36.1.0。
`build_v3.ps1` / `build_linux.sh` 调用 `:helper:exportDebug`，产物仍是 `build/out/exthelper.apk` 和 `build/out/extbridge.jar`。
当前依赖的 miuix-blur 最低 Android API 为 33，所以本分支最低系统为 Android 13。

首次构建需要联网获取依赖。签名优先使用 `EXTBRIDGE_KEYSTORE`（密码 `EXTBRIDGE_KEYSTORE_PASS`，默认 `extbridge`），其次仓库根目录 `exthelper.p12`（密码 `extbridge`），最后使用忽略文件 `.extbridge-debug.keystore`（密码 `android`），兼容原有构建的签名选择；密钥均不会随 build 清理而变化。覆盖安装需要与设备上旧版使用同一密钥。
LSPosed API 为 compileOnly；独立 daemon/probe dex jar、xposed_init 和 arm64 adb 二进制均由 Gradle 打包。

## 4.4.0 权限分级

`RefreshCapabilities` 区分基础、固件未适配、未安装、需更新、待加载、已加载未开启和已开启。无 Root 不增加界面；检测到 Root 才在显示页显示 Miuix 扩展卡片。`RefreshExtension` 读取状态不执行 su，仅在明确点击安装/切换时使用 Root。

主 APK 内置独立模块，安装后仍需 LSPosed 系统框架作用域和重启。模块版本及本次开机回执有效才显示限刷开关，切换前要求新 nonce 回执并在失败时恢复原值。原显示选择器、液态玻璃默认值、后端协议及系统刷新率偏好保持。

无 Root 模拟器界面与 shell 后端、真机应用内模块安装通过。2026-11-17补验：0.2.0完整重启加载及新nonce回执通过；接外屏通过应用UI关闭/开启，内屏物理约60/144Hz，外屏保持2560×1440@59.95055Hz，系统刷新率偏好不变。外屏2K高刷、持续应用144FPS和长期稳定性未通过；无Root模拟器未接HDMI。

## 授权

Miuix 示例的 Apache-2.0 头部及上游归属保持不变，完整许可和 Lucide ISC/MIT 声明随 APK 放在 `assets/licenses/`。未复制 KernelSU 的 GPL 应用实现。

## 4.2.0 外接桌面

`LauncherActivity` 保留原生 View 宿主，改成独立桌面和可关闭的应用抽屉。`DesktopStyle` 提供矢量控件、配色与程序绘制的蓝色折叠壁纸；没有打包 Samsung 壁纸素材。

`DesktopTaskbar` 共用于启动器回退栏与服务悬浮栏，运行中的常用应用只显示一个任务入口。`DesktopWindowChrome` 为当前外屏任务提供标题栏和右下角缩放柄；应用内容由系统原生任务渲染，没有截图伪装或嵌入。

窗口协议新增 RPC 42（控制）、43（当前窗口）、44（显示桌面前隐藏外屏任务）。后端限定本虚拟显示器，并通过 WindowContainerTransaction 和 Shell transition 同步实际 Surface；HyperOS 的任务缩放归一化仅作用于该桌面任务。最小化显式隐藏任务，恢复避免 startActivityFromRecents 再次套用内屏保存的几何尺寸。断开/正常退出时先解除本进程记录的隐藏任务。

标题栏当前只覆盖焦点窗口；受保护应用可以隐藏非系统悬浮层。外接任务栏使用半透明深色表面，控制台的 Compose 玻璃底栏保持原实现。

## 4.3.0 刷新率

显示页分别报告物理输出和虚拟桌面刷新率；配置提供自动档及显示器实际支持的档位，保留59.94等小数。自动选择匹配分辨率的最高档，自定义虚拟尺寸沿用当前物理分辨率，不为高刷悄悄降低分辨率。VD跟随系统实际采用的物理模式。

内屏首页显示当前/最高刷新率；设置的高刷开关默认开启，控制台和触摸板在前台请求同分辨率最高档，暂停时清零。系统/OEM仍可限帧；本次目标平板连接HDMI时内屏实际60Hz，即使窗口请求144Hz。玻璃底栏仍默认开启，显式关闭时停止页面backdrop录制。
