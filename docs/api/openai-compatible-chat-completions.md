# OpenAI 常用协议兼容矩阵

本项目提供 OpenAI 常用协议兼容层，而不是 OpenAI 平台的完整镜像。契约基线固定为 2026-08-18 的正式版 [Chat Completions](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create) 与 [Responses API](https://developers.openai.com/api/reference/overview)。未来官方字段不会自动获得支持。

```text
Base URL: https://niko000o.site/v1
GET  /models
POST /chat/completions
POST /responses
```

增强路径仅用于模型供应商为 `OPENAI` 的请求，并由 `API_KEY_OPENAI_COMPATIBILITY_ENABLED` 控制。开关关闭时立即回到原有严格 DTO 路径；xAI、Anthropic、Google 等供应商继续使用原来的白名单和适配器。

## 总体边界

| 能力 | Chat Completions | Responses |
| --- | --- | --- |
| 非流式 JSON | 支持 | 支持 |
| 流式 SSE | 支持，以 `[DONE]` 结束 | 支持，保留原生 event 名称 |
| 文本输入 | 支持 | 支持 |
| Function 工具 | 支持 | 支持 |
| JSON Object / JSON Schema | 支持 | 支持 |
| 多模态、文件 | 不支持，明确报错 | 不支持，明确报错 |
| 托管工具或 MCP | 不支持 | 不支持 |
| 存储、后台、历史资源 | 不支持 | 不支持 |

未支持的字段不会被静默丢弃。请求会得到精确字段路径：

```json
{
  "error": {
    "message": "Unsupported parameter: store",
    "type": "invalid_request_error",
    "param": "store",
    "code": "unsupported_parameter"
  }
}
```

## Chat Completions

支持的消息角色为 `developer`、`system`、`user`、`assistant`、`tool`，以及兼容旧客户端的 `function`。`content` 可为字符串或 `type=text` 的文本块；`reasoning_content` 是保留给现有客户端的显式扩展字段。

支持的常用字段：

- 生成控制：`max_completion_tokens`、旧版 `max_tokens`、`temperature`、`top_p`、`presence_penalty`、`frequency_penalty`、`stop`、`seed`、`n`。
- 模型控制：`reasoning_effort`、`service_tier`、`verbosity`、`safety_identifier`、`user`。
- 输出信息：`logprobs`、`top_logprobs`、文本 `prediction`。
- 缓存提示：`prompt_cache_key`、`prompt_cache_options`、文本块 `prompt_cache_breakpoint`。
- 工具：`tools`、`tool_choice`、`parallel_tool_calls`，以及旧版 `functions`、`function_call`。
- 结构化输出：`response_format.type` 为 `text`、`json_object` 或 `json_schema`。
- 传输：`stream` 与 `stream_options.include_usage`。

`max_completion_tokens` 和 `max_tokens` 不能同时提交。`store` 只允许缺省、`null` 或 `false`。

非流式示例：

```json
{
  "model": "gpt-test",
  "messages": [{"role": "user", "content": "只返回 JSON"}],
  "response_format": {
    "type": "json_schema",
    "json_schema": {
      "name": "answer",
      "strict": true,
      "schema": {
        "type": "object",
        "properties": {"answer": {"type": "string"}},
        "required": ["answer"],
        "additionalProperties": false
      }
    }
  },
  "stream": false
}
```

## Responses

支持字符串 `input`，以及文本 message、reasoning、function call 和 function call output 输入项。省略 `store` 时，增强路径会把它规范化为 `false`，保持无状态行为。

支持的常用字段：

- 生成控制：`max_output_tokens`、`reasoning`、`temperature`、`top_p`、`top_logprobs`。
- 模型控制：`service_tier`、`truncation`、`safety_identifier`、`user`。
- 缓存提示：`prompt_cache_key`、`prompt_cache_retention`。
- 工具：Function `tools`、`tool_choice`、`parallel_tool_calls`、`max_tool_calls`。
- 结构化输出：`text.format.type` 为 `text`、`json_object` 或 `json_schema`；支持 `text.verbosity`。
- 无状态 include：`reasoning.encrypted_content`、`message.output_text.logprobs`。
- 传输：`stream=false` 或省略时返回 JSON；`stream=true` 返回原生 Responses SSE。

明确拒绝 `store=true`、`background=true`、`previous_response_id`、`conversation`、图片、音频、文件以及 Function 以外的 hosted/custom/MCP 工具。

## 成功响应与错误

OPENAI 增强路径不重建成功正文：JSON 保留完整对象；SSE 保留原始 event 名称和 data JSON。Chat 不限制 choice 数量或 choice index，因此 multiple choices、logprobs、tool calls、refusal、usage、system fingerprint 与其他上游字段不会因网关重建而丢失。

网关只旁路读取结算必需的 Usage、缓存输入 Token、finish reason 和 Responses 终态。Chat 非流式响应必须在本地结算成功后才建立 HTTP 200；流式 Chat 会强制向上游请求最终 Usage，但客户端没有请求 Usage 时不输出仅供结算的 Usage chunk。`n>1` 始终按上游总 Usage 结算。

只有满足以下安全条件的 OpenAI 上游错误才原样返回：

- HTTP 状态在 400～599；
- `Content-Type` 为 JSON；
- 正文是合法 OpenAI `error` 包络；
- 正文不含内部 8317 地址、凭据或内部路由标记。

安全错误会保留原始状态和错误字段。其他错误统一转换为受控网关错误。可透传的响应头仅限 `x-request-id`、`openai-*`、`x-ratelimit-*` 和 `retry-after`。

客户端可提交 `X-Client-Request-Id`；该值必须为 1～512 个可打印 ASCII 字符。服务不会记录完整请求正文、Authorization 或供应商凭据。

## 安全、额度与部署

- API Key 只能放在服务端进程或 Secret 管理系统中，禁止嵌入浏览器、H5 或公开前端。
- Cloudflare 仅开放 `/v1/chat/completions`、`/v1/responses` 和 `/v1/models`，两个 create 路由都根据上游实际 Content-Type 自适应转发 JSON 或 SSE。
- JSON 与 SSE 都使用 `no-store`；只有实际 SSE 响应添加禁缓冲头。
- 继续使用现有并发准入、Token 预扣、终态结算和取消恢复机制。
- 本兼容子集不提供托管工具或持久资源，因此不增加非 Token 额度和计费逻辑。
- 图片、音频、文件、Web Search、File Search、Code Interpreter、MCP、Conversation、历史 Response、WebSocket 与 Beta Multi-agent 均不在此契约内。

测试环境启用示例：

```text
API_KEY_OPENAI_COMPATIBILITY_ENABLED=true
```

生产启用前必须完成 Java、Cloudflare、本地假上游和官方 SDK 契约测试。真实 8317 测试会使用模型额度，需要单独批准。
