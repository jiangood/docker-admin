package io.github.jiangood.docker.admin.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.PushImageCmd;
import com.github.dockerjava.api.model.AuthConfig;
import com.github.dockerjava.api.model.LoadResponseItem;
import com.github.dockerjava.api.model.PushResponseItem;
import io.github.jiangood.docker.admin.entity.ImageTag;
import io.github.jiangood.docker.admin.entity.Registry;
import io.github.jiangood.docker.admin.websocket.ImageImportLogHandshakeInterceptor;
import io.github.jiangood.docker.admin.websocket.TaskLogRegistry;
import io.github.jiangood.docker.base.FileLog;
import io.github.jiangood.docker.sdk.engine.DefaultCallback;
import io.github.jiangood.docker.sdk.engine.DockerClientManager;
import lombok.RequiredArgsConstructor;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 镜像导入：把用户上传的 {@code docker save} 归档载入 docker，再推送到平台注册中心，
 * 并登记镜像与版本（来源 IMPORT）。
 * <p>
 * 与导出的方向相反：导出是「注册中心 → 本机 docker → tar 下载」，导入是
 * 「上传 tar → 本机 docker load → 打标 → push 注册中心」。
 * <p>
 * 载入与推送都在后台线程执行，进度写入 {@code /data/logs/{logId}.log}，
 * 前端通过 {@code /admin/ws/image-import-log/{logId}} 实时查看。
 * <p>
 * 载入统一使用平台所在主机的 docker（compose 部署已挂载 docker.sock），不支持指定远程主机。
 * <p>
 * 镜像名与版本都可以不填：默认按 tar 内的 RepoTags 导入，
 * 即 {@code docker save app:1.0} 会导入为 {@code 注册中心/命名空间/app:1.0}。
 * 指定镜像名时只允许归档里只有一个镜像，便于改名重新发布。
 */
@Service
@RequiredArgsConstructor
public class ImageImportService {

    private static final Logger fileLog = FileLog.logger();

    /**
     * 上传归档的临时目录（系统临时目录下，任务结束即删）。
     */
    private static final String IMPORT_DIR_NAME = "docker-admin-imports";

    /**
     * 临时文件保留时长：任务异常退出（如进程被杀）时，靠后续导入顺带清扫。
     */
    private static final long TTL_MILLIS = 24 * 60 * 60 * 1000L;

    /**
     * 版本号规则，与 docker tag 一致。
     */
    private static final Pattern TAG_PATTERN = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$");

    private final DockerClientManager dockerManager;

    private final RegistryService registryService;

    private final ImageService imageService;

    private final ImageTagService imageTagService;

    private final ImageImportRegistry importRegistry;

    private final TaskLogRegistry taskLogRegistry;

    /**
     * 同步校验用户填写的镜像名与版本，让错误在开始上传前就能返回。
     */
    public void validate(String imageName, String tag) {
        if (StrUtil.isNotBlank(imageName)) {
            boolean valid = !imageName.contains(":")
                    && !imageName.contains("@")
                    && !imageName.contains(" ")
                    && !imageName.contains("\\")
                    && !imageName.startsWith("/")
                    && !imageName.endsWith("/");
            Assert.isTrue(valid, "镜像名不合法：不要包含地址、版本、空格，如 myapp 或 team/myapp");
        }
        if (StrUtil.isNotBlank(tag)) {
            Assert.isTrue(TAG_PATTERN.matcher(tag).matches(),
                    "版本号不合法：只能包含字母、数字、.、_、-，且不能以 . 或 - 开头");
        }
    }

    /**
     * 后台导入。异常只写进任务日志（前端在日志里可见），不向上抛。
     */
    @Async
    public void importTar(String logId, File tar, String registryId, String imageName, String tag) {
        taskLogRegistry.start(logId);
        FileLog.bind(logId);

        DockerClient client = null;
        boolean success = false;
        String message = null;
        List<String> imported = new ArrayList<>();
        try {
            Registry registry = registryService.requireRegistry(registryId);
            String registryBase = StrUtil.removeSuffix(StrUtil.nullToEmpty(registry.getFullUrl()), "/");
            Assert.hasText(registryBase,
                    "注册中心地址未配置，请先在【设置-镜像注册中心】中配置地址与命名空间");

            fileLog.info("开始导入镜像归档 {}", tar.getName());
            fileLog.info("归档大小: {}", FileUtil.readableFileSize(tar.length()));
            fileLog.info("执行主机: 平台所在主机（本机 Docker）");
            fileLog.info("目标注册中心: {}", registryBase);

            client = dockerManager.getLocalClient();

            // docker 的 load 接口不返回结构化结果，只能从回调流里识别 "Loaded image: xxx"
            LoadCallback callback = new LoadCallback(logId);
            fileLog.info("载入归档（大文件需要几分钟）…");
            try (InputStream in = Files.newInputStream(tar.toPath())) {
                client.loadImageAsyncCmd(in).exec(callback).awaitCompletion();
            }
            fileLog.info("载入完成，识别到 {} 个带标签的镜像", callback.taggedRefs().size());

            List<ImportTarget> targets = resolveTargets(callback.taggedRefs(), callback.imageIds(),
                    imageName, tag, registryBase);

            List<String> failures = new ArrayList<>();
            for (ImportTarget target : targets) {
                try {
                    importOne(client, registry, target, logId);
                    imported.add(target.fullName());
                } catch (Exception e) {
                    fileLog.error("镜像 {} 导入失败: {}", target.fullName(), e.getMessage());
                    fileLog.debug("镜像导入失败详情", e);
                    failures.add(target.fullName() + "（" + e.getMessage() + "）");
                }
            }

            if (failures.isEmpty()) {
                success = true;
                message = "已导入 " + imported.size() + " 个镜像版本";
                fileLog.info("镜像导入完成：{}", String.join("、", imported));
            } else {
                message = "部分镜像导入失败：" + String.join("；", failures);
                fileLog.error(message);
            }
        } catch (Exception e) {
            message = e.getMessage();
            fileLog.error("镜像导入失败: {}", e.getMessage());
            fileLog.debug("镜像导入失败详情", e);
        } finally {
            FileUtil.del(tar);
            IOUtils.closeQuietly(client);
            FileLog.unbind();
            taskLogRegistry.finish(logId);
            importRegistry.finish(logId, success
                    ? ImageImportRegistry.Status.SUCCESS
                    : ImageImportRegistry.Status.FAILED, message, imported);
        }
    }

    // ------------------------------------------------------------------ 上传目录

    /**
     * 本次导入的临时归档文件。logId 同时是文件名，必须走白名单校验防止目录穿越。
     */
    public File newTarFile(String logId) {
        Assert.state(isValidLogId(logId), "非法的导入任务标识");
        File dir = importDir();
        Assert.state(dir.isDirectory() || dir.mkdirs(), "导入临时目录不可用: " + dir.getAbsolutePath());
        sweepExpired();
        return new File(dir, logId + ".tar");
    }

    public boolean isValidLogId(String logId) {
        return logId != null && ImageImportLogHandshakeInterceptor.LOG_ID_PATTERN.matcher(logId).matches();
    }

    /**
     * 清扫上次异常退出遗留的归档与任务登记。
     */
    public void sweepExpired() {
        File dir = importDir();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".tar"));
        if (files != null) {
            long deadline = System.currentTimeMillis() - TTL_MILLIS;
            for (File file : files) {
                if (file.lastModified() < deadline && file.delete()) {
                    fileLog.debug("清理过期导入归档 {}", file.getName());
                }
            }
        }
        importRegistry.sweep(TTL_MILLIS);
    }

    private static File importDir() {
        return new File(System.getProperty("java.io.tmpdir"), IMPORT_DIR_NAME);
    }

    // ------------------------------------------------------------------ 内部

    private void importOne(DockerClient client, Registry registry, ImportTarget target, String logId)
            throws InterruptedException {
        fileLog.info("打标签 {} → {}", target.source(), target.fullName());
        client.tagImageCmd(target.source(), target.targetRepo(), target.tag()).exec();

        fileLog.info("推送到注册中心 {}", target.fullName());
        PushImageCmd pushCmd = client.pushImageCmd(target.fullName());
        AuthConfig auth = authOf(registry);
        if (auth != null) {
            pushCmd.withAuthConfig(auth);
        }
        pushCmd.exec(new DefaultCallback<PushResponseItem>(logId)).awaitCompletion();

        // 推送时已在本机打好注册中心前缀的 tag，直接 inspect 读取端口/卷等声明
        try {
            imageService.upsert(target.targetRepo(), target.name(), "IMPORT", registry.getId(), null);
            ImageTag saved = imageTagService.inspect(client, target.fullName(), target.targetRepo(),
                    target.tag(), null, "IMPORT");
            if (saved == null) {
                imageTagService.upsert(target.targetRepo(), target.tag(), "IMPORT");
            }
        } catch (Exception e) {
            // 登记失败不影响镜像已经推到注册中心的事实
            fileLog.warn("登记镜像失败: {}", e.getMessage());
        }
    }

    /**
     * 把载入结果整理成「源镜像 → 注册中心目标地址」。
     * <p>
     * 不指定镜像名时按 tar 内标签导入（多镜像归档逐个导入）；
     * 指定镜像名时只允许单个镜像，版本不填则沿用 tar 内的 tag。
     *
     * @param refs 带标签的镜像引用（docker load 输出的 {@code Loaded image:}）
     * @param ids  无标签镜像的 ID（{@code Loaded image ID:}），仅在指定镜像名且归档只有一个镜像时可用
     */
    static List<ImportTarget> resolveTargets(List<String> refs, List<String> ids, String imageName, String tag,
                                             String registryBase) {
        List<ImportTarget> targets = new ArrayList<>();
        if (StrUtil.isNotBlank(imageName)) {
            Assert.isTrue(refs.size() + ids.size() == 1,
                    "归档中包含多个镜像，指定镜像名时只能导入单个镜像");
            String source = !refs.isEmpty() ? refs.get(0) : ids.get(0);
            boolean hasTag = StrUtil.isNotBlank(tag);
            Assert.state(!refs.isEmpty() || hasTag,
                    "归档中没有版本信息，请同时填写镜像名与版本");
            targets.add(new ImportTarget(source, registryBase + "/" + imageName, imageName,
                    hasTag ? tag : tagOf(source)));
        } else {
            Assert.state(!refs.isEmpty(),
                    "归档中没有镜像标签信息，请填写镜像名与版本后再导入");
            if (StrUtil.isNotBlank(tag)) {
                Assert.isTrue(refs.size() == 1, "归档中包含多个镜像，不能统一指定版本");
            }
            for (String ref : refs) {
                String repo = repoOf(ref);
                String targetTag = StrUtil.isNotBlank(tag) ? tag : tagOf(ref);
                String name = lastSegment(repo);
                Assert.hasText(name, "无法从镜像 " + ref + " 解析镜像名");
                targets.add(new ImportTarget(ref, registryBase + "/" + name, name, targetTag));
            }
        }

        Set<String> unique = new LinkedHashSet<>();
        for (ImportTarget target : targets) {
            Assert.isTrue(unique.add(target.fullName()),
                    "归档中存在多个同名目标镜像（" + target.fullName() + "），请逐个导入或指定镜像名");
        }
        return targets;
    }

    /**
     * 源镜像去掉 tag 后的仓库名。
     */
    private static String repoOf(String ref) {
        int lastSlash = ref.lastIndexOf('/');
        int lastColon = ref.lastIndexOf(':');
        return lastColon > lastSlash ? ref.substring(0, lastColon) : ref;
    }

    /**
     * 源镜像的 tag；没有 tag 时按 docker 习惯取 latest。
     */
    private static String tagOf(String ref) {
        int lastSlash = ref.lastIndexOf('/');
        int lastColon = ref.lastIndexOf(':');
        return lastColon > lastSlash ? ref.substring(lastColon + 1) : "latest";
    }

    /**
     * 仓库名最后一段作为注册中心下的镜像名：docker.io/library/nginx → nginx。
     */
    private static String lastSegment(String repo) {
        int index = repo.lastIndexOf('/');
        return index >= 0 ? repo.substring(index + 1) : repo;
    }

    /**
     * 注册中心认证信息，未配置用户名时返回 null（匿名推送）。
     * <p>
     * 必须显式设置 serveraddress：AuthConfig 默认指向 Docker Hub，
     * 不覆盖会把凭据发到错误的服务地址，导致 push access denied。
     */
    private static AuthConfig authOf(Registry registry) {
        if (registry == null || StrUtil.isBlank(registry.getUsername())) {
            return null;
        }
        return new AuthConfig()
                .withUsername(registry.getUsername())
                .withPassword(registry.getPassword())
                .withRegistryAddress(registry.getUrl());
    }

    /**
     * docker load 的回调：在打印日志的同时收集载入的镜像引用。
     * <p>
     * docker 对带标签的镜像输出 {@code Loaded image: repo:tag}，
     * 对无标签镜像输出 {@code Loaded image ID: sha256:...}。
     */
    private static final class LoadCallback extends DefaultCallback<LoadResponseItem> {

        private static final String LOADED_IMAGE = "Loaded image: ";
        private static final String LOADED_IMAGE_ID = "Loaded image ID: ";

        private final List<String> taggedRefs = new ArrayList<>();
        private final List<String> imageIds = new ArrayList<>();

        LoadCallback(String logId) {
            super(logId);
        }

        @Override
        public void onNext(LoadResponseItem item) {
            String stream = item.getStream();
            if (stream != null) {
                for (String line : stream.split("\\r?\\n|\\r")) {
                    String value = line.trim();
                    if (value.startsWith(LOADED_IMAGE)) {
                        taggedRefs.add(value.substring(LOADED_IMAGE.length()).trim());
                    } else if (value.startsWith(LOADED_IMAGE_ID)) {
                        imageIds.add(value.substring(LOADED_IMAGE_ID.length()).trim());
                    }
                }
            }
            super.onNext(item);
        }

        List<String> taggedRefs() {
            return taggedRefs;
        }

        List<String> imageIds() {
            return imageIds;
        }

    }

    /**
     * 一次推送的目标：来源引用、注册中心仓库、镜像名与版本。
     */
    record ImportTarget(String source, String targetRepo, String name, String tag) {

        String fullName() {
            return targetRepo + ":" + tag;
        }

    }

}
