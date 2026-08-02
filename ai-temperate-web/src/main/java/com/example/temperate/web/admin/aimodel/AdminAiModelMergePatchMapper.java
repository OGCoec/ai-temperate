package com.example.temperate.web.admin.aimodel;

import com.example.temperate.service.admin.aimodel.dto.AdminAiModelPatchCommand;
import com.example.temperate.service.admin.aimodel.dto.AiModelPatchField;
import com.example.temperate.service.admin.aimodel.exception.AdminAiModelErrorCode;
import com.example.temperate.service.admin.aimodel.exception.AdminAiModelException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 将 AI 模型 JSON Merge Patch 转换为带字段存在标记和原始 Token 的强类型命令。
 *
 * <p>这里只接受固定可编辑字段，拒绝未知字段、不可编辑字段和错误 JSON 类型，防止原始 JSON
 * 或未校验 Map 穿透到 Service 和 Mapper；K 单位在此边界精确乘以 1000。</p>
 */
@Component
public final class AdminAiModelMergePatchMapper {

    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "modelName",
            "description",
            "iconPublicId",
            "tags",
            "vendor",
            "inputRatio",
            "cachedInputRatio",
            "outputRatio",
            "contextWindowK",
            "maxOutputK",
            "capabilities");
    private static final long TOKENS_PER_K = 1000L;

    private final ObjectMapper objectMapper;

    public AdminAiModelMergePatchMapper(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public AdminAiModelPatchCommand parse(JsonNode document) {
        if (document == null || !document.isObject() || document.size() == 0) {
            throw invalidPatch();
        }
        Iterator<String> fields = document.fieldNames();
        while (fields.hasNext()) {
            if (!ALLOWED_FIELDS.contains(fields.next())) {
                throw invalidPatch();
            }
        }

        return new AdminAiModelPatchCommand(
                textField(document, "modelName", false),
                textField(document, "description", true),
                textField(document, "iconPublicId", true),
                stringListField(document, "tags"),
                textField(document, "vendor", false),
                decimalField(document, "inputRatio"),
                decimalField(document, "cachedInputRatio"),
                decimalField(document, "outputRatio"),
                tokenLimitField(document, "contextWindowK"),
                tokenLimitField(document, "maxOutputK"),
                stringListField(document, "capabilities"));
    }

    private static AiModelPatchField<String> textField(
            JsonNode document,
            String field,
            boolean nullable) {
        if (!document.has(field)) {
            return AiModelPatchField.absent();
        }
        JsonNode value = document.get(field);
        if (value.isNull()) {
            if (nullable) {
                return AiModelPatchField.of(null);
            }
            throw invalidPatch();
        }
        if (!value.isTextual()) {
            throw invalidPatch();
        }
        return AiModelPatchField.of(value.textValue());
    }

    private AiModelPatchField<BigDecimal> decimalField(
            JsonNode document,
            String field) {
        if (!document.has(field)) {
            return AiModelPatchField.absent();
        }
        JsonNode value = document.get(field);
        if (!value.isNumber()) {
            throw invalidPatch();
        }
        return AiModelPatchField.of(objectMapper.convertValue(value, BigDecimal.class));
    }

    private static AiModelPatchField<List<String>> stringListField(
            JsonNode document,
            String field) {
        if (!document.has(field)) {
            return AiModelPatchField.absent();
        }
        JsonNode value = document.get(field);
        if (!value.isArray()) {
            throw invalidPatch();
        }
        List<String> values = new ArrayList<>(value.size());
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                throw invalidPatch();
            }
            values.add(item.textValue());
        }
        return AiModelPatchField.of(List.copyOf(values));
    }

    private static AiModelPatchField<Long> tokenLimitField(
            JsonNode document,
            String field) {
        if (!document.has(field)) {
            return AiModelPatchField.absent();
        }
        JsonNode value = document.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw invalidPatch();
        }
        if (!value.canConvertToInt() || value.intValue() < 1) {
            throw invalidTokenLimit();
        }
        return AiModelPatchField.of(
                Math.multiplyExact(value.intValue(), TOKENS_PER_K));
    }

    private static AdminAiModelException invalidPatch() {
        return new AdminAiModelException(
                AdminAiModelErrorCode.AI_MODEL_PATCH_INVALID,
                "AI model merge patch is invalid.");
    }

    private static AdminAiModelException invalidTokenLimit() {
        return new AdminAiModelException(
                AdminAiModelErrorCode.AI_MODEL_TOKEN_LIMIT_INVALID,
                "AI model token limit is invalid.");
    }
}
