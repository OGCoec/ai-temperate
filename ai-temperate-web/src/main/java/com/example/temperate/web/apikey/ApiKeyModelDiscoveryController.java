package com.example.temperate.web.apikey;

import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
import com.example.temperate.service.user.apikey.model.ApiKeyModelDiscoveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 该 Controller 是来按当前 Bearer API Key 的有效授权返回 OpenAI 兼容模型列表，不负责模型调用或 API Key 管理。
 */
@RestController
@RequestMapping("/v1")
@Tag(
        name = "开放接口-模型发现",
        description = "供 OpenAI 兼容 SDK 与 CC Switch 在调用 Chat Completions 前读取当前 Bearer API Key 可使用的模型。"
                + "只返回已授权、已启用且支持 Chat Completions 的模型；不提供模型详情、计费信息或上游全量目录。")
public final class ApiKeyModelDiscoveryController {

    private final ApiKeyModelDiscoveryService modelDiscoveryService;

    public ApiKeyModelDiscoveryController(ApiKeyModelDiscoveryService modelDiscoveryService) {
        this.modelDiscoveryService = Objects.requireNonNull(modelDiscoveryService);
    }

    @GetMapping(path = "/models", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "列出当前 API Key 可调用的 Chat 模型",
            description = "Authorization 使用 Bearer sk-***。返回为空表示 Key 有效但当前没有可调用模型。"
                    + "模型 ID 可直接作为 POST /v1/chat/completions 的 model 字段。",
            security = @SecurityRequirement(name = "apiKeyBearer"))
    public ResponseEntity<ApiKeyModelDiscoveryResponse> list(
            @AuthenticationPrincipal(errorOnInvalidType = true) ApiKeyPrincipal principal) {
        List<ApiKeyModelDiscoveryResponse.Model> models = modelDiscoveryService.list(principal).stream()
                .map(model -> new ApiKeyModelDiscoveryResponse.Model(
                        model.modelName(),
                        "model",
                        model.createdEpochSeconds(),
                        "ai-temperate"))
                .toList();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore().cachePrivate().noTransform())
                .header("CDN-Cache-Control", "no-store")
                .body(new ApiKeyModelDiscoveryResponse("list", models));
    }
}
