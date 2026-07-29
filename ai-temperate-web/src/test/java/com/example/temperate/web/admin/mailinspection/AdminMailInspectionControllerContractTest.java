package com.example.temperate.web.admin.mailinspection;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.service.admin.mailinspection.domain.AdminMailInspectionCreateCommand;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobCreateResult;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobSnapshot;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobSummary;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.service.AdminMailInspectionJobService;
import com.example.temperate.web.admin.mailinspection.api.AdminMailInspectionJobRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import reactor.core.publisher.Mono;

/**
 * 验证邮箱检查 Controller 的管理员路径、四个创建入口、统一查询入口与 Swagger 脱敏契约。
 */
final class AdminMailInspectionControllerContractTest {

    @Test
    void exposesCreateQueryRecoveryAndResumeRoutesUnderAdminNamespace() {
        RequestMapping mapping =
                AdminMailInspectionController.class.getAnnotation(RequestMapping.class);
        assertThat(mapping.value()).containsExactly("/api/admin/mail-inspection");

        Method[] methods = AdminMailInspectionController.class.getDeclaredMethods();
        assertThat(Arrays.stream(methods)
                .filter(method -> method.isAnnotationPresent(PostMapping.class))
                .map(method -> method.getAnnotation(PostMapping.class).value()[0]))
                .containsExactlyInAnyOrder(
                        "/openai-status-jobs",
                        "/kiro-status-jobs",
                        "/ip2location-registration-jobs",
                        "/ip2location-verify-link-jobs",
                        "/jobs/{jobId}/resume");
        assertThat(Arrays.stream(methods)
                .filter(method -> method.isAnnotationPresent(GetMapping.class))
                .map(method -> method.getAnnotation(GetMapping.class).value()[0]))
                .containsExactlyInAnyOrder(
                        "/jobs/{jobId}",
                        "/recovered-jobs");
    }

    @Test
    void documentsStableChineseTagAndEveryPublicOperation() {
        Tag tag = AdminMailInspectionController.class.getAnnotation(Tag.class);
        assertThat(tag.name()).isEqualTo("管理员-邮箱检查任务");
        assertThat(Arrays.stream(AdminMailInspectionController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PostMapping.class)
                        || method.isAnnotationPresent(GetMapping.class))
                .allMatch(method -> method.isAnnotationPresent(Operation.class)))
                .isTrue();
    }

    @Test
    void marksCredentialLinesWriteOnlyAndRedactsDebugText() throws Exception {
        Method accessor =
                AdminMailInspectionJobRequest.class.getDeclaredMethod("credentialLines");
        Schema schema = accessor.getAnnotation(Schema.class);
        AdminMailInspectionJobRequest request =
                new AdminMailInspectionJobRequest(java.util.List.of(
                        "mail----password----client----refresh"));

        assertThat(schema.accessMode()).isEqualTo(Schema.AccessMode.WRITE_ONLY);
        assertThat(Arrays.stream(accessor.getAnnotations())
                .map(annotation -> annotation.annotationType().getSimpleName()))
                .doesNotContain("Size");
        assertThat(request.toString()).doesNotContain("password", "refresh");

        AdminMailInspectionJobRequest largeRequest =
                new AdminMailInspectionJobRequest(java.util.stream.IntStream
                        .range(0, 1_000)
                        .mapToObj(index -> "credential-" + index)
                        .toList());
        assertThat(largeRequest.credentialLines()).hasSize(1_000);
    }

    @Test
    void mailJobPublicIdUsesDedicatedTwentyTwoCharacterBase64Url() {
        assertThat(HybridBase64UrlCodec.ENCODED_LENGTH).isEqualTo(22);
        assertThat(HybridBase64UrlCodec.ENCODED_PATTERN)
                .isEqualTo("^[A-Za-z0-9_-]{22}$");
    }

    @Test
    void createAndQueryResponsesArePrivateNoStore() {
        String publicId = new HybridBase64UrlCodec().encode(new byte[16]);
        Instant now = Instant.parse("2026-07-28T10:00:00Z");
        AdminMailInspectionJobService service = new AdminMailInspectionJobService() {
            @Override
            public Mono<MailInspectionJobCreateResult> create(
                    MailInspectionType type,
                    AdminMailInspectionCreateCommand command) {
                return Mono.just(new MailInspectionJobCreateResult(
                        publicId,
                        type,
                        MailInspectionJobStatus.QUEUED,
                        1,
                        1,
                        0,
                        0,
                        now));
            }

            @Override
            public MailInspectionJobSnapshot get(String jobId) {
                return new MailInspectionJobSnapshot(
                        publicId,
                        MailInspectionType.OPENAI_STATUS,
                        MailInspectionJobStatus.COMPLETED,
                        1,
                        1,
                        0,
                        0,
                        now,
                        now,
                        now,
                        now.plusSeconds(1800),
                        new MailInspectionJobSummary(Map.of()),
                        List.of());
            }

            @Override
            public List<MailInspectionJobSnapshot> getRecovered() {
                return List.of();
            }

            @Override
            public Mono<MailInspectionJobSnapshot> resume(
                    String jobId) {
                return Mono.just(get(jobId));
            }
        };
        AdminMailInspectionController controller =
                new AdminMailInspectionController(service);
        AdminMailInspectionJobRequest request =
                new AdminMailInspectionJobRequest(List.of(
                        "mail----password----client----refresh"));

        var created = controller.createOpenAi(
                "550e8400-e29b-41d4-a716-446655440000",
                request).block();
        var queried = controller.get(new MailInspectionJobPublicId(publicId));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(created.getHeaders().getFirst(HttpHeaders.LOCATION))
                .isEqualTo("/api/admin/mail-inspection/jobs/" + publicId);
        assertThat(created.getHeaders().getFirst("Idempotency-Replayed"))
                .isEqualTo("false");
        assertThat(created.getHeaders().getCacheControl())
                .contains("no-store", "private");
        assertThat(queried.getHeaders().getCacheControl())
                .contains("no-store", "private");
    }
}
