package io.github.jiangood.docker.admin.service;

import io.github.jiangood.docker.admin.dao.ProxyRepository;
import io.github.jiangood.docker.admin.job.DatabaseBackupJobParamProvider;
import io.github.jiangood.openadmin.modules.config.service.SysConfigService;
import io.github.jiangood.openadmin.modules.mail.MailAttachment;
import io.github.jiangood.openadmin.modules.mail.MailConfigKeys;
import io.github.jiangood.openadmin.modules.mail.MailMessage;
import io.github.jiangood.openadmin.modules.mail.service.MailService;
import io.github.jiangood.openadmin.util.BusinessException;
import io.github.jiangood.openadmin.util.field.Field;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 数据库备份：只导关键表的 SQL，保留本地副本后通过框架邮件服务发送。 */
class DatabaseBackupServiceTest {

    @TempDir
    Path tempDir;

    private final MailService mailService = mock(MailService.class);
    private final SysConfigService sysConfigService = mock(SysConfigService.class);
    private final ProxyRepository proxyRepository = mock(ProxyRepository.class);

    @Test
    void backsUpImportantTablesAsSqlAndKeepsLocalCopy() throws Exception {
        JdbcDataSource dataSource = newDataSource();
        seed(dataSource);
        when(mailService.isConfigured()).thenReturn(true);
        when(sysConfigService.getList(MailConfigKeys.DEFAULT_TO)).thenReturn(List.of("ops@example.com"));

        // 附件必须在 send 调用期间校验
        doAnswer(invocation -> {
            MailMessage message = invocation.getArgument(0);
            assertEquals(List.of("ops@example.com"), message.getTo());
            assertTrue(message.getSubject().startsWith("[容器管理平台] 重要表备份"));
            assertEquals(1, message.getAttachments().size());

            MailAttachment attachment = message.getAttachments().get(0);
            assertTrue(attachment.name().endsWith(".sql.gz"));
            assertTrue(attachment.file().isFile());
            assertTrue(attachment.file().length() > 0);

            // 附件必须是 SQL（建表 + INSERT），而不是整库 zip
            String sql;
            try (InputStream in = new GZIPInputStream(new FileInputStream(attachment.file()))) {
                sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            assertTrue(sql.contains("T_PROJECT"));
            assertTrue(sql.contains("'hello'"));
            return null;
        }).when(mailService).send(any());

        DatabaseBackupService service = service(dataSource);
        String summary = service.backupAndSend(null);

        assertTrue(summary.contains("ops@example.com"));
        assertTrue(summary.contains("db-backup"));
        verify(mailService).send(any());

        // 本地副本保留，邮件发完也不删除
        try (var files = Files.list(tempDir.resolve("db-backup"))) {
            List<Path> backups = files.toList();
            assertEquals(1, backups.size());
            assertTrue(backups.get(0).getFileName().toString().endsWith(".sql.gz"));
            assertTrue(Files.size(backups.get(0)) > 0);
        }
    }

    @Test
    void keepsLocalCopyWhenMailFails() throws Exception {
        JdbcDataSource dataSource = newDataSource();
        seed(dataSource);
        when(mailService.isConfigured()).thenReturn(true);
        when(sysConfigService.getList(MailConfigKeys.DEFAULT_TO)).thenReturn(List.of("ops@example.com"));
        doThrow(new RuntimeException("smtp down")).when(mailService).send(any());

        DatabaseBackupService service = service(dataSource);
        BusinessException error = assertThrows(BusinessException.class, () -> service.backupAndSend(null));

        assertTrue(error.getMessage().contains("备份已保存到"));
        try (var files = Files.list(tempDir.resolve("db-backup"))) {
            List<Path> backups = files.toList();
            assertEquals(1, backups.size());
            assertTrue(Files.size(backups.get(0)) > 0);
        }
    }

    @Test
    void rejectsWhenDefaultRecipientMissing() {
        JdbcDataSource dataSource = newDataSource();
        when(mailService.isConfigured()).thenReturn(true);
        when(sysConfigService.getList(MailConfigKeys.DEFAULT_TO)).thenReturn(List.of());

        DatabaseBackupService service = service(dataSource);

        assertThrows(BusinessException.class, () -> service.backupAndSend(null));
        verify(mailService, never()).send(any());
    }

    @Test
    void jobParamProvidesProxySelect() {
        Field field = new DatabaseBackupJobParamProvider().getFields(null, Map.of()).get(0);

        assertEquals("proxyId", field.getName());
        assertEquals("发送邮件代理", field.getLabel());
        assertEquals("select", field.getValueType());
        assertEquals("admin/proxy/options", field.getComponentProps().get("url"));
    }

    private DatabaseBackupService service(JdbcDataSource dataSource) {
        DatabaseBackupService service = new DatabaseBackupService(
                dataSource, mailService, sysConfigService, proxyRepository);
        ReflectionTestUtils.setField(service, "logPath", tempDir.resolve("logs").toString());
        return service;
    }

    private JdbcDataSource newDataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:file:" + tempDir.resolve("database").toString().replace('\\', '/')
                + ";MODE=MySQL");
        dataSource.setUser("sa");
        return dataSource;
    }

    private void seed(JdbcDataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("create table t_project(id int primary key, name varchar(32))");
            statement.execute("insert into t_project values(1, 'hello')");
        }
    }
}
