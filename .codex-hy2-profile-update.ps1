param(
    [Parameter(Mandatory = $true)]
    [string]$ProfilePath,

    [Parameter(Mandatory = $true)]
    [string]$Password
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $ProfilePath -PathType Leaf)) {
    throw "Clash profile not found: $ProfilePath"
}

$content = [System.IO.File]::ReadAllText($ProfilePath)
$newline = if ($content.Contains("`r`n")) { "`r`n" } else { "`n" }
$expiredNames = @('🇺🇸美国加州到6|6', 'SOCKS5-Oregon-64.32.187.229')
$lines = $content -split "`r?`n", 0
$updated = [System.Collections.Generic.List[string]]::new()
$insideProxies = $false
$skipCurrentProxy = $false
$hy2Inserted = $false

foreach ($line in $lines) {
    if ($line -eq 'proxies:') {
        $insideProxies = $true
        $updated.Add($line)
        continue
    }

    if ($insideProxies -and $line -eq 'proxy-groups:') {
        if (-not $hy2Inserted) {
            $hy2Block = @(
                '  - name: "Azure-HY2-443"',
                '    type: hysteria2',
                '    server: hy2.niko000o.site',
                '    port: 443',
                "    password: `"$Password`"",
                '    sni: hy2.niko000o.site',
                '    skip-cert-verify: false',
                '    udp: true',
                '    bbr-profile: conservative',
                '    alpn:',
                '      - h3',
                ''
            )
            $hy2Block | ForEach-Object { $updated.Add($_) }
            $hy2Inserted = $true
        }
        $insideProxies = $false
        $skipCurrentProxy = $false
        $updated.Add($line)
        continue
    }

    if ($insideProxies -and $line -match '^  - name: "(?<name>.*)"$') {
        $skipCurrentProxy = $expiredNames -contains $Matches.name
    }

    if (-not $skipCurrentProxy) {
        $updated.Add($line)
    }
}

if (-not $hy2Inserted) {
    throw 'The proxies section could not be located safely.'
}

$patched = [string]::Join($newline, $updated)
$proxyGroupPattern = '(?ms)^  - name: "PROXY"\r?\n    type: select\r?\n    proxies:\r?\n(?:      - [^\r\n]*(?:\r?\n|$))+'
$replacement = [string]::Join($newline, @(
    '  - name: "PROXY"',
    '    type: select',
    '    proxies:',
    '      - "Azure-VLESS-8443"',
    '      - "Azure-HY2-443"',
    '      - DIRECT',
    ''
))

if ($patched -notmatch $proxyGroupPattern) {
    throw 'The PROXY group could not be located safely.'
}
$patched = [regex]::Replace($patched, $proxyGroupPattern, $replacement, 1)

if ($patched -match '(?m)^  - name: "(?:🇺🇸美国加州到6\|6|SOCKS5-Oregon-64\.32\.187\.229)"$') {
    throw 'An expired SOCKS5 node remained after the update.'
}
if (($patched -split [regex]::Escape('Azure-HY2-443')).Count -ne 3) {
    throw 'Azure-HY2-443 was not added exactly once to the node list and PROXY group.'
}

$backupPath = "$ProfilePath.hy2-backup-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
[System.IO.File]::Copy($ProfilePath, $backupPath, $false)
[System.IO.File]::WriteAllText($ProfilePath, $patched, [System.Text.UTF8Encoding]::new($false))

[pscustomobject]@{
    BackupPath = $backupPath
    Hy2NodeAdded = $true
    ExpiredSocksRemoved = 2
} | ConvertTo-Json -Compress
