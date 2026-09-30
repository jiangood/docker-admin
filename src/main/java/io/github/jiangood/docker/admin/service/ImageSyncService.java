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
import io.github.jiangood.docker.admin.entity.Registry;
import io.github.jiangood.docker.admin.websocket.TaskLogRegistry;
import io.github.jiangood.docker.sdk.engine.DefaultCallback;
import io.github.jiangood.docker.sdk.engine.DockerClientManager;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 镜像同步：借助一台网络通畅的主机拉取公共镜像，推送到平台注册中心，
 * 再让各目标主机从注册中心拉取、重新打标签。
 * <p>
 * 目标主机不走 save/load 直传：push/pull 是 daemon 原生协议、带进度回调，
 * 也不要求同步主机与目标主机之间有大带宽直连。
 * <p>
 * 不落库；同步过程写入 {@code /data/logs/{logId}.log}，前端通过 WebSocket 实时查看。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageSyncService {

    @Resource
    TaskLogRegistry taskLogRegistry;

    @Resource
    HostService hostService;

    @Resource
    RegistryService registryService;

    @Resource
    DockerClientManager dockerService;

    public boolean isRunning(String logId) {
        return taskLogRegistry.isRunning(logId);
    }

    /**
     * 同步前的参数校验。同步执行，便于把错误直接返回给前端
     * （{@link #sync} 是异步方法，其中的异常只会写进同步日志）。
     */
    public void validate(ImageSyncRequest p) {
        String hostId = StrUtil.trim(p.getHostId());
        if (StrUtil.isNotBlank(hostId) && normalizeIds(p.getTargetHostIds()).contains(hostId)) {
            throw new IllegalArgumentException("同步主机不能同时作为目标主机");
        }
    }

    @Async
    public void sync(String logId, ImageSyncRequest p) {
        taskLogRegistry.start(logId);
        MDC.put("logFileId", logId);
        DockerClient client = null;
        try {
            String source = StrUtil.trim(p.getSourceImage());
            Assert.hasText(source, "请填写源镜像");
            Assert.hasText(p.getHostId(), "请选择同步主机");

            List<String> targetHostIds = normalizeIds(p.getTargetHostIds());
            validate(p);

            Host host = hostService.findById(p.getHostId()).orElse(null);
            Assert.notNull(host, "同步主机不存在，请选择一台网络通畅的主机");

            // 镜像一律先推到注册中心，目标主机再从注册中心拉取，因此注册中心是硬依赖
            Registry registry = registryService.getEffective();
            Assert.notNull(registry, "未配置镜像注册中心，请先在【设置-镜像注册中心】中配置");

            log.info("开始同步镜像");
            log.info("源镜像: {}", source);
            log.info("同步主机: {} ({})", host.getName(), StrUtil.blankToDefault(host.getDockerHost(), "本机"));

            // 拆分源镜像的 repo 与 tag（默认 latest），处理 registry 带端口的情况
            String repo = source;
            String tag = "latest";
            int lastSlash = source.lastIndexOf('/');
            int lastColon = source.lastIndexOf(':');
            if (lastColon > lastSlash) {
                tag = source.substring(lastColon + 1);
                repo = source.substring(0, lastColon);
            }

            String targetName = StrUtil.isNotBlank(p.getTargetName()) ? StrUtil.trim(p.getTargetName()) : lastSegment(repo);

            String registryBase = StrUtil.removeSuffix(StrUtil.nullToEmpty(registry.getFullUrl()), "/");
            Assert.hasText(registryBase, "注册中心地址未配置，请先在【设置-镜像注册中心】中配置地址与命名空间");
            String targetRepo = registryBase + "/" + targetName;
            Assert.state(!StrUtil.containsBlank(targetRepo), "镜像路径不能包含空格");

            // 注意：客户端不能携带注册中心凭据。docker-java 会把客户端级凭据
            // （registry.url/username/password）用于所有 pull，拉取 Docker Hub 等公共镜像时
            // 也会带上注册中心的账号密码，导致 401 incorrect username or password。
            // 推送到注册中心时再通过 PushImageCmd.withAuthConfig 单独传凭据。
            client = dockerService.getClient(host);

            // 注意：docker-java 的 pullImageCmd 不会拆分 tag——传入 "nginx" 时，
            // 请求为 fromImage=nginx&tag=（空），Docker 侧会把「空 tag」理解为
            // 「拉取该仓库的全部 tag」，于是逐个拉取 nginx:1、nginx:1-alpine……
            // 这里显式传入已解析出的 repo 与 tag，只拉取指定版本。
            String sourceRef = repo + ":" + tag;
            log.info("拉取源镜像 {}", sourceRef);
            PullImageCmd pullCmd = client.pullImageCmd(repo).withTag(tag);
            pullCmd.exec(new DefaultCallback<PullResponseItem>(logId)).awaitCompletion();
            log.info("拉取完成 {}", sourceRef);

            RegistryImage registryImage = new RegistryImage(targetRepo + ":" + tag, targetRepo, targetName, tag, registry, logId);

            // 推送目的地由镜像名决定：本地必须先打上带注册中心地址的完整 tag，
            // 否则 docker 推送时会报 "tag does not exist"。
            log.info("注册中心: {}", registryBase);
            log.info("推送目标镜像 {}", registryImage.image());
            client.tagImageCmd(sourceRef, targetRepo, tag).exec();

            log.info("推送到注册中心 {}", registryImage.image());
            AuthConfig auth = authOf(registry);
            PushImageCmd pushCmd = client.pushImageCmd(registryImage.image());
            if (auth != null) {
                pushCmd.withAuthConfig(auth);
            }
            pushCmd.exec(new DefaultCallback<PushResponseItem>(logId)).awaitCompletion();
            log.info("注册中心同步成功: {}", registryImage.image());

            if (!targetHostIds.isEmpty()) {
                distribute(registryImage, targetHostIds);
            }

            log.info("镜像同步成功");
        } catch (Exception e) {
            log.error("镜像同步失败: {}", e.getMessage(), e);
        } finally {
            IOUtils.closeQuietly(client);
            MDC.remove("logFileId");
            taskLogRegistry.finish(logId);
        }
    }

    /**
     * 一次同步里与注册中心相关的引用信息。
     *
     * @param image 带注册中心前缀的完整引用，如 registry.cn-hangzhou.aliyuncs.com/ztcn/nginx:1.25
     * @param repo  带注册中心前缀的仓库名，不含 tag
     * @param name  目标主机上要用的镜像名，不含注册中心前缀
     * @param tag   镜像 tag
     */
    private record RegistryImage(String image, String repo, String name, String tag, Registry registry, String logId) {
    }

    /**
     * 让各目标主机从注册中心拉取镜像，重新打成不带注册中心前缀的本地镜像名。
     * <p>
     * 单台失败不影响其他主机，全部结束后汇总结果。
     */
    private void distribute(RegistryImage reg, List<String> targetHostIds) {
        log.info("开始分发镜像到 {} 台目标主机", targetHostIds.size());
        String localImage = reg.name() + ":" + reg.tag();
        Assert.state(!StrUtil.containsBlank(localImage), "镜像路径不能包含空格");

        AuthConfig auth = authOf(reg.registry());
        List<String> succeeded = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (String targetHostId : targetHostIds) {
            Host targetHost = hostService.findById(targetHostId).orElse(null);
            if (targetHost == null) {
                log.warn("目标主机不存在，跳过：{}", targetHostId);
                failed.add(targetHostId);
                continue;
            }

            log.info("-------- 分发到目标主机: {} ({}) --------", targetHost.getName(),
                    StrUtil.blankToDefault(targetHost.getDockerHost(), "本机"));
            DockerClient targetClient = null;
            try {
                targetClient = dockerService.getClient(targetHost);

                // 从注册中心拉取。docker-java 不会拆分 tag，repo 与 tag 必须分开传，
                // 否则空 tag 会被 Docker 理解成「拉取该仓库的全部 tag」。
                log.info("从注册中心拉取 {}", reg.image());
                PullImageCmd pullCmd = targetClient.pullImageCmd(reg.repo()).withTag(reg.tag());
                if (auth != null) {
                    pullCmd.withAuthConfig(auth);
                }
                pullCmd.exec(new DefaultCallback<PullResponseItem>(reg.logId())).awaitCompletion();

                // 拉下来的是带注册中心前缀的名字，重新打成目标主机上的本地镜像名
                log.info("重新打标签为目标镜像 {}", localImage);
                targetClient.tagImageCmd(reg.image(), reg.name(), reg.tag()).exec();

                // 清理目标主机上临时打的注册中心前缀 tag（镜像本体保留在 localImage 上）
                log.info("清理临时标签 {}", reg.image());
                targetClient.removeImageCmd(reg.image()).exec();

                log.info("目标主机 {} 同步成功: {}", targetHost.getName(), localImage);
                succeeded.add(targetHost.getName());
            } catch (Exception e) {
                log.error("目标主机 {} 同步失败: {}", targetHost.getName(), e.getMessage(), e);
                failed.add(targetHost.getName());
            } finally {
                IOUtils.closeQuietly(targetClient);
            }
        }

        log.info("目标主机同步完成：成功 {} 台，失败 {} 台", succeeded.size(), failed.size());
        if (!failed.isEmpty()) {
            throw new IllegalStateException("以下目标主机同步失败：" + String.join("、", failed));
        }
    }

    /**
     * 去重并去除空白的目标主机 id。
     */
    private static List<String> normalizeIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Set<String> set = new LinkedHashSet<>();
        for (String id : ids) {
            if (StrUtil.isNotBlank(id)) {
                set.add(StrUtil.trim(id));
            }
        }
        return new ArrayList<>(set);
    }

    /**
     * 源镜像的最后一段作为默认镜像名：docker.io/library/nginx -> nginx。
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
