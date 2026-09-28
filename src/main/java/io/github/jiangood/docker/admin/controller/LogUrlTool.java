package io.github.jiangood.docker.admin.controller;


import io.github.jiangood.openadmin.util.SpringTool;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class LogUrlTool {

    private LogUrlTool() {
    }

    public static String getLogViewUrl(String logger) {
        String root = SpringTool.getProperty("logging.file.path");

        File file = new File(root, logger + ".log");

        String path = URLEncoder.encode(file.getAbsolutePath(), StandardCharsets.UTF_8);

        return "/ws-log-view?path=" + path;
    }
}
