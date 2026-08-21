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

if (props.getProperty('MODE', '') != 'loadtest-realtime') {
    throw new IllegalStateException('Callback race JMX requires loadtest-realtime.')
}
int concurrency = Integer.parseInt(props.getProperty('RACE_CONCURRENCY', '0'))
if (!(concurrency in [1, 10, 50, 100, 500])) {
    throw new IllegalStateException('Race concurrency must be 1, 10, 50, 100 or 500.')
}

def baseUrl = props.getProperty('PROTOCOL', 'http') + '://' +
        props.getProperty('HOST', 'localhost') + ':' + props.getProperty('PORT', '8080')
def callbackPid = props.getProperty('CALLBACK_PID', 'loadtest-merchant')
def callbackKey = props.getProperty(
        'CALLBACK_KEY', 'membership-loadtest-callback-key-v1-local')
def runId = props.getProperty('RUN_ID', 'local')
def json = new JsonSlurper()
def callbackTime = DateTimeFormatter.ofPattern('yyyy-MM-dd HH:mm:ss').withZone(ZoneOffset.UTC)
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

def createOrder = {
    String idempotencyKey = UUID.randomUUID().toString()
    Map headers = new LinkedHashMap<>(authHeaders)
    headers['Content-Type'] = 'application/json'
    Map response = request(
            'POST',
            '/api/user/membership-orders',
            headers,
            JsonOutput.toJson([
                    targetTier: 'GO',
                    payType: 'alipay',
                    idempotencyKey: idempotencyKey
            ]))
    if (response.status != 201) {
        throw new IllegalStateException('race order creation failed: ' + response.status)
    }
    Map value = (Map) json.parseText(response.body)
    value.idempotencyKey = idempotencyKey
    Map attempt = request(
            'POST',
            '/api/user/membership-orders/' + value.orderId + '/payment-attempts',
            authHeaders,
            null)
    if (!(attempt.status in [200, 201])) {
        throw new IllegalStateException('race payment attempt failed: ' + attempt.status)
    }
    // 并发回调仍使用秒级第三方支付时间；先跨过 paymentStartedAt 所在秒，确保竞态测试
    // 只裁决唯一性和状态竞争，不被 paidAt < paymentStartedAt 的无关非法时间干扰。
    long attemptResponseSecond = Instant.now().epochSecond
    while (Instant.now().epochSecond <= attemptResponseSecond) {
        Thread.sleep(25L)
    }
    return value
}

def getOrder = { String orderId ->
    Map response = request(
            'GET', '/api/user/membership-orders/' + orderId, authHeaders, null)
    if (response.status != 200) {
        throw new IllegalStateException('race order lookup failed: ' + response.status)
    }
    return (Map) json.parseText(response.body)
}

def waitUntil = { Instant target ->
    while (target.toEpochMilli() > System.currentTimeMillis()) {
        Thread.sleep(Math.min(1_000L, target.toEpochMilli() - System.currentTimeMillis()))
    }
}

def waitForStatus = { String orderId, Collection<String> statuses, Instant deadline ->
    Map order = getOrder(orderId)
    while (!statuses.contains(order.status) && Instant.now().isBefore(deadline)) {
        Thread.sleep(1_000L)
        order = getOrder(orderId)
    }
    if (!statuses.contains(order.status)) {
        throw new IllegalStateException(
                orderId + ' expected ' + statuses + ' but was ' + order.status)
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

def callbackFields = { Map order, String tradeNo, int payloadVariant ->
    Instant now = Instant.now()
    String paidTime = callbackTime.format(now)
    return new LinkedHashMap<String, String>([
            pid: callbackPid,
            trade_no: tradeNo,
            out_trade_no: order.orderId,
            api_trade_no: tradeNo,
            type: 'alipay',
            trade_status: 'TRADE_SUCCESS',
            addtime: paidTime,
            endtime: paidTime,
            name: 'membership-race',
            money: order.payAmountYuan,
            param: vars.get('caseName') + '-' + payloadVariant,
            buyer: 'race-buyer-' + payloadVariant,
            timestamp: Long.toString(now.epochSecond),
            sign: sign(tradeNo, order.orderId),
            sign_type: 'RSA'
    ])
}

def form = { Map<String, String> fields ->
    fields.collect { name, value ->
        URLEncoder.encode(name, StandardCharsets.UTF_8) + '=' +
                URLEncoder.encode(value, StandardCharsets.UTF_8)
    }.join('&')
}

def callback = { Map order, String tradeNo, String protocol, int payloadVariant ->
    Map<String, String> fields = callbackFields(order, tradeNo, payloadVariant)
    Map headers = [
            'X-Simulated-Payment-Key': callbackKey,
            'Accept': 'text/plain'
    ]
    Map response
    if (protocol == 'GET') {
        response = request(
                'GET',
                '/internal/test/membership-payments/liuhao/notify?' + form(fields),
                headers,
                null)
    } else if (protocol == 'POST_FORM') {
        headers['Content-Type'] = 'application/x-www-form-urlencoded; charset=UTF-8'
        response = request(
                'POST',
                '/internal/test/membership-payments/liuhao/notify',
                headers,
                form(fields))
    } else {
        headers['Content-Type'] = 'application/json; charset=UTF-8'
        response = request(
                'POST',
                '/internal/test/membership-payments/liuhao/notify',
                headers,
                JsonOutput.toJson(fields))
    }
    if (response.status != 200 || response.body != 'success'
            || !response.contentType.toLowerCase().startsWith('text/plain')) {
        throw new IllegalStateException(
                'legal race callback was not acknowledged with 200 success')
    }
    return true
}

def invokeParallel = { List<Callable<Boolean>> tasks ->
    def executor = Executors.newFixedThreadPool(tasks.size())
    try {
        def futures = tasks.collect { task -> executor.submit(task) }
        futures.each { future -> future.get() }
    } finally {
        executor.shutdownNow()
    }
}

def appendEvidence = { Map order, String role, String expectedStatus,
                       int expectedCallbackCount, String expectedResolution ->
    Path output = Path.of(props.getProperty('SCENARIO_ORDERS_CSV'))
    String header = 'run_id,case_name,concurrency,order_id,idempotency_key,role,expected_status,expected_callback_count,expected_resolution\n'
    String row = [
            runId,
            vars.get('caseName'),
            concurrency,
            order.orderId,
            order.idempotencyKey,
            role,
            expectedStatus,
            expectedCallbackCount,
            expectedResolution
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
    Map primary = createOrder()
    Map secondary = null
    Instant expiresAt = OffsetDateTime.parse(primary.expiresAt).toInstant()
    Instant hardCloseAt = expiresAt.plusSeconds(300L)
    if (vars.get('targetState') == 'CLOSING') {
        waitUntil(expiresAt.plusSeconds(1L))
        waitForStatus(primary.orderId, ['CLOSING'], hardCloseAt.minusMillis(250L))
    } else if (vars.get('targetState') == 'CLOSED') {
        waitUntil(hardCloseAt.plusSeconds(1L))
        waitForStatus(primary.orderId, ['CLOSED'], Instant.now().plusSeconds(60L))
    }

    String sharedTrade = 'RACE-' + runId + '-' + vars.get('caseName') + '-shared'
    String mode = vars.get('identityMode')
    if (mode == 'SAME_ORDER_SAME_TRADE_SEQUENTIAL') {
        concurrency.times { callback(primary, sharedTrade, 'GET', 0) }
    } else if (mode == 'DIFFERENT_ORDER_SAME_TRADE_PARALLEL') {
        secondary = createOrder()
        invokeParallel((0..<concurrency).collect { index ->
            Map selected = index % 2 == 0 ? primary : secondary
            return { -> callback(selected, sharedTrade, 'GET', 0) } as Callable<Boolean>
        })
    } else if (mode == 'CALLBACK_CANCEL_RACE') {
        List<Callable<Boolean>> tasks = (0..<concurrency).collect { index ->
            return { -> callback(primary, sharedTrade, 'GET', index) } as Callable<Boolean>
        }
        tasks.add({ ->
            Map cancelled = request(
                    'POST',
                    '/api/user/membership-orders/' + primary.orderId + '/cancel',
                    authHeaders,
                    null)
            if (!(cancelled.status in [200, 409])) {
                throw new IllegalStateException('cancel race returned ' + cancelled.status)
            }
            return true
        } as Callable<Boolean>)
        invokeParallel(tasks)
    } else {
        invokeParallel((0..<concurrency).collect { index ->
            String tradeNo = mode == 'SAME_ORDER_DIFFERENT_TRADE_PARALLEL'
                    ? 'RACE-' + runId + '-' + vars.get('caseName') + '-' + index
                    : sharedTrade
            String protocol = mode == 'SAME_ORDER_SAME_TRADE_MIXED_PAYLOAD'
                    ? ['GET', 'POST_FORM', 'POST_JSON'][index % 3]
                    : 'GET'
            int variant = mode == 'SAME_ORDER_SAME_TRADE_MIXED_PAYLOAD' ? index : 0
            return { -> callback(primary, tradeNo, protocol, variant) } as Callable<Boolean>
        })
    }

    if (mode == 'DIFFERENT_ORDER_SAME_TRADE_PARALLEL') {
        Instant deadline = Instant.now().plusSeconds(120L)
        Map first = getOrder(primary.orderId)
        Map second = getOrder(secondary.orderId)
        while (first.status != 'PAID' && second.status != 'PAID' && Instant.now().isBefore(deadline)) {
            Thread.sleep(1_000L)
            first = getOrder(primary.orderId)
            second = getOrder(secondary.orderId)
        }
        if ((first.status == 'PAID') == (second.status == 'PAID')) {
            throw new IllegalStateException('exactly one order must win reused provider trade')
        }
        Map winner = first.status == 'PAID' ? primary : secondary
        Map loser = first.status == 'PAID' ? secondary : primary
        Map cancel = request(
                'POST', '/api/user/membership-orders/' + loser.orderId + '/cancel',
                authHeaders, null)
        if (cancel.status != 200) {
            throw new IllegalStateException('losing order cancellation failed: ' + cancel.status)
        }
        appendEvidence(winner, 'WINNER', 'PAID', 1, 'APPLIED')
        appendEvidence(loser, 'LOSER', 'CANCELLED', 0, 'NONE')
    } else if (mode == 'CALLBACK_CANCEL_RACE') {
        Map terminal = waitForStatus(
                primary.orderId,
                ['PAID', 'CANCELLED'],
                Instant.now().plusSeconds(120L))
        appendEvidence(
                primary,
                'PRIMARY',
                terminal.status,
                1,
                terminal.status == 'PAID' ? 'APPLIED' : 'REFUND_REQUIRED')
    } else if (vars.get('targetState') == 'CLOSED') {
        Thread.sleep(10_000L)
        appendEvidence(primary, 'PRIMARY', 'CLOSED', 1, 'REFUND_REQUIRED')
    } else {
        waitForStatus(primary.orderId, ['PAID'], Instant.now().plusSeconds(120L))
        appendEvidence(primary, 'PRIMARY', 'PAID', 1, 'APPLIED')
    }

    SampleResult.setResponseCode('200')
    SampleResult.setResponseMessage(
            vars.get('caseName') + ' concurrency=' + concurrency + ' passed')
    SampleResult.setResponseData(
            JsonOutput.toJson([
                    caseName: vars.get('caseName'),
                    concurrency: concurrency,
                    primaryOrderId: primary.orderId
            ]),
            StandardCharsets.UTF_8.name())
    SampleResult.setSuccessful(true)
} catch (Throwable failure) {
    log.error('Membership callback race case failed: ' + vars.get('caseName') +
            ' concurrency=' + concurrency, failure)
    SampleResult.setResponseCode('500')
    SampleResult.setResponseMessage(vars.get('caseName') + ': ' + failure.class.simpleName)
    SampleResult.setResponseData(
            (failure.message ?: failure.class.name).take(1024),
            StandardCharsets.UTF_8.name())
    SampleResult.setSuccessful(false)
}
