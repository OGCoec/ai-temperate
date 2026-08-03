# ChatGPT HTML 代码预览组件调研规格

## 调研边界

- 调研目标：`https://chatgpt.com/` 中 HTML 代码块的“代码 / 预览 / 全屏”交互。
- 调研日期：2026-08-03。
- 只记录可见 DOM、计算样式、iframe 属性与实际交互结果，不复制 ChatGPT 内部源码、账号数据或后端协议。
- 交互模型：点击切换代码与预览；点击全屏进入独立运行视图。

## 参考截图

- 代码状态：`docs/design-references/chatgpt-html-code-active.png`
- 内联 JavaScript 预览状态：`docs/design-references/chatgpt-html-preview-active.png`
- 切换按钮悬停状态：`docs/design-references/chatgpt-html-preview-toggle-hover.png`
- Three.js 成功加载的全屏状态：`docs/design-references/chatgpt-html-preview-fullscreen-threejs.png`

## 实测结果

### 内联 JavaScript

- 测试页面使用内联 CSS 和内联脚本实现点击计数。
- 切换到预览后，运行区正常显示计数 `0`。
- 在运行 iframe 中点击按钮后，计数从 `0` 变为 `1`，证明事件绑定和脚本持续状态正常。

### 外部 ES Module

- 测试页面使用 `type="module"` 动态导入：
  `https://cdn.jsdelivr.net/npm/three@0.181.1/build/three.module.js`。
- 运行区显示 `加载成功！THREE.REVISION = 181`。
- 没有捕获到 jsDelivr 相关的控制台警告或错误。
- 结论：ChatGPT 当前的远程预览沙箱允许受测的 Three.js 外部模块请求。

## 按钮组件结构

### 工具栏

- 工具栏高度：`48px`。
- 左右内边距：桌面端为左侧 `20px`、右侧 `6px`。
- 背景：当次深色主题中计算值为 `rgb(0, 0, 0)`。
- 右侧操作区使用水平 Flex，间距 `2px`。
- 预览状态包含代码按钮、预览按钮和全屏按钮。

### 代码 / 预览切换组

- 可访问性容器：`role="group" aria-label="代码块视图切换"`。
- 容器尺寸：`74px × 36px`。
- 两个按钮均为 `36px × 36px`，圆形点击区，内部图标 `20px × 20px`。
- 按钮通过 `aria-label="代码"` / `aria-label="预览"` 提供可访问名称。
- 状态通过 `aria-pressed="true|false"` 表达，预览按钮还使用 `aria-disabled`。
- 实际按钮背景透明；选中背景由组内绝对定位的独立 `36px` 圆形指示块绘制。
- 指示块的深色主题计算色为 `rgba(255, 255, 255, 0.05)`。
- 指示块从代码位移到预览位，水平距离是“一个按钮宽度 + 2px”。
- 动画时长 `200ms`，缓动为 `cubic-bezier(0.4, 0, 0.2, 1)`。
- 按钮颜色过渡时长 `150ms`，同样使用 `cubic-bezier(0.4, 0, 0.2, 1)`。
- 减少动画偏好通过 `motion-safe` 类型的条件样式处理。
- 悬停或键盘焦点可临时将指示块移到对应按钮，但真实模式仍以 `aria-pressed` 为准。

### 全屏状态

- 全屏按钮为 `36px × 36px`的圆形 Ghost Button。
- 进入全屏后，原聊天容器被独立运行视图覆盖。
- 顶部工具栏提供关闭、显示代码、复制和下载代码文件操作。
- 退出全屏后回到原对话，原预览内容仍可继续显示。

## 预览沙箱架构

### 第一层：聊天页宿主 iframe

- `src` 指向 `https://web-sandbox.oaiusercontent.com?`。
- `title="预览"`。
- 当次实测的 `sandbox` 为：
  `allow-scripts allow-same-origin allow-popups allow-popups-to-escape-sandbox allow-forms`。
- `allow="midi"`。
- 运行区占满代码卡片宽度，桌面视口内的实测尺寸约为 `766px × 430px`。

### 第二层：用户 HTML 运行 iframe

- 第一层沙箱内还有一个运行 iframe。
- 运行 iframe 的 DOM `src` 为 `about:blank`，文档地址继承为 `https://web-sandbox.oaiusercontent.com/?`。
- 当次实测的内层 `sandbox` 同样允许脚本、同源、表单和受限弹窗。
- 用户文档内未发现注入的 CSP `<meta>`；这不能排除 HTTP 响应头或宿主层的其他安全限制。
- 用户脚本与 ChatGPT 主页不同源，即使运行 iframe 具有 `allow-same-origin`，也不会获得 `chatgpt.com` 的页面存储和 DOM 权限。

## 与当前项目的根本差异

### 当前项目

- `fornted/components/user/workspace/user-markdown-html-preview.vue` 直接生成 Blob URL。
- iframe 仅使用 `sandbox="allow-scripts"`，安全边界较强。
- `fornted/common/aichat/ai-html-preview-document.js` 注入以下限制：
  - `script-src 'unsafe-inline'`
  - `connect-src 'none'`
  - `frame-src 'none'`
  - `worker-src 'none'`
  - 图片、媒体和字体仅允许 `data:` / `blob:`
- 因此能执行内联按钮脚本，但必然拒绝 jsDelivr Three.js、外部图片、字体和网络 API。

### ChatGPT 参考实现

- 聊天页只负责代码卡片、模式切换和全屏容器。
- 执行环境放在独立的远程沙箱源，再通过内层 iframe 运行用户文档。
- 外部 Three.js 模块实测可用，兼容性明显高于当前 Blob + 全禁网 CSP 方案。
- 它的安全前提是“独立源隔离”，不是在聊天页同源放开任意脚本。

## 可选修复路线

### 方案 A：独立预览源（推荐）

- 建设不携带主站 Cookie 和认证状态的独立预览域名。
- 主页与预览宿主使用随机会话标识和严格的 `postMessage` 消息模型传递代码与运行状态。
- 预览宿主再创建内层运行 iframe，将用户 HTML 与宿主控制逻辑隔离。
- 为外部模块、图片、字体、Worker 和 Fetch 建模独立策略，并限制代码大小、运行时间、重载次数和错误数量。
- 优点：最接近 ChatGPT 的兼容性与隔离方式。
- 代价：需要独立前端宿主、消息协议、部署和安全评审。

### 方案 B：Blob 预览 + CDN 白名单

- 保留现有 `sandbox="allow-scripts"` 和 Blob URL。
- 只在 `script-src`、`img-src`、`font-src` 等指令中加入经审核的资源域名。
- 将 jsDelivr Three.js 作为第一个最小验证项，不同时开放任意 `connect-src https:`。
- 优点：改动小，可快速修复当前 Three.js 案例。
- 代价：兼容性受白名单限制，需承担第三方 CDN 供应链风险，不等价于 ChatGPT 的远程沙箱。

### 方案 C：内置依赖模板

- 不允许任意外网脚本，由前端预置经过审核的 Three.js 等常用库。
- 对生成代码的导入进行显式映射或重写，只解析已组合的本地资源。
- 优点：网络与供应链风险最可控。
- 代价：维护成本高，不能通用执行模型生成的任意 HTML。

## 建议的后续验证

- 只有用户确认进入项目第二阶段后才执行。
- 使用当前 Three.js 水纹 HTML 作为第一个外部模块回归样例。
- 增加内联 JavaScript、外部 ES Module、WebGL、外部图片、Worker、Fetch 和运行时错误的分类样例。
- 检查沙箱不能访问主页 DOM、Cookie、LocalStorage、SSE 会话和认证 Token。
- 检查弹窗、表单、顶层导航、下载和重放攻击边界。
- 测试前需先明确命令、测试域名、是否联网、会产生的日志与文件、以及是否写入外部状态。
