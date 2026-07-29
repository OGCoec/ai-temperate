package com.example.temperate.web.admin.mailinspection.config;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionRabbitNames;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionSubmissionListenerControl;
import java.util.Objects;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 通过 Spring Rabbit 注册表显式启停四个提交派发监听器，保证应用启动后默认保持暂停。
 */
@Component
@ConditionalOnProperty(
        prefix = "app.admin.mail-inspection.rabbit",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public final class RabbitMailInspectionSubmissionListenerControl
        implements MailInspectionSubmissionListenerControl {

    private final RabbitListenerEndpointRegistry registry;

    public RabbitMailInspectionSubmissionListenerControl(
            RabbitListenerEndpointRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
    }

    @Override
    public Mono<Void> start(MailInspectionType type) {
        return Mono.fromRunnable(() -> {
                    MessageListenerContainer container = required(type);
                    if (!container.isRunning()) {
                        container.start();
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @Override
    public void stop(MailInspectionType type) {
        MessageListenerContainer container = registry.getListenerContainer(
                MailInspectionRabbitNames.submissionListenerId(type));
        if (container != null && container.isRunning()) {
            container.stop();
        }
    }

    @Override
    public void stopAll() {
        MailInspectionRabbitNames.supportedTypes().forEach(this::stop);
    }

    private MessageListenerContainer required(MailInspectionType type) {
        MessageListenerContainer container = registry.getListenerContainer(
                MailInspectionRabbitNames.submissionListenerId(type));
        if (container == null) {
            throw new IllegalStateException(
                    "mail inspection submission listener unavailable");
        }
        return container;
    }
}
