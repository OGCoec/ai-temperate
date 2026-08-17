# OpenAI 兼容 Chat Completions API

阶段 S 提供 API Key 授权模型发现和仅支持流式调用的 OpenAI Chat Completions 兼容端点：

```text
Base URL: https://niko000o.site/v1
Endpoint: GET /models
Endpoint: POST /chat/completions
```

这里的“SDK”指 OpenAI SDK 能调用的 HTTP 协议，不代表本项目另外发布了 Python、JavaScript 或 Java 依赖包。接口只支持 `stream=true`、字符串或纯文本 parts 消息、Function tools、工具结果和推理字段；不支持非流式调用、非文本多模态、Responses API、Anthropic Messages API、图片、音频、视频、文件、内置托管工具或 `n > 1`。

## API Key 安全边界

- API Key 通过 `POST /api/users/me/api-keys` 创建，完整值只在创建响应出现一次。
- 后续管理接口只显示 `sk-…末四位`，服务端不能解密或找回原 Key。
- Key 只能放在服务端进程或安全的 Secret 管理系统中，禁止嵌入浏览器、H5、WebView 或公开前端代码。
- `/v1/models` 与 `/v1/chat/completions` 都会拒绝 Cookie、`Origin` 或 `Referer`，避免长期 API Key 进入浏览器凭据上下文。
- Node SDK 可能自动附加 `Sec-Fetch-*`。这些头单独存在时不会触发拒绝，Worker 会在回源前全部剥离；它们不参与身份认证，也不作为可信客户端判据。
- 文档中的 `sk-***` 是脱敏占位符，不是真实凭证。

部署必须通过 `API_KEY_HMAC_SECRET_BASE64` 提供固定、规范 Base64 且解码后不少于 32 字节的 HMAC Secret；YAML 不提供默认值，缺失或非法时应用启动失败。该 Secret 只用于不可逆摘要，不传给客户端，不存在加密版本、解密或找回流程。

## 查询当前 API Key 可调用模型

```bash
curl https://niko000o.site/v1/models \
  -H "Authorization: Bearer sk-***" \
  -H "Accept: application/json"
```

成功响应遵循 OpenAI Models 列表结构：

```json
{
  "object": "list",
  "data": [
    {
      "id": "gpt-5.4-mini",
      "object": "model",
      "created": 1780000000,
      "owned_by": "ai-temperate"
    }
  ]
}
```

该接口只返回当前 API Key 已授权、当前仍启用且具备 `CHAT_COMPLETIONS` 能力的模型；有效 Key 没有模型授权时返回空数组。返回的 `id` 可直接填入下方 Chat Completions 请求的 `model` 字段，但服务端仍会在每次调用时再次校验授权。模型授权或模型启停提交后，下一次 API Key 请求会重新取得授权集合和启用模型快照。

## Agent 客户端兼容性

兼容性由请求是否符合本页的 Chat Completions 契约决定，不根据 `User-Agent`、客户端名称、IP 或自定义 Header 区分客户端。

| 客户端 | 配置要求 | 状态 |
| --- | --- | --- |
| Claude Code | 经 CC Switch 使用 OpenAI Chat Completions 路由 | 支持 |
| Codex | 经 CC Switch 使用 Responses 到 Chat Completions 路由 | 支持 |
| OpenCode | 使用 `@ai-sdk/openai-compatible` Chat Provider | 支持 |
| Hermes Agent | 使用 `chat_completions` 模式 | 支持 |
| 其他 Agent | 产生相同的 Chat Completions 请求 | 支持 |
| 原生 Responses | `/v1/responses` | 不支持 |
| 原生 Anthropic Messages | `/v1/messages` | 不支持 |

### Claude Code 文本消息转换

CC Switch 可能把 Claude Code 的多个文本块转换为以下 Chat Completions `content` 数组：

```json
[
  {"type": "text", "text": "第一段"},
  {"type": "text", "text": "第二段"}
]
```

`system`、`user` 和 `assistant` 可使用这种纯文本 parts 数组；`tool` 仍只接受字符串。服务会在转发上游前按原顺序、不添加分隔符地合并文本。parts 数组不得为空，每项只允许 `type` 和 `text`，且 `type` 必须为 `text`。图片、音频、文件或未知 part 会返回可诊断的 JSON `400 invalid_request_error`。

### Codex 本地 Responses 转 Chat 路由

Codex 对本机 CC Switch 仍使用 Responses 协议，由 CC Switch 转换后才请求本服务的 Chat Completions。CC Switch 中 Codex 供应商的请求地址填站点根地址 `https://niko000o.site`，关闭“完整 URL”，API 格式选择“OpenAI Chat Completions（需开启路由）”，并同时开启本地路由总开关和 Codex 路由。

被接管后 Codex 的实时配置应为：

```toml
base_url = "http://127.0.0.1:15721/v1"
wire_api = "responses"
```

`wire_api = "responses"` 不应改为 Chat；它描述的是 Codex 到本机 CC Switch 的协议。公网继续不开放 `POST /v1/responses`，Worker 会在到达 Spring 前返回 `404 EDGE_ROUTE_NOT_FOUND`。如果 Codex 直接显示公网 `/v1/responses` 的 Cloudflare Ray ID 或路由错误，说明本地路由没有接管；应检查 `127.0.0.1:15721` 监听、Codex 路由开关并重启已运行的 Codex 进程，不应通过 WAF 放行该路径。

为兼容上述 Agent 路由，额外支持下列严格校验的字段：

- `reasoning_effort`：`none`、`minimal`、`low`、`medium`、`high`、`xhigh`、`max`、`ultra`。
- `prompt_cache_key`：非空、最多 256 UTF-8 bytes；该值不会写入日志。
- `store`：只能为 `false`。
- `service_tier`：`auto`、`default`、`flex`、`scale`、`priority`。
- assistant 消息中的 `reasoning_content`，以及响应 SSE `delta.reasoning_content`。
- 最终 Usage 中的 `completion_tokens_details.reasoning_tokens`；该数字已经包含在 `completion_tokens` 中，不会重复计费。

未列出的字段，包括 `thinking`、`enable_thinking`、`reasoning_split`、对象形式的 `reasoning`、`metadata`、`response_format`、`logit_bias`、`logprobs`、`top_logprobs` 与 `user`，会返回 JSON `400 invalid_request_error`，并通过 `error.param` 指出字段名。服务不会静默忽略或透传未知字段。

### Function tools 大小边界

Claude Code 经 CC Switch 转换后可能一次发送二十多个 Function tools，并携带较长的工具说明和 JSON Schema。服务按协议结构统一校验这些定义，不使用 Claude Code、Codex、OpenCode 或 Hermes 的客户端白名单。

默认分层预算为：

| 范围 | 默认上限 | 计算口径 |
| --- | ---: | --- |
| 单个 `function.description` | 32 KiB | UTF-8 字节数 |
| 完整 `tools` 数组 | 512 KiB | 服务端 Jackson 序列化后的 JSON 字节数 |
| 工具数量 | 128 | 数组元素数量 |
| 完整请求体 | 1 MiB | HTTP 请求体字节数 |

单项工具描述、完整工具集合和请求体预算必须依次递增。已通过校验的名称、描述和 parameters Schema 会原样进入内部 OpenAI 兼容负载；服务不会截断、摘要、删除或改写工具说明。

超过单个描述预算时返回：

```json
{
  "error": {
    "message": "Function tool description exceeds the allowed UTF-8 size.",
    "type": "invalid_request_error",
    "param": "tools[0].function.description",
    "code": "invalid_request"
  }
}
```

完整工具集合超过预算时返回相同的 OpenAI JSON 错误包络，`error.param` 为 `tools`。这些限制可以分别通过 `API_KEY_REQUEST_MAX_TOOL_DESCRIPTION_BYTES` 和 `API_KEY_REQUEST_MAX_TOOL_DEFINITIONS_BYTES` 调整，但完整工具预算不得大于 `API_KEY_REQUEST_MAX_BODY_BYTES`，错误组合会导致应用启动失败。

## curl 流式文本调用

```bash
curl --no-buffer https://niko000o.site/v1/chat/completions \
  -H "Authorization: Bearer sk-***" \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream, application/json;q=0.9" \
  --data '{
    "model": "gpt-5.4-mini",
    "messages": [
      {"role": "user", "content": "你好"}
    ],
    "stream": true,
    "stream_options": {"include_usage": true}
  }'
```

响应是标准 SSE，每个事件使用 `data: {...}`，最后为 `data: [DONE]`。后端始终消费最终 Usage 完成结算；只有请求 `include_usage=true` 时客户端才会看到 Usage chunk。

## OpenAI Python 流式工具调用

```python
from openai import OpenAI

client = OpenAI(
    api_key="sk-***",
    base_url="https://niko000o.site/v1",
)

stream = client.chat.completions.create(
    model="gpt-5.4-mini",
    messages=[{"role": "user", "content": "芝加哥现在天气如何？"}],
    tools=[{
        "type": "function",
        "function": {
            "name": "get_weather",
            "description": "查询城市天气",
            "parameters": {
                "type": "object",
                "properties": {
                    "city": {"type": "string"}
                },
                "required": ["city"],
                "additionalProperties": False,
            },
        },
    }],
    tool_choice="auto",
    parallel_tool_calls=True,
    stream=True,
    stream_options={"include_usage": True},
)

for chunk in stream:
    delta = chunk.choices[0].delta if chunk.choices else None
    if delta and delta.content:
        print(delta.content, end="", flush=True)
    if delta and delta.tool_calls:
        for call in delta.tool_calls:
            # function.arguments 是增量 JSON 字符串，必须按 index 累积后再解析。
            print(call.function.arguments or "", end="", flush=True)
```

## OpenAI JavaScript 流式工具调用

```javascript
import OpenAI from 'openai'

const client = new OpenAI({
  apiKey: 'sk-***',
  baseURL: 'https://niko000o.site/v1'
})

const stream = await client.chat.completions.create({
  model: 'gpt-5.4-mini',
  messages: [{ role: 'user', content: '芝加哥现在天气如何？' }],
  tools: [{
    type: 'function',
    function: {
      name: 'get_weather',
      description: '查询城市天气',
      parameters: {
        type: 'object',
        properties: { city: { type: 'string' } },
        required: ['city'],
        additionalProperties: false
      }
    }
  }],
  tool_choice: 'auto',
  parallel_tool_calls: true,
  stream: true,
  stream_options: { include_usage: true }
})

for await (const chunk of stream) {
  const delta = chunk.choices[0]?.delta
  if (delta?.content) process.stdout.write(delta.content)
  for (const call of delta?.tool_calls ?? []) {
    // arguments 是增量字符串；按 call.index 聚合完整后再 JSON.parse。
    process.stdout.write(call.function?.arguments ?? '')
  }
}
```

## 参数与错误

`stream` 必须是 JSON 布尔值 `true`。Token 数和 `seed` 必须是 JSON 整数；采样参数必须是 JSON 数字；不能把这些值写成字符串。`max_tokens` 与 `max_completion_tokens` 最多出现一个，实际值不会超过模型配置的 `maxOutputTokens`。

同步错误使用 OpenAI 包络：

```json
{
  "error": {
    "message": "受控错误信息",
    "type": "invalid_request_error",
    "param": "messages",
    "code": "invalid_request"
  }
}
```

并发限制不排队：每个 API Key 最多 3 条流，同一账号的 H5、Android 和所有 API Key 合计最多 10 条流，全局最多 128 条流。并发超限返回 429 和 `Retry-After: 2`。IP 可信分低于 60 返回 403；权威风险分、Redis、数据库或 8317 不可用时失败关闭，不会继续调用模型上游。成功响应必须是 SSE；请求失败响应固定为 JSON，避免流式 `Accept` 把客户端 400 错误包装成 502。

## API Key 管理摘要

管理接口继续使用现有用户会话认证：

H5 用户可在“账户与设置 → 个人 → 开发者工具 → 管理我的 API Key”进入独立管理页。该页面只负责创建、查看脱敏信息、修改状态和模型授权、撤销 Key，不提供聊天测试器、抓包器或流式调试器；Android 当前不提供此入口。

创建成功后，完整 API Key 只在一次性弹层显示。用户确认关闭后，列表和详情只能看到脱敏值；前端不会把 Key 写入本地存储、URL、全局状态或下载文件。创建请求发生网络级不确定失败时不会自动重试，应先刷新列表并撤销无法取得完整内容的新 Key，再重新创建。

```text
POST   /api/users/me/api-keys
GET    /api/users/me/api-keys
GET    /api/users/me/api-keys/{apiKeyPublicId}
PUT    /api/users/me/api-keys/{apiKeyPublicId}
PUT    /api/users/me/api-keys/{apiKeyPublicId}/models
DELETE /api/users/me/api-keys/{apiKeyPublicId}
```

资源 ID 是固定 11 字符 Base64URL。更新和删除必须发送详情响应的强 `ETag` 作为 `If-Match`。HTTP `DELETE` 只会软删除 Key 并软撤销模型映射，数据库不会执行物理删除。

外部客户端不依赖 H5 页面保持打开。连接配置固定为：

```text
Base URL: https://niko000o.site/v1
Models: GET /models
Endpoint: POST /chat/completions
第一版要求: stream=true
Authorization: Bearer sk-***
```

CC Switch 的请求地址填写 `https://niko000o.site`（不以斜杠结尾）；它会先请求 `/v1/models`，再用返回的模型 ID 调用 `/v1/chat/completions`。
