package com.example.temperate.mapper.user.membership;

import com.example.temperate.model.user.entity.UserMembershipQuota;
import com.example.temperate.model.ai.entity.AiModelUsageRefundCandidate;
import com.example.temperate.model.ai.entity.AiModelApiUsageRefundCandidate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 提供当前用户会员等级和额度记录的 MyBatis 持久化契约。
 *
 * <p>注册流程创建一对一记录并显式提供等级、额度和周期边界，数据库默认值只作为非应用写入的安全兜底；
 * 认证流程不得通过该 Mapper 判断账号可用性。</p>
 */
@Mapper
public interface UserMembershipQuotaMapper {

    int insert(UserMembershipQuota membershipQuota);

    UserMembershipQuota findByLoginIdentityId(
            @Param("loginIdentityId") long loginIdentityId);

    UserMembershipQuota findByLoginIdentityIdForUpdate(
            @Param("loginIdentityId") long loginIdentityId);

    int updateBalanceAndPeriod(UserMembershipQuota membershipQuota);

    int addHistoricalAiRefunds(
            @Param("candidates") List<AiModelUsageRefundCandidate> candidates);

    int addApiRefunds(
            @Param("candidates") List<AiModelApiUsageRefundCandidate> candidates);
}
