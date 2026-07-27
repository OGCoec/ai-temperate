# ai-temperate

## 发布版本

- [v2.0.0 第二版发布说明](docs/releases/v2.0.0.md)：管理员系统、AI 模型与图标管理、网络风险控制、用户头像、Cloudflare API Gateway，以及认证和会话安全升级。

## IP2Location LITE

认证页面可以使用外部 IP2Location LITE DB11 IPv6 BIN 提供手机号国家或地区的默认建议。BIN 文件不进入代码仓库或应用 JAR，部署、可信代理和更新回滚步骤见 [IP2Location LITE BIN 部署与维护](docs/operations/ip2location-bin.md)。

This product includes IP2Location LITE data available from [https://lite.ip2location.com](https://lite.ip2location.com).

Spring Boot 3.5.5、Java 21、Maven 多模块项目骨架。

## 模块

```text
ai-temperate-web
  -> ai-temperate-service
    -> ai-temperate-mapper
      -> ai-temperate-model
        -> ai-temperate-common
```

- `ai-temperate-common`：Redis、Redisson、JWT、Hutool、手机号和 IP 查询基础依赖。
- `ai-temperate-model`：领域模型。
- `ai-temperate-mapper`：MyBatis、PageHelper 和 PostgreSQL 数据访问。
- `ai-temperate-service`：RabbitMQ、阿里云号码认证、阿里云 OSS 和微信支付集成。
- `ai-temperate-web`：Spring MVC、Validation、Security、OpenAPI、Knife4j 和启动入口。

## 默认基础设施

| 服务 | 地址 | 说明 |
| --- | --- | --- |
| PostgreSQL | `127.0.0.1:5431/ai_temperate` | 唯一主库，不包含分库、分表、从库或读写路由 |
| Redis | `127.0.0.1:6378` | 默认使用 database 0 |
| RabbitMQ AMQP | `127.0.0.1:5673` | Spring 应用连接端口 |
| RabbitMQ Management | `http://127.0.0.1:11111` | 管理页面端口，不属于 Spring AMQP 配置 |

用户名、密码和地址都可以通过 `POSTGRES_*`、`REDIS_*`、`RABBITMQ_*` 环境变量覆盖。生产环境不要把真实密钥提交到仓库。

## 构建和测试

```powershell
mvn clean verify
```

测试 profile 会关闭 PostgreSQL、Redis 和 RabbitMQ 自动配置，因此单元测试不要求本机服务在线。

## 本地 HTTPS 启动

本地开发复用 `%USERPROFILE%\.ai-temperate\certs` 下同一张证书的 PKCS12 与 PEM 表示，不把证书私钥或密码提交到项目。首次使用时可以查询当前用户信任状态：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\https\manage-local-certificate-trust.ps1 -Action Status
```

确认指纹无误后，可显式安装到当前 Windows 用户的受信任根证书库：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\https\manage-local-certificate-trust.ps1 -Action Install
```

安装动作只影响当前 Windows 用户；回滚时使用 `-Action Remove`，不会删除外部证书文件。

完全退出 Antigravity 与 HBuilderX 后，双击或执行：

```powershell
.\start-local-https-dev.bat
```

Antigravity 或 Codex 已经在运行时，只需要完全退出 HBuilderX，然后执行：

```powershell
.\start-local-https-dev.bat -HBuilderXOnly
```

只检查 IDE 路径、DPAPI 密码、P12/PEM 一致性、指纹、别名、有效期和 SAN，而不启动 IDE：

```powershell
.\start-local-https-dev.bat -ValidateOnly
```

启动器用当前 Windows 用户解密 DPAPI 密码，校验固定证书指纹，再把本地 HTTPS 环境显式注入到 IDE 子进程。默认同时打开 Antigravity 与 HBuilderX；`-HBuilderXOnly` 只打开 HBuilderX，用于当前会话中已经打开 Antigravity 或 Codex 的场景。随后在 Antigravity 中启动 Spring Boot，在 HBuilderX 中运行 H5 或 Android。密码不会写入项目文件、日志或永久环境变量。

生产 H5 通过中央 Cloudflare Worker 使用同源 `/api`：普通站点访问
`https://niko000o.site/api/**`，管理员站点访问
`https://admin.niko000o.site/api/admin/**`，Worker 再原路径转发到
`https://api.niko000o.site`。正式切换后所有普通用户和管理员业务 Cookie 都是
Host-only，生产环境必须删除 `AUTH_COOKIE_DOMAIN`、`ADMIN_COOKIE_DOMAIN` 和
`ADMIN_CSRF_COOKIE_DOMAIN`。主配置只为上线迁移和紧急回滚保留空值占位符，Worker 会拒绝
任何仍带 `Domain=` 的业务响应。

Worker 与 Spring Boot 使用独立的 `EDGE_PROXY_HMAC_SECRET_BASE64` 验证请求。切换期设置
`EDGE_PROXY_MODE=OPTIONAL`，正式切换并清理旧父域 Cookie 后改为
`EDGE_PROXY_MODE=REQUIRED`；本地启动器固定注入 `EDGE_PROXY_MODE=DISABLED`。
Worker 源码、路由、迁移端点和部署说明位于
[`cloudflare/api-gateway`](cloudflare/api-gateway/README.md)。

本地 HTTPS 地址：

- 健康检查：`https://localhost:6655/api/health`
- OpenAPI JSON / Apifox 导入：`https://localhost:6655/v3/api-docs`
- Swagger UI：`https://localhost:6655/swagger-ui.html`
- Knife4j：`https://localhost:6655/doc.html`
- H5：`https://localhost:3000`
- H5 公网入口：`https://niko000o.site`（迁移完成后由 Cloudflare Pages 托管）
- API 公网入口：`https://api.niko000o.site`

`local-https` Profile 激活后，6655 端口只提供 HTTPS，不保留 HTTP 连接器或跳转。直接从普通终端启动且不激活该 Profile 时，仍使用主配置的通用运行方式，不依赖本地证书。

## Cloudflare 本地隧道

项目根目录提供相互隔离的 Tunnel 入口：

```text
scripts\cloudflare\windows-legacy-tunnel\start-cloudflare-frontend-dev.bat
scripts\cloudflare\windows-legacy-tunnel\stop-cloudflare-frontend-dev.bat
scripts\cloudflare\windows-legacy-tunnel\start-cloudflare-api.bat
scripts\cloudflare\windows-legacy-tunnel\stop-cloudflare-api.bat
```

`frontend-dev` 只为可选的 `dev.niko000o.site` 开发访问回源 `https://localhost:3000`；生产根域名不长期连接 HBuilderX/Vite。`api` 为 `api.niko000o.site` 回源 `https://localhost:6655`。脚本使用固定 PEM 校验本地证书并保持 `noTLSVerify: false`，且只按 PID 停止本项目启动的 `cloudflared`。

迁移期间保留 `scripts/cloudflare/windows-legacy-tunnel/start-cloudflare-frontend.bat` 和 `scripts/cloudflare/windows-legacy-tunnel/stop-cloudflare-frontend.bat` 作为根域名旧 Tunnel 的紧急回滚入口，日常开发不要使用它们。Pages 发布、域名切换和回滚步骤见 [前端公网发布与回滚手册](docs/operations/frontend-public-deployment.md)。

详细说明：[scripts/cloudflare/README.md](scripts/cloudflare/README.md)

## 认证模块

- 当前交接状态：[HANDOFF.md](HANDOFF.md)
- API 约定：[docs/authentication-api.md](docs/authentication-api.md)
- Redis Key、TTL 与 Lua 原子边界：[docs/authentication-redis.md](docs/authentication-redis.md)
- uni-app 页面：`fornted/pages/auth`

认证模块首版已完成跳过测试的编译检查：

```powershell
mvn -DskipTests compile
```

真实 PostgreSQL、Redis、邮件、短信、Turnstile 与 AndroidKeyStore 仍需按交接文档顺序联调，不应把编译成功等同于功能或安全测试通过。
