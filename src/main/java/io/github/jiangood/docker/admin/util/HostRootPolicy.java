package io.github.jiangood.docker.admin.util;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.openadmin.util.BusinessException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 主机文件管理目录的约束。
 * <p>
 * 辅助容器只挂载一个目录到容器内 {@code /host}，因此这里负责两件事：
 * <ul>
 *     <li>校验“宿主机目录”只能是 Docker daemon 可见的安全目录，禁止根目录与系统目录；</li>
 *     <li>把界面使用的相对路径转换成 {@code /host/...}，并阻止 {@code ..} 越界。</li>
 * </ul>
 * 只接受 Linux 绝对路径。Windows 主机的 C:/D: 盘需要填写 Docker daemon 可见的
 * Linux 路径（Docker Desktop 通常是 {@code /run/desktop/mnt/host/d/...}，
 * WSL2 原生 docker 是 {@code /mnt/d/...}），界面会给出说明。
 */
public final class HostRootPolicy {

    /**
     * 未配置时默认挂载的宿主机目录。
     */
    public static final String DEFAULT_ROOT = "/home";

    /**
     * 辅助容器内的挂载点。
     */
    public static final String AGENT_MOUNT = "/host";

    /**
     * 系统/危险目录：自身以及其子目录都禁止挂载。
     */
    private static final List<String> DENIED = List.of(
            "/etc", "/root", "/boot", "/proc", "/sys", "/dev", "/run",
            "/usr", "/bin", "/sbin", "/lib", "/lib32", "/lib64",
            "/var/lib/docker", "/var/run", "/mnt", "/host_mnt",
            // /host 是文件管理的内部挂载点，/tmp 是 agent 的 tmpfs，都不能作为数据目录
            "/host", "/tmp"
    );

    /**
     * Docker Desktop / WSL 的盘符视图前缀。这些路径本身是安全的入口，
     * 但盘符根目录和 Windows 系统目录仍然禁止。
     */
    private static final List<String> WINDOWS_DRIVE_PREFIXES = List.of(
            "/run/desktop/mnt/host/", "/host_mnt/", "/mnt/"
    );

    private static final List<String> WINDOWS_DENIED_NAMES = List.of(
            "windows", "program files", "program files (x86)", "programdata",
            "$recycle.bin", "system volume information", "recovery", "boot", "perflogs"
    );

    private HostRootPolicy() {
    }

    /**
     * 归一化宿主机目录：去空白、去重复斜杠与末尾斜杠，空值回落到默认目录。
     */
    public static String normalizeRoot(String raw) {
        String text = StrUtil.trimToEmpty(raw);
        if (text.isEmpty()) {
            return DEFAULT_ROOT;
        }
        if (text.indexOf('\\') >= 0) {
            throw new BusinessException("请填写 Docker daemon 文件系统中的 Linux 绝对路径（以 / 开头），"
                    + "不要填写 C:\\ 或 D:\\ 这类 Windows 路径");
        }
        assertNoControlChars(text);
        if (!text.startsWith("/")) {
            throw new BusinessException("请填写以 / 开头的绝对路径");
        }

        List<String> parts = new ArrayList<>();
        for (String segment : text.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                throw new BusinessException("目录不能包含 ..");
            }
            parts.add(segment);
        }
        String normalized = "/" + String.join("/", parts);
        if ("/".equals(normalized)) {
            throw new BusinessException("不允许挂载根目录 /");
        }
        return normalized;
    }

    /**
     * 校验宿主机目录是否允许作为辅助容器的挂载目录。
     */
    public static void assertValidRoot(String root) {
        String normalized = normalizeRoot(root);
        if (isDenied(normalized)) {
            throw new BusinessException("目录「" + normalized + "」属于系统/危险目录，不允许挂载。"
                    + "请选择 /home、/data 等数据目录；Windows 主机请填写 Docker Desktop/WSL 中可见的 Linux 路径");
        }
    }

    private static boolean isDenied(String root) {
        // Docker Desktop / WSL 的盘符路径优先放行，再单独检查盘符根目录与 Windows 系统目录
        for (String prefix : WINDOWS_DRIVE_PREFIXES) {
            if (root.equals(stripTrailingSlash(prefix))) {
                return true;
            }
            if (root.startsWith(prefix)) {
                return isDeniedWindowsPath(root.substring(prefix.length()));
            }
        }
        for (String denied : DENIED) {
            if (root.equals(denied) || root.startsWith(denied + "/")) {
                return true;
            }
            // 配置的是危险目录的祖先（如 /var、/var/lib）同样拒绝
            if (denied.startsWith(root + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断盘符视图下的子路径是否危险。入参形如 {@code c/data}、{@code c/Windows/System32}。
     */
    private static boolean isDeniedWindowsPath(String rest) {
        if (rest.isEmpty()) {
            return true;
        }
        if (rest.length() == 1) {
            // 盘符根目录，例如 /mnt/c
            return true;
        }
        if (rest.charAt(1) != '/') {
            // 不是盘符路径，例如 /mnt/data
            return false;
        }
        if (!isAsciiLetter(rest.charAt(0))) {
            return false;
        }
        String sub = rest.substring(2);
        if (sub.isEmpty()) {
            return true;
        }
        String lower = sub.toLowerCase(Locale.ROOT);
        for (String name : WINDOWS_DENIED_NAMES) {
            if (lower.equals(name) || lower.startsWith(name + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 归一化界面传来的相对路径：去掉前导/重复斜杠，拒绝 {@code ..} 与反斜杠。
     * 空字符串表示当前挂载目录本身。
     */
    public static String normalizeRelativePath(String path) {
        String text = StrUtil.trimToEmpty(path);
        assertNoControlChars(text);
        if (text.indexOf('\\') >= 0) {
            throw new BusinessException("路径分隔符请使用 /");
        }
        List<String> parts = new ArrayList<>();
        for (String segment : text.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                throw new BusinessException("路径不能包含 ..");
            }
            parts.add(segment);
        }
        return String.join("/", parts);
    }

    /**
     * 校验用户输入的文件/目录名：只能是单层名称，不能带路径分隔符。
     */
    public static String normalizeName(String name) {
        String value = StrUtil.trimToEmpty(name);
        if (value.isEmpty()) {
            throw new BusinessException("名称不能为空");
        }
        assertNoControlChars(value);
        if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0
                || ".".equals(value) || "..".equals(value)) {
            throw new BusinessException("名称不能包含路径分隔符");
        }
        return value;
    }

    /**
     * 拼接相对路径。child 允许带相对子目录（如 {@code conf/app.yml}），
     * 但仍会拒绝 {@code ..} 与反斜杠；上传文件名在调用方已按单层名称校验。
     */
    public static String joinRelative(String dir, String name) {
        String parent = normalizeRelativePath(dir);
        String child = normalizeRelativePath(name);
        if (child.isEmpty()) {
            throw new BusinessException("名称不能为空");
        }
        return parent.isEmpty() ? child : parent + "/" + child;
    }

    /**
     * 相对路径 -> 辅助容器内路径（{@code /host/...}）。
     */
    public static String toAgentPath(String relativePath) {
        String rel = normalizeRelativePath(relativePath);
        return rel.isEmpty() ? AGENT_MOUNT : AGENT_MOUNT + "/" + rel;
    }

    private static void assertNoControlChars(String value) {
        if (value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new BusinessException("路径包含非法字符");
        }
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
}
