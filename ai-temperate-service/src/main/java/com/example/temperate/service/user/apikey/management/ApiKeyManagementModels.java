package com.example.temperate.service.user.apikey.management;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 该容器是来集中定义 API Key 管理服务的命令与结果契约，使 Web 层不接触数据库实体、摘要或内部 BIGINT。
 */
public final class ApiKeyManagementModels {

    private ApiKeyManagementModels() {
    }

    /** API Key 对外可编辑状态，软删除不允许通过普通更新进入。 */
    public enum Status {
        ENABLED,
        DISABLED
    }

    /** 创建命令携带客户端 UUIDv4 创建意图并要求至少一个模型，完整 Key 和摘要仍只由服务端生成。 */
    public record CreateCommand(
            UUID idempotencyKey,
            OffsetDateTime expiresAt,
            List<String> modelPublicIds) {
    }

    /** 生命周期完整替换命令不包含名称、模型授权或任何密钥版本。 */
    public record UpdateCommand(Status status, OffsetDateTime expiresAt) {
    }

    /** 模型授权完整替换命令允许空集合表达全部软撤销。 */
    public record ReplaceModelsCommand(List<String> modelPublicIds) {
    }

    /** 模型详情仅公开公共 ID、名称、厂商和当前启用状态。 */
    public record ModelGrant(
            String modelPublicId,
            String modelName,
            String vendor,
            boolean enabled) {
    }

    /** 列表项只返回不可恢复的掩码和管理生命周期元数据。 */
    public record Summary(
            String id,
            String maskedKey,
            Status status,
            OffsetDateTime expiresAt,
            boolean expired,
            OffsetDateTime lastUsedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            long rowVersion) {
    }

    /** 详情在列表元数据之外携带当前仍为 ACTIVE 的模型映射。 */
    public record Detail(Summary key, List<ModelGrant> models) {
    }

    /** 创建结果仅在这一响应中额外携带一次完整 API Key。 */
    public record Created(Detail detail, String apiKey) {
    }

    /** 稳定游标页最多多读一条决定是否还有下一页。 */
    public record Page(List<Summary> items, String nextCursor) {
    }
}
