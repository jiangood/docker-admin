package io.github.jiangood.docker.admin.service;


import cn.hutool.core.date.BetweenFormatter;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.unit.DataSizeUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.BuildImageCmd;
import com.github.dockerjava.api.command.PushImageCmd;
import com.github.dockerjava.api.model.BuildResponseItem;
import io.github.jiangood.docker.base.FileLog;
import io.github.jiangood.docker.base.tool.GitCredential;
import io.github.jiangood.docker.base.tool.GitTool;
import io.github.jiangood.docker.admin.BuildResultEvent;
import io.github.jiangood.docker.admin.BuildSuccessEvent;
import io.github.jiangood.docker.admin.dao.AppRepository;
import io.github.jiangood.docker.admin.dao.ProjectRepository;
import io.github.jiangood.docker.admin.dto.BuildRequest;
import io.github.jiangood.docker.admin.entity.App;
import io.github.jiangood.docker.admin.entity.BuildLog;
import io.github.jiangood.docker.admin.entity.CodeSource;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.entity.ImageTag;
import io.github.jiangood.docker.admin.entity.Project;
import io.github.jiangood.docker.admin.entity.Registry;
import io.github.jiangood.docker.admin.util.BuildContextUtils;
import io.github.jiangood.docker.admin.util.ImageUrlUtils;
import io.github.jiangood.docker.admin.util.VersionUtils;
import io.github.jiangood.docker.admin.websocket.TaskLogRegistry;
import io.github.jiangood.docker.sdk.engine.DefaultCallback;
import io.github.jiangood.docker.sdk.engine.DockerClientManager;
import io.github.jiangood.openadmin.framework.data.BaseService;
import io.github.jiangood.openadmin.framework.data.specification.Spec;
import io.github.jiangood.openadmin.modules.system.repository.SysUserRepository;
import io.github.jiangood.openadmin.util.BusinessException;
import jakarta.annotation.Resource;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.slf4j.Logger;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;


@Service
@Slf4j
@RequiredArgsConstructor
public class ProjectService extends BaseService<Project> {

    private static final Logger fileLog = FileLog.logger();

    private final ProjectRepository projectRepository;

    @Resource
    RegistryService registryService;


    @Resource
    HostService hostService;

    @Resource
    DockerClientManager dockerService;

    @Resource
    CodeSourceService codeSourceService;

    @Resource
    CodeSourceApiService codeSourceApiService;

    @Resource
    BuildLogService buildLogService;

    @Resource
    ImageTagService imageTagService;

    @Resource
    ImageService imageService;

    @Resource
    AppRepository appRepository;

    @Resource
    private ApplicationEventPublisher applicationEventPublisher;

    @Resource
    TaskLogRegistry taskLogRegistry;

    @Resource
    SysUserRepository sysUserRepository;

    private final Map<String, DefaultCallback> buildThreadMap = new ConcurrentHashMap<>();

    /**
     * 正在构建的项目 id。项目使用固定代码目录，同一项目不允许并行构建，避免互相删目录。
     * <p>
     * 并发策略是「新的覆盖旧的」：新构建先取消旧构建，再等它释放代码目录后开始；
     * 不像以前那样直接报错拒绝，也不排队执行。
     */
    private final Set<String> buildingProjects = ConcurrentHashMap.newKeySet();

    /** 已被取消的构建日志 id：手动停止、被新构建覆盖等，构建线程在每个检查点据此放弃。 */
    private final Set<String> cancelledBuilds = ConcurrentHashMap.newKeySet();

    /** 新构建等待旧构建退出的上限：克隆大仓库时旧构建要等克隆结束才能看到取消标记。 */
    private static final long BUILD_SLOT_WAIT_MILLIS = 10 * 60 * 1000L;

    /** 等待旧构建退出时的轮询间隔。 */
    private static final long BUILD_SLOT_POLL_MILLIS = 200L;


    /**
     * 镜像url：registry.url/namespace/name。未配置注册中心时仅返回项目名。
     */
    public String getFullImageUrl(Project project) {
        if (project == null) {
            return null;
        }
        return ImageUrlUtils.repoUrl(registryService.effectiveFor(project.getRegistryId()), project.getName());
    }

    /**
     * 填充镜像url（不持久化），供前端展示。
     */
    public void fillImageUrl(Project project) {
        if (project != null) {
            project.setImageUrl(getFullImageUrl(project));
        }
    }

    /**
     * 填充项目创建人邮箱（不持久化），供详情页展示构建结果邮件收件人。
     */
    public void fillCreateUserEmail(Project project) {
        if (project != null) {
            project.setCreateUserEmail(findCreateUserEmail(project));
        }
    }

    /** 项目创建人的邮箱；创建人不存在或未配置邮箱时返回 null。 */
    public String findCreateUserEmail(Project project) {
        if (project == null || StrUtil.isBlank(project.getCreateUser())) {
            return null;
        }
        return sysUserRepository.findById(project.getCreateUser())
                .map(user -> StrUtil.trimToNull(user.getEmail()))
                .orElse(null);
    }

    /**
     * 保存项目：新建走 create，编辑走 update。
     */
    @Transactional
    public Project saveProject(Project input, List<String> updateFields) {
        if (StrUtil.isNotBlank(input.getRegistryId())) {
            // 校验绑定的注册中心存在（不存在会抛业务异常）
            registryService.requireRegistry(input.getRegistryId());
        }
        if (StrUtil.isBlank(input.getId())) {
            return create(input);
        }
        return update(input, updateFields);
    }


    public void stopBuild(String logId) throws IOException {
        // 先登记取消标记并关闭 docker 构建流：构建线程即使没被立刻打断，也会在下一个检查点放弃。
        // 只有真正在跑的任务才需要取消标记；进程重启遗留的记录没有对应线程。
        if (taskLogRegistry.isRunning(logId)) {
            cancelledBuilds.add(logId);
        }
        DefaultCallback callback = buildThreadMap.remove(logId);
        if (callback != null) {
            callback.close();
        }

        if (!markBuildFailed(logId)) {
            // 已经结束的构建不回写状态，也不需要保留取消标记
            cancelledBuilds.remove(logId);
        }
    }

    /**
     * 把仍在「处理中」（success 为空）的构建记录标记为失败，已结束的记录保持原样。
     *
     * @return true 表示本次确实做了标记
     */
    private boolean markBuildFailed(String logId) {
        BuildLog buildLog = buildLogService.findById(logId).orElse(null);
        if (buildLog == null || buildLog.getSuccess() != null) {
            return false;
        }

        buildLog.setSuccess(false);
        buildLog.setCompleteTime(LocalDateTime.now());
        buildLog.setTimeSpend(Duration.between(buildLog.getCreateTime(), buildLog.getCompleteTime()).toMillis());
        buildLogService.save(buildLog);
        return true;
    }

    /**
     * 取消项目下所有仍在「处理中」的构建（正在跑的、排队等槽位的，以及进程重启后遗留的记录）。
     *
     * @param excludeLogId 需要保留的构建记录 id（正在抢槽位的本次构建），可为 null
     */
    private void cancelProcessingBuilds(String projectId, String excludeLogId) {
        if (StrUtil.isBlank(projectId)) {
            return;
        }
        for (BuildLog item : buildLogService.findByProjectProcessing(projectId)) {
            if (item.getId().equals(excludeLogId)) {
                continue;
            }
            try {
                stopBuild(item.getId());
            } catch (IOException e) {
                log.warn("取消构建 {} 失败：{}", item.getId(), e.getMessage());
            }
        }
    }

    /**
     * 抢占项目构建槽位：同一项目使用固定代码目录，必须等上一轮构建（已被取消）彻底退出。
     * <p>
     * 等待发生在异步构建线程里，不阻塞触发接口（Webhook 立即返回）。等待期间若本次构建也被
     * 更新的构建取代，则直接放弃。
     *
     * @return true 表示抢到槽位；false 表示被取代或等待超时
     */
    private boolean acquireBuildSlot(String projectId, String logId) {
        long deadline = System.currentTimeMillis() + BUILD_SLOT_WAIT_MILLIS;
        boolean waiting = false;

        while (!cancelledBuilds.contains(logId)) {
            if (buildingProjects.add(projectId)) {
                if (waiting) {
                    fileLog.info("旧构建已退出，开始本次构建");
                }
                // 抢到槽位后取消同项目其它仍在排队的构建，只保留最新一次；
                // 这一步失败（如数据库异常）不能影响本次构建，否则槽位会一直占着
                try {
                    cancelProcessingBuilds(projectId, logId);
                } catch (Exception e) {
                    fileLog.warn("取消同项目排队构建失败：{}", e.getMessage());
                }
                return true;
            }

            if (!waiting) {
                waiting = true;
                fileLog.info("该项目已有构建在进行，已停止旧构建，等待其释放代码目录");
            }

            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(BUILD_SLOT_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }


    public void checkBuildImage() {
        // 判断有无主机
        long count = hostService.count();
        Assert.state(count > 0, "请先添加主机");


    }

    /**
     * 构建版本（tag）格式：v1.0.1 / 1.0.1
     */
    public static final Pattern TAG_PATTERN = Pattern.compile("^v?\\d+\\.\\d+\\.\\d+$");

    public static boolean isValidTag(String tag) {
        return StrUtil.isNotBlank(tag) && TAG_PATTERN.matcher(tag.trim()).matches();
    }

    /**
     * 拉取项目对应远程仓库的 tag 列表。
     */
    public List<String> listRemoteTags(Project project) {
        CodeSource source = codeSourceService.findByGitUrl(project.getGitUrl());
        GitCredential credential = codeSourceService.credential(source);
        try {
            return GitTool.listRemoteTags(project.getGitUrl(), credential).stream()
                    .sorted(VersionUtils.VERSION_DESC)
                    .toList();
        } catch (GitAPIException e) {
            if (source == null) {
                String host = CodeSourceService.hostKey(project.getGitUrl());
                throw new BusinessException("获取远程 tag 失败：未找到与主机 " + host
                        + " 匹配的代码源，请在【设置-代码源】配置访问方式与凭据", e);
            }
            throw new BusinessException("获取远程 tag 失败：" + e.getMessage(), e);
        }
    }

    /**
     * 拉取项目对应远程仓库的分支列表（main / master / develop 优先，其余按名称）。
     */
    public List<String> listRemoteBranches(Project project) {
        CodeSource source = codeSourceService.findByGitUrl(project.getGitUrl());
        GitCredential credential = codeSourceService.credential(source);
        try {
            return GitTool.listRemoteBranches(project.getGitUrl(), credential).stream()
                    .sorted(BRANCH_ORDER)
                    .toList();
        } catch (GitAPIException e) {
            if (source == null) {
                String host = CodeSourceService.hostKey(project.getGitUrl());
                throw new BusinessException("获取远程分支失败：未找到与主机 " + host
                        + " 匹配的代码源，请在【设置-代码源】配置访问方式与凭据", e);
            }
            throw new BusinessException("获取远程分支失败：" + e.getMessage(), e);
        }
    }

    /**
     * 分支排序：常见主开发分支在前，其余按名称升序，方便下拉默认选中。
     */
    private static final Comparator<String> BRANCH_ORDER = Comparator
            .comparingInt(ProjectService::branchPriority)
            .thenComparing(Comparator.naturalOrder());

    private static int branchPriority(String branch) {
        if ("main".equals(branch)) {
            return 0;
        }
        if ("master".equals(branch)) {
            return 1;
        }
        if ("develop".equals(branch) || "dev".equals(branch)) {
            return 2;
        }
        return 3;
    }

    /**
     * Docker tag 允许的最大长度。
     */
    private static final int DOCKER_TAG_MAX_LENGTH = 128;

    /**
     * 分支构建版本号中的构建时间格式：年月日-时分。
     */
    private static final DateTimeFormatter BUILD_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    /**
     * 每次构建成功后统一追加推送的版本号。
     */
    private static final String LATEST_TAG = "latest";

    /**
     * 分支构建的镜像版本号：分支名（非法字符替换为 -）+ 构建时间（yyyyMMdd-HHmm）+ 提交短 id，
     * 如 {@code main-20261020-1530-1a2b3c4d}。
     * <p>
     * Docker tag 不允许 {@code /} 等字符，这里做替换并按长度上限截断分支名，保证可推送。
     */
    static String buildBranchImageTag(String branch, String commitId, LocalDateTime buildTime) {
        String name = StrUtil.nullToEmpty(branch).trim()
                .replaceAll("[^A-Za-z0-9_.-]", "-")
                .replaceAll("^[-.]+", "")
                .replaceAll("[-.]+$", "");
        if (StrUtil.isBlank(name)) {
            name = "branch";
        }

        LocalDateTime time = buildTime == null ? LocalDateTime.now() : buildTime;
        StringBuilder suffix = new StringBuilder("-").append(BUILD_TIME_FORMATTER.format(time));
        if (StrUtil.isNotBlank(commitId)) {
            suffix.append("-").append(commitId);
        }

        int maxNameLength = Math.max(1, DOCKER_TAG_MAX_LENGTH - suffix.length());
        if (name.length() > maxNameLength) {
            name = name.substring(0, maxNameLength);
        }
        return name + suffix;
    }

    /**
     * 解析构建节点 id：未指定时使用系统默认 runner。
     */
    public String resolveBuildHostId(String buildHostId) {
        if (StrUtil.isNotBlank(buildHostId)) {
            return buildHostId;
        }
        Host runner = hostService.getDefaultDockerRunner();
        Assert.notNull(runner, "未配置构建节点（runner），请先在【主机】中设置");
        return runner.getId();
    }

    /**
     * 用指定 tag 触发构建（Webhook 使用，构建节点取系统默认 runner）。
     */
    public void buildByTag(Project project, String tag) {
        Assert.isTrue(isValidTag(tag), "tag 格式不正确，需形如 v1.0.1");
        Host runner = hostService.getDefaultDockerRunner();
        Assert.notNull(runner, "未配置构建节点（runner），请先在【主机】中设置");

        BuildRequest req = new BuildRequest();
        req.setProjectId(project.getId());
        req.setTag(tag);
        req.setDockerfile(project.getDockerfile());
        req.setContext(project.getContext());
        req.setBuildHostId(runner.getId());
        try {
            buildImage(req);
        } catch (IOException e) {
            throw new BusinessException("触发构建失败：" + e.getMessage(), e);
        }
    }

    /**
     * Webhook 入口：按 token 找到项目并用推送的 tag 触发构建。
     */
    public void triggerByToken(String token, String tag) {
        Assert.hasText(tag, "未从 Webhook 请求中解析到 tag");
        Assert.isTrue(isValidTag(tag), "tag 格式不正确，需形如 v1.0.1：" + tag);
        Project project = projectRepository.findByWebhookToken(token);
        Assert.notNull(project, "无效的 webhook token");
        buildByTag(project, tag);
    }

    /**
     * 用指定分支触发构建（Webhook 使用，构建节点取系统默认 runner）。
     */
    public void buildByBranch(Project project, String branch) {
        Assert.hasText(branch, "分支不能为空");
        Host runner = hostService.getDefaultDockerRunner();
        Assert.notNull(runner, "未配置构建节点（runner），请先在【主机】中设置");

        BuildRequest req = new BuildRequest();
        req.setProjectId(project.getId());
        req.setRefType(BuildRequest.REF_TYPE_BRANCH);
        req.setBranch(branch);
        req.setDockerfile(project.getDockerfile());
        req.setContext(project.getContext());
        req.setBuildHostId(runner.getId());
        try {
            buildImage(req);
        } catch (IOException e) {
            throw new BusinessException("触发构建失败：" + e.getMessage(), e);
        }
    }

    /**
     * Webhook 入口：按 token 找到项目并触发分支构建。
     * 项目未开启「分支推送自动构建」，或推送的分支不在配置的构建分支列表内时忽略。
     */
    public void triggerBranchByToken(String token, String branch) {
        Assert.hasText(branch, "未从 Webhook 请求中解析到分支");
        Project project = projectRepository.findByWebhookToken(token);
        Assert.notNull(project, "无效的 webhook token");
        if (!Boolean.TRUE.equals(project.getWebhookBranch())) {
            log.info("项目 {} 未开启分支推送自动构建，忽略分支 {} 的推送", project.getName(), branch);
            return;
        }
        List<String> branches = parseBranches(project.getWebhookBranches());
        if (!branches.contains(branch)) {
            log.info("项目 {} 的构建分支为 {}，忽略分支 {} 的推送", project.getName(), branches, branch);
            return;
        }
        buildByBranch(project, branch);
    }

    /**
     * 分支推送构建的默认分支：未配置时构建 master 与 main。
     */
    public static final String DEFAULT_WEBHOOK_BRANCHES = "master,main";

    /**
     * 解析逗号分隔的分支配置；为空时回退到 {@link #DEFAULT_WEBHOOK_BRANCHES}。
     */
    public static List<String> parseBranches(String branches) {
        String value = StrUtil.isBlank(branches) ? DEFAULT_WEBHOOK_BRANCHES : branches;
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
    }

    /**
     * 归一化逗号分隔的分支配置：去空格、去重、去空项；结果为空时回退到默认值。
     */
    public static String normalizeBranches(String branches) {
        return String.join(",", parseBranches(branches));
    }

    /**
     * 开启自动 Webhook：在代码仓库（GitLab）上创建指向 hookUrl 的 Webhook，并记录其 id。
     */
    @Transactional
    public Project enableWebhook(Project project, String hookUrl) {
        Assert.hasText(hookUrl, "Webhook 地址不能为空");
        CodeSource source = codeSourceService.findByGitUrl(project.getGitUrl());
        Assert.notNull(source, "未找到与代码仓库匹配的代码源，请先在【设置-代码源】中配置");
        String projectPath = CodeSourceApiService.projectPath(source, project.getGitUrl());
        String hookId = codeSourceApiService.enableProjectHook(source, projectPath, hookUrl);
        project.setWebhookHookId(hookId);
        project.setWebhookAuto(true);
        return save(project);
    }

    /**
     * 关闭自动 Webhook：删除代码仓库上由本系统创建的 Webhook，并清除本地记录。
     */
    @Transactional
    public Project disableWebhook(Project project) {
        CodeSource source = codeSourceService.findByGitUrl(project.getGitUrl());
        if (source != null) {
            String projectPath = CodeSourceApiService.projectPath(source, project.getGitUrl());
            codeSourceApiService.disableProjectHook(source, projectPath, project.getWebhookHookId());
        }
        project.setWebhookHookId(null);
        project.setWebhookAuto(false);
        return save(project);
    }

    /**
     * 重置 webhook token。若已开启自动 Webhook，则一并删除代码仓库上的旧 Webhook（地址已失效）。
     */
    @Transactional
    public Project resetWebhookToken(Project project) {
        if (Boolean.TRUE.equals(project.getWebhookAuto())) {
            try {
                disableWebhook(project);
            } catch (Exception e) {
                log.warn("重置令牌时删除远程 Webhook 失败，请到代码仓库手动清理：{}", e.getMessage());
                project.setWebhookHookId(null);
                project.setWebhookAuto(false);
            }
        }
        project.setWebhookToken(RandomUtil.randomString(32));
        return save(project);
    }

    /**
     * 触发构建：先同步落一条构建记录，再把真正的构建任务交给线程池异步执行。
     * 记录必须先入库，否则前端在触发接口返回后立即刷新列表时，会与异步线程写库形成竞态，
     * 出现「构建历史看不到新记录，刷新页面才有」的现象。
     * <p>
     * 同一项目同一时间只允许一个构建：项目使用固定代码目录，并行构建会互相删目录、串构建上下文。
     * 采用「新的覆盖旧的」：本次触发会先停掉正在跑的旧构建（以及进程重启遗留的「处理中」记录），
     * 新构建在异步线程里等旧构建释放代码目录后再开始，触发接口本身立即返回。
     *
     * @return 构建记录 id
     */
    public String buildImage(BuildRequest p) throws IOException {
        // 新的覆盖旧的：旧构建被取消，它的构建线程会在下一个检查点退出
        cancelProcessingBuilds(p.getProjectId(), null);

        BuildLog buildLog = createProcessingLog(p);

        // 任务在调度前就标记为运行中：前端触发后会立刻打开日志窗口，
        // 若此时尚未标记，日志 WebSocket 会误判任务已结束而提前关闭连接。
        taskLogRegistry.start(buildLog.getId());

        try {
            // 通过代理调用 @Async 方法，避免自调用失效
            SpringUtil.getBean(ProjectService.class).buildImageJob(p, buildLog.getId());
            return buildLog.getId();
        } catch (RuntimeException e) {
            // 任务没派发成功（如线程池已满）时把记录标记为失败，否则会一直显示「构建中」
            taskLogRegistry.finish(buildLog.getId());
            stopBuild(buildLog.getId());
            throw e;
        }
    }

    /**
     * 构建开始前先落一条「处理中」记录（success 为空即处理中），供前端立刻在构建历史中看到。
     */
    private BuildLog createProcessingLog(BuildRequest p) {
        Project project = projectRepository.findById(p.getProjectId()).orElse(null);
        Assert.notNull(project, "项目不存在");

        boolean branchBuild = BuildRequest.REF_TYPE_BRANCH.equals(p.getRefType())
                && StrUtil.isNotBlank(p.getBranch());
        String branch = branchBuild ? p.getBranch().trim() : null;

        BuildLog buildLog = new BuildLog();
        buildLog.setProjectId(project.getId());
        buildLog.setProjectName(project.getName());
        buildLog.setDockerfile(BuildContextUtils.normalizeDockerfile(project.getDockerfile()));
        buildLog.setContext(BuildContextUtils.normalize(project.getContext()));
        buildLog.setTag(branchBuild ? branch : p.getTag());
        buildLog.setBranch(branch);
        return buildLogService.saveLog(buildLog);
    }


    @Async
    public void buildImageJob(BuildRequest p, String logId) {
        String tag = p.getTag();
        String projectId = p.getProjectId();
        String context = p.getContext();
        // 兼容历史数据里带前导 / 的 dockerfile
        String dockerfile = BuildContextUtils.normalizeDockerfile(p.getDockerfile());

        // 分支构建：克隆分支最新代码，镜像版本号由「分支名 + 构建时间 + 提交短 id」生成
        boolean branchBuild = BuildRequest.REF_TYPE_BRANCH.equals(p.getRefType())
                && StrUtil.isNotBlank(p.getBranch());
        String branch = branchBuild ? p.getBranch().trim() : null;
        String cloneRef = branchBuild ? branch : tag;
        // 构建开始时间（服务器本地时区），用于生成分支构建版本号；抢到槽位后再取，避免等待期间时间戳过期
        LocalDateTime buildTime = null;

        Project project = null;
        BuildLog buildLog = null;
        DockerClient client = null;
        File workDir = null;
        boolean slotAcquired = false;
        FileLog.bind(logId);
        try {
            project = projectRepository.findById(projectId).orElse(null);
            Assert.notNull(project, "项目不存在");
            buildLog = buildLogService.findById(logId).orElse(null);
            Assert.notNull(buildLog, "构建记录不存在");
            // 任务已在 buildImage() 调度前通过 taskLogRegistry.start() 标记为运行中

            // 同一项目串行构建：旧构建（已被 cancelProcessingBuilds 取消）退出后才能复用代码目录
            if (!acquireBuildSlot(projectId, logId)) {
                fileLog.info("未开始构建：已被更新的构建取代，或等待旧构建释放代码目录超时");
                markBuildFailed(logId);
                return;
            }
            slotAcquired = true;
            buildTime = LocalDateTime.now();

            fileLog.info("开始构建镜像任务, 项目：{}， 仓库：{}， 来源：{}", project.getName(), project.getGitUrl(),
                    branchBuild ? "分支 " + branch : "tag " + tag);

            Host host = hostService.findById(p.getBuildHostId()).orElse(null);

            Assert.notNull(host, "无构建主机");
            fileLog.info("构建主机信息... 名称：{}, host:{}", host.getName(), host.getDockerHost());

            GitTool.CloneResult cloneResult = gitClone(project, cloneRef);
            workDir = cloneResult.getDir();
            // 克隆期间可能已被新构建取消（克隆无法中断），这里尽早放弃，避免继续占用代码目录
            if (isBuildCancelled(logId)) {
                fileLog.info("构建已取消，丢弃本次克隆的代码");
                return;
            }
            fileLog.info("代码下载完毕 " + workDir);
            fileLog.info("代码提交信息: {}", cloneResult.getCodeMessage());
            Date commitTime = Date.from(cloneResult.getCommitTime().atZone(ZoneId.systemDefault()).toInstant());
            fileLog.info("代码提交时间: {}, {}前", DateUtil.formatDateTime(commitTime), DateUtil.formatBetween(commitTime, new Date(), BetweenFormatter.Level.MINUTE));
            fileLog.info("代码文件大小: {}", DataSizeUtil.format(FileUtil.size(workDir)));

            // 镜像版本号：tag 构建用所选 tag，分支构建用「分支名 + 构建时间 + 提交短 id」
            String versionTag = branchBuild ? buildBranchImageTag(branch, cloneResult.getCommitId(), buildTime) : tag;
            fileLog.info("镜像版本号：{}", versionTag);
            buildLog.setTag(versionTag);


            fileLog.info("dockerfile {},  内容如下", dockerfile);


            buildLog.setBuildHostName(host.getName());
            buildLog.setBuildHostId(host.getId());
            buildLog.setCodeMessage(cloneResult.getCodeMessage());
            buildLog = buildLogService.saveLog(buildLog);

            Registry registry = registryService.requireRegistry(project.getRegistryId());
            fileLog.info("注册中心：{}", registry.getFullUrl());
            client = dockerService.getClient(host, registry);


            String imageUrl = ImageUrlUtils.repoUrl(registry, project.getName());

            String imageTag = ImageUrlUtils.full(imageUrl, versionTag);
            // 构建成功后统一追加的 latest 版本
            String latestImageTag = ImageUrlUtils.full(imageUrl, LATEST_TAG);

            fileLog.info("目标镜像： {}", imageTag);
            Assert.state(!StrUtil.containsBlank(imageUrl), "镜像路径不能包含空格");


            buildLog.setImageUrl(imageUrl);
            File buildDir = BuildContextUtils.resolve(workDir, context);
            fileLog.info("构建上下文: {}（{}）", BuildContextUtils.display(context), buildDir.getAbsolutePath());
            Assert.state(buildDir.isDirectory(), "构建上下文目录不存在：" + buildDir.getAbsolutePath()
                    + "，请检查项目配置的构建上下文：" + BuildContextUtils.display(context));

            fileLog.info("向docker发送构建指令");
            DefaultCallback<BuildResponseItem> buildCallback = new DefaultCallback<>(logId);
            buildThreadMap.put(logId, buildCallback);

            fileLog.info("是否拉取基础镜像 withPull {}", p.isPull());
            fileLog.info("是否使用缓存 {}", p.isUseCache());
            // dockerfile 始终相对代码仓库根目录解析，且必须位于构建上下文内
            File dockerfileFile = new File(workDir, dockerfile);
            fileLog.info("dockerfile绝对路径: {}", dockerfileFile.getAbsolutePath());
            fileLog.info("是否拉取基础镜像:{}", p.isPull());
            Assert.state(dockerfileFile.isFile(), "未找到 Dockerfile： " + dockerfile
                    + "（仓库根目录 " + workDir.getAbsolutePath() + "），请检查项目配置的 dockerfile 路径");
            Assert.state(BuildContextUtils.isInside(buildDir, dockerfileFile), "Dockerfile 必须位于构建上下文内：dockerfile="
                    + dockerfile + "，构建上下文=" + BuildContextUtils.display(context)
                    + "。dockerfile 按仓库根目录填写，如 admin/Dockerfile");


            fileLog.info("构建命令执行中...");
            // 不能用 buildImageCmd(File)：该重载在构造时强制要求上下文根目录下存在名为 Dockerfile 的文件，
            // 而 Dockerfile 常位于子目录（如 docker/Dockerfile），此时会直接抛
            // IllegalArgumentException: Dockerfile does not exist。这里手动指定上下文与 Dockerfile。
            BuildImageCmd buildImageCmd = client.buildImageCmd()
                    .withBaseDirectory(buildDir)
                    // 删除构建产生的容器
                    .withForcerm(true)
                    .withPull(p.isPull())
                    .withNetworkMode("host")
                    // 一次构建同时打上版本号与 latest，随后一起推送
                    .withTags(new LinkedHashSet<>(List.of(imageTag, latestImageTag)))
                    .withNoCache(!p.isUseCache())
                    .withDockerfile(dockerfileFile);


            if (StrUtil.isNotEmpty(project.getBuildArg())) {
                Map<String, String> buildArgsMap = UriComponentsBuilder.newInstance().query(project.getBuildArg()).build().getQueryParams().toSingleValueMap();
                for (Map.Entry<String, String> e : buildArgsMap.entrySet()) {
                    fileLog.info("构建参数: {}={}", e.getKey(), e.getValue());
                    buildImageCmd.withBuildArg(e.getKey(), e.getValue());
                }
            }


            buildImageCmd.exec(buildCallback).awaitCompletion();
            fileLog.info("构建命令执行完毕");


            // 判断是构建被中途取消（手动停止、被新构建覆盖）：关闭 docker 流后从这里退出
            if (isBuildCancelled(logId)) {
                fileLog.info("构建已取消，不再推送镜像");
                return;
            }
            fileLog.info("镜像构建结束 ");
            buildThreadMap.remove(logId);

            // 推送版本号与 latest
            fileLog.info("推送镜像 {}", imageTag);
            PushImageCmd pushImageCmd = client.pushImageCmd(imageTag);
            pushImageCmd.exec(new DefaultCallback<>(logId)).awaitCompletion();
            fileLog.info("推送镜像结束 {}", imageTag);

            fileLog.info("推送镜像 {}", latestImageTag);
            pushImageCmd = client.pushImageCmd(latestImageTag);
            pushImageCmd.exec(new DefaultCallback<>(logId)).awaitCompletion();
            fileLog.info("推送镜像结束 {}", latestImageTag);

            // 登记镜像与标签，并记录镜像声明的端口与卷（版本号与 latest 各登记一条）
            try {
                imageService.upsert(imageUrl, project.getName(), "BUILD", registry.getId(), project.getSysOrg());
                imageTagService.inspect(client, imageTag, imageUrl, versionTag, buildLog.getId(), "BUILD");
                imageTagService.inspect(client, latestImageTag, imageUrl, LATEST_TAG, buildLog.getId(), "BUILD");
            } catch (Exception e) {
                fileLog.warn("登记镜像失败: {}", e.getMessage());
            }

            // 推送阶段被取消时镜像可能已推上去，但记录已由 stopBuild/markBuildFailed 置为失败，不要回写成成功
            if (isBuildCancelled(logId)) {
                fileLog.info("构建已取消，不记录成功状态");
                return;
            }

            buildLog.setSuccess(true);
            buildLog.setCompleteTime(LocalDateTime.now());
            buildLog.setTimeSpend(Duration.between(buildLog.getCreateTime(), buildLog.getCompleteTime()).toMillis());
            buildLog = buildLogService.save(buildLog);
            fileLog.info("已更新构建日志{}", buildLog);


            BuildSuccessEvent event = new BuildSuccessEvent(this);
            event.setBuildLog(buildLog);
            event.setTag(versionTag);

            applicationEventPublisher.publishEvent(event);
            fileLog.info("抛出构建事件 {}", event.getBuildLog().getProjectName());

            publishBuildResult(buildLog);
            fileLog.info("构建阶段结束");
        } catch (Exception e) {
            fileLog.info("异常 {} {}", e.getClass().getName(), e.getMessage());
            fileLog.error("构建失败", e);

            if (buildLog != null) {
                buildLog.setSuccess(false);
                buildLog.setCompleteTime(LocalDateTime.now());
                buildLog.setTimeSpend(Duration.between(buildLog.getCreateTime(), buildLog.getCompleteTime()).toMillis());
                buildLog = buildLogService.save(buildLog);
                // 被取消的构建已经由 stopBuild 记录为失败，不再重复发结束通知
                if (!isBuildCancelled(logId)) {
                    publishBuildResult(buildLog);
                }
            }
        } finally {
            IOUtils.closeQuietly(client);
            // 构建上下文已完整传给 Docker（成功、失败、取消都会走到这里），代码目录用后即删；
            // 再全量检查一次克隆根目录，清掉进程被强杀等异常退出留下的残留（保留 10 天）
            deleteWorkDir(workDir);
            GitTool.cleanupCloneDirs();
            if (slotAcquired) {
                buildingProjects.remove(projectId);
            }
            buildThreadMap.remove(logId);
            cancelledBuilds.remove(logId);
            FileLog.unbind();
            taskLogRegistry.finish(logId);
        }
    }

    /** 构建线程是否已被取消（手动停止、被新构建覆盖）。 */
    private boolean isBuildCancelled(String logId) {
        return cancelledBuilds.contains(logId);
    }

    /** 抛出构建结束事件（成功/失败），邮件通知等旁路逻辑监听此事件。 */
    private void publishBuildResult(BuildLog buildLog) {
        if (buildLog == null) {
            return;
        }
        BuildResultEvent resultEvent = new BuildResultEvent(this);
        resultEvent.setBuildLog(buildLog);
        applicationEventPublisher.publishEvent(resultEvent);
    }

    /** 删除本次构建的代码目录，并记入构建日志。 */
    private void deleteWorkDir(File workDir) {
        if (workDir == null || !workDir.exists()) {
            return;
        }
        long size = FileUtil.size(workDir);
        if (GitTool.deleteCloneDir(workDir)) {
            fileLog.info("已删除代码目录 {}（{}）", workDir.getAbsolutePath(), FileUtil.readableFileSize(size));
        } else {
            fileLog.warn("删除代码目录失败 {}", workDir.getAbsolutePath());
        }
    }

    private GitTool.CloneResult gitClone(Project project, String ref) throws GitAPIException {
        GitCredential credential = codeSourceService.credentialByGitUrl(project.getGitUrl());

        fileLog.info("代码下载中...");
        return GitTool.clone(project.getName(), project.getGitUrl(), credential, ref);
    }


    @Transactional
    public void deleteProject(String id) {
        Project project = projectRepository.findById(id).orElse(null);
        String imageUrl = getFullImageUrl(project);

        List<App> apps = StrUtil.isBlank(imageUrl) ? List.of() : appRepository.findAllByImageUrl(imageUrl);
        Assert.state(apps.isEmpty(), "该项目下还有 " + apps.size() + " 个应用，请先删除应用");

        if (project != null && Boolean.TRUE.equals(project.getWebhookAuto())) {
            try {
                CodeSource source = codeSourceService.findByGitUrl(project.getGitUrl());
                if (source != null) {
                    codeSourceApiService.disableProjectHook(source,
                            CodeSourceApiService.projectPath(source, project.getGitUrl()), project.getWebhookHookId());
                }
            } catch (Exception e) {
                log.warn("删除项目时清理远程 Webhook 失败，请到代码仓库手动清理：{}", e.getMessage());
            }
        }

        List<BuildLog> logList = buildLogService.findByProject(id);

        for (BuildLog buildLog : logList) {
            buildLogService.deleteById(buildLog.getId());
        }

        imageTagService.deleteByImageUrl(imageUrl);
        imageService.deleteByImageUrl(imageUrl);
        if (project != null && !buildingProjects.contains(id)) {
            // 代码目录按项目名存放，删除项目后一并清掉（构建中的目录不删，避免打断正在跑的构建；
            // 改名遗留的旧目录由构建结束时的兜底清理处理）
            GitTool.deleteCloneDir(GitTool.cloneDir(project.getName()));
        }
        projectRepository.deleteById(id);
    }


    @Transactional
    public void cleanErrorLog(String projectId) {
        buildLogService.cleanErrorLog(projectId);
    }

    /**
     * 该项目产出的镜像版本（tag），倒序。
     */
    public List<String> tags(String projectId) {
        Project project = projectRepository.findById(projectId).orElse(null);
        return imageTagService.tags(getFullImageUrl(project));
    }

    /**
     * 该项目产出的镜像版本明细（含来源、构建时间、大小等），倒序。
     */
    public List<ImageTag> versionList(String projectId) {
        Project project = projectRepository.findById(projectId).orElse(null);
        return imageTagService.listByImageUrl(getFullImageUrl(project));
    }

    /**
     * 使用该项目镜像的应用。
     */
    public List<App> apps(String projectId) {
        Project project = projectRepository.findById(projectId).orElse(null);
        String imageUrl = getFullImageUrl(project);
        if (StrUtil.isBlank(imageUrl)) {
            return List.of();
        }
        return appRepository.findAllByImageUrl(imageUrl);
    }

    public Page<Project> findAll(String searchText, Pageable pageable) {
        if (StrUtil.isNotEmpty(searchText)) {
            Spec<Project> q = Spec.of();
            q.like("name", "%" + searchText.trim() + "%");
            return projectRepository.findAll(q, pageable);
        }
        return projectRepository.findAll(pageable);
    }

}
