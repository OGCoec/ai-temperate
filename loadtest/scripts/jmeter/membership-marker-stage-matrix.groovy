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
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

if (props.getProperty('MODE', '') != 'loadtest-realtime') {
    throw new IllegalStateException('Marker matrix JMX requires loadtest-realtime.')
}

def baseUrl = props.getProperty('PROTOCOL', 'http') + '://' +
        props.getProperty('HOST', 'localhost') + ':' + props.getProperty('PORT', '8080')
def callbackPid = props.getProperty('CALLBACK_PID', 'loadtest-merchant')
def callbackKey = props.getProperty(
        'CALLBACK_KEY', 'membership-loadtest-callback-key-v1-local')
def runId = props.getProperty('RUN_ID', 'local')
def json = new JsonSlurper()
def formatter = DateTimeFormatter.ofPattern('yyyy-MM-dd HH:mm:ss').withZone(ZoneOffset.UTC)
def authHeaders = [
        'Authorization': 'Bearer ' + vars.get('accessToken'),
        'Accept': 'application/json'
]

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
    def stream = status >= 400 ? connection.errorStream : connection.inputStream
    String responseBody = stream == null ? '' : stream.withCloseable { input ->
        new String(input.readAllBytes(), StandardCharsets.UTF_8)
    }
    connection.disconnect()
    return [status: status, body: responseBody]
}

def waitUntil = { Instant target ->
    while (target.toEpochMilli() > System.currentTimeMillis()) {
        Thread.sleep(Math.min(1_000L, target.toEpochMilli() - System.currentTimeMillis()))
    }
}

def getOrder = { String orderId ->
    Map response = request('GET', '/api/user/membership-orders/' + orderId, authHeaders, null)
    if (response.status != 200) {
        throw new IllegalStateException('Marker order lookup failed: ' + response.status)
    }
    return (Map) json.parseText(response.body)
}

def waitForStatus = { String orderId, String expected, Instant deadline ->
    Map order = getOrder(orderId)
    while (order.status != expected && Instant.now().isBefore(deadline)) {
        Thread.sleep(1_000L)
        order = getOrder(orderId)
    }
    if (order.status != expected) {
        throw new IllegalStateException(
                'Marker case expected ' + expected + ' but was ' + order.status)
    }
    return order
}

def readMembershipOffers = {
    Map response = request('GET', '/api/user/membership-plan-offers', authHeaders, null)
    if (response.status != 200) {
        throw new IllegalStateException('Membership offer lookup failed: ' + response.status)
    }
    Map body = (Map) json.parseText(response.body)
    return ((List) body.offers) ?: []
}

def nextTier = {
    List offers = readMembershipOffers()
    if (offers == null || offers.isEmpty()) {
        throw new IllegalStateException('Test account has no legal higher membership offer.')
    }
    return ((Map) offers.first()).targetTier as String
}

def waitForEntitlementVisibility = { String paidTargetTier ->
    Instant entitlementDeadline = Instant.now().plusSeconds(60L)
    while (true) {
        List offers = readMembershipOffers()
        if (!offers.any { offer -> offer.targetTier == paidTargetTier }) {
            return
        }
        if (!Instant.now().isBefore(entitlementDeadline)) {
            throw new IllegalStateException(
                    'Marker paid entitlement was not visible before account reuse.')
        }
        // Marker Worker 先写 PAID 时，权益事务仍可能尚未提交；账号锁覆盖到报价缓存同步完成，
        // 防止同一波次的后一个阶段案例再次冻结旧套餐。
        Thread.sleep(250L)
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
        if (createResponse.status == 409 && Instant.now().isBefore(createDeadline)) {
            Thread.sleep(250L)
            continue
        }
        throw new IllegalStateException(
                'Marker setup order failed after persistence barrier: ' +
                        createResponse.status)
    }
}

def sign = { String tradeNo, String orderId ->
    String canonical = ['SIMULATED', callbackPid, tradeNo, orderId, 'TRADE_SUCCESS'].join('\n')
    Mac mac = Mac.getInstance('HmacSHA256')
    mac.init(new SecretKeySpec(callbackKey.getBytes(StandardCharsets.UTF_8), 'HmacSHA256'))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(
            mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)))
}

def sendCallback = { Map order, String tradeNo, String resolution ->
    Instant now = Instant.now()
    String paidTime = formatter.format(now)
    String money = resolution == 'APPLIED'
            ? order.payAmountYuan as String
            : new BigDecimal(order.payAmountYuan as String)
                    .add(new BigDecimal('0.01'))
                    .setScale(2, RoundingMode.UNNECESSARY)
                    .toPlainString()
    Map fields = [
            pid: callbackPid,
            trade_no: tradeNo,
            out_trade_no: order.orderId,
            api_trade_no: tradeNo,
            type: 'alipay',
            trade_status: 'TRADE_SUCCESS',
            addtime: paidTime,
            endtime: paidTime,
            name: 'membership-marker-matrix',
            money: money,
            param: vars.get('caseName'),
            buyer: 'marker-buyer',
            timestamp: Long.toString(now.epochSecond),
            sign: sign(tradeNo, order.orderId),
            sign_type: 'RSA'
    ]
    String query = fields.collect { name, value ->
        URLEncoder.encode(name, StandardCharsets.UTF_8) + '=' +
                URLEncoder.encode(value, StandardCharsets.UTF_8)
    }.join('&')
    Map response = request(
            'GET',
            '/internal/test/membership-payments/liuhao/notify?' + query,
            ['X-Simulated-Payment-Key': callbackKey, 'Accept': 'text/plain'],
            null)
    if (response.status != 200 || response.body != 'success') {
        throw new IllegalStateException('Marker callback was not acknowledged.')
    }
}

def holdControl = { String action, String orderId, Integer seconds ->
    String encoded = URLEncoder.encode(orderId, StandardCharsets.UTF_8)
    String query = '?orderId=' + encoded
    if (seconds != null) {
        query += '&maxHoldSeconds=' + seconds
    }
    String method = action == 'inspect' ? 'GET' : 'POST'
    String suffix = action == 'inspect' ? '' : '/' + action
    Map response = request(
            method,
            '/internal/test/membership-payments/loadtest-control/callback-hold' + suffix + query,
            ['Accept': 'application/json'],
            null)
    if (response.status != 200) {
        throw new IllegalStateException('Marker hold control failed: ' + action + ' ' + response.status)
    }
    return (Map) json.parseText(response.body)
}

def waitForMarker = { String orderId ->
    Instant deadline = Instant.now().plusSeconds(20L)
    Map probe = [:]
    while (Instant.now().isBefore(deadline)) {
        probe = holdControl('inspect', orderId, null)
        if (probe.markerPresent) {
            return probe
        }
        Thread.sleep(250L)
    }
    throw new IllegalStateException('Callback Marker was not written: ' + probe)
}

def appendOrder = { String idempotencyKey, Map order, String expectedStatus,
                    String expectedResolution, String tradeNo, Instant targetAt,
                    Instant callbackAt ->
    Path output = Path.of(props.getProperty('SCENARIO_ORDERS_CSV'))
    String header = 'run_id,user_id,idempotency_key,order_id,case_name,phase,target_stage,expected_status,expected_resolution,provider_trade_no,target_at,callback_at\n'
    String row = [runId, vars.get('userId'), idempotencyKey, order.orderId,
            vars.get('caseName'), vars.get('markerPhase'), vars.get('targetStage'),
            expectedStatus, expectedResolution, tradeNo, targetAt, callbackAt].join(',') + '\n'
    synchronized (props) {
        Files.createDirectories(output.parent)
        if (!Files.exists(output)) {
            Files.writeString(output, header, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)
        }
        Files.writeString(output, row, StandardCharsets.UTF_8, StandardOpenOption.APPEND)
    }
}

String accountLockKey = 'membership.marker.account.lock.' + vars.get('userId')
Semaphore accountLock
synchronized (props) {
    accountLock = (Semaphore) props.get(accountLockKey)
    if (accountLock == null) {
        accountLock = new Semaphore(1, true)
        props.put(accountLockKey, accountLock)
    }
}
boolean acquired = accountLock.tryAcquire(22L, TimeUnit.MINUTES)
if (!acquired) {
    throw new IllegalStateException('Timed out waiting for the fixed account lane.')
}

try {
    String idempotencyKey = UUID.randomUUID().toString()
    String targetTier = nextTier()
    Map headers = new LinkedHashMap<>(authHeaders)
    headers['Content-Type'] = 'application/json'
    String createBody = JsonOutput.toJson([
            targetTier: targetTier,
            payType: 'alipay',
            idempotencyKey: idempotencyKey
    ])
    Map order = createOrderAfterPersistenceBarrier(headers, createBody)
    Map attempt = request(
            'POST',
            '/api/user/membership-orders/' + order.orderId + '/payment-attempts',
            authHeaders,
            null)
    if (!(attempt.status in [200, 201])) {
        throw new IllegalStateException('Marker Payment Attempt failed: ' + attempt.status)
    }

    Instant createdAt = OffsetDateTime.parse(order.createdAt).toInstant()
    Instant expiresAt = OffsetDateTime.parse(order.expiresAt).toInstant()
    Instant hardCloseAt = expiresAt.plusSeconds(300L)
    int stage = Integer.parseInt(vars.get('targetStage'))
    long[] pendingOffsets = [10L, 20L, 30L, 45L, 60L, 90L, 120L, 180L, 300L] as long[]
    long[] closingOffsets = [30L, 60L, 120L, 180L, 300L] as long[]
    Instant targetAt = vars.get('markerPhase') == 'PENDING'
            ? createdAt.plusSeconds(pendingOffsets[stage])
            : expiresAt.plusSeconds(closingOffsets[stage])

    waitUntil(targetAt.minusSeconds(3L))
    holdControl('arm', order.orderId, 180)
    String tradeNo = ('MARKER-' + runId + '-' + vars.get('caseName')).take(120)
    Instant callbackAt = Instant.now()
    sendCallback(order, tradeNo, vars.get('markerResolution'))
    waitForMarker(order.orderId)

    // 目标消费时刻后继续保持 Marker 十五秒，覆盖 Rabbit 调度抖动并防止过早释放形成假阳性。
    waitUntil(targetAt.plusSeconds(15L))
    Map held = holdControl('inspect', order.orderId, null)
    if (!held.armed || !held.markerPresent) {
        throw new IllegalStateException('Marker was not held through the target MQ stage.')
    }
    holdControl('release', order.orderId, null)

    String expectedStatus = vars.get('markerResolution') == 'APPLIED' ? 'PAID' : 'CLOSED'
    Instant statusDeadline = expectedStatus == 'PAID'
            ? Instant.now().plusSeconds(120L)
            : hardCloseAt.plusSeconds(180L)
    waitForStatus(order.orderId, expectedStatus, statusDeadline)
    if (vars.get('markerResolution') == 'APPLIED') {
        waitForEntitlementVisibility(targetTier)
    }
    appendOrder(idempotencyKey, order, expectedStatus, vars.get('markerResolution'),
            tradeNo, targetAt, callbackAt)

    SampleResult.setResponseCode('200')
    SampleResult.setResponseMessage(vars.get('caseName') + ' passed')
    SampleResult.setResponseData(
            JsonOutput.toJson([caseName: vars.get('caseName'), orderId: order.orderId]),
            StandardCharsets.UTF_8.name())
    SampleResult.setSuccessful(true)
} catch (Throwable failure) {
    SampleResult.setResponseCode('500')
    SampleResult.setResponseMessage(vars.get('caseName') + ': ' + failure.class.simpleName)
    SampleResult.setResponseData(
            (failure.message ?: failure.class.name).take(1024),
            StandardCharsets.UTF_8.name())
    SampleResult.setSuccessful(false)
} finally {
    accountLock.release()
}
