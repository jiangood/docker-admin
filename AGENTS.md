# docker-admin

Multi-host container management platform with CI/CD. UI is Chinese.

## Stack

- **Backend:** Spring Boot 4.1.0 + Java 21, Maven → `target/app.jar`（`<finalName>app</finalName>`）
- **Frontend:** Vite 8 + React 19 + Ant Design 6 + TypeScript 7
- **Base framework:** `io.github.jiangood:open-admin` 3.1.13（后端 Maven）/ `@jiangood/open-admin` 3.1.13（前端 npm），提供 CRUD、鉴权、菜单
- **Docker SDK:** docker-java 3.7.1（TCP 传输 httpclient5）
- **SSH 远程 docker:** `com.github.mwiede.dockerjava:docker-java-transport-jsch` 1.4
- **数据库:** 默认内置 H2（文件模式，持久化到 `./data`），也支持外部 MySQL
- **Entrypoint:** `io.github.jiangood.docker.DockerAdminBootApplication`（`src/main/java/.../DockerAdminBootApplication.java`）

## Commands

```sh
# Build
mvn clean package -DskipTests           # backend only, output target/app.jar
cd web && npm install && npm run build   # frontend only, output web/dist/

# Dev
cd web && npm run dev                    # frontend dev server on :8600, proxies to :8601

# Release（推 tag 即发版）
git add pom.xml web/package.json && git commit -m "chore(release): x.y.z"
git tag vx.y.z && git push && git push origin vx.y.z
```

## Releases

发版只有三步，**推 `v*` tag 就算发完**，其余交给 CI：

1. 同步改两处版本号：`pom.xml` 的 `<version>`、`web/package.json` 的 `"version"`。
   （`web/package-lock.json` 根包版本历来不跟随，保持不动。）
2. 提交 `chore(release): x.y.z`，只含上面两个文件。
3. 打 `v*` tag 并推送。

- **推完 tag 不要再手动验证发布结果**：不用去查 ghcr.io / 阿里云的 manifest 或镜像 tag，
  `.github/workflows/publish-docker-image.yml` 会自动构建镜像、推 ghcr.io 与阿里云
  （`:latest` + `:vx.y.z` 双 tag），并创建正文自动生成的 GitHub Release。
  想确认状态就 `gh run list` 看那条 workflow。
- 版本号不严格按 semver 递增，跟随仓库历史习惯（4.7.1 → 4.7.2）即可。
- 本地 `gh` token 缺 `read:packages` scope，`gh api user/packages/...` 会 403，
  这与发布无关，不要误判成发布失败。
- 推 GitHub 偶发 443 超时，重试 `git push` 即可（tag 已在本地建好时只重推 `origin`）。

## Key facts

- **端口：** 后端 `8601`（`application.yml`；`Dockerfile` 内置 `ENV SERVER_PORT=8601`，本地开发与容器同一端口），
  前端 dev `8600`（`web/.env`）。`docker run` 无需再传 `-e SERVER_PORT`。
- **Context path:** 默认 `/docker-admin`（`application.yml`，仅本地开发用）；
  容器部署由 `Dockerfile` 内置的 `ENV SERVER_SERVLET_CONTEXT_PATH=/` 覆盖为根路径。
  环境变量优先级高于 `application.yml`，因此 `docker run` 与 `docker compose` 行为一致，
  且可用 `-e SERVER_SERVLET_CONTEXT_PATH=/docker-admin` 覆盖回带前缀的路径。
  **已无 `prod` profile，也没有 `application-<profile>.yml` 覆盖文件。**
  - 本地开发必须用 `VITE_SERVER_SERVLET_CONTEXT_PATH=/docker-admin`（见 `web/.env.development`），
    否则 Vite 代理键为 `/` 会把前端静态资源请求也转发到后端。
- **Profiles:** 只用默认 `default`。
  compose 编排见仓库根目录 `docker-compose.yml`。
- **依赖版本集中管理在 `pom.xml` 的 `<properties>` + `<dependencyManagement>`**，不要在 `<dependencies>` 里硬编码版本。
  - `hutool.version` 必须与 open-admin 传递引入的 hutool 各模块同版本，改动前用
    `mvn dependency:tree -Dincludes=cn.hutool` 核对。
  - `docker-java.version` 核心与传输层共用同一属性，保持同版本。
  - lombok **不显式指定版本**，由 `spring-boot-starter-parent` 管理（当前 1.18.46）。
- **数据库配置**走自定义属性（`db_ip`、`db_port`、`db_database`、`db_username`、`db_password`），
  不是标准 Spring datasource；默认走 H2 无需配置。
- **菜单配置**在 `src/main/resources/application-menu-docker.yml`，可用 `-override.yml` 覆盖。
  「容器平台」下依次为 项目 / 应用 / 镜像 / 主机（`/host`），再往下是「设置」（注册中心、代码源、全局设置）
  与「工具」（主机 Agent、镜像同步、构建测试、镜像下载、内网穿透、网站监控）。**「主机」与「设置」平级**，不再挂在设置下面。
- **领域模型：** `Project`(项目，构建定义) → `Image`(镜像，`imageUrl` 唯一) → `ImageTag`(镜像标签，`imageUrl`+`tag`)。
  项目与镜像按 `imageUrl` 相等关联；`App` 只存 `imageUrl` + `imageTag`，与项目/镜像均无 id 外键。
  菜单为「项目」(`/project`) 与「镜像」(`/image`)，权限码为 `image:*`，表名为 `t_image`。
- **镜像同步记录**存 `t_image_sync_log`（`ImageSyncLog`，表由 `ddl-auto: update` 自动创建）：
  控制器先落库一条 `success` 为空的「同步中」记录，`@Async` 任务结束时补全结果、耗时与失败原因。
  记录只存快照（主机名、注册中心地址），日志正文仍在 `/data/logs/{logId}.log`，按记录里的 `logId` 回看。
  页面（`web/src/pages/image-sync/index.jsx`）是 ProTable + 工具栏三个「增加」按钮
  （同步到注册中心 / 同步到主机 / 经执行主机中转），每个按钮打开 `ImageSyncModal` 对应模式，
  提交后弹窗原地切换为实时日志；行内「查看日志」用抽屉回看。
- **主机文件管理 / Docker CLI：** Agent 是「工具」菜单下的独立工具（`/host-agent`，
  ProTable 页面 `web/src/pages/host-agent/index.jsx`），配置存 `t_host_agent_config`
  （`HostAgentConfig`，表由 `ddl-auto: update` 自动创建）：一条配置绑定一台主机
  （`hostId` 唯一），保存期望的镜像 tag 与挂载目录，支持新增 / 修改 / 删除；
  行操作「终端」打开 `/host-agent/view?id=<配置id>`（`web/src/pages/host-agent/view.jsx`），
  终端占满页面，统一通过 Docker API 在该主机上运行辅助容器 `docker-admin-agent`。
  主机列表与主机详情都不再有 Agent 入口。部署 / 切换目录成功后会把实际 tag 与目录回写到配置
  （`HostAgentService` 部署完成后按 hostId 定位配置，必要时自动补一条）；状态接口支持
  `tag` / `root` 覆写，用于按配置显示「需要重新部署」。删除配置只删记录，不会删除目标主机上的容器。
  镜像分两层配置：全局设置只保存镜像地址
  （`docker.agent.repository`，默认 `ghcr.io/jiangood/docker-admin-agent`，不含 tag），
  每条配置选择 tag（默认 `latest`），实际镜像 = 地址 + `:` + tag。
  agent 镜像内置 `docker-cli` 和 `docker compose`，另装了一批常用运维工具
  （bash / curl / wget / ping / nc / ss / netstat / dig / ssh / git / rsync / jq / yq /
  vim / less / htop / ps / top / free / lsof / tree / file / zip / unzip 等，见 `agent/Dockerfile`），
  便于直接在终端里排查；代价是镜像约 260 MB，目标主机首次部署要拉一次。由
  `.github/workflows/publish-docker-image.yml`
  在 `v*` tag 上同时发布到 ghcr 与阿里云（`:latest` + `:vX.Y.Z`），目标主机可预加载后离线使用。
  **部署时不再选择宿主机目录**：容器只挂 Agent 配置里的目录（`HostAgentConfig.fileRoot`，首次默认 `/home`）与
  `/var/run/docker.sock`；同一目录既挂到容器内 `/host` 供文件管理，也挂到原始绝对路径供 CLI 使用，
  因此 `docker compose` 的相对 bind 路径与宿主机一致。目录在 Agent 面板右上角「切换挂载目录」按需切换，
  切换会用新目录**重建容器**（`admin/host/agent/root`，需 `host:save`；终端会断开并自动重连）；
  目录只在容器成功起来后才落库，失败时保留旧值，方便重新部署恢复。
  **`Host` 实体不再有 `fileRoot` / `agentImageTag` 字段**：配置以 Agent 表为准，
  无配置时按 `/home` + `latest` 兜底；旧库升级时 `HostAgentConfigMigration` 会把
  `t_host.file_root` / `agent_image_tag` 搬到 `t_host_agent_config` 并清空旧列。
  CLI 是辅助容器内的交互式 shell，权限等同于宿主机 root。
  目录只接受 Docker daemon 可见的 Linux 绝对路径，禁止 `/`、`/host`、`/tmp` 与系统目录；Windows 主机需填
  Docker Desktop（WSL 2 后端）的 `/run/desktop/mnt/host/d/...`、Docker VMM 后端的 `/host_mnt/d/...`，
  或 WSL2 原生 docker 的 `/mnt/d/...`；Docker VMM 必须在 Settings → Resources → File sharing 显式共享目录
  （不支持 bind mount 自动共享），不支持 Windows 容器主机。
  辅助容器由 `host:save` 显式部署（`admin/host/agent/deploy`；界面不提供删除容器的按钮：
  工具页的「删除」只删配置记录，需要删除容器时走主机详情「容器」Tab，因为辅助容器就是普通容器），CLI WebSocket 用 `host:cli`；
  **界面不再提供上传 / 下载按钮**：文件操作统一在终端里做（后端 `admin/host/file` 的 `upload` / `download`
  接口仍保留，权限码 `host:file`，路径相对于当前挂载目录，空串表示挂载目录本身）；
  容器常驻，删除容器不会删除宿主机文件。重建 `latest` 会先尝试拉取，拉取失败但本地已有镜像时继续使用本地镜像。
  Agent 面板用一块 `Card` 承载：标题是「主机名 · Agent + 状态徽标（运行中 / 已停止 / 未部署）」，
  右上角 `extra` 依次是重新连接（图标）、重新部署（配置与容器不一致时出现）、切换挂载目录、部署、帮助（图标），
  正文只有终端（`styles={{body: {padding: 12}}}`）；异常 / 待处理提示（状态读取失败、
  配置与容器不一致、无 `host:save` 权限）以 Alert 形式出现在终端上方，正常状态不占空间；
  容器状态、提示与按钮由 `HostAgentAlert` 通过 render prop 交给 `HostAgentTab` 组装，
  弹窗仍由 `HostAgentAlert` 自己渲染（避免重复请求 agent 状态）。当前目录不在标题重复展示（只在「切换挂载目录」弹窗里），
  终端区没有工具条（`HostCli` 只渲染 xterm，
  重新连接由 `HostAgentTab` 通过 ref 调 `HostCli.reconnect`）；
  文件的上传 / 下载与查看 / 编辑 / 删除 / 重命名 / 新建目录都在终端里完成；
  权限、目录、文件操作等长说明收进问号弹窗（`HostAgentAlert`），页面上不再堆 Alert；
  终端连接成功后不弹通知、也不往终端回滚区写 banner，进入终端即可直接使用。
- **容器详情页：** 容器不再用弹窗 / 内嵌页签展示，统一走独立页面 `/container/view`
  （`web/src/pages/container/view.jsx`，pageMeta「容器详情」）。参数为 `hostId` + `containerId`（主机维度）
  或 `appId`（应用维度，走 `admin/app/containerDetail` 与 `/admin/ws/log/{appId}`），
  `tab=logs|info|files|console` 直达对应页签。页面上方是基本信息与启动 / 停止 / 重启 / 删除，
  下方是横向页签（日志 / 文件 / 终端 / 详情）；主机详情的容器列表、监控表跳到这里（应用详情「容器日志」只保留日志，不再提供跳转入口）。
  容器日志页签上方的「停止 / 继续滚动」控制是否跟随新日志，「下载日志」按末尾 2000 行下载为 .log 文件
  （应用维度走 `admin/app/containerLogDownload`，主机维度走 `admin/container/logDownload`）。
  删除前需二次确认，运行 / 暂停 / 重启中的容器必须勾选「强制删除」，成功后关闭当前页签。
  页面页签开启 `destroyOnHidden`，切走即断开日志 / 终端
  WebSocket；主机 / 应用详情页实现 `onShow()`，从容器页切回时刷新容器状态（首次 onShow 与 mount 重叠，需跳过）。
- **主机详情镜像导出：** 镜像 Tab 的「导出」打开弹窗，后台在该主机上执行 `docker save`
  （SSH 主机落到远端 `/tmp/docker-admin-image-tar/{logId}/`，本机 / TCP 主机落到平台 `image-tar/{logId}/`）。
  弹窗展示实时日志、已写入大小与速度（每秒 / 每分钟）、保存路径与下载按钮；SSH 下载经 SFTP 流式转发，不在平台侧重复落盘。
  任务状态只存内存（`HostImageExportService`），tar 不自动删除，由用户按路径自行清理。
- **容器启停删的兜底超时与超时后回查：** 主机详情 / 应用详情的容器操作都走
  `DockerClientManager#callWithTimeout`（容器操作 120 秒、应用部署里的停止 60 秒 / 删除 60 秒），
  不让请求挂满 docker-java 的 1 小时 `responseTimeout`。
  **stop / remove 超时后会再用 10 秒预算 inspect 回查真实状态**：容器已经停掉 / 删掉就按成功返回
  （`ContainerService#isStopped` / `#isGone`），否则错误消息里写明「仍然运行 / 仍然存在 / 无法确认」
  并给出宿主机侧下一步（`docker rm -f <id>`、`docker ps -a`）；删除遇到 404 视为成功（幂等）。
  前端删除后无论成败都刷新容器列表，容器确实不在了才关弹窗。
  典型卡死原因：容器内进程卡在不可中断（D）状态（如 bind 挂载的 NFS 失联），SIGKILL 也要等内核返回，
  此时 stop 与 rm 会一起超时，需在宿主机上看 `ps` 的 D 状态、`dmesg` 的 hung task 与 `mount`。
- **首页资源概览分两条链路：** 静态容量走 `admin/home/hostSummary`（`docker info`，30 秒缓存，首屏用），
  实时用量走 `admin/home/hostUsage`（首页）与 `admin/host/usage`（主机详情「监控」Tab），
  数据由 Quartz 定时任务「主机用量采集」（`HostUsageCollectJob`，每 10 分钟）写进 `HostUsageService` 的内存缓存。
  **页面只读快照**（`snapshotAll` / `snapshot`），不触发采集；只有 `refresh=true`（用户点「刷新」）才现采一轮，
  所以开多少个标签页、多少人在看都不会增加主机侧负载（首页 30 秒、主机页 30 秒轮询，页面隐藏时暂停）。
  逐运行中容器 `statsCmd(...).withNoStream(true)`——docker-java 只把它映射成 `?stream=0`，dockerd 内部要采两次样本（约 1 秒）
  才有 CPU%，所以容器级并发 12、单机预算 6 秒、整体 8 秒兜底，并合并同一主机的并发采集。
  每轮采集都新建 DockerClient（`DockerClientManager` 有意不缓存客户端，SSH 主机每轮一次握手），10 分钟间隔下这点开销可以接受。
  口径对齐 docker CLI：CPU% = `(cpuDelta/systemDelta)×onlineCpus×100`，内存扣 `inactive_file`
  （cgroup v1 `total_inactive_file` / v2 `inactive_file`），网络取各接口 rx/tx 求和，块 I/O 取
  `io_service_bytes_recursive` 读写之和（无条目按 0 计，与 docker stats 一致）；主机级只汇总运行中容器，是「容器合计」。
  采集失败的主机按 1 → 2 → 5 → 15 轮阶梯退避（一轮 10 分钟，状态在 `HostUsageService`，**不能放任务字段**：Quartz 每次触发都重建实例），成功即复位。
- **用量历史：** 采集结果同时写 `t_host_usage_sample`（每 10 分钟一行/主机，网络与块 I/O 存**累计计数器**原值），
  主机详情「监控」Tab 顶部按 1h / 6h / 24h / 7d / 30d 展示 CPU/内存与网络/磁盘速率曲线
  （`admin/host/usageHistory`，Java 侧分桶到 ≤500 点；速率由相邻采样求差，差值为负 = 容器重启 → 曲线断点）。
  采样默认保留 30 天（`HostUsageSampleService` 里的默认值，需要时用 `docker.usage.retain-days` 覆盖，采集任务每小时顺带清理）；
  30 台主机 30 天约 13 万行、20 MB。
  定时任务记录由 `HostUsageJobInitializer` 幂等自动创建（`sys.job-enable=false` 时跳过，已存在则不覆盖用户改动），
  日志聚合为按小时。**升级 docker-java 时需要回归这条链路。**
- **应用定时更新：** 应用详情「发布」页的「定时更新」开关（`App.scheduledUpdate`，默认关闭）按
  `scheduledUpdateIntervalMinutes`（默认 1440 分钟）定时检查镜像，适用于不是通过项目构建的外部镜像。
  任务 `AppScheduledUpdateJob` 每分钟扫描到期应用，只处理**运行中**的容器；检查由**平台直接访问注册中心**
  （`RegistryService#v2ClientFor` → `RegistryV2Client#fetchManifestDigest`，只读 manifest，**不下载镜像层**），
  用注册中心里该 tag 当前的 manifest / index 摘要与本地镜像的 `RepoDigests`、镜像 ID 比较
  （经典存储的镜像 ID 是 image config 摘要，containerd 存储的镜像 ID 就是 manifest 摘要，两个候选都参与比较），
  不同才以 `DeployTrigger.SCHEDULE`（「定时更新」）触发部署，部署时强制 pull 拿到新镜像。
  注册中心地址与凭据按镜像地址解析（`RegistryService#forImageUrl`）；未登记的公共仓库匿名访问
  （Docker Hub 走 `registry-1.docker.io`，单段名补 `library/`），因此要求**平台能访问该注册中心**。
  检查失败保留旧容器，10 分钟后重试；检查结果快照存在
  `scheduledUpdateLastCheckTime/LastSuccess/LastResult`，供页面展示。
  容器查询带 30 秒兜底超时（`SCHEDULED_UPDATE_QUERY_TIMEOUT`），一个失联主机不会拖住整轮；
  注册中心请求由 `RegistryV2Client` 自带 10 秒连接 / 30 秒读取超时。
  复制应用默认不会继承定时更新开关（间隔偏好保留）。
  **三个内建任务（本任务、主机用量采集、网站可达性检查）统一由 `BuiltinJobRegistrar` 幂等登记**：
  框架 `JobAutoRegistrar` 会先把带 `@JobDescription` 的类补建成「无 cron、默认禁用」的空壳行，
  且顺序早于 `ApplicationRunner`，所以只判断「jobClass 是否存在」会让任务永远不触发；
  有 cron 的行（用户配置过）一律不碰，没有 cron 的空壳行补上默认 cron 并启用。
- **主机接入代理：** 远程 TCP 可选 HTTP / SOCKS5 连接代理，界面与邮件代理一样只填一行地址
  （`http://192.168.1.10:8080`、`http://user:pass@192.168.1.10:8080`、`socks5://user:pass@192.168.1.10:1080`，
  不写 scheme 按 http，端口必填，留空表示不使用；密码回显为 `***`，保存时保持 `***` 或省略密码表示沿用已存密码）。
  落库仍是 `Host.proxyType/host/port/username/password` 五列，`Host.proxyUrl` 是 `@Transient` 的出入参字段，
  由 `ProxySettings.parse` / `displayUrl` 双向转换（主机与邮件共用），旧数据无需迁移；界面只提交 `proxyUrl`，
  `HostService.saveHost` 会把解析出的五列补进 `X-Body-Fields` 更新列表。
  用于平台与目标 docker 网络不通、但另有一台主机两边都能访问的场景：HTTP 走 CONNECT（支持 Basic 认证），
  SOCKS5 支持用户名密码（RFC 1929），目标主机名交给代理解析，`https://` 的 TLS 仍是平台到目标端到端。
  docker-java 3.7.1 的 Apache 传输层没有代理入口，`ProxyAwareApacheDockerHttpClient` 同步了其
  `ApacheDockerHttpClientImpl` 并改用 `ProxyTunnelSocket` 建连；**升级 docker-java 时需对照上游同步这两个类**。
  只有启用代理的 TCP 主机走它，直连主机仍用官方 `ApacheDockerHttpClient`。
- **隧道页（http-tunnel，菜单名「内网穿透」）：** `/tunnel` 是部署/使用说明页
  （`web/src/pages/tunnel/index.jsx`，纯静态，没有后端接口）。http-tunnel 已迭代到 2.x，自带 Web 管理端：
  入口、中继、客户端共用同一个 `ht`，角色由 `ht.toml` 是否含 `[upstream]` 决定；管理页与访客共用
  `listen_port`，只在 `control_host` 上可达（Basic 用户名 `ht`，密码为 `token`）。代理 `[[proxies]]`
  的增删改都在管理页完成并写回 `ht.toml`。页面保留了单节点 / 两节点配置示例、容器部署时挂载
  `/var/run/docker.sock` 的写法，以及经隧道访问 Docker 时在主机「自定义请求头」填 `X-HTTP-TUNNEL-TOKEN` 的说明。
  **改这块前先对照项目最新文档**。
- **构建上下文：** `Project.context` 相对仓库根目录，留空表示仓库根；填写时不带前导 `/`（后端兼容历史的 `/admin`，保存时归一化）。
  `dockerfile` 同样相对仓库根目录、不带前导 `/`，且必须位于上下文内（如 `context=admin` + `dockerfile=admin/Dockerfile`）。
  前端表格/详情直接原样展示上下文（空即空，不再显示「仓库根目录」），输入框失焦时自动去掉前导 `/`。
  归一化与校验集中在 `BuildContextUtils`（`normalize` / `normalizeDockerfile`），构建测试页也支持同样的上下文参数。
- **Tests** 大多是 `main()` 方法（如 `DockerSdkTest`、`PrintTest`），无法通过 `mvn test` 运行，
  需要在 IDE 里单独执行；`src/test/java` 下另有少量 JUnit 测试（如 `VersionUtilsTest`、
  `BuildContextUtilsTest`），可用 `mvn test -Dtest=<类名>` 单独跑。
- **权限**用 open-admin 的 `@HasPermission("app:view")`，不是标准 Spring Security 注解。
- **WebSocket 必须显式放行来源**：所有端点都走 `WebSocketConfig#register`，其中显式
  `setAllowedOriginPatterns("*")`。**不能省**——Spring 的
  `AbstractWebSocketHandlerRegistration#getInterceptors()` 会**无条件**追加 `OriginHandshakeInterceptor`，
  「不调 setAllowedOriginPatterns」不等于关闭校验；它的白名单默认为空集合，`CorsConfiguration.checkOrigin`
  对空配置返回 null，等价于只允许严格同源。同源判定用 `WebUtils.isSameOrigin`（servlet 请求直接取
  `getScheme()/getServerName()/getServerPort()`，**不看 `X-Forwarded-*`**），平台常经 nginx / http-tunnel
  隧道终止 TLS，后端看到的是 `http` + 内网端口，和浏览器 `Origin: https://域名` 对不上，握手直接
  `403 Unexpected response code`（会话失效是 401，别混淆）。放行所有来源后，跨站防护由①会话 Cookie 显式
  `SameSite=Lax`（`application.yml` 的 `server.servlet.session.cookie.same-site`，**别改成 none**）与
  ②每个端点的鉴权拦截器（登录+权限+数据权限）承担，所以**新增 WS 端点不要漏掉鉴权拦截器**。
- **构建并发（新的覆盖旧的）：** 同一项目用固定代码目录，不能并行构建。新的触发（手动、重试或 Webhook）
  会先取消正在跑的旧构建（`ProjectService.cancelProcessingBuilds` → `stopBuild`），新任务在 `@Async` 线程里
  等旧构建退出代码目录后再开始，触发接口立即返回。取消靠 `cancelledBuilds` 标记 + 检查点
  （克隆后、docker build 后、推送后），克隆阶段无法中断，只能等克隆结束后放弃。
  超时上限见 `BUILD_SLOT_WAIT_MILLIS`；等待是进程内的，多实例部署下不跨实例生效。
- **邮件通知：** 发件账号由**框架**「系统管理 - 参数设置 - 邮件发送」维护（`sys_config` 的
  `email.provider/username/password/from.name/proxy`，由框架 `MailConfigProvider` 声明），
  本项目的「设置 - 全局设置」只保留默认资源与 agent 镜像；旧版 `t_system_config` 里的 `email.*`
  由 `LegacyEmailConfigMigrator` 在启动时一次性搬到 `sys_config`（按旧 SMTP 主机反推邮箱服务商，
  成功后删掉旧行；新配置已填发件邮箱时不动）。
  SMTP 服务器/端口/加密方式由框架的 `MailProvider` 模板决定（QQ、腾讯企业邮、163/126、Outlook/M365、
  Gmail、阿里企业邮），界面只选服务商 + 填发件邮箱与授权码；**代理**同样只填一行地址
  （`http://192.168.1.10:8080`、`http://user:pass@192.168.1.10:8080`、`socks5://192.168.1.10:1080`，
  不写 scheme 按 http，端口必填，留空即不使用；密码回显 `***`）。平台跑在容器里时**不能填 127.0.0.1**，
  要填宿主机网桥网关（如 `172.17.0.1`）或局域网 IP。
  是否发送在**项目详情页**的「邮件通知」开关控制（`Project.notifyEmail`），收件人为项目创建人的
  `sys_user.email`；创建人未配置邮箱时开关不可开启（后端也会校验），并提示到用户管理补充。
  `ProjectService` 在构建成功与失败时都发布 `BuildResultEvent`，`admin/mail/BuildMailNotifier` 组装
  `MailMessage`（正文为构建摘要，附件为日志文件 `${logging.file.path}/{logId}.log`）后交给框架
  `MailService.sendAsync`；未配置发件账号时只跳过并记日志，邮件失败不影响构建结果。
  **不要再自己建 `MailService`**：框架的 `modules/mail/service/MailService` 是 `@Service`，
  同名 Bean 会与业务侧冲突。
- **前端**是 `@jiangood/open-admin` 之上的薄层，页面在 `web/src/pages/`，构建配置 `web/vite.config.js`。
- **JSON 里的包装类型 `Long` 是字符串**（open-admin 的 `@JacksonComponent LongToStringSerializer`，
  防 JS 丢精度；`long` / `Integer` / `Double` 仍是数字）。前端做加减乘除前必须 `Number()`
  （统一走 `web/src/components/container/utils.js` 的 `toNumber`），
  否则 `sum + item.memUsed` 会变成字符串拼接——首页内存合计曾因此显示成 `9e+33 TB`。
- **首页资源卡的 CPU 核数**在表格 CPU 列与卡片底部都直接展示（表格第二行 `已用 / 主机 核`），
  不再只放在悬浮提示里；核数优先取静态概览的 `cpus`，缺失时退回实时用量里的 `cpus`。
- **静态文件**从 JAR 提供；开发时前端通过 Vite 代理访问后端。
- **CodeMirror 5** 被锁定在 5.x（`codemirror@^5.0.0`）。CM6 是破坏性重写，
  `web/src/components/CodeMirrorEditor.jsx` 依赖 CM5 专有路径与 API，
  升级前必须先完成迁移。原因见 `.github/dependabot.yml` 中的 ignore 规则。
- **`web/package-lock.json` 已纳入版本管理**，构建应可复现，不要再 gitignore 它。
- **CI：** `.github/workflows/` 下只有 `publish-docker-image.yml`，在 `v*` tag 上发布镜像到 ghcr.io 与阿里云。
  **push/PR 上没有构建流水线**，前后端构建需本地跑。
- **No codegen, no migrations** — schema 手工管理或使用 open-admin 默认结构。
