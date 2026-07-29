package com.example.temperate.service.admin.mailinspection.strategy;

import com.example.temperate.service.admin.AdminErrorCode;
import com.example.temperate.service.admin.AdminException;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 在启动期收集全部邮箱检查策略并转换为不可变 EnumMap，供任务协调器按稳定类型选择。
 */
@Component
public final class MailInspectionStrategyRegistry {

    private final Map<MailInspectionType, MailInspectionStrategy> strategies;

    public MailInspectionStrategyRegistry(
            Map<String, MailInspectionStrategy> strategyBeans) {
        EnumMap<MailInspectionType, MailInspectionStrategy> registered =
                new EnumMap<>(MailInspectionType.class);
        for (MailInspectionStrategy strategy : strategyBeans.values()) {
            MailInspectionStrategy previous =
                    registered.put(strategy.type(), strategy);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate mail inspection strategy: " + strategy.type());
            }
        }
        this.strategies = Map.copyOf(registered);
    }

    /**
     * 未注册类型返回受控管理员基础设施错误，不允许空策略进入异步任务。
     */
    public MailInspectionStrategy getRequired(MailInspectionType type) {
        MailInspectionStrategy strategy = strategies.get(type);
        if (strategy == null) {
            throw new AdminException(
                    AdminErrorCode.ADMIN_MAIL_INSPECTION_TYPE_UNAVAILABLE,
                    "mail inspection strategy is unavailable");
        }
        return strategy;
    }
}
