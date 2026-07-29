package com.example.temperate.service.admin.mailinspection.imap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.mail.Folder;
import jakarta.mail.Store;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证 IMAP 文件夹发现始终把垃圾邮件和收件箱放在其他可读文件夹之前。
 */
final class ImapFolderScanPlannerTest {

    @Test
    void ordersJunkThenInboxThenOtherFolders() throws Exception {
        Store store = mock(Store.class);
        Folder root = mock(Folder.class);
        Folder archive = folder("Archive");
        Folder inbox = folder("INBOX");
        Folder junk = folder("Junk Email");
        when(root.getFullName()).thenReturn("");
        when(root.list()).thenReturn(new Folder[] {archive, inbox, junk});
        when(store.getDefaultFolder()).thenReturn(root);

        List<String> order = new ImapFolderScanPlanner(
                        List.of("Junk Email", "INBOX"))
                .resolveScanOrder(store);

        assertThat(order).containsExactly("Junk Email", "INBOX", "Archive");
    }

    private static Folder folder(String name) throws Exception {
        Folder folder = mock(Folder.class);
        when(folder.getFullName()).thenReturn(name);
        when(folder.getType()).thenReturn(Folder.HOLDS_MESSAGES);
        when(folder.list()).thenReturn(new Folder[0]);
        return folder;
    }
}
