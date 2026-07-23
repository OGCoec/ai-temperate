package com.example.temperate.common.aliyun;

import cn.hutool.core.util.StrUtil;
import com.aliyun.auth.credentials.Credential;
import com.aliyun.auth.credentials.provider.EnvironmentVariableCredentialProvider;
import com.aliyun.auth.credentials.provider.ICredentialProvider;
import com.aliyun.auth.credentials.provider.StaticCredentialProvider;
import com.aliyun.core.http.HttpClient;
import com.aliyun.core.http.ProxyOptions;
import com.aliyun.httpcomponent.httpclient.ApacheAsyncHttpClientBuilder;
import com.aliyun.sdk.service.dypnsapi20170525.AsyncClient;
import com.aliyun.sdk.service.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.sdk.service.dypnsapi20170525.models.SendSmsVerifyCodeResponse;
import com.aliyun.sdk.service.oss2.OSSAsyncClient;
import com.aliyun.sdk.service.oss2.credentials.CredentialsProvider;
import com.aliyun.sdk.service.oss2.credentials.EnvironmentVariableCredentialsProvider;
import com.aliyun.sdk.service.oss2.models.CopyObjectRequest;
import com.aliyun.sdk.service.oss2.models.DeleteObjectRequest;
import com.aliyun.sdk.service.oss2.models.PutObjectRequest;
import com.aliyun.sdk.service.oss2.transport.BinaryData;
import com.example.temperate.common.proxy.OutboundRouteResolver;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import darabonba.core.client.ClientOverrideConfiguration;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 提供阿里云号码认证短信与 OSS 对象存储的公共客户端能力，并统一处理凭据、代理选路、客户端关闭和安全日志边界。
 *
 * <p>该工具只负责第三方 SDK 请求和有限响应字段转换，不决定 RabbitMQ 是否重试，也不记录手机号、验证码、
 * AccessKey 或供应商原始响应文本。短信显式代理只作用于号码认证接口，OSS 保持 SDK 默认网络路径。</p>
 */
@Component
public class AliyunUtils {

    private static final Logger log = LoggerFactory.getLogger(AliyunUtils.class);
    private static final Pattern SIX_DIGIT_CODE = Pattern.compile("^[0-9]{6}$");
    private static final Pattern SAFE_DIAGNOSTIC = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    private static final String DEFAULT_OSS_BUCKET = "shopping6655";
    private static final String OSS_REGION = "cn-hongkong";
    private static final String OSS_ENDPOINT = "https://oss-cn-hongkong.aliyuncs.com";
    public static final String HONG_KONG_OSS_REGION = "cn-hongkong";
    public static final String HONG_KONG_OSS_ENDPOINT = "https://oss-cn-hongkong.aliyuncs.com";

    private static final String SMS_ENDPOINT_HOST = "dypnsapi.aliyuncs.com";
    private static final int SMS_ENDPOINT_PORT = 443;

    private final OutboundRouteResolver outboundRouteResolver;
    private final boolean smsProxyEnabled;
    private final String smsProxyHost;
    private final int smsProxyPort;
    private final String smsProxyRouteMode;
    private final int smsProxyRouteProbeTimeoutMs;
    private final String smsAccessKeyId;
    private final String smsAccessKeySecret;
    private final String smsSignName;
    private final SmsClientFactory smsClientFactory;
    private final OssClientFactory ossClientFactory;

    @Autowired
    public AliyunUtils(
            OutboundRouteResolver outboundRouteResolver,
            @Value("${aliyun.sms.proxy.enabled:true}") boolean smsProxyEnabled,
            @Value("${aliyun.sms.proxy.host:127.0.0.1}") String smsProxyHost,
            @Value("${aliyun.sms.proxy.port:7897}") int smsProxyPort,
            @Value("${aliyun.sms.proxy.route-mode:auto}") String smsProxyRouteMode,
            @Value("${aliyun.sms.proxy.route-probe-timeout-ms:1500}") int smsProxyRouteProbeTimeoutMs,
            @Value("${aliyun.sms.access-key-id:}") String smsAccessKeyId,
            @Value("${aliyun.sms.access-key-secret:}") String smsAccessKeySecret,
            @Value("${aliyun.sms.sign-name:速通互联验证平台}") String smsSignName) {
        this(
                outboundRouteResolver,
                smsProxyEnabled,
                smsProxyHost,
                smsProxyPort,
                smsProxyRouteMode,
                smsProxyRouteProbeTimeoutMs,
                smsAccessKeyId,
                smsAccessKeySecret,
                smsSignName,
                AliyunUtils::createSmsClient,
                AliyunUtils::createOssClient);
    }

    AliyunUtils(
            OutboundRouteResolver outboundRouteResolver,
            boolean smsProxyEnabled,
            String smsProxyHost,
            int smsProxyPort,
            String smsProxyRouteMode,
            int smsProxyRouteProbeTimeoutMs,
            String smsAccessKeyId,
            String smsAccessKeySecret,
            String smsSignName,
            SmsClientFactory smsClientFactory,
            OssClientFactory ossClientFactory) {
        this.outboundRouteResolver =
                Objects.requireNonNull(outboundRouteResolver, "outboundRouteResolver must not be null");
        this.smsProxyEnabled = smsProxyEnabled;
        this.smsProxyHost = StrUtil.blankToDefault(smsProxyHost, "127.0.0.1").trim();
        this.smsProxyPort = smsProxyPort;
        this.smsProxyRouteMode = StrUtil.blankToDefault(smsProxyRouteMode, "auto").trim();
        this.smsProxyRouteProbeTimeoutMs = Math.max(300, smsProxyRouteProbeTimeoutMs);
        this.smsAccessKeyId = smsAccessKeyId == null ? "" : smsAccessKeyId;
        this.smsAccessKeySecret = smsAccessKeySecret == null ? "" : smsAccessKeySecret;
        this.smsSignName = requireText(smsSignName, "smsSignName");
        this.smsClientFactory = Objects.requireNonNull(smsClientFactory, "smsClientFactory must not be null");
        this.ossClientFactory = Objects.requireNonNull(ossClientFactory, "ossClientFactory must not be null");
    }

    /**
     * 使用与旧项目兼容的分钟字符串发送验证码，并返回不包含供应商原始消息的受控结果。
     */
    public SmsSendResult sendSmsVerifyCode(
            String telephoneNumber,
            String templateCode,
            String code,
            String validityMinutes) throws Exception {
        long minutes;
        try {
            minutes = Long.parseLong(requireText(validityMinutes, "validityMinutes"));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("validityMinutes must be a positive integer", exception);
        }
        if (minutes <= 0L) {
            throw new IllegalArgumentException("validityMinutes must be positive");
        }
        return sendSmsVerifyCode(telephoneNumber, templateCode, code, Duration.ofMinutes(minutes));
    }

    /**
     * 按本次消息实际剩余有效期发送验证码，确保短信模板中的分钟数与供应商有效秒数来自同一个时长。
     */
    public SmsSendResult sendSmsVerifyCode(
            String telephoneNumber,
            String templateCode,
            String code,
            Duration validity) throws Exception {
        String normalizedPhone = normalizeChinaPhone(telephoneNumber);
        String normalizedTemplate = requireText(templateCode, "templateCode");
        if (code == null || !SIX_DIGIT_CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("code must contain exactly six digits");
        }
        long validitySeconds = positiveCeilingSeconds(validity);
        long validityMinutes = Math.floorDiv(validitySeconds - 1L, 60L) + 1L;
        String templateParamJson = String.format(
                Locale.ROOT,
                "{\"code\":\"%s\",\"min\":\"%d\"}",
                code,
                validityMinutes);

        ICredentialProvider credentialProvider = resolveSmsCredentialProvider();
        HttpClient smsHttpClient = buildSmsHttpClient();
        try (AsyncClient client = smsClientFactory.create(credentialProvider, smsHttpClient)) {
            SendSmsVerifyCodeRequest request = SendSmsVerifyCodeRequest.builder()
                    .countryCode("86")
                    .phoneNumber(normalizedPhone)
                    .signName(smsSignName)
                    .templateCode(normalizedTemplate)
                    .templateParam(templateParamJson)
                    // 供应商内部重试必须关闭，防止与 RabbitMQ 的受控重试叠加。
                    .autoRetry(0L)
                    .returnVerifyCode(false)
                    .validTime(validitySeconds)
                    .build();

            SendSmsVerifyCodeResponse response = client.sendSmsVerifyCode(request).get();
            Integer httpStatus = response == null ? null : response.getStatusCode();
            var responseBody = response == null ? null : response.getBody();
            String providerCode = responseBody == null ? null : responseBody.getCode();
            Boolean providerSuccess = responseBody == null ? null : responseBody.getSuccess();
            String requestId = responseBody == null ? null : responseBody.getRequestId();
            boolean accepted = httpStatus != null
                    && httpStatus >= 200
                    && httpStatus < 300
                    && Boolean.TRUE.equals(providerSuccess);
            SmsSendResult result = new SmsSendResult(
                    accepted,
                    httpStatus,
                    providerCode,
                    providerSuccess,
                    requestId);
            if (accepted) {
                log.info(
                        "Aliyun SMS accepted, httpStatus={}, providerCode={}, requestId={}",
                        result.httpStatus(), result.providerCode(), result.requestId());
            } else {
                log.warn(
                        "Aliyun SMS rejected, httpStatus={}, providerCode={}, providerSuccess={}, requestId={}",
                        result.httpStatus(),
                        result.providerCode(),
                        result.providerSuccess(),
                        result.requestId());
            }
            return result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logSmsException(exception);
            throw exception;
        } catch (Exception exception) {
            logSmsException(exception);
            throw exception;
        }
    }

    private void logSmsException(Exception exception) {
        Throwable rootCause = resolveRootCause(exception);
        log.error(
                "Aliyun SMS transport failed, errorType={}, endpoint={}:{}, proxyEnabled={}, routeMode={}",
                rootCause.getClass().getName(),
                SMS_ENDPOINT_HOST,
                SMS_ENDPOINT_PORT,
                smsProxyEnabled,
                smsProxyRouteMode);
    }

    private static Throwable resolveRootCause(Throwable throwable) {
        Throwable rootCause = throwable;
        while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            rootCause = rootCause.getCause();
        }
        return rootCause;
    }

    private HttpClient buildSmsHttpClient() {
        if (!smsProxyEnabled) {
            return null;
        }
        OutboundRouteResolver.RouteSelection routeSelection = outboundRouteResolver.selectRoute(
                "Aliyun SMS HTTP",
                SMS_ENDPOINT_HOST,
                SMS_ENDPOINT_PORT,
                smsProxyHost,
                smsProxyPort,
                smsProxyRouteMode,
                smsProxyRouteProbeTimeoutMs,
                OutboundRouteResolver.ProxyProtocol.HTTP_CONNECT);
        if (routeSelection.direct()) {
            log.info(
                    "Aliyun SMS HTTP DIRECT route selected, target={}:{}, reachable={}, reason={}",
                    SMS_ENDPOINT_HOST,
                    SMS_ENDPOINT_PORT,
                    routeSelection.reachable(),
                    routeSelection.reason());
            return null;
        }
        InetSocketAddress proxyAddress = routeSelection.address();
        if (proxyAddress == null) {
            return null;
        }
        log.info(
                "Aliyun SMS HTTP proxy selected, host={}, port={}, target={}:{}, reachable={}, reason={}",
                proxyAddress.getHostString(),
                proxyAddress.getPort(),
                SMS_ENDPOINT_HOST,
                SMS_ENDPOINT_PORT,
                routeSelection.reachable(),
                routeSelection.reason());
        return new ApacheAsyncHttpClientBuilder()
                .connectionTimeout(Duration.ofSeconds(10))
                .responseTimeout(Duration.ofSeconds(10))
                .maxConnections(128)
                .maxIdleTimeOut(Duration.ofSeconds(50))
                .proxy(new ProxyOptions(ProxyOptions.Type.HTTP, proxyAddress))
                .build();
    }

    private ICredentialProvider resolveSmsCredentialProvider() {
        if (StrUtil.isNotBlank(smsAccessKeyId) && StrUtil.isNotBlank(smsAccessKeySecret)) {
            return StaticCredentialProvider.create(
                    Credential.builder()
                            .accessKeyId(smsAccessKeyId.trim())
                            .accessKeySecret(smsAccessKeySecret.trim())
                            .build());
        }
        return new EnvironmentVariableCredentialProvider();
    }

    public CompletableFuture<String> uploadFile(String objectKey, byte[] fileBytes) {
        return uploadFileToBucket(DEFAULT_OSS_BUCKET, objectKey, fileBytes);
    }

    public CompletableFuture<String> uploadFileToBucket(
            String bucket, String objectKey, byte[] fileBytes) {
        return uploadFileToBucket(bucket, OSS_REGION, OSS_ENDPOINT, objectKey, fileBytes);
    }

    public CompletableFuture<String> uploadFileToBucket(
            String bucket,
            String region,
            String endpoint,
            String objectKey,
            byte[] fileBytes) {
        String resolvedBucket = normalizeBucket(bucket);
        CredentialsProvider provider = new EnvironmentVariableCredentialsProvider();
        OSSAsyncClient client = buildOssClient(provider, region, endpoint);

        return client.putObjectAsync(
                        PutObjectRequest.newBuilder()
                                .bucket(resolvedBucket)
                                .key(objectKey)
                                .body(BinaryData.fromBytes(fileBytes))
                                .build())
                .thenApply(result -> {
                    log.info(
                            "OSS upload succeeded, bucket={}, keyBytes={}, statusCode={}, requestId={}, eTag={}",
                            resolvedBucket,
                            objectKeyBytes(objectKey),
                            result.statusCode(),
                            result.requestId(),
                            result.eTag());
                    return buildFileUrl(resolvedBucket, endpoint, objectKey);
                })
                .whenComplete((url, ex) -> closeClient(client, "upload", resolvedBucket, objectKey, ex));
    }

    public CompletableFuture<String> uploadContent(String objectKey, String content) {
        CredentialsProvider provider = new EnvironmentVariableCredentialsProvider();
        OSSAsyncClient client = buildOssClient(provider);

        return client.putObjectAsync(
                        PutObjectRequest.newBuilder()
                                .bucket(DEFAULT_OSS_BUCKET)
                                .key(objectKey)
                                .body(BinaryData.fromString(content))
                                .build())
                .thenApply(result -> {
                    log.info(
                            "OSS content upload succeeded, keyBytes={}, statusCode={}, requestId={}, eTag={}",
                            objectKeyBytes(objectKey),
                            result.statusCode(),
                            result.requestId(),
                            result.eTag());
                    return buildFileUrl(DEFAULT_OSS_BUCKET, objectKey);
                })
                .whenComplete((url, ex) ->
                        closeClient(client, "upload-content", DEFAULT_OSS_BUCKET, objectKey, ex));
    }

    public CompletableFuture<Void> deleteFile(String objectKey) {
        return deleteFileFromBucket(DEFAULT_OSS_BUCKET, objectKey);
    }

    public CompletableFuture<Void> deleteFileFromBucket(String bucket, String objectKey) {
        return deleteFileFromBucket(bucket, OSS_REGION, OSS_ENDPOINT, objectKey);
    }

    public CompletableFuture<Void> deleteFileFromBucket(
            String bucket,
            String region,
            String endpoint,
            String objectKey) {
        String resolvedBucket = normalizeBucket(bucket);
        CredentialsProvider provider = new EnvironmentVariableCredentialsProvider();
        OSSAsyncClient client = buildOssClient(provider, region, endpoint);

        return client.deleteObjectAsync(
                        DeleteObjectRequest.newBuilder()
                                .bucket(resolvedBucket)
                                .key(objectKey)
                                .build())
                .thenAccept(result -> log.info(
                        "OSS delete succeeded, bucket={}, keyBytes={}, statusCode={}, requestId={}",
                        resolvedBucket,
                        objectKeyBytes(objectKey),
                        result.statusCode(),
                        result.requestId()))
                .whenComplete((res, ex) -> closeClient(client, "delete", resolvedBucket, objectKey, ex));
    }

    public CompletableFuture<String> copyFile(String srcKey, String destKey) {
        CredentialsProvider provider = new EnvironmentVariableCredentialsProvider();
        OSSAsyncClient client = buildOssClient(provider);

        return client.copyObjectAsync(
                        CopyObjectRequest.newBuilder()
                                .bucket(DEFAULT_OSS_BUCKET)
                                .key(destKey)
                                .sourceBucket(DEFAULT_OSS_BUCKET)
                                .sourceKey(srcKey)
                                .build())
                .thenApply(result -> {
                    log.info(
                            "OSS copy succeeded, sourceKeyBytes={}, destinationKeyBytes={}, statusCode={}",
                            objectKeyBytes(srcKey),
                            objectKeyBytes(destKey),
                            result.statusCode());
                    return buildFileUrl(DEFAULT_OSS_BUCKET, destKey);
                })
                .whenComplete((url, ex) -> closeClient(client, "copy", DEFAULT_OSS_BUCKET, destKey, ex));
    }

    public String buildFileUrl(String bucket, String objectKey) {
        return buildFileUrl(bucket, OSS_ENDPOINT, objectKey);
    }

    public String buildFileUrl(String bucket, String endpoint, String objectKey) {
        String host = normalizeEndpoint(endpoint)
                .replaceFirst("^https?://", "")
                .replaceFirst("/+$", "");
        return String.format("https://%s.%s/%s", normalizeBucket(bucket), host, objectKey);
    }

    private OSSAsyncClient buildOssClient(CredentialsProvider provider) {
        return buildOssClient(provider, OSS_REGION, OSS_ENDPOINT);
    }

    private OSSAsyncClient buildOssClient(
            CredentialsProvider provider, String region, String endpoint) {
        return ossClientFactory.create(
                provider,
                StrUtil.blankToDefault(region, OSS_REGION).trim(),
                normalizeEndpoint(endpoint));
    }

    private static AsyncClient createSmsClient(
            ICredentialProvider provider, HttpClient httpClient) {
        var clientBuilder = AsyncClient.builder()
                .region("cn-hangzhou")
                .credentialsProvider(provider)
                .overrideConfiguration(
                        ClientOverrideConfiguration.create()
                                .setEndpointOverride(SMS_ENDPOINT_HOST));
        if (httpClient != null) {
            clientBuilder.httpClient(httpClient);
        }
        return clientBuilder.build();
    }

    private static OSSAsyncClient createOssClient(
            CredentialsProvider provider, String region, String endpoint) {
        return OSSAsyncClient.newBuilder()
                .region(region)
                .endpoint(endpoint)
                .credentialsProvider(provider)
                .build();
    }

    private static String normalizeChinaPhone(String telephoneNumber) {
        String value = requireText(telephoneNumber, "telephoneNumber");
        try {
            var parsed = PhoneNumberUtil.getInstance().parse(value, "CN");
            if (!PhoneNumberUtil.getInstance().isValidNumber(parsed)
                    || parsed.getCountryCode() != 86) {
                throw new IllegalArgumentException("telephoneNumber must be a valid mainland China number");
            }
            return Long.toString(parsed.getNationalNumber());
        } catch (NumberParseException exception) {
            throw new IllegalArgumentException(
                    "telephoneNumber must be a valid mainland China number", exception);
        }
    }

    private static long positiveCeilingSeconds(Duration validity) {
        Objects.requireNonNull(validity, "validity must not be null");
        if (validity.isZero() || validity.isNegative()) {
            throw new IllegalArgumentException("validity must be positive");
        }
        long seconds = validity.getSeconds();
        if (validity.getNano() > 0) {
            seconds = Math.addExact(seconds, 1L);
        }
        return Math.max(1L, seconds);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String safeDiagnostic(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return SAFE_DIAGNOSTIC.matcher(normalized).matches() ? normalized : null;
    }

    private String normalizeBucket(String bucket) {
        return StrUtil.blankToDefault(bucket, DEFAULT_OSS_BUCKET).trim();
    }

    private String normalizeEndpoint(String endpoint) {
        return StrUtil.blankToDefault(endpoint, OSS_ENDPOINT).trim();
    }

    private static int objectKeyBytes(String objectKey) {
        return objectKey == null ? 0 : objectKey.getBytes(StandardCharsets.UTF_8).length;
    }

    private void closeClient(
            OSSAsyncClient client,
            String action,
            String bucket,
            String objectKey,
            Throwable failure) {
        if (failure != null) {
            log.error(
                    "OSS operation failed, action={}, bucket={}, keyBytes={}, errorType={}",
                    action,
                    bucket,
                    objectKeyBytes(objectKey),
                    resolveRootCause(failure).getClass().getName());
        }
        try {
            client.close();
        } catch (Exception closeError) {
            log.error(
                    "OSS client close failed, bucket={}, keyBytes={}, errorType={}",
                    bucket,
                    objectKeyBytes(objectKey),
                    closeError.getClass().getName());
        }
    }

    /**
     * 表示号码认证接口允许进入业务日志和重试分类的有限响应字段。
     */
    public record SmsSendResult(
            boolean accepted,
            Integer httpStatus,
            String providerCode,
            Boolean providerSuccess,
            String requestId) {

        public SmsSendResult {
            httpStatus = httpStatus != null && httpStatus >= 100 && httpStatus <= 599
                    ? httpStatus
                    : null;
            providerCode = safeDiagnostic(providerCode);
            requestId = safeDiagnostic(requestId);
        }
    }

    @FunctionalInterface
    interface SmsClientFactory {
        AsyncClient create(ICredentialProvider provider, HttpClient httpClient);
    }

    @FunctionalInterface
    interface OssClientFactory {
        OSSAsyncClient create(CredentialsProvider provider, String region, String endpoint);
    }
}
