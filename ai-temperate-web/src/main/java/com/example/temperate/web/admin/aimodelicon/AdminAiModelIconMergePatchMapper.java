package com.example.temperate.web.admin.aimodelicon;

import com.example.temperate.service.admin.aimodel.icon.AiModelIconErrorCode;
import com.example.temperate.service.admin.aimodel.icon.AiModelIconException;
import com.example.temperate.service.admin.aimodel.icon.dto.AdminAiModelIconPatchCommand;
import com.example.temperate.service.admin.aimodel.icon.dto.AiModelIconPatchField;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Iterator;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 将模型图标 JSON Merge Patch 转换为带字段存在状态的强类型命令。
 *
 * <p>名称和外部 URL 不接受显式 NULL，描述允许 NULL 表示清空；未知字段一律拒绝。</p>
 */
@Component
public final class AdminAiModelIconMergePatchMapper {

    private static final Set<String> ALLOWED_FIELDS =
            Set.of("iconName", "description", "iconUrl");

    public AdminAiModelIconPatchCommand parse(JsonNode document) {
        if (document == null || !document.isObject() || document.isEmpty()) {
            throw invalid();
        }
        Iterator<String> names = document.fieldNames();
        while (names.hasNext()) {
            if (!ALLOWED_FIELDS.contains(names.next())) {
                throw invalid();
            }
        }
        return new AdminAiModelIconPatchCommand(
                text(document, "iconName", false),
                text(document, "description", true),
                text(document, "iconUrl", false));
    }

    private static AiModelIconPatchField<String> text(
            JsonNode document,
            String field,
            boolean nullable) {
        if (!document.has(field)) {
            return AiModelIconPatchField.absent();
        }
        JsonNode value = document.get(field);
        if (value.isNull()) {
            if (nullable) {
                return AiModelIconPatchField.of(null);
            }
            throw invalid();
        }
        if (!value.isTextual()) {
            throw invalid();
        }
        return AiModelIconPatchField.of(value.textValue());
    }

    private static AiModelIconException invalid() {
        return new AiModelIconException(
                AiModelIconErrorCode.AI_MODEL_ICON_INPUT_INVALID,
                "AI model icon merge patch is invalid.");
    }
}
