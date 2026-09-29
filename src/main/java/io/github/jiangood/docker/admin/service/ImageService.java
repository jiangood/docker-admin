package io.github.jiangood.docker.admin.service;


import cn.hutool.core.date.BetweenFormatter;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.unit.DataSizeUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.BuildImageCmd;
import com.github.dockerjava.api.command.PushImageCmd;
import com.github.dockerjava.api.model.BuildResponseItem;
import io.github.jiangood.docker.base.tool.GitTool;
import io.github.jiangood.docker.admin.BuildSuccessEvent;
import io.github.jiangood.docker.admin.dao.AppRepository;
import io.github.jiangood.docker.admin.dao.ImageRepository;
import io.github.jiangood.docker.admin.dto.BuildRequest;
import io.github.jiangood.docker.admin.entity.App;
import io.github.jiangood.docker.admin.entity.BuildLog;
import io.github.jiangood.docker.admin.entity.GitCredential;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.entity.Image;
import io.github.jiangood.docker.admin.entity.Registry;
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
public class ImageService extends BaseService<Image> {

    private final ImageRepository imageRepository;

    @Resource
    RegistryService registryService;


    @Resource
    HostService hostService;

    @Resource
    DockerClientManager dockerService;

    @Resource
    GitCredentialService gitCredentialService;

    @Resource
    BuildLogService buildLogService;

    @Resource
    ImageVersionService imageVersionService;

    @Resource
    AppRepository appRepository;

    @Resource
    private ApplicationEventPublisher applicationEventPublisher;

    private final Map<String, DefaultCallback> buildThreadMap = new ConcurrentHashMap<>();


    /**
     * 镜像完整地址：registry.url/namespace/name。未配置注册中心时仅返回镜像名。
     */
    public String getFullImageUrl(Image image) {
        if (image == null) {
            return null;
        }
        Registry registry = registryService.getEffective();
        if (registry == null) {
            return image.getName();
        }
        return registry.getUrl() + "/" + registry.getNamespace() + "/" + image.getName();
    }

    /**
     * 保存镜像：新建走 create，编辑走 update。
     */
    @Transactional
    public Image saveImage(Image input, List<String> updateFields) {
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
     * 拉取镜像对应远程仓库的 tag 列表。
     */
    public List<String> listRemoteTags(Image image) {
        GitCredential credential = gitCredentialService.findBestByUrl(image.getGitUrl());
        String username = credential == null ? null : credential.getUsername();
        String password = credential == null ? null : credential.getPassword();
        try {
            return GitTool.listRemoteTags(image.getGitUrl(), username, password);
        } catch (GitAPIException e) {
            throw new BusinessException("获取远程 tag 失败：" + e.getMessage(), e);
        }
    }

    /**
     * 用指定 tag 触发构建（Webhook 使用，构建节点取系统默认 runner）。
     */
    public void buildByTag(Image image, String tag) {
        Assert.isTrue(isValidTag(tag), "tag 格式不正确，需形如 v1.0.1");
        Host runner = hostService.getDefaultDockerRunner();
        Assert.notNull(runner, "未配置构建节点（runner），请先在【主机】中设置");

        BuildRequest req = new BuildRequest();
        req.setImageId(image.getId());
        req.setTag(tag);
        req.setDockerfile(image.getDockerfile());
        req.setBuildHostId(runner.getId());
        try {
            buildImage(req);
        } catch (IOException e) {
            throw new BusinessException("触发构建失败：" + e.getMessage(), e);
        }
    }

    /**
     * Webhook 入口：按 token 找到镜像并用推送的 tag 触发构建。
     */
    public void triggerByToken(String token, String tag) {
        Assert.hasText(tag, "未从 Webhook 请求中解析到 tag");
        Assert.isTrue(isValidTag(tag), "tag 格式不正确，需形如 v1.0.1：" + tag);
        Image image = imageRepository.findByWebhookToken(token);
        Assert.notNull(image, "无效的 webhook token");
        buildByTag(image, tag);
    }

    public void buildImage(BuildRequest p) throws IOException {
        List<BuildLog> processing = buildLogService.findByImageProcessing(p.getImageId());

        for (BuildLog buildLog : processing) {
            stopBuild(buildLog.getId());
        }

        // 通过代理调用 @Async 方法，避免自调用失效
        SpringUtil.getBean(ImageService.class).buildImageJob(p);
    }


    @Async
    public void buildImageJob(BuildRequest p) {
        String tag = p.getTag();
        String imageId = p.getImageId();
        String context = p.getContext();
        String dockerfile = p.getDockerfile();


        Image image = imageRepository.findById(imageId).orElse(null);
        BuildLog buildLog = new BuildLog();
        buildLog.setImageId(image.getId());
        buildLog.setImageName(image.getName());
        buildLog.setDockerfile(image.getDockerfile());
        buildLog.setTag(tag);
        buildLog = buildLogService.saveLog(buildLog);
        String logId = buildLog.getId();


        DockerClient client = null;
        MDC.put("logFileId", logId);
        try {

            log.info("开始构建镜像任务, 镜像：{}， 仓库：{}， tag：{}", image.getName(), image.getGitUrl(), tag);

            Host host = hostService.findById(p.getBuildHostId()).orElse(null);

            Assert.notNull(host, "无构建主机");
            log.info("构建主机信息... 名称：{}, host:{}, 备注:{}", host.getName(), host.getDockerHost(), StrUtil.emptyIfNull(host.getRemark()));

            GitTool.CloneResult cloneResult = gitClone(image, tag);
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


            String imageUrl = registry.getUrl() + "/" + registry.getNamespace() + "/" + image.getName();

            String imageTag = imageUrl + ":" + tag;

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


            if (StrUtil.isNotEmpty(image.getBuildArg())) {
                Map<String, String> buildArgsMap = UriComponentsBuilder.newInstance().query(image.getBuildArg()).build().getQueryParams().toSingleValueMap();
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

            // 记录镜像声明的端口与卷，容器配置只允许使用这些声明项
            try {
                imageVersionService.inspect(client, imageTag, image.getId(), tag);
            } catch (Exception e) {
                log.warn("读取镜像声明失败: {}", e.getMessage());
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
            log.info("抛出构建事件 {}", event.getBuildLog().getImageName());

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
        }
    }

    private GitTool.CloneResult gitClone(Image image, String tag) throws GitAPIException {
        String username = null;
        String password = null;
        GitCredential credential = gitCredentialService.findBestByUrl(image.getGitUrl());
        if (credential != null) {
            username = credential.getUsername();
            password = credential.getPassword();
        }

        log.info("代码下载中...");
        return GitTool.clone(image.getGitUrl(), username, password, tag);
    }


    @Transactional
    public void deleteImage(String id) {
        List<App> apps = appRepository.findAllByImage_Id(id);
        Assert.state(apps.isEmpty(), "该镜像下还有 " + apps.size() + " 个应用，请先删除应用");

        List<BuildLog> logList = buildLogService.findByImage(id);

        for (BuildLog buildLog : logList) {
            buildLogService.deleteById(buildLog.getId());
        }

        imageVersionService.removeByImageId(id);
        imageRepository.deleteById(id);
    }


    @Transactional
    public void cleanErrorLog(String imageId) {
        buildLogService.cleanErrorLog(imageId);
    }

    /**
     * 该镜像的版本（tag），倒序。
     */
    public List<String> tags(String imageId) {
        return imageVersionService.tags(imageId);
    }

    /**
     * 使用该镜像的应用。
     */
    public List<App> apps(String imageId) {
        if (StrUtil.isBlank(imageId)) {
            return List.of();
        }
        return appRepository.findAllByImage_Id(imageId);
    }

    public Page<Image> findAll(String searchText, Pageable pageable) {
        if (StrUtil.isNotEmpty(searchText)) {
            Spec<Image> q = Spec.of();
            q.like("name", "%" + searchText.trim() + "%");
            return imageRepository.findAll(q, pageable);
        }
        return imageRepository.findAll(pageable);
    }

}
