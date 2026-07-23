param(
  [ValidateSet("Status", "Install", "Remove")]
  [string]$Action = "Status"
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$expectedSha256 = "8940C7893F566BA2E9DABCBA6D23B4294297ED46426F984D69A0860120A8FDFC"
$pemPath = Join-Path $env:USERPROFILE ".ai-temperate\certs\local-https.pem"

function Get-Sha256Fingerprint {
  param(
    [Parameter(Mandatory = $true)]
    [System.Security.Cryptography.X509Certificates.X509Certificate2]$Certificate
  )

  $sha256 = [System.Security.Cryptography.SHA256]::Create()
  try {
    return ([BitConverter]::ToString($sha256.ComputeHash($Certificate.RawData))).Replace("-", "")
  } finally {
    $sha256.Dispose()
  }
}

function Read-PemCertificate {
  param(
    [Parameter(Mandatory = $true)]
    [string]$Path
  )

  if (-not (Test-Path -LiteralPath $Path)) {
    throw "本地 PEM 证书不存在：$Path"
  }
  $pem = Get-Content -Raw -Encoding ascii -LiteralPath $Path
  $match = [regex]::Match(
    $pem,
    "-----BEGIN CERTIFICATE-----(?<body>.*?)-----END CERTIFICATE-----",
    [System.Text.RegularExpressions.RegexOptions]::Singleline)
  if (-not $match.Success) {
    throw "PEM 文件不包含有效的 CERTIFICATE 块：$Path"
  }
  $body = [regex]::Replace($match.Groups["body"].Value, "\s", "")
  try {
    return [System.Security.Cryptography.X509Certificates.X509Certificate2]::new(
      [Convert]::FromBase64String($body))
  } catch {
    throw "PEM 证书无法解析：$Path"
  }
}

function Find-PinnedCertificates {
  param(
    [Parameter(Mandatory = $true)]
    [System.Security.Cryptography.X509Certificates.X509Store]$Store
  )

  return @($Store.Certificates | Where-Object {
      (Get-Sha256Fingerprint -Certificate $_) -eq $expectedSha256
    })
}

$certificate = Read-PemCertificate -Path $pemPath
$fingerprint = Get-Sha256Fingerprint -Certificate $certificate
if ($fingerprint -ne $expectedSha256) {
  $certificate.Dispose()
  throw "PEM 证书指纹与项目固定证书不一致，拒绝修改信任库。"
}

$store = [System.Security.Cryptography.X509Certificates.X509Store]::new(
  [System.Security.Cryptography.X509Certificates.StoreName]::Root,
  [System.Security.Cryptography.X509Certificates.StoreLocation]::CurrentUser)
try {
  $openFlags = if ($Action -eq "Status") {
    [System.Security.Cryptography.X509Certificates.OpenFlags]::ReadOnly
  } else {
    [System.Security.Cryptography.X509Certificates.OpenFlags]::ReadWrite
  }
  $store.Open($openFlags)
  $installed = Find-PinnedCertificates -Store $store

  if ($Action -eq "Status") {
    if ($installed.Count -gt 0) {
      Write-Host "本地 HTTPS 证书已受当前 Windows 用户信任。" -ForegroundColor Green
    } else {
      Write-Host "本地 HTTPS 证书尚未加入当前 Windows 用户的受信任根证书库。" -ForegroundColor Yellow
    }
    exit 0
  }

  if ($Action -eq "Install") {
    if ($installed.Count -eq 0) {
      $store.Add($certificate)
      Write-Host "已将固定指纹证书加入当前 Windows 用户的受信任根证书库。" -ForegroundColor Green
    } else {
      Write-Host "固定指纹证书已经受信任，无需重复安装。" -ForegroundColor Green
    }
    exit 0
  }

  if ($installed.Count -eq 0) {
    Write-Host "当前用户信任库中不存在固定指纹证书，无需删除。"
  } else {
    foreach ($item in $installed) {
      $store.Remove($item)
    }
    Write-Host "已从当前 Windows 用户信任库删除固定指纹证书。" -ForegroundColor Green
  }
} finally {
  $store.Close()
  $certificate.Dispose()
}
