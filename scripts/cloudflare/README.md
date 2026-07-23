# Cloudflare Tunnel 本地脚本

## Azure Connector + WireGuard 回源

四条命名 Tunnel 正在迁移到 Azure Connector。Azure 上的 `cloudflared` 直接连接 Cloudflare Edge，再通过 WireGuard 私网回源到本机，避免 Windows Mihomo/Xray 链路影响 Connector 的 Edge TLS 握手。

本机一次性初始化入口：

```text
setup-azure-wireguard-origin.bat
```

该入口会请求管理员权限、安装官方 WireGuard，并且只建立以下固定转发：

```text
10.66.0.2:3000 -> 127.0.0.1:3000
10.66.0.2:3001 -> 127.0.0.1:3001
10.66.0.2:6655 -> 127.0.0.1:6655
```

禁止把 PostgreSQL、Redis、RabbitMQ 或任意端口范围加入该转发。`scripts/cloudflare/configure-wireguard-origin-forwarding.ps1` 的允许列表固定为 `3000,3001,6655`，不接受调用方传入其他端口。

Azure 安装脚本位于 `scripts/cloudflare/azure/`：

- `install-cloudflared-connectors.sh`：安装四份独立配置和 systemd 模板，校验 ingress，但不会自动启动 Connector。
- `configure-azure-wireguard.sh`：接收 Windows 客户端公钥并配置 `10.66.0.1/30`，不会输出或复制私钥。

Azure Connector 尚未逐条验证并完成切换前，必须保留下面的 Windows Tunnel 脚本和用户目录凭据作为回滚通道。

## 入口职责

- `scripts/cloudflare/windows-legacy-tunnel/start-cloudflare-frontend-dev.bat` / `scripts/cloudflare/windows-legacy-tunnel/stop-cloudflare-frontend-dev.bat`：操作 `dev.niko000o.site` 的开发 Tunnel，默认回源 `https://localhost:3000`。
- `scripts/cloudflare/windows-legacy-tunnel/start-cloudflare-api.bat` / `scripts/cloudflare/windows-legacy-tunnel/stop-cloudflare-api.bat`：操作 `api.niko000o.site` 的 API Tunnel，默认回源 `https://localhost:6655`。
- `scripts/cloudflare/windows-legacy-tunnel/start-cloudflare-frontend.bat` / `scripts/cloudflare/windows-legacy-tunnel/stop-cloudflare-frontend.bat`：迁移期根域名旧 Tunnel 回滚入口，不作为日常启动方式。
- `scripts/cloudflare/windows-legacy-tunnel/start-cloudflare.bat` / `scripts/cloudflare/windows-legacy-tunnel/stop-cloudflare.bat`：通用入口，可选择 `frontend`、`frontend-dev`、`admin` 或 `api`。
- `scripts/cloudflare/windows-legacy-tunnel/start-cloudflare.ps1`：读取 Profile、协议和端口，生成对应 YAML 并启动守护脚本。
- `scripts/cloudflare/windows-legacy-tunnel/update-cloudflare-config.ps1`：生成专用 YAML，并使用 `cloudflared tunnel ingress validate` 校验。
- `scripts/cloudflare/windows-legacy-tunnel/cloudflare-ip-guard.ps1`：运行 `cloudflared`、记录 PID、显示日志并监控出口地址变化。
- `scripts/cloudflare/windows-legacy-tunnel/stop-cloudflare.ps1`：按 Profile 核对 PID、进程名、可执行文件和启动时间后停止对应 Tunnel。

生产 H5 的目标入口是 Cloudflare Pages，不是本地 Tunnel。完整迁移步骤见 `docs/operations/frontend-public-deployment.md`。

## 环境变量

三个公网入口使用各自独立的 Tunnel ID：

```text
CF_FRONTEND_TUNNEL_ID=<根域名旧 Tunnel UUID，仅供迁移回滚>
CF_API_TUNNEL_ID=<api.niko000o.site 独立 Tunnel UUID>
```

`scripts/cloudflare/windows-legacy-tunnel/start-cloudflare-frontend-dev.bat` 已在自身进程内注入独立开发 Tunnel ID `16698f57-7037-4252-adfe-4cc1319bf55c`，双击时不需要再配置 Windows 用户环境变量。该 ID 不是凭据；真正的凭据仍只保存在用户目录。`frontend-dev` 不回退读取 `CF_FRONTEND_TUNNEL_ID`，防止两组不同 ingress 配置连接到同一个 Tunnel 后产生随机回源。每个 Tunnel 必须有对应凭据：

```text
%USERPROFILE%\.cloudflared\<tunnel-id>.json
```

## 启动

本地 HBuilderX 不需要 Tunnel，直接使用 `https://localhost:3000`。只有已经创建独立开发 Tunnel、DNS 路由和 Cloudflare Access 时，才启动：

```powershell
.\scripts\cloudflare\windows-legacy-tunnel\start-cloudflare-frontend-dev.bat -Protocol https -Port 3000
```

启动 API Tunnel：

```powershell
.\scripts\cloudflare\windows-legacy-tunnel\start-cloudflare-api.bat -Protocol https -Port 6655
```

协议只允许 `http` 或 `https`，端口必须在 `1..65535` 范围内。

## 显式代理

启动脚本只给子进程 `cloudflared.exe` 注入代理环境变量，不修改 Windows 全局代理。默认配置：

```text
代理：http://127.0.0.1:7897
本地回源直连：localhost,127.0.0.1,::1
```

显式覆盖：

```powershell
.\scripts\cloudflare\windows-legacy-tunnel\start-cloudflare-frontend-dev.bat -ProxyUrl http://127.0.0.1:7897
.\scripts\cloudflare\windows-legacy-tunnel\start-cloudflare-api.bat -ProxyUrl http://127.0.0.1:7897
```

临时回到 Mihomo/TUN 自动捕获模式：

```powershell
.\scripts\cloudflare\windows-legacy-tunnel\start-cloudflare-frontend-dev.bat -DisableExplicitProxy
.\scripts\cloudflare\windows-legacy-tunnel\start-cloudflare-api.bat -DisableExplicitProxy
```

## 生成文件与路由

脚本按 Profile 生成：

```text
%USERPROFILE%\.cloudflared\ai-temperate-frontend.yml
%USERPROFILE%\.cloudflared\ai-temperate-frontend-dev.yml
%USERPROFILE%\.cloudflared\ai-temperate-api.yml
```

路由职责：

```text
niko000o.site     -> https://localhost:3000  迁移期回滚
dev.niko000o.site -> https://localhost:3000  可选开发入口
api.niko000o.site -> https://localhost:6655  API 入口
```

HTTPS 回源使用 `%USERPROFILE%\.ai-temperate\certs\local-https.pem`，并在 YAML 中配置 `originServerName: localhost`、`caPool` 和 `noTLSVerify: false`。

## 停止与状态文件

```powershell
.\scripts\cloudflare\windows-legacy-tunnel\stop-cloudflare-frontend-dev.bat
.\scripts\cloudflare\windows-legacy-tunnel\stop-cloudflare-api.bat
```

迁移回滚入口仅在确有需要时使用：

```powershell
.\scripts\cloudflare\windows-legacy-tunnel\start-cloudflare-frontend.bat
.\scripts\cloudflare\windows-legacy-tunnel\stop-cloudflare-frontend.bat
```

运行状态分别保存：

```text
%LOCALAPPDATA%\ai-temperate\cloudflare\cloudflared-frontend.pid.json
%LOCALAPPDATA%\ai-temperate\cloudflare\cloudflared-frontend-dev.pid.json
%LOCALAPPDATA%\ai-temperate\cloudflare\cloudflared-api.pid.json
```

关闭一个 Profile 不影响其他 Profile。直接关闭启动窗口时，Windows Job Object 会让该窗口启动的 `cloudflared.exe` 跟随退出。
