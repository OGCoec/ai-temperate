package com.example.temperate.web.apikey;

import com.example.temperate.service.user.apikey.model.ApiKeyModelDiscoveryException;
import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 该异常处理器是来把 Models API 的目录读取失败转换为 OpenAI 错误包络，禁止将缓存、数据库或异常栈暴露给 API Key 客户端。
 */
@RestControllerAdvice(assignableTypes = ApiKeyModelDiscoveryController.class)
public final class ApiKeyModelDiscoveryExceptionHandler {

    @ExceptionHandler(ApiKeyModelDiscoveryException.class)
    public ResponseEntity<OpenAiErrorResponseWriter.Envelope> unavailable(
            ApiKeyModelDiscoveryException exception) {
        Objects.requireNonNull(exception);
        OpenAiErrorResponseWriter.Envelope body = new OpenAiErrorResponseWriter.Envelope(
                new OpenAiErrorResponseWriter.Error(
                        "The model catalog is temporarily unavailable.",
                        "server_error",
                        null,
                        "model_catalog_unavailable"));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore().cachePrivate().noTransform())
                .header("CDN-Cache-Control", "no-store")
                .body(body);
    }
}
