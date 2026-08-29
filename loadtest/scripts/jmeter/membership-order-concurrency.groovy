import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

String mode = props.getProperty('MODE', '')
if (!(mode in ['loadtest-realtime', 'loadtest-bar'])) {
    throw new IllegalStateException('Order concurrency JMX requires an approved loadtest Profile.')
}

def baseUrl = props.getProperty('PROTOCOL', 'http') + '://' +
        props.getProperty('HOST', 'localhost') + ':' + props.getProperty('PORT', '8080')
def runId = props.getProperty('RUN_ID', 'local')
def json = new JsonSlurper()
def authHeaders = [
        'Authorization': 'Bearer ' + vars.get('accessToken'),
        'Accept': 'application/json'
]
def httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15L))
        .followRedirects(HttpClient.Redirect.NEVER)
        .version(HttpClient.Version.HTTP_1_1)
        .build()

def request = { String method, String path, Map<String, String> headers, String body ->
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(60L))
    headers.each { name, value -> builder.header(name, value) }
    HttpRequest.BodyPublisher publisher = body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8))
    HttpResponse<byte[]> response = httpClient.send(
            builder.method(method, publisher).build(),
            HttpResponse.BodyHandlers.ofByteArray())
    return [
            status: response.statusCode(),
            body: new String(response.body(), StandardCharsets.UTF_8)
    ]
}

def nextTier = {
    Map response = request('GET', '/api/user/membership-plan-offers', authHeaders, null)
    if (response.status != 200) {
        throw new IllegalStateException('Membership offer lookup failed: ' + response.status)
    }
    List offers = (List) ((Map) json.parseText(response.body)).offers
    if (offers == null || offers.isEmpty()) {
        throw new IllegalStateException('Concurrency account has no legal higher membership offer.')
    }
    return ((Map) offers.first()).targetTier as String
}

def executeConcurrently = { int concurrency, Closure<Map> operation ->
    def executor = Executors.newFixedThreadPool(concurrency)
    CountDownLatch ready = new CountDownLatch(concurrency)
    CountDownLatch start = new CountDownLatch(1)
    try {
        def futures = (0..<concurrency).collect { index ->
            executor.submit({ ->
                ready.countDown()
                if (!start.await(60L, TimeUnit.SECONDS)) {
                    throw new IllegalStateException('Concurrent request start gate timed out.')
                }
                return operation(index)
            } as Callable<Map>)
        }
        if (!ready.await(60L, TimeUnit.SECONDS)) {
            throw new IllegalStateException('Unable to arm the requested concurrency tier.')
        }
        start.countDown()
        return futures.collect { future -> future.get(90L, TimeUnit.SECONDS) }
    } finally {
        start.countDown()
        executor.shutdownNow()
    }
}

def statusHistogram = { List<Map> responses ->
    responses.groupBy { response -> response.status }
            .collect { status, matches -> "${status}:${matches.size()}" }
            .sort()
            .join(',')
}

def waitForActiveOrderRelease = {
        int concurrency,
        List<String> requestKeys,
        boolean sameKey,
        String targetTier,
        int iteration ->
    Instant deadline = Instant.now().plusSeconds(30L)
    while (true) {
        List<Map> creates = executeConcurrently(concurrency) { index ->
            Map headers = new LinkedHashMap<>(authHeaders)
            headers['Content-Type'] = 'application/json'
            return request('POST', '/api/user/membership-orders', headers, JsonOutput.toJson([
                    targetTier: targetTier,
                    payType: iteration % 2 == 0 ? 'wxpay' : 'alipay',
                    idempotencyKey: requestKeys[index]
            ]))
        }
        if (creates.count { response -> response.status == 201 } == 1) {
            return creates
        }
        // Redis 终态可能先于数据库刷盘；仅当整批都被活动订单围栏拒绝时等待同一批请求重试，
        // 既不绕开部分唯一索引，也不把合法的短暂 409 窗口误判为创建并发失败。
        if (creates.every { response -> response.status == 409 }) {
            if (!Instant.now().isBefore(deadline)) {
                throw new IllegalStateException(
                        'Active-order database fence did not release before the bounded deadline.')
            }
            Thread.sleep(500L)
            continue
        }
        throw new IllegalStateException(
                'Concurrency tier must create exactly one order; response status histogram=' +
                        statusHistogram(creates))
    }
}

def waitForStatus = { String orderId, String expectedStatus, long timeoutSeconds ->
    Instant deadline = Instant.now().plusSeconds(timeoutSeconds)
    while (Instant.now().isBefore(deadline)) {
        Map response = request('GET', '/api/user/membership-orders/' + orderId, authHeaders, null)
        if (response.status == 200 && ((Map) json.parseText(response.body)).status == expectedStatus) {
            return
        }
        Thread.sleep(500L)
    }
    throw new IllegalStateException(expectedStatus + ' order did not become observable.')
}

def appendEvidence = { List<String> rows ->
    Path output = Path.of(props.getProperty('SCENARIO_ORDERS_CSV'))
    String header = 'run_id,user_id,idempotency_key,order_id,case_name,iteration,concurrency,idempotency_mode,create_201,create_200,create_409,attempt_201,attempt_200,read_200,closing_create_409,expected_status\n'
    synchronized (props) {
        Files.createDirectories(output.parent)
        if (!Files.exists(output)) {
            Files.writeString(output, header, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)
        }
        rows.each { row ->
            Files.writeString(output, row + '\n', StandardCharsets.UTF_8, StandardOpenOption.APPEND)
        }
    }
}

try {
    int concurrency = Integer.parseInt(vars.get('concurrency'))
    int iterations = Integer.parseInt(vars.get('iterations'))
    if (mode == 'loadtest-bar' && concurrency > 50) {
        throw new IllegalStateException('Shared BAR concurrency is capped at C50.')
    }
    List<String> evidence = []

    (1..iterations).each { iteration ->
        String targetTier = nextTier()
        boolean sameKey = iteration % 2 == 1
        String sharedKey = UUID.randomUUID().toString()
        List<String> requestKeys = (0..<concurrency).collect {
            sameKey ? sharedKey : UUID.randomUUID().toString()
        }
        List<Map> creates = waitForActiveOrderRelease(
                concurrency,
                requestKeys,
                sameKey,
                targetTier,
                iteration)
        Map created = creates.find { response -> response.status == 201 }
        if (created == null || creates.count { response -> response.status == 201 } != 1) {
            throw new IllegalStateException('Concurrency tier must create exactly one order.')
        }
        int create200 = creates.count { response -> response.status == 200 }
        int create409 = creates.count { response -> response.status == 409 }
        if (sameKey && (create200 != concurrency - 1 || create409 != 0)) {
            throw new IllegalStateException(
                    'Same-key creation did not converge to 201/200; response status histogram=' +
                            statusHistogram(creates))
        }
        if (!sameKey && (create409 != concurrency - 1 || create200 != 0)) {
            throw new IllegalStateException(
                    'Different-key creation did not converge to 201/409; response status histogram=' +
                            statusHistogram(creates))
        }
        Map order = (Map) json.parseText(created.body)
        String orderId = order.orderId

        List<Map> attempts = executeConcurrently(concurrency) { ignored ->
            request(
                    'POST',
                    '/api/user/membership-orders/' + orderId + '/payment-attempts',
                    authHeaders,
                    null)
        }
        int attempt201 = attempts.count { response -> response.status == 201 }
        int attempt200 = attempts.count { response -> response.status == 200 }
        if (attempt201 != 1 || attempt200 != concurrency - 1) {
            throw new IllegalStateException('Payment Attempt did not converge to one 201 and idempotent 200 replays.')
        }

        int read200 = 0
        6.times {
            List<Map> reads = executeConcurrently(concurrency) { ignored ->
                request('GET', '/api/user/membership-orders/' + orderId, authHeaders, null)
            }
            read200 += reads.count { response -> response.status == 200 }
        }
        if (read200 != concurrency * 6) {
            throw new IllegalStateException('Concurrent order reads were not all successful.')
        }

        boolean closingProbe = mode == 'loadtest-bar' && iteration == iterations
        int closingCreate409 = 0
        String expectedStatus
        if (closingProbe) {
            // 共享 BAR 每个并发档最后一笔保留到真实 CLOSING，证明活动订单约束不会只保护 PENDING 窗口。
            waitForStatus(orderId, 'CLOSING', 420L)
            Map second = request(
                    'POST',
                    '/api/user/membership-orders',
                    authHeaders + ['Content-Type': 'application/json'],
                    JsonOutput.toJson([
                            targetTier: nextTier(),
                            payType: 'alipay',
                            idempotencyKey: UUID.randomUUID().toString()
                    ]))
            if (second.status != 409) {
                throw new IllegalStateException('CLOSING second order was not rejected: ' + second.status)
            }
            closingCreate409 = 1
            waitForStatus(orderId, 'CLOSED', 540L)
            expectedStatus = 'CLOSED'
        } else {
            Map cancelled = request(
                    'POST',
                    '/api/user/membership-orders/' + orderId + '/cancel',
                    authHeaders,
                    null)
            if (cancelled.status != 200) {
                throw new IllegalStateException('Order cancellation failed: ' + cancelled.status)
            }
            waitForStatus(orderId, 'CANCELLED', 60L)
            // 本地并发档在同一用户上连续创建订单；必须先把 Redis 终态刷入 PostgreSQL，
            // 让单活动订单部分唯一索引释放后再开始下一轮，避免把正常的短暂 409 误判为幂等失败。
            if (mode == 'loadtest-realtime') {
                Map flushed = request(
                        'POST',
                        '/internal/test/membership-payments/loadtest-control/flush',
                        ['Accept': 'application/json'],
                        null)
                if (flushed.status != 204) {
                    throw new IllegalStateException(
                            'Order persistence flush failed: ' + flushed.status)
                }
            }
            expectedStatus = 'CANCELLED'
        }

        evidence.add([
                runId,
                vars.get('userId'),
                requestKeys[creates.indexOf(created)],
                orderId,
                vars.get('caseName'),
                iteration,
                concurrency,
                sameKey ? 'SAME' : 'DIFFERENT',
                1,
                create200,
                create409,
                attempt201,
                attempt200,
                read200,
                closingCreate409,
                expectedStatus
        ].join(','))
    }

    appendEvidence(evidence)
    SampleResult.setResponseCode('200')
    SampleResult.setResponseMessage(vars.get('caseName') + ' completed ' + iterations + ' orders')
    SampleResult.setResponseData(JsonOutput.toJson([
            caseName: vars.get('caseName'),
            concurrency: concurrency,
            actualOrders: iterations
    ]), StandardCharsets.UTF_8.name())
    SampleResult.setSuccessful(true)
} catch (Throwable failure) {
    log.error('Membership order concurrency case failed: ' + vars.get('caseName'), failure)
    SampleResult.setResponseCode('500')
    SampleResult.setResponseMessage(vars.get('caseName') + ': ' + failure.class.simpleName)
    SampleResult.setResponseData((failure.message ?: failure.class.name).take(1024), StandardCharsets.UTF_8.name())
    SampleResult.setSuccessful(false)
}
