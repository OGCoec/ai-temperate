param(
  [Parameter(Mandatory = $true)]
  [string]$CloudflaredPath,
  [Parameter(Mandatory = $true)]
  [string]$TunnelId,
  [Parameter(Mandatory = $true)]
  [string]$ConfigPath,
  [Parameter(Mandatory = $true)]
  [string]$PidFilePath,
  [Parameter(Mandatory = $true)]
  [string]$StopRequestPath,
  [string]$ProxyUrl = "",
  [string]$NoProxy = "localhost,127.0.0.1,::1",
  [int]$CheckIntervalSeconds = 5,
  [int]$BaselineWaitSeconds = 30
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

if (-not ("AiTemperate.Cloudflare.NativeJobObject" -as [type])) {
  Add-Type -TypeDefinition @"
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;

namespace AiTemperate.Cloudflare {
  public static class NativeJobObject {
    private const int JobObjectExtendedLimitInformation = 9;
    private const uint JobObjectLimitKillOnJobClose = 0x00002000;

    [StructLayout(LayoutKind.Sequential)]
    private struct IO_COUNTERS {
      public ulong ReadOperationCount;
      public ulong WriteOperationCount;
      public ulong OtherOperationCount;
      public ulong ReadTransferCount;
      public ulong WriteTransferCount;
      public ulong OtherTransferCount;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct JOBOBJECT_BASIC_LIMIT_INFORMATION {
      public long PerProcessUserTimeLimit;
      public long PerJobUserTimeLimit;
      public uint LimitFlags;
      public UIntPtr MinimumWorkingSetSize;
      public UIntPtr MaximumWorkingSetSize;
      public uint ActiveProcessLimit;
      public UIntPtr Affinity;
      public uint PriorityClass;
      public uint SchedulingClass;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct JOBOBJECT_EXTENDED_LIMIT_INFORMATION {
      public JOBOBJECT_BASIC_LIMIT_INFORMATION BasicLimitInformation;
      public IO_COUNTERS IoInfo;
      public UIntPtr ProcessMemoryLimit;
      public UIntPtr JobMemoryLimit;
      public UIntPtr PeakProcessMemoryUsed;
      public UIntPtr PeakJobMemoryUsed;
    }

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CreateJobObject(IntPtr lpJobAttributes, string lpName);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool SetInformationJobObject(
      IntPtr hJob,
      int jobObjectInfoClass,
      IntPtr lpJobObjectInfo,
      uint cbJobObjectInfoLength);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool AssignProcessToJobObject(IntPtr hJob, IntPtr hProcess);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool CloseHandle(IntPtr hObject);

    public static IntPtr CreateKillOnCloseJob() {
      IntPtr jobHandle = CreateJobObject(IntPtr.Zero, null);
      if (jobHandle == IntPtr.Zero) {
        throw new Win32Exception(Marshal.GetLastWin32Error(), "CreateJobObject failed.");
      }

      JOBOBJECT_EXTENDED_LIMIT_INFORMATION info = new JOBOBJECT_EXTENDED_LIMIT_INFORMATION();
      info.BasicLimitInformation.LimitFlags = JobObjectLimitKillOnJobClose;
      int length = Marshal.SizeOf(typeof(JOBOBJECT_EXTENDED_LIMIT_INFORMATION));
      IntPtr infoPointer = Marshal.AllocHGlobal(length);
      try {
        Marshal.StructureToPtr(info, infoPointer, false);
        if (!SetInformationJobObject(jobHandle, JobObjectExtendedLimitInformation, infoPointer, (uint)length)) {
          int error = Marshal.GetLastWin32Error();
          CloseHandle(jobHandle);
          throw new Win32Exception(error, "SetInformationJobObject failed.");
        }
      } finally {
        Marshal.FreeHGlobal(infoPointer);
      }

      return jobHandle;
    }

    public static void AssignProcess(IntPtr jobHandle, IntPtr processHandle) {
      if (!AssignProcessToJobObject(jobHandle, processHandle)) {
        throw new Win32Exception(Marshal.GetLastWin32Error(), "AssignProcessToJobObject failed.");
      }
    }
  }
}
"@
}

function Start-CloudflaredProcess {
  param(
    [string]$ExePath,
    [string]$RunTunnelId,
    [string]$RunConfigPath,
    [string]$RunProxyUrl,
    [string]$RunNoProxy,
    [System.Collections.Concurrent.ConcurrentQueue[string]]$OutputQueue
  )

  $jobHandle = [IntPtr]::Zero
  $process = $null
  $stdoutJob = $null
  $stderrJob = $null
  $escapedConfigPath = '"' + $RunConfigPath.Replace('"', '\"') + '"'
  try {
    $jobHandle = [AiTemperate.Cloudflare.NativeJobObject]::CreateKillOnCloseJob()
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $ExePath
    $startInfo.Arguments = "tunnel --config $escapedConfigPath --ha-connections 2 run $RunTunnelId"
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.CreateNoWindow = $true
    $startInfo.StandardOutputEncoding = [System.Text.UTF8Encoding]::new($false)
    $startInfo.StandardErrorEncoding = [System.Text.UTF8Encoding]::new($false)
    if (-not [string]::IsNullOrWhiteSpace($RunProxyUrl)) {
      $startInfo.EnvironmentVariables["HTTP_PROXY"] = $RunProxyUrl
      $startInfo.EnvironmentVariables["HTTPS_PROXY"] = $RunProxyUrl
      $startInfo.EnvironmentVariables["ALL_PROXY"] = $RunProxyUrl
      $startInfo.EnvironmentVariables["NO_PROXY"] = $RunNoProxy
    }

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    if (-not $process.Start()) {
      throw "cloudflared process failed to start."
    }
    [AiTemperate.Cloudflare.NativeJobObject]::AssignProcess($jobHandle, $process.Handle)

    $suffix = $process.Id.ToString() + '-' + [guid]::NewGuid().ToString('N')
    $stdoutSource = "ait-cloudflared-stdout-$suffix"
    $stderrSource = "ait-cloudflared-stderr-$suffix"
    $stdoutJob = Register-ObjectEvent -InputObject $process -EventName OutputDataReceived -SourceIdentifier $stdoutSource -Action {
      if (-not [string]::IsNullOrWhiteSpace($EventArgs.Data)) {
        [void]$Event.MessageData.Enqueue($EventArgs.Data)
      }
    } -MessageData $OutputQueue
    $stderrJob = Register-ObjectEvent -InputObject $process -EventName ErrorDataReceived -SourceIdentifier $stderrSource -Action {
      if (-not [string]::IsNullOrWhiteSpace($EventArgs.Data)) {
        [void]$Event.MessageData.Enqueue($EventArgs.Data)
      }
    } -MessageData $OutputQueue

    $process.BeginOutputReadLine()
    $process.BeginErrorReadLine()
    return [pscustomobject]@{
      Process = $process
      JobHandle = $jobHandle
      StdoutSource = $stdoutSource
      StderrSource = $stderrSource
      StdoutJob = $stdoutJob
      StderrJob = $stderrJob
    }
  } catch {
    if ($null -ne $process -and -not $process.HasExited) {
      try { $process.Kill() } catch { }
    }
    if ($jobHandle -ne [IntPtr]::Zero) {
      [void][AiTemperate.Cloudflare.NativeJobObject]::CloseHandle($jobHandle)
    }
    if ($null -ne $stdoutJob) {
      Remove-Job -Id $stdoutJob.Id -Force -ErrorAction SilentlyContinue
    }
    if ($null -ne $stderrJob) {
      Remove-Job -Id $stderrJob.Id -Force -ErrorAction SilentlyContinue
    }
    throw
  }
}

function Write-PidState {
  param([System.Diagnostics.Process]$Process, [string]$ExecutablePath, [string]$StatePath)

  $directory = Split-Path -Parent $StatePath
  New-Item -ItemType Directory -Force -Path $directory | Out-Null
  $state = [ordered]@{
    schemaVersion = 1
    processId = $Process.Id
    processStartTimeUtc = $Process.StartTime.ToUniversalTime().ToString('O')
    executablePath = (Resolve-Path -LiteralPath $ExecutablePath).Path
    createdAtUtc = [DateTime]::UtcNow.ToString('O')
  }
  $temporaryPath = $StatePath + '.tmp'
  [System.IO.File]::WriteAllText($temporaryPath, ($state | ConvertTo-Json -Compress), [System.Text.UTF8Encoding]::new($false))
  Move-Item -LiteralPath $temporaryPath -Destination $StatePath -Force
}

function Remove-OwnedPidState {
  param([int]$ProcessId, [string]$StatePath)

  if (-not (Test-Path -LiteralPath $StatePath)) {
    return
  }
  try {
    $state = Get-Content -Raw -Encoding utf8 -LiteralPath $StatePath | ConvertFrom-Json
    if ([int]$state.processId -eq $ProcessId) {
      Remove-Item -LiteralPath $StatePath -Force
    }
  } catch {
    Write-Host "WARN: failed to clean PID state file: $($_.Exception.Message)" -ForegroundColor Yellow
  }
}

function Flush-CloudflaredOutput {
  param([System.Collections.Concurrent.ConcurrentQueue[string]]$OutputQueue)

  $cyanGreen = "$([char]27)[38;2;0;255;255m"
  $resetColor = "$([char]27)[0m"
  $line = $null
  while ($OutputQueue.TryDequeue([ref]$line)) {
    if ([string]::IsNullOrWhiteSpace($line)) {
      continue
    }
    if ($line -match 'Registered tunnel connection') {
      Write-Host "$cyanGreen$line$resetColor"
    } elseif ($line -match '\sERR\s|^ERR\s') {
      Write-Host $line -ForegroundColor Red
    } elseif ($line -match '\sWRN\s|^WRN\s') {
      Write-Host $line -ForegroundColor Yellow
    } elseif ($line -match '\sINF\s|^INF\s') {
      Write-Host $line -ForegroundColor Green
    } else {
      Write-Host $line
    }
  }
}

function Get-EgressSnapshot {
  param([int]$ProcessId)

  $connections = Get-NetTCPConnection -OwningProcess $ProcessId -State Established -ErrorAction SilentlyContinue |
    Where-Object { $_.RemoteAddress -and $_.RemoteAddress -notin @('127.0.0.1', '::1') }
  if (-not $connections) {
    return $null
  }

  $localAddresses = $connections.LocalAddress |
    Where-Object { $_ -and $_ -notin @('127.0.0.1', '::1') } |
    Sort-Object -Unique
  $remoteAddresses = $connections.RemoteAddress |
    Where-Object { $_ -and $_ -notin @('127.0.0.1', '::1') } |
    Sort-Object -Unique
  if (-not $localAddresses) {
    return $null
  }

  return [pscustomobject]@{
    LocalSignature = $localAddresses -join ','
    RemoteAddresses = @($remoteAddresses)
  }
}

$outputQueue = [System.Collections.Concurrent.ConcurrentQueue[string]]::new()
$runtime = $null
$cloudflared = $null
$exitCode = 0
$baselineSignature = $null
$baselineDeadline = (Get-Date).AddSeconds([Math]::Max(5, $BaselineWaitSeconds))
$monitorEnabled = $true

try {
  $runtime = Start-CloudflaredProcess `
    -ExePath $CloudflaredPath `
    -RunTunnelId $TunnelId `
    -RunConfigPath $ConfigPath `
    -RunProxyUrl $ProxyUrl `
    -RunNoProxy $NoProxy `
    -OutputQueue $outputQueue
  $cloudflared = $runtime.Process
  Write-PidState -Process $cloudflared -ExecutablePath $CloudflaredPath -StatePath $PidFilePath
  Write-Host "cloudflared started. PID=$($cloudflared.Id)." -ForegroundColor Green

  while ($true) {
    Flush-CloudflaredOutput -OutputQueue $outputQueue
    $cloudflared.Refresh()
    if ($cloudflared.HasExited) {
      $exitCode = if (Test-Path -LiteralPath $StopRequestPath) { 0 } else { [int]$cloudflared.ExitCode }
      break
    }

    if ($monitorEnabled) {
      $snapshot = Get-EgressSnapshot -ProcessId $cloudflared.Id
      if ($null -ne $snapshot) {
        if ([string]::IsNullOrWhiteSpace($baselineSignature)) {
          $baselineSignature = $snapshot.LocalSignature
          Write-Host "cloudflared local egress address: $baselineSignature"
          if ($snapshot.RemoteAddresses.Count -gt 0) {
            $edgeAddresses = $snapshot.RemoteAddresses -join ','
            Write-Host "Cloudflare edge addresses: $edgeAddresses"
          }
        } elseif ($snapshot.LocalSignature -ne $baselineSignature) {
          Write-Host "Egress address changed: $baselineSignature -> $($snapshot.LocalSignature)" -ForegroundColor Yellow
          $cloudflared.Kill()
          $exitCode = 100
          break
        }
      } elseif ([string]::IsNullOrWhiteSpace($baselineSignature) -and (Get-Date) -gt $baselineDeadline) {
        Write-Host "No egress baseline was established. Continuing without egress-change monitoring." -ForegroundColor Yellow
        $monitorEnabled = $false
      }
    }

    Start-Sleep -Seconds ([Math]::Max(1, $CheckIntervalSeconds))
  }
} catch {
  Write-Host "cloudflared guard failed: $($_.Exception.Message)" -ForegroundColor Red
  $exitCode = 4
} finally {
  Flush-CloudflaredOutput -OutputQueue $outputQueue
  if ($null -ne $cloudflared) {
    if (-not $cloudflared.HasExited) {
      try { $cloudflared.Kill() } catch { }
    }
    Remove-OwnedPidState -ProcessId $cloudflared.Id -StatePath $PidFilePath
  }
  if (Test-Path -LiteralPath $StopRequestPath) {
    Remove-Item -LiteralPath $StopRequestPath -Force
  }

  if ($null -ne $runtime) {
    Unregister-Event -SourceIdentifier $runtime.StdoutSource -ErrorAction SilentlyContinue
    Unregister-Event -SourceIdentifier $runtime.StderrSource -ErrorAction SilentlyContinue
    Remove-Job -Id $runtime.StdoutJob.Id -Force -ErrorAction SilentlyContinue
    Remove-Job -Id $runtime.StderrJob.Id -Force -ErrorAction SilentlyContinue
    if ($runtime.JobHandle -ne [IntPtr]::Zero) {
      [void][AiTemperate.Cloudflare.NativeJobObject]::CloseHandle($runtime.JobHandle)
    }
  }
}

exit $exitCode
