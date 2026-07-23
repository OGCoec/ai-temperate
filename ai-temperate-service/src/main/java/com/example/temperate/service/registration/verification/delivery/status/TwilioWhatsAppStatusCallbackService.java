package com.example.temperate.service.registration.verification.delivery.status;

import java.util.Map;

/**
 * 校验 Twilio Messaging 状态回调并写入幂等状态索引，不执行任何新的 Provider 发送。
 */
public interface TwilioWhatsAppStatusCallbackService {

    boolean handle(String requestUrl, String signature, Map<String, String> parameters);
}
