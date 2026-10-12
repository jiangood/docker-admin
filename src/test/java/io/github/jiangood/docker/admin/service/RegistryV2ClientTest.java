package io.github.jiangood.docker.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jiangood.docker.admin.service.registry.RegistryV2Client;
import io.github.jiangood.docker.admin.service.registry.RemoteImageMeta;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

/**
 * 手工验证用（不是 JUnit 测试，mvn test 跑不了，需要 IDE 里单独执行 main）。
 *
 * <p>直接复用本机 docker login 保存的凭据，对真实注册中心跑一遍：
 * <pre>
 *   1. {@code GET /v2/} 探测认证方式
 *   2. {@code GET /v2/_catalog} 看是否开放仓库列举（阿里云个人版不开放）
 *   3. {@code GET /v2/<name>/tags/list} 读版本
 *   4. manifest -&gt; image config，读构建时间/大小/摘要
 * </pre>
 */
public class RegistryV2ClientTest {

    private static final String HOST = "registry.cn-hangzhou.aliyuncs.com";
    private static final String REPO = "ztcn/camera-vision-search";

    public static void main(String[] args) throws Exception {
        String[] credential = readDockerCredential(HOST);
        System.out.println("用户名 = " + credential[0]);

        RegistryV2Client client = new RegistryV2Client(HOST, credential[0], credential[1]);

        System.out.println("------------------------------------------");
        System.out.println("是否开放仓库列举 /v2/_catalog = " + client.supportsCatalog());

        System.out.println("------------------------------------------");
        List<String> tags = client.listTags(REPO, 100, 0);
        System.out.println("仓库 " + REPO + " 的版本（" + tags.size() + " 个）: " + tags);

        System.out.println("------------------------------------------");
        for (String tag : tags) {
            RemoteImageMeta meta = client.fetchMeta(REPO, tag);
            if (meta == null) {
                System.out.println("  " + tag + " -> 元数据为空");
                continue;
            }
            System.out.println("  " + tag
                    + " -> 构建时间=" + meta.buildTime()
                    + ", 大小=" + (meta.size() == null ? "-" : (meta.size() / 1024 / 1024) + " MB")
                    + ", 摘要=" + meta.digest());
        }

        System.out.println("------------------------------------------");
    }

    /**
     * 读本机 docker 的登录凭据（~/.docker/config.json 里的 auths），返回 {用户名, 密码}。
     * 没有登录过就返回空账号，按匿名访问。
     */
    private static String[] readDockerCredential(String host) throws Exception {
        Path config = Path.of(System.getProperty("user.home"), ".docker", "config.json");
        if (!Files.exists(config)) {
            System.out.println("未找到 " + config + "，按匿名访问");
            return new String[]{"", ""};
        }
        JsonNode auths = new ObjectMapper().readTree(config.toFile()).get("auths");
        JsonNode entry = auths == null ? null : auths.get(host);
        if (entry == null || entry.get("auth") == null) {
            System.out.println("本机未登录 " + host + "，按匿名访问");
            return new String[]{"", ""};
        }
        String raw = new String(Base64.getDecoder().decode(entry.get("auth").asText()), StandardCharsets.UTF_8);
        int idx = raw.indexOf(':');
        return new String[]{raw.substring(0, idx), raw.substring(idx + 1)};
    }

}