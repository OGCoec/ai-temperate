package com.example.temperate.web.admin.mailinspection;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobCreateResult;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.service.AdminMailInspectionJobService;
import com.example.temperate.web.admin.mailinspection.api.AdminMailInspectionCreateResponse;
import com.example.temperate.web.admin.mailinspection.api.AdminMailInspectionJobRequest;
import com.example.temperate.web.admin.mailinspection.api.AdminMailInspectionJobResponse;
import com.example.temperate.web.admin.mailinspection.api.AdminMailInspectionRecoveredJobResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 提供四类邮箱证据检查任务创建、统一查询、重启恢复列表和管理员批准继续处理接口。
 *
 * <p>Controller 只编排 HTTP 与脱敏 DTO；Rabbit 发布、恢复扫描、OAuth、IMAP 和 ACK 时机全部由 Service 负责。</p>
 */
@Validated
@RestController
@RequestMapping("/api/admin/mail-inspection")
@Tag(
        name = "管理员-邮箱检查任务",
        description =
                "仅供已登录管理员批量检查其有权访问的 Microsoft 邮箱。"
                        + "接口通过 Microsoft OAuth 与 Outlook IMAP 读取邮件，不直接调用 OpenAI、Kiro 或 IP2Location 账户 API。"
                        + "结果是邮件证据分类，不是第三方平台实时账户状态证明。"
                        + "全部接口受 Edge、CSRF、PreAuth、网络风险和管理员会话保护，且不保存、不回显密码或 Token。")
public class AdminMailInspectionController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String IDEMPOTENCY_REPLAYED = "Idempotency-Replayed";
    private static final CacheControl PRIVATE_NO_STORE =
            CacheControl.noStore().cachePrivate();

    private final AdminMailInspectionJobService jobService;

    public AdminMailInspectionController(
            AdminMailInspectionJobService jobService) {
        this.jobService = Objects.requireNonNull(jobService);
    }

    @PostMapping("/openai-status-jobs")
    @Operation(summary = "创建 OpenAI 邮件状态检查任务")
    public Mono<ResponseEntity<AdminMailInspectionCreateResponse>>
            createOpenAi(
                    @RequestHeader(IDEMPOTENCY_KEY)
                    @Parameter(
                            description = "同一次创建及其网络重试必须复用的规范小写 UUIDv4。",
                            required = true,
                            schema = @Schema(
                                    pattern = "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
                                    example = "550e8400-e29b-41d4-a716-446655440000"))
                    String idempotencyKey,
                    @Valid @RequestBody
                            AdminMailInspectionJobRequest request) {
        return create(MailInspectionType.OPENAI_STATUS, idempotencyKey, request);
    }

    @PostMapping("/kiro-status-jobs")
    @Operation(summary = "创建 Kiro 邮件状态检查任务")
    public Mono<ResponseEntity<AdminMailInspectionCreateResponse>>
            createKiro(
                    @RequestHeader(IDEMPOTENCY_KEY)
                    @Parameter(
                            description = "同一次创建及其网络重试必须复用的规范小写 UUIDv4。",
                            required = true)
                    String idempotencyKey,
                    @Valid @RequestBody
                            AdminMailInspectionJobRequest request) {
        return create(MailInspectionType.KIRO_STATUS, idempotencyKey, request);
    }

    @PostMapping("/ip2location-registration-jobs")
    @Operation(summary = "创建 IP2Location 注册邮件检查任务")
    public Mono<ResponseEntity<AdminMailInspectionCreateResponse>>
            createIp2Registration(
                    @RequestHeader(IDEMPOTENCY_KEY)
                    @Parameter(
                            description = "同一次创建及其网络重试必须复用的规范小写 UUIDv4。",
                            required = true)
                    String idempotencyKey,
                    @Valid @RequestBody
                            AdminMailInspectionJobRequest request) {
        return create(
                MailInspectionType.IP2LOCATION_REGISTRATION,
                idempotencyKey,
                request);
    }

    @PostMapping("/ip2location-verify-link-jobs")
    @Operation(summary = "创建 IP2Location 验证链接提取任务")
    public Mono<ResponseEntity<AdminMailInspectionCreateResponse>>
            createIp2VerifyLink(
                    @RequestHeader(IDEMPOTENCY_KEY)
                    @Parameter(
                            description = "同一次创建及其网络重试必须复用的规范小写 UUIDv4。",
                            required = true)
                    String idempotencyKey,
                    @Valid @RequestBody
                            AdminMailInspectionJobRequest request) {
        return create(
                MailInspectionType.IP2LOCATION_VERIFY_LINK,
                idempotencyKey,
                request);
    }

    @GetMapping("/jobs/{jobId}")
    @Operation(summary = "查询邮箱检查任务")
    public ResponseEntity<AdminMailInspectionJobResponse> get(
            @PathVariable
            @Parameter(
                    description = "固定 11 位规范 Base64URL 任务公共 ID。",
                    schema = @Schema(
                            minLength = PublicIdCodec.ENCODED_LENGTH,
                            maxLength = PublicIdCodec.ENCODED_LENGTH,
                            pattern = PublicIdCodec.ENCODED_PATTERN,
                            example = "AAAAAAAAAAE"))
            MailInspectionJobPublicId jobId) {
        return ResponseEntity.ok()
                .cacheControl(PRIVATE_NO_STORE)
                .body(AdminMailInspectionJobResponse.from(
                        jobService.get(jobId.internalId())));
    }

    @GetMapping("/recovered-jobs")
    @Operation(
            summary = "查询重启后等待批准的邮箱检查任务",
            description =
                    "只返回 RabbitMQ 中仍存在的脱敏剩余项；重启前已完成的详细结果不会伪造恢复。")
    public ResponseEntity<List<AdminMailInspectionRecoveredJobResponse>>
            getRecovered() {
        return ResponseEntity.ok()
                .cacheControl(PRIVATE_NO_STORE)
                .body(jobService.getRecovered().stream()
                        .map(AdminMailInspectionRecoveredJobResponse::from)
                        .toList());
    }

    @PostMapping("/jobs/{jobId}/resume")
    @Operation(
            summary = "批准继续处理重启后剩余任务",
            description =
                    "仅接受 AWAITING_ADMIN_RESUME；不会重新发布消息、创建新 jobId 或绕过原业务并发。")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "消费者已按原并发启动"),
        @ApiResponse(responseCode = "409", description = "任务状态不允许重复批准"),
        @ApiResponse(responseCode = "503", description = "Rabbit 消费者无法启动")
    })
    public Mono<ResponseEntity<AdminMailInspectionJobResponse>> resume(
            @PathVariable
            @Parameter(
                    description = "固定 11 位规范 Base64URL 任务公共 ID。",
                    schema = @Schema(
                            minLength = PublicIdCodec.ENCODED_LENGTH,
                            maxLength = PublicIdCodec.ENCODED_LENGTH,
                            pattern = PublicIdCodec.ENCODED_PATTERN,
                            example = "AAAAAAAAAAE"))
            MailInspectionJobPublicId jobId) {
        return jobService.resume(jobId.internalId())
                .map(snapshot -> ResponseEntity.accepted()
                        .cacheControl(PRIVATE_NO_STORE)
                        .body(AdminMailInspectionJobResponse.from(snapshot)));
    }

    private Mono<ResponseEntity<AdminMailInspectionCreateResponse>> create(
            MailInspectionType type,
            String idempotencyKey,
            AdminMailInspectionJobRequest request) {
        MailInspectionClientRequestId clientRequestId =
                MailInspectionClientRequestId.parse(idempotencyKey);
        return jobService.create(
                        type,
                        request.toCommand(clientRequestId.value()))
                .map(this::accepted);
    }

    private ResponseEntity<AdminMailInspectionCreateResponse> accepted(
            MailInspectionJobCreateResult result) {
        URI location = URI.create(
                "/api/admin/mail-inspection/jobs/" + result.jobId());
        return ResponseEntity.accepted()
                .header(HttpHeaders.LOCATION, location.toString())
                .header(
                        IDEMPOTENCY_REPLAYED,
                        Boolean.toString(result.idempotencyReplayed()))
                .cacheControl(PRIVATE_NO_STORE)
                .body(AdminMailInspectionCreateResponse.from(result));
    }
}
