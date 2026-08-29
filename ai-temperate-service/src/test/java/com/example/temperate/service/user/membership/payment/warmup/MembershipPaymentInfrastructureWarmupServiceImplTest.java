package com.example.temperate.service.user.membership.payment.warmup;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.service.user.membership.payment.exception.MembershipPaymentInfrastructureException;
import com.example.temperate.service.user.membership.payment.warmup.impl.MembershipPaymentInfrastructureWarmupServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisCommands;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisScriptingCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 该单元测试是来证明会员支付技术预热只建立 Redis 连接并加载 Lua，不执行脚本或访问任何业务 Key。
 */
final class MembershipPaymentInfrastructureWarmupServiceImplTest {

    @Test
    void warmupOnlyPingsAndLoadsScripts() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RedisConnection connection = mock(RedisConnection.class);
        RedisCommands commands = mock(RedisCommands.class);
        RedisScriptingCommands scripting = mock(RedisScriptingCommands.class);
        when(connection.commands()).thenReturn(commands);
        when(connection.scriptingCommands()).thenReturn(scripting);
        when(commands.ping()).thenReturn("PONG");
        when(scripting.scriptLoad(any(byte[].class)))
                .thenReturn("0123456789abcdef0123456789abcdef01234567");
        when(redisTemplate.execute(any(RedisCallback.class)))
                .thenAnswer(invocation -> ((RedisCallback<?>) invocation.getArgument(0))
                        .doInRedis(connection));

        MembershipPaymentInfrastructureWarmupServiceImpl service =
                new MembershipPaymentInfrastructureWarmupServiceImpl(redisTemplate);

        assertThatCode(service::warmUpRedisInfrastructure)
                .doesNotThrowAnyException();
        verify(commands).ping();
        verify(scripting, atLeastOnce()).scriptLoad(any(byte[].class));
        verifyNoMoreInteractions(commands, scripting);
    }

    @Test
    void invalidScriptShaFailsWarmupWithoutExecutingLua() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RedisConnection connection = mock(RedisConnection.class);
        RedisCommands commands = mock(RedisCommands.class);
        RedisScriptingCommands scripting = mock(RedisScriptingCommands.class);
        when(connection.commands()).thenReturn(commands);
        when(connection.scriptingCommands()).thenReturn(scripting);
        when(commands.ping()).thenReturn("PONG");
        when(scripting.scriptLoad(any(byte[].class))).thenReturn("invalid");
        when(redisTemplate.execute(any(RedisCallback.class)))
                .thenAnswer(invocation -> ((RedisCallback<?>) invocation.getArgument(0))
                        .doInRedis(connection));
        MembershipPaymentInfrastructureWarmupServiceImpl service =
                new MembershipPaymentInfrastructureWarmupServiceImpl(redisTemplate);

        assertThatThrownBy(service::warmUpRedisInfrastructure)
                .isInstanceOf(MembershipPaymentInfrastructureException.class)
                .hasMessageContaining("invalid script SHA1");
        verify(scripting).scriptLoad(any(byte[].class));
        verifyNoMoreInteractions(scripting);
    }
}
