package com.example.temperate.service.registration.config;

import com.example.temperate.service.registration.verification.delivery.util.twilio.TwilioVerifySmsUtil;
import com.twilio.http.TwilioRestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配 Twilio Verify 国际短信验证码工具。
 *
 * <p>只有统一 Twilio 客户端和 Verify Service SID 同时存在时才启用；凭据由共享客户端配置读取，
 * 该配置只负责绑定 Verify 服务，不重复创建网络客户端。</p>
 */
@Configuration
@ConditionalOnProperty(
        name = "TWILIO_VERIFY_SERVICE_SID")
@ConditionalOnBean(name = "verificationTwilioRestClient")
public class TwilioVerifyConfiguration {

    @Bean
    TwilioVerifySmsUtil twilioVerifySmsUtil(
            @Qualifier("verificationTwilioRestClient") TwilioRestClient twilioRestClient,
            @Value("${TWILIO_VERIFY_SERVICE_SID}") String verifyServiceSid) {
        return new TwilioVerifySmsUtil(twilioRestClient, verifyServiceSid);
    }
}
