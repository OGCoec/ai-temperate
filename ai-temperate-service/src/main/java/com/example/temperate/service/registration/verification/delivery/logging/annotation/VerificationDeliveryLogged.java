package com.example.temperate.service.registration.verification.delivery.logging.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要由统一 AOP 记录供应商选择、安全响应和完成结果的验证码投递方法。
 *
 * <p>注解固定放在 Service 接口方法上，使供应商实现不承担日志编排，也避免每个实现重复输出同一事件。</p>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface VerificationDeliveryLogged {}
