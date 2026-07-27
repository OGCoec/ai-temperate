package com.example.temperate.service.admin.session.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.service.admin.config.AdminConfiguration;
import com.example.temperate.service.admin.config.AdminConfigurationService;
import com.example.temperate.service.admin.config.AdminStatus;
import com.example.temperate.service.admin.config.properties.AdminProperties;
import com.example.temperate.service.admin.session.AdminSession;
import com.example.temperate.service.admin.session.AdminSessionStore;
import com.example.temperate.service.registration.component.token.RegistrationTokenGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 验证管理员会话只保存受保护 Token，并在每次成功访问时执行六小时滑动续期。
 */
@ExtendWith(MockitoExtension.class)
class AdminSessionServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-07-23T13:00:00Z");

    @Mock
    AdminSessionStore store;

    @Mock
    AdminConfigurationService configurationService;

    @Mock
    RegistrationTokenGenerator tokenGenerator;

    @Test
    void productionConstructorIsTheOnlyExplicitAutowiredCandidate() {
        var autowiredConstructors = Arrays.stream(
                        AdminSessionServiceImpl.class.getDeclaredConstructors())
                .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
                .toList();

        assertThat(autowiredConstructors)
                .singleElement()
                .satisfies(constructor -> assertThat(constructor.getParameterTypes())
                        .contains(AdminProperties.class));
    }

    @Test
    void touchExtendsExistingSessionAndReturnsCanonicalProfile() {
        AdminConfiguration configuration = new AdminConfiguration(
                1,
                AdminStatus.ACTIVE,
                "admin@example.test",
                "US",
                "+12164202316",
                "{bcrypt}$2a$10$" + "A".repeat(53),
                NOW,
                NOW);
        AdminSession session = new AdminSession(1, "device-digest", NOW, NOW);
        when(configurationService.requireActive()).thenReturn(configuration);
        when(store.touch("raw-token", "device-id", NOW, Duration.ofHours(6)))
                .thenReturn(session);
        AdminSessionServiceImpl service = new AdminSessionServiceImpl(
                store,
                configurationService,
                tokenGenerator,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofHours(6));

        var result = service.touch("raw-token", "device-id");

        assertThat(result.email()).isEqualTo("admin@example.test");
        assertThat(result.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(6)));
        verify(store).touch("raw-token", "device-id", NOW, Duration.ofHours(6));
    }
}
