package com.example.temperate.service.user.membership.payment.loadtest;

import java.util.List;

/**
 * 该服务是来按固定五百用户分页签发八万边界压测账号的短期 Access Token，不负责创建或修改账号。
 */
public interface MembershipPaymentBoundaryTokenService {

    /**
     * 批量校验指定固定页的身份和额度后按用户 ID 顺序签发令牌。
     *
     * @param page 固定页码，范围为 0 到 159
     * @return 包含恰好五百个固定用户的不可变令牌列表
     */
    List<MembershipPaymentLoadtestToken> issuePage(int page);
}
