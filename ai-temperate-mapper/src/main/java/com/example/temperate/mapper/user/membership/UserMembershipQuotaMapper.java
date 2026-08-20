package com.example.temperate.mapper.user.membership;

import com.example.temperate.model.user.entity.UserMembershipQuota;
import com.example.temperate.model.ai.entity.AiModelUsageRefundCandidate;
import com.example.temperate.model.ai.entity.AiModelApiUsageRefundCandidate;
import java.time.OffsetDateTime;
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

    /**
     * 对一个已认证用户原子执行付费会员惰性过期；FREE、未到期付费会员以及并发请求已经处理的记录均返回零。
     *
     * <p>付费等级缺少到期时间也视为失效，降级时同时开启新的 FREE 七天额度周期。</p>
     */
    int expirePaidMembershipIfDue(
            @Param("loginIdentityId") long loginIdentityId,
            @Param("now") OffsetDateTime now,
            @Param("freeQuotaMinor") long freeQuotaMinor,
            @Param("freeQuotaEndsAt") OffsetDateTime freeQuotaEndsAt);

    int addHistoricalAiRefunds(
            @Param("candidates") List<AiModelUsageRefundCandidate> candidates);

    int addApiRefunds(
            @Param("candidates") List<AiModelApiUsageRefundCandidate> candidates);
}
