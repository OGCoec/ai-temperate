import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

if (props.getProperty('MODE', '') != 'loadtest-realtime') {
    throw new IllegalStateException('Callback transport JMX requires loadtest-realtime.')
}

def baseUrl = props.getProperty('PROTOCOL', 'http') + '://' +
        props.getProperty('HOST', 'localhost') + ':' + props.getProperty('PORT', '8080')
def callbackPid = props.getProperty('CALLBACK_PID', 'loadtest-merchant')
def callbackKey = props.getProperty(
        'CALLBACK_KEY', 'membership-loadtest-callback-key-v1-local')
def json = new JsonSlurper()
def formatter = DateTimeFormatter.ofPattern('yyyy-MM-dd HH:mm:ss').withZone(ZoneOffset.UTC)
def httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10L))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

def request = { String method, String path, Map<String, String> headers, String body ->
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(30L))
    headers.each { name, value -> builder.header(name, value) }
    HttpRequest.BodyPublisher publisher = body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
    HttpResponse<String> response = httpClient.send(
            builder.method(method, publisher).build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    return [
            status: response.statusCode(),
            body: response.body() ?: '',
            contentType: response.headers().firstValue('Content-Type').orElse('')
    ]
}

def requireResponse = { Map response, int expectedStatus, String expectedBody, String operation ->
    if (response.status != expectedStatus
            || (expectedBody != 'ANY' && response.body != expectedBody)) {
        throw new IllegalStateException(
                operation + ' expected ' + expectedStatus + '/' + expectedBody +
                        ' but received ' + response.status + '/' + response.body.take(128))
    }
    if (expectedBody != 'ANY'
            && !response.contentType.toLowerCase().startsWith('text/plain')) {
        throw new IllegalStateException(operation + ' did not return text/plain.')
    }
}

def authHeaders = [
        'Authorization': 'Bearer ' + vars.get('accessToken'),
        'Accept': 'application/json'
]
def createHeaders = new LinkedHashMap<>(authHeaders)
createHeaders['Content-Type'] = 'application/json'
Map offersResponse = request(
        'GET',
        '/api/user/membership-plan-offers',
        authHeaders,
        null)
if (offersResponse.status != 200) {
    throw new IllegalStateException('transport offer lookup failed: ' + offersResponse.status)
}
List offers = (List) ((Map) json.parseText(offersResponse.body)).offers
if (offers == null || offers.isEmpty()) {
    throw new IllegalStateException('transport account has no legal higher membership offer')
}
String idempotencyKey = UUID.randomUUID().toString()
Map create = request(
        'POST',
        '/api/user/membership-orders',
        createHeaders,
        JsonOutput.toJson([
                targetTier: ((Map) offers.first()).targetTier,
                payType: 'alipay',
                idempotencyKey: idempotencyKey
        ]))
if (create.status != 201) {
    throw new IllegalStateException('transport setup order creation failed: ' + create.status)
}
Map order = (Map) json.parseText(create.body)
String orderId = order.orderId
String money = order.payAmountYuan

def sign = { String pid, String tradeNo, String outTradeNo, String tradeStatus ->
    String canonical = ['SIMULATED', pid, tradeNo, outTradeNo, tradeStatus].join('\n')
    Mac mac = Mac.getInstance('HmacSHA256')
    mac.init(new SecretKeySpec(callbackKey.getBytes(StandardCharsets.UTF_8), 'HmacSHA256'))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(
            mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)))
}

def newFields = { String tradeNo ->
    Instant now = Instant.now()
    String time = formatter.format(now)
    String pid = vars.get('mutation') == 'WRONG_MERCHANT' ? 'wrong-merchant' : callbackPid
    String tradeStatus = vars.get('mutation') == 'UNSUPPORTED_STATUS'
            ? 'TRADE_CLOSED'
            : 'TRADE_SUCCESS'
    Map<String, String> fields = new LinkedHashMap<>([
            pid: pid,
            trade_no: tradeNo,
            out_trade_no: orderId,
            api_trade_no: tradeNo,
            type: vars.get('mutation') == 'WRONG_PAY_TYPE' ? 'bitcoin' : 'alipay',
            trade_status: tradeStatus,
            addtime: time,
            endtime: time,
            name: 'membership-loadtest',
            money: vars.get('mutation') == 'WRONG_AMOUNT' ? '999999.99' : money,
            param: vars.get('mutation') == 'OVERSIZE' ? 'x'.repeat(17_000) : vars.get('caseName'),
            buyer: 'loadtest-buyer',
            timestamp: Long.toString(now.epochSecond),
            sign: sign(pid, tradeNo, orderId, tradeStatus),
            sign_type: 'RSA'
    ])
    if (vars.get('mutation') == 'WRONG_SIGNATURE') {
        fields.sign = 'invalid-signature'
    }
    if (vars.get('mutation') == 'MISSING_FIELD') {
        fields.remove('buyer')
    }
    if (vars.get('mutation') == 'UNKNOWN_FIELD') {
        fields.unknown_field = 'not-allowed'
    }
    return fields
}

def encodeForm = { Map<String, String> fields ->
    fields.collect { name, value ->
        URLEncoder.encode(name, StandardCharsets.UTF_8) + '=' +
                URLEncoder.encode(value, StandardCharsets.UTF_8)
    }.join('&')
}

def send = { String method, String transport, Map<String, String> fields ->
    Map<String, String> headers = [
            'X-Simulated-Payment-Key': vars.get('mutation') == 'WRONG_CALLBACK_KEY'
                    ? 'wrong-callback-key'
                    : callbackKey,
            'Accept': 'text/plain'
    ]
    if (transport == 'GET') {
        return request(method, '/internal/test/membership-payments/liuhao/notify?' +
                encodeForm(fields), headers, null)
    }
    if (transport == 'POST_FORM') {
        headers['Content-Type'] = 'application/x-www-form-urlencoded; charset=UTF-8'
        String body = encodeForm(fields)
        if (vars.get('mutation') == 'DUPLICATE_FIELD') {
            body += '&pid=' + URLEncoder.encode(fields.pid, StandardCharsets.UTF_8)
        }
        return request(method, '/internal/test/membership-payments/liuhao/notify', headers, body)
    }
    headers['Content-Type'] = 'application/json; charset=UTF-8'
    return request(
            method,
            '/internal/test/membership-payments/liuhao/notify',
            headers,
            JsonOutput.toJson(fields))
}

def waitForStatus = { String expected ->
    long timeoutSeconds = Long.parseLong(
            props.getProperty('STATUS_TIMEOUT_SECONDS', '120'))
    Instant deadline = Instant.now().plusSeconds(timeoutSeconds)
    while (Instant.now().isBefore(deadline)) {
        Map response = request(
                'GET', '/api/user/membership-orders/' + orderId, authHeaders, null)
        if (response.status == 200) {
            Map current = (Map) json.parseText(response.body)
            if (current.status == expected) {
                return
            }
        }
        Thread.sleep(1_000L)
    }
    throw new IllegalStateException('order did not reach ' + expected)
}

try {
    if (vars.get('expectedBody') == 'success') {
        Map attempt = request(
                'POST',
                '/api/user/membership-orders/' + orderId + '/payment-attempts',
                authHeaders,
                null)
        if (!(attempt.status in [200, 201])) {
            throw new IllegalStateException('payment attempt failed: ' + attempt.status)
        }
        // 六号模拟回调只携带秒级支付时间，而 paymentStartedAt 保留毫秒精度；合法用例必须等到
        // 下一秒再生成 paidAt，避免测试数据凭空制造 paidAt < paymentStartedAt 的非法事实。
        long attemptResponseSecond = Instant.now().epochSecond
        while (Instant.now().epochSecond <= attemptResponseSecond) {
            Thread.sleep(25L)
        }
    }

    String tradeNo = 'TRANSPORT-' + props.getProperty('RUN_ID', 'local') + '-' +
            vars.get('caseName')
    Map<String, String> fields = newFields(tradeNo)
    if (vars.get('mutation') == 'UNSUPPORTED_METHODS') {
        ['PUT', 'PATCH'].each { unsupported ->
            Map response = send(unsupported, vars.get('transport'), fields)
            // 仅 GET/POST 是受支持回调协议；其他写方法保留 CSRF 防线并在到达 MVC 前返回 403。
            requireResponse(response,
                    Integer.parseInt(vars.get('expectedStatus')),
                    'ANY', unsupported + ' callback')
        }
    } else {
        Map response = send(vars.get('method'), vars.get('transport'), fields)
        requireResponse(
                response,
                Integer.parseInt(vars.get('expectedStatus')),
                vars.get('expectedBody'),
                vars.get('caseName'))
    }

    if (vars.get('replayMode') == 'MIXED_REPEAT') {
        ['GET', 'POST_FORM', 'POST_JSON'].each { protocol ->
            3.times {
                requireResponse(send(protocol == 'GET' ? 'GET' : 'POST', protocol, fields),
                        200, 'success', 'mixed legal replay')
            }
        }
    }

    boolean applied = vars.get('expectedBody') == 'success' && vars.get('mutation') != 'WRONG_AMOUNT'
    boolean rejectedAmount = vars.get('mutation') == 'WRONG_AMOUNT'
    boolean rejectedCloseProbe = rejectedAmount && Boolean.parseBoolean(
            props.getProperty('REJECTED_CLOSE_PROBE', 'false'))
    if (applied) {
        waitForStatus('PAID')
    } else {
        // 金额错误在异步架构中先返回 200 success，但批处理必须保持订单未支付；其余负向案例也在清理前确认无 PAID。
        if (vars.get('mutation') == 'WRONG_AMOUNT') {
            Thread.sleep(10_000L)
        }
        Map currentResponse = request(
                'GET', '/api/user/membership-orders/' + orderId, authHeaders, null)
        Map current = (Map) json.parseText(currentResponse.body)
        if (current.status != 'PENDING_PAYMENT') {
            throw new IllegalStateException('negative callback changed order to ' + current.status)
        }
        if (rejectedCloseProbe) {
            // 金额错误回调必须恢复显式未支付结果，真实等待 5+5 分钟验证关单查询不会退化为 UNKNOWN 并进入 DLQ。
            waitForStatus('CLOSED')
        } else {
            Map cancel = request(
                    'POST', '/api/user/membership-orders/' + orderId + '/cancel', authHeaders, null)
            if (cancel.status != 200) {
                throw new IllegalStateException('negative callback cleanup cancel failed: ' + cancel.status)
            }
            waitForStatus('CANCELLED')
        }
    }

    String expectedStatus = applied ? 'PAID' : (rejectedCloseProbe ? 'CLOSED' : 'CANCELLED')
    int expectedCallbackCount = applied || rejectedAmount ? 1 : 0
    String expectedResolution = applied ? 'APPLIED' : (rejectedAmount ? 'REJECTED' : 'NONE')
    Path output = Path.of(props.getProperty('SCENARIO_ORDERS_CSV'))
    String header = 'run_id,user_id,idempotency_key,order_id,case_name,expected_status,expected_callback_count,expected_resolution,provider_trade_no\n'
    String row = [
            props.getProperty('RUN_ID', 'local'),
            vars.get('userId'),
            idempotencyKey,
            orderId,
            vars.get('caseName'),
            expectedStatus,
            Integer.toString(expectedCallbackCount),
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

    SampleResult.setResponseCode('200')
    SampleResult.setResponseMessage(vars.get('caseName') + ' passed')
    SampleResult.setResponseData(
            JsonOutput.toJson([caseName: vars.get('caseName'), orderId: orderId]),
            StandardCharsets.UTF_8.name())
    SampleResult.setSuccessful(true)
} catch (Throwable failure) {
    log.error('Membership callback transport case failed: ' + vars.get('caseName'), failure)
    SampleResult.setResponseCode('500')
    SampleResult.setResponseMessage(vars.get('caseName') + ': ' + failure.class.simpleName)
    SampleResult.setResponseData(
            (failure.message ?: failure.class.name).take(1024),
            StandardCharsets.UTF_8.name())
    SampleResult.setSuccessful(false)
}
