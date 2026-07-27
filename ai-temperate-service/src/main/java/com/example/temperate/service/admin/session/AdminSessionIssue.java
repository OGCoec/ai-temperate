package com.example.temperate.service.admin.session;

/**
 * 表示新管理员会话签发结果；原始 Token 只能由传输层写入 HttpOnly Cookie 或 Android 安全存储。
 */
public record AdminSessionIssue(
        String rawToken,
        AdminSessionProfile profile) {

    @Override
    public String toString() {
        return "AdminSessionIssue[redacted]";
    }
}
