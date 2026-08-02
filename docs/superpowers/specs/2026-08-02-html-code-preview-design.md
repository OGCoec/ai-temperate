# HTML 代码块沙箱预览设计

## 目标

为 AI 回复中的 HTML 代码块增加类似 ChatGPT 的“代码 / 预览”切换按钮。默认显示现有 Shiki 高亮源码；用户主动点击“预览”后，在聊天消息内部显示 HTML、CSS 和 JavaScript 的运行结果。

## 范围

- 仅规范语言为 `html` 的代码块显示预览切换控件。
- H5 提供完整预览；APP-PLUS 保留代码视图并显示平台不支持提示。
- 流式生成期间禁用预览；完成后才允许运行最终代码。
- 复制操作始终复制原始 HTML，不复制预览内容。
- 不增加后端接口、独立预览域名、代码编辑器或运行日志控制台。

## 组件边界

- `user-markdown-code-block.vue` 管理代码/预览模式、按钮状态和现有复制行为。
- 新增 `user-markdown-html-preview.vue`，只负责创建、更新和销毁 H5 沙箱预览。
- HTML 原文继续来自 Markdown AST 的 `codeBlock.code`；预览组件不维护第二份业务文本。

## 交互

- 工具栏右侧显示由“代码”和“预览”组成的圆形切换组，使用本地图标和滑动背景指示当前状态。
- 切换组使用 `role="group"` 和明确标签；每个按钮使用 `aria-label`、`aria-pressed` 和真实 `disabled` 状态。
- 键盘 Tab 可以聚焦按钮，焦点样式清晰；减少动态效果偏好下关闭滑动动画。
- 代码更新、语言改变或重新进入流式状态时自动返回代码模式并销毁旧预览。

## 沙箱与安全边界

- H5 使用独立 iframe，并且 sandbox 只允许 `allow-scripts`。
- 禁止 `allow-same-origin`、表单、弹窗、下载、父页面导航和访问本站存储。
- 预览文档通过临时 Blob URL 加载，并注入限制性 CSP：默认禁止资源，允许内联脚本和样式，禁止连接、表单和嵌套页面。
- 不使用 `v-html`、主页面 `innerHTML`、`eval` 或 `Function`。
- 每次代码变化撤销旧 Blob URL；组件卸载时清理临时资源。
- 预览脚本发生异常时只影响 iframe，不影响聊天页面和 SSE。

## 测试契约

- HTML 语言显示切换控件，Java 等其他语言不显示。
- 流式状态禁用预览，完成后可切换。
- 按钮具备可访问名称和 pressed/disabled 状态。
- iframe 仅含 `allow-scripts`，不得出现 `allow-same-origin`。
- CSP 禁止网络连接、表单和嵌套页面。
- 源码不得出现 `v-html`、`innerHTML`、`eval` 或 `Function`。
- 切回代码、代码更新和卸载时清理 Blob URL。

## 平台边界

本次完整交付对象是 H5。APP-PLUS 不直接执行模型生成的 JavaScript；若未来需要 APP 预览，应单独建设隔离预览域名并重新评审安全边界。
