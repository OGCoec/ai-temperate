package com.example.temperate.web.user.membership.payment.callback;

import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackReceiveService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 该 Controller 是来兼容六号 GET 通知以及模拟器 POST 表单/JSON 通知，并统一返回支付方要求的纯文本确认。
 *
 * <p>入口只校验测试密钥和传输格式后写 Redis，不访问数据库、不验证真实 RSA，也不构造任何前端页面。</p>
 */
@RestController
@RequestMapping(SimulatedLiuhaoPaymentCallbackController.CALLBACK_PATH)
@ConditionalOnProperty(
        prefix = "app.membership-payment.simulator",
        name = "enabled",
        havingValue = "true")
@Tag(
        name = "会员-模拟支付回调",
        description = "仅供受控测试环境兼容六号 GET 通知、POST 表单和 POST JSON；使用独立测试密钥，不访问数据库、不执行真实 RSA 验签或会员权益发放。")
public final class SimulatedLiuhaoPaymentCallbackController {

    public static final String CALLBACK_PATH =
            "/internal/test/membership-payments/liuhao/notify";
    public static final String CALLBACK_KEY_HEADER = "X-Simulated-Payment-Key";

    private static final String CDN_CACHE_CONTROL = "CDN-Cache-Control";
    private static final MediaType TEXT_PLAIN_UTF8 = new MediaType(
            "text", "plain", StandardCharsets.UTF_8);

    private final SimulatedPaymentCallbackKeyVerifier keyVerifier;
    private final SimulatedLiuhaoCallbackRequestParser requestParser;
    private final PaymentCallbackReceiveService receiveService;

    public SimulatedLiuhaoPaymentCallbackController(
            SimulatedPaymentCallbackKeyVerifier keyVerifier,
            SimulatedLiuhaoCallbackRequestParser requestParser,
            PaymentCallbackReceiveService receiveService) {
        this.keyVerifier = Objects.requireNonNull(keyVerifier);
        this.requestParser = Objects.requireNonNull(requestParser);
        this.receiveService = Objects.requireNonNull(receiveService);
    }

    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    @Operation(summary = "接收六号兼容 GET 模拟支付成功通知")
    public ResponseEntity<String> notifyByGet(
            HttpServletRequest request,
            @RequestHeader(name = CALLBACK_KEY_HEADER, required = false) String callbackKey) {
        return handle(request, callbackKey);
    }

    @PostMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    @Operation(summary = "接收模拟器 POST 表单或 JSON 支付成功通知")
    public ResponseEntity<String> notifyByPost(
            HttpServletRequest request,
            @RequestHeader(name = CALLBACK_KEY_HEADER, required = false) String callbackKey) {
        return handle(request, callbackKey);
    }

    private ResponseEntity<String> handle(
            HttpServletRequest request,
            String callbackKey) {
        if (!keyVerifier.matches(callbackKey)) {
            throw new SimulatedPaymentCallbackTransportException(
                    SimulatedPaymentCallbackTransportException.Kind.UNAUTHORIZED,
                    "Callback key is invalid.");
        }
        receiveService.receive(requestParser.parse(request));
        return ResponseEntity.ok()
                .contentType(TEXT_PLAIN_UTF8)
                .cacheControl(CacheControl.noStore())
                .header(CDN_CACHE_CONTROL, "no-store")
                .body("success");
    }
}
