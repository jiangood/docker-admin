package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.command.PushImageCmd;
import com.github.dockerjava.api.model.AuthConfig;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.api.model.PushResponseItem;
import io.github.jiangood.docker.admin.dto.ImageSyncRequest;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.entity.ImageSyncLog;
import io.github.jiangood.docker.admin.entity.Registry;
import io.github.jiangood.docker.admin.websocket.TaskLogRegistry;
import io.github.jiangood.docker.base.FileLog;
import io.github.jiangood.docker.sdk.engine.DefaultCallback;
import io.github.jiangood.docker.sdk.engine.DockerClientManager;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 镜像同步：借助一台网络通畅的主机拉取公共镜像，推送到平台注册中心，
 * 再让各目标主机从注册中心拉取、重新打标签。
 * <p>
 * 目标主机不走 save/load 直传：push/pull 是 daemon 原生协议、带进度回调，
 * 也不要求同步主机与目标主机之间有大带宽直连。
 * <p>
 * 不落库；同步过程写入 {@code /data/logs/{logId}.log}，前端通过 WebSocket 实时查看。
 */
@Service
@RequiredArgsConstructor
public class ImageSyncService {

    private static final Logger fileLog = FileLog.logger();

    @Resource
    TaskLogRegistry taskLogRegistry;

    @Resource
    HostService hostService;

    @Resource
    RegistryService registryService;

    @Resource
    ImageService imageService;

    @Resource
    ImageTagService imageTagService;

    @Resource
    DockerClientManager dockerService;

    @Resource
    ImageSyncLogService syncLogService;

    public boolean isRunning(String logId) {
        return taskLogRegistry.isRunning(logId);
    }

    /**
     * 同步前的参数校验。同步执行，便于把错误直接返回给前端
     * （{@link #sync} 是异步方法，其中的异常只会写进同步日志）。
     */
    public void validate(ImageSyncRequest p) {
        Assert.hasText(StrUtil.trim(p.getSourceImage()), "请填写源镜像");
        String syncType = syncTypeOf(p);
        if (ImageSyncLog.TYPE_REGISTRY.equals(syncType)) {
            Assert.hasText(StrUtil.trim(p.getHostId()), "请选择执行主机");
            return;
        }
        Assert.hasText(StrUtil.trim(p.getTargetHostId()), "请选择目标主机");
        if (ImageSyncLog.TYPE_RELAY.equals(syncType)) {
            Assert.hasText(StrUtil.trim(p.getHostId()), "请选择中转的执行主机");
        }
    }

    /**
     * 同步类型：未知或为空时按「同步到注册中心」处理。
     */
    public static String syncTypeOf(ImageSyncRequest p) {
        String type = StrUtil.trim(p.getSyncType());
        if (ImageSyncLog.TYPE_HOST.equals(type) || ImageSyncLog.TYPE_RELAY.equals(type)) {
            return type;
        }
        return ImageSyncLog.TYPE_REGISTRY;
    }

    /**
     * 先落库「同步中」记录再起异步任务：接口返回时页面刷新即可看到这条记录。
     */
    public ImageSyncLog startRecord(String logId, ImageSyncRequest p) {
        return syncLogService.start(logId, syncTypeOf(p), p.getSourceImage(), StrUtil.trim(p.getHostId()),
                StrUtil.trim(p.getTargetHostId()));
    }

    @Async
    public void sync(String logId, ImageSyncRequest p, ImageSyncLog syncLog) {
        taskLogRegistry.start(logId);
        FileLog.bind(logId);
        boolean success = false;
        String error = null;
        try {
            validate(p);

            String source = StrUtil.trim(p.getSourceImage());
            fileLog.info("开始同步镜像");
            fileLog.info("源镜像: {}", source);

            switch (syncTypeOf(p)) {
                case ImageSyncLog.TYPE_HOST -> pullToHosts(logId, p, syncLog);
                case ImageSyncLog.TYPE_RELAY -> relayToHosts(logId, p, syncLog);
                default -> pushToRegistry(logId, p, syncLog);
            }

            fileLog.info("镜像同步成功");
            success = true;
        } catch (Exception e) {
            error = e.getMessage();
            fileLog.error("镜像同步失败: {}", e.getMessage(), e);
        } finally {
            // 先写库再结束任务：日志 WS 断开后前端刷新列表即可拿到终态
            try {
                syncLogService.finish(syncLog, success, error);
            } catch (Exception e) {
                fileLog.warn("写入同步记录失败: {}", e.getMessage());
            }
            FileLog.unbind();
            taskLogRegistry.finish(logId);
        }
    }

    /**
     * 同步到注册中心：执行主机拉取源镜像，打上注册中心前缀后推送，并登记镜像与标签。
     */
    private void pushToRegistry(String logId, ImageSyncRequest p, ImageSyncLog syncLog) throws InterruptedException {
        String source = StrUtil.trim(p.getSourceImage());

        Host host = hostService.findById(p.getHostId()).orElse(null);
        Assert.notNull(host, "同步主机不存在，请选择一台网络通畅的主机");
        syncLog.setHostName(host.getName());

        // 镜像先推到注册中心，因此注册中心是硬依赖
        Registry registry = registryService.requireRegistry(p.getTargetRegistryId());
        syncLog.setRegistryUrl(registry.getFullUrl());

        fileLog.info("同步主机: {} ({})", host.getName(), StrUtil.blankToDefault(host.getDockerHost(), "本机"));

        // 拆分源镜像的 repo 与 tag（默认 latest），处理 registry 带端口的情况
        String repo = repoOf(source);
        String tag = tagOf(source);
        String targetName = lastSegment(repo);

        String registryBase = StrUtil.removeSuffix(StrUtil.nullToEmpty(registry.getFullUrl()), "/");
        Assert.hasText(registryBase, "注册中心地址未配置，请先在【设置-镜像注册中心】中配置地址与命名空间");
        String targetRepo = registryBase + "/" + targetName;
        Assert.state(!StrUtil.containsBlank(targetRepo), "镜像路径不能包含空格");
        String targetImage = targetRepo + ":" + tag;
        syncLog.setTargetImage(targetImage);

        // 注意：客户端不能携带注册中心凭据。docker-java 会把客户端级凭据
        // （registry.url/username/password）用于所有 pull，拉取 Docker Hub 等公共镜像时
        // 也会带上注册中心的账号密码，导致 401 incorrect username or password。
        // 推送到注册中心时再通过 PushImageCmd.withAuthConfig 单独传凭据。
        DockerClient client = null;
        try {
            client = dockerService.getClient(host);

            // 注意：docker-java 的 pullImageCmd 不会拆分 tag——传入 "nginx" 时，
            // 请求为 fromImage=nginx&tag=（空），Docker 侧会把「空 tag」理解为
            // 「拉取该仓库的全部 tag」，于是逐个拉取 nginx:1、nginx:1-alpine……
            // 这里显式传入已解析出的 repo 与 tag，只拉取指定版本。
            String sourceRef = repo + ":" + tag;
            fileLog.info("拉取源镜像 {}", sourceRef);
            // 源镜像凭据由镜像地址自动推导：地址与本平台某个注册中心同 host（且命名空间前缀匹配）时
            // 带上该注册中心的账号密码，Docker Hub 等公共镜像则匿名拉取——
            // 与部署时拉取镜像的规则一致（见 RegistryService#forImageUrl）。
            PullImageCmd pullCmd = client.pullImageCmd(repo).withTag(tag);
            Registry sourceRegistry = registryService.forImageUrl(source);
            AuthConfig sourceAuth = authOf(sourceRegistry);
            if (sourceAuth != null) {
                pullCmd.withAuthConfig(sourceAuth);
                fileLog.info("源镜像认证注册中心: {}", sourceRegistry.getUrl());
            }
            pullCmd.exec(new DefaultCallback<PullResponseItem>(logId)).awaitCompletion();
            fileLog.info("拉取完成 {}", sourceRef);

            // 推送目的地由镜像名决定：本地必须先打上带注册中心地址的完整 tag，
            // 否则 docker 推送时会报 "tag does not exist"。
            fileLog.info("注册中心: {}", registryBase);
            fileLog.info("推送目标镜像 {}", targetImage);
            client.tagImageCmd(sourceRef, targetRepo, tag).exec();

            fileLog.info("推送到注册中心 {}", targetImage);
            AuthConfig auth = authOf(registry);
            PushImageCmd pushCmd = client.pushImageCmd(targetImage);
            if (auth != null) {
                pushCmd.withAuthConfig(auth);
            }
            pushCmd.exec(new DefaultCallback<PushResponseItem>(logId)).awaitCompletion();
            fileLog.info("注册中心同步成功: {}", targetImage);

            // 登记镜像与标签。镜像此刻已在同步主机本地（推送注册中心时打过 targetRepo:tag），
            // 直接 inspect 读取大小与声明的端口/卷，与项目构建的登记方式一致；
            // inspect 拿不到（本地查不到等）退回只登记标签，登记失败也不影响已完成的同步。
            try {
                imageService.upsert(targetRepo, targetName, "SYNC", registry.getId(), null);
                var saved = imageTagService.inspect(client, targetImage, targetRepo, tag, null, "SYNC");
                if (saved == null) {
                    imageTagService.upsert(targetRepo, tag, "SYNC");
                }
            } catch (Exception e) {
                fileLog.warn("登记镜像失败: {}", e.getMessage());
            }
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    /**
     * 同步到主机：目标主机直接拉取源镜像，不经过平台注册中心。
     */
    private void pullToHosts(String logId, ImageSyncRequest p, ImageSyncLog syncLog) throws InterruptedException {
        String source = StrUtil.trim(p.getSourceImage());
        Host targetHost = requireTargetHost(p, syncLog);

        String repo = repoOf(source);
        String tag = tagOf(source);

        // 源镜像凭据由镜像地址自动推导（公共镜像匿名拉取），与部署时拉取镜像的规则一致
        Registry sourceRegistry = registryService.forImageUrl(source);
        AuthConfig auth = authOf(sourceRegistry);
        if (auth != null) {
            fileLog.info("源镜像认证注册中心: {}", sourceRegistry.getUrl());
        }
        fileLog.info("目标主机: {} ({})", targetHost.getName(),
                StrUtil.blankToDefault(targetHost.getDockerHost(), "本机"));

        DockerClient client = null;
        try {
            client = dockerService.getClient(targetHost);

            // docker-java 不会拆分 tag，repo 与 tag 必须分开传，
            // 否则空 tag 会被 Docker 理解成「拉取该仓库的全部 tag」。
            fileLog.info("拉取镜像 {}:{}", repo, tag);
            PullImageCmd pullCmd = client.pullImageCmd(repo).withTag(tag);
            if (auth != null) {
                pullCmd.withAuthConfig(auth);
            }
            pullCmd.exec(new DefaultCallback<PullResponseItem>(logId)).awaitCompletion();

            fileLog.info("目标主机 {} 同步成功: {}:{}", targetHost.getName(), repo, tag);
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    /**
     * 解析目标主机并把名称快照写进记录（主机被删除后记录仍然可读）。
     */
    private Host requireTargetHost(ImageSyncRequest p, ImageSyncLog syncLog) {
        Host targetHost = hostService.findById(StrUtil.trim(p.getTargetHostId())).orElse(null);
        Assert.notNull(targetHost, "目标主机不存在，请重新选择");
        syncLog.setTargetHostName(targetHost.getName());
        return targetHost;
    }

    /**
     * 源镜像的仓库名（不含 tag）。
     */
    private static String repoOf(String source) {
        int lastSlash = source.lastIndexOf('/');
        int lastColon = source.lastIndexOf(':');
        return lastColon > lastSlash ? source.substring(0, lastColon) : source;
    }

    /**
     * 源镜像的 tag，未写时默认 latest。
     */
    private static String tagOf(String source) {
        int lastSlash = source.lastIndexOf('/');
        int lastColon = source.lastIndexOf(':');
        return lastColon > lastSlash ? source.substring(lastColon + 1) : "latest";
    }

    /**
     * 同步到主机（中转）：中转主机拉取源镜像并打包，再导入到目标主机。
     * <p>
     * 适合目标主机访问不了镜像仓库、但平台能同时连上中转主机与目标主机的场景。
     * 镜像只从源仓库下载一次，中转文件落在平台临时目录，结束即删除。
     * 单台失败不影响其他主机，全部结束后汇总结果。
     */
    private void relayToHosts(String logId, ImageSyncRequest p, ImageSyncLog syncLog) throws InterruptedException {
        String source = StrUtil.trim(p.getSourceImage());
        Host relayHost = hostService.findById(p.getHostId()).orElse(null);
        Assert.notNull(relayHost, "中转主机不存在，请重新选择");
        syncLog.setHostName(relayHost.getName());

        Host targetHost = requireTargetHost(p, syncLog);

        String repo = repoOf(source);
        String tag = tagOf(source);
        Registry sourceRegistry = registryService.forImageUrl(source);
        AuthConfig auth = authOf(sourceRegistry);
        if (auth != null) {
            fileLog.info("源镜像认证注册中心: {}", sourceRegistry.getUrl());
        }

        fileLog.info("中转主机: {} ({})", relayHost.getName(),
                StrUtil.blankToDefault(relayHost.getDockerHost(), "本机"));
        fileLog.info("目标主机: {} ({})", targetHost.getName(),
                StrUtil.blankToDefault(targetHost.getDockerHost(), "本机"));

        File tar = null;
        try {
            tar = File.createTempFile("image-sync-", ".tar");

            // 1) 中转主机拉取并打包：镜像只从源仓库下载一次
            DockerClient relayClient = null;
            try {
                relayClient = dockerService.getClient(relayHost);

                // docker-java 不会拆分 tag，repo 与 tag 必须分开传，
                // 否则空 tag 会被 Docker 理解成「拉取该仓库的全部 tag」。
                fileLog.info("拉取镜像 {}:{}", repo, tag);
                PullImageCmd pullCmd = relayClient.pullImageCmd(repo).withTag(tag);
                if (auth != null) {
                    pullCmd.withAuthConfig(auth);
                }
                pullCmd.exec(new DefaultCallback<PullResponseItem>(logId)).awaitCompletion();

                fileLog.info("打包镜像到中转文件");
                try (InputStream in = relayClient.saveImageCmd(repo).withTag(tag).exec();
                     OutputStream out = new FileOutputStream(tar)) {
                    long size = IOUtils.copyLarge(in, out);
                    fileLog.info("打包完成，共 {} MB", size / 1024 / 1024);
                }
            } finally {
                IOUtils.closeQuietly(relayClient);
            }

            // 2) 导入到目标主机
            fileLog.info("-------- 导入到目标主机: {} ({}) --------", targetHost.getName(),
                    StrUtil.blankToDefault(targetHost.getDockerHost(), "本机"));
            DockerClient targetClient = null;
            try {
                targetClient = dockerService.getClient(targetHost);
                // load 是同步命令，返回即表示导入完成
                try (InputStream in = new FileInputStream(tar)) {
                    targetClient.loadImageCmd(in).exec();
                }
                fileLog.info("目标主机 {} 同步成功: {}:{}", targetHost.getName(), repo, tag);
            } catch (Exception e) {
                throw new IllegalStateException("目标主机 " + targetHost.getName() + " 导入失败: " + e.getMessage(), e);
            } finally {
                IOUtils.closeQuietly(targetClient);
            }
        } catch (IOException e) {
            throw new IllegalStateException("中转镜像失败: " + e.getMessage(), e);
        } finally {
            if (tar != null && !tar.delete()) {
                fileLog.warn("中转文件清理失败: {}", tar.getAbsolutePath());
            }
        }
    }

    /**
     * 源镜像的最后一段作为镜像名：docker.io/library/nginx -> nginx。
     */
    private static String lastSegment(String repo) {
        int index = repo.lastIndexOf('/');
        return index >= 0 ? repo.substring(index + 1) : repo;
    }

    /**
     * 注册中心认证信息，未配置用户名时返回 null（匿名推送）。
     */
    private static AuthConfig authOf(Registry registry) {
        if (registry == null || StrUtil.isBlank(registry.getUsername())) {
            return null;
        }
        // 必须显式设置 serveraddress：AuthConfig 默认值是 Docker Hub
        // （https://index.docker.io/v1/），若不覆盖，推送阿里云等仓库时会带着
        // 错配的服务地址做鉴权，报 push access denied / insufficient_scope。
        return new AuthConfig()
                .withUsername(registry.getUsername())
                .withPassword(registry.getPassword())
                .withRegistryAddress(registry.getUrl());
    }

}
