package com.example.temperate.web.admin.controller;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.risk.ip2location.domain.Ip2LocationImportMode;
import com.example.temperate.service.risk.ip2location.domain.Ip2LocationPlanType;
import com.example.temperate.service.risk.ip2location.dto.Ip2LocationKeyBatchCommand;
import com.example.temperate.service.risk.ip2location.dto.Ip2LocationKeyBatchResult;
import com.example.temperate.service.risk.ip2location.dto.Ip2LocationKeyPage;
import com.example.temperate.service.risk.ip2location.service.Ip2LocationApiKeyService;
import com.example.temperate.web.auth.api.WebInvalidInputException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 提供受管理员会话、设备校验和 CSRF 保护的 IP2Location API Key 批量管理接口。
 *
 * <p>接口只返回 HMAC Key ID 和脱敏元数据，永不回显明文 Key、密文或供应商响应；文件导入与 JSON
 * 批量导入共享同一原子业务入口。</p>
 */
@Validated
@RestController
@RequestMapping("/api/admin/risk/ip2location/keys")
@Tag(
        name = "管理员-IP 风险凭据",
        description = "管理网络风控调用使用的 IP2Location API Key 加密池；仅限有效管理员会话，接口不提供明文凭据查询或 IP 风险查询。")
public class AdminIp2LocationKeyController {

    private static final long MAX_IMPORT_BYTES = 256L * 1024L;

    private final Ip2LocationApiKeyService keyService;

    public AdminIp2LocationKeyController(Ip2LocationApiKeyService keyService) {
        this.keyService = Objects.requireNonNull(keyService);
    }

    @PostMapping("/batch")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "原子批量导入并加密保存 IP2Location API Key",
            description = "客户端只提交套餐、初始额度和 Key；有效期由 Service 按服务端 UTC 时间计算。")
    public Ip2LocationKeyBatchResult batch(
            @Valid @RequestBody BatchRequest request,
            HttpServletResponse response) {
        noStore(response);
        return keyService.importBatch(request.toCommand());
    }

    @PostMapping(path = "/import", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "从 UTF-8 文本文件原子批量导入 IP2Location API Key",
            description = "文件接口只接受套餐、初始额度和导入模式，不接受客户端提供的截止时间或 TTL。")
    public Ip2LocationKeyBatchResult importText(
            @RequestParam("file") MultipartFile file,
            @RequestParam
            @Parameter(
                    description = "IP2Location 套餐类型，仅接受服务端白名单中的稳定大写字符串。",
                    schema = @Schema(
                            allowableValues = {
                                "FREE", "STARTER", "PLUS", "SECURITY",
                                "SECURITY_TRIAL", "CUSTOM"
                            },
                            example = "FREE"))
            String planType,
            @RequestParam @Min(1) long initialQuota,
            @RequestParam(defaultValue = "CREATE_ONLY") Ip2LocationImportMode mode,
            HttpServletResponse response) throws IOException {
        noStore(response);
        Ip2LocationPlanType requiredPlanType = parsePlanType(planType);
        if (file == null
                || file.isEmpty()
                || file.getSize() > MAX_IMPORT_BYTES
                || file.getOriginalFilename() == null
                || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".txt")) {
            throw new WebInvalidInputException();
        }
        byte[] bytes = file.getBytes();
        String decoded = new String(bytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(bytes, decoded.getBytes(StandardCharsets.UTF_8))) {
            throw new WebInvalidInputException();
        }
        List<String> apiKeys = decoded.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
        if (apiKeys.isEmpty() || apiKeys.size() > 500) {
            throw new WebInvalidInputException();
        }
        return keyService.importBatch(new Ip2LocationKeyBatchCommand(
                requiredPlanType,
                initialQuota,
                mode,
                apiKeys));
    }

    @GetMapping
    @Operation(summary = "使用有界 HSCAN 分页查看脱敏 IP2Location Key 元数据")
    public Ip2LocationKeyPage list(
            @RequestParam(defaultValue = "0") @Min(0) long cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size,
            HttpServletResponse response) {
        noStore(response);
        return keyService.list(cursor, size);
    }

    @PostMapping("/delete")
    @Operation(summary = "按 HMAC Key ID 原子批量删除凭据与额度")
    public DeleteResponse delete(
            @Valid @RequestBody DeleteRequest request,
            HttpServletResponse response) {
        noStore(response);
        List<HmacIdentifier> keyIds = request.keyIds().stream()
                .map(HmacIdentifier::fromProtectedValue)
                .toList();
        return new DeleteResponse(keyService.delete(keyIds));
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", CacheControl.noStore().cachePrivate().getHeaderValue());
    }

    private static Ip2LocationPlanType parsePlanType(String value) {
        if (value == null) {
            throw new WebInvalidInputException();
        }
        try {
            // 传输层只接受枚举声明的稳定大写字符串，数字、大小写变体和未知值均在进入 Service 前失败。
            return Ip2LocationPlanType.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new WebInvalidInputException();
        }
    }

    /**
     * 定义 JSON 批量导入的公开参数；API Key 只允许写入且不会出现在响应或日志。
     */
    public record BatchRequest(
            @NotBlank
            @Schema(
                    description = "IP2Location 套餐类型，仅接受服务端白名单中的稳定大写字符串。",
                    allowableValues = {
                        "FREE", "STARTER", "PLUS", "SECURITY", "SECURITY_TRIAL", "CUSTOM"
                    },
                    example = "FREE")
            String planType,
            @Min(1) long initialQuota,
            Ip2LocationImportMode mode,
            @NotEmpty @Size(max = 500)
            @Schema(accessMode = Schema.AccessMode.WRITE_ONLY)
            List<@Size(min = 8, max = 256) String> apiKeys) {

        Ip2LocationKeyBatchCommand toCommand() {
            return new Ip2LocationKeyBatchCommand(
                    parsePlanType(planType),
                    initialQuota,
                    mode == null ? Ip2LocationImportMode.CREATE_ONLY : mode,
                    List.copyOf(apiKeys));
        }

        @Override
        public String toString() {
            return "BatchRequest[redacted]";
        }
    }

    /**
     * 定义管理员批量删除的 HMAC 标识集合，禁止客户端提交明文 Key。
     */
    public record DeleteRequest(
            @NotEmpty @Size(max = 100) List<String> keyIds) {
    }

    /**
     * 返回两个 Redis Hash 中被共同删除的凭据数量。
     */
    public record DeleteResponse(long deletedCount) {
    }
}
