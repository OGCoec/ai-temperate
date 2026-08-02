# AI 对话联网搜索两态开关设计

## 目标

在普通用户聊天输入框下方、推理等级控件右侧增加联网搜索两态开关，让用户可以明确决定本次请求是否强制使用联网搜索。

开关语义固定为：

- 关闭：请求发送 `webSearchMode: "OFF"`，继续使用 Chat Completions。
- 开启：请求发送 `webSearchMode: "REQUIRED"`，使用 Responses 并强制选择 `web_search`。

开关默认关闭，避免用户未明确选择时增加搜索延迟和调用成本。

## 展示与交互

- 开关位于“推理 · 当前等级”右侧，显示“联网搜索”标签和左右滑动状态。
- 使用可点击的 `button` 实现开关轨道与滑块，并设置 `role="switch"`、`aria-checked` 和清晰的禁用状态。
- 开启状态使用现有绿色强调色；关闭状态使用中性灰色。
- 模型必须同时具备 `RESPONSES` 和 `WEB_SEARCH` 才显示开关。
- 生成期间禁用开关，防止同一请求发送后改变界面状态但不改变已发送载荷。
- 切换到不支持联网搜索的模型时立即恢复为关闭。
- 研究过程、来源和推理摘要继续使用现有面板，不因开关样式变化而改变。

## 功能开关与数据流

前端联网搜索基础功能在普通构建中启用，同时允许部署环境显式设置 `AI_CONVERSATION_WEB_SEARCH_ENABLED=false` 关闭。后端仍保留同名运行时保护；正式部署必须保证后端允许 Responses 联网搜索。

请求数据流：

```text
用户关闭开关
→ selectedWebSearchMode = OFF
→ POST body.webSearchMode = OFF
→ CHAT_COMPLETIONS

用户开启开关
→ selectedWebSearchMode = REQUIRED
→ POST body.webSearchMode = REQUIRED
→ RESPONSES_WEB_SEARCH
→ activity/source/reasoning_summary/delta 通过同一条 SSE 返回
```

管理员为模型增加能力只决定该模型是否有资格显示开关，不会替用户自动开启开关。

## 错误处理

- 模型能力不足时不显示开关，并把当前模式归一化为 `OFF`。
- 后端功能关闭、8317 不支持 `/v1/responses` 或拒绝 `web_search` 时，沿用现有受控 SSE 错误，不静默降级为普通回答。
- 开启状态不伪造搜索活动；只有收到上游真实 `WEB_SEARCH` 事件后才显示正在联网搜索。

## 测试与验收

- 前端功能默认可用，但显式环境变量 `false` 可以关闭。
- 同时具备 `RESPONSES + WEB_SEARCH` 的模型显示开关；能力不足的模型不显示。
- 开关关闭时请求载荷是 `OFF`；开启时请求载荷是 `REQUIRED`。
- 开启后切换到不支持模型会恢复 `OFF`。
- 生成期间开关不可操作。
- 页面不再显示旧的三档 Picker。
- 真实请求的开发者工具载荷可以看到 `webSearchMode: "REQUIRED"`，并在上游返回搜索事件时显示状态和来源。

本次改造不修改 Spring Boot、Spring AI、SSE 事件协议、heartbeat、计费或数据库结构。
