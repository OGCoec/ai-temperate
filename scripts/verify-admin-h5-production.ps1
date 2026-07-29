param(
    [string]$ArtifactPath
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$expectedArtifactPath = Join-Path $repositoryRoot 'myuniappadmin\unpackage\dist\build\h5'
$targetPath = if ([string]::IsNullOrWhiteSpace($ArtifactPath)) {
    $expectedArtifactPath
} else {
    $ArtifactPath
}

if (-not (Test-Path -LiteralPath $targetPath -PathType Container)) {
    throw "Admin H5 production directory does not exist: $targetPath"
}

$resolvedExpected = [System.IO.Path]::GetFullPath($expectedArtifactPath).TrimEnd('\')
$resolvedTarget = [System.IO.Path]::GetFullPath((Resolve-Path -LiteralPath $targetPath).Path).TrimEnd('\')
if (-not [string]::Equals($resolvedTarget, $resolvedExpected, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Only the HBuilderX production directory may be verified: $resolvedExpected"
}

foreach ($requiredFile in @('index.html', '_headers', '_redirects')) {
    $requiredPath = Join-Path $resolvedTarget $requiredFile
    if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
        throw "Production artifact is missing required file: $requiredFile"
    }
}

$allFiles = @(Get-ChildItem -LiteralPath $resolvedTarget -Recurse -File)
if ($allFiles.Count -eq 0) {
    throw 'Admin H5 production artifact is empty.'
}

$vueSources = @($allFiles | Where-Object { $_.Extension -ieq '.vue' })
if ($vueSources.Count -gt 0) {
    throw "Production artifact contains a Vue source file: $($vueSources[0].FullName)"
}

$forbiddenPatterns = @(
    '/@vite/',
    '/@fs/',
    '@vite/client',
    'pages-json-js',
    '__vite_ping',
    'vite-hmr',
    'wss://localhost:3001',
    'wss://127.0.0.1:3001'
)

$textExtensions = @('.html', '.js', '.mjs', '.cjs', '.css', '.json', '.map', '.txt')
foreach ($file in $allFiles) {
    if ($textExtensions -notcontains $file.Extension.ToLowerInvariant()) {
        continue
    }
    $content = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8
    foreach ($pattern in $forbiddenPatterns) {
        if ($content.IndexOf($pattern, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
            throw "Production artifact contains development marker '$pattern': $($file.FullName)"
        }
    }
}

$assetDirectory = Join-Path $resolvedTarget 'assets'
if (-not (Test-Path -LiteralPath $assetDirectory -PathType Container)) {
    throw 'Production artifact is missing the assets directory.'
}

$hashedAssets = @(Get-ChildItem -LiteralPath $assetDirectory -Recurse -File |
    Where-Object { $_.Name -match '[._-][A-Za-z0-9_-]{6,}\.(?:js|css)$' })
if ($hashedAssets.Count -eq 0) {
    throw 'No content-hashed JavaScript or CSS file was found in the assets directory.'
}

$indexSource = Get-Content -LiteralPath (Join-Path $resolvedTarget 'index.html') -Raw -Encoding UTF8
if ($indexSource -notmatch '/assets/') {
    throw 'index.html does not reference a production asset.'
}

Write-Host "Admin H5 production artifact verification passed: $resolvedTarget"
Write-Host "Files: $($allFiles.Count); hashed assets: $($hashedAssets.Count)"
