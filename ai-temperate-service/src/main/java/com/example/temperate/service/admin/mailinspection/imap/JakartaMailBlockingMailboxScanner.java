package com.example.temperate.service.admin.mailinspection.imap;

import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResultStatus;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.FetchProfile;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.search.SearchTerm;
import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import javax.net.ssl.SSLException;

/**
 * 使用 Jakarta Mail 通过固定 SOCKS 代理执行只读 Outlook IMAPS/XOAUTH2 扫描。
 *
 * <p>该类只在受管虚拟线程中调用；所有 Folder 与 Store 都在当前尝试结束前关闭，正文只在栈内用于
 * 匹配且不会进入返回对象。</p>
 */
public final class JakartaMailBlockingMailboxScanner
        implements BlockingMailboxScanner {

    private final AdminMailInspectionProperties properties;
    private final ImapFolderScanPlanner folderPlanner;
    private final MailBodyExtractor bodyExtractor;

    public JakartaMailBlockingMailboxScanner(
            AdminMailInspectionProperties properties) {
        this.properties = properties;
        this.folderPlanner =
                new ImapFolderScanPlanner(properties.scan().folderOrder());
        this.bodyExtractor =
                new MailBodyExtractor(properties.scan().maxBodyChars());
    }

    @Override
    public MailboxScanOutcome scan(MailboxScanCommand command) {
        Store store = null;
        try {
            store = connect(command);
            return scanStore(store, command);
        } catch (AuthenticationFailedException failure) {
            throw MailboxImapFailureException.permanent(
                    MailInspectionResultStatus.IMAP_AUTHENTICATION_FAILED,
                    "imap_authentication_failed",
                    failure);
        } catch (MessagingException failure) {
            throw classifyMessagingFailure(failure);
        } finally {
            closeStore(store);
        }
    }

    private Store connect(MailboxScanCommand command) throws MessagingException {
        Session session = Session.getInstance(imapProperties());
        Store store = session.getStore("imaps");
        try {
            store.connect(
                    properties.imap().host(),
                    properties.imap().port(),
                    command.email(),
                    command.accessToken());
            return store;
        } catch (MessagingException failure) {
            closeStore(store);
            throw failure;
        }
    }

    private MailboxScanOutcome scanStore(
            Store store,
            MailboxScanCommand command) throws MessagingException {
        Candidate firstCandidate = null;
        int inspectedCandidates = 0;
        for (String folderName : folderPlanner.resolveScanOrder(store)) {
            Folder folder = null;
            try {
                folder = openReadableFolder(store, folderName);
                if (folder == null || folder.getMessageCount() <= 0) {
                    continue;
                }
                Message[] messages = findMessages(folder, command);
                for (int index = messages.length - 1;
                        index >= 0
                                && inspectedCandidates < command.maxCandidates();
                        index--) {
                    Message message = messages[index];
                    String sender = firstSender(message);
                    String subject = trimToNull(message.getSubject());
                    if (!command.matcher().isCandidate(sender, subject)) {
                        continue;
                    }
                    inspectedCandidates++;
                    Candidate candidate = new Candidate(
                            folderName,
                            sender,
                            subject,
                            receivedAt(message));
                    if (firstCandidate == null) {
                        firstCandidate = candidate;
                    }
                    String body = command.matcher().requiresBody()
                            ? extractBody(message)
                            : null;
                    MailboxMessageMatch match =
                            command.matcher().inspect(subject, body);
                    if (match.terminal()) {
                        return success(candidate, match);
                    }
                }
            } catch (IOException failure) {
                throw MailboxImapFailureException.retryable(
                        MailInspectionResultStatus.IMAP_TRANSIENT_EXHAUSTED,
                        "imap_message_read_failed",
                        failure);
            } finally {
                closeFolder(folder);
            }
        }
        if (firstCandidate == null) {
            return MailboxScanOutcome.success(
                    0,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    routeLabel(),
                    null,
                    null,
                    false);
        }
        return success(firstCandidate, MailboxMessageMatch.candidateOnly(false));
    }

    private Message[] findMessages(
            Folder folder,
            MailboxScanCommand command) throws MessagingException {
        Message[] messages = null;
        SearchTerm term = command.matcher().candidateSearchTerm();
        if (term != null) {
            try {
                messages = folder.search(term);
            } catch (MessagingException ignored) {
                // 服务端搜索不可用时回退到最近邮件窗口，仍受 fetchCount 和候选上限约束。
                messages = null;
            }
        }
        int total = folder.getMessageCount();
        if (messages == null || messages.length == 0) {
            if (total <= 0) {
                return new Message[0];
            }
            int start = Math.max(1, total - command.fetchCount() + 1);
            messages = folder.getMessages(start, total);
        } else if (messages.length > command.fetchCount()) {
            int keep = command.fetchCount();
            Message[] limited = new Message[keep];
            System.arraycopy(
                    messages,
                    messages.length - keep,
                    limited,
                    0,
                    keep);
            messages = limited;
        }
        FetchProfile profile = new FetchProfile();
        profile.add(FetchProfile.Item.ENVELOPE);
        folder.fetch(messages, profile);
        return messages;
    }

    private Folder openReadableFolder(Store store, String name)
            throws MessagingException {
        Folder folder = store.getFolder(name);
        if (folder == null || !folder.exists()) {
            return null;
        }
        try {
            folder.open(Folder.READ_ONLY);
            return folder;
        } catch (MessagingException failure) {
            closeFolder(folder);
            throw failure;
        }
    }

    private MailboxScanOutcome success(
            Candidate candidate,
            MailboxMessageMatch match) {
        return MailboxScanOutcome.success(
                0,
                true,
                candidate.folderName(),
                candidate.sender(),
                candidate.subject(),
                candidate.receivedAt(),
                match.evidencePhrase(),
                routeLabel(),
                match.verifyUrl(),
                match.verifyToken(),
                match.malformedVerifyUrl());
    }

    private String extractBody(Message message) throws IOException {
        try {
            return bodyExtractor.extract(message);
        } catch (MessagingException failure) {
            throw MailboxImapFailureException.retryable(
                    MailInspectionResultStatus.IMAP_TRANSIENT_EXHAUSTED,
                    "imap_message_read_failed",
                    failure);
        }
    }

    private Properties imapProperties() {
        Properties values = new Properties();
        values.put("mail.store.protocol", "imaps");
        values.put("mail.imaps.host", properties.imap().host());
        values.put("mail.imaps.port", String.valueOf(properties.imap().port()));
        values.put("mail.imaps.ssl.enable", "true");
        values.put(
                "mail.imaps.connectiontimeout",
                String.valueOf(properties.imap().connectTimeout().toMillis()));
        values.put(
                "mail.imaps.timeout",
                String.valueOf(properties.imap().readTimeout().toMillis()));
        values.put(
                "mail.imaps.writetimeout",
                String.valueOf(properties.imap().readTimeout().toMillis()));
        values.put("mail.imaps.auth.mechanisms", "XOAUTH2");
        values.put("mail.imaps.auth.login.disable", "true");
        values.put("mail.imaps.auth.plain.disable", "true");
        values.put("mail.imaps.socks.host", properties.proxy().host());
        values.put("mail.imaps.socks.port", String.valueOf(properties.proxy().port()));
        return values;
    }

    private String routeLabel() {
        return "SOCKS "
                + properties.proxy().host()
                + ":"
                + properties.proxy().port();
    }

    private static MailboxImapFailureException classifyMessagingFailure(
            MessagingException failure) {
        String normalizedMessage = failure.getMessage() == null
                ? ""
                : failure.getMessage().toLowerCase(Locale.ROOT);
        if (normalizedMessage.contains("access denied")
                || normalizedMessage.contains("not enabled")
                || normalizedMessage.contains("authenticated but not connected")) {
            return MailboxImapFailureException.permanent(
                    MailInspectionResultStatus.IMAP_ACCESS_DENIED,
                    "imap_access_denied",
                    failure);
        }
        if (normalizedMessage.contains("mailbox unavailable")
                || normalizedMessage.contains("mailbox does not exist")) {
            return MailboxImapFailureException.permanent(
                    MailInspectionResultStatus.IMAP_MAILBOX_UNAVAILABLE,
                    "imap_mailbox_unavailable",
                    failure);
        }

        Set<Throwable> visited = new HashSet<>();
        Throwable cursor = failure;
        while (cursor != null && visited.add(cursor)) {
            if (cursor instanceof AuthenticationFailedException) {
                return MailboxImapFailureException.permanent(
                        MailInspectionResultStatus.IMAP_AUTHENTICATION_FAILED,
                        "imap_authentication_failed",
                        failure);
            }
            if (cursor instanceof SocketTimeoutException
                    || cursor instanceof UnknownHostException
                    || cursor instanceof SocketException
                    || cursor instanceof SSLException) {
                return MailboxImapFailureException.retryable(
                        MailInspectionResultStatus.IMAP_NETWORK_EXHAUSTED,
                        "imap_network_failed",
                        failure);
            }
            cursor = cursor.getCause();
        }
        Exception next = failure.getNextException();
        if (next instanceof AuthenticationFailedException) {
            return MailboxImapFailureException.permanent(
                    MailInspectionResultStatus.IMAP_AUTHENTICATION_FAILED,
                    "imap_authentication_failed",
                    failure);
        }
        if (next instanceof SocketTimeoutException
                || next instanceof UnknownHostException
                || next instanceof SocketException
                || next instanceof SSLException) {
            return MailboxImapFailureException.retryable(
                    MailInspectionResultStatus.IMAP_NETWORK_EXHAUSTED,
                    "imap_network_failed",
                    failure);
        }
        return MailboxImapFailureException.retryable(
                MailInspectionResultStatus.IMAP_TRANSIENT_EXHAUSTED,
                "imap_transient_failed",
                failure);
    }

    private static String firstSender(Message message) throws MessagingException {
        if (message == null
                || message.getFrom() == null
                || message.getFrom().length == 0) {
            return null;
        }
        if (message.getFrom()[0] instanceof InternetAddress address) {
            return trimToNull(address.getAddress());
        }
        return trimToNull(message.getFrom()[0].toString());
    }

    private static Instant receivedAt(Message message) throws MessagingException {
        Date received = message.getReceivedDate();
        if (received == null) {
            received = message.getSentDate();
        }
        return received == null ? null : received.toInstant();
    }

    private static void closeFolder(Folder folder) {
        try {
            if (folder != null && folder.isOpen()) {
                folder.close(false);
            }
        } catch (MessagingException ignored) {
            // 关闭失败不能覆盖本次扫描的原始分类，资源仍随 Store 关闭释放。
        }
    }

    private static void closeStore(Store store) {
        try {
            if (store != null && store.isConnected()) {
                store.close();
            }
        } catch (MessagingException ignored) {
            // 关闭失败不传播第三方文本，也不能把已完成业务结果改写为另一种状态。
        }
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 暂存单封候选邮件的公开证据字段，不保存正文或 Message 引用。
     */
    private record Candidate(
            String folderName,
            String sender,
            String subject,
            Instant receivedAt) {
    }
}
