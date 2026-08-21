import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

if (props.getProperty('MODE', '') != 'loadtest-realtime') {
    throw new IllegalStateException('Auth boundary JMX requires loadtest-realtime.')
}

def baseUrl = props.getProperty('PROTOCOL', 'http') + '://' +
        props.getProperty('HOST', 'localhost') + ':' + props.getProperty('PORT', '8080')
def json = new JsonSlurper()

def request = { String method, String path, String token, String body, Map<String, String> extraHeaders ->
    HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection()
    connection.requestMethod = method
    connection.connectTimeout = 10_000
    connection.readTimeout = 30_000
    connection.instanceFollowRedirects = false
    if (token != null) {
        connection.setRequestProperty('Authorization', 'Bearer ' + token)
    }
    extraHeaders.each { name, value -> connection.setRequestProperty(name, value) }
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

def requireStatus = { Map response, Collection<Integer> expected, String caseName ->
    if (!expected.contains((Integer) response.status)) {
        throw new IllegalStateException(
                caseName + ' expected ' + expected + ' but received ' + response.status)
    }
}

List<String> csvLines = Files.readAllLines(
        Path.of(props.getProperty('USERS_CSV')), StandardCharsets.UTF_8)
def users = csvLines.drop(1).collect { line ->
    def matcher = line =~ /^"?([^",]+)"?,"?([^"\r\n]+)"?$/
    if (!matcher.matches()) {
        throw new IllegalStateException('Local users CSV format is invalid.')
    }
    return [userId: matcher.group(1), accessToken: matcher.group(2)]
}
if (users.size() != 4) {
    throw new IllegalStateException('Auth boundary requires exactly four approved users.')
}
Map negative = (Map) json.parseText(Files.readString(
        Path.of(props.getProperty('AUTH_NEGATIVE_TOKENS_FILE')), StandardCharsets.UTF_8))
String firstToken = users[0].accessToken
String secondToken = users[1].accessToken
String forgedToken = firstToken.substring(0, firstToken.length() - 1) +
        (firstToken.endsWith('A') ? 'B' : 'A')
String idempotencyKey = UUID.randomUUID().toString()
String createBody = JsonOutput.toJson([
        targetTier: 'GO',
        payType: 'alipay',
        idempotencyKey: idempotencyKey
])
Map jsonHeaders = ['Content-Type': 'application/json', 'Accept': 'application/json']

try {
    Map created = request('POST', '/api/user/membership-orders', firstToken, createBody, jsonHeaders)
    requireStatus(created, [201], 'A-01 valid allowlisted AT')
    String orderId = ((Map) json.parseText(created.body)).orderId

    requireStatus(
            request('POST', '/api/user/membership-orders', negative.expiredAccessToken,
                    createBody, jsonHeaders),
            [401],
            'A-02 expired signed AT')
    requireStatus(
            request('POST', '/api/user/membership-orders', forgedToken, createBody, jsonHeaders),
            [401],
            'A-03 forged AT')
    requireStatus(
            request('POST', '/api/user/membership-orders',
                    negative.nonAllowlistedAccessToken, createBody, jsonHeaders),
            [401],
            'A-04 non-allowlisted signed AT')
    requireStatus(
            request('GET', '/api/user/membership-orders/' + orderId,
                    secondToken, null, ['Accept': 'application/json']),
            [404],
            'A-05 cross-user ownership')
    requireStatus(
            request('GET', '/api/user/api-keys', firstToken, null,
                    ['Accept': 'application/json']),
            [401, 403],
            'A-06 non-membership path isolation')
    requireStatus(
            request('GET', '/internal/test/membership-payments/liuhao/notify',
                    firstToken, null, ['Accept': 'text/plain']),
            [401],
            'A-07 callback key isolation')
    requireStatus(
            request('GET', '/api/user/membership-orders', firstToken, null,
                    ['Accept': 'application/json']),
            [401, 403],
            'A-08 unmatched membership method/path isolation')

    requireStatus(
            request('POST', '/api/user/membership-orders/' + orderId + '/cancel',
                    firstToken, null, ['Accept': 'application/json']),
            [200],
            'auth setup cleanup')
    Path output = Path.of(props.getProperty('SCENARIO_ORDERS_CSV'))
    String header = 'run_id,user_id,idempotency_key,order_id,case_name,expected_status,expected_callback_count\n'
    String row = [
            props.getProperty('RUN_ID', 'local'),
            users[0].userId,
            idempotencyKey,
            orderId,
            'AUTH-BOUNDARY-SETUP',
            'CANCELLED',
            '0'
    ].join(',') + '\n'
    Files.createDirectories(output.parent)
    if (!Files.exists(output)) {
        Files.writeString(output, header, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)
    }
    Files.writeString(output, row, StandardCharsets.UTF_8, StandardOpenOption.APPEND)
    SampleResult.setResponseCode('200')
    SampleResult.setResponseMessage('eight authentication boundaries passed')
    SampleResult.setResponseData(
            JsonOutput.toJson([caseCount: 8, orderId: orderId]),
            StandardCharsets.UTF_8.name())
    SampleResult.setSuccessful(true)
} catch (Throwable failure) {
    SampleResult.setResponseCode('500')
    SampleResult.setResponseMessage(failure.class.simpleName)
    SampleResult.setResponseData(
            (failure.message ?: failure.class.name).take(1024),
            StandardCharsets.UTF_8.name())
    SampleResult.setSuccessful(false)
}
