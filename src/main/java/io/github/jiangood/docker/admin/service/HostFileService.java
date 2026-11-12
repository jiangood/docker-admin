package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dto.AgentFileVo;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.util.HostRootPolicy;
import io.github.jiangood.openadmin.util.BusinessException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
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
     * 下载前的解析结果：容器、容器内路径、相对挂载目录的路径，以及目标是不是目录。
     * <p>
     * 响应头要在写响应体之前设置，而「文件还是目录」必须问过辅助容器才知道，
     * 所以把这一步单独拆出来，由控制器先 setHeader 再调 {@link #download}。
     */
    public record DownloadPlan(String containerId, String containerPath, String relativePath, boolean directory) {
    }

    /**
     * 解析下载目标：空路径表示挂载目录本身；符号链接先解析到真实路径，
     * 指向挂载目录之外的链接直接拒绝。
     */
    public DownloadPlan planDownload(Host host, String path) {
        HostAgentService.AgentContext context = hostAgentService.requireRunning(host);
        String rel = HostRootPolicy.normalizeRelativePath(path);
        String target = HostRootPolicy.toAgentPath(rel);
        String resolved = resolveLink(host, context.containerId(), target);
        if (rel.isEmpty()) {
            return new DownloadPlan(context.containerId(), resolved, rel, true);
        }
        String kind = containerService.exec(host, context.containerId(),
                List.of("sh", "-c", kindCmd(), "sh", resolved)).stdout().trim();
        if (!"dir".equals(kind) && !"file".equals(kind)) {
            throw new BusinessException("路径不存在：" + rel);
        }
        return new DownloadPlan(context.containerId(), resolved, rel, "dir".equals(kind));
    }

    /** 一次 exec 判断目标类型：目录 / 其他已存在项 / 不存在，避免多次往返。 */
    private static String kindCmd() {
        return "if [ -d \"$1\" ]; then echo dir; elif [ -e \"$1\" ]; then echo file; else echo null; fi";
    }

    /** 按解析结果下载：目录打包成 tar 流，文件直接透传。 */
    public void download(Host host, DownloadPlan plan, OutputStream out) {
        if (plan.directory()) {
            containerService.downloadDirectory(host, plan.containerId(), plan.containerPath(), out);
        } else {
            containerService.downloadFile(host, plan.containerId(), plan.containerPath(), out);
        }
    }

    /**
     * 符号链接解析：Docker archive 接口只会给出链接本身（内容为空），
     * 所以先用 {@code readlink -f} 找到真实文件；解析结果必须仍在挂载目录内。
     */
    private String resolveLink(Host host, String containerId, String target) {
        if (containerService.exec(host, containerId, testCmd("-L", target)).exitCode() != 0) {
            return target;
        }
        ContainerService.CommandResult result = containerService.exec(host, containerId,
                List.of("sh", "-c", "readlink -f \"$1\"", "sh", target));
        String real = result.exitCode() == 0 ? result.stdout().trim() : "";
        if (real.isEmpty()) {
            throw new BusinessException("无法解析符号链接的目标，请通过终端下载");
        }
        if (!real.equals(HostRootPolicy.AGENT_MOUNT) && !real.startsWith(HostRootPolicy.AGENT_MOUNT + "/")) {
            throw new BusinessException("符号链接指向挂载目录之外（" + real + "），不允许下载");
        }
        return real;
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
     * 列出挂载目录（或其子目录）下的条目，供 Agent 面板「上传文件」弹窗逐级选择目标路径。
     * <p>
     * {@code dir} 相对挂载目录，空串表示挂载目录本身；经辅助容器执行 ls 读取，
     * {@code -p} 给目录条目带末尾斜杠用于区分类型，{@code -N} 保证名字不被转义改写。
     */
    public List<AgentFileVo> list(Host host, String dir) {
        HostAgentService.AgentContext context = hostAgentService.requireRunning(host);
        String rel = HostRootPolicy.normalizeRelativePath(dir);
        String target = HostRootPolicy.toAgentPath(rel);
        ContainerService.CommandResult result = containerService.exec(host, context.containerId(),
                List.of("sh", "-c", "ls -1A -p -N \"$1\"", "sh", target));
        if (result.exitCode() != 0) {
            String stderr = StrUtil.trimToEmpty(result.stderr());
            throw new BusinessException(stderr.isBlank()
                    ? (rel.isEmpty() ? "无法读取挂载目录" : "目录不存在或不可读：" + rel)
                    : stderr);
        }
        List<AgentFileVo> list = new ArrayList<>();
        for (String line : result.stdout().split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            boolean isDir = line.endsWith("/");
            AgentFileVo vo = new AgentFileVo();
            vo.setName(isDir ? line.substring(0, line.length() - 1) : line);
            vo.setType(isDir ? "dir" : "file");
            list.add(vo);
        }
        list.sort(Comparator
                .comparing((AgentFileVo f) -> !"dir".equals(f.getType()))
                .thenComparing(AgentFileVo::getName, Comparator.nullsLast(String::compareToIgnoreCase)));
        return list;
    }

    /**
     * 上传文件到挂载目录下的 dir，文件名只允许单层名称。
     * <p>
     * 目标位置已有同名文件 / 目录时拒绝，不允许覆盖：宿主机上的现有文件可能正被服务使用。
     */
    public void upload(Host host, String dir, String filename, byte[] content) {
        String safeName = HostRootPolicy.normalizeName(filename);
        String rel = HostRootPolicy.joinRelative(dir, safeName);
        HostAgentService.AgentContext context = hostAgentService.requireRunning(host);
        String target = HostRootPolicy.toAgentPath(rel);
        if (containerService.exec(host, context.containerId(), testCmd("-e", target)).exitCode() == 0) {
            throw new BusinessException("挂载目录下已存在 " + rel + "，不允许覆盖，请先删除或改名后再上传");
        }
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
