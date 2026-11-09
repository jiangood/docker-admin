package io.github.jiangood.docker.admin.job;

import io.github.jiangood.openadmin.framework.config.SystemProperties;
import io.github.jiangood.openadmin.modules.job.entity.JobLogAggregation;
import io.github.jiangood.openadmin.modules.job.entity.SysJob;
import io.github.jiangood.openadmin.modules.job.repository.SysJobRepository;
import io.github.jiangood.openadmin.modules.job.service.SysJobService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 内建定时任务的登记逻辑：重点覆盖「框架先补建了无 cron 的空壳行」这一顺序问题，
 * 否则全新安装后任务会一直是禁用状态。
 */
class BuiltinJobRegistrarTest {

    private static final String JOB_CLASS = "io.github.jiangood.docker.admin.job.AppScheduledUpdateJob";
    private static final String NAME = "应用定时更新检查";
    private static final String CRON = "0 * * * * ?";

    private final SysJobService sysJobService = mock(SysJobService.class);
    private final SysJobRepository sysJobRepository = mock(SysJobRepository.class);
    private final SystemProperties systemProperties = mock(SystemProperties.class);

    @Test
    void createsEnabledJobWhenMissing() throws Exception {
        when(sysJobRepository.findAll()).thenReturn(List.of());

        registrar(true).register(JOB_CLASS, NAME, CRON, JobLogAggregation.HOURLY);

        ArgumentCaptor<SysJob> captor = ArgumentCaptor.forClass(SysJob.class);
        verify(sysJobService).save(captor.capture(), isNull());
        SysJob saved = captor.getValue();
        assertEquals(JOB_CLASS, saved.getJobClass());
        assertEquals(NAME, saved.getName());
        assertEquals(CRON, saved.getCron());
        assertTrue(saved.getEnabled());
        assertEquals(JobLogAggregation.HOURLY, saved.getLogAggregation());
    }

    @Test
    void fillsShellRowCreatedByFrameworkAutoRegistrar() throws Exception {
        SysJob shell = new SysJob();
        shell.setId("job-1");
        shell.setName("AppScheduledUpdateJob");
        shell.setJobClass(JOB_CLASS);
        shell.setEnabled(false);
        when(sysJobRepository.findAll()).thenReturn(List.of(shell));

        registrar(true).register(JOB_CLASS, NAME, CRON, JobLogAggregation.HOURLY);

        ArgumentCaptor<SysJob> captor = ArgumentCaptor.forClass(SysJob.class);
        ArgumentCaptor<List<String>> fields = ArgumentCaptor.forClass(List.class);
        verify(sysJobService).save(captor.capture(), fields.capture());
        // 更新已有行必须带字段列表，框架的 updateField 不接受空列表
        assertTrue(fields.getValue().containsAll(List.of(
                SysJob.Fields.name, SysJob.Fields.cron, SysJob.Fields.enabled, SysJob.Fields.logAggregation)));
        SysJob saved = captor.getValue();
        assertEquals(NAME, saved.getName());
        assertEquals(CRON, saved.getCron());
        assertTrue(saved.getEnabled());
        assertEquals(JobLogAggregation.HOURLY, saved.getLogAggregation());
    }

    @Test
    void keepsUserConfiguredJobUntouched() throws Exception {
        SysJob configured = new SysJob();
        configured.setId("job-2");
        configured.setJobClass(JOB_CLASS);
        configured.setCron("0 0 4 * * ?");
        configured.setEnabled(false);
        when(sysJobRepository.findAll()).thenReturn(List.of(configured));

        registrar(true).register(JOB_CLASS, NAME, CRON, JobLogAggregation.HOURLY);

        verify(sysJobService, never()).save(any(), anyList());
        assertFalse(configured.getEnabled());
        assertEquals("0 0 4 * * ?", configured.getCron());
    }

    @Test
    void doesNothingWhenJobModuleDisabled() {
        registrar(false).register(JOB_CLASS, NAME, CRON, JobLogAggregation.HOURLY);

        verify(sysJobRepository, never()).findAll();
    }

    private BuiltinJobRegistrar registrar(boolean jobEnable) {
        when(systemProperties.isJobEnable()).thenReturn(jobEnable);
        return new BuiltinJobRegistrar(sysJobService, sysJobRepository, systemProperties);
    }

}
