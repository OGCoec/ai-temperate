package com.example.temperate.service.auth.login.strategy;

import com.example.temperate.service.auth.login.enums.LoginErrorCode;
import com.example.temperate.service.auth.login.exception.LoginException;
import com.example.temperate.service.auth.login.dto.result.LoginResult;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 将 Spring 注入的登录策略注册为按枚举类型查找的不可变注册表。
 *
 * <p>启动时拒绝重复策略类型，避免同一请求因 Bean 扫描顺序而走不同认证路径；客户端输入只能映射到
 * 已注册的枚举类型，不能直接参与 Bean 查找。</p>
 */
@Component
public final class LoginStrategyRegistry {

    private final Map<LoginStrategyType, LoginStrategy> strategies;

    public LoginStrategyRegistry(Map<String, LoginStrategy> strategyBeans) {
        EnumMap<LoginStrategyType, LoginStrategy> registered =
                new EnumMap<>(LoginStrategyType.class);
        // 在启动阶段检测重复类型并冻结映射，运行期不允许策略集合或优先级随容器状态变化。
        for (LoginStrategy strategy : strategyBeans.values()) {
            LoginStrategy valid = Objects.requireNonNull(strategy);
            LoginStrategy previous = registered.put(valid.type(), valid);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate login strategy: " + valid.type());
            }
        }
        this.strategies = Map.copyOf(registered);
    }

    public LoginResult login(LoginStrategyType type, LoginStrategyRequest request) {
        LoginStrategy strategy = strategies.get(type);
        if (strategy == null) {
            throw new LoginException(
                    LoginErrorCode.INVALID_INPUT,
                    "Unsupported login strategy.");
        }
        return strategy.login(request);
    }
}
