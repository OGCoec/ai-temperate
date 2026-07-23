#!/usr/bin/env bash
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "This script must run as root." >&2
  exit 1
fi

STAGING_DIRECTORY="/home/azureuser/ait-cloudflared-staging"
CONFIG_DIRECTORY="/etc/cloudflared/configs"
CREDENTIAL_DIRECTORY="/etc/cloudflared/credentials"
CERTIFICATE_DIRECTORY="/etc/cloudflared/certs"
SERVICE_FILE="/etc/systemd/system/cloudflared-ai-temperate@.service"
CLOUDFLARED_USER="cloudflared"
CLOUDFLARED_GROUP="cloudflared"

declare -A TUNNEL_IDS=(
  [frontend]="ce87b497-3300-49ae-92cd-25f4f1c543d7"
  [frontend-dev]="16698f57-7037-4252-adfe-4cc1319bf55c"
  [admin]="8f02c584-5d80-4ea7-af06-e33e6054f202"
  [api]="c23df2e4-5cde-4b3a-a387-b9530ee8c209"
)

declare -A HOSTNAMES=(
  [frontend]="niko000o.site"
  [frontend-dev]="dev.niko000o.site"
  [admin]="admin.niko000o.site"
  [api]="api.niko000o.site"
)

declare -A ORIGIN_PORTS=(
  [frontend]="3000"
  [frontend-dev]="3000"
  [admin]="3001"
  [api]="6655"
)

declare -A METRICS_PORTS=(
  [frontend]="20241"
  [frontend-dev]="20242"
  [admin]="20243"
  [api]="20244"
)

if ! command -v cloudflared >/dev/null 2>&1; then
  echo "cloudflared is not installed." >&2
  exit 1
fi

if ! getent group "${CLOUDFLARED_GROUP}" >/dev/null; then
  groupadd --system "${CLOUDFLARED_GROUP}"
fi

if ! id "${CLOUDFLARED_USER}" >/dev/null 2>&1; then
  useradd \
    --system \
    --gid "${CLOUDFLARED_GROUP}" \
    --home-dir /var/lib/cloudflared \
    --create-home \
    --shell /usr/sbin/nologin \
    "${CLOUDFLARED_USER}"
fi

install -d -o root -g "${CLOUDFLARED_GROUP}" -m 0750 /etc/cloudflared
install -d -o root -g "${CLOUDFLARED_GROUP}" -m 0750 "${CONFIG_DIRECTORY}"
install -d -o root -g "${CLOUDFLARED_GROUP}" -m 0750 "${CREDENTIAL_DIRECTORY}"
install -d -o root -g "${CLOUDFLARED_GROUP}" -m 0750 "${CERTIFICATE_DIRECTORY}"

for profile in frontend frontend-dev admin api; do
  tunnel_id="${TUNNEL_IDS[${profile}]}"
  source_path="${STAGING_DIRECTORY}/${tunnel_id}.json"
  destination_path="${CREDENTIAL_DIRECTORY}/${tunnel_id}.json"
  if [[ ! -s "${source_path}" && ! -s "${destination_path}" ]]; then
    echo "Missing credential file for ${profile}." >&2
    exit 1
  fi
  if [[ -s "${source_path}" ]]; then
    install \
      -o "${CLOUDFLARED_USER}" \
      -g "${CLOUDFLARED_GROUP}" \
      -m 0600 \
      "${source_path}" \
      "${destination_path}"
  fi
done

if [[ -s "${STAGING_DIRECTORY}/local-https.pem" ]]; then
  install \
    -o root \
    -g "${CLOUDFLARED_GROUP}" \
    -m 0644 \
    "${STAGING_DIRECTORY}/local-https.pem" \
    "${CERTIFICATE_DIRECTORY}/local-https.pem"
elif [[ ! -s "${CERTIFICATE_DIRECTORY}/local-https.pem" ]]; then
  echo "Missing public origin CA certificate." >&2
  exit 1
fi

write_config() {
  local profile="$1"
  local tunnel_id="${TUNNEL_IDS[${profile}]}"
  local hostname="${HOSTNAMES[${profile}]}"
  local origin_port="${ORIGIN_PORTS[${profile}]}"
  local metrics_port="${METRICS_PORTS[${profile}]}"
  local temporary_file
  temporary_file="$(mktemp)"

  cat >"${temporary_file}" <<EOF
# 指定该实例继续使用现有命名隧道，不创建或合并新的隧道。
tunnel: ${tunnel_id}
# 指定仅供专用 cloudflared 用户读取的隧道凭据文件。
credentials-file: ${CREDENTIAL_DIRECTORY}/${tunnel_id}.json
# 为单个隧道维持四条独立的 Cloudflare Edge 高可用连接。
ha-connections: 4
# 指标端点仅监听 Azure 回环地址，禁止从公网访问。
metrics: 127.0.0.1:${metrics_port}
# 定义该隧道允许处理的公开主机名与最终兜底响应。
ingress:
  # 仅将登记的公开主机名回源到 Windows WireGuard 地址。
  - hostname: ${hostname}
    # 通过加密的 WireGuard 私网访问对应的本地 HTTPS Origin。
    service: https://10.66.0.2:${origin_port}
    # 保持严格的 Origin TLS 证书验证，不允许静默降级。
    originRequest:
      # 证书的 SAN 是 localhost，因此 TLS SNI 与校验名称固定为 localhost。
      originServerName: localhost
      # 仅信任从 Windows 复制过来的公开 PEM 证书。
      caPool: ${CERTIFICATE_DIRECTORY}/local-https.pem
      # 禁止跳过 Origin TLS 证书验证。
      noTLSVerify: false
  # 未匹配的主机名或路径直接返回 404，禁止转发到其他本地端口。
  - service: http_status:404
EOF

  install \
    -o root \
    -g "${CLOUDFLARED_GROUP}" \
    -m 0640 \
    "${temporary_file}" \
    "${CONFIG_DIRECTORY}/${profile}.yml"
  rm -f -- "${temporary_file}"
}

for profile in frontend frontend-dev admin api; do
  write_config "${profile}"
done

temporary_service_file="$(mktemp)"
cat >"${temporary_service_file}" <<'EOF'
[Unit]
Description=ai-temperate Cloudflare Tunnel connector (%i)
Documentation=https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/
Wants=network-online.target
After=network-online.target wg-quick@wg0.service
StartLimitIntervalSec=60
StartLimitBurst=5

[Service]
Type=simple
User=cloudflared
Group=cloudflared
Environment="HTTP_PROXY="
Environment="HTTPS_PROXY="
Environment="ALL_PROXY="
Environment="http_proxy="
Environment="https_proxy="
Environment="all_proxy="
UnsetEnvironment=HTTP_PROXY HTTPS_PROXY ALL_PROXY http_proxy https_proxy all_proxy
ExecStart=/usr/bin/cloudflared --no-autoupdate --config /etc/cloudflared/configs/%i.yml tunnel run
Restart=always
RestartSec=5s
TimeoutStopSec=30s
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
LockPersonality=true
RestrictSUIDSGID=true
RestrictRealtime=true
CapabilityBoundingSet=
AmbientCapabilities=

[Install]
WantedBy=multi-user.target
EOF

install -o root -g root -m 0644 "${temporary_service_file}" "${SERVICE_FILE}"
rm -f -- "${temporary_service_file}"

for profile in frontend frontend-dev admin api; do
  cloudflared --config "${CONFIG_DIRECTORY}/${profile}.yml" tunnel ingress validate >/dev/null
done

systemctl daemon-reload

# Only delete exact staging files after all protected copies and config validations succeed.
for profile in frontend frontend-dev admin api; do
  rm -f -- "${STAGING_DIRECTORY}/${TUNNEL_IDS[${profile}]}.json"
done
rm -f -- "${STAGING_DIRECTORY}/local-https.pem"

echo "Azure cloudflared connector configurations are installed and validated."
echo "No connector service was enabled or started."
