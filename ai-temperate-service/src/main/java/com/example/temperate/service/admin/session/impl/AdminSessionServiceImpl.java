package com.example.temperate.service.admin.session.impl;

import com.example.temperate.service.admin.config.AdminConfiguration;
import com.example.temperate.service.admin.config.AdminConfigurationService;
import com.example.temperate.service.admin.session.AdminSession;
import com.example.temperate.service.admin.session.AdminSessionIssue;
import com.example.temperate.service.admin.session.AdminSessionProfile;
import com.example.temperate.service.admin.session.AdminSessionService;
import com.example.temperate.service.admin.session.AdminSessionStore;
import com.example.temperate.service.registration.component.token.RegistrationTokenGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.example.temperate.service.risk.preauth.domain.PreAuthSessionBinding;

/**
 * 编排管理员不透明 Token 的签发、六小时滑动续期和撤销。
 *
 * <p>身份资料始终从隐藏配置文件读取，Redis 只保存会话元数据；该服务不生成 Access Token 或 JWT。</p>
 */
@Service
public final class AdminSessionServiceImpl implements AdminSessionService {

    private static final Duration REQUIRED_SESSION_TTL = Duration.ofHours(6);

    private final AdminSessionStore store;
    private final AdminConfigurationService configurationService;
    private final RegistrationTokenGenerator tokenGenerator;
    private final Clock clock;
    private final Duration sessionTtl;
    private final int maximumSessions;

    // 类中保留了包级测试构造器，因此必须显式指定唯一生产注入入口，避免 Spring 回退查找无参构造器。
    @Autowired
    public AdminSessionServiceImpl(
            AdminSessionStore store,
            AdminConfigurationService configurationService,
            RegistrationTokenGenerator tokenGenerator,
            Clock clock,
            com.example.temperate.service.admin.config.properties.AdminProperties properties) {
        this(
                store,
                configurationService,
                tokenGenerator,
                clock,
                properties.sessionTtl(),
                properties.maxSessions());
    }

    AdminSessionServiceImpl(
            AdminSessionStore store,
            AdminConfigurationService configurationService,
            RegistrationTokenGenerator tokenGenerator,
            Clock clock,
            Duration sessionTtl) {
        this(store, configurationService, tokenGenerator, clock, sessionTtl, 10);
    }

    AdminSessionServiceImpl(
            AdminSessionStore store,
            AdminConfigurationService configurationService,
            RegistrationTokenGenerator tokenGenerator,
            Clock clock,
            Duration sessionTtl,
            int maximumSessions) {
        this.store = Objects.requireNonNull(store);
        this.configurationService = Objects.requireNonNull(configurationService);
        this.tokenGenerator = Objects.requireNonNull(tokenGenerator);
        this.clock = Objects.requireNonNull(clock);
        if (!REQUIRED_SESSION_TTL.equals(sessionTtl)) {
            throw new IllegalArgumentException(
                    "Admin session TTL must be exactly six hours.");
        }
        if (maximumSessions < 1 || maximumSessions > 10) {
            throw new IllegalArgumentException("Admin maximum sessions must be between 1 and 10.");
        }
        this.sessionTtl = sessionTtl;
        this.maximumSessions = maximumSessions;
    }

    @Override
    public AdminSessionIssue issue(String deviceInstallationId) {
        AdminConfiguration configuration = configurationService.requireActive();
        String rawToken = tokenGenerator.newRegisterToken();
        Instant now = clock.instant();
        store.create(rawToken, deviceInstallationId, now, sessionTtl, maximumSessions);
        return new AdminSessionIssue(rawToken, profile(configuration, now.plus(sessionTtl)));
    }

    @Override
    public AdminSessionProfile touch(String rawToken, String deviceInstallationId) {
        return touch(rawToken, deviceInstallationId, null);
    }

    /**
     * 启用网络风控时，由存储层在一个 Lua 中同时续期管理员 Hash 字段与其绑定的管理员 PreAuth。
     */
    @Override
    public AdminSessionProfile touch(
            String rawToken,
            String deviceInstallationId,
            PreAuthSessionBinding preAuthBinding) {
        AdminConfiguration configuration = configurationService.requireActive();
        Instant now = clock.instant();
        AdminSession ignored = preAuthBinding == null
                ? store.touch(rawToken, deviceInstallationId, now, sessionTtl)
                : store.touchWithPreAuth(
                        rawToken,
                        deviceInstallationId,
                        now,
                        sessionTtl,
                        preAuthBinding);
        return profile(configuration, now.plus(sessionTtl));
    }

    @Override
    public void logout(String rawToken) {
        store.delete(rawToken);
    }

    @Override
    public void logoutAll() {
        store.deleteAll();
    }

    private static AdminSessionProfile profile(
            AdminConfiguration configuration,
            Instant expiresAt) {
        return new AdminSessionProfile(
                configuration.email(),
                configuration.countryIso2(),
                configuration.phoneE164(),
                expiresAt);
    }
}
