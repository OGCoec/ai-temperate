package com.example.temperate.web.user.voice;

import com.example.temperate.service.user.voice.VoiceErrorCode;
import com.example.temperate.service.user.voice.VoiceException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 将语音票据 HTTP 失败映射为不包含内部异常、票据和 Redis 细节的稳定响应。
 */
@RestControllerAdvice(assignableTypes = VoiceSessionTicketController.class)
@ConditionalOnProperty(prefix = "app.voice", name = "enabled", havingValue = "true")
public final class VoiceWebExceptionHandler {

    private final Clock clock;

    public VoiceWebExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(VoiceException.class)
    public ResponseEntity<VoiceApiErrorResponse> handle(VoiceException exception) {
        HttpStatus status = switch (exception.code()) {
            case VOICE_TICKET_RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case VOICE_TICKET_INVALID -> HttpStatus.UNAUTHORIZED;
            case VOICE_INFRASTRUCTURE_UNAVAILABLE,
                    VOICE_UPSTREAM_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(new VoiceApiErrorResponse(
                        exception.code().name(),
                        exception.getMessage(),
                        exception.retryable(),
                        clock.instant()));
    }

    /**
     * 表示语音票据接口的安全错误响应，不携带底层异常内容。
     */
    public record VoiceApiErrorResponse(
            String code,
            String message,
            boolean retryable,
            Instant timestamp) {
    }
}
