package com.example.temperate.service.registration.config;

import com.twilio.http.NetworkHttpClient;
import com.twilio.http.Request;
import com.twilio.http.Response;
import com.twilio.http.TwilioRestClient;
import java.time.Duration;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.util.Timeout;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 统一创建验证码投递使用的 Twilio REST 客户端，供 Verify SMS 与 Programmable Messaging WhatsApp 共享。
 *
 * <p>SDK 自带的请求重试被限制为单次网络调用，跨时间退避统一交给 RabbitMQ，避免客户端重试与消息重试叠加；
 * Account SID 和 Auth Token 只从环境配置读取，且不会进入日志。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"TWILIO_ACCOUNT_SID", "TWILIO_AUTH_TOKEN"})
public class TwilioClientConfiguration {

    private static final Pattern ACCOUNT_SID_PATTERN = Pattern.compile("^AC[0-9a-fA-F]{32}$");

    @Bean("verificationTwilioRestClient")
    TwilioRestClient verificationTwilioRestClient(
            @Value("${TWILIO_ACCOUNT_SID}") String accountSid,
            @Value("${TWILIO_AUTH_TOKEN}") String authToken,
            @Value("${app.registration.whatsapp.connect-timeout:5s}") Duration connectTimeout,
            @Value("${app.registration.whatsapp.response-timeout:20s}") Duration responseTimeout,
            @Value("${app.registration.whatsapp.read-timeout:20s}") Duration readTimeout) {
        if (accountSid == null || !ACCOUNT_SID_PATTERN.matcher(accountSid).matches()) {
            throw new IllegalArgumentException("Twilio account SID has an invalid format");
        }
        if (authToken == null || authToken.isBlank()) {
            throw new IllegalArgumentException("Twilio auth token must not be blank");
        }
        return new TwilioRestClient.Builder(accountSid, authToken)
                .httpClient(new SingleAttemptNetworkHttpClient(
                        requirePositive(connectTimeout, "connectTimeout"),
                        requirePositive(responseTimeout, "responseTimeout"),
                        requirePositive(readTimeout, "readTimeout")))
                .build();
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    /**
     * 禁用 SDK 内层可靠重试，使一次 RabbitMQ 消费尝试最多发起一次供应商网络请求。
     */
    private static final class SingleAttemptNetworkHttpClient extends NetworkHttpClient {

        private SingleAttemptNetworkHttpClient(
                Duration connectTimeout, Duration responseTimeout, Duration readTimeout) {
            // 三层超时共同限定一次 HTTP 尝试的持有时间；超时交由业务层归类为 UNKNOWN，禁止 SDK 自动重发。
            super(RequestConfig.custom()
                            .setConnectionRequestTimeout(Timeout.of(connectTimeout))
                            .setConnectTimeout(Timeout.of(connectTimeout))
                            .setResponseTimeout(Timeout.of(responseTimeout))
                            .build(),
                    SocketConfig.custom()
                            .setSoTimeout(Timeout.of(readTimeout))
                            .build());
        }

        @Override
        public Response reliableRequest(Request request) {
            return makeRequest(request);
        }
    }
}
