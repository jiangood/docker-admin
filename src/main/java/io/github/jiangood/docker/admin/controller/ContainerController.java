package io.github.jiangood.docker.admin.controller;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.model.Container;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.service.HostService;
import io.github.jiangood.docker.sdk.engine.DockerClientManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@Slf4j
@RequestMapping(value = "admin/container")
public class ContainerController {

    @Resource
    private  HostService hostService;

    @Resource
    private DockerClientManager dockerClientManager;




    @RequestMapping("status")
    public AjaxResult status(String hostId, String appName /*即将弃用*/, String containerId) {
        log.info("查询容器状态:{}", appName);
        try {
            Host host = hostService.findOne(hostId);


            DockerClient cli = dockerClientManager.getClient(host);

            if(containerId != null){
                InspectContainerResponse res = cli.inspectContainerCmd(containerId).exec();

                return AjaxResult.ok().data(res.getState().getStatus());
            }


            ListContainersCmd cmd = cli.listContainersCmd();

            if(appName!= null){
                Map<String, String> appLabelFilter = dockerClientManager.getAppLabelFilter(appName);
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












}
