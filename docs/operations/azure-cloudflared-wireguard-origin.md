# Azure cloudflared 与 WireGuard 回源运维说明

## 目标架构

Azure VPS 上的四个独立 `cloudflared` 实例直接连接 Cloudflare Edge。Windows 继续运行 Vite、管理端和 Spring，Azure 仅通过 WireGuard `10.66.0.0/30` 访问三个登记的 HTTPS Origin。

```text
Cloudflare Edge
  -> Azure cloudflared
  -> WireGuard 10.66.0.1/30 <-> 10.66.0.2/30
  -> Windows TCP Port Proxy
  -> 127.0.0.1:3000 / 3001 / 6655
```

该方案隔离的是 `cloudflared -> Cloudflare Edge` 链路。浏览器访问 `challenges.cloudflare.com` 的 Turnstile 链路仍受浏览器网络、代理节点和 Cloudflare 风控影响，不属于本迁移的保证范围。

## 安全边界

Windows WireGuard Peer 只允许 `10.66.0.1/32`，Azure Peer 只允许 `10.66.0.2/32`。双方均不设置 DNS 或默认路由，不接管其他 Windows 流量。

允许 Azure 回源的端口只有：

| 端口 | 用途 |
| ---: | --- |
| 3000 | H5 与开发前端 |
| 3001 | 管理端 |
| 6655 | Spring API |

PostgreSQL `5431`、Redis `6378`、RabbitMQ `5673/11111` 以及其他端口不得建立 Port Proxy、NSG 或防火墙放行。

Origin 保持 HTTPS。Azure 使用 `originServerName: localhost` 和公开 PEM 校验证书，`noTLSVerify` 固定为 `false`。Windows 上的证书私钥、PKCS12 和密码不得复制到 Azure。

## 本机初始化

双击仓库根目录的 `setup-azure-wireguard-origin.bat` 并确认 UAC。脚本将：

1. 通过 winget 安装官方 WireGuard。
2. 在 Windows 本地生成客户端私钥，并保存到仅允许 `SYSTEM/Administrators` 读取的固定服务配置中。
3. 创建 `ait-origin` Tunnel，地址为 `10.66.0.2/30`。
4. 只创建 3000、3001、6655 三条 Port Proxy。
5. 创建只允许来源 `10.66.0.1`、目标 `10.66.0.2` 的 Windows 防火墙规则：允许 TCP 3000、3001、6655，同时阻断该来源到其余 TCP 端口和全部 UDP 端口。补集阻断只绑定 `ait-origin` 接口，不影响 localhost、LAN 或 Mihomo 的其他网卡。

安全的客户端公钥写入：

```text
C:\ProgramData\ai-temperate\wireguard\ait-origin.pub
```

禁止复制同目录的 `.conf` 或任何私钥。官方 Tunnel Service 会持续读取该固定配置，因此安装脚本不会删除它；脚本使用受保护 ACL 阻止普通用户和其他进程读取，公开的 `.pub` 文件不包含私钥。

## Azure Connector 管理

四个实例名称固定为：

```text
cloudflared-ai-temperate@frontend.service
cloudflared-ai-temperate@frontend-dev.service
cloudflared-ai-temperate@admin.service
cloudflared-ai-temperate@api.service
```

每条 Connector 必须在对应 Windows Origin 可访问后逐条启动。示例：

```bash
sudo systemctl enable --now cloudflared-ai-temperate@frontend.service
sudo systemctl status cloudflared-ai-temperate@frontend.service
```

确认公网主机名、静态资源、HMR 或 API 均正常后，才停止同一 Tunnel 的 Windows Connector。不得一次启动四条后直接停止所有 Windows Connector。

## 回滚

单条 Tunnel 回滚顺序：

1. 在 Azure 停止对应的 `cloudflared-ai-temperate@<profile>.service`。
2. 在 Windows 启动 `scripts/cloudflare/windows-legacy-tunnel/` 中对应的 `start-cloudflare-*.bat`。
3. 保持现有 Tunnel ID 和 DNS，不创建新 Tunnel，不改 WAF。

Azure 迁移稳定观察至少七天前，不删除 Windows 的 `%USERPROFILE%\.cloudflared` 配置、凭据和启动脚本。四条 Tunnel 未全部确认前，也不得删除 WireGuard、Port Proxy 或防火墙白名单。

## 故障语义

- WireGuard 断开而 Azure Connector 仍在线：公网通常表现为 Origin 不可达或 502，不应出现 Edge TLS EOF。
- Azure Connector 日志出现 `198.18.0.0/16`：说明连接仍被 Fake-IP/透明代理接管，迁移不合格。
- Azure Connector 出现 `x509`：检查公开 PEM、`originServerName: localhost` 和 Windows Origin 证书，不得用 `noTLSVerify: true` 绕过。
- 管理端 3001 未运行：只影响 `admin` Origin，不能据此判定 Tunnel 或 WireGuard 故障。
