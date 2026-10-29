package io.github.jiangood.docker.admin.service.compose;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.openadmin.util.BusinessException;

import java.util.regex.Pattern;

/**
 * compose 项目目录与文件的路径规则。
 * <p>
 * 只接受宿主机上的 POSIX 绝对路径：辅助容器要把项目目录按<b>同一路径</b>挂进去，
 * Windows 路径（{@code C:\...}）在 Linux 容器里没有等价语义，直接拒绝并给出明确提示。
 */
public final class ComposePaths {

    /**
     * 单个文件大小上限：再大就不适合在浏览器里编辑了。
     */
    public static final int MAX_FILE_BYTES = 2 * 1024 * 1024;

    /** compose.yaml / docker-compose.yml / compose.override.yaml 等 */
    public static final Pattern COMPOSE_FILE =
            Pattern.compile("^(docker-)?compose([._-][A-Za-z0-9._-]+)?\\.ya?ml$", Pattern.CASE_INSENSITIVE);

    /** .env / .env.prod 等 */
    public static final Pattern ENV_FILE =
            Pattern.compile("^\\.env([._-][A-Za-z0-9._-]+)?$", Pattern.CASE_INSENSITIVE);

    private ComposePaths() {
    }

    /**
     * 项目目录：宿主机绝对路径，去掉末尾斜杠。
     */
    public static String dir(String dir) {
        String value = StrUtil.trim(dir);
        if (StrUtil.isBlank(value)) {
            throw new BusinessException("请填写项目目录（宿主机上的绝对路径，如 /opt/compose/app）");
        }
        if (value.matches("^[A-Za-z]:.*") || value.contains("\\")) {
            throw new BusinessException("暂不支持 Windows 路径，请填写 Linux 宿主机上的绝对路径");
        }
        if (!value.startsWith("/")) {
            throw new BusinessException("请填写绝对路径，如 /opt/compose/app");
        }
        if (value.contains("..")) {
            throw new BusinessException("路径不能包含 ..");
        }
        if (value.length() > 400) {
            throw new BusinessException("路径过长");
        }
        while (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /**
     * 可编辑文件：绝对路径 + 文件名白名单（compose 文件或 .env）。
     */
    public static String file(String path) {
        String value = dir(StrUtil.trim(path));
        String name = fileName(value);
        if (!COMPOSE_FILE.matcher(name).matches() && !ENV_FILE.matcher(name).matches()) {
            throw new BusinessException("只允许编辑 compose 文件与 .env：" + name);
        }
        return value;
    }

    public static boolean isComposeFile(String name) {
        return COMPOSE_FILE.matcher(StrUtil.nullToEmpty(name)).matches();
    }

    public static boolean isEnvFile(String name) {
        return ENV_FILE.matcher(StrUtil.nullToEmpty(name)).matches();
    }

    /**
     * 主 compose 文件（不带 .override 等后缀的那几个）。
     */
    public static boolean isMainCompose(String name) {
        String lower = StrUtil.nullToEmpty(name).toLowerCase();
        return "compose.yaml".equals(lower) || "compose.yml".equals(lower)
                || "docker-compose.yaml".equals(lower) || "docker-compose.yml".equals(lower);
    }

    public static String parentOf(String path) {
        String value = StrUtil.nullToEmpty(path).replace('\\', '/');
        while (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        int idx = value.lastIndexOf('/');
        if (idx < 0) {
            return value;
        }
        return idx == 0 ? "/" : value.substring(0, idx);
    }

    public static String fileName(String path) {
        String value = StrUtil.nullToEmpty(path).replace('\\', '/');
        while (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        int idx = value.lastIndexOf('/');
        return idx < 0 ? value : value.substring(idx + 1);
    }
}
