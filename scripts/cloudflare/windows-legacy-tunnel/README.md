# Windows legacy Cloudflare Tunnel

此目录保存 Windows 本机直接连接 Cloudflare Edge 的旧 Connector 启停脚本，仅用于 Azure Connector 故障时的回滚。

日常架构为 Azure 上的 `cloudflared` 通过 WireGuard 回源 Windows；不要在日常开发中启动本目录脚本，否则会重新让本机 Mihomo/Xray 出口参与 `cloudflared -> Cloudflare Edge` 链路。

保留这些文件不代表它们正在运行，也不包含任何 Tunnel 凭据。凭据仍保存在用户目录的 `.cloudflared` 中，不能移动或提交到项目。

若明确需要回滚，先停止 Azure 上同一 Profile 的 Connector，再从本目录执行对应的 `start-cloudflare-*.bat`。恢复 Azure Connector 后，应执行对应的 `stop-cloudflare-*.bat`。
