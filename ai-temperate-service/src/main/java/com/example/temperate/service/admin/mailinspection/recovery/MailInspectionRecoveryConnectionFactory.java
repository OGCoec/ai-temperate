package com.example.temperate.service.admin.mailinspection.recovery;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import java.io.IOException;
import java.util.concurrent.TimeoutException;

/**
 * 创建绕过 Spring Channel Cache 的 Rabbit 物理恢复会话，供恢复与安全清理流程独占使用。
 */
public interface MailInspectionRecoveryConnectionFactory {

    MailInspectionRecoverySession open(
            MailInspectionType type,
            String purpose) throws IOException, TimeoutException;
}
