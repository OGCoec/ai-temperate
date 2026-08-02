# ChatGptMarkdownRenderer 组件规格

## 概览

- **目标组件：** `fornted/components/user/workspace/user-markdown-message.vue`
- **解析模块：** `fornted/common/aichat/ai-markdown-parser.js`
- **接入文件：** `fornted/components/user/workspace/user-chat-panel.vue`
- **参考证据：** `docs/research/chatgpt-markdown/BEHAVIORS.md`
- **截图：** N/A；用户明确把范围缩小为转换行为，不要求页面视觉复刻。
- **交互模型：** 流式文本驱动，附带代码/表格复制操作。

## 职责边界

组件负责把助手的 `responseText` 渲染为安全、可访问的 Markdown 节点。组件不负责发起请求、处理 SSE、保存消息、管理会话、执行代码或解释模型输出。

建议输入：

```text
text: String
streaming: Boolean
```

建议事件：

```text
copy-success(kind)
copy-failure(kind)
```

## 数据流

```text
累计 responseText
-> tokenize(text, { allowHtml: false })
-> 规范化受控 AST
-> 根据节点类型分派 Vue 模板
-> 输出平台支持的语义组件
```

解析器必须返回数据节点，不得返回需要通过 `v-html` 注入的 HTML 字符串。

## 节点映射

| AST 节点 | H5 语义 | 组件行为 |
| --- | --- | --- |
| heading 1-6 | `h1`-`h6` | 等级决定字号，不允许等级越界 |
| paragraph | `p` | 保留行内子节点 |
| strong | `strong` | 粗体 |
| emphasis | `em` | 斜体 |
| deletion | `del` | 删除线 |
| inlineCode | `code` | 单行背景和等宽字体 |
| link | `a` | URL 协议白名单与安全 rel |
| unorderedList | `ul` | 支持递归嵌套 |
| orderedList | `ol` | 保留起始序号（若解析器提供） |
| listItem | `li` | 可包含段落或子列表 |
| blockquote | `blockquote` | 支持递归嵌套 |
| thematicBreak | `hr` | 分隔线 |
| codeBlock | 专用组件 | 语言标题、复制按钮、代码正文 |
| table | 专用组件 | 表头、表体、列对齐、横向滚动、复制按钮 |
| taskItem | disabled checkbox + 文本 | 永远只读 |
| html | 文本节点 | 禁止解释或执行 |

## 流式状态

1. 每次 `text` 变化后解析当前累计文本，而不是只解析新 delta。
2. 可以按一次渲染帧或 30-50ms 合并高频更新，但终态文本不得丢失。
3. 未闭合围栏允许临时把缓冲区末尾视为代码正文；后续闭合后重新解析。
4. 表格只有在表头、分隔行满足语法时才升级为表格；否则保持段落文本。
5. `streaming` 从 `true` 变为 `false` 时强制执行最终解析。
6. 解析异常时降级为纯文本，并保留诊断事件；禁止让整条消息或消息列表白屏。

## 安全不变量

- 禁止 `v-html`、`innerHTML` 和任何模型输出 HTML 注入。
- URL 解析失败或协议不允许时，链接降级为普通文本。
- 代码语言仅作为枚举/规范化字符串使用，禁止拼入选择器、类名或任意 HTML。
- 复制动作只读取已经规范化的纯文本。
- 渲染器不得访问认证 Token、Cookie、附件本地路径或其他消息。

## 样式方向

本次不是 ChatGPT 整页像素级复刻。组件应沿用当前项目的深色主题变量，并建立独立的 `.markdown-message` 命名空间，避免污染用户消息和其他页面。

- 正文继承当前 15px、1.72 行高。
- 标题通过字号、字重和上下间距建立层级，不额外添加卡片背景。
- 代码块使用独立圆角容器、语言栏、复制按钮和横向滚动。
- 表格外层允许横向滚动；表头、单元格保持可读最小宽度。
- 引用使用左边框与弱化文字色。
- 分隔线使用当前边框色，不使用图片。

## 接入要求

只替换助手消息的这一处纯文本展示：

```text
<text v-if="message.responseText" class="message-text">...</text>
```

替换为 Markdown 组件后，用户消息仍按纯文本渲染。现有附件、错误、warning、saving、stopped 和滚动逻辑保持不变。

## 应准备的测试（本阶段不执行）

- 标题 H1-H6 与普通 `#` 字符边界。
- strong、emphasis、deletion、行内代码及转义。
- 无序、有序和三级嵌套列表。
- 嵌套引用与 `---` 分隔线。
- Java、无语言和未知语言代码块。
- GFM 表格、列对齐、缺少分隔行的降级。
- 任务列表选中/未选中且始终 disabled。
- 原始 HTML、`javascript:` URL 和事件属性不会执行。
- 未闭合强调、代码围栏和表格在连续 delta 下不会抛错。
- 解析异常回退纯文本。
- 终态重新解析与复制内容准确。

按照项目两阶段规范，以上测试只随实现编写；未经用户确认不得执行测试、构建或打包。

## 未决项

- 选择兼容 uni-app H5 与目标原生端的 AST Markdown 解析库。
- 选择代码高亮库及按语言懒加载策略。
- 是否在首个试点仅支持 H5；若要跨端，需要确认各端对动态语义标签和剪贴板 API 的支持。
- 是否在表格复制时输出 TSV、Markdown 原文或两种格式。
