package io.github.jiangood.docker.admin.util;

import cn.hutool.core.util.StrUtil;
import org.springframework.util.Assert;

import java.io.File;
import java.io.IOException;

/**
 * 构建上下文工具：把项目上配置的上下文目录（相对代码仓库根目录）归一化为安全的子目录。
 * <p>
 * 空值、{@code /}、{@code .} 都表示仓库根目录；其余为形如 {@code admin} 的相对路径。
 * 这里统一做校验，避免 {@code ..} 等写法把构建上下文指到仓库之外。
 */
public class BuildContextUtils {

    /**
     * 归一化构建上下文：统一分隔符、去掉前导 {@code ./} 与首尾 {@code /}。
     *
     * @return 归一化后的相对路径；仓库根目录返回空字符串
     */
    public static String normalize(String context) {
        String value = StrUtil.trim(context);
        if (StrUtil.isBlank(value)) {
            return "";
        }
        value = value.replace('\\', '/');
        // 去掉重复的前导 "./"
        while (value.startsWith("./")) {
            value = value.substring(2);
        }
        value = StrUtil.strip(value, "/");
        // 折叠重复的分隔符，如 admin//web
        value = value.replaceAll("/{2,}", "/");
        if (StrUtil.isEmpty(value) || ".".equals(value)) {
            return "";
        }

        Assert.isTrue(!value.contains(":"), "构建上下文必须是相对路径，不能包含盘符或协议：" + context);
        for (String segment : value.split("/")) {
            Assert.isTrue(!".".equals(segment) && !"..".equals(segment),
                    "构建上下文不能包含 . 或 .. 路径：" + context);
        }
        return value;
    }

    /**
     * 归一化后用于展示的上下文：仓库根目录显示为 {@code /}。
     */
    public static String display(String context) {
        String normalized = normalize(context);
        return StrUtil.isEmpty(normalized) ? "/" : normalized;
    }

    /**
     * 解析构建上下文目录：{@code workDir} 为代码仓库克隆目录。
     */
    public static File resolve(File workDir, String context) {
        String normalized = normalize(context);
        return StrUtil.isEmpty(normalized) ? workDir : new File(workDir, normalized);
    }

    /**
     * 判断文件是否位于目录内（用于校验 Dockerfile 不做上下文之外的引用）。
     */
    public static boolean isInside(File dir, File file) {
        try {
            return file.getCanonicalFile().toPath().startsWith(dir.getCanonicalFile().toPath());
        } catch (IOException e) {
            return false;
        }
    }
}
