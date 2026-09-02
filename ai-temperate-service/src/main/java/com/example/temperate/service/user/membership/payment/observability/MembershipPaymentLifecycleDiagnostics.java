package com.example.temperate.service.user.membership.payment.observability;

import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.order.MembershipClosingFinalizationSource;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderReference;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderStatus;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 该工具是来统一输出会员支付引用升级、六号响应验签、关单和退款终态的脱敏诊断日志，帮助区分外部事实、校验层与本地迁移结果。
 *
 * <p>调用方只能传入低基数阶段、裁决结果和受控错误码；本工具不会输出订单号、平台流水、签名、响应体或支付载体。</p>
 */
public final class MembershipPaymentLifecycleDiagnostics {

    private static final Logger LOGGER = LoggerFactory.getLogger("membership.payment.lifecycle");
    private static final Pattern SAFE_TOKEN = Pattern.compile("^[A-Za-z0-9_.:-]{1,128}$");
    private static final String UNAVAILABLE = "unavailable";

    private MembershipPaymentLifecycleDiagnostics() {
    }

    /** 记录一次平台流水发现与数据库、Redis 绑定裁决，不接收原始订单号或响应正文。 */
    public static void referenceResolution(
            MembershipOrderSnapshot order,
            PaymentProviderType provider,
            String trigger,
            String stage,
            String providerQueryOutcome,
            PaymentProviderStatus providerStatus,
            boolean tradeNoPresent,
            String databaseBind,
            String redisBind,
            String nextAction,
            String reason,
            String traceId,
            String messageId) {
        referenceResolution(
                order,
                provider,
                trigger,
                stage,
                providerQueryOutcome,
                providerStatus,
                Boolean.toString(tradeNoPresent),
                databaseBind,
                redisBind,
                nextAction,
                reason,
                traceId,
                messageId);
    }

    /** 记录三态交易号证据；unknown 表示调用失败前没有拿到完整创建结果，不能推断为不存在。 */
    public static void referenceResolution(
            MembershipOrderSnapshot order,
            PaymentProviderType provider,
            String trigger,
            String stage,
            String providerQueryOutcome,
            PaymentProviderStatus providerStatus,
            String tradeNoPresence,
            String databaseBind,
            String redisBind,
            String nextAction,
            String reason,
            String traceId,
            String messageId) {
        String message = "event=membership_payment_reference_resolution"
                + " provider=" + provider(provider)
                + " trigger=" + token(trigger)
                + " reference_kind=" + referenceKind(order.providerTradeNo())
                + " payment_started=" + (order.paymentStartedAt() != null)
                + " stage=" + token(stage)
                + " provider_query_outcome=" + token(providerQueryOutcome)
                + " provider_status=" + status(providerStatus)
                + " trade_no_present=" + token(tradeNoPresence)
                + " database_bind=" + token(databaseBind)
                + " redis_bind=" + token(redisBind)
                + " next_action=" + token(nextAction)
                + " reason=" + token(reason)
                + " traceId=" + token(traceId)
                + " messageId=" + token(messageId);
        if ("success".equals(providerQueryOutcome)
                || "PAYMENT_ATTEMPT_STARTED".equals(reason)
                || "PROVIDER_ORDER_NOT_VISIBLE".equals(reason)
                || "REFERENCE_BOUND_SUCCESSFULLY".equals(reason)
                || "REFERENCE_ALREADY_TRADE".equals(reason)) {
            LOGGER.info(message);
        } else {
            LOGGER.warn(message);
        }
    }

    /** 记录真实第三方交易号从缺失状态完成绑定；日志只描述结果，禁止输出真实流水。 */
    public static void referenceBound(
            MembershipOrderSnapshot order,
            PaymentProviderType provider,
            String source,
            String databaseBind,
            String redisBind,
            OffsetDateTime boundAt,
            String traceId,
            String messageId) {
        long latencyMillis = order.paymentStartedAt() == null
                ? -1L
                : Math.max(0L, Duration.between(order.paymentStartedAt(), boundAt).toMillis());
        LOGGER.info(
                "event=provider_trade_reference_bound"
                        + " provider=" + provider(provider)
                        + " previous_reference_present=false"
                        + " outcome=" + ("unchanged".equals(databaseBind)
                                && "unchanged".equals(redisBind) ? "already_bound" : "bound")
                        + " source=" + token(source)
                        + " database_bind=" + token(databaseBind)
                        + " redis_bind=" + token(redisBind)
                        + " bind_latency_ms=" + latencyMillis
                        + " traceId=" + token(traceId)
                        + " messageId=" + token(messageId));
    }

    /** 记录订单状态机视角的取消、关单、补查与终态迁移裁决。 */
    public static void closeLifecycle(
            MembershipOrderSnapshot order,
            PaymentProviderType provider,
            String trigger,
            boolean callbackMarker,
            String closeRequest,
            String httpOutcome,
            String signatureOutcome,
            String providerCode,
            PaymentProviderStatus providerStatus,
            boolean tradeNoPresent,
            String followupQuery,
            String transition,
            String nextAction,
            String reason,
            String traceId,
            String messageId) {
        String message = "event=membership_payment_close_lifecycle"
                + " provider=" + provider(provider)
                + " trigger=" + token(trigger)
                + " local_status=" + order.status().name().toLowerCase(Locale.ROOT)
                + " reference_kind=" + referenceKind(order.providerTradeNo())
                + " locator_kind=" + locatorKind(order.providerTradeNo())
                + " payment_started=" + (order.paymentStartedAt() != null)
                + " callback_marker=" + callbackMarker
                + " close_request=" + token(closeRequest)
                + " http_outcome=" + token(httpOutcome)
                + " signature_outcome=" + token(signatureOutcome)
                + " provider_code=" + token(providerCode)
                + " provider_status=" + status(providerStatus)
                + " trade_no_present=" + tradeNoPresent
                + " followup_query=" + token(followupQuery)
                + " transition=" + token(transition)
                + " next_action=" + token(nextAction)
                + " reason=" + token(reason)
                + " traceId=" + token(traceId)
                + " messageId=" + token(messageId);
        if ("FINALIZED_CLOSED".equals(reason)
                || "CLOSE_CONFIRMED_WAITING_CALLBACK_WINDOW".equals(reason)
                || "BEFORE_CLOSING_DEADLINE".equals(reason)
                || "PAYMENT_NEVER_STARTED".equals(reason)) {
            LOGGER.info(message);
        } else {
            LOGGER.warn(message);
        }
    }

    /** 记录六号 HTTP 客户端的定位类型、验签结果和缺失状态后的补查结果。 */
    public static void liuhaoCloseClient(
            boolean tradeLocator,
            String closeRequest,
            String httpOutcome,
            String signatureOutcome,
            String providerCode,
            PaymentProviderStatus providerStatus,
            boolean tradeNoPresent,
            String followupQuery,
            String nextAction,
            String reason) {
        String message = "event=membership_payment_close_lifecycle"
                + " provider=liuhao trigger=close_response local_status=unavailable"
                + " reference_kind=" + (tradeLocator ? "trade" : "order")
                + " locator_kind=" + (tradeLocator ? "trade_no" : "out_trade_no")
                + " payment_started=true callback_marker=false"
                + " close_request=" + token(closeRequest)
                + " http_outcome=" + token(httpOutcome)
                + " signature_outcome=" + token(signatureOutcome)
                + " provider_code=" + token(providerCode)
                + " provider_status=" + status(providerStatus)
                + " trade_no_present=" + tradeNoPresent
                + " followup_query=" + token(followupQuery)
                + " transition=none"
                + " next_action=" + token(nextAction)
                + " reason=" + token(reason)
                + " traceId=" + token(MembershipPaymentTraceContext.currentTraceId())
                + " messageId=unavailable";
        if ("CLOSE_CONFIRMED_WAITING_CALLBACK_WINDOW".equals(reason)
                || "BEFORE_CLOSING_DEADLINE".equals(reason)) {
            LOGGER.info(message);
        } else {
            LOGGER.warn(message);
        }
    }

    /** 记录关单或退款表单已完成签名字段装配，只记录字段存在性与算法分类，不接收任何敏感原值。 */
    public static void liuhaoRequestSignature(
            String operation,
            String path,
            boolean pidPresent,
            boolean timestampPresent,
            boolean tradeNoPresent,
            boolean outTradeNoPresent,
            boolean moneyPresent,
            boolean outRefundNoPresent,
            boolean signTypePresent,
            boolean signPresent,
            String signTypeClass,
            String signatureAlgorithm,
            String requestStage,
            boolean requestFieldsValid,
            String merchantSignatureSelfCheck,
            String traceId) {
        String message = "event=liuhao_request_signature"
                + " operation=" + token(operation)
                + " path=" + pathToken(path)
                + " pid_present=" + pidPresent
                + " timestamp_present=" + timestampPresent
                + " trade_no_present=" + tradeNoPresent
                + " out_trade_no_present=" + outTradeNoPresent
                + " money_present=" + moneyPresent
                + " out_refund_no_present=" + outRefundNoPresent
                + " sign_type_present=" + signTypePresent
                + " sign_present=" + signPresent
                + " sign_type_class=" + token(signTypeClass)
                + " signature_algorithm=" + token(signatureAlgorithm)
                + " request_stage=" + token(requestStage)
                + " request_fields_valid=" + requestFieldsValid
                + " merchant_signature_self_check=" + token(merchantSignatureSelfCheck)
                + " traceId=" + token(traceId);
        boolean operationFieldsPresent = !"refund".equals(operation)
                || (moneyPresent && outRefundNoPresent);
        if (pidPresent && timestampPresent && signTypePresent && signPresent
                && operationFieldsPresent && requestFieldsValid
                && "verified".equals(merchantSignatureSelfCheck)) {
            LOGGER.info(message);
        } else {
            LOGGER.warn(message);
        }
    }

    /**
     * 记录退款前置终态的逐项校验结果；调用方只能传入 Provider 枚举、布尔事实和固定原因码，
     * 禁止传入任何订单或流水原值。
     */
    public static void refundPreflight(
            PaymentProviderType provider,
            String stage,
            String snapshotOutcome,
            boolean factPresent,
            boolean callbackOrderMatch,
            boolean callbackProviderTradeMatch,
            boolean callbackResolutionMatch,
            boolean orderTerminal,
            boolean entitlementRefundRequired,
            boolean orderProviderTradePresent,
            String verificationOutcome,
            String reason,
            String traceId) {
        String message = "event=membership_payment_refund_preflight"
                + " provider=" + provider(provider)
                + " stage=" + token(stage)
                + " snapshot_outcome=" + token(snapshotOutcome)
                + " fact_present=" + factPresent
                + " callback_order_match=" + callbackOrderMatch
                + " callback_provider_trade_match=" + callbackProviderTradeMatch
                + " callback_resolution_match=" + callbackResolutionMatch
                + " order_terminal=" + orderTerminal
                + " entitlement_refund_required=" + entitlementRefundRequired
                + " order_provider_trade_present=" + orderProviderTradePresent
                + " verification_outcome=" + token(verificationOutcome)
                + " reason=" + token(reason)
                + " traceId=" + token(traceId);
        if ("verified".equals(verificationOutcome)) {
            LOGGER.info(message);
        } else {
            LOGGER.warn(message);
        }
    }

    /** 记录单次退款裁决，只允许固定 Provider、尝试次数、结果和安全原因进入日志。 */
    public static void refundAttempt(
            PaymentProviderType provider,
            int attemptNo,
            String outcome,
            String safeReason,
            boolean retryAllowed,
            String traceId) {
        LOGGER.warn("event=membership_payment_refund_attempt"
                + " provider=" + provider(provider)
                + " attempt_no=" + attemptNo
                + " outcome=" + token(outcome)
                + " safe_reason=" + token(safeReason)
                + " retry_allowed=" + retryAllowed
                + " traceId=" + token(traceId));
    }

    /** 记录下一次超时退款消息是否完成 Broker Confirm，不接收 callbackId 或消息正文。 */
    public static void refundRetryScheduled(
            int nextAttempt,
            long delayMillis,
            boolean publisherConfirmed,
            String traceId) {
        LOGGER.warn("event=membership_payment_refund_retry_scheduled"
                + " next_attempt=" + nextAttempt
                + " delay_ms=" + delayMillis
                + " publisher_confirmed=" + publisherConfirmed
                + " traceId=" + token(traceId));
    }

    /** 记录退款已进入人工终态队列，日志不包含业务标识或外部错误正文。 */
    public static void refundTerminal(
            String outcome,
            String safeReason,
            boolean manualRequired,
            String traceId) {
        LOGGER.warn("event=membership_payment_refund_terminal"
                + " outcome=" + token(outcome)
                + " safe_reason=" + token(safeReason)
                + " manual_required=" + manualRequired
                + " traceId=" + token(traceId));
    }

    /** 记录历史退款终态重放的开始、完成或失败，只描述受控状态分类，不接收订单与流水标识。 */
    public static void refundRecovery(
            PaymentProviderType provider,
            String orderEntitlementClass,
            boolean orderProviderTradePresent,
            String recoveryOutcome,
            String reason,
            String traceId) {
        String message = "event=membership_payment_refund_recovery"
                + " provider=" + provider(provider)
                + " callback_resolution=refund_required"
                + " order_entitlement_class=" + token(orderEntitlementClass)
                + " order_provider_trade_present=" + orderProviderTradePresent
                + " recovery_action=reapply_terminal_settlement"
                + " recovery_outcome=" + token(recoveryOutcome)
                + " reason=" + token(reason)
                + " traceId=" + token(traceId);
        if ("failed".equals(recoveryOutcome)) {
            LOGGER.warn(message);
        } else {
            LOGGER.info(message);
        }
    }

    /** 记录回调批次的安全重试分类，异常正文和外部响应不得进入该事件。 */
    public static void callbackRetry(
            String failureStage,
            String reason,
            String exceptionClass,
            int count,
            long retryDelayMillis,
            String traceId) {
        LOGGER.warn(
                "event=membership_payment_callback_retry"
                        + " failure_stage=" + token(failureStage)
                        + " reason=" + token(reason)
                        + " exception_class=" + token(exceptionClass)
                        + " count=" + Math.max(0, count)
                        + " retry_delay_ms=" + Math.max(0L, retryDelayMillis)
                        + " traceId=" + token(traceId));
    }

    /** 记录六号页面提交描述已经安全生成，只输出合同分类和字段存在性，不接收表单原值。 */
    public static void liuhaoCheckoutSubmissionCreated(
            String requestedChannel,
            boolean outTradeNoPresent,
            boolean signedFieldsPresent,
            String outcome,
            String reason,
            String traceId) {
        String message = "event=liuhao_checkout_submission_created"
                + " requested_channel=" + token(requestedChannel)
                + " checkout_mode=form_post"
                + " method=post"
                + " action_class=liuhao_submit"
                + " out_trade_no_present=" + outTradeNoPresent
                + " signed_fields_present=" + signedFieldsPresent
                + " outcome=" + token(outcome)
                + " reason=" + token(reason)
                + " traceId=" + token(traceId);
        if ("accepted".equals(outcome)) {
            LOGGER.info(message);
        } else {
            LOGGER.warn(message);
        }
    }

    /** 记录六号响应从传输到业务码的最终验签层级，只接受归一化元数据，不接收响应正文和签名原值。 */
    public static void liuhaoResponseVerification(
            String operation,
            String httpOutcome,
            String httpStatusClass,
            String contentType,
            String bodySizeBucket,
            String jsonShape,
            boolean hasCode,
            boolean hasMsg,
            boolean hasTimestamp,
            boolean hasSign,
            boolean hasSignType,
            boolean hasPid,
            boolean hasTradeNo,
            boolean hasOutTradeNo,
            boolean hasStatus,
            boolean hasTradeStatus,
            boolean unexpectedFieldPresent,
            String codeJsonType,
            String msgCharacterClass,
            String msgWhitespaceProfile,
            String signTypeClass,
            String verificationStage,
            String verificationOutcome,
            String reason,
            String providerCode,
            String providerCodeNumeric,
            String providerCodeTrust,
            String traceId) {
        String message = "event=liuhao_response_verification"
                + " operation=" + token(operation)
                + " http_outcome=" + token(httpOutcome)
                + " http_status_class=" + token(httpStatusClass)
                + " content_type=" + token(contentType)
                + " body_size_bucket=" + token(bodySizeBucket)
                + " json_shape=" + token(jsonShape)
                + " has_code=" + hasCode
                + " has_msg=" + hasMsg
                + " has_timestamp=" + hasTimestamp
                + " has_sign=" + hasSign
                + " has_sign_type=" + hasSignType
                + " has_pid=" + hasPid
                + " has_trade_no=" + hasTradeNo
                + " has_out_trade_no=" + hasOutTradeNo
                + " has_status=" + hasStatus
                + " has_trade_status=" + hasTradeStatus
                + " unexpected_field_present=" + unexpectedFieldPresent
                + " code_json_type=" + token(codeJsonType)
                + " msg_character_class=" + token(msgCharacterClass)
                + " msg_whitespace_profile=" + token(msgWhitespaceProfile)
                + " sign_type_class=" + token(signTypeClass)
                + " verification_stage=" + token(verificationStage)
                + " verification_outcome=" + token(verificationOutcome)
                + " reason=" + token(reason)
                + " provider_code=" + token(providerCode)
                + " provider_code_numeric=" + token(providerCodeNumeric)
                + " provider_code_trust=" + token(providerCodeTrust)
                + " traceId=" + token(traceId);
        if ("verified".equals(verificationOutcome)) {
            LOGGER.info(message);
        } else {
            LOGGER.warn(message);
        }
    }

    /** 记录外部 Provider 下单响应裁决，只保留低基数分类和字段存在性，避免记录交易号或支付入口。 */
    public static void externalPaymentCreateValidation(
            PaymentProviderType provider,
            String requestedChannel,
            boolean tradeNoPresent,
            String payTypeClass,
            boolean payInfoPresent,
            String payInfoKind,
            boolean typeMatch,
            boolean amountMatch,
            String outcome,
            String reason,
            String traceId) {
        String message = "event=external_payment_create_validation"
                + " provider=" + provider(provider)
                + " requested_channel=" + token(requestedChannel)
                + " trade_no_present=" + tradeNoPresent
                + " pay_type_class=" + token(payTypeClass)
                + " pay_info_present=" + payInfoPresent
                + " pay_info_kind=" + token(payInfoKind)
                + " type_match=" + typeMatch
                + " amount_match=" + amountMatch
                + " outcome=" + token(outcome)
                + " reason=" + token(reason)
                + " traceId=" + token(traceId);
        if ("accepted".equals(outcome)) {
            LOGGER.info(message);
        } else {
            LOGGER.warn(message);
        }
    }

    /**
     * 记录六号下单响应的字段一致性裁决，只保留支付载体分类和字段存在性，避免把交易号或跳转内容写入日志。
     */
    public static void liuhaoCreatePayloadValidation(
            String requestedChannel,
            boolean tradeNoPresent,
            String payTypeClass,
            boolean payInfoPresent,
            String payInfoKind,
            boolean typeMatch,
            boolean amountMatch,
            String outcome,
            String reason,
            String traceId) {
        String message = "event=liuhao_create_payload_validation"
                + " requested_channel=" + token(requestedChannel)
                + " trade_no_present=" + tradeNoPresent
                + " pay_type_class=" + token(payTypeClass)
                + " pay_info_present=" + payInfoPresent
                + " pay_info_kind=" + token(payInfoKind)
                + " type_match=" + typeMatch
                + " amount_match=" + amountMatch
                + " outcome=" + token(outcome)
                + " reason=" + token(reason)
                + " traceId=" + token(traceId);
        if ("accepted".equals(outcome)) {
            LOGGER.info(message);
        } else {
            LOGGER.warn(message);
        }
    }

    /**
     * 记录六号微信服务端页面提交的首跳与查询确认裁决；所有参数均为低基数分类，禁止传入 Location 或交易号。
     */
    public static void liuhaoSubmitCheckoutResolution(
            String requestedChannel,
            String httpStatusClass,
            String locationCountClass,
            String routeKind,
            boolean tradeNoPresent,
            String queryLocator,
            String queryOutcome,
            String outcome,
            String reason,
            String traceId) {
        String message = "event=liuhao_submit_checkout_resolution"
                + " requested_channel=" + token(requestedChannel)
                + " http_status_class=" + token(httpStatusClass)
                + " location_count_class=" + token(locationCountClass)
                + " route_kind=" + token(routeKind)
                + " trade_no_present=" + tradeNoPresent
                + " query_locator=" + token(queryLocator)
                + " query_outcome=" + token(queryOutcome)
                + " outcome=" + token(outcome)
                + " reason=" + token(reason)
                + " traceId=" + token(traceId);
        if ("accepted".equals(outcome)) {
            LOGGER.info(message);
        } else {
            LOGGER.warn(message);
        }
    }

    /** 记录 CLOSING 最终边界的查询事实与 CAS 裁决，明确区分平台确认关闭和本地超时关闭。 */
    public static void closingFinalization(
            MembershipOrderSnapshot order,
            PaymentProviderType provider,
            boolean callbackMarker,
            boolean closeResultTrusted,
            String finalQueryRequest,
            String finalQueryHttp,
            String finalQuerySignature,
            PaymentProviderStatus finalQueryStatus,
            MembershipClosingFinalizationSource finalizationSource,
            String transition,
            String nextAction,
            String reason,
            String traceId,
            String messageId) {
        String source = finalizationSource == null
                ? "none"
                : finalizationSource.name().toLowerCase(Locale.ROOT);
        String message = "event=membership_payment_closing_finalization"
                + " provider=" + provider(provider)
                + " trigger=final_boundary"
                + " local_status=" + order.status().name().toLowerCase(Locale.ROOT)
                + " callback_marker=" + callbackMarker
                + " close_result_trusted=" + closeResultTrusted
                + " final_query_request=" + token(finalQueryRequest)
                + " final_query_http=" + token(finalQueryHttp)
                + " final_query_signature=" + token(finalQuerySignature)
                + " final_query_status=" + status(finalQueryStatus)
                + " finalization_source=" + token(source)
                + " transition=" + token(transition)
                + " next_action=" + token(nextAction)
                + " reason=" + token(reason)
                + " traceId=" + token(traceId)
                + " messageId=" + token(messageId);
        if ("stop".equals(nextAction)) {
            LOGGER.info(message);
        } else {
            LOGGER.warn(message);
        }
    }

    private static String provider(PaymentProviderType provider) {
        return provider == null ? UNAVAILABLE : provider.name().toLowerCase(Locale.ROOT);
    }

    private static String status(PaymentProviderStatus status) {
        return status == null ? UNAVAILABLE : status.name().toLowerCase(Locale.ROOT);
    }

    private static String referenceKind(String reference) {
        if (reference == null || reference.isBlank()) {
            return "missing";
        }
        try {
            return PaymentProviderReference.resolveTrade(reference) == null
                    ? "invalid"
                    : "trade";
        } catch (IllegalArgumentException exception) {
            return "invalid";
        }
    }

    private static String locatorKind(String reference) {
        return reference == null ? "out_trade_no" : "trade_no";
    }

    private static String token(String value) {
        return value != null && SAFE_TOKEN.matcher(value).matches() ? value : UNAVAILABLE;
    }

    private static String pathToken(String value) {
        return switch (value) {
            // 路径只允许固定 Provider 白名单，防止不可信输入把任意 URL 写入日志标签。
            case "/api/pay/close", "/api/pay/create", "/api/pay/query", "/api/pay/refund",
                    "/api/pay/submit" ->
                    value;
            default -> UNAVAILABLE;
        };
    }
}
