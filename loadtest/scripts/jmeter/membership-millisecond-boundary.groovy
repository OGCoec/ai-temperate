import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.net.ConnectException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.io.BufferedWriter
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// 该脚本是正式 JMeter 端到端驱动，不允许压缩时间，也不接受 6655 之外的本机端口。
if (props.getProperty('MODE', '') != 'loadtest-realtime') {
    throw new IllegalStateException('Millisecond boundary JMeter requires loadtest-realtime.')
}
int port = Integer.parseInt(props.getProperty('PORT', '6655'))
if (port != 6655) {
    throw new IllegalStateException('Millisecond boundary JMeter is fixed to port 6655.')
}

int creationConcurrency = Integer.parseInt(props.getProperty('CREATION_CONCURRENCY', '256'))
int httpConcurrency = Integer.parseInt(props.getProperty('HTTP_CONCURRENCY', '256'))
int paymentConcurrency = Integer.parseInt(props.getProperty('PAYMENT_CONCURRENCY', '56'))
int connectAttempts = Integer.parseInt(props.getProperty('CONNECT_ATTEMPTS', '3'))
if (creationConcurrency != 256 || httpConcurrency != 256 || paymentConcurrency != 56) {
    throw new IllegalArgumentException(
            'creationConcurrency/httpConcurrency/paymentConcurrency must remain 256/256/56.')
}
if (connectAttempts < 1 || connectAttempts > 5) {
    throw new IllegalArgumentException('connectAttempts must remain between one and five.')
}
if (Runtime.version().feature() < 21) {
    throw new IllegalStateException('The fixed boundary driver requires JDK 21 virtual threads.')
}

String baseUrl = props.getProperty('PROTOCOL', 'http') + '://' +
        props.getProperty('HOST', '127.0.0.1') + ':' + port
String waveCode = props.getProperty('WAVE_CODE', '')
String groupCode = props.getProperty('GROUP_CODE', '')
String runId = props.getProperty('RUN_ID', '')
String httpEvidenceRunId = props.getProperty('HTTP_EVIDENCE_RUN_ID', runId)
String executionPhase = props.getProperty('EXECUTION_PHASE', 'FORMAL')
long formalFirstRequestDeadlineEpochMillis = Long.parseLong(
        props.getProperty('FORMAL_FIRST_REQUEST_DEADLINE_EPOCH_MILLIS', '0'))
Path usersCsv = Path.of(props.getProperty('USERS_CSV'))
Path groupsCsv = Path.of(props.getProperty('GROUPS_CSV'))
Path scenarioOrdersCsv = Path.of(props.getProperty('SCENARIO_ORDERS_CSV'))
Path callbackDispatchCsv = Path.of(props.getProperty('CALLBACK_DISPATCH_CSV'))
Path requestResultsCsv = Path.of(props.getProperty('REQUEST_RESULTS_CSV'))
String callbackPid = props.getProperty('CALLBACK_PID', 'loadtest-merchant')
String callbackKey = props.getProperty('CALLBACK_KEY', 'membership-loadtest-callback-key-v1-local')
def json = new JsonSlurper()
def iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME
def callbackTime = DateTimeFormatter.ofPattern('yyyy-MM-dd HH:mm:ss.SSSSSS').withZone(ZoneOffset.UTC)
def evidenceTime = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC)
// 一个区段共享同一连接池，避免五千请求主动断连后耗尽 Windows 回环临时端口。
HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10L))
        .followRedirects(HttpClient.Redirect.NEVER)
        .version(HttpClient.Version.HTTP_1_1)
        .build()

if (!(waveCode in ['E-PRE', 'E-AFTER', 'H-PRE', 'H-AFTER']) ||
        !(groupCode in ['E-P1', 'E-PR', 'E-A1', 'E-AR', 'H-P1', 'H-PR', 'H-A1', 'H-AR']) ||
        runId.isBlank()) {
    throw new IllegalArgumentException('WAVE_CODE, GROUP_CODE and RUN_ID must identify one fixed segment.')
}
if (!(executionPhase in ['WARMUP', 'FORMAL'])) {
    throw new IllegalArgumentException('EXECUTION_PHASE must be WARMUP or FORMAL.')
}
if ((executionPhase == 'FORMAL' && formalFirstRequestDeadlineEpochMillis <= 0L) ||
        (executionPhase == 'WARMUP' && formalFirstRequestDeadlineEpochMillis != 0L)) {
    throw new IllegalArgumentException('Only a formal wave must carry its positive first-request deadline.')
}
def assertFormalStartDeadline = { String requestPhase ->
    if (executionPhase == 'FORMAL' &&
            System.currentTimeMillis() > formalFirstRequestDeadlineEpochMillis) {
        throw new IllegalStateException(
                'FORMAL_START_DEADLINE_EXPIRED: ' + groupCode + '/' + requestPhase)
    }
}

def parseCsvLine = { String line ->
    List<String> fields = []
    StringBuilder value = new StringBuilder()
    boolean quoted = false
    for (int index = 0; index < line.length(); index++) {
        char character = line.charAt(index)
        if (character == '"') {
            if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                value.append('"')
                index++
            } else {
                quoted = !quoted
            }
        } else if (character == ',' && !quoted) {
            fields.add(value.toString())
            value.setLength(0)
        } else {
            value.append(character)
        }
    }
    fields.add(value.toString())
    return fields
}

def readCsv = { Path path, Closure<Boolean> include ->
    List<Map<String, String>> rows = []
    Files.newBufferedReader(path, StandardCharsets.UTF_8).withCloseable { reader ->
        String headerLine = reader.readLine()
        if (headerLine == null) {
            throw new IllegalStateException('CSV has no header: ' + path)
        }
        List<String> headers = parseCsvLine(headerLine.replace('\uFEFF', ''))
        String line
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue
            List<String> values = parseCsvLine(line)
            if (values.size() != headers.size()) {
                throw new IllegalStateException('CSV row has invalid column count: ' + path)
            }
            Map<String, String> row = [:]
            headers.eachWithIndex { header, index -> row[header] = values[index] }
            if (include(row)) rows.add(row)
        }
    }
    return rows
}

List<Map<String, String>> groups = readCsv(groupsCsv) {
    it.waveCode == waveCode && it.groupCode == groupCode
}
if (groups.size() != 1) {
    throw new IllegalStateException('One JMeter segment requires exactly one fixed group.')
}
Map<String, String> selectedGroup = groups.first()
long firstUserId = Long.parseLong(selectedGroup.firstUserId)
long lastUserId = Long.parseLong(selectedGroup.lastUserId)
int expectedUsers = Integer.parseInt(selectedGroup.userCount)
if (!(expectedUsers in [5_000, 10_000])) {
    throw new IllegalStateException('One JMeter segment allows only 5,000 or 10,000 users.')
}
List<Map<String, String>> users = readCsv(usersCsv) { row ->
    long userId = Long.parseLong(row.userId)
    userId >= firstUserId && userId <= lastUserId
}
if (users.size() != expectedUsers || lastUserId - firstUserId != expectedUsers - 1L) {
    throw new IllegalStateException('One JMeter segment requires the selected fixed contiguous token range.')
}
if (users.any { it.accessToken == null || it.accessToken.isBlank() }) {
    throw new IllegalStateException('One or more boundary Access Tokens are empty.')
}

def csvEscape = { Object raw ->
    String value = raw == null ? '' : raw.toString()
    return '"' + value.replace('"', '""') + '"'
}
def openCsv = { Path path, String header ->
    Files.createDirectories(path.parent)
    BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    writer.write(header)
    writer.newLine()
    writer.flush()
    return writer
}
def appendCsv = { BufferedWriter writer, List values ->
    synchronized (writer) {
        writer.write(values.collect(csvEscape).join(','))
        writer.newLine()
    }
}

BufferedWriter scenarioWriter = openCsv(scenarioOrdersCsv,
        'run_id,wave_code,group_code,trace_id,user_id,target_tier,order_id,expires_at,hard_close_at,target_offset_millis,target_at')
BufferedWriter callbackWriter = openCsv(callbackDispatchCsv,
        'run_id,wave_code,group_code,user_id,order_id,provider_trade_no,target_at,dispatch_started_at,dispatch_completed_at,dispatch_drift_micros,http_status,error_type')
BufferedWriter requestWriter = openCsv(requestResultsCsv,
        'run_id,wave_code,group_code,user_id,operation,http_status,success,error_type,transport_attempts,started_at,completed_at')
AtomicLong requestResultCount = new AtomicLong()
def recordRequest = { long userId, String operation, int status, boolean success,
                      String errorType, int transportAttempts, Instant startedAt, Instant completedAt ->
    appendCsv(requestWriter, [runId, waveCode, groupCode, userId, operation, status,
                              success, errorType, transportAttempts, evidenceTime.format(startedAt),
                              evidenceTime.format(completedAt)])
    if (requestResultCount.incrementAndGet() % 1000L == 0L) {
        synchronized (requestWriter) { requestWriter.flush() }
    }
}

def request = { String method, String path, Map<String, String> headers, String body ->
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(30L))
    headers.each { key, value -> builder.header(key, value) }
    HttpRequest.BodyPublisher publisher = body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
    HttpResponse<String> response = httpClient.send(
            builder.method(method, publisher).build(),
            BodyHandlers.ofString(StandardCharsets.UTF_8))
    return [
            status: response.statusCode(),
            body: response.body() ?: '',
            contentType: response.headers().firstValue('Content-Type').orElse(''),
            transportAttempts: 1
    ]
}
// 创建、支付和回调虽然分别有阶段许可，但所有真实 HTTP 调用共享这一总闸门，确保正式合同的在途上限始终为 256。
Semaphore httpLimiter = new Semaphore(httpConcurrency, true)
// 二百五十六个回环连接在同一点涌入时，Windows/Tomcat 接纳队列仍可能短暂拒绝建连。
// 这里只重试尚未获得 HTTP 响应的 ConnectException，并复用原始幂等键；任何 HTTP 或业务失败仍立即交给门禁裁决。
def requestWithConnectRetry = { String method, String path, Map<String, String> headers, String body ->
    int attempt = 0
    while (true) {
        attempt += 1
        try {
            httpLimiter.acquire()
            Map response
            try {
                response = request(method, path, headers, body)
            } finally {
                httpLimiter.release()
            }
            response.transportAttempts = attempt
            return response
        } catch (ConnectException failure) {
            if (attempt >= connectAttempts) {
                throw failure
            }
            long backoffMillis = 50L * (1L << (attempt - 1)) +
                    ThreadLocalRandom.current().nextLong(151L)
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(backoffMillis))
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException('Connect retry was interrupted.')
            }
        }
    }
}
def requireHttp = { Map response, Collection<Integer> expected, String operation ->
    if (!expected.contains((Integer) response.status)) {
        throw new IllegalStateException(operation + ' received HTTP ' + response.status +
                ' body=' + response.body.take(256))
    }
    return response
}

def groupForUser = { long userId ->
    if (userId < firstUserId || userId > lastUserId) {
        throw new IllegalStateException('User is outside selected segment: ' + userId)
    }
    return selectedGroup
}
def tierFor = { long userId, Map group ->
    long usersPerTier = Long.parseLong(group.usersPerTier)
    int slot = (int) ((userId - Long.parseLong(group.firstUserId)) / usersPerTier)
    if (slot < 0 || slot >= 4) {
        throw new IllegalStateException('Tier slot is outside the fixed four-tier contract: ' + slot)
    }
    return ['GO', 'PLUS', 'PRO', 'MAX'][slot]
}

// 幂等键需要在失败复测时保持稳定，同时必须满足正式接口对 RFC 4122 UUIDv4 的校验；
// 因此用输入摘要生成十六字节，再显式设置 version 4 和 IETF variant 位。
def deterministicUuidV4 = { String seed ->
    byte[] value = Arrays.copyOf(
            MessageDigest.getInstance('SHA-256').digest(seed.getBytes(StandardCharsets.UTF_8)),
            16)
    value[6] = (byte) (((int) value[6] & 0x0f) | 0x40)
    value[8] = (byte) (((int) value[8] & 0x3f) | 0x80)
    ByteBuffer buffer = ByteBuffer.wrap(value)
    return new UUID(buffer.getLong(), buffer.getLong()).toString()
}

// TEAM 负向探针只属于正式证据；预热必须恰好执行同规模的合法单订单链路，不能掺入额外请求。
long probeStart = Long.parseLong(selectedGroup.teamProbeStartUserId)
int configuredProbeCount = Integer.parseInt(selectedGroup.teamProbeCount)
int expectedTeamProbeCount = executionPhase == 'FORMAL' ? 25 : 0
if (configuredProbeCount != 25) {
    throw new IllegalStateException('The fixed segment must declare exactly 25 formal TEAM probes.')
}
Map<String, String> tokenByUser = users.collectEntries { [(it.userId): it.accessToken] }
if (executionPhase == 'FORMAL') {
    assertFormalStartDeadline('TEAM_PROBE')
    (0..<expectedTeamProbeCount).each { index ->
        long userId = probeStart + index
        String token = tokenByUser[Long.toString(userId)]
        String body = JsonOutput.toJson([
                targetTier: 'TEAM',
                payType: 'alipay',
                idempotencyKey: deterministicUuidV4(runId + ':TEAM:' + userId)
        ])
        Instant startedAt = Instant.now()
        try {
            Map response = requestWithConnectRetry('POST', '/api/user/membership-orders', [
                    'Authorization': 'Bearer ' + token,
                    'Accept': 'application/json',
                    'Content-Type': 'application/json'
            ], body)
            requireHttp(response, [400, 409, 422], 'TEAM negative probe')
            recordRequest(userId, 'TEAM_PROBE', response.status as int, true, '',
                    response.transportAttempts as int, startedAt, Instant.now())
        } catch (Throwable failure) {
            recordRequest(userId, 'TEAM_PROBE', 0, false, failure.class.simpleName,
                    failure instanceof ConnectException ? connectAttempts : 1, startedAt, Instant.now())
            throw failure
        }
    }
}

Map<Long, Map> orders = new ConcurrentHashMap<>()
ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>()
AtomicLong formalFirstCreateEpochMillis = new AtomicLong(0L)
def sign = { String tradeNo, String orderId ->
    String canonical = ['SIMULATED', callbackPid, tradeNo, orderId, 'TRADE_SUCCESS'].join('\n')
    Mac mac = Mac.getInstance('HmacSHA256')
    mac.init(new SecretKeySpec(callbackKey.getBytes(StandardCharsets.UTF_8), 'HmacSHA256'))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(
            mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)))
}
def encodeForm = { Map<String, String> fields ->
    fields.collect { name, value ->
        URLEncoder.encode(name, StandardCharsets.UTF_8) + '=' +
                URLEncoder.encode(value, StandardCharsets.UTF_8)
    }.join('&')
}
Semaphore creationLimiter = new Semaphore(creationConcurrency)
Semaphore paymentLimiter = new Semaphore(paymentConcurrency)
Semaphore callbackLimiter = new Semaphore(httpConcurrency)
CountDownLatch creationLatch = new CountDownLatch(expectedUsers)
CountDownLatch callbackLatch = new CountDownLatch(expectedUsers)
def workerPool = Executors.newVirtualThreadPerTaskExecutor()

def waitUntil = { Instant targetAt ->
    while (true) {
        long remainingNanos = Duration.between(Instant.now(), targetAt).toNanos()
        if (remainingNanos <= 0L) return
        LockSupport.parkNanos(remainingNanos)
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException('Boundary callback wait was interrupted.')
        }
    }
}

def sendCallback = { Map order ->
    String tradeNo = order.groupCode + '-MMB-' + runId + '-' + order.userId
    if (tradeNo.length() > 128) {
        failures.add(new IllegalStateException('Provider trade number exceeds VARCHAR(128).'))
        callbackLatch.countDown()
        return
    }
    boolean acquired = false
    Instant startedAt = null
    Instant completedAt = null
    Map response = [status: 0]
    String error = ''
    try {
        waitUntil(order.targetAt as Instant)
        callbackLimiter.acquire()
        acquired = true
        startedAt = Instant.now()
        String paymentTime = callbackTime.format(startedAt)
        Map<String, String> fields = [
                pid: callbackPid,
                trade_no: tradeNo,
                out_trade_no: order.orderId,
                api_trade_no: tradeNo,
                type: 'alipay',
                trade_status: 'TRADE_SUCCESS',
                addtime: paymentTime,
                endtime: paymentTime,
                name: 'membership-millisecond-boundary',
                money: order.money,
                param: order.groupCode,
                buyer: 'boundary-loadtest',
                timestamp: Long.toString(startedAt.epochSecond),
                sign: sign(tradeNo, order.orderId),
                sign_type: 'RSA'
        ]
        response = requestWithConnectRetry('POST', '/internal/test/membership-payments/liuhao/notify', [
                'X-Simulated-Payment-Key': callbackKey,
                'Accept': 'text/plain',
                'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8'
        ], encodeForm(fields))
        requireHttp(response, [200], 'millisecond callback')
        if (response.body != 'success') {
            throw new IllegalStateException('Callback acknowledgement was not exact success.')
        }
        completedAt = Instant.now()
        recordRequest(order.userId as long, 'CALLBACK', response.status as int, true, '',
                response.transportAttempts as int, startedAt, completedAt)
    } catch (Throwable failure) {
        failures.add(failure)
        error = failure.class.simpleName
        if (startedAt == null) startedAt = Instant.now()
        completedAt = Instant.now()
        recordRequest(order.userId as long, 'CALLBACK', response.status as int, false,
                error, failure instanceof ConnectException ? connectAttempts : 1, startedAt, completedAt)
    } finally {
        if (completedAt == null) completedAt = Instant.now()
        appendCsv(callbackWriter,
                [runId, waveCode, order.groupCode, order.userId, order.orderId, tradeNo,
                 evidenceTime.format(order.targetAt as Instant), evidenceTime.format(startedAt),
                 evidenceTime.format(completedAt),
                 // Groovy 的“/”会生成 BigDecimal；向下截断到微秒才能与六位时间戳证据严格一致。
                 Math.floorDiv(Duration.between(order.targetAt, startedAt).toNanos(), 1_000L),
                 response.status, error])
        if (acquired) callbackLimiter.release()
        callbackLatch.countDown()
    }
}

try {
    assertFormalStartDeadline('ORDER_CREATE')
    users.each { user ->
        workerPool.submit({ ->
            boolean creationAcquired = false
            boolean paymentAcquired = false
            boolean callbackScheduled = false
            try {
                creationLimiter.acquire()
                creationAcquired = true
                long userId = Long.parseLong(user.userId)
                Map group = groupForUser(userId)
                String tier = tierFor(userId, group)
                String createTraceId = deterministicUuidV4(
                        runId + ':ORDER_CREATE:' + groupCode + ':' + userId)
                Map<String, String> headers = [
                        'Authorization': 'Bearer ' + user.accessToken,
                        'Accept': 'application/json',
                        'Content-Type': 'application/json',
                        // 三个受控头只用于回环 Profile 的 HTTP 墙钟证据，公开接口语义与请求体保持不变。
                        'X-Trace-Id': createTraceId,
                        'X-Loadtest-Run-Id': httpEvidenceRunId,
                        'X-Loadtest-Segment': groupCode
                ]
                String body = JsonOutput.toJson([
                        targetTier: tier,
                        payType: 'alipay',
                        idempotencyKey: deterministicUuidV4(runId + ':' + groupCode + ':' + userId)
                ])

                long createStartedEpochMillis = System.currentTimeMillis()
                if (executionPhase == 'FORMAL') {
                    formalFirstCreateEpochMillis.compareAndSet(0L, createStartedEpochMillis)
                    if (formalFirstCreateEpochMillis.get() > formalFirstRequestDeadlineEpochMillis) {
                        throw new IllegalStateException(
                                'FORMAL_START_DEADLINE_EXPIRED: ' + groupCode + '/ORDER_CREATE')
                    }
                }
                Instant createStartedAt = Instant.now()
                Map create = [status: 0]
                try {
                    create = requestWithConnectRetry('POST', '/api/user/membership-orders', headers, body)
                    requireHttp(create, [201], 'create boundary order')
                    recordRequest(userId, 'CREATE_ORDER', create.status as int, true, '',
                            create.transportAttempts as int, createStartedAt, Instant.now())
                } catch (Throwable failure) {
                    recordRequest(userId, 'CREATE_ORDER', create.status as int, false,
                            failure.class.simpleName,
                            failure instanceof ConnectException ? connectAttempts : 1,
                            createStartedAt, Instant.now())
                    throw failure
                }

                Map responseOrder = (Map) json.parseText(create.body)
                // 创建并发只覆盖真实 ORDER_CREATE 请求；支付发起使用独立有界许可，不能反向节流创建 QPS。
                creationLimiter.release()
                creationAcquired = false
                Instant paymentStartedAt = Instant.now()
                Map payment = [status: 0]
                try {
                    paymentLimiter.acquire()
                    paymentAcquired = true
                    payment = requestWithConnectRetry('POST', '/api/user/membership-orders/' + responseOrder.orderId +
                            '/payment-attempts', headers, null)
                    requireHttp(payment, [200, 201], 'start payment')
                    recordRequest(userId, 'START_PAYMENT', payment.status as int, true, '',
                            payment.transportAttempts as int, paymentStartedAt, Instant.now())
                } catch (Throwable failure) {
                    recordRequest(userId, 'START_PAYMENT', payment.status as int, false,
                            failure.class.simpleName,
                            failure instanceof ConnectException ? connectAttempts : 1,
                            paymentStartedAt, Instant.now())
                    throw failure
                } finally {
                    if (paymentAcquired) {
                        paymentLimiter.release()
                        paymentAcquired = false
                    }
                }

                Instant expiresAt = OffsetDateTime.parse(responseOrder.expiresAt, iso).toInstant()
                Instant hardCloseAt = expiresAt.plusSeconds(300L)
                int position = (int) (userId - Long.parseLong(group.firstUserId))
                int offsetCycleSize = Integer.parseInt(group.offsetCycleSize)
                int offsetIndex = position % offsetCycleSize
                long targetOffsetMillis = Long.parseLong(group.firstOffsetMillis) +
                        Long.parseLong(group.offsetStepMillis) * offsetIndex
                Instant referenceAt = group.boundaryReference == 'EXPIRES_AT' ? expiresAt : hardCloseAt
                Instant targetAt = referenceAt.plusMillis(targetOffsetMillis)
                Map captured = [
                        runId: runId,
                        waveCode: waveCode,
                        groupCode: group.groupCode,
                        userId: userId,
                        tier: tier,
                        orderId: responseOrder.orderId,
                        money: responseOrder.payAmountYuan,
                        expiresAt: expiresAt,
                        hardCloseAt: hardCloseAt,
                        targetOffsetMillis: targetOffsetMillis,
                        targetAt: targetAt,
                        accessToken: user.accessToken
                ]
                orders[userId] = captured
                appendCsv(scenarioWriter,
                        [runId, waveCode, group.groupCode, createTraceId,
                         userId, tier, responseOrder.orderId,
                         evidenceTime.format(expiresAt), evidenceTime.format(hardCloseAt),
                         targetOffsetMillis, evidenceTime.format(targetAt)])
                workerPool.submit({ -> sendCallback(captured) } as Runnable)
                callbackScheduled = true
            } catch (Throwable failure) {
                failures.add(failure)
                if (!callbackScheduled) callbackLatch.countDown()
            } finally {
                if (paymentAcquired) paymentLimiter.release()
                if (creationAcquired) creationLimiter.release()
                creationLatch.countDown()
            }
        } as Runnable)
    }

    if (!creationLatch.await(20L, TimeUnit.MINUTES)) {
        throw new IllegalStateException('Timed out waiting for all order creations in the fixed segment.')
    }
    synchronized (scenarioWriter) { scenarioWriter.flush() }
    if (!failures.isEmpty() || orders.size() != expectedUsers) {
        throw new IllegalStateException('Order creation phase did not produce the fixed segment size.', failures.peek())
    }

    Instant latestTargetAt = orders.values().collect { it.targetAt as Instant }.max()
    long waitSeconds = Math.max(60L,
            Duration.between(Instant.now(), latestTargetAt.plusSeconds(600L)).seconds)
    if (!callbackLatch.await(waitSeconds, TimeUnit.SECONDS)) {
        throw new IllegalStateException('Timed out waiting for all callback requests in the fixed segment.')
    }
    synchronized (callbackWriter) { callbackWriter.flush() }
    synchronized (requestWriter) { requestWriter.flush() }
    if (!failures.isEmpty()) {
        throw new IllegalStateException('One or more boundary requests failed.', failures.peek())
    }
} finally {
    workerPool.shutdownNow()
    workerPool.awaitTermination(2L, TimeUnit.MINUTES)
    synchronized (scenarioWriter) { scenarioWriter.flush(); scenarioWriter.close() }
    synchronized (callbackWriter) { callbackWriter.flush(); callbackWriter.close() }
    synchronized (requestWriter) { requestWriter.flush(); requestWriter.close() }
}

SampleResult.setSuccessful(true)
SampleResult.setResponseCode('200')
SampleResult.setResponseMessage(expectedUsers + ' real boundary callbacks completed; Runner will verify settlement')
SampleResult.setResponseData(JsonOutput.toJson([
        runId: runId,
        waveCode: waveCode,
        groupCode: groupCode,
        orderCount: orders.size(),
        callbackCount: expectedUsers,
        requestResultCount: requestResultCount.get()
]), StandardCharsets.UTF_8.name())
