package com.example.temperate.service.user.aiconversation.diagnostic;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要由统一切面记录进入、完成、取消和失败耗时的公开 AI 会话业务边界。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AiConversationLifecycleTimed {

    String stage();
}
