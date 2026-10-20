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

    private final Map<String, DefaultCallback> buildThreadMap = new ConcurrentHashMap<>();


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
        DefaultCallback callback = buildThreadMap.remove(logId);
        if (callback != null) {
            callback.close();
        }

        BuildLog buildLog = buildLogService.findById(logId).orElse(null);
        if (buildLog == null) {
            return;
        }

        buildLog.setSuccess(false);
        buildLog.setCompleteTime(LocalDateTime.now());
        buildLog.setTimeSpend(Duration.between(buildLog.getCreateTime(), buildLog.getCompleteTime()).toMillis());
        buildLogService.save(buildLog);


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
     * 每次构建成功后统一追加推送的版本号。
     */
    private static final String LATEST_TAG = "latest";

    /**
     * 分支构建的镜像版本号：分支名（非法字符替换为 -）+ 提交短 id，如 {@code feature-x-1a2b3c4d}。
     * <p>
     * Docker tag 不允许 {@code /} 等字符，这里做替换并按长度上限截断，保证可推送。
     */
    static String buildBranchImageTag(String branch, String commitId) {
        String name = StrUtil.nullToEmpty(branch).trim()
                .replaceAll("[^A-Za-z0-9_.-]", "-")
                .replaceAll("^[-.]+", "")
                .replaceAll("[-.]+$", "");
        if (StrUtil.isBlank(name)) {
            name = "branch";
        }
        String suffix = StrUtil.isBlank(commitId) ? "" : "-" + commitId;
        int maxNameLength = DOCKER_TAG_MAX_LENGTH - suffix.length();
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
     *
     * @return 构建记录 id
     */
    public String buildImage(BuildRequest p) throws IOException {
        List<BuildLog> processing = buildLogService.findByProjectProcessing(p.getProjectId());

        for (BuildLog buildLog : processing) {
            stopBuild(buildLog.getId());
        }

        BuildLog buildLog = createProcessingLog(p);

        // 任务在调度前就标记为运行中：前端触发后会立刻打开日志窗口，
        // 若此时尚未标记，日志 WebSocket 会误判任务已结束而提前关闭连接。
        taskLogRegistry.start(buildLog.getId());

        // 通过代理调用 @Async 方法，避免自调用失效
        SpringUtil.getBean(ProjectService.class).buildImageJob(p, buildLog.getId());
        return buildLog.getId();
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
        buildLog.setDockerfile(project.getDockerfile());
        buildLog.setContext(BuildContextUtils.display(project.getContext()));
        buildLog.setTag(branchBuild ? branch : p.getTag());
        buildLog.setBranch(branch);
        return buildLogService.saveLog(buildLog);
    }


    @Async
    public void buildImageJob(BuildRequest p, String logId) {
        String tag = p.getTag();
        String projectId = p.getProjectId();
        String context = p.getContext();
        String dockerfile = p.getDockerfile();

        // 分支构建：克隆分支最新代码，镜像版本号由「分支名 + 提交短 id」生成
        boolean branchBuild = BuildRequest.REF_TYPE_BRANCH.equals(p.getRefType())
                && StrUtil.isNotBlank(p.getBranch());
        String branch = branchBuild ? p.getBranch().trim() : null;
        String cloneRef = branchBuild ? branch : tag;

        Project project = projectRepository.findById(projectId).orElse(null);
        BuildLog buildLog = buildLogService.findById(logId).orElse(null);
        Assert.notNull(buildLog, "构建记录不存在");
        // 任务已在 buildImage() 调度前通过 taskLogRegistry.start() 标记为运行中


        DockerClient client = null;
        File workDir = null;
        FileLog.bind(logId);
        try {

            fileLog.info("开始构建镜像任务, 项目：{}， 仓库：{}， 来源：{}", project.getName(), project.getGitUrl(),
                    branchBuild ? "分支 " + branch : "tag " + tag);

            Host host = hostService.findById(p.getBuildHostId()).orElse(null);

            Assert.notNull(host, "无构建主机");
            fileLog.info("构建主机信息... 名称：{}, host:{}", host.getName(), host.getDockerHost());

            GitTool.CloneResult cloneResult = gitClone(project, cloneRef);
            workDir = cloneResult.getDir();
            fileLog.info("代码下载完毕 " + workDir);
            fileLog.info("代码提交信息: {}", cloneResult.getCodeMessage());
            Date commitTime = Date.from(cloneResult.getCommitTime().atZone(ZoneId.systemDefault()).toInstant());
            fileLog.info("代码提交时间: {}, {}前", DateUtil.formatDateTime(commitTime), DateUtil.formatBetween(commitTime, new Date(), BetweenFormatter.Level.MINUTE));
            fileLog.info("代码文件大小: {}", DataSizeUtil.format(FileUtil.size(workDir)));

            // 镜像版本号：tag 构建用所选 tag，分支构建用「分支名 + 提交短 id」
            String versionTag = branchBuild ? buildBranchImageTag(branch, cloneResult.getCommitId()) : tag;
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


            // 判断是构建被中途取消，如手动取消，重复构建取消
            if (!buildThreadMap.containsKey(logId)) {
                fileLog.info("构建被取消");
                FileLog.unbind();
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

            fileLog.info("构建阶段结束");
        } catch (Exception e) {
            fileLog.info("异常 {} {}", e.getClass().getName(), e.getMessage());
            fileLog.error("构建失败", e);

            buildLog.setSuccess(false);
            buildLog.setCompleteTime(LocalDateTime.now());
            buildLog.setTimeSpend(Duration.between(buildLog.getCreateTime(), buildLog.getCompleteTime()).toMillis());
            buildLogService.save(buildLog);
        } finally {
            IOUtils.closeQuietly(client);
            // 构建上下文已完整传给 Docker（成功、失败、取消都会走到这里），代码目录用后即删，
            // 否则多次构建后历史克隆会一直堆积；进程被强杀等异常退出留下的目录由 CleanGitCodeJob 兜底。
            deleteWorkDir(workDir);
            FileLog.unbind();
            taskLogRegistry.finish(logId);
        }
    }

    /** 删除本次构建的代码目录，并记入构建日志。 */
    private void deleteWorkDir(File workDir) {
        if (workDir == null || !workDir.exists()) {
            return;
        }
        long size = FileUtil.size(workDir);
        if (FileUtil.del(workDir)) {
            fileLog.info("已删除代码目录 {}（{}）", workDir.getAbsolutePath(), FileUtil.readableFileSize(size));
        } else {
            fileLog.warn("删除代码目录失败 {}", workDir.getAbsolutePath());
        }
    }

    private GitTool.CloneResult gitClone(Project project, String ref) throws GitAPIException {
        GitCredential credential = codeSourceService.credentialByGitUrl(project.getGitUrl());

        fileLog.info("代码下载中...");
        return GitTool.clone(project.getGitUrl(), credential, ref);
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
