package com.example.temperate.service.user.avatar;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.auth.session.refresh.dto.command.NewRefreshSession;
import com.example.temperate.service.auth.session.refresh.dto.result.RefreshSessionSnapshot;
import com.example.temperate.service.auth.session.token.dto.result.VerifiedAccessToken;
import com.example.temperate.service.user.avatar.impl.UserAvatarServiceImpl;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 验证头像功能不扩展 Refresh Session、Token、Redis 或 RabbitMQ 边界。
 */
class UserAvatarIsolationContractTest {

    @Test
    void accessTokenAndRefreshSessionRecordsDoNotContainAvatarData() {
        assertThat(NewRefreshSession.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .noneMatch(name -> name.toLowerCase().contains("avatar"));
        assertThat(RefreshSessionSnapshot.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .noneMatch(name -> name.toLowerCase().contains("avatar"));
        assertThat(VerifiedAccessToken.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .noneMatch(name -> name.toLowerCase().contains("avatar"));
    }

    @Test
    void avatarOrchestrationDoesNotDependOnRedisOrRabbitMq() throws Exception {
        assertThat(UserAvatarServiceImpl.class.getDeclaredFields())
                .extracting(field -> field.getType().getName())
                .noneMatch(type -> type.contains("redis") || type.contains("Rabbit"));

        String source = Files.readString(Path.of(
                "src/main/java/com/example/temperate/service/user/avatar/impl/UserAvatarServiceImpl.java"));
        assertThat(source)
                .doesNotContain("RefreshSession", "RefreshToken", "StringRedisTemplate", "RabbitTemplate");
    }
}
