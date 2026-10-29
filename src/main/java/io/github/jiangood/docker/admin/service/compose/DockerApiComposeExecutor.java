package io.github.jiangood.docker.admin.service.compose;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Info;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.api.model.Volume;
import io.github.jiangood.docker.admin.dto.ComposeCapabilityVo;
import io.github.jiangood.docker.admin.dto.ComposeFileContentVo;
import io.github.jiangood.docker.admin.dto.ComposeFileVo;
import io.github.jiangood.docker.admin.dto.ComposeProjectVo;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.base.FileLog;
import io.github.jiangood.docker.config.Config;
import io.github.jiangood.docker.sdk.engine.DefaultCallback;
import io.github.jiangood.docker.sdk.engine.DockerClientManager;
import io.github.jiangood.openadmin.util.BusinessException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于 Docker API 的 Compose 执行器。
 * <p>
 * 平台与主机之间只有 docker API（没有 shell），所以 compose 命令不能直接在主机上执行。
 * 这里的做法是：<b>用 docker API 在目标主机上起一个临时容器</b>（{@code docker:cli}，
 * 自带 docker CLI 与 compose 插件），把项目目录与 daemon 连接一起交给它，由它代跑
 * {@code docker compose ...}，输出通过容器日志流回传。
 * <p>
 * 文件读写也不用 shell：临时容器把项目目录 bind 挂到<b>同一路径</b>，再用 Docker 的
 * archive 接口（{@code docker cp} 的 API 形态）读写文件。同路径挂载很关键——
 * compose 里 {@code ./data:/data} 这类相对路径会按项目目录解析成宿主机路径，
 * 只有挂载点与宿主机路径一致，结果才与在主机上直接执行完全等价。
 * <p>
 * daemon 连接方式：
 * <ul>
 *   <li>本机 / unix socket：把 socket 挂进辅助容器；</li>
 *   <li>TCP 主机：辅助容器使用 host 网络，{@code DOCKER_HOST} 指向平台正在用的那个 TCP 地址
 *       （容器在 host 网络命名空间里访问宿主机自身地址）。</li>
 * </ul>
 */
@Slf4j
@Service
public class DockerApiComposeExecutor implements ComposeExecutor {

    private static final Logger fileLog = FileLog.logger();

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 辅助容器统一标签，便于识别与清理 */
    public static final String LABEL_HELPER = "io.jiangood.compose";

    /** 当前任务标识，取消时按这个标签找容器 */
    public static final String LABEL_LOG_ID = "io.jiangood.compose.logId";

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]");
    private static final Pattern STATUS_PART = Pattern.compile("(\\w+)\\((\\d+)\\)");

    /** 列目录时最多读取的 entry 数，避免超大目录把 tar 流拖死 */
    private static final int MAX_LIST_ENTRIES = 2000;

    /** 连接方式探测结果缓存时长 */
    private static final long CONN_TTL_MILLIS = TimeUnit.MINUTES.toMillis(10);

    private static final String DEFAULT_SOCKET = "/var/run/docker.sock";
    private static final String TMP_COMPOSE_FILE = "/compose.tmp.yml";

    /** docker:cli 是 scratch 镜像，没有可写目录，compose/buildx 需要 HOME 与 /tmp */
    private static final Map<String, String> TMP_FS = Map.of("/tmp", "rw");

    @Resource
    private DockerClientManager dockerClientManager;

    @Resource
    private Config config;

    /** hostId|dockerHost -> 探测到的可用连接方式，避免每次操作都试一遍 */
    private final Map<String, CachedConn> connCache = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ 能力探测

    @Override
    public ComposeCapabilityVo probe(Host host) {
        ComposeCapabilityVo vo = new ComposeCapabilityVo();
        String image = helperImage();
        vo.setHelperImage(image);
        vo.setSupported(false);

        DockerClient client = null;
        try {
            client = dockerClientManager.getClient(host);
            Info info = client.infoCmd().exec();
            vo.setOsType(info.getOsType());
            vo.setOs(info.getOperatingSystem());

            if ("windows".equalsIgnoreCase(info.getOsType())) {
                vo.setReason("该主机是 Windows 容器模式：没有可用的轻量 Linux 辅助容器，暂不支持 Compose 管理");
                return vo;
            }
            if (StrUtil.containsIgnoreCase(info.getOperatingSystem(), "Docker Desktop")) {
                vo.setWarning("该主机是 Docker Desktop：compose 文件里的相对路径（如 ./data:/data）可能不会映射到宿主机，建议改用命名卷或绝对路径");
            }

            try {
                var imageInfo = client.inspectImageCmd(image).exec();
                vo.setImageReady(true);
                vo.setImageSize(imageInfo.getSize());
            } catch (NotFoundException e) {
                vo.setCanPrepare(true);
                vo.setReason("主机上还没有辅助镜像 " + image + "，请先点「准备运行环境」拉取（约 30MB）");
                return vo;
            }

            HelperConn conn = resolveConn(client, host, true);
            vo.setDaemonMode(conn.mode);

            String version = composeVersion(client, conn);
            vo.setComposeVersion(version);
            if (StrUtil.isBlank(version)) {
                vo.setReason("辅助容器里无法执行 docker compose，请确认镜像 " + image + " 完整（需自带 compose 插件）");
                return vo;
            }
            vo.setSupported(true);
            return vo;
        } catch (Exception e) {
            vo.setReason(rootMessage(e));
            return vo;
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    // ------------------------------------------------------------------ 项目列表

    @Override
    public List<ComposeProjectVo> list(Host host) {
        DockerClient client = null;
        try {
            client = dockerClientManager.getClient(host);
            HelperConn conn = resolveConn(client, host, false);
            String output = runCapture(client, conn, null,
                    List.of("compose", "ls", "--all", "--format", "json"), 60);
            if (StrUtil.isBlank(output)) {
                return new ArrayList<>();
            }
            return parseProjectList(output);
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    /**
     * 解析 {@code docker compose ls --all --format json} 的输出。
     * 形如 {@code [{"Name":"app","Status":"running(2)","ConfigFiles":"/opt/app/compose.yaml"}]}。
     */
    private List<ComposeProjectVo> parseProjectList(String output) {
        List<ComposeProjectVo> result = new ArrayList<>();
        String text = StrUtil.trim(output);
        // 老版本 compose 不支持 --format json 时可能输出表格文本，这里只处理 JSON
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return result;
        }
        try {
            JsonNode array = JSON.readTree(text.substring(start, end + 1));
            for (JsonNode node : array) {
                ComposeProjectVo vo = new ComposeProjectVo();
                vo.setName(node.path("Name").asText(null));
                String status = node.path("Status").asText("");
                vo.setStatusText(StrUtil.trim(status));
                fillStatus(vo, status);
                String file = pickComposeFile(node.path("ConfigFiles").asText(""));
                if (file != null) {
                    vo.setComposeFile(ComposePaths.fileName(file));
                    vo.setDir(ComposePaths.parentOf(file));
                }
                if (StrUtil.isNotBlank(vo.getDir())) {
                    result.add(vo);
                }
            }
        } catch (Exception e) {
            log.warn("解析 docker compose ls 输出失败: {}", e.getMessage());
        }
        result.sort((a, b) -> StrUtil.compare(a.getName(), b.getName(), true));
        return result;
    }

    private static void fillStatus(ComposeProjectVo vo, String status) {
        int running = 0;
        int total = 0;
        boolean matched = false;
        Matcher m = STATUS_PART.matcher(StrUtil.nullToEmpty(status));
        while (m.find()) {
            matched = true;
            int count = Integer.parseInt(m.group(2));
            total += count;
            if ("running".equalsIgnoreCase(m.group(1))) {
                running += count;
            }
        }
        if (!matched) {
            vo.setStatus("unknown");
            return;
        }
        vo.setRunning(running);
        vo.setTotal(total);
        if (running > 0 && running >= total) {
            vo.setStatus("running");
        } else if (running > 0) {
            vo.setStatus("partial");
        } else {
            vo.setStatus("exited");
        }
    }

    // ------------------------------------------------------------------ 打开目录

    @Override
    public ComposeProjectVo open(Host host, String dir) {
        String projectDir = ComposePaths.dir(dir);
        DockerClient client = null;
        String containerId = null;
        try {
            client = dockerClientManager.getClient(host);
            HelperConn conn = resolveConn(client, host, false);
            containerId = createFileHelper(client, conn, projectDir);

            List<ComposeFileVo> files = filesOf(client, containerId, projectDir);
            List<ComposeFileVo> composeFiles = files.stream()
                    .filter(f -> ComposePaths.isComposeFile(f.getName()))
                    .toList();
            if (composeFiles.isEmpty()) {
                throw new BusinessException("目录下没有 compose 文件（compose.yaml / docker-compose.yml 等）：" + projectDir);
            }

            ComposeProjectVo vo = new ComposeProjectVo();
            vo.setDir(projectDir);
            vo.setName(ComposePaths.fileName(projectDir));
            vo.setComposeFile(mainComposeFile(composeFiles));
            vo.setFiles(files);

            // 状态尽力而为：compose ls 取不到也不影响编辑与执行
            try {
                list(host).stream()
                        .filter(p -> projectDir.equals(p.getDir()))
                        .findFirst()
                        .ifPresent(p -> {
                            vo.setName(StrUtil.blankToDefault(p.getName(), vo.getName()));
                            vo.setStatus(p.getStatus());
                            vo.setStatusText(p.getStatusText());
                            vo.setRunning(p.getRunning());
                            vo.setTotal(p.getTotal());
                        });
            } catch (Exception e) {
                log.debug("读取 compose 项目状态失败: {}", e.getMessage());
            }
            if (StrUtil.isBlank(vo.getStatus())) {
                vo.setStatus("unknown");
            }
            return vo;
        } finally {
            removeQuietly(client, containerId);
            IOUtils.closeQuietly(client);
        }
    }

    @Override
    public List<ComposeFileVo> files(Host host, String dir) {
        String projectDir = ComposePaths.dir(dir);
        DockerClient client = null;
        String containerId = null;
        try {
            client = dockerClientManager.getClient(host);
            HelperConn conn = resolveConn(client, host, false);
            containerId = createFileHelper(client, conn, projectDir);
            return filesOf(client, containerId, projectDir);
        } finally {
            removeQuietly(client, containerId);
            IOUtils.closeQuietly(client);
        }
    }

    /**
     * 项目目录下可编辑的文件：compose 文件与 .env（只取顶层，不递归）。
     */
    private List<ComposeFileVo> filesOf(DockerClient client, String containerId, String dir) {
        List<ComposeFileVo> result = new ArrayList<>();
        for (Entry entry : listDir(client, containerId, dir)) {
            if (!ComposePaths.isComposeFile(entry.name) && !ComposePaths.isEnvFile(entry.name)) {
                continue;
            }
            ComposeFileVo file = new ComposeFileVo();
            file.setName(entry.name);
            file.setPath(dir.equals("/") ? "/" + entry.name : dir + "/" + entry.name);
            file.setSize(entry.size);
            file.setMtime(entry.mtime);
            file.setMode(Integer.toOctalString(entry.mode & 0777));
            result.add(file);
        }
        result.sort((a, b) -> {
            // compose 主文件排前面，其次按名字
            boolean am = ComposePaths.isMainCompose(a.getName());
            boolean bm = ComposePaths.isMainCompose(b.getName());
            if (am != bm) {
                return am ? -1 : 1;
            }
            return StrUtil.compare(a.getName(), b.getName(), true);
        });
        return result;
    }

    // ------------------------------------------------------------------ 文件读写

    @Override
    public ComposeFileContentVo read(Host host, String path) {
        String file = ComposePaths.file(path);
        DockerClient client = null;
        String containerId = null;
        try {
            client = dockerClientManager.getClient(host);
            HelperConn conn = resolveConn(client, host, false);
            containerId = createFileHelper(client, conn, ComposePaths.parentOf(file));
            return readFile(client, containerId, file);
        } finally {
            removeQuietly(client, containerId);
            IOUtils.closeQuietly(client);
        }
    }

    @Override
    public void write(Host host, String path, String content) {
        String file = ComposePaths.file(path);
        writeInternal(host, file, content);
    }

    @Override
    public String backup(Host host, String path, String content, String suffix) {
        String file = ComposePaths.dir(path);
        String target = file + StrUtil.nullToEmpty(suffix);
        writeInternal(host, target, content);
        return target;
    }

    /**
     * 覆盖写入。已存在的文件沿用原权限位，避免把 0600 之类的文件写成 0644。
     */
    private void writeInternal(Host host, String path, String content) {
        DockerClient client = null;
        String containerId = null;
        try {
            client = dockerClientManager.getClient(host);
            HelperConn conn = resolveConn(client, host, false);
            containerId = createFileHelper(client, conn, ComposePaths.parentOf(path));
            int mode = existingMode(client, containerId, path);
            writeFile(client, containerId, path, StrUtil.nullToEmpty(content).getBytes(StandardCharsets.UTF_8), mode);
        } finally {
            removeQuietly(client, containerId);
            IOUtils.closeQuietly(client);
        }
    }

    private ComposeFileContentVo readFile(DockerClient client, String containerId, String path) {
        try (InputStream in = client.copyArchiveFromContainerCmd(containerId, path).exec();
             TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
            TarArchiveEntry entry = tar.getNextEntry();
            if (entry == null) {
                throw new BusinessException("文件不存在：" + path);
            }
            if (entry.isDirectory()) {
                throw new BusinessException("这是一个目录，不能作为文件编辑：" + path);
            }
            if (entry.getSize() > ComposePaths.MAX_FILE_BYTES) {
                throw new BusinessException("文件超过 2MB，请登录主机手工编辑");
            }
            byte[] bytes = readExactly(tar, (int) entry.getSize());
            ComposeFileContentVo vo = new ComposeFileContentVo();
            vo.setPath(path);
            vo.setName(ComposePaths.fileName(path));
            vo.setContent(new String(bytes, StandardCharsets.UTF_8));
            vo.setSize(entry.getSize());
            vo.setMtime(entry.getModTime() == null ? 0L : entry.getModTime().getTime());
            return vo;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("读取文件失败：" + rootMessage(e));
        }
    }

    private void writeFile(DockerClient client, String containerId, String path, byte[] content, int mode) {
        String dir = ComposePaths.parentOf(path);
        String name = ComposePaths.fileName(path);
        try {
            byte[] tar = buildFileTar(name, content, mode);
            client.copyArchiveToContainerCmd(containerId)
                    .withTarInputStream(new ByteArrayInputStream(tar))
                    .withRemotePath(dir)
                    .exec();
        } catch (IOException e) {
            throw new BusinessException("写入文件失败：" + rootMessage(e));
        }
    }

    private int existingMode(DockerClient client, String containerId, String path) {
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
            log.debug("读取 {} 权限失败，按 0644 处理: {}", path, e.getMessage());
        }
        return 0644;
    }

    private static byte[] buildFileTar(String name, byte[] content, int mode) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(4096 + content.length);
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bos)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            TarArchiveEntry entry = new TarArchiveEntry(name);
            entry.setSize(content.length);
            entry.setMode(mode);
            entry.setModTime(System.currentTimeMillis());
            tar.putArchiveEntry(entry);
            tar.write(content);
            tar.closeArchiveEntry();
        }
        return bos.toByteArray();
    }

    private static byte[] readExactly(InputStream in, int size) throws IOException {
        byte[] bytes = new byte[size];
        int read = 0;
        while (read < size) {
            int n = in.read(bytes, read, size - read);
            if (n < 0) {
                break;
            }
            read += n;
        }
        return read == size ? bytes : java.util.Arrays.copyOf(bytes, read);
    }

    // ------------------------------------------------------------------ 校验

    @Override
    public String validate(Host host, String dir, String content) {
        String projectDir = ComposePaths.dir(dir);
        DockerClient client = null;
        String containerId = null;
        try {
            client = dockerClientManager.getClient(host);
            HelperConn conn = resolveConn(client, host, false);
            containerId = createHelper(client, conn, helperName("check"), null, projectDir,
                    List.of("compose", "--project-directory", projectDir, "--ansi", "never", "-f", TMP_COMPOSE_FILE, "config", "-q"), null);
            // 把新内容写进容器自己的根文件系统，不落宿主机
            writeContainerFile(client, containerId, TMP_COMPOSE_FILE, content.getBytes(StandardCharsets.UTF_8));

            StringBuilder output = new StringBuilder();
            int code = startAndWait(client, containerId, line -> output.append(line).append('\n'), 120, null);
            if (code == 0) {
                return null;
            }
            String message = StrUtil.trim(output.toString());
            return StrUtil.isBlank(message) ? "compose 配置校验失败（退出码 " + code + "）" : message;
        } finally {
            removeQuietly(client, containerId);
            IOUtils.closeQuietly(client);
        }
    }

    private void writeContainerFile(DockerClient client, String containerId, String path, byte[] content) {
        String name = ComposePaths.fileName(path);
        String dir = ComposePaths.parentOf(path);
        try {
            byte[] tar = buildFileTar(name, content, 0644);
            client.copyArchiveToContainerCmd(containerId)
                    .withTarInputStream(new ByteArrayInputStream(tar))
                    .withRemotePath(dir)
                    .exec();
        } catch (IOException e) {
            throw new BusinessException("写入临时文件失败：" + rootMessage(e));
        }
    }

    // ------------------------------------------------------------------ 执行命令

    @Override
    public int run(Host host, String dir, List<String> args, String logId) {
        String projectDir = ComposePaths.dir(dir);
        DockerClient client = null;
        String containerId = null;
        try {
            client = dockerClientManager.getClient(host);
            HelperConn conn = resolveConn(client, host, false);

            List<String> cmd = new ArrayList<>();
            cmd.add("compose");
            cmd.add("--project-directory");
            cmd.add(projectDir);
            cmd.add("--ansi");
            cmd.add("never");
            cmd.addAll(args);

            containerId = createHelper(client, conn, helperName(logId), logId, projectDir, cmd, projectDir);
            return startAndWait(client, containerId, line -> fileLog.info(line),
                    config.getComposeTimeoutSeconds(), logId);
        } finally {
            removeQuietly(client, containerId);
            IOUtils.closeQuietly(client);
        }
    }

    // ------------------------------------------------------------------ 辅助镜像

    @Override
    public void pullImage(Host host, String image, String logId) {
        DockerClient client = null;
        try {
            client = dockerClientManager.getClient(host);
            fileLog.info("拉取辅助镜像 {}（首次使用需要，约 30MB）", image);
            // docker-java 的 pullImageCmd 不拆 tag，直接传 docker:cli 会变成「拉取全部 tag」
            try {
                client.pullImageCmd(repoOf(image))
                        .withTag(tagOf(image))
                        .exec(new DefaultCallback<PullResponseItem>(logId))
                        .awaitCompletion();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException("拉取辅助镜像被中断");
            }
            fileLog.info("辅助镜像已就绪：{}", image);
            connCache.clear();
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    // ------------------------------------------------------------------ daemon 连接

    /**
     * 辅助容器访问 daemon 的方式。
     *
     * @param mode       socket / tcp
     * @param dockerHost tcp 模式下给容器用的 DOCKER_HOST（tcp://host:port）
     * @param socketPath socket 模式下挂进容器的 socket 路径
     */
    private record HelperConn(String mode, String dockerHost, String socketPath) {
    }

    private record CachedConn(HelperConn conn, long expireAt) {
    }

    private HelperConn resolveConn(DockerClient client, Host host, boolean force) {
        String key = StrUtil.nullToEmpty(host.getId()) + "|" + StrUtil.nullToEmpty(host.getDockerHost());
        CachedConn cached = connCache.get(key);
        if (!force && cached != null && cached.expireAt > System.currentTimeMillis()) {
            return cached.conn;
        }

        List<HelperConn> candidates = connCandidates(host);
        List<String> failures = new ArrayList<>();
        for (HelperConn candidate : candidates) {
            String error = testConn(client, candidate);
            if (error == null) {
                connCache.put(key, new CachedConn(candidate, System.currentTimeMillis() + CONN_TTL_MILLIS));
                return candidate;
            }
            failures.add(candidate.mode + (StrUtil.isBlank(candidate.dockerHost) ? "" : " " + candidate.dockerHost) + "：" + error);
        }
        throw new BusinessException("辅助容器无法连接该主机的 docker（" + String.join("；", failures)
                + "）。请确认主机的 docker 端口允许本机容器访问");
    }

    /**
     * 候选连接方式。TCP 主机用 host 网络 + 平台正在用的 TCP 地址；本机用 socket。
     * <p>
     * TCP 主机不会去试 socket：bind 一个不存在的 socket 路径会把它创建成目录，
     * 反而可能影响宿主机上的 docker。
     */
    private List<HelperConn> connCandidates(Host host) {
        String dockerHost = StrUtil.trim(host.getDockerHost());
        if (host.isLocalDocker()) {
            String socket = StrUtil.blankToDefault(config.getComposeSocketPath(), DEFAULT_SOCKET);
            return List.of(new HelperConn("socket", null, socket));
        }
        if (StrUtil.startWithIgnoreCase(dockerHost, Host.HTTPS_PREFIX)
                || StrUtil.startWithIgnoreCase(dockerHost, "https://")) {
            throw new BusinessException("TLS 主机暂不支持 Compose 管理：辅助容器还需要客户端证书，当前未做");
        }

        String authority = dockerHost.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "");
        String hostPart = authority;
        int port = 2375;
        int idx = authority.lastIndexOf(':');
        if (idx > 0) {
            hostPart = authority.substring(0, idx);
            try {
                port = Integer.parseInt(authority.substring(idx + 1));
            } catch (NumberFormatException ignored) {
                // 地址里没有合法端口，按默认 2375
            }
        }
        if (StrUtil.isBlank(hostPart)) {
            throw new BusinessException("主机未配置 docker 地址，无法在主机上启动辅助容器");
        }

        List<HelperConn> list = new ArrayList<>();
        // 平台连的就是这个地址，主机自己的网络命名空间里同样可达
        list.add(new HelperConn("tcp", "tcp://" + hostPart + ":" + port, null));
        if ("localhost".equalsIgnoreCase(hostPart) || "127.0.0.1".equals(hostPart)) {
            // Docker Desktop：daemon 在 Windows/macOS 侧，容器里要用 host.docker.internal 才到得了
            list.add(new HelperConn("tcp", "tcp://host.docker.internal:" + port, null));
        } else {
            // dockerd 只监听 0.0.0.0 时，host 网络下的回环地址同样可达
            list.add(new HelperConn("tcp", "tcp://127.0.0.1:" + port, null));
        }
        return list;
    }

    /**
     * 起一个探针容器执行 {@code docker ps}，返回 null 表示连接可用。
     */
    private String testConn(DockerClient client, HelperConn conn) {
        String id = null;
        try {
            id = createHelper(client, conn, helperName("probe"), null, null, List.of("ps", "-q"), null);
            int code = startAndWait(client, id, line -> { }, 20, null);
            if (code == 0) {
                return null;
            }
            return "退出码 " + code;
        } catch (Exception e) {
            return rootMessage(e);
        } finally {
            removeQuietly(client, id);
        }
    }

    private String composeVersion(DockerClient client, HelperConn conn) {
        try {
            String output = runCapture(client, conn, null, List.of("compose", "version", "--short"), 30);
            return StrUtil.trim(output);
        } catch (Exception e) {
            log.debug("读取 compose 版本失败: {}", e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ 容器生命周期

    private String createFileHelper(DockerClient client, HelperConn conn, String dir) {
        // 目录按同一路径挂进容器，后面用 archive 接口读写（不启动容器）
        return createHelper(client, conn, helperName("file"), null, dir, List.of(), null);
    }

    /**
     * @param mountPath 需要与宿主机同路径挂载的目录（项目目录）
     * @param workDir   容器工作目录
     */
    private String createHelper(DockerClient client, HelperConn conn, String name, String logId,
                                String mountPath, List<String> args, String workDir) {
        String image = helperImage();

        List<String> env = new ArrayList<>();
        env.add("HOME=/tmp");
        env.add("DOCKER_CONFIG=/tmp");
        env.add("COMPOSE_ANSI=never");

        List<Bind> binds = new ArrayList<>();
        String networkMode = null;
        if ("tcp".equals(conn.mode)) {
            // 与宿主机共享网络命名空间，直接访问宿主机自己的 TCP 地址
            networkMode = "host";
            env.add("DOCKER_HOST=" + conn.dockerHost);
        } else {
            binds.add(new Bind(conn.socketPath, new Volume(conn.socketPath)));
            env.add("DOCKER_HOST=unix://" + conn.socketPath);
        }
        if (StrUtil.isNotBlank(mountPath)) {
            binds.add(new Bind(mountPath, new Volume(mountPath)));
        }

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withAutoRemove(false)
                .withTmpFs(TMP_FS)
                .withBinds(binds);
        if (networkMode != null) {
            hostConfig.withNetworkMode(networkMode);
            // Docker Desktop 的 daemon 在宿主 OS 上，容器里要用这个域名才能回到宿主
            hostConfig.withExtraHosts("host.docker.internal:host-gateway");
        }

        Map<String, String> labels = new HashMap<>();
        labels.put(LABEL_HELPER, "1");
        if (StrUtil.isNotBlank(logId)) {
            labels.put(LABEL_LOG_ID, logId);
        }

        CreateContainerCmd cmd = client.createContainerCmd(image)
                .withName(name)
                .withHostConfig(hostConfig)
                .withEnv(env)
                .withEntrypoint("docker")
                .withLabels(labels);
        if (StrUtil.isNotBlank(workDir)) {
            cmd.withWorkingDir(workDir);
        }
        if (!args.isEmpty()) {
            cmd.withCmd(args);
        }
        return cmd.exec().getId();
    }

    /**
     * 启动容器、跟随日志直到结束，返回退出码。
     *
     * @param lineSink 每行输出（stdout + stderr）的回调
     * @param logId    非空时把回调线程绑定到该日志文件（FileLog 是 ThreadLocal 的）
     */
    private int startAndWait(DockerClient client, String containerId, Consumer<String> lineSink,
                             int timeoutSeconds, String logId) {
        LogFollower follower = new LogFollower(lineSink, logId);
        try {
            client.startContainerCmd(containerId).exec();
            client.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withFollowStream(true)
                    .withTailAll()
                    .exec(follower);

            Integer code = client.waitContainerCmd(containerId).start()
                    .awaitStatusCode(Math.max(5, timeoutSeconds), TimeUnit.SECONDS);
            return code == null ? -1 : code;
        } catch (Exception e) {
            // 超时或连接异常：先把容器停了，避免留一个还在跑的任务
            killQuietly(client, containerId);
            throw new BusinessException("执行 compose 命令失败：" + rootMessage(e));
        } finally {
            try {
                follower.awaitCompletion(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // 容器已结束，日志流可能已经关闭
            }
            try {
                follower.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private String runCapture(DockerClient client, HelperConn conn, String workDir, List<String> args, int timeoutSeconds) {
        String containerId = null;
        try {
            containerId = createHelper(client, conn, helperName("capture"), null, workDir, args, workDir);
            StringBuilder output = new StringBuilder();
            int code = startAndWait(client, containerId, line -> output.append(line).append('\n'), timeoutSeconds, null);
            if (code != 0) {
                String text = StrUtil.trim(output.toString());
                throw new BusinessException("docker compose 执行失败（退出码 " + code + "）"
                        + (StrUtil.isBlank(text) ? "" : "：" + text));
            }
            return output.toString();
        } finally {
            removeQuietly(client, containerId);
        }
    }

    private String helperImage() {
        return StrUtil.blankToDefault(config.getComposeHelperImage(), "docker:cli");
    }

    private static void killQuietly(DockerClient client, String containerId) {
        if (client == null || StrUtil.isBlank(containerId)) {
            return;
        }
        try {
            client.killContainerCmd(containerId).exec();
        } catch (Exception ignored) {
            // 容器可能已经退出
        }
    }

    private static void removeQuietly(DockerClient client, String containerId) {
        if (client == null || StrUtil.isBlank(containerId)) {
            return;
        }
        try {
            client.removeContainerCmd(containerId).withForce(true).exec();
        } catch (Exception e) {
            log.debug("清理 compose 辅助容器失败 {}: {}", containerId, e.getMessage());
        }
    }

    private static String helperName(String tag) {
        return "docker-admin-compose-" + tag + "-" + RandomUtil.randomString(8);
    }

    // ------------------------------------------------------------------ 目录读取

    private record Entry(String name, long size, long mtime, int mode) {
    }

    /**
     * 列出目录顶层的文件。Docker archive 返回的 entry 形如 {@code <目录名>/<文件>}，
     * 这里只保留当前层，避免把子目录里的东西也当成可编辑文件。
     */
    private List<Entry> listDir(DockerClient client, String containerId, String dir) {
        List<Entry> result = new ArrayList<>();
        String prefix = ComposePaths.fileName(dir) + "/";
        try (InputStream in = client.copyArchiveFromContainerCmd(containerId, dir).exec();
             TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
            TarArchiveEntry entry;
            int count = 0;
            while ((entry = tar.getNextEntry()) != null) {
                if (++count > MAX_LIST_ENTRIES) {
                    break;
                }
                if (entry.isDirectory()) {
                    continue;
                }
                String name = StrUtil.removePrefix(entry.getName(), "./");
                if (name.startsWith(prefix)) {
                    name = name.substring(prefix.length());
                }
                if (name.contains("/")) {
                    continue;
                }
                result.add(new Entry(name, entry.getSize(),
                        entry.getModTime() == null ? 0L : entry.getModTime().getTime(), entry.getMode()));
            }
        } catch (Exception e) {
            throw new BusinessException("读取目录失败：" + rootMessage(e));
        }
        return result;
    }

    // ------------------------------------------------------------------ 工具

    /**
     * {@code compose ls} 的 ConfigFiles 可能包含多个文件（逗号分隔），优先取主 compose 文件。
     */
    private static String pickComposeFile(String configFiles) {
        if (StrUtil.isBlank(configFiles)) {
            return null;
        }
        String[] parts = configFiles.split(",");
        // 先找主文件，没有就取第一个
        for (String part : parts) {
            String file = StrUtil.trim(part);
            if (StrUtil.isNotBlank(file) && ComposePaths.isMainCompose(ComposePaths.fileName(file))) {
                return file;
            }
        }
        for (String part : parts) {
            String file = StrUtil.trim(part);
            if (StrUtil.isNotBlank(file)) {
                return file;
            }
        }
        return null;
    }

    private static String mainComposeFile(List<ComposeFileVo> files) {
        return files.stream().filter(f -> ComposePaths.isMainCompose(f.getName())).findFirst()
                .orElse(files.get(0)).getName();
    }

    /**
     * 镜像地址拆成 repo 与 tag。docker-java 的 pullImageCmd 不会自己拆，
     * 直接把 {@code docker:cli} 传进去会变成「拉取该仓库全部 tag」。
     */
    private static String repoOf(String image) {
        String value = StrUtil.trim(image);
        int slash = value.lastIndexOf('/');
        int colon = value.lastIndexOf(':');
        return colon > slash ? value.substring(0, colon) : value;
    }

    private static String tagOf(String image) {
        String value = StrUtil.trim(image);
        int slash = value.lastIndexOf('/');
        int colon = value.lastIndexOf(':');
        return colon > slash ? value.substring(colon + 1) : "latest";
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        String message = t.getMessage();
        return StrUtil.isBlank(message) ? t.getClass().getSimpleName() : message;
    }

    // ------------------------------------------------------------------ 日志流

    /**
     * 按行切分容器输出。compose 的进度用 {@code \r} 刷新，这里一律当作换行，
     * 保证日志里每一行都是完整可读的。
     */
    private static final class LineSplitter {

        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        void accept(byte[] payload, Consumer<String> sink) {
            for (byte b : payload) {
                if (b == '\n' || b == '\r') {
                    emit(sink);
                } else {
                    buffer.write(b);
                }
            }
        }

        void flush(Consumer<String> sink) {
            emit(sink);
        }

        private void emit(Consumer<String> sink) {
            if (buffer.size() == 0) {
                return;
            }
            String line = ANSI.matcher(buffer.toString(StandardCharsets.UTF_8)).replaceAll("").trim();
            buffer.reset();
            if (!line.isEmpty()) {
                sink.accept(line);
            }
        }
    }

    /**
     * 容器日志回调：stdout / stderr 分别按行切分后交给同一个 sink。
     */
    private static final class LogFollower extends ResultCallback.Adapter<Frame> {

        private final LineSplitter stdout = new LineSplitter();
        private final LineSplitter stderr = new LineSplitter();
        private final Consumer<String> sink;
        private final String logId;

        LogFollower(Consumer<String> sink, String logId) {
            this.sink = sink;
            this.logId = logId;
        }

        @Override
        public void onStart(Closeable stream) {
            if (StrUtil.isNotBlank(logId)) {
                FileLog.bind(logId);
            }
            super.onStart(stream);
        }

        @Override
        public void onNext(Frame frame) {
            if (frame == null || frame.getPayload() == null) {
                return;
            }
            LineSplitter splitter = StreamType.STDERR.equals(frame.getStreamType()) ? stderr : stdout;
            splitter.accept(frame.getPayload(), sink);
        }

        @Override
        public void onComplete() {
            try {
                stdout.flush(sink);
                stderr.flush(sink);
                super.onComplete();
            } finally {
                if (StrUtil.isNotBlank(logId)) {
                    FileLog.unbind();
                }
            }
        }

        @Override
        public void onError(Throwable throwable) {
            try {
                stdout.flush(sink);
                stderr.flush(sink);
                super.onError(throwable);
            } finally {
                if (StrUtil.isNotBlank(logId)) {
                    FileLog.unbind();
                }
            }
        }
    }

    /**
     * 按日志标识查找并结束仍在跑的辅助容器，供「取消」使用。
     */
    @Override
    public void cancel(Host host, String logId) {
        DockerClient client = null;
        try {
            client = dockerClientManager.getClient(host);
            final DockerClient docker = client;
            Map<String, String> filter = new LinkedHashMap<>();
            filter.put(LABEL_LOG_ID, logId);
            client.listContainersCmd().withShowAll(true).withLabelFilter(filter).exec()
                    .forEach(c -> {
                        killQuietly(docker, c.getId());
                        removeQuietly(docker, c.getId());
                    });
        } catch (Exception e) {
            log.warn("取消 compose 任务失败: {}", e.getMessage());
        } finally {
            IOUtils.closeQuietly(client);
        }
    }
}
