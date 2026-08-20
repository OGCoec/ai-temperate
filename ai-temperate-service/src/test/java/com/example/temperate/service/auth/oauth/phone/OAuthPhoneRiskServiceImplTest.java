package com.example.temperate.service.auth.oauth.phone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.auth.oauth.flow.ProtectedOAuthFlowAccess;
import com.example.temperate.service.auth.oauth.phone.impl.OAuthPhoneRiskServiceImpl;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 验证 OAuth 手机风控向 Lua 传入固定窗口、冷却与封禁边界，并在 Redis 异常时关闭发送通路。
 */
class OAuthPhoneRiskServiceImplTest {

    private StringRedisTemplate redisTemplate;
    private RedisKeyFactory keyFactory;
    private OAuthPhoneRiskService service;
    private ProtectedOAuthFlowAccess access;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        keyFactory = mock(RedisKeyFactory.class);
        service = new OAuthPhoneRiskServiceImpl(redisTemplate, keyFactory);
        HmacIdentifier flow = id('A');
        HmacIdentifier device = id('B');
        access = new ProtectedOAuthFlowAccess(flow, id('C'), device, id('D'));
        when(keyFactory.oauthPhoneSendRiskKey(flow)).thenReturn("send-risk");
        when(keyFactory.oauthPhoneBlockKey(flow)).thenReturn("flow-block");
        when(keyFactory.globalDeviceBlockKey(device)).thenReturn("device-block");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void sendRiskUsesFiveMinuteWindowFiveAttemptsAndTwoHourBlock() {
        doReturn(0L).when(redisTemplate).execute(
                any(RedisScript.class), anyList(), any(Object[].class));

        service.requireSendAllowed(access, Instant.ofEpochMilli(1_000L));

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of("send-risk", "flow-block", "device-block")),
                arguments.capture());
        assertThat(arguments.getValue())
                .containsExactly("1000", "300000", "60000", "5", "7200");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void blockedOrUnavailableRedisNeverAllowsSmsDeliveryToContinue() {
        doReturn(2L).when(redisTemplate).execute(
                any(RedisScript.class), anyList(), any(Object[].class));

        assertThatThrownBy(() -> service.requireSendAllowed(access, Instant.EPOCH))
                .isInstanceOf(OAuthPhoneRiskException.class);

        doReturn(null).when(redisTemplate).execute(
                any(RedisScript.class), anyList(), any(Object[].class));
        assertThatThrownBy(() -> service.requireSendAllowed(access, Instant.EPOCH))
                .isInstanceOf(OAuthPhoneRiskException.class);
    }

    private static HmacIdentifier id(char value) {
        return HmacIdentifier.fromProtectedValue(String.valueOf(value).repeat(43));
    }
}
