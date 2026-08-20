package com.example.temperate.service.auth.oauth.flow.impl;

import cn.hutool.core.lang.id.NanoId;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowTokenGenerator;
import com.example.temperate.service.auth.session.token.service.AuthTokenService;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 复用现有安全令牌生成边界产生 OAuth 随机材料，并固定 NanoID32 state/launch 字符集。
 */
@Component
public final class SecureOAuthFlowTokenGenerator implements OAuthFlowTokenGenerator {

    private static final int SHORT_NANO_ID_LENGTH = 32;

    private final AuthTokenService tokenService;

    public SecureOAuthFlowTokenGenerator(AuthTokenService tokenService) {
        this.tokenService = Objects.requireNonNull(tokenService);
    }

    @Override
    public String newFlowToken() {
        return tokenService.newFlowToken();
    }

    @Override
    public String newState() {
        return NanoId.randomNanoId(SHORT_NANO_ID_LENGTH);
    }

    @Override
    public String newLaunchTicket() {
        return NanoId.randomNanoId(SHORT_NANO_ID_LENGTH);
    }

    @Override
    public String newBrowserBinding() {
        return tokenService.newFlowToken();
    }

    @Override
    public String newNonce() {
        return tokenService.newCsrfToken();
    }

    @Override
    public String newPkceVerifier() {
        return tokenService.newCsrfToken();
    }
}
