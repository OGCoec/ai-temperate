#!/usr/bin/env bash
set -euo pipefail

WINDOWS_WIREGUARD_ADDRESS="10.66.0.2"
ORIGIN_CA="/etc/cloudflared/certs/local-https.pem"

probe_tcp() {
  local port="$1"
  if timeout 4 bash -c "</dev/tcp/${WINDOWS_WIREGUARD_ADDRESS}/${port}" 2>/dev/null; then
    echo "TCP ${port}: reachable"
    return 0
  fi
  echo "TCP ${port}: blocked-or-closed"
  return 1
}

verify_origin_tls() {
  local port="$1"
  local status
  if status="$(curl \
      --silent \
      --show-error \
      --output /dev/null \
      --write-out '%{http_code}' \
      --connect-timeout 5 \
      --max-time 10 \
      --cacert "${ORIGIN_CA}" \
      --resolve "localhost:${port}:${WINDOWS_WIREGUARD_ADDRESS}" \
      "https://localhost:${port}/")"; then
    echo "HTTPS ${port}: TLS verified, HTTP ${status}"
    return 0
  fi
  echo "HTTPS ${port}: unavailable"
  return 1
}

echo "WireGuard service: $(systemctl is-active wg-quick@wg0.service)"
echo "WireGuard address: $(ip -o -4 address show dev wg0 | awk '{print $4}')"

allowed_failure=0
for port in 3000 3001 6655; do
  if ! probe_tcp "${port}"; then
    allowed_failure=1
  fi
done

verify_origin_tls 3000 || allowed_failure=1
verify_origin_tls 6655 || allowed_failure=1

blocked_failure=0
for port in 5431 6378 5673 11111; do
  if probe_tcp "${port}"; then
    echo "SECURITY_FAILURE: TCP ${port} must not be reachable." >&2
    blocked_failure=1
  fi
done

if [[ "${allowed_failure}" -ne 0 || "${blocked_failure}" -ne 0 ]]; then
  exit 1
fi

echo "WireGuard origin allowlist verification passed."
