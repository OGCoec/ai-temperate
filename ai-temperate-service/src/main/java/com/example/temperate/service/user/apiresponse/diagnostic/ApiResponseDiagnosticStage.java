package com.example.temperate.service.user.apiresponse.diagnostic;

/**
 * 该枚举是来以固定低基数标识 Responses 公开入口和业务服务两个 AOP 观察阶段。
 */
public enum ApiResponseDiagnosticStage {
    HTTP_CONTROLLER,
    RESPONSE_SERVICE
}
