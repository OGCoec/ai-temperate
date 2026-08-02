package com.example.temperate.service.user.aiconversation.diagnostic;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要由统一 AOP 切面输出安全 AI 流失败日志的跨 Bean 诊断方法。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AiConversationStreamFailureDiagnostic {
}
