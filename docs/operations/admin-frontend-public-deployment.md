# 管理员前端持久化工作台发布与回滚手册

## 适用范围

本文只覆盖 `myuniappadmin` 的 H5 静态生产包和 `admin.niko000o.site`。后端 API、Redis、RabbitMQ、SSE 事件格式及现有 `/api/admin/*` Worker Route 均不在本次变更范围内。

管理员工作台的唯一正式前端入口为：

```text
/pages/admin/workspace?view=<view>&mode=<mode>&publicId=<publicId>
```

旧业务 URL 仍可访问，但页面只执行一次 `redirectTo`，不会在旧入口读取业务数据。

## 生产包来源

必须在 HBuilderX 使用“发行 → 网站-H5 手机版”生成生产包。唯一允许发布的目录是：

```text
myuniappadmin\unpackage\dist\build\h5
```

禁止上传 `myuniappadmin` 源码目录、开发服务器输出、`.vue`、`node_modules`、证书或环境变量文件。`public/_headers` 和 `public/_redirects` 必须由发行流程复制到生产包根目录。

构建、校验和上传均属于第二阶段操作。没有用户单独批准时，不执行 HBuilderX 发行、脚本验证、Cloudflare 上传或域名切换。

## 静态校验

生成生产包后，在仓库根目录运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\verify-admin-h5-production.ps1
```

脚本只读取固定生产目录，不连接 API，也不写入数据库、Redis 或 RabbitMQ。以下任一情况会拒绝发布：

- 缺少 `index.html`、`_headers`、`_redirects` 或 `assets`。
- 包含 `.vue`、`/@vite/`、`/@fs/`、`@vite/client` 或 `pages-json-js`。
- 包含 Vite HMR 探测或本地开发 WebSocket。
- 没有带内容哈希的 JavaScript/CSS 资源。

## Cloudflare Pages 预览

Pages 项目固定命名为：

```text
ai-temperate-admin
```

使用 Direct Upload 上传校验通过的生产目录。首次仅在项目的 `*.pages.dev` 域验证以下静态能力：

1. `index.html`、哈希资源和旧路由回写正常。
2. Network 不出现 `.vue`、Vite、HMR 或新的页面级模块请求。
3. 首次进入工作台立即显示深色 Shell 或结构骨架。
4. 同级切换只出现对应业务 API，不重新下载页面模块。
5. `index.html` 不缓存，`/assets/*` 一年 immutable。

预览域不得为了登录测试而放宽生产 Cookie、CORS、PreAuth 或 WebRTC 风险规则。

## 自定义域切换

切换前记录：

- 当前 `admin.niko000o.site` 的 Tunnel/DNS 目标。
- 当前已知正常的 Pages 部署 ID。
- 待切换 Pages 部署 ID。
- 现有 `/api/admin/*` Worker Route 配置。

将 `admin.niko000o.site` 绑定到已验收的 `ai-temperate-admin` 部署，但保留原有 `/api/admin/*` Worker Route。不要修改 API Origin、Redis、RabbitMQ 或 SSE 配置。

使用外部 Chrome 验证登录、首次进入、工作台切换、浏览器前进后退、直接刷新旧 URL 和 SSE 恢复。根据项目浏览器安全策略，不得使用 Codex 内置浏览器。Android 模拟器和真实设备需要另行批准。

## 生产验收

- 左侧栏、平板轨道或手机顶部栏在业务切换中不卸载。
- 首次加载无白色画布；网络失败时 Shell 保留并显示重试状态。
- OpenAI、Kiro、IP2Location、模型、图标和 IP 凭据都能从规范 URL 直接刷新。
- 浏览器前进后退不形成循环；Android 返回先关闭抽屉和弹层。
- 邮件面板停用时暂停 SSE，恢复时沿用 Job ID 与 `lastRevision`。
- 请求和日志中不出现 Token、邮箱凭证、任务结果或其他敏感 URL 参数。

## 回滚

优先把 `admin.niko000o.site` 指回上一份已知正常的 Pages 部署。如果没有可用 Pages 版本，再恢复切换前记录的 Tunnel/DNS。回滚不修改后端、Redis、RabbitMQ、SSE 或 Worker API 契约。

回滚后保留失败部署及其部署 ID用于诊断，不覆盖或删除生产数据。
