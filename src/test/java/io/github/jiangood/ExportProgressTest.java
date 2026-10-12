package io.github.jiangood;

import cn.hutool.core.io.FileUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.model.PullResponseItem;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.entity.Registry;
import io.github.jiangood.docker.sdk.engine.DefaultCallback;
import io.github.jiangood.docker.sdk.engine.DockerClientManager;
import org.apache.commons.io.IOUtils;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 验证镜像导出三个阶段都能拿到进度：
 * <ol>
 *   <li><b>pull</b>：进度由 {@link DefaultCallback} 输出（与生产一致，docker 原生进度明细）</li>
 *   <li><b>save</b>：读取 daemon 的 tar 流时按字节数报进度（相对镜像大小）</li>
 *   <li><b>download</b>：把 tar 写出去时按字节数报进度（对应 HTTP 响应体 / 浏览器下载）</li>
 * </ol>
 * <p>
 * 用法（参数可选，默认本地 registry 的 alpine）：
 * <pre>
 *   ExportProgressTest [镜像地址] [tag] [注册中心地址]
 *   # 例：验证大镜像的真实下载进度
 *   ExportProgressTest docker.m.daocloud.io/library/mysql 8.0 docker.m.daocloud.io
 * </pre>
 * 要点：本地 daemon 上不能已有该镜像，否则 docker 不会真正下载层、pull 阶段看不到进度。
 * <p>
 * 与项目其余测试一致，这是 {@code main()} 方法，直接在 IDE 中运行本类即可。
 */
public class ExportProgressTest {

    /** 进度上报步长：百分比每涨 5% 或每 2MB 报一次。 */
    private static final int PCT_STEP = 5;
    private static final long BYTE_STEP = 2L * 1024 * 1024;

    public static void main(String[] args) throws Exception {
        String imageUrl = args.length > 0 ? args[0] : "localhost:5000/test/alpine";
        String tag = args.length > 1 ? args[1] : "v1";
        String registryUrl = args.length > 2 ? args[2] : "";
        String full = imageUrl + ":" + tag;

        DockerClientManager manager = new DockerClientManager();
        Registry registry = new Registry();
        if (!registryUrl.isEmpty()) {
            registry.setUrl(registryUrl);
        }

        DockerClient client = manager.getClient((Host) null, registry);
        try {
            String logId = "export-progress-" + UUID.randomUUID();
            System.out.println("任务 logId = " + logId);
            System.out.println("本机 daemon = " + manager.getLocalDockerHost());
            System.out.println("目标镜像   = " + full);
            boolean preExist = exists(client, full);
            System.out.println("本机是否已有该镜像 = " + preExist
                    + (preExist ? "   ← 警告：层可能已缓存，pull 阶段将看不到真实下载进度" : ""));
            System.out.println();

            // ================= 阶段 1/3：pull =================
            System.out.println("==================== 1/3 pull（来自注册中心，进度由 DefaultCallback 输出）====================");
            long t0 = System.currentTimeMillis();
            client.pullImageCmd(imageUrl)
                    .withTag(tag)
                    .exec(new DefaultCallback<PullResponseItem>(logId))
                    .awaitCompletion();
            System.out.println("pull 耗时 " + (System.currentTimeMillis() - t0) + "ms");

            long imageSize = inspectSize(client, full);
            System.out.println("镜像大小(参考) = " + FileUtil.readableFileSize(imageSize));
            System.out.println();

            // ================= 阶段 2/3：save =================
            System.out.println("==================== 2/3 save（读取 daemon 的 tar 流）====================");
            Path tar = Path.of("target", "progress-test.tar");
            long saved;
            long t1 = System.currentTimeMillis();
            try (InputStream in = client.saveImageCmd(full).exec();
                 OutputStream out = Files.newOutputStream(tar)) {
                saved = copyWithProgress(in, out, imageSize, "save");
            }
            System.out.println("save 耗时 " + (System.currentTimeMillis() - t1) + "ms，tar = "
                    + FileUtil.readableFileSize(saved));
            System.out.println();

            // ================= 阶段 3/3：download =================
            System.out.println("==================== 3/3 download（写出到输出流，对应 HTTP 响应体 / 浏览器下载）====================");
            long total = Files.size(tar);
            long t2 = System.currentTimeMillis();
            try (InputStream in = Files.newInputStream(tar);
                 OutputStream sink = OutputStream.nullOutputStream()) {
                copyWithProgress(in, sink, total, "download");
            }
            System.out.println("download 耗时 " + (System.currentTimeMillis() - t2) + "ms");

            System.out.println();
            System.out.println("ALL OK");
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    private static boolean exists(DockerClient client, String ref) {
        try {
            client.inspectImageCmd(ref).exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 带进度的拷贝：百分比每涨 {@link #PCT_STEP}% 或距离上次上报超过 {@link #BYTE_STEP} 字节时打印一行。
     */
    private static long copyWithProgress(InputStream in, OutputStream out, long total, String label) throws Exception {
        byte[] buf = new byte[64 * 1024];
        long read = 0;
        long lastReportBytes = -1;
        int lastPct = -1;
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            read += n;

            int pct = total > 0 ? (int) Math.min(100, read * 100 / total) : -1;
            boolean pctDue = pct >= 0 && pct - lastPct >= PCT_STEP;
            boolean byteDue = lastReportBytes < 0 || read - lastReportBytes >= BYTE_STEP;
            if (pctDue || byteDue) {
                System.out.printf("  [%s] %s / %s%s%n", label,
                        FileUtil.readableFileSize(read),
                        FileUtil.readableFileSize(total),
                        pct >= 0 ? "  (" + pct + "%)" : "");
                lastReportBytes = read;
                lastPct = Math.max(lastPct, pct);
            }
        }
        System.out.printf("  [%s] 完成：%s%n", label, FileUtil.readableFileSize(read));
        return read;
    }

    private static long inspectSize(DockerClient client, String ref) {
        try {
            InspectImageResponse r = client.inspectImageCmd(ref).exec();
            return r.getSize() == null ? 0L : r.getSize();
        } catch (Exception e) {
            return 0L;
        }
    }
}
