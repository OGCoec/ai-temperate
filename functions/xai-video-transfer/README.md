# xAI 视频 FC 流式搬运器

该部署单元独立于主 Spring Boot Maven 模块。它接收经 HMAC 签名的小型 JSON，从允许的 HTTPS 来源读取视频，并以固定 8 MiB 分片写入指定 OSS Bucket；主业务 JVM 从不接收视频字节。

部署使用阿里云 FC `custom.debian11` Web Function、512 MiB 内存和 900 秒超时。`customRuntimeConfig` 运行 Shade 包中的 `XaiVideoTransferWebServer`，并由 FC 将 HTTP 请求转发至端口 `9000`。

`probe` 保持历史单个 JSON 响应。`transfer` 在已签名请求中携带 `responseMode=ndjson-v1` 时返回 `application/x-ndjson`：

- `progress`：OSS SDK 分片的真实累计字节进度；
- `verifying`：全部分片提交完毕，正在 complete 与 HEAD；
- `completed`：HEAD 校验通过后的可信视频元数据；
- `failed`：稳定错误码，不含源 URL、对象 Key、临时凭据或底层异常。

必须配置以下部署环境变量：

- `VIDEO_TRANSFER_HMAC_SECRET`：主业务与 FC 独立共享、至少 32 字节的 HMAC Secret；
- `OSS_BUCKET`、`OSS_REGION`、`OSS_ENDPOINT`：固定 OSS 目标；
- `OSS_OBJECT_PREFIX`：默认 `ai/video/`，FC 会再次校验目标 Key；
- `VIDEO_MAXIMUM_BYTES`：默认 `2147483648`；
- `VIDEO_ALLOWED_SOURCE_HOSTS`：逗号分隔的精确 HTTPS 主机白名单。

OSS 凭据不得通过请求或用户自定义环境变量传入。FC 必须绑定最小权限 RAM 角色；Web Function 只读取平台注入的 `ALIBABA_CLOUD_ACCESS_KEY_ID`、`ALIBABA_CLOUD_ACCESS_KEY_SECRET` 和 `ALIBABA_CLOUD_SECURITY_TOKEN` 短期 STS 三元组并交给 OSS SDK。所有 SDK 操作最大尝试次数固定为 1。

`s.yaml` 默认创建只允许 POST 的匿名 HTTP Trigger；匿名只代表不使用 FC 网关签名，Web Function 仍强制校验主业务 HMAC、时间戳和随机 nonce。主业务必须同步调用 HTTPS URL，禁止附加 `X-Fc-Invocation-Type: Async`，以避免平台异步重试。
