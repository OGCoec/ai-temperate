# Eagle AI 启动动画修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Eagle AI 从点击图标到登录页或主页面出现之前始终显示同一套完整加载画面，并让斜向 Shimmer 在真机上清晰、连续、无标题栏闪现。

**Architecture:** Android 原生覆盖层是 App 冷启动期间唯一可见的动态加载层；UniApp `session-gate` 在其下方恢复会话并决定最终路由。原生层只在登录页或已认证主页完成首帧绘制后淡出；如果会话恢复出现可重试错误，则交接给同视觉的 UniApp 会话页。DCloud PNG Splash 仅作为系统要求的静态第一帧，不承担动画职责。

**Tech Stack:** Android Java、DCloud Android Offline SDK 5.24、UniApp Vue 3、SCSS、Node.js contract tests、JUnit 4、ADB/logcat。

**Baseline commit:** `7c9772e4`（前端仓库当前还有用户未提交改动，实施时必须保留；Android 离线工程位于仓库外）。

---

## 1. 已确认根因

1. DCloud `SplashView` 从 `08:56:20.444` 存活到 `08:56:24.042`，当前 `alwaysShowBeforeRender: true` 会等待首页渲染。
2. 原生 `EagleSplashController` 只添加 132dp 鹰标，没有添加 `WELCOME BACK`、标题和说明，所以第一段与第二段天生不同。
3. `session-gate` 在 `onReady` 中立即调用 `dismissNativeSplash()`，同时 `onLoad` 的会话恢复又立即 `reLaunch`。
4. 真机日志中，`session-gate` 和下一个 UniApp 窗口的创建间隔只有约 292ms；当前 2350ms Shimmer 没有足够可见时间。
5. `pages.json` 为 `session-gate` 配置了默认原生导航栏标题“正在启动”，导致交接时额外闪出一套顶部 UI。
6. 原生逐帧画面存在轻微亮度变化，但高光面积和停留比例使动画肉眼近似静态。
7. 系统动画倍率均为 1.0；不是手机开发者选项关闭了动画。
8. `BuildConfig` 缺失是独立的 DCloud/UTS 初始化警告，不是 Shimmer 消失的主因，但应单独修复。

## 2. 目标启动时序

```text
点击 Eagle AI
  → Android/DCloud 静态 PNG（只作为不可取消的极短第一帧，画面与动态层一致）
  → EagleApplication 在 PandoraEntryActivity 上挂完整原生加载层
  → 原生鹰标持续 Shimmer；完整文字从第一帧开始存在
  → UniApp session-gate 在覆盖层下恢复会话
      ├─ 未登录：reLaunch 登录页，登录页 onReady 后淡出原生层
      ├─ 已登录：reLaunch 主页面，认证与工作区首帧就绪后淡出原生层
      └─ 可重试错误：将相位交给 session-gate，淡出原生层并显示重试按钮
  → 登录页 / 主页面 / 错误交互页
```

用户可见的是一段连续动画，而不是两套加载 UI 相互替换。

## 3. 文件职责图

### Android 离线工程

- Modify: `C:/Users/damn/Downloads/5.24/Android-SDK/@5.24.82669_20260813/HBuilder-HelloUniApp/app/src/main/java/site/niko000o/aitemperate/launch/EagleApplication.java`
  - 监听 `PandoraEntryActivity` 创建、恢复和销毁；挂载覆盖层并保证层级在最上方。
- Modify: `C:/Users/damn/Downloads/5.24/Android-SDK/@5.24.82669_20260813/HBuilder-HelloUniApp/app/src/main/java/site/niko000o/aitemperate/launch/EagleSplashController.java`
  - 管理挂载、置顶、带原因关闭、160ms 淡出和 30 秒兜底。
- Create: `C:/Users/damn/Downloads/5.24/Android-SDK/@5.24.82669_20260813/HBuilder-HelloUniApp/app/src/main/java/site/niko000o/aitemperate/launch/EagleSplashView.java`
  - 只负责完整原生加载页布局：鹰标、英文提示、中文标题和说明。
- Modify: `C:/Users/damn/Downloads/5.24/Android-SDK/@5.24.82669_20260813/HBuilder-HelloUniApp/app/src/main/java/site/niko000o/aitemperate/launch/EagleShimmerView.java`
  - 只负责鹰标容器、内外框、斜向高光和静态降级。
- Modify: `C:/Users/damn/Downloads/5.24/Android-SDK/@5.24.82669_20260813/HBuilder-HelloUniApp/app/src/test/java/site/niko000o/aitemperate/launch/EagleSplashTimingTest.java`
  - 约束新周期、活动区间、周期偏移和透明度边界。
- Modify: `C:/Users/damn/Downloads/5.24/Android-SDK/@5.24.82669_20260813/HBuilder-HelloUniApp/app/src/main/res/drawable-xxhdpi/splash.png`
  - 改为与完整原生加载层相同的静态第一帧；PNG 本身不伪装成动画。
- Modify: `C:/Users/damn/Downloads/5.24/Android-SDK/@5.24.82669_20260813/HBuilder-HelloUniApp/app/build.gradle`
  - 打开 `BuildConfig` 生成，移除 UTS 启动时的 `ClassNotFoundException`。

### UniApp 前端

- Modify: `fornted/manifest.json`
  - `alwaysShowBeforeRender` 改为 `false`；保留 `autoclose: true`、`waiting: false`。
- Modify: `fornted/pages.json`
  - `session-gate` 改为 `navigationStyle: "custom"`，删除“正在启动”原生标题栏。
- Modify: `fornted/pages/launch/session-gate.vue`
  - `onReady` 只启动 CSS Shimmer，不关闭原生覆盖层；`go()` 只路由；仅在可重试错误时交接给本页。
- Modify: `fornted/common/launch/eagle-native-splash.js`
  - 提供幂等的目标页交接方法，并向原生传入固定关闭原因；保留异常兜底。
- Modify: `fornted/pages/auth/login.vue`
  - 登录页 `onReady` 完成一帧绘制后关闭原生覆盖层。
- Modify: `fornted/pages/ai-chat/index.vue`
  - 仅在 `authReady` 且工作区首帧完成后关闭原生覆盖层。
- Modify: `fornted/pages/launch/session-gate-branding.contract.test.cjs`
  - 约束会话页不再过早关闭、无默认标题栏、错误路径可交接。
- Modify: `fornted/pages/ai-chat/ai-chat-page-contract.test.cjs`
  - 约束认证主页通过明确的 ready hook 关闭覆盖层。
- Modify: `fornted/pages/auth/auth-ui-structure.test.cjs`
  - 约束登录页在 `onReady` 执行最终交接。

## 4. 分步实施计划

### Task 1：先用契约测试锁定错误生命周期

- [ ] 在 `session-gate-branding.contract.test.cjs` 新增失败断言：
  - `session-gate.onReady` 不得直接调用 `dismissNativeSplash`。
  - `go(url)` 不得在 `reLaunch` 前关闭原生层。
  - `pages.json` 中 `session-gate.style.navigationStyle` 必须为 `custom`。
  - `manifest.json` 中 `alwaysShowBeforeRender` 必须为 `false`。
- [ ] 在登录页和主页现有 contract test 中新增失败断言：最终目标页必须调用统一 ready handoff。
- [ ] 第一阶段只编写测试，不执行；用户确认验证阶段后才运行。

### Task 2：修正 DCloud 静态 Splash 与页面标题栏

- [ ] 将 `fornted/manifest.json` 调整为：

```json
"splashscreen": {
  "alwaysShowBeforeRender": false,
  "waiting": false,
  "autoclose": true,
  "delay": 0
}
```

- [ ] 将 `session-gate` 页面样式调整为：

```json
"style": {
  "navigationStyle": "custom",
  "backgroundColor": "#0b0d0c"
}
```

- [ ] 不删除 DCloud Splash。官方说明 App Splash 必然存在且只支持静态 PNG；本步骤只是让它在首页加载后关闭，不再等待首页完全渲染。
- [ ] 静态 `splash.png` 必须与动态页的初始画面一致，避免 Android 系统第一帧出现只有鹰标的旧状态。

### Task 3：把原生第一阶段改成完整加载页

- [ ] 新建 `EagleSplashView`，内部使用一个全屏 `FrameLayout` 和居中纵向内容容器。
- [ ] 视觉参数固定为：
  - 背景：`#0B0D0C`。
  - Logo：132dp × 132dp；圆角 38dp；内层 inset 8dp、圆角 31dp。
  - Logo 下间距：30dp。
  - `WELCOME BACK`：13sp、粗体、`#37D39A`、字距约 `0.154em`、下间距 12dp。
  - `正在恢复会话`：28sp、粗体、`#F3F5F4`、行高 1.25。
  - `正在安全确认登录状态，请稍候。`：15sp、`#8B9690`、行高 1.6、上间距 12dp。
- [ ] 状态栏、导航栏和整个内容区全部使用 `#0B0D0C`；系统图标保持浅色。
- [ ] 鹰标继续保持纯白且位于最高绘制层，不参与颜色变化。
- [ ] `EagleSplashController` 改为挂载 `EagleSplashView`，而不是直接挂一个孤立的 `EagleShimmerView`。
- [ ] `EagleApplication.onActivityResumed` 调用 `ensureOnTop(activity)`，防止 DCloud 后续窗口操作改变覆盖层层级。
- [ ] 不替换 `PandoraEntry` 或 `PandoraEntryActivity`，不复制 DCloud 核心 Activity。

### Task 4：让 Shimmer 真正肉眼可见

- [ ] 先保持布局不变，单独调整动画，便于判断改变来自哪里。
- [ ] 统一原生与 CSS 参数：
  - 周期：1900ms。
  - 0%～6%：起点停留，最多 114ms。
  - 6%～88%：完整斜向扫过，约 1558ms。
  - 88%～100%：终点停留，约 228ms。
  - 曲线：`cubic-bezier(0.4, 0, 0.2, 1)`。
  - 角度：115°。
  - 外层峰值：`rgba(132,255,211,0.76)`。
  - 内层峰值：`rgba(109,255,201,0.50)`。
  - 光带半宽：38dp。
- [ ] 高光必须同时经过外圈边框和内部深色底板；白色鹰标不变色。
- [ ] 光晕只做轻微呼吸，不代替扫光；不能出现“只有边框一闪、斜线看不见”的状态。
- [ ] `EagleSplashTimingTest` 更新为新周期和新停留比例，并增加以下边界断言：
  - 6% 前进度为 0。
  - 47% 左右接近中点。
  - 88% 后进度为 1。
  - 周期跨越后偏移正确回绕。
- [ ] 系统动画倍率为 0 时取消 `ValueAnimator`，显示静态完整加载页；不留空白或无限刷新。

### Task 5：重新设计 UniApp 交接时机

- [ ] `session-gate.onReady`：
  - 读取原生周期偏移。
  - 启动本页 CSS Shimmer。
  - 不调用 `dismissNativeSplash()`。
- [ ] `session-gate.go(url)`：
  - 只设置 `routing` 并执行 `uni.reLaunch({ url })`。
  - 成功或失败均不得在页面还未绘制时盲目关闭覆盖层。
- [ ] 登录页 `onReady`：
  - 等待 `$nextTick`。
  - 再等待一帧约 34ms，确认 `.auth-page` 已绘制。
  - 调用 `dismissNativeSplash('login-ready')`。
- [ ] 主页面不得只在普通 `onReady` 关闭；应在已有 `onAuthenticatedPageReady()` 中：
  - 先执行 `workspace.handleAuthenticated()`。
  - 等待下一帧。
  - 调用 `dismissNativeSplash('home-ready')`。
- [ ] `session-gate` 遇到非终止型网络错误时：
  - 先设置 `unavailable = true`。
  - 等待错误 UI 和重试按钮绘制。
  - 以相同动画相位关闭原生层，显示可操作的错误页。
- [ ] `uni.reLaunch` 失败时进入受控错误状态，不允许原生层永久遮挡；30 秒安全上限继续保留。
- [ ] 桥接接口保持幂等：同一启动过程多次 ready 通知只能触发一次 160ms 淡出。

建议桥接表面保持很小：

```text
getNativeSplashCycleOffsetMillis(): number
dismissNativeSplash(reason: 'login-ready' | 'home-ready' | 'session-error' | 'route-failed'): void
```

### Task 6：增加可诊断性，但不记录敏感数据

- [ ] `EagleSplashController` 使用固定 tag `EagleSplash` 输出以下事件：
  - `ATTACH`：Activity 和启动时间。
  - `ANIMATION_START`：周期与系统动画倍率。
  - `ENSURE_TOP`：只在真正发生层级恢复时记录。
  - `DISMISS_REQUEST`：固定枚举原因。
  - `DISMISS_COMPLETE`：总显示时长。
  - `TIMEOUT_DISMISS`：30 秒兜底。
- [ ] 禁止日志写入 Token、Cookie、账号、邮箱、手机号或完整路由参数。
- [ ] 这些日志让下一次只录一次就能确认是哪个层级在显示，不再靠肉眼猜测。

### Task 7：单独处理 BuildConfig 警告

- [ ] 在 Android `app/build.gradle` 的 `android` 块内启用：

```groovy
buildFeatures {
    buildConfig true
}
```

- [ ] 不修改 `applicationId` 或 `namespace`，两者继续保持 `site.niko000o.aitemperate`。
- [ ] 此改动单独验证，避免把它误认为动画修复本身。

### Task 8：打包资源链路

- [ ] 修改前端源码后，在 HBuilderX 重新执行“生成本地 App 资源”。
- [ ] 将新生成的 `__UNI__F0AE07E/www` 完整覆盖 Android 工程：

```text
C:/Users/damn/Downloads/5.24/Android-SDK/@5.24.82669_20260813/
HBuilder-HelloUniApp/app/src/main/assets/apps/__UNI__F0AE07E/www
```

- [ ] 覆盖后检查 `app-config-service.js`：
  - `alwaysShowBeforeRender` 为 `false`。
  - `session-gate` 为 custom navigation。
  - 登录页和主页包含新的 ready handoff。
- [ ] 再由 Android Studio 生成签名 APK。
- [ ] 禁止直接长期修改压缩后的 `app-service.js`；它只能来自 HBuilderX 导出。

## 5. 验证计划（实施后、经用户确认才执行）

### 机械验证

```powershell
cd C:/Users/damn/Desktop/ai-temperate-main/fornted
node --test pages/launch/session-gate-branding.contract.test.cjs
node --test pages/ai-chat/ai-chat-page-contract.test.cjs
node --test pages/auth/auth-ui-structure.test.cjs
```

预期：全部 PASS，且测试能证明关闭动作位于最终目标页，而非中间会话页。

```powershell
cd C:/Users/damn/Downloads/5.24/Android-SDK/@5.24.82669_20260813/HBuilder-HelloUniApp
./gradlew.bat :app:testDebugUnitTest --tests site.niko000o.aitemperate.launch.EagleSplashTimingTest
```

预期：新周期和停留比例全部 PASS。

### 真机验证：只做一次完整采样

- 冷启动录屏一次，同时捕获 `EagleSplash`、`SplashView`、`Html5Plus-SplashClosed` 和 `UIWidgetMgr` 日志。
- 逐帧确认：
  - 点击后完整文字立即存在，不再先只有鹰标。
  - 1900ms 内能明确看到完整斜向扫光。
  - 不出现顶部“正在启动”。
  - 不闪出 `session-gate` 再跳登录页。
  - 登录页或主页首帧完成后才淡出，淡出为 160ms。
  - 背景、外框和内部底板始终为 `#0B0D0C`。
- 分别验证未登录、已登录、网络异常三条路径；每条路径只在需要时单独执行，不连续反复启动。

## 6. 完成标准

- 从 Android 可绘制的第一帧到最终页面之间，没有白屏、标题栏闪现、Logo 尺寸跳变或只有鹰标的长时间静止页。
- 用户能在一个周期内明确辨认斜向高光从左下到右上扫过。
- 未登录时覆盖层在登录页首帧后关闭；已登录时在认证主页首帧后关闭。
- 可重试错误不会被覆盖层遮挡 30 秒；错误页准备好后即可操作。
- 后台恢复不重新显示启动覆盖层。
- 系统关闭动画时显示静态完整品牌页并正常进入应用。
- `BuildConfig` 异常不再出现。
- 新 APK 中的 `www` 与前端源码一致。

## 7. 明确不做的事情

- 不为了展示动画而人为延迟登录或主页进入。
- 不把 AppsFlyer、开屏广告或投流逻辑混入本次修复。
- 不修改登录、会话、Google/GitHub 登录和业务 API。
- 不替换 DCloud 核心 Activity。
- 不用 GIF 冒充系统 Splash；DCloud 官方只支持静态 PNG。
- 不再让 `session-gate.onReady` 决定最终覆盖层关闭时机。

