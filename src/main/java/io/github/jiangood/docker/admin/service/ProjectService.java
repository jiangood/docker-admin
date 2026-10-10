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
import io.github.jiangood.docker.admin.entity.Project;
import io.github.jiangood.docker.admin.entity.Registry;
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
import org.slf4j.MDC;
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
    ImageRepoService imageRepoService;

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
        return ImageUrlUtils.repoUrl(registryService.getEffective(), project.getName());
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

    public void buildImage(BuildRequest p) throws IOException {
        List<BuildLog> processing = buildLogService.findByProjectProcessing(p.getProjectId());

        for (BuildLog buildLog : processing) {
            stopBuild(buildLog.getId());
        }

        // 通过代理调用 @Async 方法，避免自调用失效
        SpringUtil.getBean(ProjectService.class).buildImageJob(p);
    }


    @Async
    public void buildImageJob(BuildRequest p) {
        String tag = p.getTag();
        String projectId = p.getProjectId();
        String context = p.getContext();
        String dockerfile = p.getDockerfile();


        Project project = projectRepository.findById(projectId).orElse(null);
        BuildLog buildLog = new BuildLog();
        buildLog.setProjectId(project.getId());
        buildLog.setProjectName(project.getName());
        buildLog.setDockerfile(project.getDockerfile());
        buildLog.setTag(tag);
        buildLog = buildLogService.saveLog(buildLog);
        String logId = buildLog.getId();
        taskLogRegistry.start(logId);


        DockerClient client = null;
        MDC.put("logFileId", logId);
        try {

            log.info("开始构建镜像任务, 项目：{}， 仓库：{}， tag：{}", project.getName(), project.getGitUrl(), tag);

            Host host = hostService.findById(p.getBuildHostId()).orElse(null);

            Assert.notNull(host, "无构建主机");
            log.info("构建主机信息... 名称：{}, host:{}", host.getName(), host.getDockerHost());

            GitTool.CloneResult cloneResult = gitClone(project, tag);
            File workDir = cloneResult.getDir();
            log.info("代码下载完毕 " + workDir);
            log.info("代码提交信息: {}", cloneResult.getCodeMessage());
            Date commitTime = Date.from(cloneResult.getCommitTime().atZone(ZoneId.systemDefault()).toInstant());
            log.info("代码提交时间: {}, {}前", DateUtil.formatDateTime(commitTime), DateUtil.formatBetween(commitTime, new Date(), BetweenFormatter.Level.MINUTE));
            log.info("代码文件大小: {}", DataSizeUtil.format(FileUtil.size(workDir)));


            log.info("dockerfile {},  内容如下", dockerfile);


            buildLog.setBuildHostName(host.getName());
            buildLog.setBuildHostId(host.getId());
            buildLog.setCodeMessage(cloneResult.getCodeMessage());
            buildLog = buildLogService.saveLog(buildLog);

            Registry registry = registryService.getEffective();
            Assert.notNull(registry, "未配置镜像注册中心，请先在【设置-镜像注册中心】中配置");
            log.info("注册中心：{}", registry.getFullUrl());
            client = dockerService.getClient(host, registry);


            String imageUrl = ImageUrlUtils.repoUrl(registry, project.getName());

            String imageTag = ImageUrlUtils.full(imageUrl, tag);

            log.info("目标镜像： {}", imageTag);
            Assert.state(!StrUtil.containsBlank(imageUrl), "镜像路径不能包含空格");


            buildLog.setImageUrl(imageUrl);
            File buildDir = new File(workDir, context);

            log.info("向docker发送构建指令");
            DefaultCallback<BuildResponseItem> buildCallback = new DefaultCallback<>(logId);
            buildThreadMap.put(logId, buildCallback);

            log.info("是否拉取基础镜像 withPull {}", p.isPull());
            log.info("是否使用缓存 {}", p.isUseCache());
            File dockerfileFile = new File(buildDir, dockerfile);
            log.info("dockerfile绝对路径: {}", dockerfileFile.getAbsolutePath());
            log.info("是否拉取基础镜像:{}", p.isPull());


            log.info("构建命令执行中...");
            BuildImageCmd buildImageCmd = client.buildImageCmd(buildDir)
                    // 删除构建产生的容器
                    .withForcerm(true)
                    .withPull(p.isPull())
                    .withNetworkMode("host")
                    .withTags(Collections.singleton(imageTag))
                    .withNoCache(!p.isUseCache())
                    .withDockerfile(dockerfileFile);


            if (StrUtil.isNotEmpty(project.getBuildArg())) {
                Map<String, String> buildArgsMap = UriComponentsBuilder.newInstance().query(project.getBuildArg()).build().getQueryParams().toSingleValueMap();
                for (Map.Entry<String, String> e : buildArgsMap.entrySet()) {
                    log.info("构建参数: {}={}", e.getKey(), e.getValue());
                    buildImageCmd.withBuildArg(e.getKey(), e.getValue());
                }
            }


            buildImageCmd.exec(buildCallback).awaitCompletion();
            log.info("构建命令执行完毕");


            // 判断是构建被中途取消，如手动取消，重复构建取消
            if (!buildThreadMap.containsKey(logId)) {
                log.info("构建被取消");
                MDC.remove("logFileId");
                return;
            }
            log.info("镜像构建结束 ");
            buildThreadMap.remove(logId);

            // 推送
            log.info("推送镜像 {}", imageTag);
            PushImageCmd pushImageCmd = client.pushImageCmd(imageTag);
            pushImageCmd.exec(new DefaultCallback<>(logId)).awaitCompletion();
            log.info("推送镜像结束 {}", imageTag);

            // 登记镜像仓库与标签，并记录镜像声明的端口与卷
            try {
                imageRepoService.upsert(imageUrl, project.getName(), "BUILD", registry.getId(), project.getSysOrg());
                imageTagService.inspect(client, imageTag, imageUrl, tag, buildLog.getId(), "BUILD");
            } catch (Exception e) {
                log.warn("登记镜像失败: {}", e.getMessage());
            }


            buildLog.setSuccess(true);
            buildLog.setCompleteTime(LocalDateTime.now());
            buildLog.setTimeSpend(Duration.between(buildLog.getCreateTime(), buildLog.getCompleteTime()).toMillis());
            buildLog = buildLogService.save(buildLog);
            log.info("已更新构建日志{}", buildLog);


            BuildSuccessEvent event = new BuildSuccessEvent(this);
            event.setBuildLog(buildLog);
            event.setTag(tag);

            applicationEventPublisher.publishEvent(event);
            log.info("抛出构建事件 {}", event.getBuildLog().getProjectName());

            log.info("构建阶段结束");
        } catch (Exception e) {
            log.info("异常 {} {}", e.getClass().getName(), e.getMessage());


            if (e instanceof IllegalArgumentException && e.getMessage() != null && e.getMessage().contains("Dockerfile does not exist")) {
                log.info("请确保项目下至少有一个Dockerfile文件，不论是否指定其他Dockerfile");
            }
            log.error("构建失败", e);

            buildLog.setSuccess(false);
            buildLog.setCompleteTime(LocalDateTime.now());
            buildLog.setTimeSpend(Duration.between(buildLog.getCreateTime(), buildLog.getCompleteTime()).toMillis());
            buildLogService.save(buildLog);
        } finally {
            IOUtils.closeQuietly(client);
            MDC.remove("logFileId");
            taskLogRegistry.finish(logId);
        }
    }

    private GitTool.CloneResult gitClone(Project project, String tag) throws GitAPIException {
        GitCredential credential = codeSourceService.credentialByGitUrl(project.getGitUrl());

        log.info("代码下载中...");
        return GitTool.clone(project.getGitUrl(), credential, tag);
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
        imageRepoService.deleteByImageUrl(imageUrl);
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
