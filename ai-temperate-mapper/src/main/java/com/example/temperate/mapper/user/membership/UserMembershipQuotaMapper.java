package com.example.temperate.mapper.user.membership;

import com.example.temperate.model.user.entity.UserMembershipQuota;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 提供当前用户会员等级和额度记录的 MyBatis 持久化契约。
 *
 * <p>注册流程只创建一对一默认记录，等级和额度由数据库默认值统一初始化；认证流程不得通过该 Mapper 判断账号可用性。</p>
 */
@Mapper
public interface UserMembershipQuotaMapper {

    int insert(UserMembershipQuota membershipQuota);

    UserMembershipQuota findByLoginIdentityId(
            @Param("loginIdentityId") long loginIdentityId);
}
