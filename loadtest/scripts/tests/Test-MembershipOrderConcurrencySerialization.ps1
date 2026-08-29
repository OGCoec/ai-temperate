$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$jmxPath = Join-Path $repoRoot 'loadtest/jmeter/membership-order-concurrency.jmx'
$wrapperPath = Join-Path $repoRoot 'loadtest/scripts/run-membership-order-concurrency.ps1'
$soakPath = Join-Path $repoRoot 'loadtest/scripts/Start-MembershipPaymentSoakLocalPhase.ps1'
$scenarioRunnerPath = Join-Path $repoRoot 'loadtest/scripts/Invoke-MembershipLoadtestScenario.ps1'
$groovyPath = Join-Path $repoRoot 'loadtest/scripts/jmeter/membership-order-concurrency.groovy'

$jmx = Get-Content -Raw -LiteralPath $jmxPath
$wrapper = Get-Content -Raw -LiteralPath $wrapperPath
$soak = Get-Content -Raw -LiteralPath $soakPath
$scenarioRunner = Get-Content -Raw -LiteralPath $scenarioRunnerPath
$groovy = Get-Content -Raw -LiteralPath $groovyPath

# 五个外层档位必须串行，真正的并发仅由每个档位内部的 C1～C500 闸门产生；
# 否则五档叠加会把设计的 C500 偷换成 C661，并污染每档独立裁决。
if (-not $jmx.Contains('<stringProp name="LoopController.loops">5</stringProp>')) {
    throw 'Order concurrency JMX must iterate the five tiers sequentially.'
}
if (-not $jmx.Contains('<stringProp name="ThreadGroup.num_threads">${__P(THREADS,1)}</stringProp>')) {
    throw 'Order concurrency JMX must use exactly one outer worker by default.'
}
if (-not $wrapper.Contains('[int] $Threads = 1')) {
    throw 'Standalone order concurrency runner must default to one outer worker.'
}
if (-not $soak.Contains("'loadtest/jmeter/membership-order-concurrency.jmx' 1 0")) {
    throw 'Soak W04 must invoke one serial outer worker.'
}
if (-not $scenarioRunner.Contains('Get-ScenarioTestCaseCount')) {
    throw 'Scenario validation must distinguish CSV case count from outer worker count.'
}
if (-not $scenarioRunner.Contains("'membership-order-concurrency' {" +
        "`n            return @(Import-Csv")) {
    throw 'Order concurrency validation must count the five CSV tiers.'
}

# 同一账号上一笔订单虽然已在 Redis 终态，但数据库刷盘前部分唯一索引仍会拒绝下一笔；
# Runner 必须只在整批全为 409 时等待并重试，不能用固定两秒睡眠误判业务并发失败。
foreach ($contract in @(
        'waitForActiveOrderRelease',
        'creates.every { response -> response.status == 409 }',
        'Active-order database fence did not release before the bounded deadline.')) {
    if (-not $groovy.Contains($contract)) {
        throw "Order concurrency active-order release contract is missing: $contract"
    }
}
if ($groovy.Contains('Thread.sleep(2_000L)')) {
    throw 'Order concurrency runner must not assume the database flush completes in two seconds.'
}

# 五百并发必须复用一个异步 HTTP 客户端；逐请求 HttpURLConnection.disconnect 会在 Windows
# 制造大量短连接和临时端口竞争，把 Runner 端 BindException 误判为服务端业务失败。
foreach ($contract in @(
        'java.net.http.HttpClient',
        'HttpClient.newBuilder()',
        'HttpResponse.BodyHandlers.ofByteArray()')) {
    if (-not $groovy.Contains($contract)) {
        throw "Order concurrency shared HTTP client contract is missing: $contract"
    }
}
if ($groovy.Contains('HttpURLConnection')) {
    throw 'Order concurrency runner must not allocate a new HttpURLConnection per request.'
}

# 高并发失败必须保留脱敏状态码分布，不能只抛出笼统断言后丢失产品与 Runner 的分界证据。
foreach ($contract in @('statusHistogram', 'response status histogram=')) {
    if (-not $groovy.Contains($contract)) {
        throw "Order concurrency response histogram contract is missing: $contract"
    }
}

# 本地同账号连续五笔必须在取消后调用正式 dirty 刷盘入口，确保下一批并发开始前
# PostgreSQL 部分唯一索引已经释放；不能让 C500 横跨上一笔活动订单的释放瞬间。
foreach ($contract in @(
        '/internal/test/membership-payments/loadtest-control/flush',
        'Order persistence flush failed:')) {
    if (-not $groovy.Contains($contract)) {
        throw "Order concurrency persistence flush contract is missing: $contract"
    }
}

Write-Output 'PASS'
