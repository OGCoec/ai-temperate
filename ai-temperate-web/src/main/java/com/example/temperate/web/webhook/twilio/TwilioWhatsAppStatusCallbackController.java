package com.example.temperate.web.webhook.twilio;

import com.example.temperate.service.registration.verification.delivery.status.TwilioWhatsAppStatusCallbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接收 Twilio WhatsApp 出站消息状态回调的 HTTP 入口，只做验签、幂等记录和可观测性更新。
 */
@RestController
@ConditionalOnBean(TwilioWhatsAppStatusCallbackService.class)
@ConditionalOnProperty(name = "TWILIO_AUTH_TOKEN")
@Tag(
        name = "第三方-WhatsApp 状态回调",
        description = "接收并验证 Twilio Messaging 状态回调，更新已创建消息的状态索引；不创建新消息、不触发验证码重发，也不返回敏感业务数据。")
public final class TwilioWhatsAppStatusCallbackController {

    private final TwilioWhatsAppStatusCallbackService callbackService;
    private final String configuredCallbackUrl;

    public TwilioWhatsAppStatusCallbackController(
            TwilioWhatsAppStatusCallbackService callbackService,
            @Value("${app.registration.whatsapp.status-callback-url:}") String configuredCallbackUrl) {
        this.callbackService = callbackService;
        this.configuredCallbackUrl = configuredCallbackUrl == null
                ? "" : configuredCallbackUrl.trim();
    }

    @PostMapping(
            path = "/webhooks/twilio/messaging/status",
            consumes = "application/x-www-form-urlencoded")
    @Operation(summary = "接收 Twilio WhatsApp 消息状态回调")
    public ResponseEntity<Void> status(
            @RequestHeader(value = "X-Twilio-Signature", required = false) String signature,
            @RequestParam MultiValueMap<String, String> form,
            HttpServletRequest request) {
        Map<String, String> parameters = new LinkedHashMap<>();
        form.forEach((key, values) -> {
            if (key != null && values != null && !values.isEmpty()) {
                parameters.put(key, values.getFirst());
            }
        });
        String url = request.getRequestURL().toString();
        if (request.getQueryString() != null && !request.getQueryString().isBlank()) {
            url += "?" + request.getQueryString();
        }
        String validationUrl = configuredCallbackUrl.isBlank() ? url : configuredCallbackUrl;
        if (!callbackService.handle(validationUrl, signature, parameters)) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.noContent().build();
    }
}
