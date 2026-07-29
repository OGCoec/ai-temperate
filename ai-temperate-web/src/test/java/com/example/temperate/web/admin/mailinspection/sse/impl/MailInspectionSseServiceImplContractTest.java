package com.example.temperate.web.admin.mailinspection.sse.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 锁定 SSE 建连快照、暂存通知回放、事件 ID 和 revision 心跳校准的关键顺序。
 */
final class MailInspectionSseServiceImplContractTest {

    @Test
    void snapshotsBeforeActivationAndIdsOnlyCommittedEvents()
            throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/example/temperate/web/admin/"
                        + "mailinspection/sse/impl/"
                        + "MailInspectionSseServiceImpl.java"));

        int snapshot = source.indexOf(
                "sendSnapshot(registration, snapshot)");
        int activation = source.indexOf(
                "registration.activate(snapshot.revision())");
        int replay = source.indexOf(
                "buffered.forEach(event -> onEvent(registration, event))");
        assertThat(snapshot).isGreaterThanOrEqualTo(0);
        assertThat(activation).isGreaterThan(snapshot);
        assertThat(replay).isGreaterThan(activation);
        assertThat(source).contains(
                "\"snapshot-meta\"",
                "\"result-batch\"",
                "\"sync-complete\"",
                "\"progress\"",
                "\"result\"",
                "\"status\"",
                "\"terminal\"",
                "\"heartbeat\"",
                "builder.id(Long.toString(revision))",
                "document.revision()",
                "> registration.lastRevision()",
                "jobStore.findSnapshotMetas(byJob.keySet())",
                "snapshot.status().terminal()",
                "sendNewResults(registration, snapshot)",
                "registration.markResultSent(result.lineNumber())",
                "registration.resetResultCursor()");
        assertThat(source.indexOf(
                "\"snapshot-meta\"",
                source.indexOf("private void sendSnapshot")))
                .isLessThan(source.indexOf(
                        "false",
                        source.indexOf("\"snapshot-meta\"")));
        assertThat(source.indexOf(
                "\"sync-complete\"",
                source.indexOf("private void sendSnapshot")))
                .isLessThan(source.indexOf(
                        "true",
                        source.indexOf("\"sync-complete\"")));
    }
}
