package io.github.jiangood.docker.admin.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.SaveImageCmd;
import io.github.jiangood.docker.admin.dao.ImageDownloadRecordRepository;
import io.github.jiangood.docker.admin.dto.ImageDownloadCleanResult;
import io.github.jiangood.docker.admin.dto.ImageDownloadRequest;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.entity.ImageDownloadRecord;
import io.github.jiangood.docker.sdk.engine.DockerClientManager;
import io.github.jiangood.openadmin.modules.task.entity.SysTaskLog;
import io.github.jiangood.openadmin.modules.task.registry.TaskLogRegistry;
import io.github.jiangood.openadmin.modules.task.service.SysTaskLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageDownloadServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    ImageDownloadRecordRepository recordRepository;

    @Mock
    HostService hostService;

    @Mock
    SysTaskLogService taskLogService;

    @Mock
    TaskLogRegistry taskLogRegistry;

    @Mock
    DockerClientManager dockerClientManager;

    @Mock
    PlatformTransactionManager transactionManager;

    @InjectMocks
    ImageDownloadService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "logPath", tempDir.resolve("logs").toString());
    }

    @Test
    void startPersistsRecordBeforeRegisteringTask() {
        Host host = new Host();
        host.setId("host-1");
        host.setName("构建节点");
        ImageDownloadRequest request = new ImageDownloadRequest();
        request.setImage(" nginx:1.27 ");
        request.setHostId("host-1");

        when(hostService.findById("host-1")).thenReturn(Optional.of(host));
        when(taskLogService.currentTriggerUser()).thenReturn("alice");
        when(recordRepository.saveAndFlush(any(ImageDownloadRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        String logId = service.start(request);

        ArgumentCaptor<ImageDownloadRecord> captor = ArgumentCaptor.forClass(ImageDownloadRecord.class);
        verify(recordRepository).saveAndFlush(captor.capture());
        ImageDownloadRecord saved = captor.getValue();
        assertEquals(logId, saved.getLogId());
        assertEquals("nginx:1.27", saved.getImage());
        assertEquals("host-1", saved.getHostId());
        assertEquals("构建节点", saved.getHostName());
        assertEquals("alice", saved.getTriggerUser());
        assertEquals(ImageDownloadRecord.RUNNING, saved.getStatus());
        assertFalse(saved.getReady());
        verify(taskLogService).start(eq(logId), eq("image-download"), eq("下载镜像 nginx:1.27"),
                eq("image-download:download"), eq("alice"));
        verify(taskLogRegistry).start(logId);
    }

    @Test
    void batchCleanSkipsRunningCleanedAndInvalidRecords() throws Exception {
        ImageDownloadRecord success = record("image-download-success", ImageDownloadRecord.SUCCESS);
        ImageDownloadRecord failed = record("image-download-failed", ImageDownloadRecord.FAILED);
        ImageDownloadRecord running = record("image-download-running", ImageDownloadRecord.RUNNING);
        ImageDownloadRecord cleaned = record("image-download-cleaned", ImageDownloadRecord.CLEANED);
        archive(success);
        archive(failed);
        archive(running);

        when(taskLogService.currentTriggerUser()).thenReturn("bob");
        when(recordRepository.findByLogIdForUpdate(success.getLogId())).thenReturn(Optional.of(success));
        when(recordRepository.findByLogIdForUpdate(failed.getLogId())).thenReturn(Optional.of(failed));
        when(recordRepository.findByLogIdForUpdate(running.getLogId())).thenReturn(Optional.of(running));
        when(recordRepository.findByLogIdForUpdate(cleaned.getLogId())).thenReturn(Optional.of(cleaned));

        ImageDownloadCleanResult result = service.clean(List.of(
                success.getLogId(), success.getLogId(), failed.getLogId(),
                running.getLogId(), cleaned.getLogId(), "../bad"));

        assertEquals(2, result.getCleaned());
        assertEquals(3, result.getSkipped());
        assertEquals(ImageDownloadRecord.CLEANED, success.getStatus());
        assertEquals(ImageDownloadRecord.CLEANED, failed.getStatus());
        assertEquals("bob", success.getCleanedBy());
        assertFalse(success.getReady());
        assertEquals(ImageDownloadRecord.RUNNING, running.getStatus());
        assertTrue(Files.exists(service.localTarDir(running.getLogId()).toPath()));
        assertFalse(Files.exists(service.localTarDir(success.getLogId()).toPath()));
        assertFalse(Files.exists(service.localTarDir(failed.getLogId()).toPath()));
    }

    @Test
    void batchCleanSkipsArchiveBeingDownloaded() throws Exception {
        ImageDownloadRecord record = record("image-download-downloading", ImageDownloadRecord.SUCCESS);
        archive(record);
        when(taskLogService.currentTriggerUser()).thenReturn("bob");
        when(recordRepository.findByLogIdForUpdate(record.getLogId())).thenReturn(Optional.of(record));
        activeDownloads().put(record.getLogId(), new AtomicInteger(1));

        ImageDownloadCleanResult result = service.clean(List.of(record.getLogId()));

        assertEquals(0, result.getCleaned());
        assertEquals(1, result.getSkipped());
        assertEquals(ImageDownloadRecord.SUCCESS, record.getStatus());
        assertTrue(Files.exists(service.localTarDir(record.getLogId()).toPath()));
    }

    @Test
    void downloadingKeepsArchiveForOtherUsers() throws Exception {
        ImageDownloadRecord record = record("image-download-shared", ImageDownloadRecord.SUCCESS);
        archive(record);
        when(recordRepository.findByLogId(record.getLogId())).thenReturn(Optional.of(record));

        ByteArrayOutputStream first = new ByteArrayOutputStream();
        ByteArrayOutputStream second = new ByteArrayOutputStream();
        service.reserveDownload(record.getLogId());
        service.reserveDownload(record.getLogId());
        try {
            service.copyTarTo(record.getLogId(), first);
            service.copyTarTo(record.getLogId(), second);

            assertEquals("archive", first.toString(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("archive", second.toString(java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(Files.exists(service.localTarDir(record.getLogId()).toPath()));
            assertEquals(2, activeDownloads().get(record.getLogId()).get());
        } finally {
            service.releaseDownload(record.getLogId());
            service.releaseDownload(record.getLogId());
        }
        assertTrue(activeDownloads().isEmpty());
    }

    @Test
    void readyTaskRejectsMissingArchive() {
        ImageDownloadRecord record = record("image-download-missing", ImageDownloadRecord.SUCCESS);
        when(recordRepository.findByLogId(record.getLogId())).thenReturn(Optional.of(record));

        assertThrows(IllegalStateException.class, () -> service.reserveDownload(record.getLogId()));
        assertTrue(activeDownloads().isEmpty());
    }

    @Test
    void readyTaskRejectsSymbolicArchive() throws Exception {
        Path dir = service.localTarDir("image-download-symlink-file").toPath();
        Files.createDirectories(dir);
        Path outside = tempDir.resolve("outside.tar");
        Files.writeString(outside, "outside");
        Path link = dir.resolve("nginx-1.27.tar");
        Files.createSymbolicLink(link, outside);
        ImageDownloadRecord record = record("image-download-symlink-file", ImageDownloadRecord.SUCCESS);
        record.setPath(link.toFile().getAbsolutePath());
        when(recordRepository.findByLogId(record.getLogId())).thenReturn(Optional.of(record));

        assertThrows(IllegalStateException.class, () -> service.reserveDownload(record.getLogId()));

        assertEquals("outside", Files.readString(outside));
        assertTrue(Files.isSymbolicLink(link));
        assertTrue(activeDownloads().isEmpty());
    }

    @Test
    void cleanRemovesSymlinkWithoutDeletingItsTarget() throws Exception {
        Path outside = tempDir.resolve("outside-dir");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("keep.tar"), "keep");
        Files.createDirectories(service.tarRoot().toPath());
        Path link = service.localTarDir("image-download-symlink-dir").toPath();
        Files.createSymbolicLink(link, outside);
        ImageDownloadRecord record = record("image-download-symlink-dir", ImageDownloadRecord.SUCCESS);
        when(taskLogService.currentTriggerUser()).thenReturn("bob");
        when(recordRepository.findByLogIdForUpdate(record.getLogId())).thenReturn(Optional.of(record));

        ImageDownloadCleanResult result = service.clean(List.of(record.getLogId()));

        assertEquals(1, result.getCleaned());
        assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS));
        assertEquals("keep", Files.readString(outside.resolve("keep.tar")));
        assertEquals(ImageDownloadRecord.CLEANED, record.getStatus());
    }

    @Test
    void restartMarksRunningFailedAndMissingArchiveCleaned() throws Exception {
        ImageDownloadRecord running = record("image-download-interrupted", ImageDownloadRecord.RUNNING);
        ImageDownloadRecord missing = record("image-download-gone", ImageDownloadRecord.SUCCESS);
        ImageDownloadRecord present = record("image-download-present", ImageDownloadRecord.SUCCESS);
        archive(running);
        archive(present);
        when(recordRepository.findByStatus(ImageDownloadRecord.RUNNING)).thenReturn(List.of(running));
        when(recordRepository.findByStatus(ImageDownloadRecord.CLEANING)).thenReturn(List.of());
        when(recordRepository.findByStatus(ImageDownloadRecord.FAILED)).thenReturn(List.of());
        when(recordRepository.findByStatus(ImageDownloadRecord.SUCCESS)).thenReturn(List.of(missing, present));
        when(recordRepository.findByLogId(present.getLogId())).thenReturn(Optional.of(present));

        int interrupted = service.recoverAfterRestart();

        assertEquals(1, interrupted);
        assertEquals(ImageDownloadRecord.FAILED, running.getStatus());
        assertEquals("平台重启，任务中断", running.getError());
        assertFalse(Files.exists(service.localTarDir(running.getLogId()).toPath()));
        assertEquals(ImageDownloadRecord.CLEANED, missing.getStatus());
        assertEquals("SYSTEM", missing.getCleanedBy());
        assertEquals(ImageDownloadRecord.SUCCESS, present.getStatus());
        assertNull(present.getCleanedTime());
    }

    @Test
    void restartFinishesInterruptedCleaning() throws Exception {
        ImageDownloadRecord record = record("image-download-cleaning", ImageDownloadRecord.CLEANING);
        archive(record);
        when(recordRepository.findByStatus(ImageDownloadRecord.RUNNING)).thenReturn(List.of());
        when(recordRepository.findByStatus(ImageDownloadRecord.CLEANING)).thenReturn(List.of(record));
        when(recordRepository.findByStatus(ImageDownloadRecord.FAILED)).thenReturn(List.of());
        when(recordRepository.findByStatus(ImageDownloadRecord.SUCCESS)).thenReturn(List.of());

        service.recoverAfterRestart();

        assertEquals(ImageDownloadRecord.CLEANED, record.getStatus());
        assertEquals("SYSTEM", record.getCleanedBy());
        assertFalse(Files.exists(service.localTarDir(record.getLogId()).toPath()));
    }

    @Test
    void restartImportsLegacyArchivesWithoutDatabaseRecords() throws Exception {
        Path dir = service.localTarDir("image-download-legacy").toPath();
        Files.createDirectories(dir);
        Path archive = dir.resolve("nginx-1.27.tar");
        Files.writeString(archive, "legacy");
        when(recordRepository.findByStatus(ImageDownloadRecord.RUNNING)).thenReturn(List.of());
        when(recordRepository.findByStatus(ImageDownloadRecord.CLEANING)).thenReturn(List.of());
        when(recordRepository.findByStatus(ImageDownloadRecord.FAILED)).thenReturn(List.of());
        when(recordRepository.findByStatus(ImageDownloadRecord.SUCCESS)).thenReturn(List.of());
        when(recordRepository.findByLogId("image-download-legacy")).thenReturn(Optional.empty());
        SysTaskLog task = new SysTaskLog();
        task.setLogId("image-download-legacy");
        task.setType(SysTaskLog.TYPE_IMAGE_DOWNLOAD);
        task.setTitle("下载镜像 nginx:1.27");
        task.setStatus(SysTaskLog.STATUS_SUCCESS);
        task.setTriggerUser("alice");
        when(taskLogService.findByLogId("image-download-legacy")).thenReturn(task);

        service.recoverAfterRestart();

        ArgumentCaptor<ImageDownloadRecord> captor = ArgumentCaptor.forClass(ImageDownloadRecord.class);
        verify(recordRepository).saveAndFlush(captor.capture());
        ImageDownloadRecord imported = captor.getValue();
        assertEquals("image-download-legacy", imported.getLogId());
        assertEquals("nginx:1.27", imported.getImage());
        assertEquals("alice", imported.getTriggerUser());
        assertEquals(archive.toFile().getAbsolutePath(), imported.getPath());
        assertEquals(ImageDownloadRecord.SUCCESS, imported.getStatus());
        assertTrue(imported.getReady());
    }

    @Test
    void restartDoesNotPublishUnconfirmedLegacyArchive() throws Exception {
        Path dir = service.localTarDir("image-download-unconfirmed").toPath();
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("nginx-1.27.tar"), "partial");
        when(recordRepository.findByStatus(ImageDownloadRecord.RUNNING)).thenReturn(List.of());
        when(recordRepository.findByStatus(ImageDownloadRecord.CLEANING)).thenReturn(List.of());
        when(recordRepository.findByStatus(ImageDownloadRecord.FAILED)).thenReturn(List.of());
        when(recordRepository.findByStatus(ImageDownloadRecord.SUCCESS)).thenReturn(List.of());
        when(recordRepository.findByLogId("image-download-unconfirmed")).thenReturn(Optional.empty());

        service.recoverAfterRestart();

        ArgumentCaptor<ImageDownloadRecord> captor = ArgumentCaptor.forClass(ImageDownloadRecord.class);
        verify(recordRepository).saveAndFlush(captor.capture());
        ImageDownloadRecord imported = captor.getValue();
        assertEquals(ImageDownloadRecord.FAILED, imported.getStatus());
        assertFalse(imported.getReady());
        assertTrue(imported.getError().contains("未确认成功"));
    }

    @Test
    void saveConflictDoesNotDeleteExistingArchive() throws Exception {
        Host host = new Host();
        host.setId("host-1");
        host.setName("构建节点");
        String logId = "image-download-conflict";
        Path dir = service.localTarDir(logId).toPath();
        Files.createDirectories(dir);
        Path existing = dir.resolve("image-nginx127.tar");
        Files.writeString(existing, "keep");

        DockerClient client = mock(DockerClient.class);
        SaveImageCmd save = mock(SaveImageCmd.class);
        when(hostService.findById("host-1")).thenReturn(Optional.of(host));
        when(dockerClientManager.getClient(host)).thenReturn(client);
        when(client.saveImageCmd("nginx:1.27")).thenReturn(save);
        when(save.exec()).thenReturn(new ByteArrayInputStream("new".getBytes(StandardCharsets.UTF_8)));

        assertThrows(FileAlreadyExistsException.class,
                () -> service.saveToHost(logId, "host-1", "nginx:1.27", false, null));

        assertEquals("keep", Files.readString(existing));
    }

    @Test
    void hostExportCleanupDoesNotTouchDownloadArchives() throws Exception {
        Path downloadDir = service.localTarDir("image-download-keep").toPath();
        Path oldExportDir = service.localTarDir("host-export-old").toPath();
        Path runningExportDir = service.localTarDir("host-export-running").toPath();
        Files.createDirectories(downloadDir);
        Files.createDirectories(oldExportDir);
        Files.createDirectories(runningExportDir);
        Path outside = tempDir.resolve("export-link-target");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("keep.tar"), "keep");
        Path linkedExport = service.localTarDir("host-export-link").toPath();
        Files.createSymbolicLink(linkedExport, outside);

        service.cleanPreviousTars("host-export-", Set.of("host-export-running"));

        assertTrue(Files.exists(downloadDir));
        assertFalse(Files.exists(oldExportDir));
        assertTrue(Files.exists(runningExportDir));
        assertTrue(Files.isSymbolicLink(linkedExport));
        assertEquals("keep", Files.readString(outside.resolve("keep.tar")));
    }

    private ImageDownloadRecord record(String logId, String status) {
        ImageDownloadRecord record = new ImageDownloadRecord();
        record.setLogId(logId);
        record.setImage("nginx:1.27");
        record.setHostId("host-1");
        record.setHostName("构建节点");
        record.setStatus(status);
        File archive = new File(service.localTarDir(logId), "nginx-1.27.tar");
        record.setFileName(archive.getName());
        record.setPath(archive.getAbsolutePath());
        record.setSize(7L);
        return record;
    }

    private void archive(ImageDownloadRecord record) throws Exception {
        Path target = Path.of(record.getPath());
        Files.createDirectories(target.getParent());
        Files.writeString(target, "archive");
    }

    @SuppressWarnings("unchecked")
    private Map<String, AtomicInteger> activeDownloads() {
        return (Map<String, AtomicInteger>) ReflectionTestUtils.getField(service, "activeDownloads");
    }

}
