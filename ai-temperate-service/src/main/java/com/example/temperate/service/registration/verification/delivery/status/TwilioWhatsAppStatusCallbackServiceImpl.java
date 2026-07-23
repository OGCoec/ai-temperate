package com.example.temperate.service.registration.verification.delivery.status;

import com.twilio.security.RequestValidator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Twilio 回调的安全边界实现：先验签和字段白名单校验，再用状态等级原子写 Redis。
 *
 * <p>乱序、重复和未知 SID 都只影响状态记录，不会触发新的 WhatsApp 请求。
 */
@Service
@ConditionalOnBean(TwilioWhatsAppStatusStore.class)
@ConditionalOnProperty(name = "TWILIO_AUTH_TOKEN")
public final class TwilioWhatsAppStatusCallbackServiceImpl
        implements TwilioWhatsAppStatusCallbackService {

    private static final Pattern SID = Pattern.compile("^(SM|MM)[0-9a-fA-F]{32}$");
    private static final Pattern STATUS = Pattern.compile("^[a-z_]{1,32}$");
    private static final Pattern ERROR_CODE = Pattern.compile("^[0-9]{1,8}$");
    private static final Duration STATUS_INDEX_TTL = Duration.ofMinutes(15);

    private final RequestValidator requestValidator;
    private final TwilioWhatsAppStatusStore statusStore;
    private final Clock clock;

    @Autowired
    public TwilioWhatsAppStatusCallbackServiceImpl(
            TwilioWhatsAppStatusStore statusStore,
            @Value("${TWILIO_AUTH_TOKEN:}") String authToken) {
        this(statusStore, authToken, Clock.systemUTC());
    }

    TwilioWhatsAppStatusCallbackServiceImpl(
            TwilioWhatsAppStatusStore statusStore, String authToken, Clock clock) {
        this.statusStore = Objects.requireNonNull(statusStore);
        if (authToken == null || authToken.isBlank()) {
            throw new IllegalArgumentException("Twilio auth token must not be blank");
        }
        this.requestValidator = new RequestValidator(authToken);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public boolean handle(String requestUrl, String signature, Map<String, String> parameters) {
        if (requestUrl == null || requestUrl.isBlank() || signature == null || signature.isBlank()
                || parameters == null) {
            return false;
        }
        if (!requestValidator.validate(requestUrl, parameters, signature)) {
            return false;
        }
        String sid = parameters.get("MessageSid");
        String status = parameters.get("MessageStatus");
        if (sid == null || !SID.matcher(sid).matches()
                || status == null || !STATUS.matcher(status).matches()) {
            return false;
        }
        String errorCode = parameters.get("ErrorCode");
        if (errorCode != null && !errorCode.isBlank() && !ERROR_CODE.matcher(errorCode).matches()) {
            errorCode = "unavailable";
        }
        statusStore.recordCallback(
                sid,
                status,
                errorCode == null ? "" : errorCode,
                Instant.now(clock),
                STATUS_INDEX_TTL);
        // 合法签名但 SID 未建立索引时也返回成功，避免 Twilio 因无关的旧回调持续重试；全程不创建新发送。
        return true;
    }
}
