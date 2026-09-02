$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$httpsDirectory = (Resolve-Path -LiteralPath (Join-Path $scriptDirectory "..")).Path
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $httpsDirectory "..\..")).Path
$modulePath = Join-Path $httpsDirectory "backend-release-id.psm1"
$launcherPath = Join-Path $httpsDirectory "start-local-https-dev.ps1"

Import-Module -Name $modulePath -Force

function Assert-Equal {
  param(
    [Parameter(Mandatory = $true)]
    [AllowEmptyString()]
    [string]$Actual,
    [Parameter(Mandatory = $true)]
    [AllowEmptyString()]
    [string]$Expected,
    [Parameter(Mandatory = $true)]
    [string]$Message
  )

  if ($Actual -cne $Expected) {
    throw "$Message。期望：$Expected；实际：$Actual"
  }
}

function Assert-True {
  param(
    [Parameter(Mandatory = $true)]
    [bool]$Condition,
    [Parameter(Mandatory = $true)]
    [string]$Message
  )

  if (-not $Condition) {
    throw $Message
  }
}

$fixedNow = [DateTimeOffset]::Parse(
  "2026-08-31T22:45:00Z",
  [Globalization.CultureInfo]::InvariantCulture)
$gitSha = "abcdef123456"

$cleanRelease = New-BackendReleaseId `
  -GitSha $gitSha `
  -Dirty $false `
  -UtcNow $fixedNow
Assert-Equal `
  -Actual $cleanRelease `
  -Expected "local-gabcdef123456" `
  -Message "干净工作区必须只使用固定长度 Git SHA"

$dirtyRelease = New-BackendReleaseId `
  -GitSha $gitSha `
  -Dirty $true `
  -UtcNow $fixedNow
Assert-Equal `
  -Actual $dirtyRelease `
  -Expected "local-gabcdef123456-dirty-20260831T224500Z" `
  -Message "脏工作区必须包含 dirty 标记和固定 UTC 启动时间"

$explicitRelease = New-BackendReleaseId `
  -ExplicitReleaseId "git-abcdef123456" `
  -GitSha $gitSha `
  -Dirty $true `
  -UtcNow $fixedNow
Assert-Equal `
  -Actual $explicitRelease `
  -Expected "git-abcdef123456" `
  -Message "合法显式发布标识必须优先于自动生成值"

$unknownRelease = New-BackendReleaseId `
  -GitSha "" `
  -Dirty $false `
  -UtcNow $fixedNow
Assert-Equal `
  -Actual $unknownRelease `
  -Expected "local-unknown-20260831T224500Z" `
  -Message "Git 信息不可用时必须生成有界本地标识"

foreach ($invalidRelease in @("", " ", "unsafe/release", ("x" * 65))) {
  $generated = New-BackendReleaseId `
    -ExplicitReleaseId $invalidRelease `
    -GitSha $gitSha `
    -Dirty $false `
    -UtcNow $fixedNow
  Assert-Equal `
    -Actual $generated `
    -Expected "local-gabcdef123456" `
    -Message "非法显式发布标识必须回退到自动生成值"
}

foreach ($release in @(
    $cleanRelease,
    $dirtyRelease,
    $explicitRelease,
    $unknownRelease)) {
  Assert-True `
    -Condition (Test-BackendReleaseId -Value $release) `
    -Message "所有生成结果必须满足后端发布标识白名单：$release"
}

$launcherSource = Get-Content -Raw -Encoding utf8 -LiteralPath $launcherPath
Assert-True `
  -Condition ($launcherSource -match 'backend-release-id\.psm1' -and
    $launcherSource -match 'Import-Module\s+-Name\s+\$backendReleaseModulePath') `
  -Message "本地 HTTPS 启动器必须加载发布标识模块"
Assert-True `
  -Condition ($launcherSource -match '"APP_RELEASE_ID"\s*=\s*\$backendReleaseId') `
  -Message "本地 HTTPS 启动器必须把发布标识写入子进程环境"

Write-Host "Backend release ID contract verified for $projectRoot" -ForegroundColor Green
