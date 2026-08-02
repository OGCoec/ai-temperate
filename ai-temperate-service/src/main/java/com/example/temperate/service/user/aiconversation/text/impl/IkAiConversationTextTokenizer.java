package com.example.temperate.service.user.aiconversation.text.impl;

import com.example.temperate.service.user.aiconversation.text.AiConversationTextTokenizer;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.wltea.analyzer.core.IKSegmenter;
import org.wltea.analyzer.core.Lexeme;

/**
 * 使用 IK Analyzer 智能分词模式为用户提问生成 JSONB GIN 检索使用的完整词元集合。
 */
@Service
public final class IkAiConversationTextTokenizer
        implements AiConversationTextTokenizer {

    @Override
    public List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        IKSegmenter segmenter =
                new IKSegmenter(new StringReader(text), true);
        List<String> tokens = new ArrayList<>();
        try {
            Lexeme lexeme;
            while ((lexeme = segmenter.next()) != null) {
                String token = lexeme.getLexemeText();
                if (token != null && !token.isBlank()) {
                    tokens.add(token);
                }
            }
            return List.copyOf(tokens);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "AI conversation text tokenization failed.", exception);
        }
    }
}
