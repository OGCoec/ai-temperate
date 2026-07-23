package com.example.temperate.service.registration.verification.delivery.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * 在单元测试中临时捕获指定类型的 DEBUG 日志，并在关闭时恢复原有日志级别。
 *
 * <p>该工具只读取格式化后的日志文本，用于验证结构化字段与敏感信息不会进入日志；它不会修改生产配置，
 * 也不会把测试日志写入文件或外部系统。</p>
 */
public final class DebugLogCapture implements AutoCloseable {

    private final Logger logger;
    private final Level previousLevel;
    private final ListAppender<ILoggingEvent> appender;

    private DebugLogCapture(
            Logger logger,
            Level previousLevel,
            ListAppender<ILoggingEvent> appender) {
        this.logger = logger;
        this.previousLevel = previousLevel;
        this.appender = appender;
    }

    public static DebugLogCapture start(Class<?> loggedType) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggedType);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);
        return new DebugLogCapture(logger, previousLevel, appender);
    }

    public List<String> messages() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    public String joinedMessages() {
        return String.join("\n", messages());
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
        appender.stop();
    }
}
