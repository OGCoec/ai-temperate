# AI Temperate HTML 预览沙箱

该目录是与主站分离部署的纯静态 HTML 运行沙箱。它不持有登录态、Cookie、API Token 或业务数据，只通过版本化 `postMessage` 接收最多 1 MiB 的 HTML，并在一次性内层 iframe 中运行。由于内层 `srcdoc` 继承外层 CSP，整个独立预览站点允许 HTTPS 外部资源；该能力不得迁移到主站 Origin。

## 目录用途

- `public/` 是 Cloudflare Pages 的静态输出目录，无构建步骤。
- `scripts/serve-local.mjs` 使用项目现有本地 P12 证书，在 `https://127.0.0.1:4174` 提供本地调试服务。
- `test/` 是协议、运行文档和静态安全头的契约测试。

## 生产边界

生产计划 Origin 为 `https://ai-temperate-html-preview.pages.dev`。若实际 Pages 项目地址变化，必须同时更新主前端的 `AI_HTML_PREVIEW_ORIGIN` 与 `frame-src` 精确白名单，不得改用 `https://*.pages.dev` 通配符。

沙箱应部署为单独的 Cloudflare Pages 项目，输出目录选择 `cloudflare/html-preview-sandbox/public`，不配置 Cookie、Functions、服务绑定或主站 Secret。部署和域名配置属于第二阶段外部操作，不能仅凭源码存在就宣称已经上线。

## 本地和测试

本项目根目录规范要求第一阶段只交付代码，不自动运行测试或启动服务。获得用户对第二阶段范围的明确确认后，才可以使用本地证书环境变量启动沙箱并执行 Node 契约测试。
