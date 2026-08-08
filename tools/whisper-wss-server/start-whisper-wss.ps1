param(
  [ValidateRange(1024, 65535)]
  [int]$Port = 7896,
  [string]$BindAddress = "127.0.0.1",
  [string]$AllowedOrigins = "",
  [ValidateRange(1, 4)]
  [int]$InferenceConcurrency = 3,
  [ValidateRange(0, 32)]
  [int]$WaitingQueueCapacity = 5,
  [ValidateRange(1000, 300000)]
  [int]$QueueWaitTimeoutMs = 90000
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$pythonPath = "C:\Users\damn\AppData\Local\whisper-venv\Scripts\python.exe"
$certificateDirectory = Join-Path $env:USERPROFILE ".ai-temperate\certs"
$p12Path = Join-Path $certificateDirectory "local-https.p12"
$passwordPath = Join-Path $certificateDirectory "local-https.password.dpapi"
$modelRoot = Join-Path $env:USERPROFILE ".cache\huggingface\hub\models--Systran--faster-whisper-medium\snapshots"
$serviceRoot = $PSScriptRoot

foreach ($requiredPath in @($pythonPath, $p12Path, $passwordPath, $modelRoot)) {
  if (-not (Test-Path -LiteralPath $requiredPath)) {
    throw "Whisper WSS dependency is missing: $requiredPath"
  }
}

$modelPath = Get-ChildItem -LiteralPath $modelRoot -Directory |
  Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName "model.bin") } |
  Sort-Object LastWriteTime -Descending |
  Select-Object -First 1 -ExpandProperty FullName
if ([string]::IsNullOrWhiteSpace($modelPath)) {
  throw "No faster-whisper-medium snapshot containing model.bin was found."
}

$securePassword = $null
$passwordPointer = [IntPtr]::Zero
$password = $null
$managedVariables = @(
  "WHISPER_WSS_HOST",
  "WHISPER_WSS_PORT",
  "WHISPER_WSS_PATH",
  "WHISPER_WSS_MODEL_PATH",
  "WHISPER_WSS_PKCS12_PATH",
  "WHISPER_WSS_PKCS12_PASSWORD",
  "WHISPER_WSS_ALLOWED_ORIGINS",
  "WHISPER_WSS_PARTIAL_INTERVAL_MS",
  "WHISPER_WSS_MAX_DURATION_MS",
  "WHISPER_WSS_INFERENCE_CONCURRENCY",
  "WHISPER_WSS_WAITING_QUEUE_CAPACITY",
  "WHISPER_WSS_QUEUE_WAIT_TIMEOUT_MS",
  "WHISPER_WSS_PARTIAL_WINDOW_MS",
  "WHISPER_WSS_STABILITY_DELAY_MS"
)
$previousValues = @{}

try {
  foreach ($name in $managedVariables) {
    if (Test-Path -LiteralPath "Env:$name") {
      $previousValues[$name] = (Get-Item -LiteralPath "Env:$name").Value
    }
  }

  $encryptedPassword = (Get-Content -Raw -Encoding utf8 -LiteralPath $passwordPath).Trim()
  $securePassword = $encryptedPassword | ConvertTo-SecureString
  $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
  $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)

  $env:WHISPER_WSS_HOST = $BindAddress
  $env:WHISPER_WSS_PORT = [string]$Port
  $env:WHISPER_WSS_PATH = "/ws/transcribe"
  $env:WHISPER_WSS_MODEL_PATH = $modelPath
  $env:WHISPER_WSS_PKCS12_PATH = $p12Path
  $env:WHISPER_WSS_PKCS12_PASSWORD = $password
  $env:WHISPER_WSS_ALLOWED_ORIGINS = $AllowedOrigins
  $env:WHISPER_WSS_PARTIAL_INTERVAL_MS = "1500"
  $env:WHISPER_WSS_MAX_DURATION_MS = "300000"
  $env:WHISPER_WSS_INFERENCE_CONCURRENCY = [string]$InferenceConcurrency
  $env:WHISPER_WSS_WAITING_QUEUE_CAPACITY = [string]$WaitingQueueCapacity
  $env:WHISPER_WSS_QUEUE_WAIT_TIMEOUT_MS = [string]$QueueWaitTimeoutMs
  $env:WHISPER_WSS_PARTIAL_WINDOW_MS = "20000"
  $env:WHISPER_WSS_STABILITY_DELAY_MS = "2000"

  Push-Location -LiteralPath $serviceRoot
  try {
    & $pythonPath -m whisper_wss
    if ($LASTEXITCODE -ne 0) {
      throw "Whisper WSS exited unexpectedly with code $LASTEXITCODE."
    }
  } finally {
    Pop-Location
  }
} finally {
  foreach ($name in $managedVariables) {
    if ($previousValues.ContainsKey($name)) {
      Set-Item -LiteralPath "Env:$name" -Value $previousValues[$name]
    } else {
      Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
    }
  }
  if ($passwordPointer -ne [IntPtr]::Zero) {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
  }
  $password = $null
  $securePassword = $null
}
