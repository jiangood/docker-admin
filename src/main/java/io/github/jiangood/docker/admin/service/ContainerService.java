package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.command.InspectContainerResponse.ContainerState;
import com.github.dockerjava.api.command.InspectContainerResponse.Mount;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import io.github.jiangood.docker.admin.dto.ContainerDetailVo;
import io.github.jiangood.docker.admin.dto.ContainerFileVo;
import io.github.jiangood.docker.admin.dto.ContainerSummaryVo;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.sdk.engine.DockerClientManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.io.IOUtils;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 通用容器操作：列表、inspect、文件、操作。
 * <p>
 * 与 {@link AppService} 不同，这里不依赖应用标签，直接以 hostId + containerId 定位容器，
 * 供「主机详情」与「应用详情」的通用容器组件使用。
 */
@Slf4j
@Service
public class ContainerService {

    /** 文本预览最大字节数。 */
    private static final int PREVIEW_MAX_BYTES = 262144;

    /** 无 ls 时从 archive 接口推断出的修改时间格式，与 {@code ls --time-style=long-iso} 对齐。 */
    private static final DateTimeFormatter MTIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final Pattern MODE_PATTERN = Pattern.compile("^[bcdlps-][rwxSsTt-]{9}[.+]?$");
    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    @Resource
    private DockerClientManager dockerClientManager;

    // ------------------------------------------------------------------ 列表

    public List<ContainerSummaryVo> list(Host host, boolean showAll) {
        DockerClient client = dockerClientManager.getClient(host);
        try {
            List<Container> list = client.listContainersCmd().withShowAll(showAll).exec();
            List<ContainerSummaryVo> result = new ArrayList<>(list.size());
            for (Container c : list) {
                result.add(toSummary(c));
            }
            return result;
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    private static ContainerSummaryVo toSummary(Container c) {
        ContainerSummaryVo vo = new ContainerSummaryVo();
        vo.setId(c.getId());
        vo.setIdShort(shortId(c.getId()));
        vo.setName(firstName(c.getNames()));
        vo.setImage(c.getImage());
        vo.setImageId(c.getImageId());
        vo.setState(c.getState());
        vo.setStatus(c.getStatus());
        vo.setCreated(c.getCreated());
        vo.setCommand(c.getCommand());
        if (c.getLabels() != null) {
            vo.setLabels(new LinkedHashMap<>(c.getLabels()));
        }
        if (c.getPorts() != null) {
            for (ContainerPort p : c.getPorts()) {
                ContainerDetailVo.PortVo pv = new ContainerDetailVo.PortVo();
                pv.setPrivatePort(p.getPrivatePort());
                pv.setPublicPort(p.getPublicPort());
                pv.setProtocol(p.getType());
                pv.setHostIp(p.getIp());
                vo.getPorts().add(pv);
            }
        }
        return vo;
    }

    // ------------------------------------------------------------------ inspect

    public ContainerDetailVo inspect(Host host, String containerId) {
        DockerClient client = dockerClientManager.getClient(host);
        try {
            InspectContainerResponse r = client.inspectContainerCmd(containerId).exec();
            return toDetail(r);
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    private static ContainerDetailVo toDetail(InspectContainerResponse r) {
        ContainerDetailVo vo = new ContainerDetailVo();
        vo.setId(r.getId());
        vo.setIdShort(shortId(r.getId()));
        vo.setName(r.getName() != null && r.getName().startsWith("/") ? r.getName().substring(1) : r.getName());
        vo.setImageId(r.getImageId());
        vo.setCreated(r.getCreated());
        vo.setRestartCount(r.getRestartCount());

        ContainerConfig cfg = r.getConfig();
        if (cfg != null) {
            vo.setImage(cfg.getImage());
            vo.setCmd(cfg.getCmd());
            vo.setEntrypoint(cfg.getEntrypoint());
            vo.setWorkingDir(cfg.getWorkingDir());
            vo.setUser(cfg.getUser());
            vo.setHostname(cfg.getHostName());
            if (cfg.getEnv() != null) {
                vo.setEnv(new ArrayList<>(Arrays.asList(cfg.getEnv())));
            }
            if (cfg.getLabels() != null) {
                vo.setLabels(new LinkedHashMap<>(cfg.getLabels()));
            }
        }

        ContainerState st = r.getState();
        if (st != null) {
            vo.setState(st.getStatus());
            vo.setStatus(st.getStatus());
            vo.setRunning(st.getRunning());
            vo.setPaused(st.getPaused());
            vo.setRestarting(st.getRestarting());
            vo.setDead(st.getDead());
            vo.setExitCode(st.getExitCode());
            vo.setError(st.getError());
            vo.setStartedAt(st.getStartedAt());
            vo.setFinishedAt(st.getFinishedAt());
        }

        HostConfig hc = r.getHostConfig();
        if (hc != null) {
            vo.setPrivileged(hc.getPrivileged());
            vo.setNetworkMode(hc.getNetworkMode());
            RestartPolicy rp = hc.getRestartPolicy();
            if (rp != null) {
                String name = rp.getName();
                if (name != null && rp.getMaximumRetryCount() != null && rp.getMaximumRetryCount() > 0) {
                    name = name + ":" + rp.getMaximumRetryCount();
                }
                vo.setRestartPolicy(name);
            }
            LogConfig lc = hc.getLogConfig();
            if (lc != null && lc.getType() != null) {
                vo.setLogDriver(lc.getType().name().toLowerCase().replace('_', '-'));
            }
        }

        if (r.getMounts() != null) {
            for (Mount m : r.getMounts()) {
                ContainerDetailVo.MountVo mv = new ContainerDetailVo.MountVo();
                // 命名卷有 name，绑定挂载没有；用于区分 volume / bind
                mv.setType(StrUtil.isNotBlank(m.getName()) ? "volume" : "bind");
                mv.setName(m.getName());
                mv.setSource(m.getSource());
                mv.setDestination(m.getDestination() == null ? null : m.getDestination().getPath());
                mv.setMode(m.getMode());
                mv.setReadOnly(m.getRW() == null ? null : !m.getRW());
                vo.getMounts().add(mv);
            }
        }

        NetworkSettings ns = r.getNetworkSettings();
        if (ns != null) {
            Ports ports = ns.getPorts();
            if (ports != null && ports.getBindings() != null) {
                for (Map.Entry<ExposedPort, Ports.Binding[]> e : ports.getBindings().entrySet()) {
                    ExposedPort ep = e.getKey();
                    Ports.Binding[] bindings = e.getValue();
                    if (bindings == null || bindings.length == 0) {
                        ContainerDetailVo.PortVo pv = new ContainerDetailVo.PortVo();
                        pv.setPrivatePort(ep.getPort());
                        pv.setProtocol(ep.getProtocol().toString());
                        vo.getPorts().add(pv);
                        continue;
                    }
                    for (Ports.Binding b : bindings) {
                        ContainerDetailVo.PortVo pv = new ContainerDetailVo.PortVo();
                        pv.setPrivatePort(ep.getPort());
                        pv.setProtocol(ep.getProtocol().toString());
                        pv.setHostIp(b.getHostIp());
                        pv.setPublicPort(parsePort(b.getHostPortSpec()));
                        vo.getPorts().add(pv);
                    }
                }
            }
            if (ns.getNetworks() != null) {
                for (Map.Entry<String, ContainerNetwork> e : ns.getNetworks().entrySet()) {
                    ContainerNetwork n = e.getValue();
                    ContainerDetailVo.NetworkVo nv = new ContainerDetailVo.NetworkVo();
                    nv.setName(e.getKey());
                    nv.setIpAddress(n.getIpAddress());
                    nv.setGateway(n.getGateway());
                    nv.setMacAddress(n.getMacAddress());
                    vo.getNetworks().add(nv);
                }
            }
        }
        return vo;
    }

    private static Integer parsePort(String spec) {
        if (StrUtil.isBlank(spec)) {
            return null;
        }
        try {
            return Integer.parseInt(spec.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 操作

    public void start(Host host, String containerId) {
        withClient(host, client -> client.startContainerCmd(containerId).exec());
    }

    public void stop(Host host, String containerId) {
        withClient(host, client -> client.stopContainerCmd(containerId).exec());
    }

    public void restart(Host host, String containerId) {
        withClient(host, client -> client.restartContainerCmd(containerId).exec());
    }

    public void remove(Host host, String containerId, boolean force) {
        withClient(host, client -> client.removeContainerCmd(containerId)
                .withForce(force)
                .withRemoveVolumes(false)
                .exec());
    }

    private interface ClientAction {
        void run(DockerClient client);
    }

    private void withClient(Host host, ClientAction action) {
        DockerClient client = dockerClientManager.getClient(host);
        try {
            action.run(client);
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    // ------------------------------------------------------------------ 文件

    /**
     * 列出容器内目录。
     * <p>
     * 优先用容器内的 {@code ls}（快，且不需要拉取整个目录树）；镜像里没有 {@code ls} 时
     * （distroless / scratch 等）回退到 Docker archive 接口，由 dockerd 直接读取文件系统。
     */
    public List<ContainerFileVo> listFiles(Host host, String containerId, String path) {
        String dir = StrUtil.blankToDefault(path, "/");
        DockerClient client = dockerClientManager.getClient(host);
        try {
            List<ContainerFileVo> byLs = listFilesByLs(client, containerId, dir);
            if (byLs != null) {
                return byLs;
            }
            return listFilesByArchive(client, containerId, dir);
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    /** 用容器内的 ls 列目录；容器没有 ls 或执行失败时返回 null，由 archive 兜底。 */
    private List<ContainerFileVo> listFilesByLs(DockerClient client, String containerId, String dir) {
        try {
            ExecResult result = execCapture(client, containerId, List.of("ls", "-la", "--time-style=long-iso", dir));
            if (result.exitCode != 0) {
                // 兼容 busybox 等不支持 --time-style 的环境，退回默认格式
                result = execCapture(client, containerId, List.of("ls", "-la", dir));
            }
            if (result.exitCode == 0) {
                return parseLs(dir, result.stdout);
            }
        } catch (RuntimeException e) {
            log.debug("容器 {} 执行 ls 失败，改用 archive 接口: {}", containerId, e.getMessage());
        }
        return null;
    }

    /**
     * 用 Docker archive 接口列目录（不依赖容器内的任何二进制）。
     * <p>
     * archive 返回的是以「请求路径所在目录名」为前缀的 tar，根目录则为绝对路径；
     * 这里只取第一层条目，更深的层级直接跳过。
     */
    private List<ContainerFileVo> listFilesByArchive(DockerClient client, String containerId, String dir) {
        String base = dir.endsWith("/") ? dir : dir + "/";
        String prefix;
        if ("/".equals(base)) {
            prefix = "";
        } else {
            String d = base.substring(0, base.length() - 1);
            prefix = d.substring(d.lastIndexOf('/') + 1) + "/";
        }

        List<ContainerFileVo> list = new ArrayList<>();
        try (InputStream in = client.copyArchiveFromContainerCmd(containerId, dir).exec();
             TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String rest = stripArchivePrefix(prefix, entry.getName());
                if (rest == null || rest.isEmpty() || rest.indexOf('/') >= 0) {
                    continue;
                }
                ContainerFileVo vo = new ContainerFileVo();
                vo.setName(rest);
                vo.setPath(base + rest);
                vo.setMode(modeString(entry));
                vo.setMtime(formatArchiveMtime(entry));
                if (entry.isDirectory()) {
                    vo.setType("dir");
                } else if (entry.isSymbolicLink()) {
                    vo.setType("link");
                    String target = entry.getLinkName();
                    vo.setLinkTarget(target);
                    // 与 ls -l 保持一致：链接的大小显示为目标路径长度
                    vo.setSize(target == null ? null : (long) target.length());
                } else {
                    vo.setType("file");
                    vo.setSize(entry.getSize());
                }
                list.add(vo);
            }
        } catch (IOException e) {
            throw new RuntimeException("无法列出目录: " + dir, e);
        }

        list.sort(Comparator
                .comparing((ContainerFileVo f) -> !"dir".equals(f.getType()))
                .thenComparing(ContainerFileVo::getName, Comparator.nullsLast(String::compareToIgnoreCase)));
        return list;
    }

    /** 去掉 archive 条目名前缀，返回相对当前目录的路径；不属于该目录时返回 null。 */
    private static String stripArchivePrefix(String prefix, String entryName) {
        String rest;
        if (prefix.isEmpty()) {
            rest = entryName.startsWith("/") ? entryName.substring(1) : entryName;
        } else if (entryName.startsWith(prefix)) {
            rest = entryName.substring(prefix.length());
        } else {
            return null;
        }
        // 目录条目以 / 结尾，去掉后再判断深度
        return rest.endsWith("/") ? rest.substring(0, rest.length() - 1) : rest;
    }

    /** 由 tar 条目的权限位还原 ls 风格的模式串（如 {@code -rw-r--r--}）。 */
    private static String modeString(TarArchiveEntry entry) {
        int mode = entry.getMode();
        char type = entry.isDirectory() ? 'd' : entry.isSymbolicLink() ? 'l' : '-';
        int[] bits = {0400, 0200, 0100, 040, 020, 010, 04, 02, 01};
        char[] chars = {'r', 'w', 'x'};
        StringBuilder sb = new StringBuilder(10);
        sb.append(type);
        for (int i = 0; i < 9; i++) {
            sb.append((mode & bits[i]) != 0 ? chars[i % 3] : '-');
        }
        return sb.toString();
    }

    private static String formatArchiveMtime(TarArchiveEntry entry) {
        Date time = entry.getModTime();
        if (time == null) {
            return null;
        }
        return MTIME_FORMAT.format(LocalDateTime.ofInstant(time.toInstant(), ZoneId.systemDefault()));
    }

    private List<ContainerFileVo> parseLs(String dir, String output) {
        List<ContainerFileVo> list = new ArrayList<>();
        if (StrUtil.isBlank(output)) {
            return list;
        }
        String base = dir.endsWith("/") ? dir : dir + "/";
        for (String line : output.split("\\r?\\n")) {
            if (StrUtil.isBlank(line) || line.startsWith("total ")) {
                continue;
            }
            String[] tokens = line.trim().split("\\s+");
            if (tokens.length < 7 || !MODE_PATTERN.matcher(tokens[0]).matches()) {
                continue;
            }
            String mode = tokens[0];
            String size = tokens[4];
            int nameStart;
            String mtime;
            if (ISO_DATE_PATTERN.matcher(tokens[5]).matches()) {
                mtime = tokens[5] + " " + tokens[6];
                nameStart = 7;
            } else if (tokens.length >= 8) {
                mtime = tokens[5] + " " + tokens[6] + " " + tokens[7];
                nameStart = 8;
            } else {
                continue;
            }
            if (tokens.length <= nameStart) {
                continue;
            }
            String name = String.join(" ", Arrays.copyOfRange(tokens, nameStart, tokens.length));
            if (".".equals(name) || "..".equals(name)) {
                continue;
            }

            ContainerFileVo vo = new ContainerFileVo();
            vo.setMode(mode);
            vo.setMtime(mtime);
            char kind = mode.charAt(0);
            if (kind == 'd') {
                vo.setType("dir");
            } else if (kind == 'l') {
                vo.setType("link");
            } else if (kind == '-') {
                vo.setType("file");
            } else {
                vo.setType("other");
            }
            if (vo.getType().equals("link")) {
                int arrow = name.indexOf(" -> ");
                if (arrow >= 0) {
                    vo.setLinkTarget(name.substring(arrow + 4));
                    name = name.substring(0, arrow);
                }
            }
            vo.setName(name);
            vo.setPath(base + name);
            if (!"dir".equals(vo.getType())) {
                try {
                    vo.setSize(Long.parseLong(size));
                } catch (NumberFormatException ignored) {
                    // 保持 size 为空
                }
            }
            list.add(vo);
        }
        list.sort(Comparator
                .comparing((ContainerFileVo f) -> !"dir".equals(f.getType()))
                .thenComparing(ContainerFileVo::getName, Comparator.nullsLast(String::compareToIgnoreCase)));
        return list;
    }

    /**
     * 文本预览：截断读取，避免大文件打爆内存。
     * <p>
     * 优先用容器内的 {@code head}；镜像里没有 head 时（distroless / scratch 等）从 Docker archive
     * 接口读取该文件内容。
     */
    public String previewFile(Host host, String containerId, String path) {
        DockerClient client = dockerClientManager.getClient(host);
        try {
            String byHead = previewByHead(client, containerId, path);
            if (byHead != null) {
                return byHead;
            }
            return new String(readFileByArchive(client, containerId, path, PREVIEW_MAX_BYTES), StandardCharsets.UTF_8);
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    /** 用容器内的 head 截断读取；容器没有 head 或执行失败时返回 null，由 archive 兜底。 */
    private String previewByHead(DockerClient client, String containerId, String path) {
        try {
            ExecResult result = execCapture(client, containerId,
                    List.of("head", "-c", String.valueOf(PREVIEW_MAX_BYTES), path));
            if (result.exitCode == 0) {
                return result.stdout;
            }
        } catch (RuntimeException e) {
            log.debug("容器 {} 执行 head 失败，改用 archive 接口: {}", containerId, e.getMessage());
        }
        return null;
    }

    /** 从 Docker archive 接口读取单文件内容（不依赖容器内的 cat / head）。 */
    private byte[] readFileByArchive(DockerClient client, String containerId, String path, int maxBytes) {
        try (InputStream in = client.copyArchiveFromContainerCmd(containerId, path).exec();
             TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
            TarArchiveEntry entry = tar.getNextEntry();
            if (entry == null) {
                throw new RuntimeException("文件不存在: " + path);
            }
            if (entry.isDirectory()) {
                throw new RuntimeException("这是一个目录: " + path);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            while (out.size() < maxBytes) {
                len = tar.read(buf, 0, Math.min(buf.length, maxBytes - out.size()));
                if (len == -1) {
                    break;
                }
                out.write(buf, 0, len);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("无法读取文件: " + path, e);
        }
    }

    /**
     * 下载文件：从 Docker archive 接口取出该文件内容。
     * <p>
     * 不再依赖容器内的 {@code cat}，因此 distroless / scratch 等镜像同样可下载。
     */
    public void downloadFile(Host host, String containerId, String path, OutputStream out) {
        DockerClient client = dockerClientManager.getClient(host);
        try (InputStream in = client.copyArchiveFromContainerCmd(containerId, path).exec();
             TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
            TarArchiveEntry entry = tar.getNextEntry();
            if (entry == null) {
                throw new RuntimeException("文件不存在: " + path);
            }
            if (entry.isDirectory()) {
                throw new RuntimeException("这是一个目录: " + path);
            }
            tar.transferTo(out);
        } catch (IOException e) {
            throw new RuntimeException("下载失败: " + e.getMessage(), e);
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    /** 下载目录：直接透传 docker archive 接口返回的 tar 流。 */
    public void downloadDirectory(Host host, String containerId, String path, OutputStream out) {
        DockerClient client = dockerClientManager.getClient(host);
        try (InputStream in = client.copyArchiveFromContainerCmd(containerId, path).exec()) {
            in.transferTo(out);
        } catch (java.io.IOException e) {
            throw new RuntimeException("打包下载失败: " + e.getMessage(), e);
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    // ------------------------------------------------------------------ 写入 / 上传

    /**
     * 写入容器内文件（覆盖）或上传新文件：把内容打包成单文件 tar，经 Docker archive 接口复制进容器。
     * <p>
     * 不依赖容器内的 shell / cat，distroless / scratch 镜像同样可用；同名文件会被覆盖，已存在文件沿用原权限。
     *
     * @param path    容器内文件的绝对路径
     * @param content 文件字节内容
     */
    public void writeFile(Host host, String containerId, String path, byte[] content) {
        String file = normalizePath(path);
        int idx = file.lastIndexOf('/');
        String dir = idx <= 0 ? "/" : file.substring(0, idx);
        String name = file.substring(idx + 1);
        if (name.isEmpty()) {
            throw new RuntimeException("文件名不能为空");
        }

        DockerClient client = dockerClientManager.getClient(host);
        try {
            // 已存在的文件沿用原权限，避免把 0600 之类的文件覆盖成 0644
            int mode = resolveFileMode(client, containerId, file);
            byte[] tar = buildSingleFileTar(name, content, mode);
            client.copyArchiveToContainerCmd(containerId)
                    .withTarInputStream(new ByteArrayInputStream(tar))
                    .withRemotePath(dir)
                    .exec();
        } catch (IOException e) {
            throw new RuntimeException("写入文件失败: " + e.getMessage(), e);
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    /** 读取容器内已存在文件的权限位；文件不存在或读取失败时返回默认 0644。 */
    private int resolveFileMode(DockerClient client, String containerId, String path) {
        try (InputStream in = client.copyArchiveFromContainerCmd(containerId, path).exec();
             TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
            TarArchiveEntry entry = tar.getNextEntry();
            if (entry != null && !entry.isDirectory()) {
                int mode = entry.getMode() & 0777;
                if (mode > 0) {
                    return mode;
                }
            }
        } catch (Exception e) {
            log.debug("读取容器 {} 文件 {} 权限失败，使用默认权限: {}", containerId, path, e.getMessage());
        }
        return 0644;
    }

    /** 归一化容器内路径：统一分隔符、去掉重复斜杠与 . / .. 片段，并保证以 / 开头。 */
    private static String normalizePath(String path) {
        List<String> parts = new ArrayList<>();
        for (String seg : StrUtil.trimToEmpty(path).replace('\\', '/').trim().split("/")) {
            if (seg.isEmpty() || ".".equals(seg)) {
                continue;
            }
            if ("..".equals(seg)) {
                if (!parts.isEmpty()) {
                    parts.remove(parts.size() - 1);
                }
                continue;
            }
            parts.add(seg);
        }
        return "/" + String.join("/", parts);
    }

    /** 把单文件内容打包成 tar，供 archive 接口写入容器。 */
    private static byte[] buildSingleFileTar(String name, byte[] content, int mode) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(4096 + content.length);
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bos)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            TarArchiveEntry entry = new TarArchiveEntry(name);
            entry.setSize(content.length);
            entry.setMode(mode);
            tar.putArchiveEntry(entry);
            tar.write(content);
            tar.closeArchiveEntry();
        }
        return bos.toByteArray();
    }

    // ------------------------------------------------------------------ 工具

    private ExecResult execCapture(DockerClient client, String containerId, List<String> cmd) {
        ExecCreateCmdResponse exec = client.execCreateCmd(containerId)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withCmd(cmd.toArray(new String[0]))
                .exec();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try {
            client.execStartCmd(exec.getId())
                    .withDetach(false)
                    .exec(new ExecStartResultCallback(stdout, stderr))
                    .awaitCompletion();
            InspectExecResponse state = client.inspectExecCmd(exec.getId()).exec();
            int exitCode = state.getExitCodeLong() == null ? 0 : state.getExitCodeLong().intValue();
            return new ExecResult(exitCode, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("命令执行被中断", e);
        }
    }

    private static String shortId(String id) {
        return id != null && id.length() > 12 ? id.substring(0, 12) : id;
    }

    private static String firstName(String[] names) {
        if (names == null || names.length == 0) {
            return null;
        }
        String n = names[0];
        return n != null && n.startsWith("/") ? n.substring(1) : n;
    }

    private record ExecResult(int exitCode, String stdout, String stderr) {
    }

    /**
     * 交互式 exec 会话（供控制台 WebSocket 使用）。
     * 由创建者负责 {@link #close()}。
     */
    public static class ExecSession {
        private final DockerClient client;
        private final String execId;
        private final OutputStream stdin;
        private final ResultCallback.Adapter<Frame> callback;

        ExecSession(DockerClient client, String execId, OutputStream stdin, ResultCallback.Adapter<Frame> callback) {
            this.client = client;
            this.execId = execId;
            this.stdin = stdin;
            this.callback = callback;
        }

        public String getExecId() {
            return execId;
        }

        public void write(byte[] data) {
            try {
                synchronized (stdin) {
                    stdin.write(data);
                    stdin.flush();
                }
            } catch (Exception e) {
                throw new RuntimeException("写入控制台失败: " + e.getMessage(), e);
            }
        }

        public void resize(int cols, int rows) {
            try {
                client.resizeExecCmd(execId).withSize(rows, cols).exec();
            } catch (Exception e) {
                log.debug("调整控制台大小失败: {}", e.getMessage());
            }
        }

        public void close() {
            IOUtils.closeQuietly(stdin);
            try {
                callback.close();
            } catch (Exception ignored) {
                // ignore
            }
            IOUtils.closeQuietly(client);
        }
    }

    /**
     * 打开交互式 shell 会话。TTY 模式下 stdin/stdout 为原始字节流，直接对接前端 xterm。
     *
     * @param onOutput 收到容器输出时的回调（原始字节）
     */
    public ExecSession openExecSession(Host host, String containerId, String shell,
                                       java.util.function.Consumer<byte[]> onOutput, Runnable onComplete) {
        DockerClient client = dockerClientManager.getClient(host);
        String sh = StrUtil.blankToDefault(shell, "/bin/sh");
        try {
            ExecCreateCmdResponse exec = client.execCreateCmd(containerId)
                    .withCmd(sh)
                    .withAttachStdin(true)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .withTty(true)
                    .exec();

            java.io.PipedOutputStream stdinOut = new java.io.PipedOutputStream();
            java.io.PipedInputStream stdinIn = new java.io.PipedInputStream(stdinOut, 8192);

            ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
                @Override
                public void onNext(Frame frame) {
                    if (frame != null && frame.getPayload() != null) {
                        onOutput.accept(frame.getPayload());
                    }
                }

                @Override
                public void onComplete() {
                    try {
                        super.onComplete();
                    } finally {
                        if (onComplete != null) {
                            onComplete.run();
                        }
                    }
                }
            };

            client.execStartCmd(exec.getId())
                    .withTty(true)
                    .withStdIn(stdinIn)
                    .exec(callback);

            return new ExecSession(client, exec.getId(), stdinOut, callback);
        } catch (Exception e) {
            IOUtils.closeQuietly(client);
            throw e instanceof RuntimeException re ? re : new RuntimeException(e.getMessage(), e);
        }
    }
}
