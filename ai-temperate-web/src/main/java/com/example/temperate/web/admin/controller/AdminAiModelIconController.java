package com.example.temperate.web.admin.controller;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.service.admin.aimodel.icon.AiModelIconErrorCode;
import com.example.temperate.service.admin.aimodel.icon.AiModelIconException;
import com.example.temperate.service.admin.aimodel.icon.dto.AdminAiModelIconRemoteCreateCommand;
import com.example.temperate.service.admin.aimodel.icon.dto.AdminAiModelIconUploadCommand;
import com.example.temperate.service.admin.aimodel.icon.dto.AiModelIconPageResult;
import com.example.temperate.service.admin.aimodel.icon.dto.AiModelIconResult;
import com.example.temperate.service.admin.aimodel.icon.service.AdminAiModelIconService;
import com.example.temperate.web.admin.aimodelicon.AdminAiModelIconMergePatchMapper;
import com.example.temperate.web.admin.aimodelicon.AiModelIconPublicId;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 提供受管理员认证、设备校验和 CSRF 保护的模型图标资源管理接口。
 *
 * <p>Controller 只负责 HTTP 请求形态、大小预检和响应状态；图片验证、OSS 操作、逻辑引用与事务
 * 均由 Service 接口处理，不暴露内部数据库 ID 或 Object Key。</p>
 */
@Validated
@RestController
@RequestMapping("/api/admin/ai-model-icons")
@Tag(
        name = "管理员-模型图标",
        description = "供已登录管理员分页查询、登记外部图片、上传或替换 OSS 图片并删除未被引用的模型图标；"
                + "接口受管理员会话、设备与 CSRF 边界保护，不负责 AI 模型字段和启停管理。")
public class AdminAiModelIconController {

    private final AdminAiModelIconService iconService;
    private final AdminAiModelIconMergePatchMapper patchMapper;

    public AdminAiModelIconController(
            AdminAiModelIconService iconService,
            AdminAiModelIconMergePatchMapper patchMapper) {
        this.iconService = Objects.requireNonNull(iconService);
        this.patchMapper = Objects.requireNonNull(patchMapper);
    }

    @GetMapping
    @Operation(summary = "分页查询模型图标资源")
    public AiModelIconPageResult list(
            @RequestParam(defaultValue = "1") @Min(1)
            @Parameter(
                    description = "从一开始的图标资源页码",
                    schema = @Schema(type = "integer", minimum = "1", defaultValue = "1"))
            int pageNum,
            @RequestParam(defaultValue = "100") @Min(1) @Max(100)
            @Parameter(
                    description = "每页图标资源数量，最大一百条",
                    schema = @Schema(
                            type = "integer",
                            minimum = "1",
                            maximum = "100",
                            defaultValue = "100"))
            int pageSize,
            HttpServletResponse response) {
        noStore(response);
        return iconService.list(pageNum, pageSize);
    }

    @GetMapping("/{publicId}")
    @Operation(summary = "查看单个模型图标资源")
    public AiModelIconResult detail(
            @PathVariable @Parameter(
                    description = "模型图标的 11 位 Base64URL 公共 ID",
                    schema = @Schema(
                            minLength = PublicIdCodec.ENCODED_LENGTH,
                            maxLength = PublicIdCodec.ENCODED_LENGTH,
                            pattern = PublicIdCodec.ENCODED_PATTERN,
                            example = "AAAAAAAAAAE"))
            AiModelIconPublicId publicId,
            HttpServletResponse response) {
        noStore(response);
        return iconService.detail(publicId.value());
    }

    @PostMapping("/remote")
    @Operation(summary = "验证并登记外部 HTTPS 模型图标")
    public ResponseEntity<AiModelIconResult> createRemote(
            @Valid @RequestBody RemoteCreateRequest request) {
        AiModelIconResult created = iconService.createRemote(new AdminAiModelIconRemoteCreateCommand(
                request.iconName(),
                request.iconUrl(),
                request.description()));
        return created(created);
    }

    @PostMapping(
            value = "/upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传本地图片并创建模型图标")
    public ResponseEntity<AiModelIconResult> createUpload(
            @RequestParam @NotBlank @Size(max = 128)
            @Parameter(description = "大小写不敏感唯一的图标显示名称")
            String iconName,
            @RequestParam(required = false) @Size(max = 512)
            @Parameter(description = "图标适用厂商或模型系列的可选说明")
            String description,
            @RequestPart("file")
            @Parameter(description = "最大 2 MiB 的 PNG、JPEG/JPG、WebP、GIF、ICO、AVIF 或安全 SVG 本地图片")
            MultipartFile file) {
        AiModelIconResult created = iconService.createUpload(new AdminAiModelIconUploadCommand(
                iconName,
                description,
                readFile(file),
                file.getContentType()));
        return created(created);
    }

    @PatchMapping(
            value = "/{publicId}",
            consumes = "application/merge-patch+json")
    @Operation(summary = "修改模型图标名称、描述或外部 URL")
    public AiModelIconResult patch(
            @PathVariable @Parameter(
                    description = "模型图标的 11 位 Base64URL 公共 ID",
                    schema = @Schema(
                            minLength = PublicIdCodec.ENCODED_LENGTH,
                            maxLength = PublicIdCodec.ENCODED_LENGTH,
                            pattern = PublicIdCodec.ENCODED_PATTERN,
                            example = "AAAAAAAAAAE"))
            AiModelIconPublicId publicId,
            @RequestBody JsonNode document,
            HttpServletResponse response) {
        noStore(response);
        return iconService.patch(publicId.value(), patchMapper.parse(document));
    }

    @RequestMapping(
            value = "/{publicId}/file",
            method = {RequestMethod.PUT, RequestMethod.POST},
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "使用本地图片替换模型图标文件")
    public AiModelIconResult replaceFile(
            @PathVariable @Parameter(
                    description = "模型图标的 11 位 Base64URL 公共 ID",
                    schema = @Schema(
                            minLength = PublicIdCodec.ENCODED_LENGTH,
                            maxLength = PublicIdCodec.ENCODED_LENGTH,
                            pattern = PublicIdCodec.ENCODED_PATTERN,
                            example = "AAAAAAAAAAE"))
            AiModelIconPublicId publicId,
            @RequestPart("file")
            @Parameter(description = "最大 2 MiB 的 PNG、JPEG/JPG、WebP、GIF、ICO、AVIF 或安全 SVG 替换图片")
            MultipartFile file,
            HttpServletResponse response) {
        noStore(response);
        return iconService.replaceFile(
                publicId.value(),
                readFile(file),
                file.getContentType());
    }

    @DeleteMapping("/{publicId}")
    @Operation(summary = "删除未被 AI 模型引用的图标资源")
    public ResponseEntity<Void> delete(
            @PathVariable @Parameter(
                    description = "模型图标的 11 位 Base64URL 公共 ID",
                    schema = @Schema(
                            minLength = PublicIdCodec.ENCODED_LENGTH,
                            maxLength = PublicIdCodec.ENCODED_LENGTH,
                            pattern = PublicIdCodec.ENCODED_PATTERN,
                            example = "AAAAAAAAAAE"))
            AiModelIconPublicId publicId) {
        iconService.delete(publicId.value());
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .build();
    }

    private static ResponseEntity<AiModelIconResult> created(AiModelIconResult result) {
        return ResponseEntity.created(URI.create(
                        "/api/admin/ai-model-icons/" + result.publicId()))
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(result);
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader(
                "Cache-Control",
                CacheControl.noStore().cachePrivate().getHeaderValue());
    }

    private static byte[] readFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw invalidInput();
        }
        if (file.getSize() > AdminAiModelIconService.MAX_FILE_BYTES) {
            throw new AiModelIconException(
                    AiModelIconErrorCode.AI_MODEL_ICON_FILE_TOO_LARGE,
                    "AI model icon file exceeds two MiB.");
        }
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new AiModelIconException(
                    AiModelIconErrorCode.AI_MODEL_ICON_INPUT_INVALID,
                    "AI model icon multipart content could not be read.",
                    exception);
        }
    }

    private static AiModelIconException invalidInput() {
        return new AiModelIconException(
                AiModelIconErrorCode.AI_MODEL_ICON_INPUT_INVALID,
                "AI model icon multipart content is invalid.");
    }

    /**
     * 定义直接登记外部图片时的受控 JSON 请求。
     */
    public record RemoteCreateRequest(
            @NotBlank @Size(max = 128)
            @Schema(description = "大小写不敏感唯一的图标显示名称")
            String iconName,
            @NotBlank @Size(max = 1024)
            @Schema(description = "需要经过服务器安全验证的外部 HTTPS 图片地址")
            String iconUrl,
            @Size(max = 512)
            @Schema(description = "图标适用厂商或模型系列的可选说明")
            String description) {
    }
}
