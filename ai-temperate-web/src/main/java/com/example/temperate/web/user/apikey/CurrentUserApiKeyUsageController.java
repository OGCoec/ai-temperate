package com.example.temperate.web.user.apikey;

import com.example.temperate.common.codec.id.HybridUlidCodec;
import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.user.apikey.usage.ApiKeyUsageQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 该 Controller 是来为已通过用户会话认证的 H5 与 Android 查询本人 API Key 逐次调用记录，不参与计费写入、Key 认证或请求正文存取。
 */
@Validated
@RestController
@RequestMapping("/api/users/me/api-keys")
@Tag(
        name = "用户-API Key 调用记录",
        description = "供已通过现有用户会话认证的 H5 与 Android 按固定 UTC 时间段查看本人 API Key 的逐次模型调用、Token 和权威总扣费。接口不返回完整 Key、摘要、请求正文、回答正文、频率或趋势统计。")
public class CurrentUserApiKeyUsageController {

    private static final String CDN_CACHE_CONTROL = "CDN-Cache-Control";

    private final ApiKeyUsageQueryService usageService;

    public CurrentUserApiKeyUsageController(ApiKeyUsageQueryService usageService) {
        this.usageService = Objects.requireNonNull(usageService);
    }

    /**
     * 未传时间时由 Service 使用服务器当前时间生成最近一小时；后续分页必须复用首次响应返回的时间段。
     */
    @GetMapping("/{apiKeyPublicId}/usage")
    @Operation(
            summary = "查询本人 API Key 的逐次调用记录",
            description = "from 与 to 必须同时提供且为带时区 ISO-8601；均不提供时查询服务器当前时间之前一小时。范围采用 [from,to)，最大 31 天，明细按创建时间和用量 ID 倒序游标分页。")
    public ResponseEntity<ApiKeyUsageResponse> usage(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable
            @Parameter(
                    description = "API Key 的 26 字符规范大写 ULID。",
                    schema = @Schema(
                            minLength = HybridUlidCodec.ENCODED_LENGTH,
                            maxLength = HybridUlidCodec.ENCODED_LENGTH,
                            pattern = HybridUlidCodec.ENCODED_PATTERN,
                            example = "01KC938NKR041061050R3GG28A"))
            ApiKeyPublicId apiKeyPublicId,
            @RequestParam(required = false)
            @Parameter(description = "查询起点，包含该时刻，必须带时区并与 to 同时提供。")
            OffsetDateTime from,
            @RequestParam(required = false)
            @Parameter(description = "查询终点，不包含该时刻，不得晚于服务器当前时间。")
            OffsetDateTime to,
            @RequestParam(required = false)
            @Size(min = 38, max = 38)
            @Parameter(description = "上一页返回的固定 38 字符调用明细游标，只能与原固定时间段一起使用。")
            String cursor,
            @RequestParam(defaultValue = "20")
            @Min(1)
            @Max(100)
            @Parameter(description = "每页调用记录数量，范围 1 至 100。")
            int pageSize) {
        ApiKeyUsageResponse response = ApiKeyUsageResponse.from(usageService.query(
                principal.userId(),
                apiKeyPublicId.internalValue(),
                from,
                to,
                cursor,
                pageSize));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate().noTransform())
                .header(CDN_CACHE_CONTROL, "no-store")
                .body(response);
    }
}
