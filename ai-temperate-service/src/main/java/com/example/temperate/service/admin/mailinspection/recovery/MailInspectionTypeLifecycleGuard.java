package com.example.temperate.service.admin.mailinspection.recovery;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
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

    /**
     * 按枚举顺序一次持有多个类型锁，供必须在同一权威快照下处理全部类型的维护任务使用。
     *
     * <p>固定顺序避免维护任务之间互相等待；调用方不得在动作中等待异步确认，
     * 也不应把该入口用于普通单类型请求。</p>
     *
     * @param types 需要串行化的类型集合
     * @param action 获取全部锁后执行的有界动作
     */
    public void withLocks(
            Collection<MailInspectionType> types,
            Runnable action) {
        Objects.requireNonNull(types);
        Objects.requireNonNull(action);
        List<MailInspectionType> ordered = types.stream()
                .map(Objects::requireNonNull)
                .distinct()
                .sorted()
                .toList();
        List<ReentrantLock> acquired = new ArrayList<>(ordered.size());
        try {
            for (MailInspectionType type : ordered) {
                ReentrantLock lock = requiredLock(type);
                lock.lock();
                acquired.add(lock);
            }
            action.run();
        } finally {
            // 与加锁顺序相反释放，保留清晰的栈式资源边界。
            for (int index = acquired.size() - 1; index >= 0; index--) {
                acquired.get(index).unlock();
            }
        }
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
