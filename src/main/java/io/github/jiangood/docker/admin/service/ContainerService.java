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
import org.apache.commons.io.IOUtils;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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

    public List<ContainerFileVo> listFiles(Host host, String containerId, String path) {
        String dir = StrUtil.blankToDefault(path, "/");
        DockerClient client = dockerClientManager.getClient(host);
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("ls");
            cmd.add("-la");
            cmd.add("--time-style=long-iso");
            cmd.add(dir);
            ExecResult result = execCapture(client, containerId, cmd);
            if (result.exitCode != 0 && StrUtil.isNotBlank(result.stderr)) {
                // 兼容 busybox 等不支持 --time-style 的环境，退回默认格式
                cmd = new ArrayList<>();
                cmd.add("ls");
                cmd.add("-la");
                cmd.add(dir);
                result = execCapture(client, containerId, cmd);
            }
            if (result.exitCode != 0) {
                throw new RuntimeException(StrUtil.blankToDefault(result.stderr, "无法列出目录: " + dir));
            }
            return parseLs(dir, result.stdout);
        } finally {
            IOUtils.closeQuietly(client);
        }
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

    /** 文本预览：截断读取，避免大文件打爆内存。 */
    public String previewFile(Host host, String containerId, String path) {
        DockerClient client = dockerClientManager.getClient(host);
        try {
            ExecResult result = execCapture(client, containerId,
                    List.of("head", "-c", String.valueOf(PREVIEW_MAX_BYTES), path));
            if (result.exitCode != 0) {
                throw new RuntimeException(StrUtil.blankToDefault(result.stderr, "无法读取文件: " + path));
            }
            return result.stdout;
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    /** 下载文件：将 exec cat 的 stdout 直接流式写入响应。 */
    public void downloadFile(Host host, String containerId, String path, OutputStream out) {
        DockerClient client = dockerClientManager.getClient(host);
        try {
            ExecCreateCmdResponse exec = client.execCreateCmd(containerId)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .withCmd("cat", path)
                    .exec();
            client.execStartCmd(exec.getId())
                    .withDetach(false)
                    .exec(new ExecStartResultCallback(out, new ByteArrayOutputStream()))
                    .awaitCompletion();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("下载被中断", e);
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
