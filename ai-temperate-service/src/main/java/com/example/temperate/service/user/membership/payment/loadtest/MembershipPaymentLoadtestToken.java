package com.example.temperate.service.user.membership.payment.loadtest;

/**
 * 该记录是来承载本机压测入口为一个既有用户签发的短期 Access Token，禁止把令牌落入业务对象或日志。
 */
public record MembershipPaymentLoadtestToken(long userId, String accessToken) {

    public MembershipPaymentLoadtestToken {
        if (userId <= 0L) {
            throw new IllegalArgumentException("Loadtest token user ID must be positive.");
        }
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("Loadtest access token must not be blank.");
        }
    }
}
