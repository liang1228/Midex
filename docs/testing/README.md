# Midex 0.0.1 验证记录

## 本地构建与模拟器

- 运行 `bash tools/test-display.sh`。显示模式回读 12 项、切档 10 项、桌面稳定窗口 12 项、模式守卫 17 项、Root 扩展判定 41 项均通过。
- Android 模拟器安装并启动本地 0.0.1 构建。应用包名为 `com.extbridge.helper`，版本号为 `0.0.1`，内部版本码为 `28`。[控制台](../images/midex-console.png) · [显示设置](../images/midex-display.png)

## 小米 15 Ultra 直连试验

2026-11-22，在 HyperOS `OS4.0.3.0.XOACNXM` 的小米 15 Ultra 上，用 USB-C 转 DP 四通道直连外接显示器。手机运行 SurfaceFlinger 0.1.4 单次开机预览及临时加载的 4.7.13 测试后端。试验中临时提高 MIUI 刷新率上限，并只允许按内容切换渲染帧率。

| 验证 | 观测值 |
| --- | --- |
| 物理输出 | 23:32:59.926 至 23:36:11.533，316 次连续采样均为 2560×1440 @ 179.99960 Hz |
| 物理时序 | 像素时钟 720200 kHz，水平总像素 2720，垂直总行数 1471 |
| Android 和扩展桌面 | 桌面迁移完成后的 16 次交叉检查均为 180 Hz，扩展桌面 ID 保持一致 |
| 交互与实际观感 | 打开及关闭应用抽屉后读数保持 180 Hz，现场反馈画面清楚、无黑屏 |
| 回退 | 恢复设置后，DRM 回读 2560×1440 @ 119.99759 Hz，SurfaceFlinger 与扩展桌面回到 120 Hz |

[统计结果](stability-summary.json) · [DRM 原始时序](drm-live.tsv) · [分层回读](crosschecks.jsonl) · [第 189 秒桌面](../images/xuanyuan-desktop-180hz.png)

以上结论适用于该次直连短测。0.0.1 APK 单独安装后的物理高刷，以及高刷长测、重启与扩展坞表现仍待验证。
