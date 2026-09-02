package com.example.temperate.web.user.membership.payment.callback;

import com.example.temperate.service.user.membership.payment.callback.LiuhaoPaymentCallbackCommand;
import com.example.temperate.service.user.membership.payment.callback.LiuhaoPaymentCallbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
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
 * 该 Controller 是来接收六号易支付唯一公开的 GET 通知，并把有界单值完整参数集交给验签和主动查询流程。
 *
 * <p>入口保留平台当前及未来扩展标量，但拒绝参数污染、非法名称、控制字符和超限载荷；它不解释或信任扩展字段。</p>
 */
@RestController
@RequestMapping("/api/payment/liuhao")
@ConditionalOnProperty(
        prefix = "app.membership-payment.liuhao",
        name = "enabled",
        havingValue = "true")
@Tag(
        name = "会员-六号易支付回调",
        description = "仅供六号易支付服务器调用固定 GET 通知路径；接口使用平台 RSA 公钥验证调用方并核对时间窗、订单、金额和主动查询事实，只写既有回调队列，不接收 POST、不直接发放权益。")
public final class LiuhaoPaymentCallbackController {

    public static final String CALLBACK_PATH = "/api/payment/liuhao/notify";
    private static final MediaType TEXT_PLAIN_UTF8 =
            new MediaType("text", "plain", StandardCharsets.UTF_8);
    private static final Set<String> REQUIRED = Set.of(
            "pid", "trade_no", "out_trade_no", "type", "money", "trade_status",
            "timestamp", "sign_type", "sign");
    private static final int MAXIMUM_PARAMETERS = 32;
    private static final int MAXIMUM_PARAMETER_VALUE_BYTES = 4_096;
    private static final int MAXIMUM_PAYLOAD_BYTES = 16 * 1_024;
    private static final Pattern PARAMETER_NAME = Pattern.compile("^[A-Za-z0-9_]{1,64}$");

    private final LiuhaoPaymentCallbackService callbackService;

    public LiuhaoPaymentCallbackController(LiuhaoPaymentCallbackService callbackService) {
        this.callbackService = Objects.requireNonNull(callbackService);
    }

    @GetMapping(value = "/notify", produces = "text/plain;charset=UTF-8")
    @Operation(
            summary = "接收六号易支付成功通知",
            description = "接受有界单值完整 Query 参数集；所有非空扩展标量均参与 RSA 验签，时间窗、订单核对、主动查询和 Redis 幂等入队全部成功后才返回纯文本 success。")
    public ResponseEntity<String> notify(
            @RequestParam MultiValueMap<String, String> parameters) {
        Map<String, String> fields = singleValueFields(parameters);
        callbackService.receive(new LiuhaoPaymentCallbackCommand(fields));
        return ResponseEntity.ok()
                .contentType(TEXT_PLAIN_UTF8)
                .cacheControl(CacheControl.noStore())
                .header("CDN-Cache-Control", "no-store")
                .body("success");
    }

    /**
     * 该边界转换保留 Spring 已解码的原始字符串，不做修剪或二次解码；固定上限用于阻断参数污染和验签 CPU 放大。
     */
    private static Map<String, String> singleValueFields(
            MultiValueMap<String, String> parameters) {
        if (parameters == null || !parameters.keySet().containsAll(REQUIRED)) {
            throw new LiuhaoPaymentCallbackTransportException(
                    LiuhaoPaymentCallbackTransportException.Reason.MISSING_REQUIRED);
        }
        if (parameters.size() > MAXIMUM_PARAMETERS) {
            throw new LiuhaoPaymentCallbackTransportException(
                    LiuhaoPaymentCallbackTransportException.Reason.TOO_MANY_PARAMETERS);
        }
        Map<String, String> fields = new LinkedHashMap<>();
        long payloadBytes = 0L;
        parameters.forEach((name, values) -> {
            if (name == null || !PARAMETER_NAME.matcher(name).matches()) {
                throw new LiuhaoPaymentCallbackTransportException(
                        LiuhaoPaymentCallbackTransportException.Reason.INVALID_PARAMETER_NAME);
            }
            if (values == null || values.size() != 1) {
                throw new LiuhaoPaymentCallbackTransportException(
                        LiuhaoPaymentCallbackTransportException.Reason.REPEATED_PARAMETER);
            }
            String value = values.getFirst();
            if (value == null) {
                throw new LiuhaoPaymentCallbackTransportException(
                        LiuhaoPaymentCallbackTransportException.Reason.INVALID_PARAMETER_VALUE);
            }
            if (value.chars().anyMatch(Character::isISOControl)) {
                throw new LiuhaoPaymentCallbackTransportException(
                        LiuhaoPaymentCallbackTransportException.Reason.INVALID_PARAMETER_VALUE);
            }
            int valueBytes = value.getBytes(StandardCharsets.UTF_8).length;
            if (valueBytes > MAXIMUM_PARAMETER_VALUE_BYTES) {
                throw new LiuhaoPaymentCallbackTransportException(
                        LiuhaoPaymentCallbackTransportException.Reason.VALUE_TOO_LARGE);
            }
            fields.put(name, value);
        });
        for (Map.Entry<String, String> field : fields.entrySet()) {
            payloadBytes += field.getKey().getBytes(StandardCharsets.UTF_8).length;
            payloadBytes += field.getValue().getBytes(StandardCharsets.UTF_8).length;
        }
        if (payloadBytes > MAXIMUM_PAYLOAD_BYTES) {
            throw new LiuhaoPaymentCallbackTransportException(
                    LiuhaoPaymentCallbackTransportException.Reason.PAYLOAD_TOO_LARGE);
        }
        return Map.copyOf(fields);
    }
}
