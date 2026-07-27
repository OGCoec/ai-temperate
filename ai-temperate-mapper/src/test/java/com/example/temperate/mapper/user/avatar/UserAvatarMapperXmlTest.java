package com.example.temperate.mapper.user.avatar;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

/**
 * 验证头像 URL 的行锁读取和单字段更新 Mapper XML 可以完整注册。
 */
class UserAvatarMapperXmlTest {

    @Test
    void registersAvatarUrlReadAndUpdateStatements() throws Exception {
        Configuration configuration = new Configuration();
        parse(configuration, "mapper/user/avatar/UserAvatarMapper.xml");

        assertThat(configuration.hasStatement(
                "com.example.temperate.mapper.user.avatar.UserAvatarMapper.findByUserIdForUpdate"))
                .isTrue();
        assertThat(configuration.hasStatement(
                "com.example.temperate.mapper.user.avatar.UserAvatarMapper.updateAvatar"))
                .isTrue();
        String lockSql = configuration.getMappedStatement(
                        "com.example.temperate.mapper.user.avatar.UserAvatarMapper.findByUserIdForUpdate")
                .getBoundSql(Map.of("userId", 10001L))
                .getSql()
                .toLowerCase(Locale.ROOT);
        assertThat(lockSql).contains("for update");
        String updateSql = configuration.getMappedStatement(
                        "com.example.temperate.mapper.user.avatar.UserAvatarMapper.updateAvatar")
                .getBoundSql(Map.of("userId", 10001L, "avatarUrl", "https://cdn.example/avatar.webp"))
                .getSql()
                .toLowerCase(Locale.ROOT);
        assertThat(updateSql)
                .contains("update user_profile", "set avatar_url")
                .doesNotContain("avatar_object_key");
    }

    private static void parse(Configuration configuration, String resource) throws Exception {
        Path path = Path.of("src/main/resources").resolve(resource);
        try (InputStream input = Files.newInputStream(path)) {
            new XMLMapperBuilder(
                    input,
                    configuration,
                    resource,
                    configuration.getSqlFragments())
                    .parse();
        }
    }
}
