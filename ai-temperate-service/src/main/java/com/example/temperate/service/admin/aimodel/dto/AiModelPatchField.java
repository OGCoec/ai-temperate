package com.example.temperate.service.admin.aimodel.dto;

/**
 * 表示 JSON Merge Patch 中一个字段是否出现以及它的显式值。
 *
 * <p>字段缺省与字段值为 {@code null} 具有不同业务含义，因此不能使用普通可空 DTO 字段合并。</p>
 */
public record AiModelPatchField<T>(boolean present, T value) {

    public static <T> AiModelPatchField<T> absent() {
        return new AiModelPatchField<>(false, null);
    }

    public static <T> AiModelPatchField<T> of(T value) {
        return new AiModelPatchField<>(true, value);
    }
}
