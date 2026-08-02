package com.example.temperate.service.aimodel.search;

import java.util.List;

/**
 * 统一生成 AI 模型名称与描述词元、搜索条件及列表字段的命中词。
 *
 * <p>模型名称只能按 ASCII 横杠切分，描述继续使用 IK；调用方不得自行复制分词规则或拼接 JSON SQL。
 * 名称和描述的命中词必须分别计算，避免一个索引分支的命中状态污染另一个展示字段。</p>
 */
public interface AiModelSearchService {

    AiModelSearchCriteria prepare(String keyword);

    String modelNameTokensJson(String modelName);

    String descriptionTokensJson(String description);

    List<String> matchedModelNameTokens(
            String storedModelNameTokensJson,
            AiModelSearchCriteria criteria);

    List<String> matchedDescriptionTokens(
            String storedDescriptionTokensJson,
            AiModelSearchCriteria criteria);
}
