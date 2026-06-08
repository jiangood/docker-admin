package io.github.jiangood.docker.admin.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.api.model.Info;
import com.github.dockerjava.api.model.PruneType;
import io.github.jiangood.docker.admin.dao.HostRepository;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.sdk.engine.DockerClientManager;
import io.github.jiangood.openadmin.framework.data.specification.Spec;
import io.github.jiangood.openadmin.framework.data.BaseService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Slf4j
@Service
public class HostService extends BaseService<Host> {


    private final HostRepository hostRepository;

    public HostService(HostRepository hostRepository) {
        super(hostRepository);
        this.hostRepository = hostRepository;
    }

    @Resource
    DockerClientManager sdkManager;

    /**
     * 获得镜像构建主机
     *
     * @return
     */
    public Host getDefaultDockerRunner() {
        Spec<Host> q = Spec.of();
        q.eq(Host.Fields.isRunner, true);
        return hostRepository.findTop1(q, Sort.by(Sort.Direction.DESC, "updateTime"));
    }

    public Info getDockerInfo(Host host) {

        DockerClient client = sdkManager.getClient(host);
        Info info = client.infoCmd().exec();

        return info;
    }


    public List<Container> getContainers(String id) {
            Host db = hostRepository.findById(id).orElse(null);
            DockerClient client = sdkManager.getClient(db);

            List<Container> list = client.listContainersCmd().withShowAll(true).exec();
            return list;

    }
    public List<Image> getImages(String id) {
        Host db = hostRepository.findById(id).orElse(null);
        DockerClient client = sdkManager.getClient(db);

        List<Image> list = client.listImagesCmd().withShowAll(true).exec();
        return list;
    }

    public void deleteImage(String hostId, String imageId) {
        Host host = hostRepository.findById(hostId).orElse(null);
        DockerClient client = sdkManager.getClient(host);

        client.removeImageCmd(imageId).withForce(true).exec();
    }


    public long count() {

        return hostRepository.count();
    }



    @Async
    public void cleanImage(String hostId) throws IOException {
        Host db = hostRepository.findById(hostId).orElse(null);
        log.info("开始清理主机镜像 {}", db.getName());
        DockerClient client = sdkManager.getClient(db);
        client.pruneCmd(PruneType.IMAGES)
                .withDangling(false) //  无名镜像
                .exec();
        client.close();
        log.info("清理完成");
    }


}
