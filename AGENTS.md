# docker-admin

Multi-host container management platform with CI/CD. UI is Chinese.

## Stack

- **Backend:** Spring Boot 4.1.0 + Java 21, Maven → `target/app.jar`（`<finalName>app</finalName>`）
- **Frontend:** Vite 8 + React 19 + Ant Design 6 + TypeScript 7
- **Base framework:** `io.github.jiangood:open-admin` 3.1.8（后端 Maven）/ `@jiangood/open-admin` 3.1.8（前端 npm），提供 CRUD、鉴权、菜单
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
- **领域模型：** `Project`(项目，构建定义) → `Image`(镜像，`imageUrl` 唯一) → `ImageTag`(镜像标签，`imageUrl`+`tag`)。
  项目与镜像按 `imageUrl` 相等关联；`App` 只存 `imageUrl` + `imageTag`，与项目/镜像均无 id 外键。
  菜单为「项目」(`/project`) 与「镜像」(`/image`)，权限码为 `image:*`，表名为 `t_image`。
- **镜像同步记录**存 `t_image_sync_log`（`ImageSyncLog`，表由 `ddl-auto: update` 自动创建）：
  控制器先落库一条 `success` 为空的「同步中」记录，`@Async` 任务结束时补全结果、耗时与失败原因。
  记录只存快照（主机名、注册中心地址），日志正文仍在 `/data/logs/{logId}.log`，按记录里的 `logId` 回看。
  页面（`web/src/pages/image-sync/index.jsx`）是 ProTable + 工具栏三个「增加」按钮
  （同步到注册中心 / 同步到主机 / 经执行主机中转），每个按钮打开 `ImageSyncModal` 对应模式，
  提交后弹窗原地切换为实时日志；行内「查看日志」用抽屉回看。
- **主机详情镜像导出：** 镜像 Tab 的「导出」打开弹窗，后台在该主机上执行 `docker save`
  （SSH 主机落到远端 `/tmp/docker-admin-image-tar/{logId}/`，本机 / TCP 主机落到平台 `image-tar/{logId}/`）。
  弹窗展示实时日志、已写入大小与速度（每秒 / 每分钟）、保存路径与下载按钮；SSH 下载经 SFTP 流式转发，不在平台侧重复落盘。
  任务状态只存内存（`HostImageExportService`），tar 不自动删除，由用户按路径自行清理。
- **主机详情 Compose 管理：** 新增 Tab「Compose」（权限 `host:compose`），一期只做「看/改 compose 文件 + pull / up / down」。
  平台与主机之间只有 docker API、没有 shell，所以 compose 命令由**目标主机上的临时容器**代跑：
  镜像默认 `docker:cli`（自带 docker CLI 与 compose 插件，可通过 `cfg.compose-helper-image` 换内网地址），
  **项目目录按同一路径 bind 挂进容器**（compose 里 `./data:/data` 按项目目录解析，挂载点不一致结果就错），
  daemon 连接优先用平台正在用的 TCP 地址（辅助容器 `--network host` + `DOCKER_HOST`），本机/`unix:` 则挂 socket。
  文件读写不走 shell：同一个临时容器 + Docker archive 接口（`docker cp` 的 API 形态）读、写、列目录，写入保留原权限位。
  项目列表来自 `docker compose ls --all --format json`；没登记过的目录在页面上手工「打开目录」，路径记在浏览器 localStorage。
  **保存流程是「mtime 冲突检测 → `docker compose config -q` 校验 → 备份 `xxx.bak.时间戳` → 覆盖」**，
  校验不通过绝不落盘。执行输出走 `FileLog` + WebSocket `/admin/ws/host-compose-log/{logId}`，同一项目同时只允许一个任务。
  已知边界：只支持 Linux 宿主（Windows 容器模式直接提示不支持）；TLS 主机暂不支持（辅助容器需要客户端证书）；
  Docker Desktop 下 `./relative` 挂载可能不生效（界面有提示）；辅助容器都打 `io.jiangood.compose=1` 标签，异常时按 label 清理。
- **首页资源概览分两条链路：** 静态容量走 `admin/home/hostSummary`（`docker info`，30 秒缓存，首屏用），
  实时用量走 `admin/home/hostUsage`（首页）与 `admin/host/usage`（主机详情「实时资源」Tab），
  数据由 Quartz 定时任务「主机用量采集」（`HostUsageCollectJob`，每分钟）写进 `HostUsageService` 的内存缓存。
  **页面只读快照**（`snapshotAll` / `snapshot`），不触发采集；只有 `refresh=true`（用户点「刷新」）才现采一轮，
  所以开多少个标签页、多少人在看都不会增加主机侧负载（首页 30 秒、主机页 30 秒轮询，页面隐藏时暂停）。
  逐运行中容器 `statsCmd(...).withNoStream(true)`——docker-java 只把它映射成 `?stream=0`，dockerd 内部要采两次样本（约 1 秒）
  才有 CPU%，所以容器级并发 12、单机预算 6 秒、整体 8 秒兜底，并合并同一主机的并发采集。
  每轮采集都新建 DockerClient（`DockerClientManager` 有意不缓存客户端，SSH 主机每轮一次握手），分钟级间隔下这点开销可以接受。
  口径对齐 docker CLI：CPU% = `(cpuDelta/systemDelta)×onlineCpus×100`，内存扣 `inactive_file`
  （cgroup v1 `total_inactive_file` / v2 `inactive_file`），网络取各接口 rx/tx 求和，块 I/O 取
  `io_service_bytes_recursive` 读写之和（无条目按 0 计，与 docker stats 一致）；主机级只汇总运行中容器，是「容器合计」。
  采集失败的主机按 1 → 2 → 5 → 15 分钟阶梯退避（状态在 `HostUsageService`，**不能放任务字段**：Quartz 每次触发都重建实例），成功即复位。
- **用量历史：** 采集结果同时写 `t_host_usage_sample`（每分钟一行/主机，网络与块 I/O 存**累计计数器**原值），
  主机详情「实时资源」Tab 顶部按 1h / 6h / 24h / 7d / 30d 展示 CPU/内存与网络/磁盘速率曲线
  （`admin/host/usageHistory`，Java 侧分桶到 ≤500 点；速率由相邻采样求差，差值为负 = 容器重启 → 曲线断点）。
  采样默认保留 30 天（`HostUsageSampleService` 里的默认值，需要时用 `docker.usage.retain-days` 覆盖，采集任务每小时顺带清理）；
  30 台主机 30 天约 130 万行、200 MB。
  定时任务记录由 `HostUsageJobInitializer` 幂等自动创建（`sys.job-enable=false` 时跳过，已存在则不覆盖用户改动），
  日志聚合为按小时。**升级 docker-java 时需要回归这条链路。**
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
- **隧道（http-tunnel）：** 平台只是 `ht` 进程管理 API 的前端，不部署进程、不生成配置。
  客户端登记在 `t_tunnel_client`（名称、API 地址、控制 Host、令牌、域名），令牌做
  `Authorization: Bearer` 认证。v1.0.0 起服务端/客户端/中继合并为单个 `ht`，管理 API 与访客流量
  共用 `listen_port`，只接受 Host 等于 `ht.toml` 的 `control_host` 的请求；平台通常按 IP 连接，
  所以 `TunnelClient.controlHost` 用于覆盖 Host。`java.net.http` 不允许设置 Host，
  `HttpTunnelApiClient` 因此改用 Apache HttpClient5（docker-java 传输层已引入）。
  接口是 `GET /api/status`、`GET|PUT|DELETE /api/proxies`，0.x 的 `/api/tunnels` 在 404 时自动回退兼容；
  `status.proxies` 是本地代理数（0.x 是 `tunnels`），`connected` 只表示上游会话状态，不是在线状态。
  平台用应用 id 作 `name`、完整域名作 `domain`、`<主机 IP>:<主机端口>` 作 `local_addr`；
  改动写回客户端的 `ht.toml`，客户端用 `HT_PROXIES` 提供代理时增删返回 409（页面显示只读）。
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
- **邮件通知：** 发件账号（QQ 邮箱 SMTP，默认 `smtp.qq.com:465`）在「设置 - 全局设置」配置，
  键为 `t_system_config` 的 `email.*`，授权码不回显、留空表示不修改。
  **邮件代理**同样在全局设置里配置，界面只填一行地址（`http://192.168.1.10:8080`、
  `http://user:pass@192.168.1.10:8080`、`socks5://192.168.1.10:1080`，不写 scheme 按 http，
  端口必填，留空即不使用代理；密码回显为 `***`、保存时保持原样表示沿用已存密码，清空地址则把
  `email.proxy.*` 全部清掉）。落库仍是 `email.proxy.type/host/port/username/password` 五个键，
  由 `ProxySettings.parse` / `displayUrl` 双向转换（与主机代理共用），旧数据无需迁移。
  发送时直接复用 Angus Mail 原生的 `mail.smtp.proxy.*`（HTTP CONNECT，可带 Basic 认证）与
  `mail.smtp.socks.*`（SOCKS5 无认证），隧道在 TLS 之前建立，465/587 都适用；
  平台跑在容器里时**不能填 127.0.0.1**，要填宿主机网桥网关（如 `172.17.0.1`）或局域网 IP。
  是否发送在**项目详情页**的「邮件通知」开关控制（`Project.notifyEmail`），收件人为项目创建人的
  `sys_user.email`；创建人未配置邮箱时开关不可开启（后端也会校验），并提示到用户管理补充。
  `ProjectService` 在构建成功与失败时都发布 `BuildResultEvent`，`MailService` 异步发送，
  正文为构建摘要并附带日志文件 `${logging.file.path}/{logId}.log`；邮件失败不影响构建结果。
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
