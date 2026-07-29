package com.example.temperate.mapper.user.identity;

import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.model.user.domain.CurrentUserProfile;
import com.example.temperate.model.user.entity.UserLoginIdentity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 提供用户登录身份的 MyBatis 数据访问契约。
 *
 * <p>该 Mapper 负责规范化邮箱、手机号和身份 ID 的查询及密码相关持久化；调用方必须完成输入规范化、
 * 授权和事务边界控制，Mapper 不承载业务状态机。</p>
 */
@Mapper
public interface UserLoginIdentityMapper {

    /**
     * 在一次数据库请求中按各个唯一联系方式查询至多一条冲突记录。
     *
     * <p>调用方必须至少提供一个非空且已规范化的联系方式；邮箱必须转为小写，手机号必须符合项目统一格式。</p>
     */
    List<UserLoginIdentity> findConflicts(
            @Param("normalizedEmail") String normalizedEmail,
            @Param("normalizedPhone") String normalizedPhone);

    UserLoginIdentity findByNormalizedEmail(
            @Param("normalizedEmail") String normalizedEmail);

    UserLoginIdentity findByNormalizedPhone(
            @Param("normalizedPhone") String normalizedPhone);

    /**
     * 按内部 ID 游标分页读取 Bloom 初始化所需的最小联系方式列。
     *
     * <p>调用方必须把单批限制在 2000 条以内，并以上一页最后一个 ID 继续，禁止使用深分页或一次加载全表。</p>
     */
    List<UserLoginIdentity> findIdentityContactsAfterId(
            @Param("afterId") long afterId,
            @Param("limit") int limit);

    AuthenticationContext findAuthenticationByNormalizedEmail(
            @Param("normalizedEmail") String normalizedEmail);

    AuthenticationContext findAuthenticationByNormalizedPhone(
            @Param("normalizedPhone") String normalizedPhone);

    AuthenticationContext findAuthenticationById(
            @Param("identityId") long identityId);

    /**
     * 按已认证的内部身份 ID 一次查询个人中心所需的最小资料，不加载密码哈希或会话信息。
     */
    CurrentUserProfile findCurrentUserProfileById(
            @Param("identityId") long identityId);

    int insert(UserLoginIdentity identity);

    int updatePasswordHash(
            @Param("id") Long id,
            @Param("passwordHash") String passwordHash);

    /**
     * 在同一条 SQL 中修改真实密码哈希并递增凭据版本。
     *
     * <p>哈希和版本必须同时提交，避免新密码生效后旧会话仍被视为有效；通用更新时间由数据库触发器维护。</p>
     */
    int updatePasswordHashAndIncrementVersion(
            @Param("id") long id,
            @Param("passwordHash") String passwordHash);

    /**
     * 以当前密码哈希为比较条件执行密码哈希升级，避免并发登录时较晚请求覆盖较新的升级结果。
     */
    int upgradePasswordHashCas(
            @Param("identityId") long identityId,
            @Param("expectedPasswordHash") String expectedPasswordHash,
            @Param("upgradedPasswordHash") String upgradedPasswordHash);
}
