package com.example.temperate.service.auth.login.code.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * 验证手机验证码登录状态机通过服务端 purpose 隔离 LOGIN 与 OAUTH_PHONE。
 */
class OAuthPhoneLoginFlowContractTest {

    @Test
    void redisFlowPersistsServerSelectedPurpose() throws Exception {
        String create = read("create_login_code_flow.lua");
        String get = read("get_login_code_flow.lua");
        assertThat(create).contains("'purpose', purpose");
        assertThat(get).contains("'purpose'");
    }

    private static String read(String name) throws Exception {
        return Files.readString(
                Path.of("src", "main", "resources", "lua", "auth-login", name),
                StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
    }
}
