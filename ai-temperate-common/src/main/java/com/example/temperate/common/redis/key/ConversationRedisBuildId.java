package com.example.temperate.common.redis.key;

import java.util.regex.Pattern;

/**
 * 表示 AI 会话上下文分批重建使用的一次性随机标识，防止业务代码自行拼接临时 Redis Key。
 */
public record ConversationRedisBuildId(String value) {

    private static final Pattern FORMAT =
            Pattern.compile("^[a-f0-9]{32}$");

    public ConversationRedisBuildId {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "AI conversation Redis build ID must be 32 lowercase hexadecimal characters.");
        }
    }
}
