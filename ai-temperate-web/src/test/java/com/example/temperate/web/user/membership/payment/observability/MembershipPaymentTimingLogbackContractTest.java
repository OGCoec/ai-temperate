package com.example.temperate.web.user.membership.payment.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来约束会员支付计时事件写入固定一级日志目录，且异步缓冲在压力下不丢弃任何状态机明细。
 */
final class MembershipPaymentTimingLogbackContractTest {

    @Test
    void routesTimingLoggerToNonDiscardingFlatFileAppender() throws IOException {
        Path configuration = Path.of("src/main/resources/logback-spring.xml");

        assertThat(configuration).exists();
        String xml = Files.readString(configuration);
        assertThat(xml)
                .contains("membership.payment.state.timing")
                .contains("logs/membership-payment-state-machine.log")
                .contains("membership.payment.order.create.http")
                .contains("logs/membership-order-create-http-events.log")
                .contains("<append>true</append>")
                .contains("<discardingThreshold>0</discardingThreshold>")
                .contains("<neverBlock>false</neverBlock>")
                .contains("<pattern>%msg%n</pattern>")
                .contains("additivity=\"false\"");
    }
}
