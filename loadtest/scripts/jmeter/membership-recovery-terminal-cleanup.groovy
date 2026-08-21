import groovy.json.JsonOutput
import groovy.json.JsonSlurper
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
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

if (props.getProperty('MODE', '') != 'loadtest-realtime') {
    throw new IllegalStateException('Recovery JMX requires loadtest-realtime.')
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

def createOrder = {
    String idempotencyKey = UUID.randomUUID().toString()
    Map headers = new LinkedHashMap<>(authHeaders)
    headers['Content-Type'] = 'application/json'
    Map response = request(
            'POST',
            '/api/user/membership-orders',
            headers,
            JsonOutput.toJson([targetTier: 'GO', payType: 'alipay', idempotencyKey: idempotencyKey]))
    if (response.status != 201) {
        throw new IllegalStateException('Recovery setup order failed: ' + response.status)
    }
    return [idempotencyKey: idempotencyKey, order: (Map) json.parseText(response.body)]
}

def getOrder = { String orderId ->
    Map response = request('GET', '/api/user/membership-orders/' + orderId, authHeaders, null)
    if (response.status != 200) {
        throw new IllegalStateException('Recovery order lookup failed: ' + response.status)
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
        throw new IllegalStateException('Expected ' + expected + ' but found ' + order.status)
    }
    return order
}

def startPayment = { String orderId ->
    Map response = request(
            'POST', '/api/user/membership-orders/' + orderId + '/payment-attempts',
            authHeaders, null)
    if (!(response.status in [200, 201])) {
        throw new IllegalStateException('Recovery payment attempt failed: ' + response.status)
    }
    // 模拟供应商时间只有秒精度；跨过下一整秒后再回调，保证截断时间不会早于微秒级支付发起事实。
    long millisToNextSecond = 1000L - (System.currentTimeMillis() % 1000L) + 25L
    Thread.sleep(millisToNextSecond)
}

def cancel = { String orderId ->
    Map response = request(
            'POST', '/api/user/membership-orders/' + orderId + '/cancel', authHeaders, null)
    if (response.status != 200) {
        throw new IllegalStateException('Recovery cancellation failed: ' + response.status)
    }
}

def sign = { String tradeNo, String orderId ->
    String canonical = ['SIMULATED', callbackPid, tradeNo, orderId, 'TRADE_SUCCESS'].join('\n')
    Mac mac = Mac.getInstance('HmacSHA256')
    mac.init(new SecretKeySpec(callbackKey.getBytes(StandardCharsets.UTF_8), 'HmacSHA256'))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(
            mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)))
}

def callback = { Map order, String tradeNo ->
    Instant now = Instant.now()
    String paidTime = formatter.format(now)
    Map fields = [
            pid: callbackPid,
            trade_no: tradeNo,
            out_trade_no: order.orderId,
            api_trade_no: tradeNo,
            type: 'alipay',
            trade_status: 'TRADE_SUCCESS',
            addtime: paidTime,
            endtime: paidTime,
            name: 'membership-recovery',
            money: order.payAmountYuan,
            param: vars.get('caseName'),
            buyer: 'recovery-buyer',
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
        throw new IllegalStateException('Recovery callback was not acknowledged')
    }
}

def control = { String action ->
    Map response = request(
            'POST', '/internal/test/membership-payments/loadtest-control/' + action,
            ['Accept': 'application/json'], null)
    if (!(response.status in [200, 204])) {
        throw new IllegalStateException('Loadtest control action failed: ' + action + ' ' + response.status)
    }
    return response.body ? (Map) json.parseText(response.body) : [:]
}

def armCallbackCompleteFailure = { String orderId ->
    String encoded = URLEncoder.encode(orderId, StandardCharsets.UTF_8)
    Map response = request(
            'POST',
            '/internal/test/membership-payments/loadtest-control/' +
                    'arm-callback-complete-failure?orderId=' + encoded,
            ['Accept': 'application/json'],
            null)
    if (response.status != 200) {
        throw new IllegalStateException(
                'Callback complete failure arm failed: ' + response.status)
    }
    return (Map) json.parseText(response.body)
}

def faultCount = {
    Map response = request(
            'GET',
            '/internal/test/membership-payments/loadtest-control/faults',
            ['Accept': 'application/json'],
            null)
    if (response.status != 200) {
        throw new IllegalStateException('Loadtest fault probe failed: ' + response.status)
    }
    return ((Map) json.parseText(response.body)).callbackCompleteFailureCount as long
}

def waitForRedisCleanup = { String orderId ->
    Instant deadline = Instant.now().plusSeconds(60L)
    Map state = [:]
    while (Instant.now().isBefore(deadline)) {
        control('flush')
        String encoded = URLEncoder.encode(orderId, StandardCharsets.UTF_8)
        Map response = request(
                'GET', '/internal/test/membership-payments/loadtest-control/state?orderId=' + encoded,
                ['Accept': 'application/json'], null)
        if (response.status != 200) {
            throw new IllegalStateException('Redis cleanup probe failed: ' + response.status)
        }
        state = (Map) json.parseText(response.body)
        boolean clean = !state.snapshotPresent && !state.callbackMarkerPresent &&
                (state.callbackReadySize as long) == 0L &&
                (state.callbackProcessingSize as long) == 0L &&
                (state.dirtySize as long) == 0L &&
                (state.dirtyProcessingSize as long) == 0L
        if (clean) {
            return true
        }
        Thread.sleep(1_000L)
    }
    throw new IllegalStateException('Terminal Redis artifacts did not settle: ' + state)
}

def appendOrder = { String idempotencyKey, Map order, String expectedStatus,
                    String expectedResolution, String tradeNo,
                    int claimed, int recovered, long faultBefore, long faultAfter,
                    boolean redisClean ->
    Path output = Path.of(props.getProperty('SCENARIO_ORDERS_CSV'))
    String header = 'run_id,user_id,idempotency_key,order_id,case_name,expected_status,expected_resolution,provider_trade_no,recovered_claimed,recovered_count,fault_before,fault_after,redis_clean\n'
    String row = [runId, vars.get('userId'), idempotencyKey, order.orderId,
            vars.get('caseName'), expectedStatus, expectedResolution, tradeNo,
            claimed, recovered, faultBefore, faultAfter, redisClean].join(',') + '\n'
    synchronized (props) {
        Files.createDirectories(output.parent)
        if (!Files.exists(output)) {
            Files.writeString(output, header, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)
        }
        Files.writeString(output, row, StandardCharsets.UTF_8, StandardOpenOption.APPEND)
    }
}

try {
    Map setup = createOrder()
    Map order = (Map) setup.order
    String tradeNo = ('REC-' + runId + '-' + vars.get('caseName')).take(120)
    int claimed = 0
    int recovered = 0
    long faultBefore = 0L
    long faultAfter = 0L
    String expectedStatus
    String expectedResolution

    switch (vars.get('recoveryMode')) {
        case 'CALLBACK_PROCESSING_TIMEOUT':
            startPayment(order.orderId)
            callback(order, tradeNo)
            Map callbackProbe = control('recover-callback')
            claimed = callbackProbe.claimed as int
            recovered = callbackProbe.recovered as int
            waitForStatus(order.orderId, 'PAID', Instant.now().plusSeconds(120L))
            expectedStatus = 'PAID'
            expectedResolution = 'APPLIED'
            break
        case 'DIRTY_PROCESSING_TIMEOUT':
            cancel(order.orderId)
            Map dirtyProbe = control('recover-order')
            claimed = dirtyProbe.claimed as int
            recovered = dirtyProbe.recovered as int
            waitForStatus(order.orderId, 'CANCELLED', Instant.now().plusSeconds(60L))
            expectedStatus = 'CANCELLED'
            expectedResolution = 'NONE'
            tradeNo = ''
            break
        case 'DB_COMMITTED_COMPLETE_RETRY':
            startPayment(order.orderId)
            Map armed = armCallbackCompleteFailure(order.orderId)
            faultBefore = armed.callbackCompleteFailureCount as long
            callback(order, tradeNo)
            waitForStatus(order.orderId, 'PAID', Instant.now().plusSeconds(120L))
            Instant faultDeadline = Instant.now().plusSeconds(120L)
            while (Instant.now().isBefore(faultDeadline)) {
                control('flush')
                faultAfter = faultCount()
                if (faultAfter > faultBefore) {
                    break
                }
                Thread.sleep(1_000L)
            }
            if (faultAfter != faultBefore + 1L) {
                throw new IllegalStateException(
                        'Callback complete failure did not trigger exactly once.')
            }
            // 第二次正式刷盘必须命中数据库已提交的原 callback，并完成 processing/data/marker 清理。
            control('flush')
            expectedStatus = 'PAID'
            expectedResolution = 'APPLIED'
            break
        case 'PAID_TERMINAL_CLEANUP':
            startPayment(order.orderId)
            callback(order, tradeNo)
            waitForStatus(order.orderId, 'PAID', Instant.now().plusSeconds(120L))
            expectedStatus = 'PAID'
            expectedResolution = 'APPLIED'
            break
        case 'CANCELLED_TERMINAL_CLEANUP':
            cancel(order.orderId)
            waitForStatus(order.orderId, 'CANCELLED', Instant.now().plusSeconds(60L))
            expectedStatus = 'CANCELLED'
            expectedResolution = 'NONE'
            tradeNo = ''
            break
        case 'CLOSED_TERMINAL_CLEANUP':
            Instant expiresAt = OffsetDateTime.parse(order.expiresAt).toInstant()
            waitUntil(expiresAt.plusSeconds(301L))
            waitForStatus(order.orderId, 'CLOSED', Instant.now().plusSeconds(60L))
            expectedStatus = 'CLOSED'
            expectedResolution = 'NONE'
            tradeNo = ''
            break
        case 'DEDUPE_TTL_DATABASE_FALLBACK':
            startPayment(order.orderId)
            callback(order, tradeNo)
            waitForStatus(order.orderId, 'PAID', Instant.now().plusSeconds(120L))
            Thread.sleep(31_000L)
            callback(order, (tradeNo + '-NEW').take(128))
            control('flush')
            waitForStatus(order.orderId, 'PAID', Instant.now().plusSeconds(60L))
            expectedStatus = 'PAID'
            expectedResolution = 'APPLIED'
            break
        default:
            throw new IllegalStateException('Unknown recovery mode: ' + vars.get('recoveryMode'))
    }

    boolean redisClean = waitForRedisCleanup(order.orderId)
    appendOrder(setup.idempotencyKey, order, expectedStatus, expectedResolution,
            tradeNo, claimed, recovered, faultBefore, faultAfter, redisClean)
    if (vars.get('recoveryMode') in ['CALLBACK_PROCESSING_TIMEOUT', 'DIRTY_PROCESSING_TIMEOUT'] &&
            (claimed != 1 || recovered != 1)) {
        throw new IllegalStateException('Recovery probe did not claim and recover exactly one item.')
    }
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
}
