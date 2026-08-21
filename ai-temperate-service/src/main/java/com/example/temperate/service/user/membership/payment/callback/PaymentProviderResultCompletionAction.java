package com.example.temperate.service.user.membership.payment.callback;

/**
 * 该枚举是来声明回调完成时如何收敛模拟支付方结果，避免“无效成功通知”和“数据库唯一冲突”共用删除语义。
 */
public enum PaymentProviderResultCompletionAction {
    KEEP,
    REMOVE,
    RESET_UNPAID
}
