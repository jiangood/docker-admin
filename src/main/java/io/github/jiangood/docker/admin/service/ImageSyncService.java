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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 镜像同步：借助一台网络通畅的主机拉取公共镜像，重打标签后推送到平台注册中心。
 * <p>
 * 不落库；同步过程写入 {@code /data/logs/{logId}.log}，前端通过 WebSocket 实时查看。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageSyncService {

    /**
     * 正在执行的同步任务 logId，供 WebSocket 判断任务是否结束。
     */
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    @Resource
    HostService hostService;

    @Resource
    RegistryService registryService;

    @Resource
    DockerClientManager dockerService;

    public boolean isRunning(String logId) {
        return logId != null && running.contains(logId);
    }

    @Async
    public void sync(String logId, ImageSyncRequest p) {
        running.add(logId);
        MDC.put("logFileId", logId);
        DockerClient client = null;
        try {
            String source = StrUtil.trim(p.getSourceImage());
            Assert.hasText(source, "请填写源镜像");
            Assert.hasText(p.getHostId(), "请选择同步主机");

            Registry registry = registryService.getEffective();
            Assert.notNull(registry, "未配置镜像注册中心，请先在【设置-镜像注册中心】中配置");

            Host host = hostService.findById(p.getHostId()).orElse(null);
            Assert.notNull(host, "同步主机不存在，请选择一台网络通畅的主机");

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
            String targetImage = targetRepo + ":" + tag;
            Assert.state(!StrUtil.containsBlank(targetRepo), "镜像路径不能包含空格");

            log.info("注册中心: {}", registryBase);
            log.info("目标镜像: {}", targetImage);

            client = dockerService.getClient(host, registry);

            log.info("拉取源镜像 {}", source);
            PullImageCmd pullCmd = client.pullImageCmd(source);
            if (StrUtil.isNotBlank(p.getPlatform())) {
                log.info("指定平台 {}", p.getPlatform());
                pullCmd.withPlatform(StrUtil.trim(p.getPlatform()));
            }
            pullCmd.exec(new DefaultCallback<PullResponseItem>(logId)).awaitCompletion();
            log.info("拉取完成 {}", source);

            log.info("重新打标签为目标镜像 {}", targetImage);
            client.tagImageCmd(source, targetRepo, tag).exec();

            log.info("推送到注册中心 {}", targetImage);
            PushImageCmd pushCmd = client.pushImageCmd(targetImage);
            AuthConfig auth = authOf(registry);
            if (auth != null) {
                pushCmd.withAuthConfig(auth);
            }
            pushCmd.exec(new DefaultCallback<PushResponseItem>(logId)).awaitCompletion();

            log.info("镜像同步成功: {}", targetImage);
        } catch (Exception e) {
            log.error("镜像同步失败: {}", e.getMessage(), e);
        } finally {
            IOUtils.closeQuietly(client);
            MDC.remove("logFileId");
            running.remove(logId);
        }
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
        return new AuthConfig().withUsername(registry.getUsername()).withPassword(registry.getPassword());
    }

}
