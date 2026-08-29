import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// 真实时间状态机脚本只接受 realtime Profile，防止同一 JMX 被误用于压缩时间。
if (props.getProperty('MODE', '') != 'loadtest-realtime') {
    throw new IllegalStateException('Membership state machine JMX requires loadtest-realtime.')
}

def baseUrl = props.getProperty('PROTOCOL', 'http') + '://' +
        props.getProperty('HOST', 'localhost') + ':' +
        props.getProperty('PORT', '8080')
def callbackPid = props.getProperty('CALLBACK_PID', 'loadtest-merchant')
def callbackKey = props.getProperty(
        'CALLBACK_KEY', 'membership-loadtest-callback-key-v1-local')
def runId = props.getProperty('RUN_ID', 'local')
def accessToken = vars.get('accessToken')
def scenario = vars.get('scenario')
def iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME
def liuhaoTime = DateTimeFormatter.ofPattern('yyyy-MM-dd HH:mm:ss').withZone(ZoneOffset.UTC)
def json = new JsonSlurper()

def readBody = { HttpURLConnection connection ->
    def stream = connection.responseCode >= 400
            ? connection.errorStream
            : connection.inputStream
    if (stream == null) {
        return ''
    }
    stream.withCloseable { input ->
        return new String(input.readAllBytes(), StandardCharsets.UTF_8)
    }
}

def request = { String method, String path, Map<String, String> headers, String body ->
    HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection()
    connection.requestMethod = method
    connection.connectTimeout = 10_000
    connection.readTimeout = 30_000
    connection.instanceFollowRedirects = false
    headers.each { name, value -> connection.setRequestProperty(name, value) }
    if (body != null) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8)
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(bytes.length)
        connection.outputStream.withCloseable { output -> output.write(bytes) }
    }
    int status = connection.responseCode
    String responseBody = readBody(connection)
    String contentType = connection.getHeaderField('Content-Type') ?: ''
    connection.disconnect()
    return [status: status, body: responseBody, contentType: contentType]
}

def requireHttp = { Map response, Collection<Integer> expected, String operation ->
    if (!expected.contains((Integer) response.status)) {
        throw new IllegalStateException(
                operation + ' expected ' + expected + ' but received ' + response.status +
                        ' body=' + response.body.take(256))
    }
    return response
}

def authHeaders = [
        'Authorization': 'Bearer ' + accessToken,
        'Accept': 'application/json'
]

def getOrder = { String orderId ->
    def response = requireHttp(
            request('GET', '/api/user/membership-orders/' + orderId, authHeaders, null),
            [200],
            'get order')
    return (Map) json.parseText(response.body)
}

def waitUntil = { Instant target ->
    while (true) {
        long remaining = target.toEpochMilli() - System.currentTimeMillis()
        if (remaining <= 0L) {
            return
        }
        Thread.sleep(Math.min(remaining, 1_000L))
    }
}

def waitForStatus = { String orderId, String expected, Instant deadline ->
    Map order = getOrder(orderId)
    while (order.status != expected && Instant.now().isBefore(deadline)) {
        if (expected != 'PAID' && order.status in ['PAID', 'CANCELLED', 'CLOSED']) {
            break
        }
        Thread.sleep(1_000L)
        order = getOrder(orderId)
    }
    if (order.status != expected) {
        throw new IllegalStateException(
                'order ' + orderId + ' expected status ' + expected + ' but was ' + order.status)
    }
    return order
}

def readMembershipOffers = {
    Map response = requireHttp(
            request('GET', '/api/user/membership-plan-offers', authHeaders, null),
            [200],
            'get membership plan offers')
    Map body = (Map) json.parseText(response.body)
    List<Map> offers = (List<Map>) body.offers
    return offers ?: []
}

def resolveTargetTier = {
    List<Map> offers = readMembershipOffers()
    if (offers == null || offers.isEmpty()) {
        throw new IllegalStateException('Test account has no legal higher membership offer.')
    }
    String requestedTier = vars.get('targetTier')
    Map requested = offers.find { offer -> offer.targetTier == requestedTier }
    return ((requested ?: offers.first()).targetTier) as String
}

def waitForEntitlementVisibility = { String paidTargetTier ->
    Instant entitlementDeadline = Instant.now().plusSeconds(60L)
    while (true) {
        List<Map> offers = readMembershipOffers()
        if (!offers.any { offer -> offer.targetTier == paidTargetTier }) {
            return
        }
        if (!Instant.now().isBefore(entitlementDeadline)) {
            throw new IllegalStateException(
                    'Paid membership entitlement was not visible before account reuse.')
        }
        // Redis 订单先进入 PAID 不代表 PostgreSQL 权益事务和用户资料缓存失效已经完成；
        // 账号锁必须覆盖这一窗口，避免后一个案例冻结随后会变成非法的旧套餐报价。
        Thread.sleep(250L)
    }
}

def sign = { String tradeNo, String orderId ->
    String canonical = ['SIMULATED', callbackPid, tradeNo, orderId, 'TRADE_SUCCESS'].join('\n')
    Mac mac = Mac.getInstance('HmacSHA256')
    mac.init(new SecretKeySpec(callbackKey.getBytes(StandardCharsets.UTF_8), 'HmacSHA256'))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(
            mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)))
}

def callbackFields = { String orderId, String money, String tradeNo ->
    Instant now = Instant.now()
    String paymentTime = liuhaoTime.format(now)
    return new LinkedHashMap<String, String>([
            pid: callbackPid,
            trade_no: tradeNo,
            out_trade_no: orderId,
            api_trade_no: tradeNo,
            type: vars.get('payType'),
            trade_status: 'TRADE_SUCCESS',
            addtime: paymentTime,
            endtime: paymentTime,
            name: 'membership-loadtest',
            money: money,
            param: scenario,
            buyer: 'loadtest-buyer',
            timestamp: Long.toString(now.epochSecond),
            sign: sign(tradeNo, orderId),
            sign_type: 'RSA'
    ])
}

def encodeForm = { Map<String, String> fields ->
    return fields.collect { name, value ->
        URLEncoder.encode(name, StandardCharsets.UTF_8) + '=' +
                URLEncoder.encode(value, StandardCharsets.UTF_8)
    }.join('&')
}

def sendCallback = { String orderId, String money, String tradeNo, String transport ->
    Map<String, String> fields = callbackFields(orderId, money, tradeNo)
    Map<String, String> headers = [
            'X-Simulated-Payment-Key': callbackKey,
            'Accept': 'text/plain'
    ]
    Map response
    if (transport == 'GET') {
        response = request(
                'GET',
                '/internal/test/membership-payments/liuhao/notify?' + encodeForm(fields),
                headers,
                null)
    } else if (transport == 'POST_FORM') {
        headers['Content-Type'] = 'application/x-www-form-urlencoded; charset=UTF-8'
        response = request(
                'POST',
                '/internal/test/membership-payments/liuhao/notify',
                headers,
                encodeForm(fields))
    } else if (transport == 'POST_JSON') {
        headers['Content-Type'] = 'application/json; charset=UTF-8'
        response = request(
                'POST',
                '/internal/test/membership-payments/liuhao/notify',
                headers,
                JsonOutput.toJson(fields))
    } else {
        throw new IllegalArgumentException('Unsupported callback transport: ' + transport)
    }
    requireHttp(response, [200], 'payment callback')
    if (response.body != 'success' || !response.contentType.toLowerCase().startsWith('text/plain')) {
        throw new IllegalStateException(
                'legal callback must return exact text/plain success acknowledgement')
    }
    return response
}

def parallelCallbacks = { List<Map<String, String>> calls ->
    def executor = Executors.newFixedThreadPool(calls.size())
    try {
        def futures = calls.collect { call ->
            executor.submit({ ->
                sendCallback(call.orderId, call.money, call.tradeNo, call.protocol)
                return true
            } as Callable<Boolean>)
        }
        futures.each { future -> future.get() }
    } finally {
        executor.shutdownNow()
    }
}

def workerControl = { String action, Integer maxPauseSeconds ->
    String path = '/internal/test/membership-payments/loadtest-control/workers/' + action
    if (maxPauseSeconds != null) {
        path += '?maxPauseSeconds=' + maxPauseSeconds
    }
    return requireHttp(request('POST', path, ['Accept': 'application/json'], null), [200],
            'worker control ' + action)
}

def cancelWithConcurrentAttempts = { String orderId, int attemptCount ->
    def executor = Executors.newFixedThreadPool(attemptCount + 1)
    try {
        def cancelFuture = executor.submit({ ->
            return request(
                    'POST',
                    '/api/user/membership-orders/' + orderId + '/cancel',
                    authHeaders,
                    null)
        } as Callable<Map>)
        def attemptFutures = (0..<attemptCount).collect {
            executor.submit({ ->
                return request(
                        'POST',
                        '/api/user/membership-orders/' + orderId + '/payment-attempts',
                        authHeaders,
                        null)
            } as Callable<Map>)
        }
        requireHttp(cancelFuture.get(), [200], 'concurrent cancel order')
        attemptFutures.each { future ->
            requireHttp(future.get(), [200, 201, 409], 'concurrent payment attempt')
        }
    } finally {
        executor.shutdownNow()
    }
}

def createOrderAfterPersistenceBarrier = { Map<String, String> headers, String body ->
    Instant createDeadline = Instant.now().plusSeconds(30L)
    while (true) {
        Map createResponse = request(
                'POST', '/api/user/membership-orders', headers, body)
        if (createResponse.status == 201) {
            return (Map) json.parseText(createResponse.body)
        }
        // Redis 终态可能先于 PostgreSQL 权益裁决可见；同账号下一个案例必须沿用同一幂等键有限等待，
        // 不能把这一致性窗口误判为产品缺陷，更不能通过放宽单活动订单约束绕过数据库裁决。
        if (createResponse.status == 409 && Instant.now().isBefore(createDeadline)) {
            Thread.sleep(250L)
            continue
        }
        requireHttp(
                createResponse,
                [201],
                'create order after previous terminal persistence')
    }
}

// 同一 JMeter 进程会循环使用 16 个固定账号；每账号串行创建订单，避免测试器自身违反单活动订单约束。
String accountLockKey = 'membership-state-account-lock:' + vars.get('userId')
Semaphore accountLock
synchronized (props) {
    accountLock = (Semaphore) props.get(accountLockKey)
    if (accountLock == null) {
        accountLock = new Semaphore(1, true)
        props.put(accountLockKey, accountLock)
    }
}
if (!accountLock.tryAcquire(30L, TimeUnit.MINUTES)) {
    throw new IllegalStateException('Timed out waiting for the fixed test account to become idle.')
}

boolean workersPaused = false

try {
    String idempotencyKey = UUID.randomUUID().toString()
    String targetTier = resolveTargetTier()
    String createBody = JsonOutput.toJson([
            targetTier: targetTier,
            payType: vars.get('payType'),
            idempotencyKey: idempotencyKey
    ])
    Map createHeaders = new LinkedHashMap<>(authHeaders)
    createHeaders['Content-Type'] = 'application/json'
    Map order = createOrderAfterPersistenceBarrier(createHeaders, createBody)
    String orderId = order.orderId
    String money = order.payAmountYuan
    Instant createdAt = OffsetDateTime.parse(order.createdAt, iso).toInstant()
    Instant expiresAt = OffsetDateTime.parse(order.expiresAt, iso).toInstant()
    Instant hardCloseAt = expiresAt.plusSeconds(300L)

    if (Boolean.parseBoolean(vars.get('startPayment'))) {
        requireHttp(
                request(
                        'POST',
                        '/api/user/membership-orders/' + orderId + '/payment-attempts',
                        authHeaders,
                        null),
                [200, 201],
                'start payment')
    }

    def anchor = { String name ->
        switch (name) {
            case 'CREATED': return createdAt
            case 'EXPIRES': return expiresAt
            case 'HARD_CLOSE': return hardCloseAt
            case 'NONE': return Instant.now()
            default: throw new IllegalArgumentException('Unknown time anchor: ' + name)
        }
    }

    if (vars.get('cancelAnchor') != 'NONE') {
        Instant cancelAt = anchor(vars.get('cancelAnchor'))
                .plusSeconds(Long.parseLong(vars.get('cancelOffsetSeconds')))
        waitUntil(cancelAt)
        switch (vars.get('raceMode')) {
            case 'CANCEL_DURING_PERSIST_PAUSE':
                workerControl('pause', 180)
                workersPaused = true
                requireHttp(
                        request(
                                'POST',
                                '/api/user/membership-orders/' + orderId + '/cancel',
                                authHeaders,
                                null),
                        [200],
                        'cancel while order persistence is paused')
                break
            case 'CANCEL_PAYMENT_ATTEMPT_PARALLEL':
                cancelWithConcurrentAttempts(orderId, 1)
                break
            case 'CANCEL_IDEMPOTENT_REPLAY_PARALLEL':
                cancelWithConcurrentAttempts(orderId, 5)
                break
            default:
                requireHttp(
                        request(
                                'POST',
                                '/api/user/membership-orders/' + orderId + '/cancel',
                                authHeaders,
                                null),
                        [200],
                        'cancel order')
                break
        }
        waitForStatus(orderId, 'CANCELLED', Instant.now().plusSeconds(20L))
        if (workersPaused) {
            // 保留 Redis 已终态、数据库仍可能滞后的观察窗口，再恢复真实刷盘 Worker 完成收敛。
            Thread.sleep(3_000L)
            workerControl('resume', null)
            workersPaused = false
        }
    }

    Instant callbackTarget = anchor(vars.get('callbackAnchor'))
            .plusSeconds(Long.parseLong(vars.get('callbackOffsetSeconds')))

    // CLOSING 场景从 expiresAt 起就观察真实状态，再等待目标回调时刻；如果到 hardCloseAt-1s 才开始
    // 一秒轮询，会由测试器自身把本应合法的边界回调拖过硬截止。
    if (vars.get('group') == 'CLOSING') {
        waitUntil(expiresAt)
        Instant observationDeadline = [
                callbackTarget.plusSeconds(30L),
                hardCloseAt.minusMillis(250L)
        ].min()
        waitForStatus(orderId, 'CLOSING', observationDeadline)
        waitUntil(callbackTarget)
        if (!Instant.now().isBefore(hardCloseAt)) {
            throw new IllegalStateException('CLOSING callback missed hardCloseAt.')
        }
    } else if (vars.get('group') == 'CLOSED') {
        waitUntil(callbackTarget)
        waitForStatus(orderId, 'CLOSED', Instant.now().plusSeconds(60L))
    } else {
        waitUntil(callbackTarget)
    }

    if (vars.get('paymentAttemptCheck') == 'REJECT_BEFORE_CALLBACK') {
        requireHttp(
                request(
                        'POST',
                        '/api/user/membership-orders/' + orderId + '/payment-attempts',
                        authHeaders,
                        null),
                [409],
                vars.get('group') + ' payment attempt rejection')
    }

    String firstTradeNo = vars.get('expectedResolution') == 'NONE'
            ? 'NONE'
            : 'JMX-' + runId + '-' + scenario + '-0'
    Instant callbackSentAt = Instant.now()
    if (vars.get('expectedResolution') != 'NONE') {
        String callbackMoney = vars.get('expectedResolution') == 'REJECTED'
                ? new BigDecimal(money)
                        .add(new BigDecimal('0.01'))
                        .setScale(2, RoundingMode.UNNECESSARY)
                        .toPlainString()
                : money
        sendCallback(orderId, callbackMoney, firstTradeNo, vars.get('protocol'))
    }

    // PAID 重放必须在首次回调已经完成状态迁移后进行，证明测试针对的是终态而非 ready 队列竞态。
    if (vars.get('group') == 'PAID') {
        waitForStatus(orderId, 'PAID', Instant.now().plusSeconds(120L))
        if (vars.get('paymentAttemptCheck') == 'REJECT_AFTER_FIRST_CALLBACK') {
            requireHttp(
                    request(
                            'POST',
                            '/api/user/membership-orders/' + orderId + '/payment-attempts',
                            authHeaders,
                            null),
                    [409],
                    'PAID payment attempt rejection')
        }
    } else if (vars.get('replayMode') != 'NONE') {
        Thread.sleep(8_000L)
    }

    switch (vars.get('replayMode')) {
        case 'NONE':
            break
        case 'SAME_5':
        case 'SAME_5_GET':
        case 'SAME_5_FORM':
        case 'SAME_5_JSON':
            String replayProtocol = vars.get('replayMode') == 'SAME_5_FORM'
                    ? 'POST_FORM'
                    : vars.get('replayMode') == 'SAME_5_JSON'
                            ? 'POST_JSON'
                            : 'GET'
            5.times { sendCallback(orderId, money, firstTradeNo, replayProtocol) }
            break
        case 'MIXED_SAME_PARALLEL':
            parallelCallbacks((0..<15).collect { index -> [
                    orderId: orderId,
                    money: money,
                    tradeNo: firstTradeNo,
                    protocol: ['GET', 'POST_FORM', 'POST_JSON'][index % 3]
            ]})
            break
        case 'DIFFERENT_5_PARALLEL':
            parallelCallbacks((1..5).collect { index -> [
                    orderId: orderId,
                    money: money,
                    tradeNo: 'JMX-' + runId + '-' + scenario + '-' + index,
                    protocol: ['GET', 'POST_FORM', 'POST_JSON'][index % 3]
            ]})
            break
        case 'MIXED_SAME_AND_DIFFERENT':
            parallelCallbacks((0..<10).collect { index -> [
                    orderId: orderId,
                    money: money,
                    tradeNo: index < 5 ? firstTradeNo : 'JMX-' + runId + '-' + scenario + '-' + index,
                    protocol: ['GET', 'POST_FORM', 'POST_JSON'][index % 3]
            ]})
            break
        default:
            throw new IllegalArgumentException('Unsupported replay mode: ' + vars.get('replayMode'))
    }

    Instant finalDeadline = vars.get('expectedStatus') == 'CLOSED'
            ? [Instant.now().plusSeconds(120L), hardCloseAt.plusSeconds(180L)].max()
            : Instant.now().plusSeconds(120L)
    Map finalOrder = waitForStatus(orderId, vars.get('expectedStatus'), finalDeadline)
    if (vars.get('expectedStatus') == 'PAID'
            && vars.get('expectedResolution') == 'APPLIED') {
        waitForEntitlementVisibility(targetTier)
    }
    Path output = Path.of(props.getProperty('SCENARIO_ORDERS_CSV'))
    String header = 'run_id,user_id,idempotency_key,order_id,scenario,scenario_group,expected_status,expected_resolution,payment_started_required,target_callback_at,actual_callback_at,callback_drift_millis,provider_trade_no,protocol\n'
    String row = [
            runId,
            vars.get('userId'),
            idempotencyKey,
            orderId,
            scenario,
            vars.get('group'),
            vars.get('expectedStatus'),
            vars.get('expectedResolution'),
            Boolean.toString(Boolean.parseBoolean(vars.get('startPayment'))),
            callbackTarget.toString(),
            callbackSentAt.toString(),
            Long.toString(callbackSentAt.toEpochMilli() - callbackTarget.toEpochMilli()),
            firstTradeNo,
            vars.get('protocol')
    ].join(',') + '\n'
    synchronized (props) {
        Files.createDirectories(output.parent)
        if (!Files.exists(output)) {
            Files.writeString(output, header, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)
        }
        Files.writeString(output, row, StandardCharsets.UTF_8, StandardOpenOption.APPEND)
    }
    SampleResult.setResponseCode('200')
    SampleResult.setResponseMessage(
            scenario + ' final=' + finalOrder.status + ' driftMs=' +
                    (callbackSentAt.toEpochMilli() - callbackTarget.toEpochMilli()))
    SampleResult.setResponseData(
            JsonOutput.toJson([
                    scenario: scenario,
                    orderId: orderId,
                    expectedStatus: vars.get('expectedStatus'),
                    actualStatus: finalOrder.status,
                    callbackDriftMillis: callbackSentAt.toEpochMilli() - callbackTarget.toEpochMilli()
            ]),
            StandardCharsets.UTF_8.name())
    SampleResult.setSuccessful(true)
} catch (Throwable failure) {
    // 失败堆栈只写本地 JMeter 日志，便于把具体业务步骤与通用 sampler 标签区分；日志不包含 Access Token。
    log.error('Membership state scenario failed: ' + scenario, failure)
    SampleResult.setResponseCode('500')
    SampleResult.setResponseMessage(scenario + ': ' + failure.class.simpleName)
    SampleResult.setResponseData(
            (failure.message ?: failure.class.name).take(1024),
            StandardCharsets.UTF_8.name())
    SampleResult.setSuccessful(false)
} finally {
    if (workersPaused) {
        try {
            workerControl('resume', null)
        } catch (Throwable resumeFailure) {
            log.error('Failed to resume local membership workers after state case.', resumeFailure)
        }
    }
    accountLock.release()
}
