Set-StrictMode -Version Latest

$script:BackendReleaseIdPattern = "^[A-Za-z0-9._-]{1,64}$"

function Test-BackendReleaseId {
  [CmdletBinding()]
  param(
    [AllowNull()]
    [AllowEmptyString()]
    [string]$Value
  )

  if ([string]::IsNullOrWhiteSpace($Value)) {
    return $false
  }
  return $Value -ceq $Value.Trim() -and $Value -cmatch $script:BackendReleaseIdPattern
}

function New-BackendReleaseId {
  [CmdletBinding()]
  param(
    [AllowNull()]
    [AllowEmptyString()]
    [string]$ExplicitReleaseId = "",
    [AllowNull()]
    [AllowEmptyString()]
    [string]$GitSha = "",
    [bool]$Dirty = $false,
    [DateTimeOffset]$UtcNow = [DateTimeOffset]::UtcNow
  )

  if (Test-BackendReleaseId -Value $ExplicitReleaseId) {
    return $ExplicitReleaseId
  }

  $timestamp = $UtcNow.ToUniversalTime().ToString(
    "yyyyMMdd'T'HHmmss'Z'",
    [Globalization.CultureInfo]::InvariantCulture)
  $normalizedGitSha = if ($null -eq $GitSha) { "" } else { $GitSha.Trim().ToLowerInvariant() }
  if ($normalizedGitSha -match "^[a-f0-9]{12,40}$") {
    $shortGitSha = $normalizedGitSha.Substring(0, 12)
    $releaseId = if ($Dirty) {
      "local-g$shortGitSha-dirty-$timestamp"
    } else {
      "local-g$shortGitSha"
    }
  } else {
    $releaseId = "local-unknown-$timestamp"
  }

  if (-not (Test-BackendReleaseId -Value $releaseId)) {
    throw "自动生成的 APP_RELEASE_ID 不符合后端发布标识白名单。"
  }
  return $releaseId
}

function Resolve-BackendReleaseId {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Container })]
    [string]$ProjectRoot,
    [AllowNull()]
    [AllowEmptyString()]
    [string]$ExplicitReleaseId = "",
    [DateTimeOffset]$UtcNow = [DateTimeOffset]::UtcNow
  )

  if (Test-BackendReleaseId -Value $ExplicitReleaseId) {
    return $ExplicitReleaseId
  }

  $gitCommand = Get-Command git -CommandType Application -ErrorAction SilentlyContinue |
    Select-Object -First 1
  if ($null -eq $gitCommand) {
    return New-BackendReleaseId -UtcNow $UtcNow
  }

  $gitSha = ""
  $dirty = $false
  try {
    $shaOutput = @(& $gitCommand.Source -C $ProjectRoot rev-parse --short=12 HEAD 2>$null)
    $shaExitCode = $LASTEXITCODE
    if ($shaExitCode -eq 0 -and $shaOutput.Count -gt 0) {
      $gitSha = [string]$shaOutput[0]
      $statusOutput = @(& $gitCommand.Source -C $ProjectRoot status --porcelain --untracked-files=all 2>$null)
      if ($LASTEXITCODE -ne 0) {
        $gitSha = ""
      } else {
        $dirty = $statusOutput.Count -gt 0
      }
    }
  } catch {
    $gitSha = ""
    $dirty = $false
  }

  return New-BackendReleaseId `
    -GitSha $gitSha `
    -Dirty $dirty `
    -UtcNow $UtcNow
}

Export-ModuleMember -Function @(
  "Test-BackendReleaseId",
  "New-BackendReleaseId",
  "Resolve-BackendReleaseId")
