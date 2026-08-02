package com.example.temperate.service.user.aiconversation.diagnostic;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要由 Spring AOP 惰性包装订阅生命周期的模型流公开方法。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AiConversationStreamTiming {
}
