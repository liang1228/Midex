# 控制台 UI 结构与修改指南

控制台采用原生 View + 自绘玻璃材质，不依赖 AndroidX / Compose。目标是接近 KernelSU Miuix 的
「黑底 + 分组卡片 + 图标导航」结构，同时保留可离线构建、可在旧 API 30+ 运行的特性。

## 文件

| 文件 | 作用 |
|---|---|
| `MainActivity.java` | 主控制台：页面、卡片、状态刷新、激活对话框、分应用模式对话框 |
| `Ui.java` | 颜色、字号、卡片、行、按钮、导航项的轻量组件 |
| `GlassDrawable.java` | 悬浮底栏的液态玻璃背景（渐变 + 描边 + 高光） |
| `LiquidBackgroundView.java` | 全屏深色渐变背景（极低饱和蓝/青光晕） |
| `ChipFlowLayout.java` | 按实际卡片宽度换行的 chip 容器（修复双列布局溢出） |
| `res/drawable/ic_*.xml` | Material 风格矢量图标 |

## 布局约定

- 宽屏（>= 840dp）和窄屏使用同一套页面；底部是 620dp 悬浮玻璃 Dock，最多 5 个入口。
- 页面内容宽度限制在 1180dp 并居中；左右留白由 `contentSide()` 计算，顶栏标题与卡片左边缘对齐。
- 卡片圆角 24dp、背景 `#141416`；不使用边框和阴影，靠间距和色阶分层。
- 行高 >= 58dp，标题 15.5sp，副标题 12sp；图标 21dp。
- 强调色 `#A8C7FA`（Material You dark primary 的固定回退）；成功 `#7EE2B8`，警告 `#FBCB77`。
- 双列页面：概览（快捷操作 / 运行信息）、显示（分辨率 / 缩放和刷新率）、设置（权限 / 激活）。

## 新增页面

1. 在 `buildUi()` 里 `makePage()` + `addPage()`；
2. 写一个 `buildXxxPage(LinearLayout box)`，用 `Ui.section()`、`Ui.card()`、`addSetting()`；
3. 在 `PAGE_TITLES` 和 `buildDock()` 里加入口；
4. `switchPage()` 里加入显隐。

## 注意

- `addChipWrap()` 现在必须用 `ChipFlowLayout`，不要再用屏幕宽度估算换行（旧实现会在双列卡片里裁切）。
- 状态卡的颜色/标签统一走 `setHeroTint()`；不要单独改标题颜色，否则会破坏语义色。
- 玻璃底栏是静态半透明 + 高光 + 阴影，不做实时 backdrop 采样，避免 3200×2136 掉帧。
