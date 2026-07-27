package com.example.temperate.service.admin.aimodel.icon.dto;

/**
 * 区分 JSON Merge Patch 字段未出现与显式提交值的状态。
 */
public record AiModelIconPatchField<T>(
        boolean present,
        T value) {

    public static <T> AiModelIconPatchField<T> absent() {
        return new AiModelIconPatchField<>(false, null);
    }

    public static <T> AiModelIconPatchField<T> of(T value) {
        return new AiModelIconPatchField<>(true, value);
    }
}
