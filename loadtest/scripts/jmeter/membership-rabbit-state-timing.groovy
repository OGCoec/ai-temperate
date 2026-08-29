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
    throw new IllegalStateException('Rabbit timing JMX requires loadtest-realtime.')
}

def baseUrl = props.getProperty('PROTOCOL', 'http') + '://' +
        props.getProperty('HOST', 'localhost') + ':' + props.getProperty('PORT', '8080')
def callbackPid = props.getProperty('CALLBACK_PID', 'loadtest-merchant')
def callbackKey = props.getProperty(
        'CALLBACK_KEY', 'membership-loadtest-callback-key-v1-local')
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
    String contentType = connection.getHeaderField('Content-Type') ?: ''
    connection.disconnect()
    return [status: status, body: responseBody, contentType: contentType]
}

def waitUntil = { Instant target ->
    while (target.toEpochMilli() > System.currentTimeMillis()) {
        Thread.sleep(Math.min(1_000L, target.toEpochMilli() - System.currentTimeMillis()))
    }
}

def nextTier = {
    Map response = request('GET', '/api/user/membership-plan-offers', authHeaders, null)
    if (response.status != 200) {
        throw new IllegalStateException('Rabbit offer lookup failed: ' + response.status)
    }
    Map body = (Map) json.parseText(response.body)
    List offers = (List) body.offers
    if (offers == null || offers.isEmpty()) {
        throw new IllegalStateException('Rabbit account has no legal higher membership offer.')
    }
    return ((Map) offers.first()).targetTier as String
}

def getOrder = { String orderId ->
    Map response = request('GET', '/api/user/membership-orders/' + orderId, authHeaders, null)
    if (response.status != 200) {
        throw new IllegalStateException('Rabbit order lookup failed: ' + response.status)
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
        throw new IllegalStateException('Rabbit case expected ' + expected + ' but was ' + order.status)
    }
    return order
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
    String callbackMoney = resolution == 'REJECTED'
            ? new BigDecimal(order.payAmountYuan as String)
                    .add(new BigDecimal('0.01'))
                    .setScale(2)
                    .toPlainString()
            : order.payAmountYuan
    Map fields = [
            pid: callbackPid,
            trade_no: tradeNo,
            out_trade_no: order.orderId,
            api_trade_no: tradeNo,
            type: 'alipay',
            trade_status: 'TRADE_SUCCESS',
            addtime: paidTime,
            endtime: paidTime,
            name: 'membership-rabbit',
            money: callbackMoney,
            param: vars.get('caseName'),
            buyer: 'rabbit-buyer',
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
        throw new IllegalStateException('marker callback was not acknowledged')
    }
}

def markerHold = { String action, String orderId, Integer seconds ->
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
        throw new IllegalStateException('Rabbit Marker hold failed: ' + action)
    }
    return (Map) json.parseText(response.body)
}

def waitForMarker = { String orderId ->
    Instant deadline = Instant.now().plusSeconds(20L)
    Map probe = [:]
    while (Instant.now().isBefore(deadline)) {
        probe = markerHold('inspect', orderId, null)
        if (probe.markerPresent) {
            return probe
        }
        Thread.sleep(250L)
    }
    throw new IllegalStateException('Rabbit callback Marker was not written: ' + probe)
}

def runMarkerCase = { Map order, Instant targetAt, Instant hardCloseAt,
                      String resolution, String tradeNo ->
    waitUntil(targetAt.minusSeconds(3L))
    markerHold('arm', order.orderId, 180)
    try {
        sendCallback(order, tradeNo, resolution)
        waitForMarker(order.orderId)
        waitUntil(targetAt.plusSeconds(15L))
        Map held = markerHold('inspect', order.orderId, null)
        if (!held.armed || !held.markerPresent) {
            throw new IllegalStateException('Marker did not cover the target Rabbit stage.')
        }
    } finally {
        markerHold('release', order.orderId, null)
    }
    String expected = resolution == 'APPLIED' ? 'PAID' : 'CLOSED'
    Instant deadline = expected == 'PAID'
            ? Instant.now().plusSeconds(120L)
            : hardCloseAt.plusSeconds(180L)
    waitForStatus(order.orderId, expected, deadline)
    return expected
}

def appendOrder = { String idempotencyKey, Map order, String expectedStatus,
                    String expectedResolution, String tradeNo ->
    Path output = Path.of(props.getProperty('SCENARIO_ORDERS_CSV'))
    String header = 'run_id,user_id,idempotency_key,order_id,scenario,expected_status,expected_resolution,provider_trade_no\n'
    String row = [
            props.getProperty('RUN_ID', 'local'),
            vars.get('userId'),
            idempotencyKey,
            order.orderId,
            vars.get('caseName'),
            expectedStatus,
            expectedResolution,
            tradeNo
    ].join(',') + '\n'
    synchronized (props) {
        Files.createDirectories(output.parent)
        if (!Files.exists(output)) {
            Files.writeString(output, header, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)
        }
        Files.writeString(output, row, StandardCharsets.UTF_8, StandardOpenOption.APPEND)
    }
}

try {
    String idempotencyKey = UUID.randomUUID().toString()
    Map headers = new LinkedHashMap<>(authHeaders)
    headers['Content-Type'] = 'application/json'
    Map created = request(
            'POST',
            '/api/user/membership-orders',
            headers,
            JsonOutput.toJson([
                    targetTier: nextTier(),
                    payType: 'alipay',
                    idempotencyKey: idempotencyKey
            ]))
    if (created.status != 201) {
        throw new IllegalStateException('Rabbit setup order failed: ' + created.status)
    }
    Map order = (Map) json.parseText(created.body)
    Instant createdAt = OffsetDateTime.parse(order.createdAt).toInstant()
    Instant expiresAt = OffsetDateTime.parse(order.expiresAt).toInstant()
    Instant hardCloseAt = expiresAt.plusSeconds(300L)

    if (vars.get('rabbitMode') in ['FINITE_RETRY_TOPOLOGY', 'EXPECTED_DLQ_TOPOLOGY']) {
        String action = vars.get('rabbitMode') == 'FINITE_RETRY_TOPOLOGY'
                ? 'rabbit-retry'
                : 'rabbit-poison'
        String encodedOrderId = URLEncoder.encode(order.orderId, StandardCharsets.UTF_8)
        Map probe = request(
                'POST',
                '/internal/test/membership-payments/loadtest-control/' + action +
                        '?orderId=' + encodedOrderId,
                ['Accept': 'application/json'],
                null)
        if (probe.status != 200 || !probe.body.contains('messageId')) {
            throw new IllegalStateException('Rabbit failure probe publish failed: ' + probe.status)
        }
        // 两次受控重投和第三次 ACK/DLQ 都应在短窗口内完成；最终数量由 Runner 的前后快照裁决。
        Thread.sleep(8_000L)
        Map cancel = request(
                'POST', '/api/user/membership-orders/' + order.orderId + '/cancel',
                authHeaders, null)
        if (cancel.status != 200) {
            throw new IllegalStateException('Rabbit probe order cleanup failed')
        }
        appendOrder(idempotencyKey, order, 'CANCELLED', 'NONE', '')
    } else if (vars.get('rabbitMode') == 'FIRST_PENDING_SEGMENT') {
        waitUntil(createdAt.plusSeconds(12L))
        if (getOrder(order.orderId).status != 'PENDING_PAYMENT') {
            throw new IllegalStateException('first PENDING segment changed state too early')
        }
        Map cancel = request(
                'POST', '/api/user/membership-orders/' + order.orderId + '/cancel',
                authHeaders, null)
        if (cancel.status != 200) {
            throw new IllegalStateException('first segment cleanup failed')
        }
        appendOrder(idempotencyKey, order, 'CANCELLED', 'NONE', '')
    } else if (vars.get('rabbitMode') == 'PENDING_TO_CLOSING') {
        waitUntil(expiresAt.plusSeconds(1L))
        waitForStatus(order.orderId, 'CLOSING', hardCloseAt.minusMillis(250L))
        waitUntil(hardCloseAt.plusSeconds(1L))
        waitForStatus(order.orderId, 'CLOSED', Instant.now().plusSeconds(60L))
        appendOrder(idempotencyKey, order, 'CLOSED', 'NONE', '')
    } else if (vars.get('rabbitMode') == 'CLOSING_TO_CLOSED') {
        waitUntil(hardCloseAt.plusSeconds(1L))
        waitForStatus(order.orderId, 'CLOSED', Instant.now().plusSeconds(60L))
        appendOrder(idempotencyKey, order, 'CLOSED', 'NONE', '')
    } else {
        Map attempt = request(
                'POST',
                '/api/user/membership-orders/' + order.orderId + '/payment-attempts',
                authHeaders,
                null)
        if (!(attempt.status in [200, 201])) {
            throw new IllegalStateException('marker payment attempt failed')
        }
        String tradeNo = ('RABBIT-' + props.getProperty('RUN_ID', 'local') + '-' +
                vars.get('caseName')).take(120)
        String mode = vars.get('rabbitMode')
        if (mode == 'CALLBACK_MARKER_CLOSE_RACE') {
            waitUntil(hardCloseAt.minusSeconds(1L))
            sendCallback(order, tradeNo, 'APPLIED')
            waitForStatus(order.orderId, 'PAID', Instant.now().plusSeconds(120L))
            appendOrder(idempotencyKey, order, 'PAID', 'APPLIED', tradeNo)
        } else {
            String resolution = mode in [
                    'PENDING_MARKER_REJECTED',
                    'CLOSING_MARKER_REJECTED',
                    'FINALIZE_MARKER_REJECTED_RACE'] ? 'REJECTED' : 'APPLIED'
            Instant targetAt
            if (mode in ['PENDING_MARKER_APPLIED', 'PENDING_MARKER_REJECTED']) {
                targetAt = createdAt.plusSeconds(10L)
            } else if (mode in ['CLOSING_MARKER_APPLIED', 'CLOSING_MARKER_REJECTED']) {
                targetAt = expiresAt.plusSeconds(30L)
            } else if (mode == 'FINALIZE_MARKER_REJECTED_RACE') {
                targetAt = hardCloseAt
            } else {
                throw new IllegalStateException('Unknown Rabbit Marker mode: ' + mode)
            }
            String expected = runMarkerCase(order, targetAt, hardCloseAt, resolution, tradeNo)
            appendOrder(idempotencyKey, order, expected, resolution, tradeNo)
        }
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
