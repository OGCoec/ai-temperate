package com.example.temperate.service.user.aiconversation.generation;

import static org.assertj.core.api.Assertions.assertThat;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * 使用三节点 RabbitMQ 隔离集群验证 Quorum Leader 停止后未确认消息重投和持久化结算入口可继续消费。
 *
 * <p>该门禁允许 At Least Once 重复投递；资金幂等由独立 PostgreSQL 事务测试验证，本测试不宣称 Exactly Once。</p>
 */
@EnabledIfEnvironmentVariable(named = "AIT_TEST_RABBIT_QUORUM_IMAGE", matches = ".+")
final class RabbitAiConversationGenerationThreeNodeQuorumIntegrationTest {

    private static final String USER = "generation-quorum-test";
    private static final String PASSWORD = "generation-quorum-password";
    private static final String COOKIE = "generation-quorum-cookie-20260801";

    @Test
    void unackedPersistentMessageIsRedeliveredAfterQueueLeaderStops() throws Exception {
        DockerImageName image = DockerImageName.parse(
                System.getenv("AIT_TEST_RABBIT_QUORUM_IMAGE"));
        try (Network network = Network.newNetwork();
                GenericContainer<?> nodeA = node(image, network, "rabbit-a");
                GenericContainer<?> nodeB = node(image, network, "rabbit-b");
                GenericContainer<?> nodeC = node(image, network, "rabbit-c")) {
            nodeA.start();
            nodeB.start();
            nodeC.start();
            join(nodeB, "rabbit@rabbit-a");
            join(nodeC, "rabbit@rabbit-a");

            String queue = "ait.test.generation.quorum." + UUID.randomUUID();
            GetResponse firstDelivery;
            Connection firstConnection = connection(nodeA);
            Channel firstChannel = firstConnection.createChannel();
            firstChannel.queueDeclare(
                    queue,
                    true,
                    false,
                    false,
                    Map.of(
                            "x-queue-type", "quorum",
                            "x-quorum-initial-group-size", 3));
            firstChannel.confirmSelect();
            firstChannel.basicPublish(
                    "",
                    queue,
                    new AMQP.BasicProperties.Builder().deliveryMode(2).build(),
                    "terminal-once".getBytes(StandardCharsets.UTF_8));
            firstChannel.waitForConfirmsOrDie(5_000L);
            firstDelivery = waitForMessage(firstChannel, queue, Duration.ofSeconds(3));
            assertThat(firstDelivery).isNotNull();

            String leader = leader(nodeA, queue);
            GenericContainer<?> leaderNode = nodeByName(leader, nodeA, nodeB, nodeC);
            GenericContainer<?> survivor = List.of(nodeA, nodeB, nodeC).stream()
                    .filter(candidate -> candidate != leaderNode)
                    .findFirst()
                    .orElseThrow();
            leaderNode.execInContainer("rabbitmqctl", "stop_app");
            firstConnection.abort();

            GetResponse redelivery = waitForRedelivery(survivor, queue, Duration.ofSeconds(20));
            assertThat(redelivery).isNotNull();
            assertThat(redelivery.getEnvelope().isRedeliver()).isTrue();
            assertThat(redelivery.getProps().getDeliveryMode()).isEqualTo(2);

            leaderNode.execInContainer("rabbitmqctl", "start_app");
            waitForClusterSize(survivor, 3, Duration.ofSeconds(20));
            try (Connection verification = connection(survivor);
                    Channel channel = verification.createChannel()) {
                assertThat(channel.queueDeclarePassive(queue).getMessageCount()).isZero();
            }
        }
    }

    private static GenericContainer<?> node(
            DockerImageName image,
            Network network,
            String hostName) {
        return new GenericContainer<>(image)
                .withNetwork(network)
                .withNetworkAliases(hostName)
                .withCreateContainerCmdModifier(command -> command.withHostName(hostName))
                .withEnv("RABBITMQ_NODENAME", "rabbit@" + hostName)
                .withEnv("RABBITMQ_ERLANG_COOKIE", COOKIE)
                .withEnv("RABBITMQ_DEFAULT_USER", USER)
                .withEnv("RABBITMQ_DEFAULT_PASS", PASSWORD)
                .withExposedPorts(5672)
                .waitingFor(Wait.forLogMessage(".*Server startup complete;.*\\n", 1))
                .withStartupTimeout(Duration.ofMinutes(1));
    }

    private static void join(GenericContainer<?> node, String seedNode) throws Exception {
        assertThat(node.execInContainer("rabbitmqctl", "stop_app").getExitCode()).isZero();
        assertThat(node.execInContainer("rabbitmqctl", "reset").getExitCode()).isZero();
        assertThat(node.execInContainer("rabbitmqctl", "join_cluster", seedNode).getExitCode())
                .isZero();
        assertThat(node.execInContainer("rabbitmqctl", "start_app").getExitCode()).isZero();
    }

    private static String leader(GenericContainer<?> node, String queue) throws Exception {
        var status = node.execInContainer("rabbitmq-queues", "quorum_status", queue);
        assertThat(status.getExitCode()).isZero();
        return status.getStdout().lines()
                .filter(line -> line.contains("leader") && line.contains("rabbit@rabbit-"))
                .map(line -> {
                    int start = line.indexOf("rabbit@rabbit-");
                    int end = line.indexOf(' ', start);
                    return end < 0 ? line.substring(start) : line.substring(start, end);
                })
                .map(value -> value.replace("│", "").trim())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "RabbitMQ quorum leader was not reported: " + status.getStdout()));
    }

    private static GenericContainer<?> nodeByName(
            String leader,
            GenericContainer<?> nodeA,
            GenericContainer<?> nodeB,
            GenericContainer<?> nodeC) {
        return switch (leader) {
            case "rabbit@rabbit-a" -> nodeA;
            case "rabbit@rabbit-b" -> nodeB;
            case "rabbit@rabbit-c" -> nodeC;
            default -> throw new IllegalStateException("Unknown RabbitMQ quorum leader: " + leader);
        };
    }

    private static GetResponse waitForRedelivery(
            GenericContainer<?> node,
            String queue,
            Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        Exception lastFailure = null;
        while (System.nanoTime() < deadline) {
            try (Connection connection = connection(node);
                    Channel channel = connection.createChannel()) {
                GetResponse response = channel.basicGet(queue, false);
                if (response != null) {
                    channel.basicAck(response.getEnvelope().getDeliveryTag(), false);
                    return response;
                }
            } catch (Exception failure) {
                lastFailure = failure;
            }
            Thread.sleep(200L);
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        return null;
    }

    private static GetResponse waitForMessage(
            Channel channel,
            String queue,
            Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            GetResponse response = channel.basicGet(queue, false);
            if (response != null) {
                return response;
            }
            Thread.sleep(20L);
        } while (System.nanoTime() < deadline);
        return null;
    }

    private static void waitForClusterSize(
            GenericContainer<?> node,
            int expectedNodes,
            Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            var status = node.execInContainer("rabbitmqctl", "cluster_status", "--formatter", "json");
            if (status.getExitCode() == 0
                    && status.getStdout().split("rabbit@rabbit-").length - 1 >= expectedNodes) {
                return;
            }
            Thread.sleep(250L);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("RabbitMQ cluster did not restore all nodes.");
    }

    private static Connection connection(GenericContainer<?> node) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(node.getHost());
        factory.setPort(node.getMappedPort(5672));
        factory.setUsername(USER);
        factory.setPassword(PASSWORD);
        factory.setConnectionTimeout(2_000);
        factory.setHandshakeTimeout(2_000);
        return factory.newConnection("ai-generation-quorum-gate");
    }
}
