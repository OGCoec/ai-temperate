param(
  [Parameter(Mandatory = $true)]
  [string]$ConfigPath,
  [Parameter(Mandatory = $true)]
  [string]$TunnelId,
  [Parameter(Mandatory = $true)]
  [string]$CredentialsFile,
  [Parameter(Mandatory = $true)]
  [string]$Hostname,
  [Parameter(Mandatory = $true)]
  [ValidateSet("http", "https")]
  [string]$Protocol,
  [Parameter(Mandatory = $true)]
  [ValidateRange(1, 65535)]
  [int]$Port,
  [Parameter(Mandatory = $true)]
  [string]$CloudflaredPath,
  [string]$CertificatePath
)

$ErrorActionPreference = "Stop"

function ConvertTo-YamlSingleQuotedValue {
  param([string]$Value)
  return "'" + $Value.Replace("'", "''") + "'"
}

function ConvertTo-YamlPath {
  param([string]$Path)
  return ConvertTo-YamlSingleQuotedValue -Value ((Resolve-Path -LiteralPath $Path).Path.Replace('\', '/'))
}

if (-not (Test-Path -LiteralPath $CloudflaredPath)) {
  throw "cloudflared executable not found: $CloudflaredPath"
}
if (-not (Test-Path -LiteralPath $CredentialsFile)) {
  throw "Cloudflare credentials file not found: $CredentialsFile"
}

$parsedTunnelId = [guid]::Empty
if (-not [guid]::TryParse($TunnelId.Trim(), [ref]$parsedTunnelId)) {
  throw "TunnelId must be a valid UUID."
}

if ($Hostname -notmatch '^(?=.{1,253}$)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*$') {
  throw "Hostname must be a lowercase DNS hostname."
}

$originRequestLines = @()
if ($Protocol -eq "https") {
  if ([string]::IsNullOrWhiteSpace($CertificatePath) -or -not (Test-Path -LiteralPath $CertificatePath)) {
    throw "HTTPS origin certificate not found: $CertificatePath"
  }
  $originRequestLines = @(
    "    originRequest:",
    "      originServerName: localhost",
    "      caPool: $(ConvertTo-YamlPath -Path $CertificatePath)",
    "      noTLSVerify: false"
  )
}

$serviceValue = "${Protocol}://localhost:${Port}"
$configLines = @(
  "tunnel: $($parsedTunnelId.ToString())",
  "credentials-file: $(ConvertTo-YamlPath -Path $CredentialsFile)",
  "protocol: http2",
  "ha-connections: 2",
  "ingress:",
  "  - hostname: $Hostname",
  "    service: $serviceValue"
) + $originRequestLines + @(
  "  - service: http_status:404"
)

$newText = ($configLines -join [Environment]::NewLine) + [Environment]::NewLine
$newBytes = [System.Text.UTF8Encoding]::new($false).GetBytes($newText)
$configDirectory = Split-Path -Parent $ConfigPath
if (-not [string]::IsNullOrWhiteSpace($configDirectory)) {
  New-Item -ItemType Directory -Force -Path $configDirectory | Out-Null
}

$temporaryPath = $ConfigPath + '.ai-temperate.tmp'
$backupPath = $ConfigPath + '.ai-temperate.bak'
try {
  [System.IO.File]::WriteAllBytes($temporaryPath, $newBytes)
  & $CloudflaredPath tunnel --config $temporaryPath ingress validate
  if ($LASTEXITCODE -ne 0) {
    throw "cloudflared rejected the updated ingress config with exit code $LASTEXITCODE"
  }

  if ((Test-Path -LiteralPath $ConfigPath) -and -not (Test-Path -LiteralPath $backupPath)) {
    Copy-Item -LiteralPath $ConfigPath -Destination $backupPath -Force
  }
  [System.IO.File]::WriteAllBytes($ConfigPath, $newBytes)
} finally {
  if (Test-Path -LiteralPath $temporaryPath) {
    Remove-Item -LiteralPath $temporaryPath -Force
  }
}

Write-Host "Cloudflare ingress config generated: $Hostname -> $serviceValue"
if ($Protocol -eq "https") {
  Write-Host "Origin TLS validation: localhost + $((Resolve-Path -LiteralPath $CertificatePath).Path)"
}
