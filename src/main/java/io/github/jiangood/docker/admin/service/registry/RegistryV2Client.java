package io.github.jiangood.docker.admin.service.registry;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jiangood.openadmin.util.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;

import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 标准 Docker Registry HTTP API V2 客户端。
 * <p>
 * 只依赖 v2 协议本身，不需要任何云厂商 SDK，实测可用于：阿里云 ACR 个人版
 * （{@code registry.cn-<region>.aliyuncs.com}）、Harbor、以及绝大多数自建 registry。
 * <p>
 * <b>能力边界：</b>各 registry 对 {@code /v2/_catalog}（列举仓库）的开放程度不同。
 * 阿里云个人版不开放该接口，认证后仍返回 401，此时只有「按仓库地址逐个拉版本」这条路，
 * 见 {@link #supportsCatalog()}。
 * <p>
 * 本类非线程安全：令牌缓存是普通 HashMap，请每个仓库单独 new 一个实例。
 */
@Slf4j
public class RegistryV2Client {

    private static final String SCOPE_CATALOG = "registry:catalog:*";

    private static final String ACCEPT_MANIFEST = String.join(",",
            "application/vnd.oci.image.index.v1+json",
            "application/vnd.docker.distribution.manifest.list.v2+json",
            "application/vnd.oci.image.manifest.v1+json",
            "application/vnd.docker.distribution.manifest.v2+json");

    private final String base;

    /** base64(username:password)，未填账号时为 null（匿名访问）。 */
    private final String basicAuth;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // blob 接口会 307 重定向到对象存储的预签名地址（JDK 默认 Redirect.NEVER，不跟随就读不到内容）
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 令牌服务地址，形如 https://dockerauth.cn-hangzhou.aliyuncs.com/auth。 */
    private String realm;

    /** 令牌服务名，形如 registry.aliyuncs.com:cn-hangzhou:26842（注意带端口）。 */
    private String service;

    /** scope -> token。 */
    private final Map<String, String> tokens = new HashMap<>();

    /** /v2/ 首次探测结果：TRUE=匿名可读，FALSE=需要令牌，null=尚未探测。 */
    private Boolean anonymous;

    public RegistryV2Client(String url, String username, String password) {
        Assert.hasText(url, "注册中心地址不能为空");
        String v = url.trim();
        if (!v.contains("://")) {
            v = "https://" + v;
        }
        this.base = StrUtil.removeSuffix(v, "/");
        this.basicAuth = StrUtil.isBlank(username) ? null
                : Base64.getEncoder().encodeToString(
                (username + ":" + StrUtil.nullToEmpty(password)).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ 仓库

    /**
     * 注册中心是否开放仓库列举（{@code /v2/_catalog}）。
     * <p>
     * 阿里云 ACR 个人版返回 false，此时请改用手工输入镜像地址的方式。
     */
    public boolean supportsCatalog() {
        try {
            listRepos(1, 1);
            return true;
        } catch (BusinessException e) {
            log.info("注册中心 {} 未开放仓库列举接口：{}", base, e.getMessage());
            return false;
        }
    }

    /**
     * 列举全部仓库（相对路径，如 {@code ztcn/nginx}）。
     */
    public List<String> listRepos(int pageSize, int max) {
        int size = pageSize > 0 ? pageSize : 100;
        List<String> out = new ArrayList<>();
        String last = null;
        while (max <= 0 || out.size() < max) {
            StringBuilder path = new StringBuilder("/v2/_catalog?n=").append(size);
            if (StrUtil.isNotBlank(last)) {
                path.append("&last=").append(enc(last));
            }
            JsonNode node = getJson(path.toString(), SCOPE_CATALOG, "application/json", "列举仓库");
            JsonNode arr = node == null ? null : node.get("repositories");
            if (arr == null || !arr.isArray() || arr.isEmpty()) {
                break;
            }
            for (JsonNode n : arr) {
                String name = n.asText();
                if (StrUtil.isNotBlank(name)) {
                    out.add(name);
                }
            }
            if (arr.size() < size) {
                break;
            }
            last = arr.get(arr.size() - 1).asText();
        }
        return out;
    }

    // ------------------------------------------------------------------ 版本

    /**
     * 列举仓库下的全部 tag。
     */
    public List<String> listTags(String repo, int pageSize, int max) {
        Assert.hasText(repo, "仓库名不能为空");
        int size = pageSize > 0 ? pageSize : 100;
        String scope = "repository:" + repo + ":pull";
        String prefix = "/v2/" + encPath(repo) + "/tags/list?n=" + size;

        List<String> out = new ArrayList<>();
        String last = null;
        while (max <= 0 || out.size() < max) {
            String path = last == null ? prefix : prefix + "&last=" + enc(last);
            JsonNode node = getJson(path, scope, "application/json", "读取版本列表");
            JsonNode arr = node == null ? null : node.get("tags");
            if (arr == null || !arr.isArray() || arr.isEmpty()) {
                break;
            }
            for (JsonNode n : arr) {
                String tag = n.asText();
                if (StrUtil.isNotBlank(tag)) {
                    out.add(tag);
                }
            }
            if (arr.size() < size) {
                break;
            }
            last = arr.get(arr.size() - 1).asText();
        }
        return out;
    }

    /**
     * 读取镜像元数据：manifest -> image config。
     * <p>
     * 每个版本 2 次请求；多架构镜像（manifest list / OCI index）会先选 amd64/linux 的子清单，
     * 因此可能是 3 次。单版本失败返回 null，不影响其余版本。
     */
    public RemoteImageMeta fetchMeta(String repo, String tag) {
        Assert.hasText(repo, "仓库名不能为空");
        Assert.hasText(tag, "版本不能为空");
        String scope = "repository:" + repo + ":pull";
        String prefix = "/v2/" + encPath(repo);

        JsonNode manifest = getJson(prefix + "/manifests/" + enc(tag), scope, ACCEPT_MANIFEST, "读取镜像清单");
        if (manifest == null) {
            return null;
        }
        JsonNode list = manifest.get("manifests");
        if (list != null && list.isArray() && !list.isEmpty()) {
            String digest = pickPlatformDigest(list);
            if (StrUtil.isBlank(digest)) {
                return null;
            }
            manifest = getJson(prefix + "/manifests/" + enc(digest), scope, ACCEPT_MANIFEST, "读取子镜像清单");
            if (manifest == null) {
                return null;
            }
        }

        Long size = totalSize(manifest);
        JsonNode config = manifest.get("config");
        String configDigest = config == null ? null : text(config, "digest");
        if (StrUtil.isBlank(configDigest)) {
            return new RemoteImageMeta(null, null, size, null, null);
        }

        JsonNode blob = getJson(prefix + "/blobs/" + enc(configDigest), scope, "application/json", "读取镜像配置");
        LocalDateTime created = blob == null ? null : parseTime(text(blob, "created"));
        // 声明的端口与卷也在同一份 image config 里，顺手一起读出来，供详情页展示
        JsonNode configNode = blob == null ? null : blob.get("config");
        return new RemoteImageMeta(created, configDigest, size,
                fieldNames(configNode, "ExposedPorts"), fieldNames(configNode, "Volumes"));
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 发起一次带鉴权的 GET，401 时清掉令牌缓存重试一次。
     *
     * @param failHint 失败时给用户看的中文提示前缀
     */
    private JsonNode getJson(String path, String scope, String accept, String failHint) {
        HttpResponse<String> r = send(base + path, tokenOf(scope), accept);
        if (r.statusCode() == 401) {
            // 令牌过期或 scope 变化，清缓存后重试一次
            tokens.remove(scope);
            r = send(base + path, tokenOf(scope), accept);
        }
        int code = r.statusCode();
        if (code == 401 || code == 403) {
            // 阿里云个人版对「仓库不存在」也返回 401（而非 404），所以三种可能都要提示到
            throw new BusinessException(failHint + "失败：账号密码错误、无访问权限，或镜像地址不存在（" + base + "）");
        }
        if (code == 404) {
            throw new BusinessException(failHint + "失败：镜像仓库或版本不存在（" + base + "）");
        }
        if (code < 200 || code >= 300) {
            throw new BusinessException(failHint + "失败：注册中心返回 " + code + "，" + errorText(r.body()));
        }
        if (StrUtil.isBlank(r.body())) {
            return null;
        }
        try {
            return objectMapper.readTree(r.body());
        } catch (Exception e) {
            throw new BusinessException(failHint + "失败：解析返回内容出错，" + e.getMessage(), e);
        }
    }

    private HttpResponse<String> send(String url, String bearer, String accept) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET();
        if (accept != null) {
            builder.header("Accept", accept);
        }
        if (StrUtil.isNotBlank(bearer)) {
            builder.header("Authorization", "Bearer " + bearer);
        } else if (basicAuth != null) {
            builder.header("Authorization", "Basic " + basicAuth);
        }
        try {
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("访问注册中心被中断：" + base);
        } catch (UnknownHostException e) {
            throw new BusinessException("注册中心域名解析失败：" + base + "，请检查地址是否正确");
        } catch (BusinessException e) {
            throw e;
        } catch (ConnectException e) {
            throw new BusinessException("无法连接注册中心：" + base + "，请检查网络与地址");
        } catch (Exception e) {
            throw new BusinessException("访问注册中心失败：" + StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()), e);
        }
    }

    /**
     * 取指定 scope 的 Bearer 令牌。匿名可读时返回 null（请求不带 Authorization）。
     */
    private String tokenOf(String scope) {
        if (anonymous == null) {
            discover();
        }
        if (Boolean.TRUE.equals(anonymous)) {
            return null;
        }
        String cached = tokens.get(scope);
        if (cached != null) {
            return cached;
        }
        if (StrUtil.isBlank(realm)) {
            // 非 Bearer 认证的注册中心，直接用 Basic 访问
            return null;
        }

        StringBuilder url = new StringBuilder(realm).append("?service=").append(enc(service));
        if (StrUtil.isNotBlank(scope)) {
            url.append("&scope=").append(enc(scope));
        }
        HttpResponse<String> r = send(url.toString(), null, "application/json");
        int code = r.statusCode();
        if (code == 401 || code == 403) {
            throw new BusinessException("获取访问令牌失败：账号密码不正确（" + realm + "）");
        }
        if (code < 200 || code >= 300) {
            throw new BusinessException("获取访问令牌失败：返回 " + code + "，" + errorText(r.body()));
        }
        try {
            JsonNode node = objectMapper.readTree(r.body());
            String token = text(node, "token");
            if (StrUtil.isBlank(token)) {
                token = text(node, "access_token");
            }
            if (StrUtil.isBlank(token)) {
                throw new BusinessException("获取访问令牌失败：返回内容中没有 token 字段");
            }
            tokens.put(scope, token);
            return token;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("获取访问令牌失败：解析返回内容出错，" + e.getMessage(), e);
        }
    }

    /**
     * 探测 {@code /v2/}，确定认证方式：匿名可读 / Bearer / Basic。
     */
    private void discover() {
        HttpResponse<String> r = send(base + "/v2/", null, "application/json");
        int code = r.statusCode();
        if (code == 200) {
            anonymous = Boolean.TRUE;
            return;
        }
        if (code != 401) {
            throw new BusinessException(base + " 不是标准的 Docker Registry V2 服务（/v2/ 返回 " + code + "）");
        }
        String header = r.headers().firstValue("WWW-Authenticate").orElse("");
        this.realm = attr(header, "realm");
        this.service = attr(header, "service");
        // 没有 realm 说明不是 Bearer 认证，交给 Basic 直连
        this.anonymous = StrUtil.isBlank(realm) ? null : Boolean.FALSE;
    }

    /**
     * 取 JSON 对象里某个子对象的全部字段名（如 image config 的 config.ExposedPorts 的端口、config.Volumes 的路径），
     * 排序后返回；节点缺失或不是对象时返回空列表。
     */
    private static List<String> fieldNames(JsonNode node, String field) {
        JsonNode obj = node == null ? null : node.get(field);
        if (obj == null || !obj.isObject() || obj.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        obj.fieldNames().forEachRemaining(out::add);
        out.sort(Comparator.naturalOrder());
        return out;
    }

    /**
     * 多架构清单里挑 amd64/linux 的子清单摘要，挑不到就用第一个。
     */
    private static String pickPlatformDigest(JsonNode manifests) {
        for (JsonNode m : manifests) {
            JsonNode p = m.get("platform");
            if (p != null && "amd64".equals(text(p, "architecture")) && "linux".equals(text(p, "os"))) {
                return text(m, "digest");
            }
        }
        return text(manifests.get(0), "digest");
    }

    /**
     * 镜像压缩后总大小：各层大小 + 配置文件大小。
     */
    private static Long totalSize(JsonNode manifest) {
        long sum = 0;
        JsonNode layers = manifest.get("layers");
        if (layers != null && layers.isArray()) {
            for (JsonNode layer : layers) {
                JsonNode size = layer.get("size");
                if (size != null && size.isNumber()) {
                    sum += size.asLong();
                }
            }
        }
        JsonNode config = manifest.get("config");
        if (config != null) {
            JsonNode size = config.get("size");
            if (size != null && size.isNumber()) {
                sum += size.asLong();
            }
        }
        return sum == 0 ? null : sum;
    }

    /**
     * 解析镜像构建时间。OCI/Docker 配置里是 RFC3339（如 {@code 2026-10-10T01:35:06.803968147Z}），
     * 部分注册中心接口返回毫秒时间戳字符串，两种都兜住，都不识别时返回 null。
     */
    private static LocalDateTime parseTime(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        String v = value.trim();
        try {
            return OffsetDateTime.parse(v).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // 不是 RFC3339，继续按时间戳试
        }
        if (v.chars().allMatch(Character::isDigit)) {
            try {
                return LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(v)), ZoneId.systemDefault());
            } catch (NumberFormatException ignored) {
                // 也不是时间戳
            }
        }
        return null;
    }

    /**
     * 从 WWW-Authenticate 里取属性值，形如 {@code Bearer realm="https://x/auth",service="y"}。
     */
    private static String attr(String header, String name) {
        if (StrUtil.isBlank(header)) {
            return null;
        }
        String needle = name.toLowerCase(Locale.ROOT) + "=\"";
        int idx = header.toLowerCase(Locale.ROOT).indexOf(needle);
        if (idx < 0) {
            return null;
        }
        int start = idx + needle.length();
        int end = header.indexOf('"', start);
        return end < 0 ? null : header.substring(start, end);
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /**
     * registry v2 的错误体是 {@code {"errors":[{"code":..,"message":..}]}}，解析出来给用户看。
     */
    private String errorText(String body) {
        if (StrUtil.isBlank(body)) {
            return "无返回内容";
        }
        try {
            JsonNode errors = objectMapper.readTree(body).get("errors");
            if (errors != null && errors.isArray() && !errors.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode e : errors) {
                    String code = text(e, "code");
                    String message = text(e, "message");
                    if (sb.length() > 0) {
                        sb.append("；");
                    }
                    if (StrUtil.isNotBlank(code)) {
                        sb.append(code);
                    }
                    if (StrUtil.isNotBlank(message)) {
                        sb.append(' ').append(message);
                    }
                }
                if (sb.length() > 0) {
                    return sb.toString();
                }
            }
        } catch (Exception ignored) {
            // 非 JSON 错误体，退回原文
        }
        return StrUtil.maxLength(body, 200);
    }

    /** 查询参数编码：空格编成 %20 而不是 +。 */
    private static String enc(String value) {
        return URLEncoder.encode(StrUtil.nullToEmpty(value), StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** 路径编码：保留 {@code /} 分隔符，逐段编码。 */
    private static String encPath(String path) {
        StringBuilder sb = new StringBuilder();
        for (String segment : StrUtil.split(path, '/')) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(enc(segment));
        }
        return sb.toString();
    }

}