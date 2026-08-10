# 前端公网发布与回滚手册

## 目标架构

公网用户统一访问生产静态站点，开发服务器只用于本地开发或受保护的开发子域名：

```text
普通浏览器
  -> https://niko000o.site
  -> Cloudflare Pages
  -> H5 生产静态文件

HBuilderX
  -> https://localhost:3000
  -> Vite 开发服务器

可选的远程开发访问
  -> https://dev.niko000o.site
  -> 独立 frontend-dev Tunnel
  -> https://localhost:3000

普通 H5 API 请求
  -> https://niko000o.site/api/**
  -> Cloudflare Worker
  -> https://api.niko000o.site
  -> 独立 API Tunnel
  -> https://localhost:6655

普通 H5 语音 WebSocket
  -> wss://niko000o.site/ws/voice
  -> Cloudflare Worker
  -> https://api.niko000o.site/ws/voice + Upgrade: websocket
  -> 独立 API Tunnel
  -> https://localhost:6655/ws/voice

Android HTTP 与 SSE
  -> https://niko000o.site/api/**
  -> Cloudflare Worker
  -> https://api.niko000o.site
  -> 独立 API Tunnel
  -> https://localhost:6655

Android 语音 WebSocket
  -> wss://niko000o.site/ws/voice
  -> Cloudflare Worker
  -> https://api.niko000o.site/ws/voice + Upgrade: websocket
  -> 独立 API Tunnel
  -> https://localhost:6655/ws/voice

管理员 H5 API 请求
  -> https://admin.niko000o.site/api/admin/**
  -> 同一个 Cloudflare Worker
  -> https://api.niko000o.site
  -> 独立 API Tunnel
  -> https://localhost:6655
```

两个生产 H5 都通过当前页面 Host 的同源 `/api` 调用后端；Android 使用相同主域名的绝对
API 基址。普通 H5 与 Android 的语音连接都只使用根域名精确 `/ws/voice`。Worker 保持路径
不变，只允许普通站点进入普通命名空间、管理员站点进入 `/api/admin/**`，因此后端仍然只有
一个 Spring Boot 实例。H5 业务 Cookie 都不设置 `Domain`；Android 继续使用显式 Header
Token 且不接收业务 Cookie。语音身份由连接后的平台匹配单次票据完成，Worker 不把 Cookie
或 Authorization 转发到 WebSocket 上游。

## 当前迁移状态

- 旧 `frontend` Profile 仍将 `niko000o.site` 回源到 `https://localhost:3000`，仅作为迁移期回滚通道。
- 新 `frontend-dev` Profile 固定使用 `dev.niko000o.site`。独立 Tunnel ID `16698f57-7037-4252-adfe-4cc1319bf55c` 由 `scripts/cloudflare/windows-legacy-tunnel/start-cloudflare-frontend-dev.bat` 注入，禁止与旧前端 Tunnel 共用同一个 Tunnel ID；凭据 JSON 仍只保存在用户目录，不进入仓库。
- `api` Profile 和 `api.niko000o.site` 不变，但 `api.` 只作为 Worker 回源，生产客户端不再直连。
- 中央 Worker 源码位于 `cloudflare/api-gateway`；正式 Routes 启用前不能把后端切换为
  `EDGE_PROXY_MODE=REQUIRED`。
- Cloudflare 临时 Configuration Rule `52a5bd8bfb6f499d854e43fdaed8ddd4` 只对根域名的 Vite 开发资源关闭 Browser Integrity Check。生产切到 Pages 并完成观察后应删除该规则。

## 第一阶段：生成 H5 生产文件

在 HBuilderX 中使用“发行 -> 网站-H5 手机版”生成生产包。预期输出目录：

```text
fornted\unpackage\dist\build\h5
```

发布目录必须包含 `index.html`、业务静态资源以及由 `fornted/public` 复制得到的 `_headers` 和 `_redirects`。`_redirects` 负责把直接访问的前端路由回写到 `index.html`；`_headers` 必须让 `/index.html` 与 `/pages/*` 都保持浏览器和 CDN 不缓存，只允许带内容哈希的 `/assets/*` 长期缓存。禁止把 `fornted` 源码目录、`.vue` 源文件、`node_modules`、本地证书或环境变量文件上传到 Pages。

构建属于第二阶段验证操作。执行前必须获得用户确认，且只使用 HBuilderX 生产发行，不连接生产数据库、Redis 或 RabbitMQ。构建完成后、上传 Pages 前必须在 `fornted` 目录执行以下发布前检查：

```powershell
npm run verify:esm-contracts
npm run test:models
npm run verify:h5-release -- --dir unpackage\dist\build\h5
```

`verify:esm-contracts` 用于提前发现 `user-model-catalog.vue` 导入的本地命名导出在目标模块中不存在等源码契约错误；`verify:h5-release` 用于阻止把 Vite 开发模块、`.vue` 源文件、`node_modules` 或缺少缓存规则的 H5 目录上传到 Pages。这些命令只读取本地源码和发行目录，不连接生产数据库、Redis、RabbitMQ 或生产 OSS。

## 第二阶段：创建 Pages 预览部署

建议 Pages 项目名：

```text
ai-temperate-frontend
```

使用 Cloudflare Pages Direct Upload 上传 `fornted\unpackage\dist\build\h5`。首次只使用自动分配的 `*.pages.dev` 预览地址，不绑定根域名。

静态验收：

1. 首页返回 200，响应不包含 `CF-Mitigated: challenge`。
2. 页面只加载生产 JS/CSS/图片，不请求 `.vue`、`/@vite/`、`/@fs/`、`pages-json-js` 或 HMR WebSocket。
3. 直接打开 `/pages/auth/login` 后刷新仍能返回应用页面。
4. `index.html` 不长期缓存；带内容哈希的 `/assets/*` 可以长期缓存。
5. Console 没有动态模块加载、Mixed Content、证书或 Service Worker 旧版本错误。
6. DevTools Network 中根域名页面不出现 `.vue?type=script`、`304` 的源码模块响应或 HMR WebSocket；如果出现，说明当前域名仍回源到 Vite/Tunnel，不能继续生产切换。

`*.pages.dev` 只用于静态资源与路由回退预览，不直连生产 API。新的 CSP 和 API Base URL
刻意不为预览域开放跨域认证；完整登录必须等自定义域名与对应 Worker Route 生效后再验收，
避免临时 CORS 配置重新引入父域 Cookie 或双入口问题。

## 第三阶段：部署中央 Worker

1. 在 Cloudflare Worker Secret 和 Spring Boot 环境中配置同一份独立
   `EDGE_PROXY_HMAC_SECRET_BASE64`。
2. 后端先使用 `EDGE_PROXY_MODE=OPTIONAL`，仅用于 Worker 与客户端切换验证。
3. 发布 `cloudflare/api-gateway`，保持 `workers.dev` 和 preview URL 关闭。
4. 配置 `niko000o.site/api/*`、`admin.niko000o.site/api/admin/*` 与精确的
   `niko000o.site/ws/voice` Route，并在 Cloudflare 控制台选择失败关闭。
5. 为两条 API Route 配置缓存绕过；认证响应必须保持 `Cache-Control: no-store`。确认 Zone
   的 WebSockets 功能已开启，且 WAF 只检查初始 Upgrade，不记录或检查后续语音帧。

Worker 将根域请求分类为 H5 Cookie 运输或 Android Header 运输。Android 必须声明
`X-Client-Platform: ANDROID` 且不携带 Origin 或任何 `Sec-Fetch-*`；Worker 会删除 Android
入站 Cookie 与浏览器元数据，但保留显式 Token、PreAuth、CSRF 与设备安装 ID 头。Android
上游响应不得设置 Cookie。Cloudflare WAF 与 Bot 规则不在本次发布范围；若响应包含
`CF-Mitigated: challenge` 与 HTML Challenge Page，应停止验收并作为独立外部阻塞处理。

Worker 发布与 Route 修改属于第二阶段外部状态操作，必须在用户明确批准后执行。

## 第四阶段：生产域名切换

预览通过后，在 Pages 项目中添加自定义域名：

```text
niko000o.site
```

切换前记录旧根域名 Tunnel DNS 路由和当前 Tunnel ID。Pages 自定义域名生效后，确认根域名只指向 Pages，不再同时指向旧 Tunnel。

如果线上出现 `does not provide an export named 'buildTextHighlightSegments'` 之类浏览器模块错误，先按以下顺序处理，禁止只重启 HBuilderX：

1. 确认 `niko000o.site` 当前 DNS/Route 是否仍指向旧 `frontend` Tunnel；如果仍指向 Vite 开发服务，先切回已验收 Pages 部署或明确作为回滚状态处理。
2. 重新生成同一 Git 版本的 H5 生产包，执行 `verify:esm-contracts`、`test:models` 和 `verify:h5-release`。
3. 通过 Pages Direct Upload 发布完整目录，不单独替换某一个 `.js` 或 `.vue` 文件。
4. 若 Cloudflare 或浏览器已缓存旧源码路径，清理 `https://niko000o.site/index.html`、`https://niko000o.site/pages/ai-chat/index`、`https://niko000o.site/common/aimodel/description-highlight.js` 和 `https://niko000o.site/components/user/workspace/user-model-catalog.vue`；生产 Pages 验收通过后这些源码路径不应再被请求。
5. 使用新的无痕会话验收，Network 里只允许出现生产静态资源和 `/api/**` 请求，不允许出现 Vite 开发模块。

生产验收：

1. 新建普通 Chrome、Edge 或无痕会话均能打开登录页。
2. 页面资源哈希与 Pages 预览一致。
3. `GET https://niko000o.site/api/auth/csrf` 经 Worker 返回预期状态并签发
   Host-only `XSRF-TOKEN`。
4. `https://admin.niko000o.site/api/admin/auth/state` 只签发 Host-only
   `ADMIN-XSRF-TOKEN`，不出现普通 `XSRF-TOKEN`。
5. 删除生产环境中的 `AUTH_COOKIE_DOMAIN`、`ADMIN_COOKIE_DOMAIN` 和
   `ADMIN_CSRF_COOKIE_DOMAIN`；此时后端继续保持 `EDGE_PROXY_MODE=OPTIONAL`，直到主域名客户端全部验收。
6. 重新生成并安装 Android 开发包，确认 PreAuth、登录、Token 续期、AI SSE、上传和语音
   Ticket 请求都只访问 `https://niko000o.site/api/**`，不再出现客户端直连 `api.`。
7. 同一个新浏览器配置往返两个站点后，两边仍只包含各自业务 CSRF Cookie。
8. API Tunnel 的请求量随操作增长，前端旧 Tunnel 不再收到生产页面流量。
9. 发布新版普通 H5 后，语音 Network 请求固定显示
   `wss://niko000o.site/ws/voice` 和 `101 Switching Protocols`，不再出现
   `wss://api.niko000o.site/ws/voice`。
10. Android 语音请求同样固定显示 `wss://niko000o.site/ws/voice` 和 `101 Switching Protocols`，
    且 Worker 回源无 Origin、Cookie 与 Authorization。
11. 确认 H5 与 Android 的临时转写、最终转写、排队取消和停止录音正常后，最后部署后端
    REQUIRED 收口版本并设置 `EDGE_PROXY_MODE=REQUIRED`。此模式对 `/api`、`/api/**` 与
    精确 `/ws/voice` 全部要求有效 Worker HMAC，不允许无 Origin Android 直连。
12. 验证主域名经 Worker 请求仍成功，直接请求 `api.niko000o.site/api/**` 或
    `api.niko000o.site/ws/voice` 返回 `EDGE_PROXY_SIGNATURE_INVALID`。

## 第五阶段：收口安全例外

生产观察稳定后：

1. 停止旧 `frontend` Profile，但暂时保留 Tunnel 凭据和脚本。
2. 删除临时 Configuration Rule `52a5bd8bfb6f499d854e43fdaed8ddd4`。Pages 生产包不需要 Vite 源码例外。
3. 如需远程开发，单独创建 `dev.niko000o.site` DNS/Tunnel 路由并启用 Cloudflare Access；否则不要公开该子域名。
4. API Tunnel 和 API 域名保持不变；后端稳定保持 `EDGE_PROXY_MODE=REQUIRED`。
5. H5 与 Android 对 `api.niko000o.site/api/**` 或 `api.niko000o.site/ws/voice` 的无签名直连
   都必须被后端拒绝；单次语音 Ticket 不能替代 Worker HMAC 边缘边界。

## 回滚

如果 Pages 切换后出现严重故障：

若主域名链路故障且后端已经完成 REQUIRED 收口，必须先把后端切换到临时兼容模式，再回滚
Android API 基址或 H5 版本；禁止先恢复任何 `api.` 直连客户端，否则会立即收到签名403。
Worker 的精确 `/ws/voice` Route 可以暂时保留，API Tunnel、Whisper 地址和 DNS 均不修改。

1. 先把后端切换为 `EDGE_PROXY_MODE=OPTIONAL`，避免旧 H5 被 REQUIRED 签名边界阻断。
2. 恢复 Android 或受影响前端的旧 API Base URL；确认客户端已恢复后才能暂停 Worker Routes。
3. 如需恢复旧架构，再恢复切换前的 Cookie Domain 配置；所有 H5 用户仍必须重新登录。
4. 把 `niko000o.site` 恢复到切换前记录的旧 frontend Tunnel 路由。
5. 启动 `scripts/cloudflare/windows-legacy-tunnel/start-cloudflare-frontend.bat`，确认它仍使用旧 `CF_FRONTEND_TUNNEL_ID`。
6. 不回滚 `api.niko000o.site`，除非证据表明 API Tunnel 本身故障。

回滚后重新收集浏览器 Network、两个 Tunnel 指标和同一时间段日志，再决定下一次切换。
