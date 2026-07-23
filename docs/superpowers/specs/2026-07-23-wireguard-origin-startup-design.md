# WireGuard Origin 开机自动恢复设计

## 目标

让 Windows 在每次开机后自动等待 `ait-origin` WireGuard 网卡就绪，再应用现有的 TCP PortProxy 和最小防火墙白名单。用户不需要手动运行 `-Action Apply`，Cloudflare Connector 也不需要改成 Windows 本地进程。

## 方案

新增两个 PowerShell 脚本：

- `ensure-wireguard-origin-forwarding.ps1`：等待 `WireGuardTunnel$ait-origin` 服务处于运行状态，并确认 `10.66.0.2` 已绑定到 `ait-origin`；就绪后调用现有 `configure-wireguard-origin-forwarding.ps1 -Action Apply`。
- `register-wireguard-origin-startup.ps1`：以管理员权限注册一个由 `SYSTEM` 账户、最高权限运行的 Windows 开机计划任务。任务只执行上述等待脚本，并设置有限执行时间和失败重试。

现有 `setup-azure-wireguard-origin.bat` 在完成一次性安装和初始 Apply 后调用注册脚本。重复运行安装入口只会幂等更新任务，不会扩大端口白名单。

## 安全边界

- 只允许既有的 TCP `3000`、`3001`、`6655`。
- 不关闭 Windows 防火墙，不开放 UDP，不创建默认路由，不读取或输出 WireGuard 私钥。
- 任务使用 `SYSTEM` 是为了在无人登录时仍能修改系统级 PortProxy 和防火墙规则；它不向网络暴露新的管理入口。

## 失败处理与可观测性

等待超过限定时间时任务失败并写入 `C:\ProgramData\ai-temperate\wireguard\origin-forwarding-startup.log`，不执行部分配置。任务设置有限重试，避免无限循环或阻塞开机。

## 验收标准

开机任务运行后，以下地址应处于监听状态：

```text
10.66.0.2:3000
10.66.0.2:3001
10.66.0.2:6655
```

Azure 通过 `10.66.0.2:3000` 能够完成 HTTPS 回源；不需要用户手动打开 PowerShell 或重新运行 BAT。
