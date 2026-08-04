package com.example.temperate.web.user.aimodel.controller;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.service.user.aimodel.dto.UserAiModelPageResult;
import com.example.temperate.service.user.aimodel.dto.UserAiModelResult;
import com.example.temperate.service.user.aimodel.service.UserAiModelCatalogService;
import com.example.temperate.web.aimodel.AiModelPublicId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 为已通过 RT-first 会话认证的普通用户提供已启用 AI 模型目录和详情接口。
 *
 * <p>该 Controller 不接受内部模型 ID，不提供启停或编辑能力，也不直接访问 PostgreSQL 或 Redis；
 * 模型可用性由 Service 的已启用快照或数据库 {@code is_enabled = TRUE} 边界控制。</p>
 */
@Validated
@RestController
@RequestMapping("/api/ai-models")
@Tag(
        name = "用户-AI 模型目录",
        description = "供已通过 RT-first 会话认证的 H5 和 Android 普通用户分页查看当前已启用模型及计费倍率。"
                + "接口只读，不暴露数据库 BIGINT，不负责模型调用、额度预扣、最终结算或管理员配置。")
public class UserAiModelController {

    private final UserAiModelCatalogService catalogService;

    public UserAiModelController(UserAiModelCatalogService catalogService) {
        this.catalogService = Objects.requireNonNull(catalogService);
    }

    @GetMapping
    @Operation(
            summary = "分页读取已启用 AI 模型",
            description = "无关键词时读取已启用模型快照；搜索时按完整名称词元、描述词元或完整厂商名"
                    + "查询 PostgreSQL，并按模型内部创建顺序稳定排列。响应禁止浏览器和共享代理缓存。")
    public ResponseEntity<UserAiModelPageResult> list(
            @RequestParam(defaultValue = "1")
            @Min(1)
            @Parameter(
                    description = "从一开始的模型目录页码",
                    schema = @Schema(
                            type = "integer",
                            minimum = "1",
                            defaultValue = "1"))
            int pageNum,
            @RequestParam(defaultValue = "20")
            @Min(1)
            @Max(50)
            @Parameter(
                    description = "每页模型数量，最大五十条",
                    schema = @Schema(
                            type = "integer",
                            minimum = "1",
                            maximum = "50",
                            defaultValue = "20"))
            int pageSize,
            @RequestParam(required = false)
            @Size(max = 128)
            @Parameter(
                    description = "按模型名称横杠词元或描述 IK 词元执行完整词元搜索，"
                            + "也可按完整厂商名执行忽略大小写的精确搜索；省略时读取已启用模型快照",
                    schema = @Schema(type = "string", maxLength = 128))
            String keyword) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(catalogService.list(pageNum, pageSize, keyword));
    }

    @GetMapping("/{modelPublicId}")
    @Operation(
            summary = "读取单个已启用 AI 模型详情",
            description = "禁用或不存在的模型统一返回 404；公共 ID 仅用于路由，仍受 RT-first 会话认证保护。")
    public ResponseEntity<UserAiModelResult> detail(
            @PathVariable
            @Parameter(
                    description = "模型的 11 位 Base64URL 公共 ID",
                    schema = @Schema(
                            type = "string",
                            minLength = PublicIdCodec.ENCODED_LENGTH,
                            maxLength = PublicIdCodec.ENCODED_LENGTH,
                            pattern = PublicIdCodec.ENCODED_PATTERN,
                            example = "AAABi0VWeJ8"))
            AiModelPublicId modelPublicId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(catalogService.detail(modelPublicId.value()));
    }
}
