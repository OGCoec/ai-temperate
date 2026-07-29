package com.example.temperate.web.admin.mailinspection.api;

import com.example.temperate.service.admin.mailinspection.domain.AdminMailInspectionCreateCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;

/**
 * 接收总量不超过 1 MiB 的四段邮箱凭证，并在传输边界固定使用脱敏调试文本。
 */
public record AdminMailInspectionJobRequest(
        @NotEmpty
        @Schema(
                description =
                        "每行必须为邮箱----密码----clientId----refreshToken；"
                                + "密码只验证存在性，服务端不会保存或回显任何密码或 Token。",
                accessMode = Schema.AccessMode.WRITE_ONLY,
                example =
                        "[\"***@***----***----00000000-0000-0000-0000-000000000000----***\"]")
        List<@NotNull String> credentialLines,
        @Min(1)
        @Max(64)
        @Schema(
                description = "任务对应队列的业务并发额度，默认 4，创建后锁定。",
                defaultValue = "4",
                minimum = "1",
                maximum = "64",
                example = "4")
        Integer businessConcurrency) {

    public AdminMailInspectionJobRequest(
            List<String> credentialLines) {
        this(credentialLines, 4);
    }

    public AdminMailInspectionJobRequest {
        credentialLines = credentialLines == null
                ? null
                : List.copyOf(credentialLines);
    }

    public AdminMailInspectionCreateCommand toCommand(
            String clientRequestId) {
        return new AdminMailInspectionCreateCommand(
                clientRequestId,
                credentialLines,
                businessConcurrency == null ? 4 : businessConcurrency);
    }

    @Override
    public String toString() {
        return "AdminMailInspectionJobRequest[credentialLines=protected,count="
                + (credentialLines == null ? 0 : credentialLines.size())
                + ",businessConcurrency="
                + (businessConcurrency == null ? 4 : businessConcurrency)
                + "]";
    }
}
