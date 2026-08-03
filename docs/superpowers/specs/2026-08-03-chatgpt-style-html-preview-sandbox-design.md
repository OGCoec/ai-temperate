# ChatGPT 式 HTML 代码预览沙箱设计

日期：2026-08-03

状态：已批准进入实施
替代设计：`docs/superpowers/specs/2026-08-02-html-code-preview-design.md`

## 1. 背景与结论

现有聊天代码预览把 HTML 写入 Blob iframe，并在 `ai-html-preview-document.js` 中注入严格 CSP。该 CSP 明确禁止远程脚本、网络请求与 Worker，因此 Three.js 的远程 ES Module 会在执行前被浏览器拦截。代码本身可以在普通本地 HTTP 页面运行，故问题不在 Three.js 水纹代码，也不在浏览器 WebGL 渲染能力，而在临时预览组件的运行边界。

本次采用与 ChatGPT 可观察行为一致、但完全独立实现的架构：主站只负责代码/预览切换和工具栏；不可信 HTML 在独立站点的双层 iframe 沙箱中运行。外部依赖由沙箱页面加载，主站的登录态、DOM、存储和 API 不向预览代码开放。

## 2. 目标

- HTML、CSS、普通 JavaScript、ES Module 和 Three.js CDN 依赖可以在预览中执行。
- 工具栏提供代码、预览、复制、全屏、显示代码和下载操作，视觉与交互密度接近已观察到的 ChatGPT 预览。
- 预览代码不能访问主站 DOM、主站存储、认证信息或主站同源能力。
- 父页面与沙箱只通过版本化、可验证的 `postMessage` 协议通信。
- 流式回复尚未结束时不启动预览，沿用现有防止执行半截代码的行为。
- 运行错误、加载超时和沙箱不可用都显示明确状态，不再静默失败。
- H5 端完整启用；非 H5 端继续显示代码，不创建 WebView 或隐藏浏览器。

## 3. 非目标

- 不复制 ChatGPT 私有源码、资源文件、商标或不可见实现。
- 不把任意 HTML 迁移进 Java Controller、Java Text Block 或后端模板。
- 不在本阶段创建 Cloudflare 项目、修改 DNS 或执行部署。
- 不为用户代码提供摄像头、麦克风、地理位置、剪贴板、顶层导航或主站 API 凭证。
- 不保证所有第三方网站都允许 iframe、CORS 或模块加载；这仍由第三方响应头决定。

## 4. 运行架构

```text
ai-temperate 主站
  user-markdown-code-block.vue
    -> user-markdown-html-preview.vue
      -> 外层 iframe: 独立预览源
        -> 沙箱壳页面
          -> 内层 iframe: 用户 HTML 运行时
```

### 4.1 独立预览源

- 本地开发默认：`https://localhost:4174`
- 生产计划地址：`https://ai-temperate-html-preview.pages.dev`
- 主站生产地址与预览地址属于不同的可注册站点，避免把 `preview.niko000o.site` 这类同站子域当作完整安全边界。
- 生产构建通过 `AI_HTML_PREVIEW_ORIGIN` 显式覆盖预览源；配置必须是无路径、无查询参数、无 Fragment 的 HTTPS Origin。
- 预览源不设置 Cookie，不接收主站 Token，也不代理主站 API。

### 4.2 双层 iframe

主站中的外层 iframe 固定加载沙箱壳页面。沙箱壳页面不直接执行用户代码，而是为每次渲染重建内层 iframe：

- 外层 iframe 隔离主站与预览源。
- 内层 iframe 隔离沙箱控制逻辑与用户页面，用户代码即使破坏内层 DOM，也不会破坏消息接收壳。
- 每次从代码切到预览或代码发生变化时创建新运行实例，防止上一实例的定时器、事件监听和全局变量泄漏到下一次渲染。
- 离开预览、组件卸载或进入新的渲染实例时销毁旧 iframe。

## 5. iframe 权限

### 5.1 主站外层 iframe

外层 iframe 使用：

```text
sandbox="allow-scripts allow-same-origin allow-forms allow-popups allow-popups-to-escape-sandbox"
```

不授予 `allow-top-navigation`、`allow-downloads`、`allow-modals`、`allow-pointer-lock`、摄像头、麦克风、地理位置或剪贴板权限。下载由主站工具栏根据原始代码显式完成，而不是由用户 HTML 自行触发。

### 5.2 沙箱内层 iframe

内层 iframe使用与外层相同的 sandbox token，以支持常见表单、模块和演示页面。`allow-scripts + allow-same-origin` 允许用户页面影响同一预览源中的壳页面，因此壳页面必须可丢弃、无认证、无 Secret、无业务 API 能力。安全边界是“预览站点与主站分站”，而不是依赖内层 iframe 保护壳页面。

## 6. CSP 与网络策略

### 6.1 主站 CSP

主站 `frame-src` 仅增加以下精确源：

```text
https://localhost:4174
https://127.0.0.1:4174
https://ai-temperate-html-preview.pages.dev
```

主站自身的 `script-src`、`connect-src` 与其他限制不为预览放宽。第三方 Three.js 请求发生在预览源的内层文档中，不经过主站 CSP。

### 6.2 沙箱壳 CSP

沙箱壳自身只引用同源静态 JS/CSS，也不编写任何主动联网逻辑。由于 `srcdoc/about:blank` 会继承外层响应 CSP，预览站点响应头必须允许内层运行时所需的 HTTPS 脚本、连接、图片、字体、Worker 和子框架；不能再用 `connect-src 'none'` 或仅 `script-src 'self'`，否则会重复旧实现对 Three.js 的阻断。该宽松能力仅存在于无登录态、无 Secret、与主站分站的独立预览站点。

### 6.3 用户运行时 CSP

内层运行时允许用户代码访问 `https:`、`wss:`、`data:` 和 `blob:` 资源，以支持 CDN 模块、图片、字体、Worker、WebSocket 与演示 API。仍禁止插件对象和顶层导航。该宽松策略只存在于无登录态、可丢弃的独立预览源。

## 7. 消息协议

协议常量：

```text
source: ait-html-preview
version: 1
```

每个预览组件创建 128-bit 随机 `channelId`。父页面发送时必须指定精确 `targetOrigin`；双方接收时必须同时验证：

- `event.source` 是预期窗口；
- `event.origin` 在允许列表中；
- `source`、`version`、`channelId`、`type` 完全匹配；
- 消息字段类型与长度合法。

### 7.1 沙箱到主站

```js
{ source, version, channelId, type: 'ready' }
{ source, version, channelId, type: 'rendered', renderId, height, backgroundColor }
{ source, version, channelId, type: 'runtime-error', renderId, message, line, column }
{ source, version, channelId, type: 'navigation', renderId, url }
```

### 7.2 主站到沙箱

```js
{
  source,
  version,
  channelId,
  type: 'render',
  renderId,
  html,
  theme
}
{ source, version, channelId, type: 'dispose', renderId }
```

### 7.3 边界

- HTML 最大 1 MiB，超过时不发送并显示“预览内容过大”。
- 单条错误消息最大 4 KiB，路径和堆栈只保留安全摘要。
- 每个渲染实例最多上报 20 条错误，后续错误合并计数。
- 8 秒内未收到 `ready` 显示“预览服务连接超时”。
- 15 秒内未收到 `rendered` 显示“页面仍在加载”，但不强制终止可能较慢的 Three.js 初始化。
- 主站日志禁止记录完整 HTML、URL Query、Fragment 或用户代码产生的原始对象。

## 8. 用户代码封装

沙箱收到 HTML 后：

1. 解析为完整 HTML 文档；片段代码自动补齐 `doctype/html/head/body`。
2. 在 `head` 最前面注入运行时 CSP、基础 viewport 和错误桥接脚本。
3. 保留用户原始脚本顺序、`type="module"`、样式和相对 DOM 结构。
4. 通过新的内层 iframe 一次性载入。
5. 捕获 `error` 与 `unhandledrejection`，发送经过截断和字符串化的错误摘要。
6. 使用 `ResizeObserver` 上报内容高度；全屏模式由主站控制布局，不修改用户 HTML。

用户代码中的相对 URL 以预览源为基准。需要稳定资源路径的代码应使用绝对 HTTPS URL。

## 9. 主站组件行为

### 9.1 工具栏

- 左侧为 74×36 的代码/预览组合按钮，两个点击区均为 36×36，中间 2px 间隔。
- 36×36 的活动指示背景通过 `transform: translateX(38px)` 在两个状态间移动。
- 指示背景使用 200ms `cubic-bezier(0.4, 0, 0.2, 1)`；颜色使用 150ms transition。
- 按钮按下采用 120ms、`scale(0.97)` 的轻反馈。
- 预览按钮在流式输出、非 HTML 或沙箱配置无效时设置 `aria-disabled="true"`。
- 使用本地 SVG 图标替换 `</>` 与 `▶` 文本，图标提供无障碍名称。
- 右侧提供复制与全屏按钮；只在预览可用时显示全屏。
- 所有纯 hover 样式只在 `(hover: hover) and (pointer: fine)` 下启用。
- `prefers-reduced-motion: reduce` 时移除滑块位移过渡，只保留即时状态变化。

### 9.2 预览面板状态

状态机：

```text
idle -> connecting -> rendering -> ready
                     -> warning
                     -> error
```

- `connecting`：显示轻量进度和“正在连接安全预览”。
- `rendering`：iframe 已连接，等待运行时首帧。
- `ready`：展示页面，不覆盖交互。
- `warning`：页面仍可交互，同时在顶部显示非阻断错误摘要。
- `error`：沙箱不可用、协议错误或内容过大，显示原因与返回代码按钮。

### 9.3 全屏

全屏采用主站内部的 fixed overlay，不调用浏览器 Fullscreen API，因此不会弹出权限提示。顶部栏包含：

- 关闭；
- 显示代码/显示预览；
- 复制；
- 下载 HTML。

打开和关闭 overlay 使用 180ms opacity/transform transition，焦点进入时落在关闭按钮，关闭后回到原全屏按钮。Escape 可关闭；页面滚动在全屏期间锁定并在退出时恢复。

### 9.4 下载

下载内容始终是聊天中原始 HTML，不包含沙箱注入的桥接脚本或 CSP。文件名使用无用户隐私的时间戳，例如 `html-preview-20260803-194615.html`。

## 10. 文件边界

### 10.1 主前端

- 修改 `fornted/components/user/workspace/user-markdown-code-block.vue`
- 修改 `fornted/components/user/workspace/user-markdown-html-preview.vue`
- 修改 `fornted/common/aichat/ai-html-preview-document.js`
- 新增 `fornted/common/aichat/ai-html-preview-config.js`
- 新增 `fornted/common/aichat/ai-html-preview-protocol.js`
- 新增对应 Node contract tests，但第一阶段不执行
- 新增本地 SVG 图标资源
- 修改 `fornted/vite.config.js` 注入预览源常量
- 修改 `fornted/index.html` 的 `frame-src` 精确白名单，保留用户已有 CSP 改动

### 10.2 独立沙箱

新增 `cloudflare/html-preview-sandbox/`：

- 零第三方运行依赖的纯静态工程与 Node 内置 HTTPS 本地服务脚本；
- 沙箱壳页面、消息协议、运行时文档构造器和样式；
- Cloudflare Pages `_headers` 与 `_redirects`；
- 协议、HTML 注入和安全头 contract tests，但第一阶段不执行；
- 部署说明，不包含实际凭据或项目 Token。

后端 Java 模块不做任何修改。

## 11. 错误处理与降级

- 预览源未配置或配置非法：代码视图保持可用，预览按钮禁用并显示配置原因。
- 外层 iframe 加载失败：显示重试与返回代码，不创建 Blob 回退，避免悄悄退回旧的不安全/不兼容路径。
- 第三方 CDN 被 CORS 或自身 CSP 拒绝：展示运行时错误摘要；不自动改写用户 URL。
- WebGL 不可用：用户页面自己的兼容提示正常显示，沙箱不伪造成功。
- 消息来源、版本或 channel 不匹配：静默丢弃并递增本地诊断计数，不把可疑内容写入业务日志。
- 非 H5 平台：只提供代码与复制，不尝试创建隐藏 WebView。

## 12. 测试设计

代码中准备以下测试，但依据项目两阶段规范，本阶段不执行：

- 预览源解析：合法 HTTPS、开发地址、路径/查询/凭据/非 HTTPS 拒绝。
- 消息协议：正确 channel、错误 origin、错误 source、未知版本、超大 HTML。
- 文档构造：完整文档、片段、`type="module"`、包含 `</script>` 文本、CSP 注入顺序。
- 组件契约：iframe sandbox token、精确 `targetOrigin`、卸载 dispose、流式输出禁用预览。
- UI 契约：组合按钮 ARIA、全屏焦点恢复、Escape、下载原始源码、减少动态效果。
- 沙箱头：无 Cookie、外层 CSP、`Referrer-Policy: no-referrer`、`X-Content-Type-Options: nosniff`。
- 第二阶段人工 Chrome 验证：原 Three.js 水纹、点击交互、动态 import、全屏、错误提示和网络失败。

## 13. 部署顺序

1. 先部署独立 Pages 沙箱并取得最终 HTTPS Origin。
2. 在隔离环境验证响应头与外部 Three.js 模块。
3. 用最终 Origin 构建主前端并同步更新主站 `frame-src`。
4. 先在开发域启用，再在生产域启用。
5. 保留代码视图作为无条件降级路径。

部署、DNS、外部浏览器验证和任何网络连接测试都属于第二阶段，必须由用户单独确认范围后执行。

## 14. 回滚

- 主站通过配置把 HTML 预览入口禁用，代码展示与复制继续可用。
- 不回退到旧 Blob 预览；旧实现造成的 CDN 阻断正是本次修复原因。
- 沙箱 Pages 可独立下线，不影响聊天文本和代码渲染。
- 回滚不需要数据库、Redis、RabbitMQ 或 Java 服务迁移。

## 15. 验收标准

- 原 Three.js 水纹代码在独立沙箱中能加载外部模块并持续动画。
- 点击/拖动事件可在预览中触发，切回代码再切回预览会得到全新运行实例。
- 主站不能从用户 iframe 读取 DOM，用户 iframe不能读取主站 DOM、localStorage 或认证 Cookie。
- 无效消息、错误 origin 和旧 channel 不改变当前预览。
- 预览加载失败时给出可理解的错误，不出现空白绿色/黑色页面却无解释。
- 工具栏、全屏、复制、下载、键盘和减少动态效果行为符合本设计。
- 第一阶段只声明代码已交付；只有第二阶段产生新证据后才能声明构建或功能验证通过。
