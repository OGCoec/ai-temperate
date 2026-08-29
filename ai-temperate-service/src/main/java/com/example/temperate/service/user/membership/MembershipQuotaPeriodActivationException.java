package com.example.temperate.service.user.membership;

/**
 * 该异常是来把无效会员等级或缺失额度规则转换为统一的周期激活失败，供不同计费入口映射自己的受控错误。
 */
public final class MembershipQuotaPeriodActivationException extends RuntimeException {

    public MembershipQuotaPeriodActivationException(String message) {
        super(message);
    }

    public MembershipQuotaPeriodActivationException(
            String message,
            Throwable cause) {
        super(message, cause);
    }
}
