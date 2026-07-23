package com.example.temperate.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * HTTP JSON 中 Java Long 到字符串的序列化配置。
 *
 * <p>用途：避免超过 JavaScript 安全整数范围的 Long 在浏览器中精度丢失；业务资源 ID 仍应优先使用统一
 * Base64URL 编码。</p>
 */
@Configuration
public class HttpJsonLong2StringConfiguration implements WebMvcConfigurer {

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (HttpMessageConverter<?> converter : converters) {
            if (converter instanceof MappingJackson2HttpMessageConverter jacksonConverter) {
                // 复制 HTTP 专用 ObjectMapper，避免直接变更被其他框架组件共享的全局序列化器。
                ObjectMapper httpObjectMapper = jacksonConverter.getObjectMapper().copy();
                SimpleModule longAsStringModule = new SimpleModule()
                        .addSerializer(Long.class, ToStringSerializer.instance)
                        .addSerializer(Long.TYPE, ToStringSerializer.instance);
                httpObjectMapper.registerModule(longAsStringModule);
                jacksonConverter.setObjectMapper(httpObjectMapper);
            }
        }
    }
}
