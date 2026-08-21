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
import java.util.concurrent.Callable
import java.util.concurrent.Executors
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

try {
    String idempotencyKey = UUID.randomUUID().toString()
    String createBody = JsonOutput.toJson([
            targetTier: vars.get('targetTier'),
            payType: vars.get('payType'),
            idempotencyKey: idempotencyKey
    ])
    Map createHeaders = new LinkedHashMap<>(authHeaders)
    createHeaders['Content-Type'] = 'application/json'
    def createResponse = requireHttp(
            request('POST', '/api/user/membership-orders', createHeaders, createBody),
            [201],
            'create order')
    Map order = (Map) json.parseText(createResponse.body)
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
        requireHttp(
                request(
                        'POST',
                        '/api/user/membership-orders/' + orderId + '/cancel',
                        authHeaders,
                        null),
                [200],
                'cancel order')
        waitForStatus(orderId, 'CANCELLED', Instant.now().plusSeconds(20L))
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

    String firstTradeNo = 'JMX-' + runId + '-' + scenario + '-0'
    Instant callbackSentAt = Instant.now()
    sendCallback(orderId, money, firstTradeNo, vars.get('protocol'))

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

    Map finalOrder = waitForStatus(
            orderId,
            vars.get('expectedStatus'),
            Instant.now().plusSeconds(120L))
    Path output = Path.of(props.getProperty('SCENARIO_ORDERS_CSV'))
    String header = 'run_id,user_id,idempotency_key,order_id,scenario,scenario_group,expected_status,expected_resolution,target_callback_at,actual_callback_at,callback_drift_millis,provider_trade_no,protocol\n'
    String row = [
            runId,
            vars.get('userId'),
            idempotencyKey,
            orderId,
            scenario,
            vars.get('group'),
            vars.get('expectedStatus'),
            vars.get('expectedResolution'),
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
}
