package io.github.jiangood.docker.admin.controller;

import cn.hutool.core.date.DateField;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.io.unit.DataSizeUtil;
import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.ListContainersCmd;
import io.admin.common.utils.ResponseUtils;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.service.AppService;
import io.github.jiangood.docker.admin.service.HostService;
import io.github.jiangood.docker.sdk.engine.DockerClientManager;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.core.async.ResultCallbackTemplate;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.github.dockerjava.core.command.LogContainerResultCallback;
import io.admin.common.dto.AjaxResult;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;

@RestController
@Slf4j
@RequestMapping(value = "admin/container")
public class ContainerController {

    @Resource
    private  HostService hostService;

    @Resource
    private DockerClientManager sdk;


    @Resource
    private AppService appService;





    @RequestMapping("downloadLog")
    public void downloadLog(String hostId, String containerId, HttpServletResponse response) throws Exception {
        Host host = hostService.findByRequest(hostId);

        DockerClient client = sdk.getClient(host);


        ResponseUtils.setDownloadHeader(containerId + ".log", null, response);

        OutputStream out = response.getOutputStream();
        client.logContainerCmd(containerId)
                .withStdOut(true)
                .withStdErr(true)
                .withTimestamps(true)
                .withFollowStream(false)
                .exec(new LogContainerResultCallback() {
                    @Override
                    public void onNext(Frame item) {
                        byte[] payload = item.getPayload();
                        try {
                            out.write(payload);
                            out.flush();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }).awaitCompletion();

        out.close();
        client.close();


    }






    @RequestMapping("status")
    public AjaxResult status(String hostId, String appName /*即将弃用*/, String containerId, String cont) {
        log.info("查询容器状态:{}", appName);
        try {
            Host host = hostService.findOne(hostId);


            DockerClient cli = sdk.getClient(host);

            if(containerId != null){
                InspectContainerResponse res = cli.inspectContainerCmd(containerId).exec();

                return AjaxResult.ok().data(res.getState().getStatus());
            }


            ListContainersCmd cmd = cli.listContainersCmd();

            if(appName!= null){
                Map<String, String> appLabelFilter = sdk.getAppLabelFilter(appName);
                cmd.withLabelFilter(appLabelFilter);
            }




            List<Container> list = cmd.withShowAll(true).exec();
            cli.close();
            if (list.isEmpty()) {
                return AjaxResult.ok().data("未知");
            }

            Container container = list.get(0);




            return AjaxResult.ok().data(container.getStatus());
        } catch (Exception e) {
            return AjaxResult.ok().data("未知");
        }

    }


    @RequestMapping("file")
    public AjaxResult file(String hostId, String containerId, @RequestParam(defaultValue = "/", required = false) String path) throws Exception {
        Host host = hostService.findOne(hostId);

        DockerClient client = sdk.getClient(host);

        // --time-style=long-iso 参数不一定每个容器都支持
        String cmd = "ls -lt " + path;

        ExecCreateCmdResponse response = client.execCreateCmd(containerId)
                .withCmd("sh", "-c", cmd).withAttachStdout(true).exec();
        String execId = response.getId();
        log.info("执行容器命令 {}", cmd);

        List<FileVo> dirs = new ArrayList<>();
        List<FileVo> files = new ArrayList<>();


        System.out.println("路径 {}" + path);

        StringBuilder sb = new StringBuilder();
        client.execStartCmd(execId).exec(new ResultCallbackTemplate<ExecStartResultCallback, Frame>() {
            @Override
            public void onNext(Frame frame) {
                String str = new String(frame.getPayload());
                System.out.println(str);
                if (frame.getStreamType() != StreamType.STDOUT) {
                    return;
                }

                sb.append(new String(frame.getPayload()));
            }
        }).awaitCompletion();
        client.close();

        List<String> rs = StrUtil.splitTrim(sb.toString(), "\n");
        if (rs.size() > 0) {
            rs.remove(0);
        }


        for (String line : rs) {
            System.out.println(line);

            String[] parts = line.split("\\s+");


            String name = parts[parts.length - 1];
            String fullPath = path + (path.endsWith("/") ? "" : "/") + name;


            FileVo item = new FileVo();
            item.setDir(parts[0].startsWith("d"));
            item.setTitle(name);
            item.setKey(fullPath.replaceAll("/", "_"));
            item.setPath(fullPath);

            // 设备号
            boolean hasDeviceNo = parts[4].endsWith(",");

            if(hasDeviceNo){
                item.size = Long.parseLong(parts[5]);
                item.time = parseLinuxDate( parts[6] , parts[7] , parts[8]);
            }else {
                item.size = Long.parseLong(parts[4]);
                item.time = parseLinuxDate( parts[5] , parts[6] , parts[7]);
            }



            item.sizeFmt = DataSizeUtil.format(item.size);
            boolean dir = item.isDir();
            if (dir) {
                dirs.add(item);
            } else {
                files.add(item);
            }
        }
        Map<String, List<FileVo>> data = new HashMap<>();
        data.put("dirs", dirs);
        data.put("files", files);

        return AjaxResult.ok().msg("获取文件列表成功").data(data);
    }


    @Data
    public static class FileVo {
        boolean dir;
        String key;
        String title;

        String path;
        String time;

        long size;
        String sizeFmt;
    }

    @RequestMapping("downloadFile")
    public void downloadFile(String hostId, String containerId, String file, HttpServletResponse response) throws Exception {
        log.info("进入下载文件");
        Host host = hostService.findOne(hostId);

        DockerClient client = sdk.getClient(host);

        InputStream is = client.copyArchiveFromContainerCmd(containerId, file).exec();


        ResponseUtils.setDownloadHeader(FilenameUtils.getName(file) + ".tar", null, response);

        IoUtil.copy(is, response.getOutputStream());
        is.close();
        client.close();
        log.info("文件下载结束");
    }


    /**
     * Apr 10 06:30
     * Jan  9  2018
     *
     * @return
     */
    private String parseLinuxDate(String month, String day, String yearOrTime) {
        StringBuilder sb = new StringBuilder();

        boolean isYear = yearOrTime.length() == 4;
        if(isYear){
            sb.append(yearOrTime).append("-");
        }



        DateTime monthDate = DateUtil.parse(month, "MMM", Locale.US);
        int monthInt = monthDate.getField(DateField.MONTH) + 1;
        sb.append(monthInt).append("月").append(day).append("日");

        if(!isYear){
            sb.append(" ").append(yearOrTime);
        }


        return sb.toString();
    }

}
