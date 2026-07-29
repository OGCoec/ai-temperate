package com.example.temperate.web.auth.identity.bloom;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.temperate.service.auth.identity.bloom.IdentityPresenceFilter;
import org.junit.jupiter.api.Test;

/**
 * 验证应用完成启动后只触发身份 Bloom 的后台初始化入口。
 */
class IdentityPresenceBloomStartupListenerTest {

    @Test
    void startsBackgroundInitializationAfterApplicationReady() {
        IdentityPresenceFilter filter = mock(IdentityPresenceFilter.class);
        IdentityPresenceBloomStartupListener listener =
                new IdentityPresenceBloomStartupListener(filter);

        listener.initialize();

        verify(filter).initializeInBackground();
    }
}
