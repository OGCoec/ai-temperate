package com.example.temperate.web.user.apikey;

import com.example.temperate.common.codec.id.HybridUlidCodec;
import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.Created;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.Detail;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.ModelGrant;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.Page;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.Summary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 该响应容器是来把服务层 API Key 结果转换为扁平 HTTP JSON，并把完整 Key 严格限制在创建响应类型中。
 */
public final class ApiKeyManagementResponse {

    private ApiKeyManagementResponse() {
    }

    public static Key from(Detail detail) {
        Summary value = detail.key();
        return new Key(
                value.id(),
                value.maskedKey(),
                value.status().name(),
                value.expiresAt(),
                value.expired(),
                value.lastUsedAt(),
                value.createdAt(),
                value.updatedAt(),
                value.rowVersion(),
                detail.models().stream().map(ApiKeyManagementResponse::from).toList());
    }

    public static CreatedKey from(Created created) {
        Key value = from(created.detail());
        return new CreatedKey(
                value.id(),
                value.maskedKey(),
                value.status(),
                value.expiresAt(),
                value.expired(),
                value.lastUsedAt(),
                value.createdAt(),
                value.updatedAt(),
                value.rowVersion(),
                value.models(),
                created.apiKey());
    }

    public static KeyPage from(Page page) {
        return new KeyPage(
                page.items().stream().map(ApiKeyManagementResponse::from).toList(),
                page.nextCursor());
    }

    private static KeySummary from(Summary value) {
        return new KeySummary(
                value.id(),
                value.maskedKey(),
                value.status().name(),
                value.expiresAt(),
                value.expired(),
                value.lastUsedAt(),
                value.createdAt(),
                value.updatedAt(),
                value.rowVersion());
    }

    private static Model from(ModelGrant value) {
        return new Model(
                value.modelPublicId(),
                value.modelName(),
                value.vendor(),
                value.enabled());
    }

    /** 普通详情不可能携带完整 Key。 */
    public record Key(
            @Schema(
                    minLength = HybridUlidCodec.ENCODED_LENGTH,
                    maxLength = HybridUlidCodec.ENCODED_LENGTH,
                    pattern = HybridUlidCodec.ENCODED_PATTERN,
                    example = "01KC938NKR041061050R3GG28A")
            String id,
            String maskedKey,
            String status,
            OffsetDateTime expiresAt,
            boolean expired,
            OffsetDateTime lastUsedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            long rowVersion,
            List<Model> models) {
    }

    /** 只有创建 201 的响应类型包含一次性 apiKey 字段。 */
    public record CreatedKey(
            @Schema(
                    minLength = HybridUlidCodec.ENCODED_LENGTH,
                    maxLength = HybridUlidCodec.ENCODED_LENGTH,
                    pattern = HybridUlidCodec.ENCODED_PATTERN,
                    example = "01KC938NKR041061050R3GG28A")
            String id,
            String maskedKey,
            String status,
            OffsetDateTime expiresAt,
            boolean expired,
            OffsetDateTime lastUsedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            long rowVersion,
            List<Model> models,
            @Schema(
                    description = "仅在创建响应返回一次的完整 API Key。",
                    example = "sk-***",
                    accessMode = Schema.AccessMode.READ_ONLY)
            String apiKey) {
    }

    /** 列表项不加载模型授权，保持游标查询为一次有界 SQL。 */
    public record KeySummary(
            @Schema(
                    minLength = HybridUlidCodec.ENCODED_LENGTH,
                    maxLength = HybridUlidCodec.ENCODED_LENGTH,
                    pattern = HybridUlidCodec.ENCODED_PATTERN,
                    example = "01KC938NKR041061050R3GG28A")
            String id,
            String maskedKey,
            String status,
            OffsetDateTime expiresAt,
            boolean expired,
            OffsetDateTime lastUsedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            long rowVersion) {
    }

    /** 模型公共详情不暴露数据库 ID。 */
    public record Model(
            @Schema(
                    minLength = PublicIdCodec.ENCODED_LENGTH,
                    maxLength = PublicIdCodec.ENCODED_LENGTH,
                    pattern = PublicIdCodec.ENCODED_PATTERN,
                    example = "AAAAAAAAABc")
            String modelPublicId,
            String modelName,
            String vendor,
            boolean enabled) {
    }

    /** 游标为空表示已经到达列表末尾。 */
    public record KeyPage(
            List<KeySummary> items,
            @Schema(
                    nullable = true,
                    minLength = 38,
                    maxLength = 38,
                    pattern = "^[A-Za-z0-9_-]{38}$")
            String nextCursor) {
    }
}
