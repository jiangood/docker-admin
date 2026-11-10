package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dao.SiteMonitorLogRepository;
import io.github.jiangood.docker.admin.dao.SiteMonitorRepository;
import io.github.jiangood.docker.admin.entity.SiteMonitor;
import io.github.jiangood.docker.admin.entity.SiteMonitorLog;
import io.github.jiangood.docker.admin.mail.SiteMonitorMailNotifier;
import io.github.jiangood.openadmin.framework.data.BaseService;
import io.github.jiangood.openadmin.framework.data.specification.Spec;
import io.github.jiangood.openadmin.modules.system.repository.SysUserRepository;
import io.github.jiangood.openadmin.util.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 网站监控：监控项的增删改查之外，还承担本轮探测的判定、快照更新、告警与历史清理。
 * <p>
 * 判定分三层，命中即判不可达（错误原因写进历史记录与监控项快照）：
 * <ol>
 *   <li>状态码不在 2xx~3xx（nginx 反代上游挂掉时的 502/504 直接命中）；</li>
 *   <li>响应体包含反向关键词 {@link SiteMonitor#getErrorKeyword()}——
 *       很多代理/业务方用 200 状态码返回错误占位页，只看状态码会误报为正常；</li>
 *   <li>配置了正向关键词 {@link SiteMonitor#getKeyword()} 但响应体不包含它。</li>
 * </ol>
 * 两条链路共用 {@link #doCheck}：定时链路每分钟由 Quartz 任务扫到期项执行；
 * 手动链路是页面上的「立即检测」，同步等结果返回。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SiteMonitorService extends BaseService<SiteMonitor> {

    /**
     * 共享的 HTTP 客户端：跟随 301/302 重定向（nginx http→https、加尾斜杠等常见跳转），
     * 取跳转后的最终页判定；连接超时 10 秒兜底，单次请求超时另按监控项配置。
     */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final String USER_AGENT = "docker-admin-site-monitor";

    /**
     * 历史记录保留天数（检测记录每轮检查都落一条，不清理很快把内置 H2 顶满）。
     */
    private static final int LOG_RETAIN_DAYS = 30;

    /**
     * 历史清理频率：跟着检查任务顺带跑，一小时到点清理一次足够。
     */
    private static final long LOG_PRUNE_INTERVAL_MILLIS = Duration.ofHours(1).toMillis();

    /**
     * 失败原因最长保留 900 字符（列 length=1000 留余量），避免把整页 html 灌进库。
     */
    private static final int ERROR_MAX_LENGTH = 900;

    private final SiteMonitorRepository siteMonitorRepository;
    private final SiteMonitorLogRepository logRepository;
    private final SiteMonitorMailNotifier mailNotifier;
    private final SysUserRepository sysUserRepository;

    private final AtomicLong lastLogPrune = new AtomicLong();

    // ---------------- CRUD ----------------

    /**
     * 保存监控项：新增走 create，编辑走 update；保存前补默认值并做校验。
     */
    @Transactional
    public SiteMonitor saveMonitor(SiteMonitor input, List<String> updateFields) {
        validate(input);
        if (StrUtil.isBlank(input.getId())) {
            return create(input);
        }
        return update(input, updateFields);
    }

    /**
     * 删除监控项，同时删掉它的历史记录，避免留下孤儿数据。
     */
    @Transactional
    public void deleteMonitor(String id) {
        Assert.hasText(id, "缺少监控项 id");
        logRepository.deleteByMonitorId(id);
        deleteById(id);
    }

    /**
     * 检测历史分页，页面「历史」抽屉使用，按检测时间倒序由 Pageable 决定。
     */
    public Page<SiteMonitorLog> pageLogs(String monitorId, Pageable pageable) {
        Assert.hasText(monitorId, "缺少监控项 id");
        Spec<SiteMonitorLog> q = Spec.<SiteMonitorLog>of().eq(SiteMonitorLog.Fields.monitorId, monitorId);
        return logRepository.findAll(q, pageable);
    }

    /**
     * 批量填充告警邮件实际收件人（创建人邮箱，不持久化），供列表展示。
     * 创建人未配置邮箱时留空，页面据此提示「告警发不出去」。
     */
    public void fillNotifyEmailTo(List<SiteMonitor> monitors) {
        if (monitors == null || monitors.isEmpty()) {
            return;
        }
        Set<String> userIds = monitors.stream()
                .map(SiteMonitor::getCreateUser)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toSet());
        Map<String, String> emails = userIds.isEmpty() ? Map.of()
                : sysUserRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(u -> u.getId(),
                        u -> StrUtil.trimToEmpty(u.getEmail()),
                        (a, b) -> a));
        for (SiteMonitor monitor : monitors) {
            monitor.setNotifyEmailTo(StrUtil.trimToNull(emails.get(monitor.getCreateUser())));
        }
    }

    /**
     * 配置校验与默认值补齐。
     * <p>
     * 关键词判定需要响应体，而 HEAD 请求没有 body，所以填了任一关键词时方法必须是 GET。
     */
    private void validate(SiteMonitor input) {
        Assert.hasText(input.getName(), "请填写监控名称");
        Assert.hasText(input.getUrl(), "请填写监控地址");
        input.setUrl(input.getUrl().trim());

        URI uri;
        try {
            uri = URI.create(input.getUrl());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("监控地址格式不正确：" + input.getUrl());
        }
        String scheme = StrUtil.trimToEmpty(uri.getScheme()).toLowerCase();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new BusinessException("监控地址必须以 http:// 或 https:// 开头");
        }

        if (StrUtil.isBlank(input.getMethod())) {
            input.setMethod(SiteMonitor.METHOD_GET);
        }
        if (!SiteMonitor.METHOD_GET.equals(input.getMethod()) && !SiteMonitor.METHOD_HEAD.equals(input.getMethod())) {
            throw new BusinessException("请求方法仅支持 GET / HEAD");
        }
        if ((StrUtil.isNotBlank(input.getKeyword()) || StrUtil.isNotBlank(input.getErrorKeyword()))
                && !SiteMonitor.METHOD_GET.equals(input.getMethod())) {
            throw new BusinessException("HEAD 请求没有响应体，无法做关键词判定，请改用 GET 或清空关键词");
        }

        if (input.getIntervalMinutes() == null || input.getIntervalMinutes() < 1) {
            input.setIntervalMinutes(5);
        }
        if (input.getTimeoutSeconds() == null || input.getTimeoutSeconds() < 2) {
            input.setTimeoutSeconds(10);
        }
        if (input.getEnabled() == null) {
            input.setEnabled(true);
        }
        if (input.getNotifyEmail() == null) {
            input.setNotifyEmail(false);
        }
        // 新增时初始化检测状态，避免列表出现半空的快照
        if (StrUtil.isBlank(input.getId())) {
            input.setLastSuccess(null);
            input.setFailCount(0);
            input.setAlerted(false);
        }
    }

    // ---------------- 探测 ----------------

    /**
     * 定时链路：逐个检查启用且到期的监控项（Quartz 任务每分钟触发一次）。
     * 到期 = 从未检测过，或距最近一次检测已达监控项配置的间隔。
     *
     * @return 本轮实际探测的项目数（不是成功数）
     */
    public int runDueChecks() {
        List<SiteMonitor> due = siteMonitorRepository.findAll().stream()
                .filter(m -> Boolean.TRUE.equals(m.getEnabled()))
                .filter(this::isDue)
                .toList();
        for (SiteMonitor monitor : due) {
            try {
                doCheck(monitor);
            } catch (Exception e) {
                // 单个监控项失败不影响同轮的其他项
                log.warn("网站监控「{}」检查异常：{}", monitor.getName(), e.toString());
            }
        }
        return due.size();
    }

    /**
     * 手动链路：页面「立即检测」同步执行一次，返回本次检测记录（页面用它的结果提示）。
     */
    public SiteMonitorLog checkNow(String id) {
        SiteMonitor monitor = findById(id).orElseThrow(() -> new BusinessException("监控项不存在"));
        return doCheck(monitor);
    }

    /**
     * 执行一次探测并把结果落库（历史记录 + 监控项快照），失败原因不抛出而写进记录里。
     */
    private SiteMonitorLog doCheck(SiteMonitor monitor) {
        long start = System.currentTimeMillis();
        SiteMonitorLog logRow = new SiteMonitorLog();
        logRow.setMonitorId(monitor.getId());
        logRow.setMonitorName(monitor.getName());

        Integer statusCode = null;
        String error;
        try {
            HttpResponse<String> response = HTTP_CLIENT.send(
                    buildRequest(monitor), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            statusCode = response.statusCode();
            error = judge(monitor, response.body(), statusCode);
        } catch (HttpTimeoutException e) {
            error = "请求超时（超过 " + timeoutSeconds(monitor) + " 秒无响应）";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("检测被中断", e);
        } catch (Exception e) {
            // 连接失败、DNS 失败、SSL 错误、重定向过多等，摘 toString 避免冗长堆栈
            error = StrUtil.maxLength(e.toString(), ERROR_MAX_LENGTH);
        }
        boolean success = error == null;
        long latency = System.currentTimeMillis() - start;

        logRow.setSuccess(success);
        logRow.setStatusCode(statusCode);
        logRow.setLatencyMs(latency);
        logRow.setError(error);
        logRepository.save(logRow);

        applyResult(monitor, success, statusCode, latency, error);
        return logRow;
    }

    private static HttpRequest buildRequest(SiteMonitor monitor) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(monitor.getUrl()))
                .header("User-Agent", USER_AGENT);
        if (SiteMonitor.METHOD_HEAD.equals(monitor.getMethod())) {
            builder.method("HEAD", HttpRequest.BodyPublishers.noBody());
        } else {
            builder.GET();
        }
        return builder.timeout(Duration.ofSeconds(timeoutSeconds(monitor))).build();
    }

    /**
     * 三层判定，返回 null 表示正常，否则是写进日志与邮件的失败原因。
     */
    private String judge(SiteMonitor monitor, String body, int statusCode) {
        if (statusCode < 200 || statusCode >= 400) {
            return "HTTP 状态码 " + statusCode + " 不在 2xx~3xx 范围";
        }
        String errorKeyword = StrUtil.trimToNull(monitor.getErrorKeyword());
        if (errorKeyword != null && StrUtil.contains(body, errorKeyword)) {
            return "响应中包含异常关键词「" + errorKeyword + "」：" + snippetOf(body, errorKeyword);
        }
        String keyword = StrUtil.trimToNull(monitor.getKeyword());
        if (keyword != null && !StrUtil.contains(body, keyword)) {
            return "响应中未找到期望关键词「" + keyword + "」（状态码 " + statusCode + "）";
        }
        return null;
    }

    /**
     * 命中异常关键词时截取它前后的上下文片段（去掉多余空白），方便确认是不是代理/业务的占位页。
     */
    private static String snippetOf(String body, String keyword) {
        String text = StrUtil.nullToEmpty(body);
        int index = text.indexOf(keyword);
        if (index < 0) {
            return StrUtil.maxLength(text.replaceAll("\\s+", " ").trim(), 100);
        }
        int from = Math.max(0, index - 30);
        int to = Math.min(text.length(), index + keyword.length() + 60);
        return StrUtil.maxLength(text.substring(from, to).replaceAll("\\s+", " ").trim(), 100);
    }

    /**
     * 更新监控项上的检测快照，并按阈值决定是否发告警 / 恢复邮件。
     * <p>
     * 告警带去重：连续失败满 {@link SiteMonitor#ALERT_THRESHOLD} 次才发第一次，
     * 发过（{@code alerted=true}）不再重复，恢复时发恢复通知并复位标记。
     */
    private void applyResult(SiteMonitor monitor, boolean success, Integer statusCode, long latencyMs, String error) {
        monitor.setLastSuccess(success);
        monitor.setLastStatusCode(statusCode);
        monitor.setLastLatencyMs(latencyMs);
        monitor.setLastCheckTime(LocalDateTime.now());
        monitor.setLastError(StrUtil.maxLength(error, ERROR_MAX_LENGTH));
        int failCount = success ? 0 : (monitor.getFailCount() == null ? 0 : monitor.getFailCount()) + 1;
        monitor.setFailCount(failCount);

        boolean wasAlerted = Boolean.TRUE.equals(monitor.getAlerted());
        boolean notify = Boolean.TRUE.equals(monitor.getNotifyEmail());
        try {
            if (success) {
                monitor.setAlerted(false);
                if (wasAlerted && notify) {
                    mailNotifier.recovered(monitor);
                }
            } else if (!wasAlerted && failCount >= SiteMonitor.ALERT_THRESHOLD && notify) {
                monitor.setAlerted(true);
                mailNotifier.alert(monitor, error, statusCode);
            }
        } catch (Exception e) {
            // 邮件失败不影响检测结果，下一轮失败/恢复仍会再尝试
            log.warn("网站监控「{}」邮件通知发送失败：{}", monitor.getName(), e.toString());
        }
        siteMonitorRepository.save(monitor);
    }

    private boolean isDue(SiteMonitor monitor) {
        if (monitor.getLastCheckTime() == null) {
            return true;
        }
        long elapsedMillis = Duration.between(monitor.getLastCheckTime(), LocalDateTime.now()).toMillis();
        return elapsedMillis >= Duration.ofMinutes(defaultInt(monitor.getIntervalMinutes(), 5)).toMillis();
    }

    // ---------------- 历史清理 ----------------

    /**
     * 定期清理超出保留期的历史记录；到点才真正执行（check 任务每分钟一次，清理一小时一次）。
     */
    @Transactional
    public int pruneLogsIfDue() {
        long now = System.currentTimeMillis();
        long last = lastLogPrune.get();
        if (last != 0 && now - last < LOG_PRUNE_INTERVAL_MILLIS) {
            return 0;
        }
        if (!lastLogPrune.compareAndSet(last, now)) {
            return 0;
        }
        int deleted = logRepository.deleteBefore(LocalDateTime.now().minusDays(LOG_RETAIN_DAYS));
        if (deleted > 0) {
            log.info("清理网站监控历史记录 {} 条（保留 {} 天）", deleted, LOG_RETAIN_DAYS);
        }
        return deleted;
    }

    private static int timeoutSeconds(SiteMonitor monitor) {
        return defaultInt(monitor.getTimeoutSeconds(), 10);
    }

    private static int defaultInt(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

}
