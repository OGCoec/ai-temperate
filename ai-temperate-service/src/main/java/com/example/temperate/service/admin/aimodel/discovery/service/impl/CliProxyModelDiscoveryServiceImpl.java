package com.example.temperate.service.admin.aimodel.discovery.service.impl;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.mapper.ai.AiModelMapper;
import com.example.temperate.model.ai.entity.AiModel;
import com.example.temperate.service.admin.aimodel.discovery.client.CliProxyModelClient;
import com.example.temperate.service.admin.aimodel.discovery.config.CliProxyModelDiscoveryProperties;
import com.example.temperate.service.admin.aimodel.discovery.dto.CliProxyDiscoveredModel;
import com.example.temperate.service.admin.aimodel.discovery.dto.CliProxyModelDiscoveryResult;
import com.example.temperate.service.admin.aimodel.discovery.dto.CliProxyModelMatchStatus;
import com.example.temperate.service.admin.aimodel.discovery.exception.CliProxyModelDiscoveryErrorCode;
import com.example.temperate.service.admin.aimodel.discovery.exception.CliProxyModelDiscoveryException;
import com.example.temperate.service.admin.aimodel.discovery.service.CliProxyModelDiscoveryService;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 校验、去重并稳定排序 CLIProxyAPI 模型，再通过一次批量 SQL 丰富本地目录状态和倍率。
 *
 * <p>外部 HTTP 在任何数据库事务之外完成；该服务不写 PostgreSQL、Redis 或前端持久存储。</p>
 */
@Service
public final class CliProxyModelDiscoveryServiceImpl implements CliProxyModelDiscoveryService {

    private static final String SOURCE = "CLI_PROXY";
    private static final String REQUEST_METRIC =
            "admin.cli.proxy.model.discovery.requests";
    private static final String DURATION_METRIC =
            "admin.cli.proxy.model.discovery.duration";
    private static final long MAX_SAFE_JSON_INTEGER = 9_007_199_254_740_991L;

    private final CliProxyModelClient client;
    private final AiModelMapper modelMapper;
    private final PublicIdCodec publicIdCodec;
    private final CliProxyModelDiscoveryProperties properties;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public CliProxyModelDiscoveryServiceImpl(
            CliProxyModelClient client,
            AiModelMapper modelMapper,
            PublicIdCodec publicIdCodec,
            CliProxyModelDiscoveryProperties properties,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.client = Objects.requireNonNull(client);
        this.modelMapper = Objects.requireNonNull(modelMapper);
        this.publicIdCodec = Objects.requireNonNull(publicIdCodec);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
    }

    @Override
    public CliProxyModelDiscoveryResult discoverModels() {
        long startedAt = System.nanoTime();
        String outcome = "success";
        try {
            if (!properties.enabled()) {
                throw failure(
                        CliProxyModelDiscoveryErrorCode
                                .CLI_PROXY_MODEL_DISCOVERY_DISABLED,
                        "CLIProxyAPI model discovery is disabled.");
            }

            List<UpstreamModel> upstreamModels = validateAndNormalize(client.fetchModels());
            List<String> normalizedNames = upstreamModels.stream()
                    .map(UpstreamModel::normalizedId)
                    .toList();
            Map<String, AiModel> localModels = loadLocalModels(normalizedNames);
            List<CliProxyDiscoveredModel> models = upstreamModels.stream()
                    .map(model -> toResult(model, localModels.get(model.normalizedId())))
                    .toList();
            return new CliProxyModelDiscoveryResult(
                    SOURCE,
                    clock.instant(),
                    models.size(),
                    models);
        } catch (CliProxyModelDiscoveryException exception) {
            outcome = outcome(exception.code());
            throw exception;
        } catch (RuntimeException exception) {
            outcome = "internal_error";
            throw exception;
        } finally {
            recordObservation(outcome, System.nanoTime() - startedAt);
        }
    }

    private List<UpstreamModel> validateAndNormalize(JsonNode root) {
        if (root == null
                || !root.isObject()
                || !root.path("object").isTextual()
                || !"list".equals(root.path("object").textValue())
                || !root.path("data").isArray()) {
            throw invalidResponse();
        }
        JsonNode data = root.path("data");
        if (data.size() > properties.maxModels()) {
            throw invalidResponse();
        }

        Map<String, UpstreamModel> unique = new LinkedHashMap<>();
        for (JsonNode item : data) {
            if (!item.isObject()) {
                throw invalidResponse();
            }
            JsonNode idNode = item.get("id");
            if (idNode == null || !idNode.isTextual()) {
                throw invalidResponse();
            }
            String displayId = idNode.textValue().trim();
            if (displayId.isEmpty() || displayId.length() > 128) {
                throw invalidResponse();
            }
            String normalizedId = displayId.toLowerCase(Locale.ROOT);
            String owner = optionalText(item.get("owned_by"));
            Long created = optionalEpochSeconds(item.get("created"));
            unique.putIfAbsent(
                    normalizedId,
                    new UpstreamModel(displayId, normalizedId, owner, created));
        }
        return unique.values().stream()
                .sorted((left, right) -> left.normalizedId()
                        .compareTo(right.normalizedId()))
                .toList();
    }

    private Map<String, AiModel> loadLocalModels(List<String> normalizedNames) {
        if (normalizedNames.isEmpty()) {
            return Map.of();
        }
        // 上游名称已按 LOWER(BTRIM(...)) 语义规范化，数据库写入触发器也保证存储值规范化；
        // Mapper 使用直接等值集合查询，才能复用现有 model_name 唯一 B-tree 索引。
        List<AiModel> found = modelMapper.findByNormalizedModelNames(normalizedNames);
        Map<String, AiModel> result = new LinkedHashMap<>();
        for (AiModel model : found) {
            String key = normalizeLocalName(model.getModelName());
            if (result.put(key, model) != null) {
                throw new IllegalStateException(
                        "Local AI model normalization produced duplicate rows.");
            }
        }
        return Map.copyOf(result);
    }

    private CliProxyDiscoveredModel toResult(
            UpstreamModel upstream,
            AiModel local) {
        if (local == null) {
            return new CliProxyDiscoveredModel(
                    upstream.displayId(),
                    upstream.owner(),
                    upstream.createdEpochSeconds(),
                    CliProxyModelMatchStatus.UNREGISTERED,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }
        return new CliProxyDiscoveredModel(
                upstream.displayId(),
                upstream.owner(),
                upstream.createdEpochSeconds(),
                CliProxyModelMatchStatus.MATCHED,
                publicIdCodec.encode(local.getId()),
                local.getVendor(),
                local.getInputRatio(),
                local.getCachedInputRatio(),
                local.getOutputRatio(),
                local.getEnabled());
    }

    private static String optionalText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw invalidResponse();
        }
        String value = node.textValue().trim();
        if (value.length() > 128) {
            throw invalidResponse();
        }
        return value.isEmpty() ? null : value;
    }

    private static Long optionalEpochSeconds(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw invalidResponse();
        }
        long value = node.longValue();
        // 前端使用 JavaScript Number，超过安全整数上限会在 JSON 解析阶段丢失精度。
        if (value < 0 || value > MAX_SAFE_JSON_INTEGER) {
            throw invalidResponse();
        }
        return value;
    }

    private static String normalizeLocalName(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalStateException("Local AI model name is invalid.");
        }
        return modelName.trim().toLowerCase(Locale.ROOT);
    }

    private void recordObservation(String outcome, long elapsedNanos) {
        Counter.builder(REQUEST_METRIC)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
        Timer.builder(DURATION_METRIC)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .record(Duration.ofNanos(Math.max(elapsedNanos, 0)));
    }

    private static String outcome(CliProxyModelDiscoveryErrorCode code) {
        return switch (code) {
            case CLI_PROXY_MODEL_DISCOVERY_DISABLED -> "disabled";
            case CLI_PROXY_UNAVAILABLE -> "unavailable";
            case CLI_PROXY_TIMEOUT -> "timeout";
            case CLI_PROXY_AUTH_FAILED -> "auth_failed";
            case CLI_PROXY_REQUEST_FAILED -> "request_failed";
            case CLI_PROXY_RESPONSE_INVALID -> "response_invalid";
        };
    }

    private static CliProxyModelDiscoveryException invalidResponse() {
        return failure(
                CliProxyModelDiscoveryErrorCode.CLI_PROXY_RESPONSE_INVALID,
                "CLIProxyAPI model list response is invalid.");
    }

    private static CliProxyModelDiscoveryException failure(
            CliProxyModelDiscoveryErrorCode code,
            String message) {
        return new CliProxyModelDiscoveryException(code, message);
    }

    private record UpstreamModel(
            String displayId,
            String normalizedId,
            String owner,
            Long createdEpochSeconds) {
    }
}
