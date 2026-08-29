package com.example.temperate.service.user.membership.payment.warmup.impl;

import com.example.temperate.service.user.membership.payment.exception.MembershipPaymentInfrastructureException;
import com.example.temperate.service.user.membership.payment.warmup.MembershipPaymentInfrastructureWarmupService;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 该实现是来通过 PING 和 SCRIPT LOAD 建立 Lettuce 连接并加载全部会员支付 Lua，不执行脚本也不触碰业务数据。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class MembershipPaymentInfrastructureWarmupServiceImpl
        implements MembershipPaymentInfrastructureWarmupService {

    private static final String SCRIPT_PATTERN =
            "classpath*:lua/membership-payment/*.lua";

    private final StringRedisTemplate redisTemplate;
    private final PathMatchingResourcePatternResolver resourceResolver =
            new PathMatchingResourcePatternResolver();

    public MembershipPaymentInfrastructureWarmupServiceImpl(
            StringRedisTemplate redisTemplate) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
    }

    @Override
    public void warmUpRedisInfrastructure() {
        try {
            String pong = redisTemplate.execute(
                    (RedisCallback<String>) connection -> connection.commands().ping());
            if (!"PONG".equalsIgnoreCase(pong)) {
                throw unavailable("Membership payment Redis warmup PING failed.");
            }
            List<Resource> scripts = scripts();
            for (Resource script : scripts) {
                String sha = load(script);
                if (sha == null || !sha.matches("^[0-9a-f]{40}$")) {
                    throw unavailable(
                            "Membership payment Redis warmup returned an invalid script SHA1.");
                }
            }
        } catch (MembershipPaymentInfrastructureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable("Membership payment Redis warmup failed.", exception);
        }
    }

    private List<Resource> scripts() {
        try {
            List<Resource> resources = Arrays.stream(
                            resourceResolver.getResources(SCRIPT_PATTERN))
                    .sorted((left, right) -> fileName(left).compareTo(fileName(right)))
                    .toList();
            if (resources.isEmpty()) {
                throw unavailable("Membership payment Redis warmup found no Lua scripts.");
            }
            Set<String> fileNames = new HashSet<>();
            for (Resource resource : resources) {
                String fileName = fileName(resource);
                if (!fileNames.add(fileName)) {
                    throw unavailable(
                            "Membership payment Redis warmup found a duplicate Lua resource.");
                }
            }
            return resources;
        } catch (IOException exception) {
            throw unavailable(
                    "Membership payment Redis warmup could not enumerate Lua scripts.",
                    exception);
        }
    }

    private String load(Resource resource) {
        try (InputStream input = resource.getInputStream()) {
            byte[] source = input.readAllBytes();
            if (source.length == 0) {
                throw unavailable(
                        "Membership payment Redis warmup found an empty Lua script.");
            }
            return redisTemplate.execute(
                    (RedisCallback<String>) connection ->
                            connection.scriptingCommands().scriptLoad(source));
        } catch (IOException exception) {
            throw unavailable(
                    "Membership payment Redis warmup could not read a Lua script.",
                    exception);
        }
    }

    private static String fileName(Resource resource) {
        String fileName = resource.getFilename();
        if (fileName == null || !fileName.matches("^[a-z0-9_]+\\.lua$")) {
            throw unavailable(
                    "Membership payment Redis warmup found an invalid Lua resource name.");
        }
        return fileName;
    }

    private static MembershipPaymentInfrastructureException unavailable(String message) {
        return new MembershipPaymentInfrastructureException(message);
    }

    private static MembershipPaymentInfrastructureException unavailable(
            String message,
            Throwable cause) {
        return new MembershipPaymentInfrastructureException(message, cause);
    }
}
