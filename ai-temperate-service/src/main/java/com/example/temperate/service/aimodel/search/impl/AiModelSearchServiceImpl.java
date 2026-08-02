package com.example.temperate.service.aimodel.search.impl;

import com.example.temperate.service.admin.aimodel.text.AiModelTextTokenizer;
import com.example.temperate.service.aimodel.search.AiModelSearchCriteria;
import com.example.temperate.service.aimodel.search.AiModelSearchService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 使用横杠切分模型名、使用 IK 处理描述，并生成 JSONB 包含查询与安全高亮所需的确定性词元。
 *
 * <p>该实现只产生参数值，不生成 SQL；查询数组为空时返回 {@code null}，由 Mapper 彻底省略对应
 * {@code @>} 分支。持久化数组则始终输出合法 JSON，包括没有描述时的空数组；列表响应中的
 * 名称与描述命中词分别从各自已持久化数组计算。</p>
 */
@Service
public final class AiModelSearchServiceImpl implements AiModelSearchService {

    private static final int MAX_KEYWORD_LENGTH = 128;

    private final ObjectMapper objectMapper;
    private final AiModelTextTokenizer descriptionTokenizer;

    public AiModelSearchServiceImpl(
            ObjectMapper objectMapper,
            AiModelTextTokenizer descriptionTokenizer) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.descriptionTokenizer = Objects.requireNonNull(descriptionTokenizer);
    }

    @Override
    public AiModelSearchCriteria prepare(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return new AiModelSearchCriteria(null, List.of(), null, List.of(), null);
        }
        String normalized = keyword.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > MAX_KEYWORD_LENGTH) {
            throw new IllegalArgumentException("AI model search keyword is too long.");
        }
        List<String> modelNameTokens = modelNameTokens(normalized);
        List<String> descriptionTokens = normalizedDescriptionTokens(normalized);
        return new AiModelSearchCriteria(
                normalized,
                modelNameTokens,
                queryTokensJson(modelNameTokens),
                descriptionTokens,
                queryTokensJson(descriptionTokens));
    }

    @Override
    public String modelNameTokensJson(String modelName) {
        return writeTokens(modelNameTokens(modelName));
    }

    @Override
    public String descriptionTokensJson(String description) {
        return writeTokens(normalizedDescriptionTokens(description));
    }

    @Override
    public List<String> matchedModelNameTokens(
            String storedModelNameTokensJson,
            AiModelSearchCriteria criteria) {
        Objects.requireNonNull(criteria);
        return matchedTokens(
                storedModelNameTokensJson,
                criteria.modelNameTokens(),
                "model name");
    }

    @Override
    public List<String> matchedDescriptionTokens(
            String storedDescriptionTokensJson,
            AiModelSearchCriteria criteria) {
        Objects.requireNonNull(criteria);
        return matchedTokens(
                storedDescriptionTokensJson,
                criteria.descriptionTokens(),
                "description");
    }

    private String queryTokensJson(List<String> tokens) {
        return tokens.isEmpty() ? null : writeTokens(tokens);
    }

    private String writeTokens(List<String> tokens) {
        try {
            return objectMapper.writeValueAsString(tokens);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("AI model search tokens serialization failed.", exception);
        }
    }

    private List<String> matchedTokens(
            String storedTokensJson,
            List<String> queryTokens,
            String fieldName) {
        if (queryTokens.isEmpty()) {
            return List.of();
        }
        // 两个 GIN 分支通过 OR 筛选；高亮仍须逐字段确认，不能把另一字段的命中词带入当前文本。
        Set<String> storedTokens = readStoredTokens(storedTokensJson, fieldName);
        return storedTokens.containsAll(queryTokens) ? queryTokens : List.of();
    }

    private Set<String> readStoredTokens(String tokensJson, String fieldName) {
        try {
            JsonNode root = objectMapper.readTree(tokensJson);
            if (root == null || !root.isArray()) {
                throw invalidTokens(fieldName, null);
            }
            LinkedHashSet<String> tokens = new LinkedHashSet<>();
            for (JsonNode tokenNode : root) {
                if (!tokenNode.isTextual() || tokenNode.textValue().isBlank()) {
                    throw invalidTokens(fieldName, null);
                }
                tokens.add(tokenNode.textValue().trim().toLowerCase(Locale.ROOT));
            }
            return Set.copyOf(tokens);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw invalidTokens(fieldName, exception);
        }
    }

    private static IllegalStateException invalidTokens(String fieldName, Exception cause) {
        return new IllegalStateException(
                "AI model " + fieldName + " tokens JSON is invalid.",
                cause);
    }

    private static List<String> modelNameTokens(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String segment : modelName.split("-")) {
            String normalized = segment.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty()) {
                tokens.add(normalized);
            }
        }
        return List.copyOf(tokens);
    }

    private List<String> normalizedDescriptionTokens(String description) {
        List<String> rawTokens = descriptionTokenizer.tokenize(description);
        if (rawTokens.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String token : rawTokens) {
            if (token != null && !token.isBlank()) {
                normalized.add(token.trim().toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(normalized);
    }
}
