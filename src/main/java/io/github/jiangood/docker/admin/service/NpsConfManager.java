package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
import io.github.jiangood.docker.admin.entity.TunnelSetting;
import io.github.jiangood.openadmin.util.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * nps.conf 的生成、读写与解析。
 * <p>
 * nps 镜像为 scratch 型（容器内没有 shell），因此配置只能通过 {@code docker cp} 读写，
 * 不能用 exec 改文件。容器启动时若 {@code /conf/nps.conf} 已存在则直接使用，不会覆盖。
 */
@Slf4j
@Service
public class NpsConfManager {

    /**
     * 容器内配置目录与文件路径。
     */
    public static final String CONF_DIR = "/conf";
    public static final String CONF_FILE = "/conf/nps.conf";

    private static final String MASK = "******";

    /**
     * 密钥在 conf 原文里的掩码（对外展示用）。
     */
    public static final String MASK_SECRET = MASK;

    /**
     * 按设置渲染默认 nps.conf。
     */
    public String defaultConf(TunnelSetting s) {
        StringBuilder sb = new StringBuilder();
        sb.append("appname = nps\n");
        sb.append("runmode = release\n");
        sb.append('\n');
        sb.append("# 与 npc 的桥接\n");
        sb.append("bridge_type = tcp\n");
        sb.append("bridge_ip = 0.0.0.0\n");
        sb.append("bridge_port = ").append(s.bridgePort()).append('\n');
        sb.append("tls_enable = true\n");
        sb.append("tls_bridge_port = ").append(s.tlsBridgePort()).append('\n');
        sb.append("disconnect_timeout = 60\n");
        sb.append("log_level = 6\n");
        sb.append("log_path = nps.log\n");
        sb.append("flow_store_interval = 1\n");
        sb.append('\n');
        sb.append("# 域名（HTTP）反向代理\n");
        sb.append("http_proxy_ip = 0.0.0.0\n");
        sb.append("http_proxy_port = ").append(s.httpProxyPort()).append('\n');
        sb.append("# 本期只做 HTTP，留空即关闭 https 监听\n");
        sb.append("https_proxy_port =\n");
        sb.append("show_http_proxy_port = true\n");
        sb.append("http_add_origin_header = true\n");
        sb.append('\n');
        sb.append("# Web 后台\n");
        sb.append("web_host = a.o.com\n");
        sb.append("web_ip = 0.0.0.0\n");
        sb.append("web_port = ").append(s.webPort()).append('\n');
        sb.append("web_username = ").append(s.webUsername()).append('\n');
        sb.append("web_password = ").append(s.getWebPassword()).append('\n');
        sb.append("web_open_ssl = false\n");
        sb.append("open_captcha = false\n");
        sb.append("# 不允许客户端用 vkey 登录 Web 后台\n");
        sb.append("allow_user_login = false\n");
        sb.append('\n');
        sb.append("# Web API 鉴权\n");
        sb.append("auth_key = ").append(s.getAuthKey()).append('\n');
        sb.append("auth_crypt_key = ").append(s.getAuthCryptKey()).append('\n');
        sb.append('\n');
        sb.append("# 只允许把域名指向客户端侧目标\n");
        sb.append("allow_local_proxy = false\n");
        return sb.toString();
    }

    /**
     * 补齐平台生成的密钥（首次生成 conf 前调用）。
     */
    public void ensureSecrets(TunnelSetting s) {
        if (StrUtil.isBlank(s.getAuthKey())) {
            s.setAuthKey(RandomUtil.randomString(24));
        }
        if (StrUtil.isBlank(s.getAuthCryptKey()) || s.getAuthCryptKey().length() != 16) {
            // nps 要求 auth_crypt_key 恰好 16 位，否则 /auth/getauthkey 异常
            s.setAuthCryptKey(RandomUtil.randomString(16));
        }
        if (StrUtil.isBlank(s.getWebPassword())) {
            s.setWebPassword(RandomUtil.randomString(16));
        }
    }

    /**
     * 重置 Web 后台密码与 API 密钥。
     */
    public void resetSecrets(TunnelSetting s) {
        s.setWebPassword(RandomUtil.randomString(16));
        s.setAuthKey(RandomUtil.randomString(24));
        s.setAuthCryptKey(RandomUtil.randomString(16));
    }

    /**
     * conf 内容哈希（前 12 位），用于判断是否需要重建容器。
     */
    public String hash(String conf) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(StrUtil.nullToEmpty(conf).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new BusinessException("计算配置哈希失败：" + e.getMessage(), e);
        }
    }

    /**
     * 把 conf 内容写入容器的 /conf/nps.conf（docker cp）。
     */
    public void writeConf(DockerClient client, String containerId, String conf) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             TarArchiveOutputStream tar = new TarArchiveOutputStream(bos)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            byte[] data = conf.getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry entry = new TarArchiveEntry("nps.conf");
            entry.setSize(data.length);
            tar.putArchiveEntry(entry);
            tar.write(data);
            tar.closeArchiveEntry();
            tar.finish();

            client.copyArchiveToContainerCmd(containerId)
                    .withTarInputStream(new ByteArrayInputStream(bos.toByteArray()))
                    .withRemotePath(CONF_DIR)
                    .exec();
            log.info("已写入 {}", CONF_FILE);
        } catch (Exception e) {
            throw new BusinessException("写入 nps.conf 失败：" + e.getMessage(), e);
        }
    }

    /**
     * 读回容器内的 /conf/nps.conf。
     */
    public String readConf(DockerClient client, String containerId) {
        try (InputStream in = client.copyArchiveFromContainerCmd(containerId, CONF_FILE).exec();
             TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    return new String(tar.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            return null;
        } catch (Exception e) {
            throw new BusinessException("读取 nps.conf 失败：" + e.getMessage(), e);
        }
    }

    /**
     * 解析 nps.conf 的关键项（key = value，忽略注释）。
     */
    public Map<String, String> parse(String conf) {
        Map<String, String> map = new LinkedHashMap<>();
        if (StrUtil.isBlank(conf)) {
            return map;
        }
        for (String raw : conf.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";") || line.startsWith("[")) {
                continue;
            }
            int i = line.indexOf('=');
            if (i <= 0) {
                continue;
            }
            String k = line.substring(0, i).trim();
            String v = line.substring(i + 1).trim();
            map.put(k, v);
        }
        return map;
    }

    /**
     * 用手工编辑过的 conf 原文回填设置项。
     * <p>
     * 数组等无法解析的内容一律忽略；解析不到时保留原值。
     */
    public void syncFromConf(TunnelSetting s, String conf) {
        Map<String, String> map = parse(conf);
        Integer v;
        if ((v = intOf(map.get("http_proxy_port"))) != null) {
            s.setHttpProxyPort(v);
        }
        if ((v = intOf(map.get("bridge_port"))) != null) {
            s.setBridgePort(v);
        }
        if ((v = intOf(map.get("tls_bridge_port"))) != null) {
            s.setTlsBridgePort(v);
        }
        if ((v = intOf(map.get("web_port"))) != null) {
            s.setWebPort(v);
        }
        String key = map.get("auth_key");
        if (StrUtil.isNotBlank(key) && !MASK.equals(key)) {
            s.setAuthKey(key);
        }
        String crypt = map.get("auth_crypt_key");
        if (StrUtil.isNotBlank(crypt) && !MASK.equals(crypt)) {
            s.setAuthCryptKey(crypt);
        }
        String pwd = map.get("web_password");
        if (StrUtil.isNotBlank(pwd) && !MASK.equals(pwd)) {
            s.setWebPassword(pwd);
        }
        String user = map.get("web_username");
        if (StrUtil.isNotBlank(user)) {
            s.setWebUsername(user);
        }
    }

    private static Integer intOf(String v) {
        if (StrUtil.isBlank(v)) {
            return null;
        }
        try {
            int i = Integer.parseInt(v.trim());
            return i > 0 ? i : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 展示用：把 conf 里的密钥替换成掩码，避免通过接口泄露。
     */
    public String maskConf(String conf) {
        if (StrUtil.isBlank(conf)) {
            return conf;
        }
        String r = setKey(conf, "web_password", MASK_SECRET);
        r = setKey(r, "auth_key", MASK_SECRET);
        r = setKey(r, "auth_crypt_key", MASK_SECRET);
        return r;
    }

    /**
     * 保存用：把 conf 里仍是掩码的密钥还原成库里的真实值（用户没改密钥时）。
     */
    public String restoreMasked(String conf, TunnelSetting s) {
        if (StrUtil.isBlank(conf) || s == null) {
            return conf;
        }
        String r = restoreKey(conf, "web_password", s.getWebPassword());
        r = restoreKey(r, "auth_key", s.getAuthKey());
        r = restoreKey(r, "auth_crypt_key", s.getAuthCryptKey());
        return r;
    }

    /**
     * 无条件覆盖某一项的值。
     */
    private static String setKey(String conf, String key, String value) {
        return conf.replaceAll("(?m)^(" + key + "\\s*=\\s*)[^\\r\\n]*$",
                "$1" + Matcher.quoteReplacement(StrUtil.nullToEmpty(value)));
    }

    /**
     * 仅当该行的值等于掩码时才替换，避免覆盖用户新填的值。
     */
    private static String restoreKey(String conf, String key, String value) {
        return conf.replaceAll("(?m)^(" + key + "\\s*=\\s*)" + Pattern.quote(MASK_SECRET) + "\\s*$",
                "$1" + Matcher.quoteReplacement(StrUtil.nullToEmpty(value)));
    }

}
