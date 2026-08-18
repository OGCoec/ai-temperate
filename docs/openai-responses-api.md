# OpenAI Responses API 兼容范围

本项目公开提供 `POST /v1/responses`，定位为“Codex 核心 Responses 子集”，不声明完整兼容 OpenAI Responses API。

## 传输

- `stream` 缺省或为 `false`：返回 `application/json`。
- `stream=true`：返回 `text/event-stream`，保留 Responses 原生 `event:` 名称。
- SSE 以 `response.completed`、`response.incomplete`、`response.failed` 或 `error` 为权威终态，不生成 Chat 风格 `[DONE]`。
- 服务端固定向 `http://127.0.0.1:8317/v1/responses` 发起对应 JSON 或 SSE 请求，并使用服务端密钥；客户端 Bearer Key 不会转发到 8317。

## 支持能力

- 文本字符串或 `developer`、`system`、`user`、`assistant` 消息输入。
- `instructions`、`reasoning.effort`、`reasoning.summary`。
- `type=function` 工具、函数调用增量、`function_call` 与通过 `call_id` 关联的 `function_call_output`。
- `store=false` 下回放 `reasoning` 项及 `encrypted_content`。
- 非流式 JSON 和流式 SSE 的权威 Usage 结算。
- 无状态多轮：客户端显式回放需要的历史输入与输出项。

## 不支持能力

以下输入返回 HTTP 400，不会静默忽略：

- `store=true`、`previous_response_id`、`conversation`、`background`、`metadata`。
- Web Search、File Search、Computer Use、Code Interpreter、MCP 等托管工具。
- 图片、文件、音频和视频输入。
- JSON Schema 等结构化输出。
- 任何不在公开 DTO 白名单内的顶层或嵌套字段。

本项目不保存 OpenAI Response，不提供 GET、DELETE、CANCEL、Conversation 或后台任务接口，也不使用 WebSocket。

## 模型发现与上线开关

`GET /v1/models` 返回当前 API Key 已授权、已启用，且支持 `CHAT_COMPLETIONS` 或 `RESPONSES` 至少一种能力的模型。响应保持 OpenAI 兼容结构，不添加私有 capability 字段。

模型只有在管理端启用 `RESPONSES` capability 后才能用于 `/v1/responses`；该能力也是首选回滚开关。
