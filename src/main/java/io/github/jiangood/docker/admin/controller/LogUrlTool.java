package io.github.jiangood.docker.admin.controller;




import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

public class LogUrlTool {


    public static  String getLogViewUrl(String logger) throws UnsupportedEncodingException {
        String root = SpringUtils.getProperty("logging.file.path");

        File file = new File(root, logger + ".log");

        String path = file.getAbsolutePath();

        path = URLEncoder.encode(path,"utf-8");

        return "/ws-log-view?path=" + path;
    }
}
