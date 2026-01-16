package io.github.jiangood.docker.admin;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.PruneResponse;
import com.github.dockerjava.api.model.PruneType;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.service.HostService;
import io.github.jiangood.docker.sdk.engine.DockerClientManager;
import io.github.jiangood.as.modules.job.BaseJob;
import jakarta.annotation.Resource;
import org.quartz.JobDataMap;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CleanImageJob extends BaseJob {


    @Resource
    HostService hostService;

    @Resource
    DockerClientManager sdkManager;

    @Override
    public String execute(JobDataMap data, Logger log) throws Exception {
        log.info("开始清理资源");

        List<Host> list = hostService.findAll();

        for (Host host : list) {

            try {
                log.info("清理服务器 {}", host.getName());

                DockerClient client = sdkManager.getClient(host);


                PruneResponse response = client.pruneCmd(PruneType.IMAGES)
                        .withDangling(false) //  无名镜像
                        .withUntilFilter( (30 * 24) + "h") // 超过x天
                        .exec();
                log.info(" raw values {}", response.getRawValues());
                log.info("{} 回收空间  {}", host.getName(), response.getSpaceReclaimed());

                client.close();

            } catch (Exception e) {
                log.info("清理失败  " + e.getMessage(), e);
            }

        }
        return "OK";
    }




}
