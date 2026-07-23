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

前端 API 请求
  -> https://api.niko000o.site
  -> 独立 API Tunnel
  -> https://localhost:6655
```

`niko000o.site` 不再长期回源 HBuilderX/Vite。这样公网浏览器不会请求 `.vue`、`/@vite/`、`/@fs/` 或 HMR WebSocket，也不会依赖开发电脑、HBuilderX 进程或本地 Keep-Alive 状态。

## 当前迁移状态

- 旧 `frontend` Profile 仍将 `niko000o.site` 回源到 `https://localhost:3000`，仅作为迁移期回滚通道。
- 新 `frontend-dev` Profile 固定使用 `dev.niko000o.site`。独立 Tunnel ID `16698f57-7037-4252-adfe-4cc1319bf55c` 由 `scripts/cloudflare/windows-legacy-tunnel/start-cloudflare-frontend-dev.bat` 注入，禁止与旧前端 Tunnel 共用同一个 Tunnel ID；凭据 JSON 仍只保存在用户目录，不进入仓库。
- `api` Profile 和 `api.niko000o.site` 不变。
- Cloudflare 临时 Configuration Rule `52a5bd8bfb6f499d854e43fdaed8ddd4` 只对根域名的 Vite 开发资源关闭 Browser Integrity Check。生产切到 Pages 并完成观察后应删除该规则。

## 第一阶段：生成 H5 生产文件

在 HBuilderX 中使用“发行 -> 网站-H5 手机版”生成生产包。预期输出目录：

```text
fornted\unpackage\dist\build\h5
```

发布目录必须包含 `index.html`、业务静态资源以及由 `fornted/public` 复制得到的 `_headers` 和 `_redirects`。`_redirects` 负责把直接访问的前端路由回写到 `index.html`。禁止把 `fornted` 源码目录、`.vue` 源文件、`node_modules`、本地证书或环境变量文件上传到 Pages。

构建属于第二阶段验证操作。执行前必须获得用户确认，且只使用 HBuilderX 生产发行，不连接生产数据库、Redis 或 RabbitMQ。

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

完整登录预览需要把实际 `*.pages.dev` 主机名精确加入后端 CORS 与 Turnstile 允许列表。禁止使用 `*` 配合凭据请求；完成预览后应移除临时预览域名。

## 第三阶段：生产域名切换

预览通过后，在 Pages 项目中添加自定义域名：

```text
niko000o.site
```

切换前记录旧根域名 Tunnel DNS 路由和当前 Tunnel ID。Pages 自定义域名生效后，确认根域名只指向 Pages，不再同时指向旧 Tunnel。

生产验收：

1. 新建普通 Chrome、Edge 或无痕会话均能打开登录页。
2. 页面资源哈希与 Pages 预览一致。
3. `GET https://api.niko000o.site/api/auth/csrf` 返回预期状态并签发 `XSRF-TOKEN`。
4. Cookie 的 Domain、Secure、SameSite 与当前跨子域认证设计一致。
5. API Tunnel 的请求量随操作增长，前端旧 Tunnel 不再收到生产页面流量。

## 第四阶段：收口安全例外

生产观察稳定后：

1. 停止旧 `frontend` Profile，但暂时保留 Tunnel 凭据和脚本。
2. 删除临时 Configuration Rule `52a5bd8bfb6f499d854e43fdaed8ddd4`。Pages 生产包不需要 Vite 源码例外。
3. 如需远程开发，单独创建 `dev.niko000o.site` DNS/Tunnel 路由并启用 Cloudflare Access；否则不要公开该子域名。
4. API Tunnel、API 域名和后端 Cookie 策略保持不变。

## 回滚

如果 Pages 切换后出现严重故障：

1. 把 `niko000o.site` 恢复到切换前记录的旧 frontend Tunnel 路由。
2. 启动 `scripts/cloudflare/windows-legacy-tunnel/start-cloudflare-frontend.bat`，确认它仍使用旧 `CF_FRONTEND_TUNNEL_ID`。
3. 必要时重新启用临时 Browser Integrity Check 例外规则。
4. 不回滚 `api.niko000o.site`，除非证据表明 API Tunnel 本身故障。

回滚后重新收集浏览器 Network、两个 Tunnel 指标和同一时间段日志，再决定下一次切换。
