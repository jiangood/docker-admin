package io.github.jiangood.docker.admin.controller;

import cn.hutool.core.util.StrUtil;
import jakarta.servlet.http.HttpServletResponse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 附件下载响应头工具：统一设置 Content-Type 与 Content-Disposition（支持中文文件名）。
 */
public final class DownloadTool {

    private DownloadTool() {
    }

    /**
     * 设置附件下载响应头。调用方应确保此时尚未写出响应体。
     */
    public static void prepareDownload(HttpServletResponse response, String fileName, String contentType) {
        response.setContentType(contentType);
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
    }

    /**
     * 从路径推断文件名（取最后一段）。
     */
    public static String fileName(String path) {
        if (StrUtil.isBlank(path)) {
            return "download";
        }
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int idx = p.lastIndexOf('/');
        String name = idx >= 0 ? p.substring(idx + 1) : p;
        return StrUtil.blankToDefault(name, "download");
    }
}
