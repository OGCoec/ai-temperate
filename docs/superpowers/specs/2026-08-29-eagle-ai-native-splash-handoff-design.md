# Eagle AI 原生启动层重新挂载与双信号交接设计

## 目标

修复 DCloud 在 `PandoraEntryActivity` 初始化期间替换内容视图、导致 `EagleSplashView` 在首帧前失去父节点的问题，并避免登录页或主页尚未真正显示时提前淡出原生覆盖层。

## 已确认事实

- 原生覆盖层在启动后约 62ms 内由 `ContentFrameLayout` 子节点变为 `parent=null`。
- `ValueAnimator` 虽然启动，但覆盖层没有发生 `onAttachedToWindow` 或首次绘制。
- DCloud 静态 Splash 关闭后约 500ms 才创建首个 UniApp 窗口。
- `login-ready` 比后续 UniApp 窗口创建早约 101ms。
- `DecorView` 标识始终相同，因此不需要使用独立 Window 或系统级悬浮层。

## 设计

1. `EagleSplashController.ensureOnTop()` 在覆盖层没有父节点时，将同一个覆盖层重新加入当前 `android.R.id.content` 容器；动画不重新从零开始，而是按共享 1900ms 周期恢复当前相位。
2. 重新挂载只允许发生在 `ATTACHED` 或 `RUNNING` 状态；进入 `DISMISSING` 后不得重新挂载。
3. 登录页和认证主页在 `onLoad` 阶段绑定自身 App WebView 的 `show`/`loaded` 事件，在业务页面完成 `$nextTick` 后标记 DOM 已准备。只有 DOM 已准备且 WebView 已显示时才请求原生层淡出。
4. WebView 事件不可用时使用有界安全回退：DOM 准备后最多保留原生层 1200ms，再执行淡出；该回退优先保证不露出黑屏。
5. `session-error` 和 `route-failed` 不发生跨页面切换，继续在当前错误页完成双帧绘制后关闭原生层。
6. 保留30秒总超时、关闭幂等、后台恢复不重播、系统动画关闭时静态降级和现有固定原因白名单。

## 验收

- 真机日志出现 `REATTACH`、`OVERLAY_ATTACHED`、`SHIMMER_FIRST_DRAW` 和第一周期三个里程碑。
- DCloud Splash 关闭后的空档始终由原生 Eagle 动画覆盖。
- `login-ready`/`home-ready` 在目标 WebView 的 `show` 信号和页面 DOM 准备后才触发。
- 冷启动不再出现静态页、黑屏、第二套加载页和目标页之间的可见跳变。
- 正式发布前关闭诊断开关。
