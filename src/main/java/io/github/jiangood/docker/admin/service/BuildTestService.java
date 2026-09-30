package io.github.jiangood.docker.admin.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.BuildImageCmd;
import com.github.dockerjava.api.model.BuildResponseItem;
import io.github.jiangood.docker.admin.dto.BuildTestRequest;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.entity.Registry;
import io.github.jiangood.docker.admin.websocket.TaskLogRegistry;
import io.github.jiangood.docker.base.tool.GitCredential;
import io.github.jiangood.docker.base.tool.GitTool;
import io.github.jiangood.docker.sdk.engine.DefaultCallback;
import io.github.jiangood.docker.sdk.engine.DockerClientManager;
import io.github.jiangood.openadmin.util.BusinessException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.io.File;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

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

    /**
     * 当前运行的构建测试任务 logId。构建节点与默认镜像名都是共用的，同一时间只允许一个任务。
     */
    private final AtomicReference<String> runningLogId = new AtomicReference<>();

    /**
     * 已请求取消、尚未从任务线程里观察到的 logId。
     */
    private final Set<String> cancelRequested = ConcurrentHashMap.newKeySet();

    public boolean isRunning(String logId) {
        return taskLogRegistry.isRunning(logId);
    }

    /**
     * 当前运行中的构建测试任务，无任务时为 null。供页面刷新后恢复按钮状态（任务不落库）。
     */
    public String getRunningLogId() {
        return runningLogId.get();
    }

    /**
     * 触发构建测试：先同步占位（并发提交时直接报错），再转异步执行。
     */
    public void start(String logId, BuildTestRequest p) {
        if (!runningLogId.compareAndSet(null, logId)) {
            throw new BusinessException("已有构建测试任务正在运行，请先等待完成或取消");
        }
        // 通过代理调用 @Async 方法，避免自调用失效
        SpringUtil.getBean(BuildTestService.class).build(logId, p);
    }

    /**
     * 取消构建。
     * <p>
     * 已进入 docker 构建阶段时直接关闭响应流，构建立即中断；仍在克隆代码阶段时只能记录取消意图，
     * 由任务线程在克隆结束后自行跳过镜像构建。
     *
     * @return false 表示任务不存在或已结束
     */
    public boolean cancel(String logId) {
        if (StrUtil.isBlank(logId) || !logId.equals(runningLogId.get())) {
            return false;
        }
        cancelRequested.add(logId);
        taskLogRegistry.cancel(logId);
        return true;
    }

    /**
     * 读取 Git 仓库根目录下的 Dockerfile 内容，供构建测试页面回填编辑。
     */
    public String readDockerfile(String gitUrl) {
        String url = StrUtil.trim(gitUrl);
        Assert.hasText(url, "请先填写 Git 仓库地址");

        try {
            GitCredential credential = codeSourceService.credentialByGitUrl(url);
            String content = GitTool.readTextFile(url, credential, DOCKERFILE);
            if (content == null) {
                throw new BusinessException("仓库根目录未找到 " + DOCKERFILE + " 文件，请确认仓库地址或手动粘贴内容");
            }
            return content;
        } catch (GitAPIException e) {
            throw new BusinessException("读取仓库 Dockerfile 失败：" + e.getMessage(), e);
        }
    }

    /**
     * 将编辑后的 Dockerfile 写入 Git 仓库根目录，提交并推送。
     *
     * @param branch 目标分支，留空使用仓库默认分支
     * @return 新提交的短 id
     */
    public String writeDockerfile(String gitUrl, String dockerfileText, String commitMessage, String branch) {
        String url = StrUtil.trim(gitUrl);
        Assert.hasText(url, "请先填写 Git 仓库地址");
        Assert.hasText(dockerfileText, "请填写 Dockerfile 内容");

        try {
            GitCredential credential = codeSourceService.credentialByGitUrl(url);
            String commit = GitTool.writeTextFile(url, credential, DOCKERFILE, dockerfileText, commitMessage, branch);
            if (commit == null) {
                throw new BusinessException("Dockerfile 内容与仓库一致，无需写入");
            }
            log.info("Dockerfile 已写入仓库 {}，提交 {}", url, commit);
            return commit;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("写入仓库失败：" + e.getMessage(), e);
        }
    }

    @Async
    public void build(String logId, BuildTestRequest p) {
        taskLogRegistry.start(logId);
        MDC.put("logFileId", logId);
        DockerClient client = null;
        File workDir = null;
        try {
            if (cancelRequested.contains(logId)) {
                log.info("构建已取消");
                return;
            }

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

            // 凭据按地址主机自动匹配代码源，按其访问方式解析
            GitCredential credential = codeSourceService.credentialByGitUrl(gitUrl);

            GitTool.CloneResult cloneResult = GitTool.clone(gitUrl, credential, null);
            workDir = cloneResult.getDir();
            log.info("代码下载完毕 {}", workDir);
            log.info("代码提交信息: {}", cloneResult.getCodeMessage());

            // 克隆阶段无法中断，取消请求在此处生效
            if (cancelRequested.contains(logId)) {
                log.info("构建已取消：代码下载阶段无法中断，已跳过镜像构建");
                return;
            }

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

            // 登记到注册表，页面「取消构建」即关闭该响应流中断构建
            DefaultCallback<BuildResponseItem> buildCallback = new DefaultCallback<>(logId);
            taskLogRegistry.register(logId, buildCallback);

            buildImageCmd.exec(buildCallback).awaitCompletion();
            log.info("构建命令执行完毕");

            // 取消时响应流被外部关闭，awaitCompletion 会正常返回（也可能抛出连接被关闭的异常）
            if (cancelRequested.contains(logId)) {
                log.info("构建已取消");
                return;
            }

            log.info("构建测试成功: {}", targetImage);
        } catch (Exception e) {
            if (cancelRequested.contains(logId)) {
                log.info("构建已取消: {}", e.getMessage());
            } else {
                log.error("构建测试失败: {}", e.getMessage(), e);
            }
        } finally {
            IOUtils.closeQuietly(client);
            if (workDir != null) {
                // 构建测试不保留代码
                FileUtil.del(workDir);
            }
            MDC.remove("logFileId");
            cancelRequested.remove(logId);
            taskLogRegistry.finish(logId);
            runningLogId.compareAndSet(logId, null);
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
