package com.example.temperate.service.admin.mailinspection.diagnostic;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要记录有界耗时和失败类别的邮件检查边界操作，不采集方法参数或返回值。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface MailInspectionDiagnosticOperation {

    /**
     * 返回稳定、低基数的操作名称，禁止拼入 Job ID、邮箱或消息内容。
     */
    String value();
}
