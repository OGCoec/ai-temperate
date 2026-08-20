package com.example.temperate.mapper.user.identity;

import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.model.auth.domain.TotpCredential;
import com.example.temperate.model.user.domain.CurrentUserProfile;
import com.example.temperate.model.user.entity.UserLoginIdentity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 提供用户登录身份的 MyBatis 数据访问契约。
 *
 * <p>该 Mapper 负责规范化邮箱、手机号和身份 ID 的查询，以及密码与 TOTP 凭据持久化；调用方必须完成
 * 输入规范化、授权和事务边界控制。TOTP 密文只允许专用最小查询读取，Mapper 不承载业务状态机。</p>
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

    UserLoginIdentity findByGithubSubject(@Param("githubSubject") String githubSubject);

    UserLoginIdentity findByGoogleSubject(@Param("googleSubject") String googleSubject);

    /**
     * 在最终 OAuth 裁决事务内锁定邮箱命中的账号，避免并发 Provider 绑定互相覆盖。
     */
    UserLoginIdentity findByNormalizedEmailForUpdate(
            @Param("normalizedEmail") String normalizedEmail);

    UserLoginIdentity findByIdForUpdate(@Param("identityId") long identityId);

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
     * 按已认证用户 ID 读取当前 TOTP 状态和密文；调用方必须在服务层完成解密与状态一致性校验。
     */
    TotpCredential findTotpCredentialById(
            @Param("identityId") long identityId);

    /**
     * 按已认证的内部身份 ID 一次查询个人中心所需的最小资料，不加载密码哈希或会话信息。
     */
    CurrentUserProfile findCurrentUserProfileById(
            @Param("identityId") long identityId);

    boolean existsById(@Param("identityId") long identityId);

    int insert(UserLoginIdentity identity);

    /**
     * 尝试插入 OAuth 首次登录账号；任一唯一约束已被并发请求占用时返回零并保持当前事务可继续查询。
     *
     * <p>调用方必须在返回零后重新按 Subject 与规范化邮箱裁决，禁止把所有冲突直接当成新账号失败。</p>
     */
    int insertOAuthIdentityIfAbsent(UserLoginIdentity identity);

    /**
     * 仅在尚未绑定 GitHub 时写入稳定主体；返回零表示账号不存在或已被其他主体占用。
     */
    int bindGithubSubjectIfAbsent(
            @Param("identityId") long identityId,
            @Param("githubSubject") String githubSubject);

    /**
     * 仅在尚未绑定 Google 时写入稳定主体；返回零表示账号不存在或已被其他主体占用。
     */
    int bindGoogleSubjectIfAbsent(
            @Param("identityId") long identityId,
            @Param("googleSubject") String googleSubject);

    int markEmailVerified(@Param("identityId") long identityId);

    /**
     * OAuth 补验手机号只允许填补空值，禁止借登录流程替换已有安全联系方式。
     */
    int fillPhoneIfAbsent(
            @Param("identityId") long identityId,
            @Param("phone") String phone);

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

    /**
     * 在单条 SQL 中按旧状态和旧密文执行 CAS，再同时写入新密文并启用 TOTP。
     *
     * <p>旧状态比较用于阻止并发开启、轮换或关闭请求互相覆盖，更新失败必须由服务层作为状态冲突处理。</p>
     */
    int enableOrRotateTotp(
            @Param("identityId") long identityId,
            @Param("encryptedSecret") String encryptedSecret,
            @Param("expectedEnabled") boolean expectedEnabled,
            @Param("expectedEncryptedSecret") String expectedEncryptedSecret);

    /**
     * 在单条 SQL 中同时关闭 TOTP 并清空旧密钥，禁止保留可恢复的失效认证材料。
     */
    int disableTotp(@Param("identityId") long identityId);
}
