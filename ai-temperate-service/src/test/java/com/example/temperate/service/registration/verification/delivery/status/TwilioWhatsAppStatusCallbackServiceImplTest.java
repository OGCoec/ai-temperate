package com.example.temperate.service.registration.verification.delivery.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** 验证 Twilio 状态回调必须先通过签名校验，并且只写状态索引而不触发发送。 */
class TwilioWhatsAppStatusCallbackServiceImplTest {

    private static final String TOKEN = "auth-token-for-test";
    private static final String URL = "https://example.test/webhooks/twilio/messaging/status";
    private static final String SID = "SMbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Test
    void rejectsInvalidSignatureWithoutUpdatingStore() {
        TwilioWhatsAppStatusStore store = mock(TwilioWhatsAppStatusStore.class);
        TwilioWhatsAppStatusCallbackServiceImpl service = service(store);

        assertThat(service.handle(URL, "invalid", parameters())).isFalse();
        verify(store, never()).recordCallback(any(), any(), any(), any(), any());
    }

    @Test
    void acceptsValidSignatureAndRecordsStatusIdempotentlyAtStoreBoundary() {
        TwilioWhatsAppStatusStore store = mock(TwilioWhatsAppStatusStore.class);
        when(store.recordCallback(any(), any(), any(), any(), any())).thenReturn(true);
        TwilioWhatsAppStatusCallbackServiceImpl service = service(store);
        Map<String, String> parameters = parameters();

        assertThat(service.handle(URL, signature(URL, parameters), parameters)).isTrue();
        verify(store).recordCallback(
                eq(SID), eq("queued"), eq(""), any(Instant.class), any());
    }

    private static TwilioWhatsAppStatusCallbackServiceImpl service(
            TwilioWhatsAppStatusStore store) {
        return new TwilioWhatsAppStatusCallbackServiceImpl(
                store, TOKEN, Clock.fixed(Instant.parse("2026-07-21T00:00:00Z"), ZoneOffset.UTC));
    }

    private static Map<String, String> parameters() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("MessageSid", SID);
        parameters.put("MessageStatus", "queued");
        return parameters;
    }

    private static String signature(String url, Map<String, String> parameters) {
        StringBuilder payload = new StringBuilder(url);
        parameters.keySet().stream().sorted().forEach(key -> payload.append(key)
                .append(parameters.get(key)));
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            Key key = new SecretKeySpec(TOKEN.getBytes(StandardCharsets.UTF_8), "HmacSHA1");
            mac.init(key);
            return Base64.getEncoder().encodeToString(
                    mac.doFinal(payload.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
