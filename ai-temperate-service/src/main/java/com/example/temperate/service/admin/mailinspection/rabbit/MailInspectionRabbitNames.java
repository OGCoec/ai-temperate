package com.example.temperate.service.admin.mailinspection.rabbit;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 集中定义管理员邮箱检查 RabbitMQ 拓扑名称，并提供检查类型到唯一队列、路由键和监听容器的固定映射。
 */
public final class MailInspectionRabbitNames {

    public static final String WORK_EXCHANGE =
            "ait.admin.mail-inspection.work.v1";
    public static final String SUBMISSION_EXCHANGE =
            "ait.admin.mail-inspection.submission.v1";
    public static final String DISPATCH_STATE_EXCHANGE =
            "ait.admin.mail-inspection.dispatch-state.v1";
    public static final String DEAD_EXCHANGE =
            "ait.admin.mail-inspection.dead.v1";
    public static final String DEAD_QUEUE =
            "ait.admin.mail-inspection.dead.v1";
    public static final String DEAD_ROUTING_KEY =
            "mail-inspection.dead";
    public static final String SUBMISSION_DEAD_EXCHANGE =
            "ait.admin.mail-inspection.submission.dead.v1";
    public static final String SUBMISSION_DEAD_QUEUE =
            "ait.admin.mail-inspection.submission.dead.v1";
    public static final String SUBMISSION_DEAD_ROUTING_KEY =
            "mail-inspection.submission.dead";
    public static final String EVENT_TYPE =
            "ADMIN_MAIL_INSPECTION_WORK";
    public static final int LEGACY_WORK_SCHEMA_VERSION = 1;
    public static final int WORK_SCHEMA_VERSION = 2;
    public static final int SCHEMA_VERSION = WORK_SCHEMA_VERSION;
    public static final String SUBMISSION_EVENT_TYPE =
            "ADMIN_MAIL_INSPECTION_SUBMISSION_CHUNK";
    public static final String DISPATCH_MARKER_EVENT_TYPE =
            "ADMIN_MAIL_INSPECTION_DISPATCH_MARKER";
    public static final int SUBMISSION_SCHEMA_VERSION = 1;
    public static final int DISPATCH_MARKER_SCHEMA_VERSION = 1;

    public static final String OPENAI_QUEUE =
            "ait.admin.mail-inspection.openai-status.work.v1";
    public static final String KIRO_QUEUE =
            "ait.admin.mail-inspection.kiro-status.work.v1";
    public static final String IP2_REGISTRATION_QUEUE =
            "ait.admin.mail-inspection.ip2location-registration.work.v1";
    public static final String IP2_VERIFY_QUEUE =
            "ait.admin.mail-inspection.ip2location-verify-link.work.v1";

    public static final String OPENAI_SUBMISSION_QUEUE =
            "ait.admin.mail-inspection.openai-status.submission.v1";
    public static final String KIRO_SUBMISSION_QUEUE =
            "ait.admin.mail-inspection.kiro-status.submission.v1";
    public static final String IP2_REGISTRATION_SUBMISSION_QUEUE =
            "ait.admin.mail-inspection.ip2location-registration.submission.v1";
    public static final String IP2_VERIFY_SUBMISSION_QUEUE =
            "ait.admin.mail-inspection.ip2location-verify-link.submission.v1";

    public static final String OPENAI_DISPATCH_STATE_QUEUE =
            "ait.admin.mail-inspection.openai-status.dispatch-state.v1";
    public static final String KIRO_DISPATCH_STATE_QUEUE =
            "ait.admin.mail-inspection.kiro-status.dispatch-state.v1";
    public static final String IP2_REGISTRATION_DISPATCH_STATE_QUEUE =
            "ait.admin.mail-inspection.ip2location-registration.dispatch-state.v1";
    public static final String IP2_VERIFY_DISPATCH_STATE_QUEUE =
            "ait.admin.mail-inspection.ip2location-verify-link.dispatch-state.v1";

    public static final String OPENAI_ROUTING_KEY =
            "mail-inspection.openai-status";
    public static final String KIRO_ROUTING_KEY =
            "mail-inspection.kiro-status";
    public static final String IP2_REGISTRATION_ROUTING_KEY =
            "mail-inspection.ip2location-registration";
    public static final String IP2_VERIFY_ROUTING_KEY =
            "mail-inspection.ip2location.verify-link";

    public static final String OPENAI_SUBMISSION_ROUTING_KEY =
            "mail-inspection.submission.openai-status";
    public static final String KIRO_SUBMISSION_ROUTING_KEY =
            "mail-inspection.submission.kiro-status";
    public static final String IP2_REGISTRATION_SUBMISSION_ROUTING_KEY =
            "mail-inspection.submission.ip2location-registration";
    public static final String IP2_VERIFY_SUBMISSION_ROUTING_KEY =
            "mail-inspection.submission.ip2location.verify-link";

    public static final String OPENAI_DISPATCH_STATE_ROUTING_KEY =
            "mail-inspection.dispatch-state.openai-status";
    public static final String KIRO_DISPATCH_STATE_ROUTING_KEY =
            "mail-inspection.dispatch-state.kiro-status";
    public static final String IP2_REGISTRATION_DISPATCH_STATE_ROUTING_KEY =
            "mail-inspection.dispatch-state.ip2location-registration";
    public static final String IP2_VERIFY_DISPATCH_STATE_ROUTING_KEY =
            "mail-inspection.dispatch-state.ip2location.verify-link";

    public static final String OPENAI_LISTENER_ID =
            "adminMailInspectionOpenAiListener";
    public static final String KIRO_LISTENER_ID =
            "adminMailInspectionKiroListener";
    public static final String IP2_REGISTRATION_LISTENER_ID =
            "adminMailInspectionIp2RegistrationListener";
    public static final String IP2_VERIFY_LISTENER_ID =
            "adminMailInspectionIp2VerifyListener";
    public static final String OPENAI_SUBMISSION_LISTENER_ID =
            "adminMailInspectionOpenAiSubmissionListener";
    public static final String KIRO_SUBMISSION_LISTENER_ID =
            "adminMailInspectionKiroSubmissionListener";
    public static final String IP2_REGISTRATION_SUBMISSION_LISTENER_ID =
            "adminMailInspectionIp2RegistrationSubmissionListener";
    public static final String IP2_VERIFY_SUBMISSION_LISTENER_ID =
            "adminMailInspectionIp2VerifySubmissionListener";

    private static final Map<MailInspectionType, Route> ROUTES = routes();

    private MailInspectionRabbitNames() {
    }

    public static Set<MailInspectionType> supportedTypes() {
        return ROUTES.keySet();
    }

    public static String queue(MailInspectionType type) {
        return required(type).queue();
    }

    public static String routingKey(MailInspectionType type) {
        return required(type).routingKey();
    }

    public static String listenerId(MailInspectionType type) {
        return required(type).listenerId();
    }

    public static String submissionQueue(MailInspectionType type) {
        return required(type).submissionQueue();
    }

    public static String submissionRoutingKey(MailInspectionType type) {
        return required(type).submissionRoutingKey();
    }

    public static String submissionListenerId(MailInspectionType type) {
        return required(type).submissionListenerId();
    }

    public static String dispatchStateQueue(MailInspectionType type) {
        return required(type).dispatchStateQueue();
    }

    public static String dispatchStateRoutingKey(MailInspectionType type) {
        return required(type).dispatchStateRoutingKey();
    }

    private static Route required(MailInspectionType type) {
        Route route = ROUTES.get(type);
        if (route == null) {
            throw new IllegalArgumentException(
                    "unsupported mail inspection Rabbit route");
        }
        return route;
    }

    private static Map<MailInspectionType, Route> routes() {
        EnumMap<MailInspectionType, Route> routes =
                new EnumMap<>(MailInspectionType.class);
        routes.put(
                MailInspectionType.OPENAI_STATUS,
                new Route(
                        OPENAI_QUEUE,
                        OPENAI_ROUTING_KEY,
                        OPENAI_LISTENER_ID,
                        OPENAI_SUBMISSION_QUEUE,
                        OPENAI_SUBMISSION_ROUTING_KEY,
                        OPENAI_SUBMISSION_LISTENER_ID,
                        OPENAI_DISPATCH_STATE_QUEUE,
                        OPENAI_DISPATCH_STATE_ROUTING_KEY));
        routes.put(
                MailInspectionType.KIRO_STATUS,
                new Route(
                        KIRO_QUEUE,
                        KIRO_ROUTING_KEY,
                        KIRO_LISTENER_ID,
                        KIRO_SUBMISSION_QUEUE,
                        KIRO_SUBMISSION_ROUTING_KEY,
                        KIRO_SUBMISSION_LISTENER_ID,
                        KIRO_DISPATCH_STATE_QUEUE,
                        KIRO_DISPATCH_STATE_ROUTING_KEY));
        routes.put(
                MailInspectionType.IP2LOCATION_REGISTRATION,
                new Route(
                        IP2_REGISTRATION_QUEUE,
                        IP2_REGISTRATION_ROUTING_KEY,
                        IP2_REGISTRATION_LISTENER_ID,
                        IP2_REGISTRATION_SUBMISSION_QUEUE,
                        IP2_REGISTRATION_SUBMISSION_ROUTING_KEY,
                        IP2_REGISTRATION_SUBMISSION_LISTENER_ID,
                        IP2_REGISTRATION_DISPATCH_STATE_QUEUE,
                        IP2_REGISTRATION_DISPATCH_STATE_ROUTING_KEY));
        routes.put(
                MailInspectionType.IP2LOCATION_VERIFY_LINK,
                new Route(
                        IP2_VERIFY_QUEUE,
                        IP2_VERIFY_ROUTING_KEY,
                        IP2_VERIFY_LISTENER_ID,
                        IP2_VERIFY_SUBMISSION_QUEUE,
                        IP2_VERIFY_SUBMISSION_ROUTING_KEY,
                        IP2_VERIFY_SUBMISSION_LISTENER_ID,
                        IP2_VERIFY_DISPATCH_STATE_QUEUE,
                        IP2_VERIFY_DISPATCH_STATE_ROUTING_KEY));
        return Map.copyOf(routes);
    }

    private record Route(
            String queue,
            String routingKey,
            String listenerId,
            String submissionQueue,
            String submissionRoutingKey,
            String submissionListenerId,
            String dispatchStateQueue,
            String dispatchStateRoutingKey) {
    }
}
