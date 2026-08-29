package com.example.temperate.web.user.membership.payment.callback;

import com.example.temperate.service.user.membership.payment.callback.BarPaymentCallbackCommand;
import com.example.temperate.service.user.membership.payment.callback.BarPaymentCallbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 该 Controller 是来接收 BAR 唯一公开的 GET 异步通知，拒绝重复或未知参数后返回严格小写纯文本确认。
 *
 * <p>它不提供 POST 版本、不读取请求体，也不直接更新订单或会员权益。</p>
 */
@RestController
@RequestMapping("/api/payment/bar")
@ConditionalOnProperty(
        prefix = "app.membership-payment.bar",
        name = "enabled",
        havingValue = "true")
@Tag(
        name = "会员-BAR支付回调",
        description = "仅供 BAR 沙箱服务器在无用户会话时调用固定 GET 通知路径；接口使用版本化 HMAC 认证调用方，并执行时间窗、订单和权威查询核对，只写现有 Redis 回调队列，不接受 POST、不发放会员权益。")
public final class BarPaymentCallbackController {

    public static final String CALLBACK_PATH = "/api/payment/bar/notify";
    private static final MediaType TEXT_PLAIN_UTF8 =
            new MediaType("text", "plain", StandardCharsets.UTF_8);
    private static final Set<String> REQUIRED = Set.of(
            "pid", "trade_no", "out_trade_no", "api_trade_no", "type", "name",
            "money", "trade_status", "timestamp", "key_version", "sign_type", "sign");
    private static final Set<String> ALLOWED = Set.of(
            "pid", "trade_no", "out_trade_no", "api_trade_no", "type", "name",
            "money", "param", "trade_status", "timestamp", "key_version", "sign_type", "sign");

    private final BarPaymentCallbackService callbackService;

    public BarPaymentCallbackController(BarPaymentCallbackService callbackService) {
        this.callbackService = Objects.requireNonNull(callbackService);
    }

    @GetMapping(value = "/notify", produces = "text/plain;charset=UTF-8")
    @Operation(
            summary = "接收 BAR 沙箱支付成功通知",
            description = "只接受协议白名单 Query 参数；验签、订单核对、BAR 主动查询和 Redis 幂等入队全部成功后才返回纯文本 success。")
    public ResponseEntity<String> notify(
            @RequestParam MultiValueMap<String, String> parameters) {
        Map<String, String> fields = singleValueFields(parameters);
        callbackService.receive(new BarPaymentCallbackCommand(
                fields.get("pid"),
                fields.get("trade_no"),
                fields.get("out_trade_no"),
                fields.get("api_trade_no"),
                fields.get("type"),
                fields.get("name"),
                fields.get("money"),
                fields.get("param"),
                fields.get("trade_status"),
                fields.get("timestamp"),
                fields.get("key_version"),
                fields.get("sign_type"),
                fields.get("sign")));
        return ResponseEntity.ok()
                .contentType(TEXT_PLAIN_UTF8)
                .cacheControl(CacheControl.noStore())
                .header("CDN-Cache-Control", "no-store")
                .body("success");
    }

    private static Map<String, String> singleValueFields(
            MultiValueMap<String, String> parameters) {
        if (parameters == null
                || !ALLOWED.containsAll(parameters.keySet())
                || !parameters.keySet().containsAll(REQUIRED)) {
            throw new BarPaymentCallbackTransportException(
                    "BAR callback parameters are invalid.");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        parameters.forEach((name, values) -> {
            if (values == null || values.size() != 1 || values.getFirst() == null) {
                throw new BarPaymentCallbackTransportException(
                        "BAR callback parameters must not repeat.");
            }
            fields.put(name, values.getFirst());
        });
        return Map.copyOf(fields);
    }
}
