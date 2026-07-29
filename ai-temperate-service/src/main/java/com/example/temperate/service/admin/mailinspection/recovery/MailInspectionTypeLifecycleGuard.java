package com.example.temperate.service.admin.mailinspection.recovery;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 为每一种邮箱检查类型提供独立生命周期锁，串行化创建、恢复、批准和 Marker 清理的关键区。
 *
 * <p>调用方必须遵循“类型锁 → JobStore → Rabbit”的固定顺序；本组件不跨异步 Publisher Confirm 持锁。</p>
 */
@Component
public final class MailInspectionTypeLifecycleGuard {

    private final Map<MailInspectionType, ReentrantLock> locks;

    public MailInspectionTypeLifecycleGuard() {
        EnumMap<MailInspectionType, ReentrantLock> values =
                new EnumMap<>(MailInspectionType.class);
        for (MailInspectionType type : MailInspectionType.values()) {
            values.put(type, new ReentrantLock());
        }
        this.locks = Map.copyOf(values);
    }

    public <T> T withLock(
            MailInspectionType type,
            Supplier<T> action) {
        Objects.requireNonNull(action);
        ReentrantLock lock = requiredLock(type);
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    public void withLock(
            MailInspectionType type,
            Runnable action) {
        withLock(type, () -> {
            action.run();
            return null;
        });
    }

    private ReentrantLock requiredLock(MailInspectionType type) {
        ReentrantLock lock = locks.get(Objects.requireNonNull(type));
        if (lock == null) {
            throw new IllegalArgumentException(
                    "unsupported mail inspection type");
        }
        return lock;
    }
}
