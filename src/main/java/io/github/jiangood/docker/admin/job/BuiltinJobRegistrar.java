package io.github.jiangood.docker.admin.job;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.openadmin.framework.config.SystemProperties;
import io.github.jiangood.openadmin.modules.job.entity.JobLogAggregation;
import io.github.jiangood.openadmin.modules.job.entity.SysJob;
import io.github.jiangood.openadmin.modules.job.repository.SysJobRepository;
import io.github.jiangood.openadmin.modules.job.service.SysJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.ObjectAlreadyExistsException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 内建定时任务的幂等登记。
 * <p>
 * 框架的 {@code JobAutoRegistrar} 会先按 {@code @JobDescription} 把可调度类补建成 {@code sys_job} 行，
 * 但补建出来的行是「空壳」：{@code cron} 为空、{@code enabled=false}，需要在「定时任务」页面里手工配置才会跑。
 * 而本模块的三个内建任务（主机用量采集 / 网站可达性检查 / 应用定时更新检查）设计上应当开箱即用，
 * 由 {@link org.springframework.boot.ApplicationRunner} 补上默认 cron 并启用。
 * <p>
 * 两者的执行顺序并不固定：{@code JobAutoRegistrar} 也是 runner 且优先级通常更高，因此这里不能只看
 * 「jobClass 是否存在」，还要识别出「存在但没有 cron」的空壳行并补全。判断依据刻意用 cron 是否为空：
 * 用户只要在页面上保存过，cron 一定有值（停用也保留 cron），此时一律不碰。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BuiltinJobRegistrar {

    /**
     * 更新已有行时要写回的字段。框架的 update 只复制列出的字段，传空列表会直接断言失败。
     */
    private static final List<String> UPDATE_FIELDS = List.of(
            SysJob.Fields.name, SysJob.Fields.cron, SysJob.Fields.enabled, SysJob.Fields.logAggregation);

    private final SysJobService sysJobService;
    private final SysJobRepository sysJobRepository;
    private final SystemProperties systemProperties;

    /**
     * 确保指定任务存在且已启用。
     *
     * @param jobClass       任务类全名，用于按 {@code job_class} 定位已有记录
     * @param name           任务名，仅用于新建 / 补全空壳行
     * @param cron           默认 cron 表达式
     * @param logAggregation 执行记录的聚合方式（高频任务建议按小时）
     */
    public void register(String jobClass, String name, String cron, JobLogAggregation logAggregation) {
        if (!systemProperties.isJobEnable()) {
            return;
        }

        SysJob existing = sysJobRepository.findAll().stream()
                .filter(job -> jobClass.equals(job.getJobClass()))
                .findFirst()
                .orElse(null);

        if (existing == null) {
            SysJob job = new SysJob();
            job.setName(name);
            job.setCron(cron);
            job.setEnabled(true);
            job.setJobClass(jobClass);
            job.setLogAggregation(logAggregation);
            save(job, name);
            return;
        }

        if (StrUtil.isNotBlank(existing.getCron())) {
            // 用户配置过（改过 cron、间隔或手动停用），保持页面上的设置不变
            return;
        }

        existing.setName(name);
        existing.setCron(cron);
        existing.setEnabled(true);
        existing.setLogAggregation(logAggregation);
        save(existing, name);
    }

    private void save(SysJob job, String name) {
        try {
            boolean created = job.isNew();
            sysJobService.save(job, created ? null : UPDATE_FIELDS);
            log.info(created
                            ? "已自动创建定时任务「{}」（{}）"
                            : "已补全自动登记的空定时任务「{}」（{}）",
                    name, job.getCron());
        } catch (ObjectAlreadyExistsException e) {
            log.debug("定时任务「{}」已由 QuartzInitializer 注册，跳过", name);
        } catch (Exception e) {
            log.warn("自动登记定时任务「{}」失败，请到「系统 / 定时任务」手工创建：{}", name, e.toString());
        }
    }

}
