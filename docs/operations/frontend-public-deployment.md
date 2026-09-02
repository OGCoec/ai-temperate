# 主站 H5 公网发布、全路径收口与回滚手册

## 稳定架构

普通主站的唯一公网入口是现有 `cloudflare/api-gateway` Worker。Cloudflare Pages 只保存已经验收的生产静态文件，不直接承担主站路由判定：

```text
浏览器 / Android
  -> https://niko000o.site/**
  -> Cloudflare Worker
     -> 精确 H5 页面：读取指定 Pages 部署的 /index.html
     -> 清单内静态文件：读取指定 Pages 部署的同路径文件
     -> 精确 API / SSE / WebSocket：签名后转发 api.niko000o.site
     -> 已知路径的方法错误：405 + Allow，不访问上游
     -> 未知或非法路径：纯文本 404，不访问 Pages 或 Java

管理员 H5 API
  -> https://admin.niko000o.site/api/admin/**
  -> 同一个 Cloudflare Worker
  -> https://api.niko000o.site
```

`api.niko000o.site` 仅作为 Worker 的 Java 回源地址。生产 H5 与 Android 都使用主域名；普通 H5 通过 Host-only Cookie 认证，Android 使用显式 Header Token。`/ws/voice` 仍使用单次语音 Ticket，Worker 回源前删除 Cookie、Authorization 和浏览器元数据。

本手册只调整 `niko000o.site`。管理员页面域名和开发域名不在本次全路径白名单的迁移范围内。

## 路由和发布不变量

- `cloudflare/api-gateway/wrangler.jsonc` 必须让 `niko000o.site/*` 全量经过 Worker。
- H5 页面只能来自 `main-site-policy.js` 的精确页面集合；该集合必须与 `fornted/pages.json` 一致。
- `/index.html`、未知 `/pages/**`、尾斜杠、重复斜杠、编码分隔符、`.vue`、Vite/HMR、`/hybrid/**` 和 `/uni_modules/**` 不公开。
- `/assets/**` 和 `/static/**` 不是前缀白名单；只有 `generated/h5-assets.js` 中的精确生产文件可以回源 Pages。
- `_redirects` 禁止出现 `/* /index.html 200`。合法深层页面只由 Worker 内部读取 `/index.html`。
- Worker API 策略必须使用“方法 + 精确路径或有界参数模板”，禁止恢复 `/api/auth/**`、`/api/users/**` 等族级兜底。
- Java 生产环境最终必须使用 `EDGE_PROXY_MODE=REQUIRED`。Worker 签名过滤先于 Controller 和业务访问；Spring Controller 映射与统一 404/405 是第二道防线。生产环境仍处于 OPTIONAL 或 DISABLED 时，应用启动期会记录 `security_edge_proxy_mode_not_required` ERROR，切换窗口结束后不得忽略该错误。
- 未知 H5/静态路径返回纯文本 404；普通 `/api/**` 返回 JSON；`/v1/**` 返回 OpenAI 风格 JSON。所有拒绝响应禁止缓存。

## 当前迁移边界

- 旧 `frontend` Tunnel 仅保留为紧急回滚记录，不作为主站稳定入口。
- `frontend-dev` Profile 只用于受保护的 `dev.niko000o.site`；不得把 Vite/HBuilderX 开发服务重新接到主域名。
- `api` Profile 和 `api.niko000o.site` 保持 Java 回源职责，生产客户端不得直连。
- Worker 源码位于 `cloudflare/api-gateway`；Pages 项目建议保持 `ai-temperate-frontend`。
- Worker 和 Java 必须配置同一份独立 `EDGE_PROXY_HMAC_SECRET_BASE64`，不得写入仓库或日志。

## 第一阶段：生成生产 H5 与精确资源清单

在 `fornted` 目录使用唯一的生产构建命令。该命令调用项目本地锁定版本的 uni-app 编译器，固定使用 `h5` 目标，并固定输出到：

```text
fornted\unpackage\dist\build\h5
```

`fornted\unpackage\dist\build\web` 是 HBuilderX 的旧自动输出目录，不得用于生成边缘资源清单、发布检查或 Pages 上传。

发行目录只包含 `index.html`、生产资源、`_headers` 和不带 SPA 通配回退的 `_redirects`。禁止上传源码目录、`.vue`、source map、测试文件、`node_modules`、本地证书或环境变量文件。构建产物中的 `/hybrid/**` 和 `/uni_modules/**` 不进入 Worker 资源清单，主域名对这些路径固定返回 404。

生产构建完成后，在 `fornted` 目录依次生成并核对边缘资源清单：

```powershell
npm run build:h5
npm run generate:h5-edge-assets
npm run verify:esm-contracts
npm run test:models
npm run test:release
npm run verify:h5-release -- --dir unpackage\dist\build\h5
```

`generate:h5-edge-assets` 扫描实际生产目录中的 `/assets/**` 与 `/static/**`，生成 Worker 可导入的精确文件集合。每次 H5 构建结果变化都必须重新生成并随 Worker 版本提交。`verify:h5-release` 会检查清单漂移、全局 SPA 回退、开发模块、源文件和安全响应头。

上述构建和测试属于验证阶段，必须在获得当前任务的明确批准后执行。

## 第二阶段：Pages Direct Upload

将完整的 `fornted\unpackage\dist\build\h5` Direct Upload 到 `ai-temperate-frontend`。先记录本次不可变部署地址，再更新 Worker 的 `H5_PAGES_ORIGIN`；该地址必须是 HTTPS `*.pages.dev` 源站，不能指回 `niko000o.site`，否则会形成回源循环。

从仓库根目录执行上传时，目录参数必须保持为完整 H5 产物目录：

```powershell
Set-Location cloudflare\api-gateway
npx wrangler pages deploy ..\..\fornted\unpackage\dist\build\h5 --project-name=ai-temperate-frontend
```

禁止上传 `build\web`，也禁止把旧部署中的单个 JS 文件复制进新目录。

上传完成后，必须使用命令输出中的不可变部署地址逐项检查当前清单内的资源；只有所有 JS、CSS、字体、
图片和静态文件都返回 200，且静态资源没有错误回退成 HTML，才能修改 Worker：

```powershell
Set-Location ..\..\fornted
npm run verify:h5-pages-deployment -- --origin https://<deployment>.ai-temperate-frontend.pages.dev
```

该命令拒绝可变的 `https://ai-temperate-frontend.pages.dev` 项目地址。任一哈希资源 404、重定向或返回
HTML 都会以非零状态退出，此时禁止更新 `H5_PAGES_ORIGIN` 或部署 Worker。

Pages 独立地址只验收静态原点：

1. 规范入口 `/` 返回生产 HTML 且不长期缓存；Pages 将 `/index.html` 308 到 `/` 属于正常行为。
2. 清单内 JS、CSS、字体和图片正常返回。
3. 不存在文件自然返回 404，不回退到 HTML。
4. 构建产物不请求 `.vue`、`/@vite/`、`/@fs/`、HMR 或源码模块。
5. Pages 深层 H5 页面不负责 SPA 回退；`/pages/auth/login` 等只在主域名经过 Worker 时返回应用壳。

不要为了 Pages 预览登录临时放宽 CORS、Cookie Domain 或认证边界。

## 第三阶段：发布 Worker 策略

1. 确认 Worker Secret 与 Java 使用相同的 `EDGE_PROXY_HMAC_SECRET_BASE64`。
2. 将 `H5_PAGES_ORIGIN` 更新为刚验收的 Pages 部署源站。
3. 发布包含本版页面白名单、API 策略和资源清单的 Worker，但暂不修改 Java 的 REQUIRED 状态。
4. 确认 `niko000o.site/*` 全量路由到 Worker；管理员域名路由保持原状。
5. 验证 H5、Android、SSE、API Key `/v1/**`、Clearance 和 `/ws/voice`。
6. 验证未知页面、未知 API 和未知静态资源的上游访问次数为零。

Worker 发布和 Cloudflare Route 修改都是外部状态操作，必须取得明确批准后执行。

## 第四阶段：切换主域名并收紧 Java

主域名切换后执行以下生产验收：

1. `GET /pages/auth/login` 返回 200，登录页和认证流程正常。
2. `GET /shopping/user/login` 返回 `text/plain` 404；Network 中没有 `index.html`、JS、CSS、`pre-auth`、用户信息、Turnstile 或 WebRTC 请求。
3. `/assets/not-found.js` 不访问 Pages；未知 `/api/auth/**` 不访问 Java。
4. 已知路径使用错误方法时返回 405 和正确 `Allow`。
5. H5、Android、SSE、API Key `/v1/models`、`/v1/chat/completions` 和 `/ws/voice` 均保持正常。
6. Worker 拒绝日志只包含 Ray ID、方法、Host、低基数分类、状态和是否回源，不包含查询参数、Cookie、Token 或完整动态 ID。

全部客户端通过后，最后将 Java 生产环境设置为：

```text
EDGE_PROXY_MODE=REQUIRED
```

然后验证：

- 主域名合法 API 经 Worker 仍成功。
- 直接访问 `api.niko000o.site/api/**`、`/v1/**` 或 `/ws/voice` 因缺少有效边缘签名返回 403。
- 携带合法 Worker 签名但 Java Controller 不存在的路径返回统一 JSON 404。
- 已签名已知路径的方法错误返回统一 JSON 405 和 `Allow`。
- 未知路径不进入 Controller、Service、Mapper、Redis、PostgreSQL 或外部服务。

## 后续前端版本切换

为避免旧浏览器引用的新旧资源交叉：

1. 先上传新版 Pages 并保留唯一部署地址。
2. 从新版产物生成新版资源清单。
3. 过渡 Worker 同时保留当前版和新版资源到各自 Pages 部署的映射。
4. 将新页面请求切换到新版 `/index.html`。
5. 经过约定观察窗口后删除旧资源清单和旧 Pages 映射。

禁止先删除旧哈希资源再切换页面，也禁止用恢复永久 `/* /index.html 200` 解决版本问题。

## 回滚

每次发布前记录上一个 Worker Version、Pages Deployment、页面/API/资源清单和旧 Tunnel DNS 信息。

若新策略误拦合法流量：

1. 优先回滚 Worker 到上一版本。
2. 将页面源站恢复为上一 Pages Deployment。
3. 不恢复永久 SPA 全局回退。
4. 若 Java 已切到 REQUIRED，保持 Worker 签名链路；只有必须临时恢复旧 Tunnel 时才先评估并显式切换兼容模式。
5. 临时恢复旧 Tunnel 后要标记为回滚状态，并在问题解决后重新关闭开发资源例外。

## 观察指标

Worker 拒绝分类保持低基数：

```text
H5_ROUTE_NOT_FOUND
H5_PATH_UNSAFE
STATIC_ASSET_NOT_FOUND
API_ROUTE_NOT_FOUND
METHOD_NOT_ALLOWED
API_PARAMETER_INVALID
```

验收标准是非法路径 Pages 回源为零、非法 API Java 回源为零，且合法 H5、Android、SSE 和 WebSocket 成功率不下降。完整原始路径、查询参数、Token、Cookie、CSRF 和动态资源 ID 不得作为指标标签。

## Cloudflare 参考

- [Workers Custom Domains](https://developers.cloudflare.com/workers/configuration/routing/custom-domains/)
- [Workers Routes](https://developers.cloudflare.com/workers/configuration/routing/routes/)
- [Pages Redirects](https://developers.cloudflare.com/pages/configuration/redirects/)
- [Pages Direct Upload](https://developers.cloudflare.com/pages/get-started/direct-upload/)
