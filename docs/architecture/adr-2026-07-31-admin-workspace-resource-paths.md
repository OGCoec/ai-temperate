# ADR：管理员 H5 工作台使用固定页面与 Fragment 子路由

- 状态：已接受
- 日期：2026-07-31
- 范围：`myuniappadmin` H5 路由
- 关联：`adr-2026-07-29-admin-persistent-workspace.md`

## 背景

持久化工作台原先使用 `/pages/admin/workspace?view=...` 表示当前业务面板，并通过
`mode`、`publicId` Query 传递子视图和模型资源标识。该方案保证了 Shell 不卸载，但把资源身份
建模成页面参数，导致浏览器地址缺少清晰的资源层级。

## 决策

保留单一 `AdminPageShell`、业务面板生命周期和 Android 内部 Location 状态机。H5 的 pathname
固定为 UniApp 已注册的 `/pages/admin/workspace`，业务面板写入 Fragment。模型详情固定使用
`/pages/admin/workspace#/ai-models/{publicId}`，模型 `publicId` 继续遵守十一位 Base64URL 契约。

当前已经发布的 `/pages/admin/workspace/**` 深层 pathname 在 UniApp 启动前单向迁移到等价
Fragment，避免框架 Router 接收到未注册页面。启动边界不读取 Query、Cookie 或浏览器持久化存储。
Android 页面导航继续通过一次性内存 Location 进入固定 Workspace，不写入 H5 Fragment。

旧 `view`、`mode`、`publicId` Query 和旧业务页面不再恢复原面板，只进入工作台首页。Query
仍可用于后端列表 API 的分页、筛选和排序，但不参与 Workspace 页面识别。

## 结果

- H5 子页面拥有唯一、可直接刷新和可前进后退的 Fragment 地址，同时 UniApp 始终匹配固定页面。
- Workspace Shell、SSE 停用钩子、脏表单离开确认和 Android 返回顺序保持不变。
- Cloudflare Pages 继续使用现有 `/* /index.html 200` SPA 回退。
- Cloudflare API Worker、DNS、Spring API、数据库和模型 ID 编解码均不修改。

## 被替代的旧决策

本 ADR 替代 2026-07-29 ADR 中“通过 Query 表示工作台位置”的部分，也替代本文件早期使用
未注册资源型 pathname 的方案；持久化工作台、单一 Shell 和平台历史适配器决策继续有效。

## 回滚

回滚前端版本和本 ADR 对应代码即可恢复旧 Query 路由。回滚不修改后端、Worker、数据库、
Redis、RabbitMQ 或现有生产数据。
