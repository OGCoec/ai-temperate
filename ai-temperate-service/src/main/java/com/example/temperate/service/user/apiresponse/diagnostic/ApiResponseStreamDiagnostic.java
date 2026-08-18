package com.example.temperate.service.user.apiresponse.diagnostic;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 该注解是来声明 Responses Controller 或 Service 的诊断阶段，由 AOP 统一建立请求级会话并观察惰性 Publisher。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiResponseStreamDiagnostic {

    ApiResponseDiagnosticStage stage();
}
