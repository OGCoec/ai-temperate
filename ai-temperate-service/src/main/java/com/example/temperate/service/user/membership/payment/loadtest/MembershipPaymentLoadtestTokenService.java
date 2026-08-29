package com.example.temperate.service.user.membership.payment.loadtest;

import java.util.List;

/**
 * 该服务是来为会员支付全天浸泡测试签发十六个固定账号的十五小时 Access Token，不负责创建账号或修改会员数据。
 */
public interface MembershipPaymentLoadtestTokenService {

    /**
     * 校验白名单账号当前可用且有会员额度后，使用统一 JWT 服务签发十五小时令牌。
     */
    List<MembershipPaymentLoadtestToken> issueForAllowlistedUsers();

    /** 为认证负向用例签发一个已过期但签名正确的 Token，不允许用于普通业务请求。 */
    String issueExpiredToken();

    /** 为白名单边界用例签发一个签名正确但用户 ID 不在配置名单内的 Token。 */
    MembershipPaymentLoadtestToken issueNonAllowlistedToken();
}
