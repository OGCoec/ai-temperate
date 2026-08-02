package com.example.temperate.web.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 绑定 Spring MVC 异步响应写出线程池和外层请求时限，防止长连接使用无界默认执行器。
 */
@Validated
@ConfigurationProperties(prefix = "app.web.mvc-async")
public record MvcAsyncStreamingProperties(
        @Min(1) int corePoolSize,
        @Min(1) int maxPoolSize,
        @Min(0) @Max(0) int queueCapacity,
        @NotNull Duration keepAlive,
        @NotNull Duration timeout) {

    /**
     * 核心线程不得超过最大线程数，避免底层执行器在初始化阶段以不明确异常退出。
     *
     * @return 线程上下界是否合法
     */
    @AssertTrue(message = "MVC async executor pool sizes are invalid")
    public boolean arePoolSizesValid() {
        return corePoolSize <= maxPoolSize;
    }

    /**
     * 保活时间至少为整秒且可安全转换为执行器参数，MVC 超时也必须能转换为毫秒。
     *
     * @return 时长参数是否合法
     */
    @AssertTrue(message = "MVC async executor durations are invalid")
    public boolean areDurationsValid() {
        return isPositive(keepAlive)
                && keepAlive.compareTo(Duration.ofSeconds(1)) >= 0
                && keepAlive.compareTo(Duration.ofSeconds(Integer.MAX_VALUE)) <= 0
                && isPositive(timeout)
                && fitsMillis(timeout);
    }

    private static boolean isPositive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }

    private static boolean fitsMillis(Duration value) {
        try {
            value.toMillis();
            return true;
        } catch (ArithmeticException overflow) {
            return false;
        }
    }
}
