# ADR：管理员前端采用持久化工作台与平台历史适配器

- 状态：已接受
- 日期：2026-07-29
- 范围：`myuniappadmin`

> 2026-07-31 起，工作台外部 URL 的 Query 表示已由
> `adr-2026-07-31-admin-workspace-resource-paths.md` 替代；本 ADR 的单一 Shell 和平台历史决策继续有效。

## 背景

原管理员 H5 为每项业务建立独立 uni-app 页面。每次同级切换都会重新创建页面 Shell、再次验证会话并按需加载页面模块。公网环境还曾把 Vite 开发模块直接暴露给浏览器，导致第一次进入模型、图标、IP 凭据或邮件检查时出现白屏和明显延迟。邮件页面同时拥有侧栏和右侧一级业务标签，造成重复导航。

H5 需要浏览器前进后退，Android 需要抽屉、系统返回和安全区域适配。两端仍必须共享业务页面、API 封装、SSE 状态机和敏感数据保护逻辑。

## 决策

新增唯一正式路由 `/pages/admin/workspace`。工作台静态导入全部业务面板，只挂载一次 `AdminPageShell`，同级切换只更换右侧面板。旧 URL 作为无业务请求的兼容入口，使用一次性 replace 跳转到规范 URL。

路由只允许固定 `view`、IP2Location `mode` 和模型 `publicId`。H5 History 与 Android 内部历史分别由适配器实现，平台无关的返回顺序由工作台控制器维护。业务面板通过 `beforeWorkspaceLeave`、`onWorkspaceActivated` 和 `onWorkspaceDeactivated` 接入，不直接操作 uni 页面栈。

会话验证采用并发 Promise 合并和 30 秒内存有效期。首次进入显示持久深色 Shell 与骨架；只有受控 401 才返回登录页，临时网络故障保留 Shell 并允许重试。

## 结果

优点：

- 侧栏、背景和工作台上下文在业务切换中保持稳定。
- 首次进入工作台后不再请求新的 `.vue` 或页面路由模块。
- H5 和 Android 共享同一业务面板，只有历史与返回行为按平台适配。
- 邮件一级导航只有一个来源，IP2Location 二级模式继续保留。

代价与风险：

- 工作台初始生产包会包含所有管理员业务面板，首包体积可能增加。
- 面板激活/停用钩子必须正确释放 SSE、弹层和敏感临时状态。
- 浏览器 History 与 uni-app 页面栈并存，需要通过契约测试防止返回循环。

不采用 Pinia、Vue Router、第三方动画库、H5/Android 两套业务页面或后端协议调整。

## 安全边界

URL 不保存 Token、邮箱凭证、任务结果或 SSE 数据。前端 30 秒验证时间只减少重复 bootstrap，不能替代后端认证；任何业务 API 的受控 401 仍立即清理会话并回到登录入口。

## 回滚

前端契约必须整体回滚到上一份已知正常的管理员 Pages 部署。旧 URL 仍保留，因此 DNS/Pages 回滚不需要修改后端 API。若 Pages 不可用，则恢复切换前记录的管理员 Tunnel/DNS；Redis、RabbitMQ、SSE 和 Worker API Route 保持不变。
