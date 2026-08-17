package com.example.temperate.service.user.apichat.diagnostic;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 该记录是来承载一次 Chat 校验拒绝的有界结构元数据，所有正文、字段值、工具名称和 Schema 内容都被排除在模型之外。
 */
public record ApiChatValidationRejection(
        ApiChatValidationFailureRule rule,
        String parameter,
        int messageIndex,
        int toolIndex,
        int partIndex,
        ValueType actualNodeType,
        int collectionSize,
        int nameLength,
        int descriptionLength,
        long descriptionBytes,
        long toolDefinitionsBytes,
        int schemaDepth,
        int schemaNodeCount) {

    private static final int NOT_APPLICABLE = -1;

    public static ApiChatValidationRejection of(
            ApiChatValidationFailureRule rule,
            String parameter) {
        return new ApiChatValidationRejection(
                rule,
                parameter,
                NOT_APPLICABLE,
                NOT_APPLICABLE,
                NOT_APPLICABLE,
                ValueType.NOT_APPLICABLE,
                NOT_APPLICABLE,
                NOT_APPLICABLE,
                NOT_APPLICABLE,
                NOT_APPLICABLE,
                NOT_APPLICABLE,
                NOT_APPLICABLE,
                NOT_APPLICABLE);
    }

    public ApiChatValidationRejection withMessageIndex(int value) {
        return copy(value, toolIndex, partIndex, actualNodeType, collectionSize,
                nameLength, descriptionLength, descriptionBytes, toolDefinitionsBytes,
                schemaDepth, schemaNodeCount);
    }

    public ApiChatValidationRejection withToolIndex(int value) {
        return copy(messageIndex, value, partIndex, actualNodeType, collectionSize,
                nameLength, descriptionLength, descriptionBytes, toolDefinitionsBytes,
                schemaDepth, schemaNodeCount);
    }

    public ApiChatValidationRejection withPartIndex(int value) {
        return copy(messageIndex, toolIndex, value, actualNodeType, collectionSize,
                nameLength, descriptionLength, descriptionBytes, toolDefinitionsBytes,
                schemaDepth, schemaNodeCount);
    }

    public ApiChatValidationRejection withActualNode(JsonNode value) {
        return copy(messageIndex, toolIndex, partIndex, ValueType.from(value), collectionSize,
                nameLength, descriptionLength, descriptionBytes, toolDefinitionsBytes,
                schemaDepth, schemaNodeCount);
    }

    public ApiChatValidationRejection withCollectionSize(int value) {
        return copy(messageIndex, toolIndex, partIndex, actualNodeType, value,
                nameLength, descriptionLength, descriptionBytes, toolDefinitionsBytes,
                schemaDepth, schemaNodeCount);
    }

    public ApiChatValidationRejection withNameLength(int value) {
        return copy(messageIndex, toolIndex, partIndex, actualNodeType, collectionSize,
                Math.max(NOT_APPLICABLE, value), descriptionLength,
                descriptionBytes, toolDefinitionsBytes,
                schemaDepth, schemaNodeCount);
    }

    public ApiChatValidationRejection withDescriptionMetrics(int length, long bytes) {
        return copy(messageIndex, toolIndex, partIndex, actualNodeType, collectionSize,
                nameLength, Math.max(NOT_APPLICABLE, length),
                Math.max(NOT_APPLICABLE, bytes), toolDefinitionsBytes,
                schemaDepth, schemaNodeCount);
    }

    public ApiChatValidationRejection withToolDefinitionsBytes(long value) {
        return copy(messageIndex, toolIndex, partIndex, actualNodeType, collectionSize,
                nameLength, descriptionLength, descriptionBytes,
                Math.max(NOT_APPLICABLE, value), schemaDepth, schemaNodeCount);
    }

    public ApiChatValidationRejection withSchema(int depth, int nodes) {
        return copy(messageIndex, toolIndex, partIndex, actualNodeType, collectionSize,
                nameLength, descriptionLength, descriptionBytes, toolDefinitionsBytes,
                Math.max(NOT_APPLICABLE, depth), Math.max(NOT_APPLICABLE, nodes));
    }

    private ApiChatValidationRejection copy(
            int resolvedMessageIndex,
            int resolvedToolIndex,
            int resolvedPartIndex,
            ValueType resolvedNodeType,
            int resolvedCollectionSize,
            int resolvedNameLength,
            int resolvedDescriptionLength,
            long resolvedDescriptionBytes,
            long resolvedToolDefinitionsBytes,
            int resolvedSchemaDepth,
            int resolvedSchemaNodeCount) {
        return new ApiChatValidationRejection(
                rule,
                parameter,
                resolvedMessageIndex,
                resolvedToolIndex,
                resolvedPartIndex,
                resolvedNodeType,
                resolvedCollectionSize,
                resolvedNameLength,
                resolvedDescriptionLength,
                resolvedDescriptionBytes,
                resolvedToolDefinitionsBytes,
                resolvedSchemaDepth,
                resolvedSchemaNodeCount);
    }

    /**
     * 该枚举是来把 Jackson 节点压缩为固定类型，避免在日志中输出节点值或由客户端控制的类名。
     */
    public enum ValueType {
        NOT_APPLICABLE,
        ABSENT,
        NULL,
        OBJECT,
        ARRAY,
        STRING,
        BOOLEAN,
        INTEGER,
        NUMBER,
        BINARY,
        POJO,
        MISSING,
        UNKNOWN;

        private static ValueType from(JsonNode node) {
            if (node == null) {
                return ABSENT;
            }
            return switch (node.getNodeType()) {
                case NULL -> NULL;
                case OBJECT -> OBJECT;
                case ARRAY -> ARRAY;
                case STRING -> STRING;
                case BOOLEAN -> BOOLEAN;
                case NUMBER -> node.isIntegralNumber() ? INTEGER : NUMBER;
                case BINARY -> BINARY;
                case POJO -> POJO;
                case MISSING -> MISSING;
            };
        }
    }
}
