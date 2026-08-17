package com.example.temperate.web.apikey;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 该响应是来承载 OpenAI 兼容的 Models 列表，只包含客户端选择模型所需的公开字段。
 */
public record ApiKeyModelDiscoveryResponse(String object, List<Model> data) {

    /** 单个公开模型不携带数据库 ID、计费倍率或供应商凭据。 */
    public record Model(
            String id,
            String object,
            long created,
            @JsonProperty("owned_by") String ownedBy) {
    }
}
