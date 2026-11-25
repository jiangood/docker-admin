package io.github.jiangood.docker.admin.service;

import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.openadmin.modules.task.registry.TaskLogRegistry;
import io.github.jiangood.openadmin.modules.task.service.SysTaskLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HostImageExportServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    SysTaskLogService taskLogService;

    @Mock
    TaskLogRegistry taskLogRegistry;

    @Mock
    ImageDownloadService imageDownloadService;

    @InjectMocks
    HostImageExportService service;

    @Test
    void concurrentDownloadsDeleteArchiveOnlyAfterLastLease() throws Exception {
        Host host = new Host();
        host.setId("host-1");
        host.setName("构建节点");
        Path archive = tempDir.resolve("nginx.tar");
        Files.writeString(archive, "archive");
        ImageDownloadService.TarResult result = new ImageDownloadService.TarResult("nginx.tar", archive.toString(), 7L);
        when(taskLogService.currentTriggerUser()).thenReturn("alice");
        when(imageDownloadService.saveToHost(any(), eq("host-1"), eq("nginx:1.27"), eq(false), any()))
                .thenReturn(result);

        String logId = service.register(host, "nginx:1.27");
        service.export(logId, "host-1", "nginx:1.27");
        assertNotNull(service.taskView("host-1", "nginx:1.27"));

        service.reserveDownload(logId);
        service.reserveDownload(logId);
        service.releaseDownload(logId);
        service.deleteAfterDownload(logId);
        verify(imageDownloadService, never()).deleteArchiveDir(logId);
        assertNotNull(service.taskView("host-1", "nginx:1.27"));

        service.releaseDownload(logId);
        service.deleteAfterDownload(logId);
        verify(imageDownloadService).deleteArchiveDir(logId);
        assertNull(service.taskView("host-1", "nginx:1.27"));
    }

    @Test
    void reRegisterDoesNotInvalidateActiveExportDownload() throws Exception {
        Host host = new Host();
        host.setId("host-1");
        host.setName("构建节点");
        Path archive = tempDir.resolve("nginx.tar");
        Files.writeString(archive, "archive");
        ImageDownloadService.TarResult result = new ImageDownloadService.TarResult("nginx.tar", archive.toString(), 7L);
        when(taskLogService.currentTriggerUser()).thenReturn("alice");
        when(imageDownloadService.saveToHost(any(), eq("host-1"), eq("nginx:1.27"), eq(false), any()))
                .thenReturn(result);

        String oldLogId = service.register(host, "nginx:1.27");
        service.export(oldLogId, "host-1", "nginx:1.27");
        service.reserveDownload(oldLogId);
        String newLogId = service.register(host, "nginx:1.27");

        assertNotNull(service.readyTask(oldLogId));
        service.releaseDownload(oldLogId);
        service.deleteAfterDownload(oldLogId);

        verify(imageDownloadService).deleteArchiveDir(oldLogId);
        assertNotNull(service.taskView("host-1", "nginx:1.27"));
        assertEquals(newLogId, service.taskView("host-1", "nginx:1.27").getLogId());
    }

}
