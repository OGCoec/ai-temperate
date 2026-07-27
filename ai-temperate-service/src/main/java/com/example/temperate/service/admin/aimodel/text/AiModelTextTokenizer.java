package com.example.temperate.service.admin.aimodel.text;

import java.util.List;

/**
 * 定义 AI 模型名称和描述生成确定性搜索词元的能力。
 *
 * <p>实现必须无状态、线程安全，并对相同输入稳定返回小写、去重且有序的结果。</p>
 */
public interface AiModelTextTokenizer {

    List<String> tokenize(String text);
}
