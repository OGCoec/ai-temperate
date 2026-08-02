package com.example.temperate.service.user.aiconversation.text;

import java.util.List;

/**
 * 定义用户原始提问写入消息表前生成 IK 搜索词元数组的边界。
 */
public interface AiConversationTextTokenizer {

    List<String> tokenize(String text);
}
