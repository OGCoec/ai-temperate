# 管理员 AI 模型图标 API

模型图标管理接口统一位于 `/api/admin/ai-model-icons`，沿用 `/api/admin/**` 的管理员认证、设备校验、PreAuth、WebRTC 和 CSRF 安全边界。路径中的图标资源 ID 必须是项目统一的 11 位 Base64URL 公共 ID；响应不返回数据库内部 ID 或 OSS Object Key。

## 接口

| 方法与路径 | 请求 | 成功响应 |
| --- | --- | --- |
| `GET /api/admin/ai-model-icons?pageNum=1&pageSize=100` | 页码从 1 开始，每页最多 100 条 | 按名称排序的分页图标列表 |
| `GET /api/admin/ai-model-icons/{publicId}` | 无 | 单个图标资源 |
| `POST /api/admin/ai-model-icons/remote` | JSON：`iconName`、`iconUrl`、可空 `description` | `201 Created` |
| `POST /api/admin/ai-model-icons/upload` | Multipart：`iconName`、可空 `description`、`file` | `201 Created` |
| `PATCH /api/admin/ai-model-icons/{publicId}` | `application/merge-patch+json`，允许 `iconName`、`description`、`iconUrl` | 修改后的图标资源 |
| `PUT /api/admin/ai-model-icons/{publicId}/file` | Multipart：`file` | 替换后的图标资源 |
| `POST /api/admin/ai-model-icons/{publicId}/file` | 与上述 PUT 相同；供固定使用 POST 的 `uni.uploadFile` 调用 | 替换后的图标资源 |
| `DELETE /api/admin/ai-model-icons/{publicId}` | 无 | `204 No Content` |

单个图标统一响应字段为：

```json
{
  "publicId": "AAAAAAAAAAE",
  "iconName": "OpenAI",
  "iconUrl": "https://cdn.example.test/openai.png",
  "description": "OpenAI / ChatGPT 模型族",
  "createdAt": "2026-07-27",
  "updatedAt": "2026-07-27"
}
```

本地文件和外部响应最大 2 MiB，支持 PNG、JPEG/JPG、WebP、GIF、ICO、AVIF 和 SVG。服务端不根据文件名或 URL 后缀放行，Content-Type 必须与检测到的真实格式一致；PNG、JPEG、WebP、GIF、ICO 和 AVIF 必须完成真实像素解码，SVG 必须通过静态白名单检查并在本地上传时重新序列化。

GIF 最多 120 帧，ICO 最多 20 个图标条目，二者会验证全部帧或条目；动画能力只由 GIF 提供，APNG、动画 WebP 和动画 AVIF 都会被拒绝。单帧宽高不超过 4096，动画或容器累计像素不超过 `4096 × 4096`。

外部地址还必须通过 HTTPS、公网 DNS、每跳重定向、响应状态和大小校验。验证成功后只保存最终 URL，绝不复制到 OSS，`object_key` 保持 `NULL`。

### SVG 安全档位

本地上传及普通外部域名始终使用严格档位：只允许受限静态图形、本文件唯一且存在的 `#id` 引用，不允许 `<style>`、行内 `style`、`class`、`<image>` 或 Data URI。

外部请求完成全部重定向后，只有最终响应主机命中服务端配置的八家官方主机时，才使用可信官方兼容档位：

| 厂商 | 默认可信主机 |
| --- | --- |
| OpenAI / ChatGPT | `chatgpt.com`、`openai.com` 及其真实子域 |
| Anthropic / Claude | `claude.ai`、`anthropic.com` 及其真实子域 |
| Google / Gemini | `gemini.google.com` 及其子域、精确主机 `www.gstatic.com` |
| xAI / Grok | `grok.com`、`x.ai` 及其真实子域 |
| DeepSeek | `deepseek.com` 及其真实子域 |
| 智谱 / GLM | `zhipuai.cn`、`chatglm.cn`、`bigmodel.cn` 及其真实子域 |
| Moonshot / Kimi | `kimi.com`、`moonshot.cn`、`moonshot.ai` 及其真实子域 |
| Alibaba / Qwen | `qwen.ai` 及其真实子域 |

可信官方档位不是跳过验证。它只额外允许经过 CSS AST 白名单验证的 `<style>`、行内 `style`、安全 `class`，以及 Base64 内嵌的 PNG、JPEG、WebP `<image>`。内嵌图片仍要完成真实像素解码，单个 SVG 最多 8 张、解码字节合计不超过 1 MiB、累计像素不超过 `4096 × 4096`。脚本、事件属性、动画、`foreignObject`、外部字体、外部 CSS、HTTP(S) 子资源、相对子资源、任意外部 `<image>`、重复或悬空 `#id` 引用仍会被拒绝。

厂商身份由最终主机在服务端确定，前端请求不增加厂商字段。共享 CDN 不会因为路径、查询参数或调用方声称的厂商而获得信任；例如默认只信任 `www.gstatic.com`，不会信任整个 `gstatic.com`。可通过 `AI_MODEL_ICON_TRUSTED_OFFICIAL_SVG_ENABLED=false` 立即让所有来源回退到严格档位。

## 管理端外链展示

管理员 H5 使用响应中的 `iconUrl` 直接加载外部图片，不通过本站 API 代理，也不会在展示阶段复制到 OSS。入口页面 CSP 的 `img-src` 允许同源、`data:` 和任意 HTTPS 图片；该放行只适用于图片，不扩大脚本、XHR、WebSocket、iframe 或样式来源。

外部图标站点会直接收到管理员浏览器发起的图片请求，并可能根据浏览器策略获得客户端 IP 和来源信息。第三方防盗链、WAF、资源替换、过期或网络失败均可能导致预览失败；管理端保留失败占位符，不把展示失败解释为数据库记录丢失。本站 Cloudflare WAF 和 API Worker 只处理本站域名请求，不负责放行或代理第三方图片。

## 错误状态

- `400 Bad Request`：公共 ID、名称、Merge Patch、外部 URL 或图片内容无效。
  - `AI_MODEL_ICON_IMAGE_FORMAT_UNSUPPORTED`：真实格式不在七种允许范围内。
  - `AI_MODEL_ICON_IMAGE_UNSAFE`：SVG 含危险内容，或帧数、条目数、尺寸、累计像素越界。
  - `AI_MODEL_ICON_IMAGE_INVALID`：文件损坏、Content-Type 不一致或无法完整解码。
- `404 Not Found`：图标资源不存在。
- `409 Conflict`：名称或 Object Key 冲突，或者图标仍被模型引用。
- `413 Payload Too Large`：文件超过 2 MiB。
- `503 Service Unavailable`：OSS 上传或覆盖暂时不可用，或必需图片解码器未加载。
  - `AI_MODEL_ICON_DECODER_UNAVAILABLE`：当前运行环境缺少必需解码器。

图标管理员接口保留稳定错误码、通用中文消息、时间戳以及开发诊断用的异常类型和根因字段；不得回显完整 URL、查询参数、Object Key、CSS 原文、Base64 或第三方响应内容。

## AI 模型关联

创建模型时使用可空 `iconPublicId`，编辑模型时 Merge Patch 使用：

```json
{
  "iconPublicId": "AAAAAAAAAAE"
}
```

提交 `null` 表示清除图标。旧 `icon` URL 输入字段会被拒绝。模型列表和详情继续返回最终 `icon` URL，并额外返回 `iconPublicId`；Redis 启用模型快照仍只保存原有 URL 字段。
