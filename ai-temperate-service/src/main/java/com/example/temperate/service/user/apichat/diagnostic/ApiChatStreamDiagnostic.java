package com.example.temperate.service.user.apichat.diagnostic;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 该注解是来声明需要记录同步入口和返回类型的方法，AOP 只做惰性包装，禁止主动订阅响应流。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiChatStreamDiagnostic {

    ApiChatDiagnosticStage value();
}
