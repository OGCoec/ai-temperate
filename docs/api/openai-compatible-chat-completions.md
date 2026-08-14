# OpenAI 兼容 Chat Completions API

阶段 S 提供一个仅支持流式调用的 OpenAI Chat Completions 兼容端点：

```text
Base URL: https://niko000o.site/v1
Endpoint: POST /chat/completions
```

这里的“SDK”指 OpenAI SDK 能调用的 HTTP 协议，不代表本项目另外发布了 Python、JavaScript 或 Java 依赖包。第一版不支持非流式调用、多模态、Responses API、图片、音频、视频或 `n > 1`。

## API Key 安全边界

- API Key 通过 `POST /api/users/me/api-keys` 创建，完整值只在创建响应出现一次。
- 后续管理接口只显示 `sk-…末四位`，服务端不能解密或找回原 Key。
- Key 只能放在服务端进程或安全的 Secret 管理系统中，禁止嵌入浏览器、H5、WebView 或公开前端代码。
- `/v1/chat/completions` 会拒绝 Cookie、`Origin`、`Referer` 和 `Sec-Fetch-*` 浏览器元数据。
- 文档中的 `sk-***` 是脱敏占位符，不是真实凭证。

部署必须通过 `API_KEY_HMAC_SECRET_BASE64` 提供固定、规范 Base64 且解码后不少于 32 字节的 HMAC Secret；YAML 不提供默认值，缺失或非法时应用启动失败。该 Secret 只用于不可逆摘要，不传给客户端，不存在加密版本、解密或找回流程。

## curl 流式文本调用

```bash
curl --no-buffer https://niko000o.site/v1/chat/completions \
  -H "Authorization: Bearer sk-***" \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
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

并发限制不排队：每个 API Key 最多 3 条流，同一账号的 H5、Android 和所有 API Key 合计最多 10 条流，全局最多 128 条流。并发超限返回 429 和 `Retry-After: 2`。IP 可信分低于 60 返回 403；权威风险分、Redis、数据库或 8317 不可用时失败关闭，不会继续调用模型上游。

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
Endpoint: POST /chat/completions
第一版要求: stream=true
Authorization: Bearer sk-***
```
