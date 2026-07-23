package com.example.temperate.web.auth.phonecountry.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "认证页面根据客户端网络位置得到的默认手机号国家建议")
/**
 * 电话号码默认国家建议接口的最小响应对象。
 *
 * <p>用途：仅向认证页面返回是否识别成功和 ISO2 国家码，不暴露客户端 IP、城市、经纬度或定位置信度。</p>
 */
public record PhoneCountryResponse(
        @Schema(description = "是否成功识别到前端国家列表可使用的 ISO2 国家代码")
                boolean resolved,
        @Schema(description = "大写 ISO 3166-1 alpha-2 国家代码；无法识别时为 null", example = "US")
                String countryIso2) {
}
