package com.example.temperate.web.admin.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 管理员认证页面根据可信客户端网络位置获得的手机号默认国家建议响应。
 *
 * <p>该响应只公开是否解析成功和 ISO2 国家代码，不返回客户端 IP、城市、经纬度或定位置信度。</p>
 */
@Schema(description = "管理员认证页面的手机号默认国家建议")
public record AdminPhoneCountryResponse(
        @Schema(description = "是否识别到管理员前端国家列表可用的 ISO2 国家代码")
                boolean resolved,
        @Schema(
                description = "大写 ISO 3166-1 alpha-2 国家代码；无法识别时为 null",
                example = "US")
                String countryIso2) {
}
