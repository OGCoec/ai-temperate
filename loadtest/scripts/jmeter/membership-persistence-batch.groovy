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
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

if (props.getProperty('MODE', '') != 'loadtest-realtime') {
    throw new IllegalStateException('Persistence JMX requires loadtest-realtime.')
}

int batchSize = Integer.parseInt(vars.get('batchSize'))
int expectedMaxBatch = Integer.parseInt(vars.get('expectedMaxBatch'))
if (!(batchSize in [1, 99, 100, 101, 500, 2000]) || expectedMaxBatch != 500) {
    throw new IllegalStateException('Unexpected persistence batch contract.')
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
            name: 'membership-batch',
            money: order.payAmountYuan,
            param: 'batch-' + batchSize,
            buyer: 'batch-buyer',
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
        throw new IllegalStateException('Batch callback was not acknowledged: ' + response.status)
    }
}

def appendOrder = { String idempotencyKey, Map order, int ordinal, String tradeNo ->
    Path output = Path.of(props.getProperty('SCENARIO_ORDERS_CSV'))
    String header = 'run_id,user_id,idempotency_key,order_id,batch_size,ordinal,expected_status,expected_resolution,provider_trade_no\n'
    String row = [runId, vars.get('userId'), idempotencyKey, order.orderId, batchSize,
            ordinal, 'PAID', 'APPLIED', tradeNo].join(',') + '\n'
    synchronized (props) {
        Files.createDirectories(output.parent)
        if (!Files.exists(output)) {
            Files.writeString(output, header, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)
        }
        Files.writeString(output, row, StandardCharsets.UTF_8, StandardOpenOption.APPEND)
    }
}

try {
    List<Map> preparedOrders = []
    for (int ordinal = 1; ordinal <= batchSize; ordinal++) {
        String idempotencyKey = UUID.randomUUID().toString()
        Map headers = new LinkedHashMap<>(authHeaders)
        headers['Content-Type'] = 'application/json'
        Map create = request(
                'POST',
                '/api/user/membership-orders',
                headers,
                JsonOutput.toJson([targetTier: 'GO', payType: 'alipay', idempotencyKey: idempotencyKey]))
        if (create.status != 201) {
            throw new IllegalStateException('Batch order creation failed at ' + ordinal + ': ' + create.status)
        }
        Map order = (Map) json.parseText(create.body)
        Map attempt = request(
                'POST', '/api/user/membership-orders/' + order.orderId + '/payment-attempts',
                authHeaders, null)
        if (!(attempt.status in [200, 201])) {
            throw new IllegalStateException('Batch payment attempt failed at ' + ordinal)
        }
        String tradeNo = ('B' + batchSize + '-' + runId + '-' + ordinal).take(120)
        preparedOrders.add([
                idempotencyKey: idempotencyKey,
                order: order,
                ordinal: ordinal,
                tradeNo: tradeNo
        ])
    }

    // 模拟支付协议只携带秒级支付时间；先越过最后一次支付发起后的整秒边界，
    // 避免截断后的 paid_at 早于 PostgreSQL 保存的微秒级 payment_started_at。
    long millisToNextSecond = 1000L - (System.currentTimeMillis() % 1000L) + 25L
    Thread.sleep(millisToNextSecond)

    preparedOrders.each { prepared ->
        Map order = (Map) prepared.order
        int ordinal = (int) prepared.ordinal
        String tradeNo = (String) prepared.tradeNo
        callback(order, tradeNo)
        appendOrder((String) prepared.idempotencyKey, order, ordinal, tradeNo)

        // 每组第一笔额外重放同流水与不同流水，验证 Redis 的订单级和流水级去重不会扩张工作项。
        if (ordinal == 1) {
            callback(order, tradeNo)
            callback(order, (tradeNo + '-OTHER').take(128))
        }
    }
    SampleResult.setResponseCode('200')
    SampleResult.setResponseMessage('batch ' + batchSize + ' accepted')
    SampleResult.setResponseData(
            JsonOutput.toJson([batchSize: batchSize, expectedMode: vars.get('expectedMode')]),
            StandardCharsets.UTF_8.name())
    SampleResult.setSuccessful(true)
} catch (Throwable failure) {
    SampleResult.setResponseCode('500')
    SampleResult.setResponseMessage('batch ' + batchSize + ': ' + failure.class.simpleName)
    SampleResult.setResponseData(
            (failure.message ?: failure.class.name).take(1024),
            StandardCharsets.UTF_8.name())
    SampleResult.setSuccessful(false)
}
