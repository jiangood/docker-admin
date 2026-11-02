package io.github.jiangood.docker.admin.service;

import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.util.HostRootPolicy;
import io.github.jiangood.openadmin.util.BusinessException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.util.List;

/**
 * 主机文件：只保留上传与下载，所有操作都通过辅助容器完成。
 * <p>
 * 辅助容器把配置的宿主机目录挂载到 {@code /host}，所以这里只做两件事：
 * 把界面上的相对路径转成 {@code /host/...}、复用 {@link ContainerService} 的容器文件能力。
 * <p>
 * 界面上看到的路径始终是相对于挂载目录的，不会出现宿主机绝对路径，
 * 因此也不存在从文件管理里跳到目录外的可能。
 */
@Slf4j
@Service
public class HostFileService {

    @Resource
    private HostAgentService hostAgentService;

    @Resource
    private ContainerService containerService;

    /**
     * 下载挂载目录本身或其中的子目录，空路径表示挂载目录。
     */
    public void downloadDirectory(Host host, String path, OutputStream out) {
        HostAgentService.AgentContext context = hostAgentService.requireRunning(host);
        String rel = HostRootPolicy.normalizeRelativePath(path);
        containerService.downloadDirectory(host, context.containerId(), HostRootPolicy.toAgentPath(rel), out);
    }

    /**
     * 覆盖写入文本文件；已存在的文件沿用原 mode/uid/gid，避免把宿主机文件改成 root 所有。
     */
    public void write(Host host, String path, byte[] content) {
        String rel = HostRootPolicy.normalizeRelativePath(path);
        if (rel.isEmpty()) {
            throw new BusinessException("文件名不能为空");
        }
        HostAgentService.AgentContext context = hostAgentService.requireRunning(host);
        String target = HostRootPolicy.toAgentPath(rel);
        byte[] bytes = content == null ? new byte[0] : content;

        // 新文件继承父目录的 uid/gid，避免在用户目录下创建出 root:root 的文件；
        // 权限固定 0644（继承目录的 0755 会让普通文本文件变成可执行）；
        // 已存在文件由 writeFile(..., true) 沿用原属性。
        ContainerService.CommandResult exists = containerService.exec(host, context.containerId(),
                testCmd("-e", target));
        if (exists.exitCode() != 0) {
            long[] owner = parentOwner(host, context.containerId(), target);
            if (owner != null) {
                containerService.writeFile(host, context.containerId(), target, bytes,
                        0644, owner[0], owner[1]);
                return;
            }
        }
        containerService.writeFile(host, context.containerId(), target, bytes, true);
    }

    /**
     * 读取父目录的 uid/gid；读取失败返回 null，由调用方回落到默认值。
     */
    private long[] parentOwner(Host host, String containerId, String target) {
        int idx = target.lastIndexOf('/');
        String parent = idx <= 0 ? "/" : target.substring(0, idx);
        ContainerService.CommandResult result = containerService.exec(host, containerId,
                statCmd("%u %g", parent));
        if (result.exitCode() != 0) {
            return null;
        }
        String[] parts = result.stdout().trim().split("\\s+");
        if (parts.length < 2) {
            return null;
        }
        try {
            return new long[]{Long.parseLong(parts[0]), Long.parseLong(parts[1])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 上传文件到挂载目录下的 dir，文件名只允许单层名称。
     */
    public void upload(Host host, String dir, String filename, byte[] content) {
        String safeName = HostRootPolicy.normalizeName(filename);
        String rel = HostRootPolicy.joinRelative(dir, safeName);
        write(host, rel, content);
    }

    /**
     * 用 sh 内置的 test，避免依赖镜像里是否存在 /usr/bin/test 这个 applet。
     * 路径作为 $1 传入，不拼进脚本，空格与特殊字符不会被解释。
     */
    private static List<String> testCmd(String flag, String path) {
        return List.of("sh", "-c", "test " + flag + " \"$1\"", "sh", path);
    }

    /**
     * 同上，用 sh 调 stat；不支持的 busybox 版本会返回非 0，调用方按默认值处理。
     */
    private static List<String> statCmd(String format, String path) {
        return List.of("sh", "-c", "stat -c '" + format + "' \"$1\"", "sh", path);
    }
}
