# AI Temperate Frontend

这是 AI Temperate 的 UniApp 前端，当前覆盖认证流程、TOTP 二次认证、普通用户 AI 聊天、个人中心和已启用模型目录。

当前页面范围：

- 启动会话恢复：`pages/launch/session-gate`
- 登录：`pages/auth/login`
- 注册：`pages/auth/register`
- 找回密码：`pages/auth/password-reset`
- AI 聊天与最近会话：`pages/ai-chat/index`
- 个人中心：`pages/account/profile`
- 模型目录：`pages/ai-models/catalog`
- 模型详情：`pages/ai-models/detail`

普通用户一级导航固定为“聊天 / 模型 / 个人”，登录成功默认进入聊天页。“新聊天”只清理页面内存，第一条发送收到服务端 `accepted` 后才取得会话公共 ID，只有收到 `completed` 才刷新最近会话。

聊天历史只读取 PostgreSQL 接口，不读取 Redis；用户取消或网络中断产生的部分回答离开当前页面后立即丢弃，不会在重新打开会话时展示。H5 与 Android 共享同一 SSE 帧解析器；H5 使用 `fetch + ReadableStream`，Android 使用 `ait-sse` 原生 POST SSE 传输。

附件先通过受保护 API申请 OSS 预签名 PUT，再由客户端直接上传 OSS。H5 使用带进度的 `XMLHttpRequest`，Android 使用系统文档选择器和原生流式 PUT；直接 OSS 请求不经过 `authorizedRequest`，其余后端 API 必须经过统一认证请求层。SVG、HTML、文档、压缩包和可执行文件只作为文件卡外部打开，不在页面内联执行。

个人中心、模型目录、会话历史和预上传 API 都只能经统一的受保护请求客户端访问；前端不直接访问本机网关，也不保存内部用户 ID、OSS 凭据、CLIProxyAPI 密钥或 Redis Key。
