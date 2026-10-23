package io.github.jiangood.docker.admin.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemConfigServiceTest {

    @Test
    void recipientsSupportCommaSemicolonAndWhitespace() {
        assertEquals(List.of("dev@example.com", "ops@example.com", "a@b.cn"),
                SystemConfigService.splitRecipients("dev@example.com, ops@example.com；a@b.cn"));
    }

    @Test
    void blankRecipientsReturnEmptyList() {
        assertTrue(SystemConfigService.splitRecipients(null).isEmpty());
        assertTrue(SystemConfigService.splitRecipients("   ").isEmpty());
    }
}
