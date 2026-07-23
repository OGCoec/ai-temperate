#!/usr/bin/env bash
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "This script must run as root." >&2
  exit 1
fi

if [[ "$#" -ne 1 ]]; then
  echo "Usage: $0 <windows-client-public-key>" >&2
  exit 1
fi

CLIENT_PUBLIC_KEY="$1"
SERVER_PRIVATE_KEY_FILE="/etc/wireguard/ait-origin.key"
WG_CONFIG_FILE="/etc/wireguard/wg0.conf"

decoded_length="$({ printf '%s' "${CLIENT_PUBLIC_KEY}" | base64 --decode 2>/dev/null || true; } | wc -c)"
if [[ "${decoded_length}" -ne 32 ]]; then
  echo "The Windows WireGuard public key is invalid." >&2
  exit 1
fi

if [[ ! -s "${SERVER_PRIVATE_KEY_FILE}" ]]; then
  echo "The Azure WireGuard private key file is missing." >&2
  exit 1
fi

umask 077
temporary_config="$(mktemp)"
server_private_key="$(<"${SERVER_PRIVATE_KEY_FILE}")"

cat >"${temporary_config}" <<EOF
[Interface]
Address = 10.66.0.1/30
ListenPort = 51820
PrivateKey = ${server_private_key}
MTU = 1420

[Peer]
PublicKey = ${CLIENT_PUBLIC_KEY}
AllowedIPs = 10.66.0.2/32
EOF

install -o root -g root -m 0600 "${temporary_config}" "${WG_CONFIG_FILE}"
rm -f -- "${temporary_config}"
unset server_private_key

systemctl enable wg-quick@wg0.service >/dev/null
if systemctl is-active --quiet wg-quick@wg0.service; then
  systemctl restart wg-quick@wg0.service
else
  systemctl start wg-quick@wg0.service
fi

echo "Azure WireGuard interface is configured and active."
