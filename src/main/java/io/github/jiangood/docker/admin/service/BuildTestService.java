package io.github.jiangood.docker.admin.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.BuildImageCmd;
import com.github.dockerjava.api.model.BuildResponseItem;
import io.github.jiangood.docker.admin.dto.BuildTestRequest;
import io.github.jiangood.docker.admin.entity.CodeSource;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.entity.Registry;
import io.github.jiangood.docker.admin.websocket.TaskLogRegistry;
import io.github.jiangood.docker.base.tool.GitTool;
import io.github.jiangood.docker.sdk.engine.DefaultCallback;
import io.github.jiangood.docker.sdk.engine.DockerClientManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.io.File;
import java.util.Collections;

/**
 * 构建测试：以 Git 仓库为构建上下文，用粘贴的 Dockerfile 覆盖后，在默认构建节点上本地构建镜像。
 * <p>
 * 不落库、不推送；构建过程写入 {@code /data/logs/{logId}.log}，前端通过 WebSocket 实时查看。
 */
@Slf4j
@Service
public class BuildTestService {

    /**
     * 覆盖仓库中的 Dockerfile 相对路径。
     */
    private static final String DOCKERFILE = "Dockerfile";

    private static final String DEFAULT_TARGET_IMAGE = "temp:latest";

    @Resource
    HostService hostService;

    @Resource
    CodeSourceService codeSourceService;

    @Resource
    RegistryService registryService;

    @Resource
    DockerClientManager dockerService;

    @Resource
    TaskLogRegistry taskLogRegistry;

    public boolean isRunning(String logId) {
        return taskLogRegistry.isRunning(logId);
    }

    @Async
    public void build(String logId, BuildTestRequest p) {
        taskLogRegistry.start(logId);
        MDC.put("logFileId", logId);
        DockerClient client = null;
        File workDir = null;
        try {
            String gitUrl = StrUtil.trim(p.getGitUrl());
            Assert.hasText(gitUrl, "请填写 Git 仓库地址");
            Assert.hasText(p.getDockerfileText(), "请粘贴 Dockerfile 内容");

            Host host = hostService.getDefaultDockerRunner();
            Assert.notNull(host, "未配置构建节点（runner），请先在【设置-主机管理】中将某台主机设为构建节点");

            String[] target = splitTarget(p.getTargetImage());
            String targetName = target[0];
            String targetTag = target[1];

            log.info("开始构建测试");
            log.info("代码仓库: {}", gitUrl);
            log.info("构建节点: {} ({})", host.getName(), StrUtil.blankToDefault(host.getDockerHost(), "本机"));
            log.info("目标镜像: {}:{}", targetName, targetTag);

            // 凭据按地址主机自动匹配代码源
            CodeSource source = codeSourceService.findByGitUrl(gitUrl);
            String username = source == null ? null : source.getUsername();
            String password = source == null ? null : source.getPassword();

            GitTool.CloneResult cloneResult = GitTool.clone(gitUrl, username, password, null);
            workDir = cloneResult.getDir();
            log.info("代码下载完毕 {}", workDir);
            log.info("代码提交信息: {}", cloneResult.getCodeMessage());

            // 用粘贴内容覆盖仓库中的 Dockerfile
            File dockerfileFile = new File(workDir, DOCKERFILE);
            FileUtil.writeUtf8String(p.getDockerfileText(), dockerfileFile);
            log.info("已写入 Dockerfile: {}", dockerfileFile.getAbsolutePath());

            Registry registry = registryService.getEffective();
            client = dockerService.getClient(host, registry);

            String targetImage = targetName + ":" + targetTag;
            log.info("构建命令执行中...");
            BuildImageCmd buildImageCmd = client.buildImageCmd(workDir)
                    // 删除构建产生的临时容器
                    .withForcerm(true)
                    .withPull(false)
                    .withNoCache(false)
                    .withNetworkMode("host")
                    .withTags(Collections.singleton(targetImage))
                    .withDockerfile(dockerfileFile);

            buildImageCmd.exec(new DefaultCallback<BuildResponseItem>(logId)).awaitCompletion();
            log.info("构建命令执行完毕");

            log.info("构建测试成功: {}", targetImage);
        } catch (Exception e) {
            log.error("构建测试失败: {}", e.getMessage(), e);
        } finally {
            IOUtils.closeQuietly(client);
            if (workDir != null) {
                // 构建测试不保留代码
                FileUtil.del(workDir);
            }
            MDC.remove("logFileId");
            taskLogRegistry.finish(logId);
        }
    }

    /**
     * 解析镜像名:标签，未写标签时默认 latest。
     */
    private static String[] splitTarget(String value) {
        String target = StrUtil.isBlank(value) ? DEFAULT_TARGET_IMAGE : StrUtil.trim(value);
        int lastColon = target.lastIndexOf(':');
        int lastSlash = target.lastIndexOf('/');
        if (lastColon > lastSlash) {
            return new String[]{target.substring(0, lastColon), target.substring(lastColon + 1)};
        }
        return new String[]{target, "latest"};
    }

}
