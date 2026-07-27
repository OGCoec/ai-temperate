package com.example.temperate.service.admin.aimodel.text.impl;

import com.example.temperate.service.admin.aimodel.text.AiModelTextTokenizer;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import org.springframework.stereotype.Component;
import org.wltea.analyzer.core.IKSegmenter;
import org.wltea.analyzer.core.Lexeme;

/**
 * 使用 IK Analyzer 为 AI 模型展示文本生成稳定搜索词元。
 *
 * <p>每次调用创建独立分词器，避免单例 Bean 保存请求级游标；TreeSet 同时保证小写去重和确定性顺序。</p>
 */
@Component
public final class IkAiModelTextTokenizer implements AiModelTextTokenizer {

    @Override
    public List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        TreeSet<String> tokens = new TreeSet<>();
        IKSegmenter segmenter = new IKSegmenter(new StringReader(text), true);
        try {
            Lexeme lexeme;
            while ((lexeme = segmenter.next()) != null) {
                String token = lexeme.getLexemeText();
                if (token != null && !token.isBlank()) {
                    tokens.add(token.toLowerCase(Locale.ROOT));
                }
            }
        } catch (IOException exception) {
            // StringReader 理论上不会发生 I/O 失败；若分词器仍抛错，必须终止写入而不是保存不完整词元。
            throw new IllegalStateException("AI model text tokenization failed.", exception);
        }
        return List.copyOf(tokens);
    }
}
