package com.example.temperate.web.auth.identity.bloom;

import com.example.temperate.service.auth.identity.bloom.IdentityPresenceFilter;
import java.util.Objects;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 在唯一可执行 Web 应用完成启动后触发已注册身份 Bloom 的后台初始化。
 *
 * <p>监听器不执行数据库分页或 Redis 写入，只调用 Service 接口把任务交给专用执行器，避免阻塞应用
 * Ready 事件线程。</p>
 */
@Component
public final class IdentityPresenceBloomStartupListener {

    private final IdentityPresenceFilter filter;

    public IdentityPresenceBloomStartupListener(IdentityPresenceFilter filter) {
        this.filter = Objects.requireNonNull(filter);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        filter.initializeInBackground();
    }
}
