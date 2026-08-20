package com.example.temperate.service.auth.oauth.identity;

import com.example.temperate.model.auth.enums.RegistrationSource;
import com.example.temperate.model.user.entity.UserLoginIdentity;
import com.example.temperate.service.auth.oauth.domain.OAuthProvider;

/**
 * 定义某个 Provider 稳定 Subject 的查询、读取和条件绑定能力。
 *
 * <p>实现只选择固定数据库列，禁止根据客户端字符串动态拼接列名；数据库唯一索引仍是并发绑定的最终裁决。</p>
 */
public interface OAuthSubjectBindingStrategy {

    OAuthProvider provider();

    RegistrationSource registrationSource();

    UserLoginIdentity findBySubject(String subject);

    String subjectOf(UserLoginIdentity identity);

    void applySubject(UserLoginIdentity identity, String subject);

    int bindIfAbsent(long identityId, String subject);
}
