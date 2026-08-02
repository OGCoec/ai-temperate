# AI Markdown 输出到前端界面的转换规范

## 1. 文档目的

本文说明 AI 返回的 Markdown 文本怎样转换成安全、可访问、支持流式更新的前端界面。目标是复现 ChatGPT 网页版已经观察到的 Markdown 展示行为，而不是复制 ChatGPT 私有源码或整页视觉样式。

本文所说的“转换为前端语言”不是把 Markdown 翻译成另一种编程语言，而是完成以下转换：

```text
Markdown 字符串
-> Markdown Token
-> AST 语法树
-> Vue/React 受控组件
-> HTML 语义 DOM
-> CSS 样式与交互
```

## 2. 证据等级

本文使用三个等级区分规则来源：

| 等级 | 含义 | 使用原则 |
| --- | --- | --- |
| 已实测 | 已在 ChatGPT 网页版语法矩阵中观察到对应语义节点 | 可以作为迁移基线 |
| 标准规则 | CommonMark/GFM 中的常见行为，但本次未逐项实测 | 实现前应补充测试 |
| 项目规则 | 为保证流式稳定性和安全性而在本项目明确采用的行为 | 不应描述成 ChatGPT 官方内部实现 |

## 3. 输入不是 HTML

模型输出首先是一段文本。即使文本中包含 `#`、`*`、反引号、管道符或 `<span>`，它仍然只是字符串。

示例流式事件的概念结构：

```json
{
  "type": "response.output_text.delta",
  "delta": "## Java 示例\n"
}
```

前端只提取 `delta` 文本并追加到当前消息：

```javascript
responseText = responseText + event.delta
```

禁止把事件对象整体作为 Markdown，也禁止直接把 `delta` 交给 `innerHTML`。

## 4. 完整转换管线

```text
SSE/WebSocket 文本增量
-> 累计消息缓冲区 responseText
-> 块级语法分析
-> 行内语法分析
-> 安全 AST 规范化
-> 节点组件分派
-> DOM 提交
-> 代码高亮、复制按钮等增强行为
```

### 4.1 块级解析

块级解析负责识别跨行结构：

- 标题
- 段落
- 无序列表和有序列表
- 引用
- 分隔线
- 围栏代码块
- 表格
- 任务列表

### 4.2 行内解析

块级节点内部再解析：

- 粗体
- 斜体
- 删除线
- 行内代码
- 链接
- 转义字符

这两个阶段不能简单颠倒。例如代码围栏内部的 `**text**` 必须保持代码文本，不能先被转换成粗体。

## 5. 建议的 AST 数据结构

解析器应返回数据节点，而不是返回一段需要 `v-html` 注入的 HTML 字符串。

```javascript
{
  type: "document",
  children: [
    {
      type: "heading",
      depth: 2,
      children: [{ type: "text", value: "Java 示例" }]
    },
    {
      type: "codeBlock",
      language: "java",
      value: "public class Main {}"
    }
  ]
}
```

允许的节点类型必须是固定白名单。未知节点应降级为纯文本，而不是动态创建任意标签。

## 6. 块级 Markdown 到 DOM 的详细规则

### 6.1 ATX 标题

输入：

```markdown
# 一级标题
## 二级标题
### 三级标题
```

转换：

```html
<h1>一级标题</h1>
<h2>二级标题</h2>
<h3>三级标题</h3>
```

ChatGPT 网页版已实测 H1、H2、H3。项目实现应把标题深度限制在 1-6；超出范围时降级为段落。

### 6.2 段落

连续普通文本形成段落：

```markdown
这是普通段落。
```

```html
<p>这是普通段落。</p>
```

空行用于结束当前段落并开始新的块级节点。

### 6.3 无序列表

输入：

```markdown
- Java
- Python
  - FastAPI
```

概念 DOM：

```html
<ul>
  <li>Java</li>
  <li>
    Python
    <ul>
      <li>FastAPI</li>
    </ul>
  </li>
</ul>
```

行首的 `-`、`*` 或 `+` 只有在满足列表上下文并跟随空白时才是列表标记。普通单词中的横杠不能转成列表。

### 6.4 有序列表

输入：

```markdown
1. Java
2. Python
```

```html
<ol>
  <li>Java</li>
  <li>Python</li>
</ol>
```

只有“数字 + 点号 + 空白”满足列表规则。版本号 `1.2.3` 保持普通文本。

### 6.5 引用

输入：

```markdown
> 一级引用
> > 二级引用
```

转换：

```html
<blockquote>
  <p>一级引用</p>
  <blockquote>
    <p>二级引用</p>
  </blockquote>
</blockquote>
```

嵌套引用已经在 ChatGPT 网页版实测。

### 6.6 分隔线

输入：

```markdown
---
```

转换：

```html
<hr>
```

独立的 `---` 已实测。`***` 和 `___` 属于常见 Markdown 规则，但本次没有逐项实测。

### 6.7 围栏代码块

输入：

````markdown
```java
public class Main {
    public static void main(String[] args) {}
}
```
````

解析结果：

```javascript
{
  type: "codeBlock",
  language: "java",
  value: "public class Main {\n    public static void main(String[] args) {}\n}"
}
```

前端组件的概念结构：

```html
<section class="markdown-code-block">
  <header>
    <span>Java</span>
    <button type="button">复制</button>
  </header>
  <pre><code>public class Main { ... }</code></pre>
</section>
```

ChatGPT 网页版已经观察到语言标题 `Java`、复制按钮和独立代码区域。具体 wrapper 标签和 class 名不属于已验证事实。

代码块规则：

1. 围栏后的第一个信息字符串作为语言标识。
2. 语言标识必须规范化，不能直接拼接成任意 HTML。
3. 未知语言仍显示纯文本代码块。
4. 复制操作只复制代码正文。
5. 代码中的 Markdown 标记不再进行行内解析。
6. 代码必须经过 HTML 转义，绝不能执行。

### 6.8 GFM 表格

输入：

```markdown
| 左列 | 中列 | 右列 |
| :--- | :---: | ---: |
| A | B | C |
```

对齐元数据：

| 分隔写法 | 对齐 |
| --- | --- |
| `:---` | 左对齐 |
| `:---:` | 居中 |
| `---:` | 右对齐 |
| `---` | 默认对齐 |

概念 DOM：

```html
<div class="markdown-table-scroll">
  <table>
    <thead>
      <tr><th>左列</th><th>中列</th><th>右列</th></tr>
    </thead>
    <tbody>
      <tr><td>A</td><td>B</td><td>C</td></tr>
    </tbody>
  </table>
  <button type="button">复制表格</button>
</div>
```

ChatGPT 网页版已实测语义 table、表头、单元格和“复制表格”按钮。精确列对齐 CSS 尚未抓取。

缺少表头分隔行时不得强制转换成表格，应保持普通段落。

### 6.9 任务列表

输入：

```markdown
- [x] 已完成
- [ ] 未完成
```

转换概念：

```html
<ul>
  <li><input type="checkbox" checked disabled>已完成</li>
  <li><input type="checkbox" disabled>未完成</li>
</ul>
```

ChatGPT 网页版实测复选框为 disabled。模型回复不能通过任务框修改业务状态。

## 7. 行内 Markdown 到 DOM 的详细规则

| Markdown | AST | DOM | 实测状态 |
| --- | --- | --- | --- |
| `**粗体**` | strong | `<strong>` | 已实测 |
| `*斜体*` | emphasis | `<em>` | 已实测 |
| `~~删除线~~` | deletion | `<del>` | 已实测 |
| `` `code()` `` | inlineCode | `<code>` | 已实测 |
| `[OpenAI](https://openai.com)` | link | `<a>` | 已实测 |
| `\*字面星号\*` | text | 普通文本 | 已实测 |
| `..` / `...` | text | 普通文本 | 已实测 |

### 7.1 星号的上下文

```text
*text*     -> emphasis
**text**   -> strong
* text     -> 可能是无序列表项
***        -> 独立成行时通常可能是分隔线
2 * 3      -> 普通文本中的乘号字符
```

因此不能使用全局正则把所有 `*` 替换成同一种标签。

### 7.2 链接安全

允许的 URL 协议必须由项目白名单决定。最低安全规则：

```text
https:       允许
http:        按产品策略允许或拒绝
mailto:      按产品策略允许或拒绝
javascript:  拒绝
data:        默认拒绝
file:        拒绝
```

危险链接应降级为普通文本，而不是保留可点击行为。

## 8. 原始 HTML 的处理

测试输入：

```markdown
<span data-md-test="raw-html">原始 HTML 测试</span>
```

ChatGPT 网页版实测显示为字面文本，没有创建真实 span。项目必须采用同样的安全边界：

```text
Markdown 中的 HTML
-> text 节点
-> HTML 转义
-> 不执行
```

禁止：

```vue
<view v-html="modelOutput"></view>
```

允许的方向：

```vue
<text>{{ textNode.value }}</text>
```

## 9. 流式 Markdown 转换规则

### 9.1 为什么不能只解析新 delta

假设服务端依次发送：

```text
第一段：**Ja
第二段：va**
```

第一段单独看是不完整强调；拼接后才是 `**Java**`。因此必须解析累计文本：

```javascript
buffer += delta
ast = parseMarkdown(buffer)
render(ast)
```

### 9.2 代码围栏跨 delta

可能收到：

```text
delta 1: ```ja
delta 2: va\npublic class
delta 3:  Main {}\n```
```

项目规则：

1. 每次解析完整累计缓冲区。
2. 未闭合围栏临时延伸到当前缓冲区末尾。
3. 新 delta 到达后重新解析。
4. 收到 completed 终态后强制最终解析。
5. 中间解析失败时显示纯文本，不得白屏。

这是一条项目稳定性规则。本次由于 ChatGPT 回答生成过快，没有捕获到官网未闭合围栏的精确中间 DOM。

### 9.3 表格跨 delta

只有收到完整表头和分隔行后，当前段落才能升级为表格。升级前可以临时显示普通文本；升级后重新生成 table 节点。

### 9.4 更新节流

高频 delta 可以在一个渲染帧或 30-50ms 窗口内合并：

```text
多个 delta
-> 合并到 buffer
-> 一次 parse
-> 一次 DOM 更新
```

终态事件不能被节流丢失，必须立即完成最终解析。

## 10. Vue 组件分派示意

不使用动态 HTML 字符串，而是按白名单节点选择模板：

```vue
<template>
  <view class="markdown-message">
    <markdown-node
      v-for="node in ast.children"
      :key="node.key"
      :node="node"
    />
  </view>
</template>
```

节点组件的逻辑示意：

```javascript
switch (node.type) {
  case "paragraph":
    return ParagraphNode
  case "heading":
    return HeadingNode
  case "codeBlock":
    return CodeBlockNode
  case "table":
    return TableNode
  default:
    return PlainTextNode
}
```

不能根据模型输入动态导入组件，也不能把模型给出的语言名当作组件名。

## 11. 端到端转换示例

AI 输出：

````markdown
## Java 示例

下面是 **Main** 类：

```java
public class Main {}
```

- 可以编译
- 可以运行
````

AST：

```javascript
{
  type: "document",
  children: [
    { type: "heading", depth: 2, children: [{ type: "text", value: "Java 示例" }] },
    {
      type: "paragraph",
      children: [
        { type: "text", value: "下面是 " },
        { type: "strong", children: [{ type: "text", value: "Main" }] },
        { type: "text", value: " 类：" }
      ]
    },
    { type: "codeBlock", language: "java", value: "public class Main {}" },
    {
      type: "unorderedList",
      children: [
        { type: "listItem", value: "可以编译" },
        { type: "listItem", value: "可以运行" }
      ]
    }
  ]
}
```

概念 DOM：

```html
<article class="markdown-message">
  <h2>Java 示例</h2>
  <p>下面是 <strong>Main</strong> 类：</p>
  <section class="markdown-code-block">
    <header><span>Java</span><button>复制</button></header>
    <pre><code>public class Main {}</code></pre>
  </section>
  <ul>
    <li>可以编译</li>
    <li>可以运行</li>
  </ul>
</article>
```

## 12. 错误与降级策略

| 失败情况 | 必须行为 |
| --- | --- |
| Markdown 解析器抛错 | 显示原始纯文本，记录受控诊断 |
| 未知 AST 节点 | 递归提取纯文本，不动态创建标签 |
| 未知代码语言 | 纯文本代码块，无高亮 |
| 危险链接协议 | 显示链接文字但不可点击 |
| 复制失败 | 显示非阻塞提示，不改变正文 |
| 流式消息中断 | 保留当前可见文本并进行一次安全最终解析 |
| 超长消息 | 分片调度解析或限制高亮，不能阻塞整个页面 |

## 13. 性能原则

- 解析范围限制在发生变化的助手消息，不重新解析全部历史消息。
- 已完成历史消息缓存 AST。
- 流式消息可合并多个 delta 后解析。
- 大代码块的高亮可以延迟到代码围栏闭合或消息完成。
- 表格和代码块使用局部横向滚动，不能撑破消息容器。
- 复制按钮不应触发重新解析。

## 14. 项目接入边界

当前项目在 `fornted/components/user/workspace/user-chat-panel.vue` 中将助手回复直接作为纯文本输出。试点迁移只替换助手正文展示层：

```text
message.responseText
-> user-markdown-message
-> ai-markdown-parser
-> 受控 Vue 节点
```

以下部分保持不变：

- SSE 连接和事件格式
- `responseText` 的累计过程
- 消息保存
- 停止生成
- 附件展示
- warning 和 error
- 用户消息的纯文本展示

## 15. 验收测试矩阵

实施时至少准备以下测试，但按照项目两阶段规范，未经用户确认不得执行：

1. H1-H6、普通井号和转义井号。
2. 粗体、斜体、粗斜体、删除线、行内代码。
3. 无序、有序、嵌套列表和版本号边界。
4. 嵌套引用与分隔线。
5. Java、无语言、未知语言和未闭合代码围栏。
6. GFM 表格、对齐、缺少分隔行的降级。
7. 选中和未选中的只读任务项。
8. 链接协议白名单。
9. 原始 HTML、script、事件属性不会执行。
10. Unicode、中文、Emoji 和反斜杠转义。
11. delta 在强调、链接、代码围栏和表格中间断开。
12. 解析失败回退纯文本。
13. 停止生成后保留已显示内容。
14. 代码复制和表格复制只包含目标正文。

## 16. 明确不应声称的内容

- 不能声称本文是 ChatGPT 官方公开的内部解析器源码。
- 不能声称 ChatGPT 只使用 Markdown；引用、工具结果和交互卡片可能是独立结构化组件。
- 不能声称已经验证所有 CommonMark/GFM 扩展。
- 不能声称已经复制 ChatGPT 的精确 CSS、代码高亮主题或私有 class 名。
- 不能把项目定义的流式降级规则描述为 ChatGPT 官网的确定内部行为。

## 17. 相关研究资料

- `docs/research/chatgpt-markdown/BEHAVIORS.md`：ChatGPT 网页版实测行为。
- `docs/research/chatgpt-markdown/PAGE_TOPOLOGY.md`：消息渲染表面拓扑。
- `docs/research/components/chatgpt-markdown-renderer.spec.md`：试点组件规格。

## 18. 完整性结论

截至目前，本文已经覆盖“普通文字、引用、代码块、表格、列表、分隔线、流式拼接、安全渲染”的首个试点范围，足以支持基础 Markdown 消息渲染。

但它还不是 ChatGPT 全部消息类型的完整协议。ChatGPT 页面中可能存在不属于 Markdown 的来源引用、工具结果、附件预览、数学公式、图表、生成图片、交互卡片和确认对话框。这些内容不能靠继续增加星号或横杠规则来解决，而应使用结构化消息类型。

因此应把完整性分成两层：

| 层级 | 覆盖范围 | 当前状态 |
| --- | --- | --- |
| Markdown 基础层 | 标题、段落、强调、列表、引用、分隔线、代码、表格、任务项、链接 | 已覆盖首个试点 |
| ChatGPT 结构化层 | 引用来源、工具结果、附件、数学、图表、卡片、对话框、动作按钮 | 需要单独协议，不能伪装成普通 Markdown |

## 19. 仍需补充的 Markdown 边界语法

下面项目属于标准 Markdown 或常见 GFM 扩展，但本次没有全部在 ChatGPT 网页版逐项实测。它们应作为第二批兼容性测试，而不是直接声称为官网内部规则：

- H4-H6 标题和标题等级跳跃。
- Setext 标题：上一行文本加下一行 `===` 或 `---`。
- 软换行与硬换行：普通换行、行尾两个空格和反斜杠换行。
- 缩进代码块：四个空格开头的代码。
- 自动链接：`https://example.com` 和 `<user@example.com>`。
- 引用链接：正文使用 `[文档][id]`，后面单独定义 URL。
- 图片：`![alt](url)`，包括 alt 文本、尺寸和安全协议。
- HTML 实体：`&lt;`、`&amp;` 等是否解码。
- 嵌套强调的边界，例如 `***粗斜体***` 和标记跨行。
- 表格中转义管道符、空单元格和列数不一致。
- 脚注、任务列表自定义标记和其他 GFM 扩展。

补充这些语法时必须保留“标准规则”和“ChatGPT 实测规则”两列，避免把解析库默认行为误认为 ChatGPT 官网行为。

## 20. ChatGPT 结构化内容不能只靠 Markdown

以下内容不应从普通句子或引号中猜测：

| 内容 | 推荐消息类型 | 不能采用的方式 |
| --- | --- | --- |
| 来源引用 | `citation` | 从普通括号文字猜来源按钮 |
| 工具调用 | `tool_call` | 从代码块猜测需要执行的工具 |
| 工具结果 | `tool_result` | 把任意模型文本当作可信结果 |
| 文件附件 | `attachment` | 从文件名猜测附件卡片 |
| 数学公式 | `math` | 把任意 `$` 文字直接交给 HTML |
| 图表或 Mermaid | `diagram` | 自动执行模型提供的脚本 |
| 确认对话框 | `dialog` | 看到两个引号就弹窗 |
| 操作按钮 | `action` | 让模型输出任意 onclick 代码 |

普通 Markdown 只负责文本排版。结构化内容应在传输层带有明确的 `type`、稳定的 `id`、受控的 payload 和允许的动作集合。

## 21. 对话框的补充转换规则

对话框不是 CommonMark/GFM 标准节点。如果业务确实需要“Markdown 文档中声明一个对话框”，必须定义受控扩展。例如使用指令块：

````markdown
:::dialog id="delete-confirm"
title: 删除确认
body: 确定要删除这个文件吗？
confirm: 确认
cancel: 取消
:::
````

解析器产生的节点应类似：

```text
DialogNode
├── id: delete-confirm
├── title: 删除确认
├── body: 确定要删除这个文件吗？
└── actions: [confirm, cancel]
```

前端只从白名单中选择 `ConfirmDialog` 组件。指令开始标记和结束标记不显示在页面上，普通的：

```text
"确定要删除吗？"
```

仍然只是普通文字。

对话框规则还必须满足：

1. 必须有稳定的 `id`，同一 ID 只能显示一次。
2. 流式文本未收到结束标记前，不得提交最终对话框动作。
3. 标题、正文和按钮文字按文本节点渲染，禁止 HTML 注入。
4. 动作名称必须来自服务端白名单，不能直接作为函数名或 URL。
5. 需要确认的动作由用户点击触发，不能由 Markdown 解析器自动执行。
6. 关闭对话框后是否允许再次打开，应由业务状态决定。

如果后端已经可以发送结构化消息，优先使用 `dialog` 事件而不是把对话框指令混在 Markdown 中。

## 22. 流式事件的完整契约

为了避免代码块、表格或对话框重复出现，前后端必须明确事件语义：

```text
{ type: "snapshot", revision, text }
{ type: "delta", sequence, eventId, text }
{ type: "block", blockId, blockType, payload }
{ type: "completed", finalRevision }
```

建议规则：

- `snapshot` 替换当前文本，不与旧文本拼接。
- `delta` 按 `sequence` 递增追加，重复 `eventId` 忽略。
- `block` 按 `blockId` 幂等更新，不根据内容字符串去重。
- `completed` 强制执行最终解析并关闭流式状态。
- 解析器只处理 Markdown 文本；结构化 block 交给对应组件。

这样可以同时支持普通 Markdown 和真正的对话框，而不需要用“两个引号”“三个横杠”这样的模糊约定触发 UI。

## 23. 可访问性和交互完整性

“转换成 DOM”不仅是标签名称，还必须保留可访问行为：

- 标题使用真实的 heading 等级，不能全部使用粗体 div。
- 列表使用 `ul`、`ol`、`li` 语义。
- 表格保留 `thead`、`tbody`、`th`、`td`，必要时补充 scope。
- 代码复制按钮使用真实 button，并有明确的 aria-label。
- 任务复选框为 disabled，不能伪装成可交互按钮。
- 对话框使用 dialog 语义、标题关联、焦点管理和 Escape 关闭。
- 对话框打开时焦点进入对话框，关闭后返回触发按钮。
- 外链保留可读名称，并通过协议白名单。

## 24. 第二批补充验收清单

在实现基础试点后，补充以下验证：

1. H4-H6、Setext 标题和标题边界。
2. 硬换行、缩进代码、图片、自动链接和引用链接。
3. `***`、`___`、嵌套强调和跨行强调。
4. 表格转义、空单元格、错列和列对齐。
5. 数学、来源、工具结果和附件是否走独立结构化组件。
6. 对话框指令的未闭合、重复 ID、危险动作和取消流程。
7. snapshot/delta 混合、重复 eventId、乱序 sequence 和断流恢复。
8. 代码块与表格复制只复制正文，不复制围栏或 UI 按钮文字。
9. 键盘、屏幕阅读器、移动端横向滚动和焦点恢复。

这些补充项完成后，文档才可以称为“项目 Markdown 到前端渲染规范”；在此之前，它准确的名称应是“基础 Markdown 渲染试点规范”。
