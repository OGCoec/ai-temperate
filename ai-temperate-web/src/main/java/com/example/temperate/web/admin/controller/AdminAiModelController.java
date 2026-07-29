package com.example.temperate.web.admin.controller;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.service.admin.aimodel.dto.AdminAiModelBatchStatusResult;
import com.example.temperate.service.admin.aimodel.dto.AdminAiModelCreateCommand;
import com.example.temperate.service.admin.aimodel.dto.AdminAiModelDetailResult;
import com.example.temperate.service.admin.aimodel.dto.AdminAiModelPageResult;
import com.example.temperate.service.admin.aimodel.dto.AdminAiModelResult;
import com.example.temperate.service.admin.aimodel.domain.AiModelSortDirection;
import com.example.temperate.service.admin.aimodel.domain.AiModelSortPriority;
import com.example.temperate.service.admin.aimodel.exception.AdminAiModelErrorCode;
import com.example.temperate.service.admin.aimodel.exception.AdminAiModelException;
import com.example.temperate.service.admin.aimodel.service.AdminAiModelService;
import com.example.temperate.web.admin.aimodel.AdminAiModelMergePatchMapper;
import com.example.temperate.web.admin.aimodel.AiModelPublicId;
import com.example.temperate.web.admin.aimodel.AiModelVersionTag;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供受管理员会话、设备校验和 CSRF 保护的 AI 模型查询、新增、字段编辑与启停接口。
 *
 * <p>该 Controller 只依赖 Service 接口，不提供物理删除，也不直接读写数据库、Redis 或
 * 解码内部 BIGINT。</p>
 */
@Validated
@RestController
@RequestMapping("/api/admin/ai-models")
@Tag(
        name = "管理员-AI 模型",
        description = "供已登录管理员查询、新增、并发安全编辑并单个或批量启停 AI 模型；接口受管理员会话、设备与 CSRF 安全边界保护，不提供模型物理删除。")
public class AdminAiModelController {

    private final AdminAiModelService aiModelService;
    private final AdminAiModelMergePatchMapper mergePatchMapper;

    public AdminAiModelController(
            AdminAiModelService aiModelService,
            AdminAiModelMergePatchMapper mergePatchMapper) {
        this.aiModelService = Objects.requireNonNull(aiModelService);
        this.mergePatchMapper = Objects.requireNonNull(mergePatchMapper);
    }

    @GetMapping
    @Operation(summary = "分页查询 AI 模型及能力")
    public AdminAiModelPageResult list(
            @RequestParam(defaultValue = "1") @Min(1)
            @Parameter(
                    description = "从一开始的管理员列表页码",
                    schema = @Schema(
                            type = "integer",
                            minimum = "1",
                            defaultValue = "1"))
            int pageNum,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100)
            @Parameter(
                    description = "每页模型数量，最大一百条",
                    schema = @Schema(
                            type = "integer",
                            minimum = "1",
                            maximum = "100",
                            defaultValue = "50"))
            int pageSize,
            @RequestParam(required = false) @Size(max = 128)
            @Parameter(
                    description = "按模型名称或厂商执行不区分大小写的前缀搜索；百分号和下划线按普通字符处理",
                    schema = @Schema(type = "string", maxLength = 128))
            String keyword,
            @RequestParam(required = false)
            @Parameter(
                    description = "按启用状态筛选；省略时返回全部状态",
                    schema = @Schema(type = "boolean"))
            Boolean enabled,
            @RequestParam(defaultValue = "INPUT_FIRST")
            @Parameter(
                    description = "倍率排序优先级",
                    schema = @Schema(
                            type = "string",
                            allowableValues = {"INPUT_FIRST", "OUTPUT_FIRST"},
                            defaultValue = "INPUT_FIRST"))
            AiModelSortPriority sortPriority,
            @RequestParam(defaultValue = "ASC")
            @Parameter(
                    description = "全部排序字段共用的方向",
                    schema = @Schema(
                            type = "string",
                            allowableValues = {"ASC", "DESC"},
                            defaultValue = "ASC"))
            AiModelSortDirection direction,
            HttpServletResponse response) {
        noStore(response);
        return aiModelService.list(
                pageNum,
                pageSize,
                keyword,
                enabled,
                sortPriority,
                direction);
    }

    @GetMapping("/{publicId}")
    @Operation(summary = "查看单个 AI 模型详情与能力")
    public ResponseEntity<AdminAiModelDetailResult> detail(
            @PathVariable
            @Parameter(
                    description = "模型的 11 位 Base64URL 公共 ID",
                    schema = @Schema(
                            type = "string",
                            minLength = PublicIdCodec.ENCODED_LENGTH,
                            maxLength = PublicIdCodec.ENCODED_LENGTH,
                            pattern = PublicIdCodec.ENCODED_PATTERN,
                            example = "AAABi0VWeJ8"))
            AiModelPublicId publicId,
            HttpServletResponse response) {
        AdminAiModelDetailResult detail = aiModelService.detail(publicId.value());
        noStore(response);
        return ResponseEntity.ok()
                .eTag(AiModelVersionTag.format(detail.rowVersion()))
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(detail);
    }

    @PatchMapping(
            value = "/{publicId}",
            consumes = "application/merge-patch+json")
    @Operation(summary = "按强 ETag 并发安全编辑单个 AI 模型字段与能力")
    public ResponseEntity<AdminAiModelDetailResult> patch(
            @PathVariable
            @Parameter(
                    description = "模型的 11 位 Base64URL 公共 ID",
                    schema = @Schema(
                            type = "string",
                            minLength = PublicIdCodec.ENCODED_LENGTH,
                            maxLength = PublicIdCodec.ENCODED_LENGTH,
                            pattern = PublicIdCodec.ENCODED_PATTERN,
                            example = "AAABi0VWeJ8"))
            AiModelPublicId publicId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false)
            String ifMatch,
            @RequestBody JsonNode patchDocument) {
        long expectedVersion = AiModelVersionTag.parseRequired(ifMatch);
        AdminAiModelDetailResult updated = aiModelService.patch(
                publicId.value(),
                expectedVersion,
                mergePatchMapper.parse(patchDocument));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .eTag(AiModelVersionTag.format(updated.rowVersion()))
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(updated);
    }

    @PostMapping
    @Operation(summary = "新增 AI 模型及能力")
    public ResponseEntity<AdminAiModelResult> create(
            @Valid @RequestBody CreateRequest request) {
        AdminAiModelResult created = aiModelService.create(request.toCommand());
        return ResponseEntity.created(URI.create("/api/admin/ai-models/" + created.publicId()))
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(created);
    }

    @PatchMapping("/{publicId}/status")
    @Operation(summary = "启用或禁用单个 AI 模型")
    public AdminAiModelResult setStatus(
            @PathVariable
            @Parameter(
                    description = "模型的 11 位 Base64URL 公共 ID",
                    schema = @Schema(
                            type = "string",
                            minLength = PublicIdCodec.ENCODED_LENGTH,
                            maxLength = PublicIdCodec.ENCODED_LENGTH,
                            pattern = PublicIdCodec.ENCODED_PATTERN,
                            example = "AAABi0VWeJ8"))
            AiModelPublicId publicId,
            @Valid @RequestBody StatusRequest request,
            HttpServletResponse response) {
        noStore(response);
        return aiModelService.setEnabled(publicId.value(), request.enabled());
    }

    @PostMapping("/status/batch")
    @Operation(summary = "一次批量启用或禁用多个 AI 模型")
    public AdminAiModelBatchStatusResult setStatusBatch(
            @Valid @RequestBody BatchStatusRequest request,
            HttpServletResponse response) {
        noStore(response);
        return aiModelService.setEnabledBatch(request.publicIds(), request.enabled());
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", CacheControl.noStore().cachePrivate().getHeaderValue());
    }

    /**
     * 定义新增模型的完整且显式请求，启用状态和能力集合均不得省略。
     */
    public record CreateRequest(
            @NotBlank @Size(max = 128) String modelName,
            @Size(max = 4000) String description,
            @Pattern(regexp = PublicIdCodec.ENCODED_PATTERN) String iconPublicId,
            @NotNull @Size(max = 20)
            List<@NotBlank @Size(max = 64) String> tags,
            @NotBlank @Size(max = 128) String vendor,
            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 8)
            BigDecimal inputRatio,
            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 8)
            BigDecimal cachedInputRatio,
            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 8)
            BigDecimal outputRatio,
            @NotNull Boolean enabled,
            @ArraySchema(schema = @Schema(
                    type = "string",
                    description = "模型支持的单项 API 能力大类代码",
                    allowableValues = {
                            "CHAT_COMPLETIONS",
                            "RESPONSES",
                            "IMAGE",
                            "VIDEO",
                            "AUDIO"
                    }))
            @NotEmpty @Size(max = 5)
            List<@NotBlank @Size(max = 64) String> capabilities) {

        AdminAiModelCreateCommand toCommand() {
            return new AdminAiModelCreateCommand(
                    modelName,
                    description,
                    iconPublicId,
                    tags,
                    vendor,
                    inputRatio,
                    cachedInputRatio,
                    outputRatio,
                    enabled,
                    capabilities);
        }

        /**
         * 拒绝旧 {@code icon} URL 字段及其他未知属性，避免客户端静默以为旧图标写入仍然生效。
         */
        @JsonAnySetter
        public void rejectUnknownField(String name, JsonNode value) {
            throw new AdminAiModelException(
                    AdminAiModelErrorCode.AI_MODEL_INPUT_INVALID,
                    "AI model create request contains an unsupported field.");
        }
    }

    /**
     * 定义单个模型的显式目标状态；缺失字段不能回落到数据库默认值。
     */
    public record StatusRequest(@NotNull Boolean enabled) {
    }

    /**
     * 定义最多一百个公共 ID 的统一启停请求，重复和不存在 ID 由 Service 受控拒绝。
     */
    public record BatchStatusRequest(
            @NotEmpty @Size(max = 100)
            @Schema(description = "一至一百个 11 位 Base64URL 模型公共 ID")
            List<@NotBlank @Pattern(regexp = PublicIdCodec.ENCODED_PATTERN) String> publicIds,
            @NotNull Boolean enabled) {
    }
}
