package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import io.admin.framework.CodeException;
import io.github.jiangood.base.tool.YamlTool;
import io.github.jiangood.docker.admin.BuildSuccessEvent;
import io.github.jiangood.docker.admin.dao.AppDao;
import io.github.jiangood.docker.admin.dao.DeployLogDao;
import io.github.jiangood.docker.admin.dao.HostDao;
import io.github.jiangood.docker.admin.entity.*;
import io.github.jiangood.docker.admin.dto.ContainerVo;
import io.github.jiangood.docker.config.Config;
import io.github.jiangood.docker.config.Registry;
import io.github.jiangood.docker.sdk.engine.DefaultCallback;
import io.github.jiangood.docker.sdk.engine.DockerClientManager;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.*;
import io.admin.framework.data.service.BaseService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.slf4j.MDC;
import org.springframework.beans.BeanUtils;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;

@Service
@Slf4j
public class AppService extends BaseService<App> {

    Set<String> deployingList = new HashSet<>();

    @Resource
    DeployLogDao deployLogDao;

    @Resource
    DockerClientManager dockerManager;

    @Resource
    private AppDao appDao;

    @Resource
    private HostDao hostDao;


    @Resource
    private Config config;

    @Async
    public void deploy(App app) {
        deployingList.add(app.getId());
        MDC.put("logFileId", app.getId());

        // 修改更新时间
        app.setUpdateTime(new Date());
        appDao.save(app);
        app = appDao.findOne(app.getId()); // 确保关联对象都取出来

        DeployLog deployLog = new DeployLog();
        deployLog.setAppId(app.getId());
        deployLog.setAppName(app.getName());

        deployLog = deployLogDao.save(deployLog);

        try {
            log.info("部署阶段开始");
            String image = null;
            DockerClient client;
            Host host = app.getHost();

            if (app.getProject() != null) {
                // 项目镜像
                image = config.getRegistry().getFullUrl() + "/" + app.getProject().getName() + ":" + app.getImageTag();
                client = dockerManager.getClient(host, config.getRegistry());
            } else {
                // 镜像
                image = app.getImageUrl() + ":" + app.getImageTag();

                Registry registry = config.getRegistry();
                if (registry != null) { // 通过镜像地址倒推 注册中心
                    client = dockerManager.getClient(host, registry);
                } else {
                    client = dockerManager.getClient(host);
                }

            }


            log.info("开始拉取镜像 {}", image);
            client.pullImageCmd(image).exec(new DefaultCallback<>(app.getId())).awaitCompletion();


            log.info("开始部署镜像 {}", image);
            App.AppConfig cfg = app.getConfig();


            List<Container> containers = getContainer(app.getName(), client);


            for (Container container : containers) {
                log.info("容器状态 {}", container.getState());
                if (container.getState().equals("running")) {
                    log.info("停止容器{}", container.getNames());
                    client.stopContainerCmd(container.getId()).exec();
                }
                log.info("删除容器{}", container.getNames());
                client.removeContainerCmd(container.getId()).exec();
            }


            HostConfig hostConfig = new HostConfig();

            // 网络、 端口
            List<ExposedPort> exposedPorts = new ArrayList<>();
            {
                if (StrUtil.isNotBlank(cfg.getNetworkMode())) {
                    hostConfig.withNetworkMode(cfg.getNetworkMode());
                }

                // 仅桥接模式下使用端口映射
                if (StrUtil.isBlank(cfg.getNetworkMode()) || cfg.getNetworkMode().equals("bridge")) {
                    Ports ports = new Ports();

                    if (cfg.getPorts() != null) {
                        for (App.PortBinding p : cfg.getPorts()) {
                            String protocol = p.getProtocol();
                            if (protocol == null) {
                                protocol = "TCP";
                            }
                            Integer privatePort = p.getPrivatePort();
                            Integer publicPort = p.getPublicPort();
                            if (privatePort == null || publicPort == null) {
                                continue;
                            }

                            ExposedPort e = new ExposedPort(privatePort, InternetProtocol.valueOf(protocol));
                            ports.bind(e, Ports.Binding.bindPort(publicPort));

                            exposedPorts.add(e);
                        }
                    }

                    hostConfig.withPortBindings(ports);
                }

            }


            // 文件路径绑定
            List<Bind> binds = new ArrayList<>();

            for (App.BindConfig v : cfg.getBinds()) {
                // /host:/container:ro
                binds.add(new Bind(v.getPublicVolume(), new Volume(v.getPrivateVolume()), AccessMode.rw));
            }
            hostConfig.withBinds(binds);


            // 环境变量
            Map<String, Object> dict = YamlTool.yamlToFlattenedMap(cfg.getEnvironmentYAML());
            List<String> envs = new ArrayList<>();
            for (Map.Entry<String, Object> e : dict.entrySet()) {
                envs.add(e.getKey() + "=" + e.getValue());
            }


            // 是否自动启动
            hostConfig.withRestartPolicy(RestartPolicy.onFailureRestart(5));

            hostConfig.withPrivileged(true);


            // hosts，ip域名映射, 支持两种格式， 1. ip 域名 2.域名:ip
            String hosts = cfg.getExtraHosts();
            if (StrUtil.isNotBlank(hosts)) {
                List<String> list = StrUtil.splitTrim(hosts, " ");
                log.info("hosts={}", list);
                hostConfig.withExtraHosts(list.toArray(new String[list.size()]));
            }


            // cpu，限制

            // 日志限制
            LogConfig logConfig = new LogConfig(LogConfig.LoggingType.DEFAULT, new HashMap<>());
            logConfig.getConfig().put("max-size", "200m");
            hostConfig.withLogConfig(logConfig);


            log.info("主机配置{}", hostConfig.getBinds());
            CreateContainerCmd containerCmd = client.createContainerCmd(image);
            containerCmd
                    .withName(app.getName() + "_1")
                    .withLabels(dockerManager.getAppLabelFilter(app.getName()))
                    .withHostConfig(hostConfig)
                    .withExposedPorts(exposedPorts) // 如果dockerfile中未指定端口，需要在这里指定
                    .withEnv(envs);

            String cmd = app.getConfig().getCmd();
            if (StrUtil.isNotEmpty(cmd)) {
                List<String> cmds = StrUtil.splitTrim(cmd, " ");
                containerCmd.withCmd(cmds);
            }


            CreateContainerResponse response = containerCmd.exec();


            log.info("创建容器{}", response);

            String containerId = response.getId();

            client.startContainerCmd(containerId).exec();


            log.info("启动容器");
            log.info("部署阶段结束");
        } catch (Exception e) {
            log.info("--------------------------------------------------");
            log.info("部署失败:" + e.getClass().getName() + "=>" + e.getMessage());


            StringWriter writer = new StringWriter();
            e.printStackTrace(new PrintWriter(writer));

            String exStr = writer.toString();
            log.info(exStr);


            e.printStackTrace();
            log.info("--------------------------------------------------");
        }
        deployingList.remove(app.getId());

    }

    public void stop(String id) {
        App app = appDao.findOne(id);


        DockerClient client = dockerManager.getClient(app.getHost());

        List<Container> list = getContainer(app.getName(), client);

        for (Container container : list) {
            client.stopContainerCmd(container.getId()).exec();
        }
    }


    public void start(String id) {
        App app = appDao.findOne(id);

        DockerClient client = dockerManager.getClient(app.getHost());

        List<Container> list = getContainer(app.getName(), client);

        for (Container container : list) {
            client.startContainerCmd(container.getId()).exec();
        }
    }

    public App rename(String appId, String newName) {
        App app = appDao.findOne(appId);
        Assert.notNull(app, "app不存在");
        //判断名字是否相同进行部署
        if (newName.equals(app.getName())) {
            return app;
        }
        this.deleteContainer(app);
        app.setName(newName);
        App saved = appDao.save(app);

        this.deploy(app);

        return saved;
    }

    private List<Container> getContainer(String name, DockerClient client) {
        Map<String, String> labels = dockerManager.getAppLabelFilter(name);
        List<Container> list = client.listContainersCmd()
                .withLabelFilter(labels)
                .withShowAll(true).exec();
        return list;
    }


    public ContainerVo getContainerVo(App app) {
        Container container = getContainer(app);
        ContainerVo data = new ContainerVo(container);

        if (deployingList.contains(app.getId())) {
            data.setState("deploying");
            data.setStatus("部署中...");
        }


        return data;
    }

    public Container getContainer(App app) {
        DockerClient client = dockerManager.getClient(app.getHost());

        String name = app.getName();

        Map<String, String> labels = dockerManager.getAppLabelFilter(name);
        try {


            List<Container> list = client.listContainersCmd().withLabelFilter(labels).withShowAll(true).exec();
            if (!list.isEmpty()) {
                return list.get(0);
            }
        } catch (Exception e) {
            throw new CodeException("查询容器状态失败", e);
        } finally {
            IOUtils.closeQuietly(client);
        }
        return null;
    }

    @Transactional
    public void deleteApp(String id) {
        // 远程删除应用
        App app = appDao.findOne(id);
        deleteContainer(app);

        appDao.deleteById(id);
    }


    public void updateAppVersion(String id, String tag) {
        Assert.hasLength(tag, "tag不能为空");
        // 远程删除应用
        App app = appDao.findOne(id);
        app.setImageTag(tag);
        appDao.save(app);

        SpringUtil.getBean(getClass()).deploy(app);
    }

    private void deleteContainer(App app) {
        DockerClient client = dockerManager.getClient(app.getHost());


        Map<String, String> labels = dockerManager.getAppLabelFilter(app.getName());
        List<Container> list = client.listContainersCmd().withLabelFilter(labels).withShowAll(true).exec();
        log.info("已有容器个数 {}", list.size());

        list.forEach(c -> {
            if (c.getState().equals("running")) {
                log.info("正在停止容器 {}", c);
                client.stopContainerCmd(c.getId()).exec();
            }
            log.info("正在删除容器 {}", c);
            client.removeContainerCmd(c.getId()).exec();
        });
    }

    public App updateConfig(String id, App.AppConfig appConfig) {
        App app = appDao.findOne(id);
        app.setConfig(appConfig);

        app = appDao.save(app);
        return app;
    }

    @EventListener
    public void onBuildSuccess(BuildSuccessEvent event) {
        log.info("构建成功，开始检测关联应用");
        BuildLog buildLog = event.getBuildLog();

        // 自动部署
        List<App> list = appDao.findAll();


        // 让注解生效
        AppService $this = SpringUtil.getBean(getClass());

        for (App app : list) {
            boolean auto = app.getAutoDeploy() != null && app.getAutoDeploy();
            if (!auto) {
                continue;
            }
            String imageUrl = app.getImageUrl();
            boolean imageOk = buildLog.getImageUrl().equals(imageUrl);
            boolean projectOk = false;
            String projectId = buildLog.getProjectId();
            if (projectId != null && app.getProject() != null && StrUtil.equals(projectId, app.getProject().getId())) {
                projectOk = true;
            }
            if (imageOk || projectOk) {
                app.setImageTag(event.getVersion());
                $this.deploy(app);
            }
        }
    }

    /**
     * 只修改简单信息， 其他信息设计到重新部署，如修改主机，比较复杂
     *
     * @param input
     */
    @Transactional
    public void updateBaseInfo(App input) {
        if (input.getSysOrg().getId() == null) {
            input.setSysOrg(null);
        }

        App old = appDao.findOne(input.getId());
        old.setSysOrg(input.getSysOrg());
        old.setImageUrl(input.getImageUrl());
        old.setImageTag(input.getImageTag());
        appDao.save(old);
    }


    public App copyApp(String appId, String hostId) {
        App app = appDao.findOne(appId);
        Host host = hostDao.findOne(hostId);

        App newApp = new App();
        BeanUtils.copyProperties(app, newApp, "id","name","host");
        newApp.setName(app.getName() + "_copy");
        newApp.setHost(host);



        appDao.save(newApp);

        return newApp;
    }
}
