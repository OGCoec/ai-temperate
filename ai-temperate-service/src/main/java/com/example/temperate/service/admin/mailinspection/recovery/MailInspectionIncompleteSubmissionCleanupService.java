package com.example.temperate.service.admin.mailinspection.recovery;

/**
 * 定期清理超过保留期且仍不完整的持久提交，释放同类型活动任务额度。
 */
public interface MailInspectionIncompleteSubmissionCleanupService {

    void cleanupExpiredSubmissions();
}
