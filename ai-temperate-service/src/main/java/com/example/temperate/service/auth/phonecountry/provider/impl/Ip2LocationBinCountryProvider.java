package com.example.temperate.service.auth.phonecountry.provider.impl;

import com.example.temperate.service.auth.phonecountry.provider.IpCountryProvider;
import com.ip2location.IP2Location;
import com.ip2location.IPResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于本地 IP2Location BIN 文件解析客户端国家代码的提供者实现。
 *
 * <p>BIN 缺失、初始化失败和查询异常均 Fail Open 返回空值，以免地理库故障阻断认证；生命周期和查询分别
 * 使用锁保护，避免关闭客户端与并发 IP 查询交叉执行。</p>
 */
public final class Ip2LocationBinCountryProvider implements IpCountryProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(Ip2LocationBinCountryProvider.class);

    private final boolean enabled;
    private final String configuredBinPath;
    private final Counter resolvedCounter;
    private final Counter notFoundCounter;
    private final Counter unavailableCounter;
    private final Counter errorCounter;
    private final Object lifecycleLock = new Object();
    private final Object queryLock = new Object();
    private volatile IP2Location client;

    public Ip2LocationBinCountryProvider(
            boolean enabled,
            String configuredBinPath,
            MeterRegistry meterRegistry) {
        this.enabled = enabled;
        this.configuredBinPath = configuredBinPath;
        this.resolvedCounter = counter(meterRegistry, "resolved");
        this.notFoundCounter = counter(meterRegistry, "not_found");
        this.unavailableCounter = counter(meterRegistry, "unavailable");
        this.errorCounter = counter(meterRegistry, "error");
    }

    @PostConstruct
    void initialize() {
        if (!enabled) {
            return;
        }
        synchronized (lifecycleLock) {
            if (client != null) {
                return;
            }
            Path binPath = resolveBinPath(configuredBinPath);
            if (binPath == null || !Files.isRegularFile(binPath) || !Files.isReadable(binPath)) {
                LOGGER.warn(
                        "IP2Location BIN is unavailable, phone country lookup will fail open, configuredPathPresent={}",
                        configuredBinPath != null && !configuredBinPath.isBlank());
                return;
            }
            try {
                IP2Location created = new IP2Location();
                created.Open(binPath.toString(), true);
                client = created;
                LOGGER.info("IP2Location BIN loaded for phone country lookup");
            } catch (Exception exception) {
                LOGGER.warn(
                        "IP2Location BIN load failed, phone country lookup will fail open, exceptionType={}",
                        exceptionType(exception));
            }
        }
    }

    @Override
    public Optional<String> findCountryIso2(String canonicalClientIp) {
        if (!enabled || canonicalClientIp == null || canonicalClientIp.isBlank()) {
            unavailableCounter.increment();
            return Optional.empty();
        }
        IP2Location current = client;
        if (current == null) {
            unavailableCounter.increment();
            return Optional.empty();
        }
        try {
            IPResult result;
            // IP2Location 客户端与 close 共享底层资源，查询和释放必须互斥以避免关闭期间使用已释放句柄。
            synchronized (queryLock) {
                result = current.IPQuery(canonicalClientIp.trim());
            }
            if (result == null || !"OK".equalsIgnoreCase(safeText(result.getStatus()))) {
                notFoundCounter.increment();
                return Optional.empty();
            }
            Optional<String> countryIso2 = normalizeCountryIso2(result.getCountryShort());
            if (countryIso2.isPresent()) {
                resolvedCounter.increment();
            } else {
                notFoundCounter.increment();
            }
            return countryIso2;
        } catch (Exception exception) {
            errorCounter.increment();
            return Optional.empty();
        }
    }

    @PreDestroy
    public void close() {
        synchronized (lifecycleLock) {
            IP2Location current = client;
            if (current == null) {
                return;
            }
            synchronized (queryLock) {
                try {
                    current.Close();
                } catch (Exception ignored) {
                    // The process is shutting down; no user or network data is logged here.
                } finally {
                    client = null;
                }
            }
        }
    }

    private static Counter counter(MeterRegistry meterRegistry, String outcome) {
        return Counter.builder("auth.phone_country.lookup")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }

    private static Optional<String> normalizeCountryIso2(String countryIso2) {
        if (countryIso2 == null) {
            return Optional.empty();
        }
        String normalized = countryIso2.trim().toUpperCase(Locale.ROOT);
        return normalized.matches("^[A-Z]{2}$")
                ? Optional.of(normalized)
                : Optional.empty();
    }

    private static Path resolveBinPath(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            return null;
        }
        try {
            Path configured = Paths.get(configuredPath.trim());
            if (configured.isAbsolute()) {
                return configured.normalize();
            }
            return Paths.get(System.getProperty("user.dir", "."))
                    .resolve(configured)
                    .toAbsolutePath()
                    .normalize();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private static String exceptionType(Exception exception) {
        String simpleName = exception.getClass().getSimpleName();
        return simpleName.isBlank() ? "UnknownException" : simpleName;
    }
}
