package com.example.temperate.web.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.temperate.web.auth.diagnostic.filter.AuthRequestTraceFilter;
import com.example.temperate.web.auth.flow.transport.AuthFlowCookieWriter;
import com.example.temperate.web.auth.session.transport.AuthCookieWriter;
import java.lang.reflect.Method;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * 验证只有明确的 Web 输入异常和框架绑定错误返回 400，内部参数异常不会伪装成客户端错误。
 */
final class GlobalExceptionHandlerInputClassificationTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(
            Clock.systemUTC(),
            mock(AuthCookieWriter.class),
            mock(AuthFlowCookieWriter.class));

    @Test
    void controlledWebInputAndBindingErrorsRemainBadRequests() {
        var controlled = handler.handleWebInvalidInput(new WebInvalidInputException());
        var binding = handler.handleInvalidInput(new BindException(new Object(), "request"));

        assertThat(controlled.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controlled.getBody()).isNotNull();
        assertThat(controlled.getBody().code()).isEqualTo("INVALID_INPUT");
        assertThat(binding.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void internalIllegalArgumentExceptionUsesGenericServerError() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthRequestTraceFilter.TRACE_ATTRIBUTE, "test-trace-id");

        var response = handler.handleUnexpected(
                new IllegalArgumentException("sensitive internal detail"),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().toString())
                .doesNotContain("sensitive internal detail", "IllegalArgumentException");
    }

    @Test
    void exceptionMappingKeepsInternalIllegalArgumentsOutsideTheInputHandler()
            throws Exception {
        Method method = GlobalExceptionHandler.class.getMethod(
                "handleInvalidInput", Exception.class);
        ExceptionHandler mapping = method.getAnnotation(ExceptionHandler.class);

        assertThat(mapping.value())
                .contains(BindException.class, HandlerMethodValidationException.class)
                .doesNotContain(IllegalArgumentException.class);
    }
}
