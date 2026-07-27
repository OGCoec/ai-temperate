param(
  [switch]$ValidateOnly,
  [switch]$HBuilderXOnly,
  [ValidateSet("user", "admin")]
  [string]$FrontendProfile = "user"
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$expectedSha256 = "8940C7893F566BA2E9DABCBA6D23B4294297ED46426F984D69A0860120A8FDFC"
$expectedAlias = "ai-temperate-local"

function Resolve-RequiredPath {
  param(
    [Parameter(Mandatory = $true)]
    [string]$Description,
    [Parameter(Mandatory = $true)]
    [AllowEmptyString()]
    [string[]]$Candidates
  )

  foreach ($candidate in $Candidates) {
    if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path -LiteralPath $candidate)) {
      return (Resolve-Path -LiteralPath $candidate).Path
    }
  }
  throw "$Description 不存在。已检查：$($Candidates -join '；')"
}

function Assert-DevelopmentIdesStopped {
  param(
    [Parameter(Mandatory = $true)]
    [string[]]$ProcessNames
  )

  $running = @(Get-Process -ErrorAction SilentlyContinue | Where-Object {
      $_.ProcessName -in $ProcessNames
    } | Select-Object -ExpandProperty ProcessName -Unique)
  if ($running.Count -gt 0) {
    throw "请先完全退出以下 IDE，再通过本脚本重新打开：$($running -join '、')"
  }
}

function ConvertTo-ProcessArgumentString {
  param(
    [string[]]$ArgumentList = @()
  )

  if ($ArgumentList.Count -eq 0) {
    return ""
  }

  return ($ArgumentList | ForEach-Object {
      $argument = [string]$_
      try {
        [System.Management.Automation.Language.CodeGeneration]::QuoteArgument($argument)
      } catch {
        if ($argument.Length -eq 0) {
          '""'
        } elseif ($argument -notmatch '[\s"]') {
          $argument
        } else {
          '"' + $argument.Replace('"', '\"') + '"'
        }
      }
    }) -join " "
}

function Start-ProcessWithLocalHttpsEnvironment {
  param(
    [Parameter(Mandatory = $true)]
    [string]$Description,
    [Parameter(Mandatory = $true)]
    [string]$FilePath,
    [string[]]$ArgumentList = @(),
    [string]$WorkingDirectory = "",
    [Parameter(Mandatory = $true)]
    [hashtable]$Environment
  )

  $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
  $startInfo.FileName = $FilePath
  $startInfo.UseShellExecute = $false
  $startInfo.Arguments = ConvertTo-ProcessArgumentString -ArgumentList $ArgumentList
  if (-not [string]::IsNullOrWhiteSpace($WorkingDirectory)) {
    $startInfo.WorkingDirectory = $WorkingDirectory
  }

  $processEnvironment = [Environment]::GetEnvironmentVariables("Process")
  # Cookie Domain 已迁移为 Host-only；启动子进程前必须移除父进程可能残留的旧父域配置。
  foreach ($cookieDomainVariable in @(
      "AUTH_COOKIE_DOMAIN",
      "ADMIN_COOKIE_DOMAIN",
      "ADMIN_CSRF_COOKIE_DOMAIN")) {
    [void]$processEnvironment.Remove($cookieDomainVariable)
  }
  if ($null -ne $startInfo.PSObject.Properties["Environment"]) {
    $startInfo.Environment.Clear()
    foreach ($name in $processEnvironment.Keys) {
      $startInfo.Environment[$name] = [string]$processEnvironment[$name]
    }
    foreach ($name in $Environment.Keys) {
      $startInfo.Environment[$name] = [string]$Environment[$name]
    }
  } else {
    $startInfo.EnvironmentVariables.Clear()
    foreach ($name in $processEnvironment.Keys) {
      $startInfo.EnvironmentVariables[$name] = [string]$processEnvironment[$name]
    }
    foreach ($name in $Environment.Keys) {
      $startInfo.EnvironmentVariables[$name] = [string]$Environment[$name]
    }
  }

  try {
    $process = [System.Diagnostics.Process]::Start($startInfo)
  } catch {
    throw "无法启动 $Description：$FilePath。$($_.Exception.Message)"
  }
  if ($null -eq $process) {
    throw "无法启动 $Description：$FilePath。"
  }

  Write-Host "$Description 已启动，PID：$($process.Id)" -ForegroundColor Green
  return $process
}

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
    $bytes = [Convert]::FromBase64String($body)
    return [System.Security.Cryptography.X509Certificates.X509Certificate2]::new($bytes)
  } catch {
    throw "PEM 证书无法解析：$Path"
  }
}

function Assert-ServerCertificate {
  param(
    [Parameter(Mandatory = $true)]
    [System.Security.Cryptography.X509Certificates.X509Certificate2]$Certificate,
    [Parameter(Mandatory = $true)]
    [string]$Source
  )

  $fingerprint = Get-Sha256Fingerprint -Certificate $Certificate
  if ($fingerprint -ne $expectedSha256) {
    throw "$Source 的 SHA-256 指纹与项目固定证书不一致。"
  }
  $now = [DateTime]::Now
  if ($Certificate.NotBefore -gt $now -or $Certificate.NotAfter -le $now) {
    throw "$Source 的证书不在有效期内。"
  }
  $san = $Certificate.Extensions | Where-Object { $_.Oid.Value -eq "2.5.29.17" } | Select-Object -First 1
  if ($null -eq $san) {
    throw "$Source 的证书缺少 Subject Alternative Name。"
  }
  $sanText = $san.Format($false).ToLowerInvariant()
  if (-not $sanText.Contains("localhost") -or -not $sanText.Contains("127.0.0.1")) {
    throw "$Source 的证书必须同时包含 localhost 和 127.0.0.1。"
  }
}

function Get-P12Certificate {
  param(
    [Parameter(Mandatory = $true)]
    [string]$Path,
    [Parameter(Mandatory = $true)]
    [string]$Password
  )

  $collection = [System.Security.Cryptography.X509Certificates.X509Certificate2Collection]::new()
  try {
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    $collection.Import(
      $bytes,
      $Password,
      [System.Security.Cryptography.X509Certificates.X509KeyStorageFlags]::EphemeralKeySet)
  } catch {
    throw "PKCS12 无法使用 DPAPI 密码解锁。"
  }

  $privateKeyCertificates = @($collection | Where-Object { $_.HasPrivateKey })
  if ($privateKeyCertificates.Count -ne 1) {
    foreach ($certificate in $collection) {
      $certificate.Dispose()
    }
    throw "PKCS12 必须且只能包含一张带私钥的服务器证书。"
  }
  $certificate = $privateKeyCertificates[0]
  if ($certificate.FriendlyName -ne $expectedAlias) {
    foreach ($item in $collection) {
      $item.Dispose()
    }
    throw "PKCS12 证书别名必须为 $expectedAlias，当前为 $($certificate.FriendlyName)。"
  }
  return @{ Certificate = $certificate; Collection = $collection }
}

function Get-ConfiguredValues {
  param(
    [Parameter(Mandatory = $true)]
    [string]$Name
  )

  $rawValues = @(
    [Environment]::GetEnvironmentVariable($Name, "Process"),
    [Environment]::GetEnvironmentVariable($Name, "User")
  )
  return @($rawValues | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
      ForEach-Object { $_ -split "[,;]" } |
      ForEach-Object { $_.Trim() } |
      Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}

function Get-OptionalConfiguredValue {
  param(
    [Parameter(Mandatory = $true)]
    [string]$Name
  )

  $values = @(Get-ConfiguredValues -Name $Name)
  if ($values.Count -eq 0) {
    return ""
  }
  return $values[0]
}

function Merge-ProfileList {
  $profiles = [System.Collections.Generic.List[string]]::new()
  foreach ($profile in (Get-ConfiguredValues -Name "SPRING_PROFILES_ACTIVE") + @("local-dev", "local-https")) {
    if (-not ($profiles | Where-Object { $_.Equals($profile, [StringComparison]::OrdinalIgnoreCase) })) {
      $profiles.Add($profile)
    }
  }
  return $profiles -join ","
}

function Merge-OriginList {
  $origins = [System.Collections.Generic.List[string]]::new()
  foreach ($origin in (Get-ConfiguredValues -Name "CORS_ALLOWED_ORIGINS") + @(
      "https://localhost:3000",
      "https://localhost:3001",
      "https://admin.niko000o.site",
      "https://dev.niko000o.site",
      "https://niko000o.site")) {
    $uri = $null
    if (-not [Uri]::TryCreate($origin, [UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -notin @("http", "https") -or
        -not [string]::IsNullOrEmpty($uri.UserInfo) -or
        -not [string]::IsNullOrEmpty($uri.Query) -or
        -not [string]::IsNullOrEmpty($uri.Fragment) -or
        $uri.AbsolutePath -ne "/") {
      continue
    }
    $canonical = $uri.GetLeftPart([UriPartial]::Authority)
    if (-not ($origins | Where-Object { $_.Equals($canonical, [StringComparison]::OrdinalIgnoreCase) })) {
      $origins.Add($canonical)
    }
  }
  return $origins -join ","
}

function Merge-HostnameList {
  $hostnames = [System.Collections.Generic.List[string]]::new()
  foreach ($hostname in (Get-ConfiguredValues -Name "TURNSTILE_ALLOWED_HOSTS") + @(
      "localhost",
      "niko000o.site",
      "dev.niko000o.site",
      "admin.niko000o.site",
      "api.niko000o.site")) {
    $normalized = $hostname.ToLowerInvariant()
    if ($normalized -notmatch "^(?=.{1,253}$)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*$") {
      continue
    }
    if (-not $hostnames.Contains($normalized)) {
      $hostnames.Add($normalized)
    }
  }
  return $hostnames -join ","
}

$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path
$certificateDirectory = Join-Path $env:USERPROFILE ".ai-temperate\certs"
$p12Path = Resolve-RequiredPath -Description "本地 PKCS12 证书" -Candidates @(
  (Join-Path $certificateDirectory "local-https.p12"))
$pemPath = Resolve-RequiredPath -Description "本地 PEM 证书" -Candidates @(
  (Join-Path $certificateDirectory "local-https.pem"))
$passwordPath = Resolve-RequiredPath -Description "本地证书 DPAPI 密码" -Candidates @(
  (Join-Path $certificateDirectory "local-https.password.dpapi"))
$antigravityPath = $null
if (-not $HBuilderXOnly) {
  $antigravityPath = Resolve-RequiredPath -Description "Antigravity" -Candidates @(
    $env:ANTIGRAVITY_PATH,
    "D:\Antigravity\Antigravity.exe")
}
$hbuilderXPath = Resolve-RequiredPath -Description "HBuilderX" -Candidates @(
  $env:HBUILDERX_PATH,
  (Join-Path $env:USERPROFILE "Desktop\HBuilderX\HBuilderX.exe"))
$frontendProjectName = if ($FrontendProfile -eq "admin") { "myuniappadmin" } else { "fornted" }
$frontendPath = Resolve-RequiredPath -Description "uni-app 前端工程" -Candidates @(
  (Join-Path $projectRoot $frontendProjectName))

$securePassword = $null
$passwordPointer = [IntPtr]::Zero
$password = $null
$p12State = $null
$pemCertificate = $null
try {
  $encryptedPassword = (Get-Content -Raw -Encoding utf8 -LiteralPath $passwordPath).Trim()
  if ([string]::IsNullOrWhiteSpace($encryptedPassword)) {
    throw "DPAPI 密码文件为空。"
  }
  $securePassword = $encryptedPassword | ConvertTo-SecureString
  $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
  $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)

  $p12State = Get-P12Certificate -Path $p12Path -Password $password
  Assert-ServerCertificate -Certificate $p12State.Certificate -Source "PKCS12"
  $pemCertificate = Read-PemCertificate -Path $pemPath
  Assert-ServerCertificate -Certificate $pemCertificate -Source "PEM"
  if ((Get-Sha256Fingerprint -Certificate $p12State.Certificate) -ne
      (Get-Sha256Fingerprint -Certificate $pemCertificate)) {
    throw "PKCS12 与 PEM 不是同一张证书。"
  }

  Write-Host "本地 HTTPS 证书、DPAPI 密码、IDE 路径和 $FrontendProfile 前端工程校验通过。" -ForegroundColor Green
  if ($ValidateOnly) {
    Write-Host "ValidateOnly 模式不会启动 IDE，也不会修改永久环境变量。"
    exit 0
  }

  $edgeProxyMode = Get-OptionalConfiguredValue -Name "EDGE_PROXY_MODE"
  if ([string]::IsNullOrWhiteSpace($edgeProxyMode)) {
    $edgeProxyMode = "DISABLED"
  }
  $edgeProxyMode = $edgeProxyMode.ToUpperInvariant()
  if ($edgeProxyMode -notin @("DISABLED", "OPTIONAL", "REQUIRED")) {
    throw "EDGE_PROXY_MODE 只能是 DISABLED、OPTIONAL 或 REQUIRED。"
  }

  $requiredStoppedProcesses = @("HBuilderX")
  if (-not $HBuilderXOnly) {
    $requiredStoppedProcesses = @("Antigravity", "HBuilderX")
  }
  Assert-DevelopmentIdesStopped -ProcessNames $requiredStoppedProcesses

  $localHttpsEnvironment = @{
    "SPRING_PROFILES_ACTIVE" = (Merge-ProfileList)
    "SERVER_SSL_KEY_STORE" = "file:" + $p12Path.Replace("\", "/")
    "SERVER_SSL_KEY_STORE_TYPE" = "PKCS12"
    "SERVER_SSL_KEY_STORE_PASSWORD" = $password
    "SERVER_SSL_KEY_ALIAS" = $expectedAlias
    "LOCAL_HTTPS_ENABLED" = "true"
    "LOCAL_HTTPS_P12_PATH" = $p12Path
    "CORS_ALLOWED_ORIGINS" = (Merge-OriginList)
    "TURNSTILE_ALLOWED_HOSTS" = (Merge-HostnameList)
    "EDGE_PROXY_MODE" = $edgeProxyMode
  }

  if (-not $HBuilderXOnly) {
    Start-ProcessWithLocalHttpsEnvironment `
      -Description "Antigravity" `
      -FilePath $antigravityPath `
      -ArgumentList @($projectRoot) `
      -WorkingDirectory $projectRoot `
      -Environment $localHttpsEnvironment | Out-Null
  }
  Start-ProcessWithLocalHttpsEnvironment `
    -Description "HBuilderX" `
    -FilePath $hbuilderXPath `
    -ArgumentList @($frontendPath) `
    -WorkingDirectory $frontendPath `
    -Environment $localHttpsEnvironment | Out-Null
  if ($HBuilderXOnly) {
    Write-Host "HBuilderX 已使用本地 HTTPS 进程环境启动。" -ForegroundColor Green
    Write-Host "请在 HBuilderX 运行 $FrontendProfile 前端的 H5 或 Android。"
  } else {
    Write-Host "Antigravity 与 HBuilderX 已使用本地 HTTPS 进程环境启动。" -ForegroundColor Green
    Write-Host "请在 Antigravity 启动 Spring Boot，并在 HBuilderX 运行 $FrontendProfile 前端的 H5 或 Android。"
  }
} finally {
  if ($null -ne $pemCertificate) {
    $pemCertificate.Dispose()
  }
  if ($null -ne $p12State) {
    foreach ($certificate in $p12State.Collection) {
      $certificate.Dispose()
    }
  }
  if ($passwordPointer -ne [IntPtr]::Zero) {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
  }
  $password = $null
  $securePassword = $null
}
