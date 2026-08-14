package com.example.temperate.web.user.apikey;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementException;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.CreateCommand;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.Created;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.Detail;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.ReplaceModelsCommand;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.Status;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.UpdateCommand;
import com.example.temperate.service.user.apikey.management.ApiKeyVersionTag;
import com.example.temperate.service.user.apikey.management.UserApiKeyService;
import com.example.temperate.web.user.apikey.ApiKeyManagementResponse.CreatedKey;
import com.example.temperate.web.user.apikey.ApiKeyManagementResponse.Key;
import com.example.temperate.web.user.apikey.ApiKeyManagementResponse.KeyPage;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 该 Controller 是来为已通过现有会话认证的 H5、Android、curl 和 Apifox 提供当前用户 API Key 管理，不参与 `/v1` Bearer 认证或模型流传输。
 */
@Validated
@RestController
@RequestMapping("/api/users/me/api-keys")
@Tag(
        name = "用户-API Key 管理",
        description = "供已通过现有用户会话认证的客户端创建、分页查询、更新、替换模型授权和软删除外部 API Key。完整 Key 只在创建响应出现一次；接口不提供解密、找回、名称或物理删除。")
public class CurrentUserApiKeyController {

    private final UserApiKeyService apiKeyService;

    public CurrentUserApiKeyController(UserApiKeyService apiKeyService) {
        this.apiKeyService = Objects.requireNonNull(apiKeyService);
    }

    @PostMapping
    @Operation(summary = "创建 API Key 并只返回一次完整凭证")
    public ResponseEntity<CreatedKey> create(
            @AuthenticationPrincipal SessionPrincipal principal,
            @Valid @RequestBody CreateRequest request) {
        Created created = apiKeyService.create(
                principal.userId(),
                new CreateCommand(request.expiresAt(), request.modelPublicIds()));
        CreatedKey response = ApiKeyManagementResponse.from(created);
        return ResponseEntity.created(URI.create("/api/users/me/api-keys/" + response.id()))
                .eTag(ApiKeyVersionTag.format(response.rowVersion()))
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(response);
    }

    @GetMapping
    @Operation(summary = "按稳定游标分页查询当前用户全部未删除 API Key")
    public ResponseEntity<KeyPage> list(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestParam(required = false) @Size(max = 128) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(ApiKeyManagementResponse.from(
                        apiKeyService.list(principal.userId(), cursor, pageSize)));
    }

    @GetMapping("/{apiKeyPublicId}")
    @Operation(summary = "查询单个 API Key 生命周期和当前模型授权详情")
    public ResponseEntity<Key> detail(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable
            @Parameter(schema = @Schema(
                    minLength = PublicIdCodec.ENCODED_LENGTH,
                    maxLength = PublicIdCodec.ENCODED_LENGTH,
                    pattern = PublicIdCodec.ENCODED_PATTERN,
                    example = "AAAAAAAAAAE"))
            ApiKeyPublicId apiKeyPublicId) {
        Key response = ApiKeyManagementResponse.from(
                apiKeyService.detail(principal.userId(), apiKeyPublicId.value()));
        return ResponseEntity.ok()
                .eTag(ApiKeyVersionTag.format(response.rowVersion()))
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(response);
    }

    @PutMapping("/{apiKeyPublicId}")
    @Operation(summary = "按强 ETag 完整替换 API Key 状态和过期时间")
    public ResponseEntity<Key> update(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable ApiKeyPublicId apiKeyPublicId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody UpdateRequest request) {
        long version = ApiKeyVersionTag.parseRequired(ifMatch);
        Detail detail = apiKeyService.update(
                principal.userId(),
                apiKeyPublicId.value(),
                version,
                new UpdateCommand(request.status(), request.expiresAt()));
        return keyResponse(detail);
    }

    @PutMapping("/{apiKeyPublicId}/models")
    @Operation(summary = "按强 ETag 完整替换 API Key 模型授权集合")
    public ResponseEntity<Key> replaceModels(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable ApiKeyPublicId apiKeyPublicId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ReplaceModelsRequest request) {
        long version = ApiKeyVersionTag.parseRequired(ifMatch);
        Detail detail = apiKeyService.replaceModels(
                principal.userId(),
                apiKeyPublicId.value(),
                version,
                new ReplaceModelsCommand(request.modelPublicIds()));
        return keyResponse(detail);
    }

    @DeleteMapping("/{apiKeyPublicId}")
    @Operation(summary = "按强 ETag 软删除 API Key 并批量软撤销模型授权")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable ApiKeyPublicId apiKeyPublicId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        apiKeyService.delete(
                principal.userId(),
                apiKeyPublicId.value(),
                ApiKeyVersionTag.parseRequired(ifMatch));
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .build();
    }

    private static ResponseEntity<Key> keyResponse(Detail detail) {
        Key response = ApiKeyManagementResponse.from(detail);
        return ResponseEntity.ok()
                .eTag(ApiKeyVersionTag.format(response.rowVersion()))
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(response);
    }

    /** 创建只接受过期时间与一至五百个模型公共 ID。 */
    public record CreateRequest(
            OffsetDateTime expiresAt,
            @NotNull @Size(min = 1, max = 500)
            List<@Pattern(regexp = PublicIdCodec.ENCODED_PATTERN) String> modelPublicIds) {

        @JsonAnySetter
        public void rejectUnknown(String name, JsonNode value) {
            throw unsupported();
        }
    }

    /** 普通更新只接受 ENABLED 或 DISABLED 和完整过期时间。 */
    public record UpdateRequest(@NotNull Status status, OffsetDateTime expiresAt) {

        @JsonAnySetter
        public void rejectUnknown(String name, JsonNode value) {
            throw unsupported();
        }
    }

    /** 模型替换允许空数组表达软撤销全部授权。 */
    public record ReplaceModelsRequest(
            @NotNull @Size(max = 500)
            List<@Pattern(regexp = PublicIdCodec.ENCODED_PATTERN) String> modelPublicIds) {

        @JsonAnySetter
        public void rejectUnknown(String name, JsonNode value) {
            throw unsupported();
        }
    }

    private static ApiKeyManagementException unsupported() {
        return new ApiKeyManagementException(
                com.example.temperate.service.user.apikey.management.ApiKeyManagementErrorCode.INPUT_INVALID,
                "API Key request contains an unsupported field");
    }
}
