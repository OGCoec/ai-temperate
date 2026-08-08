# xAI 视频 FC 流式搬运器

该部署单元独立于主 Spring Boot Maven 模块。它接收经过 HMAC 签名的小型 JSON，使用一次 HTTPS GET 读取 xAI 或项目 OSS 视频，并以固定 8 MiB 分片写入指定 OSS Bucket。主业务 JVM 不会接收视频字节。

部署时使用阿里云 FC 内置 Java 11、512 MiB 内存和 900 秒超时，Handler 为：

`com.example.temperate.functions.video.XaiVideoTransferHandler::handleRequest`

必须配置以下环境变量：

- `VIDEO_TRANSFER_HMAC_SECRET`：主业务与 FC 独立共享、至少 32 字节的 HMAC Secret。
- `OSS_BUCKET`、`OSS_REGION`、`OSS_ENDPOINT`：固定 OSS 目标。
- `OSS_OBJECT_PREFIX`：默认 `ai/video/`，FC 会再次校验目标 Key。
- `VIDEO_MAXIMUM_BYTES`：默认 `2147483648`。
- `VIDEO_ALLOWED_SOURCE_HOSTS`：逗号分隔的精确 HTTPS 主机白名单，必须同时覆盖 xAI 临时结果域名和项目 OSS 签名输入域名。

OSS 凭据不得通过请求或环境变量传入。FC 必须绑定最小权限 RAM 角色；Handler 只通过 `Context.getExecutionCredentials()` 取得本次执行的短期 STS 三元组并交给 OSS SDK。所有 SDK 操作的最大尝试次数固定为 1。

`s.yaml` 默认创建只允许 POST 的匿名 HTTP Trigger；匿名只代表不使用 FC 网关签名，Handler 仍强制校验主业务 HMAC、时间戳和随机 nonce。主业务服务必须同步调用该 HTTPS URL，禁止附加 `X-Fc-Invocation-Type: Async`，从而避免平台异步重试。
