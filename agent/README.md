# agent —— 主机辅助容器

本目录只有一份 `Dockerfile`，用来构建 **`docker-admin-agent`** 镜像。
平台不往目标主机装任何东西，而是在目标主机上跑这台常驻的 Linux 辅助容器，
主机页的「终端」和文件操作全部发生在它里面。

- 镜像名：`docker-admin-agent`（基础镜像 `alpine:3.21`，约 260 MB）
- 平台侧入口：主机列表「操作」列的 **Agent** 按钮（`/host/agent-view?id=<主机id>`）
- 相关代码：
  - `src/main/java/io/github/jiangood/docker/admin/service/HostAgentService.java`（部署 / 状态 / 挂载）
  - `src/main/java/io/github/jiangood/docker/admin/util/HostRootPolicy.java`（挂载目录校验）
  - `src/main/java/io/github/jiangood/docker/admin/util/AgentImageUtils.java`（镜像地址 / tag 校验）
  - `src/main/java/io/github/jiangood/docker/admin/websocket/HostCliWebSocketHandler.java`（终端）
  - `web/src/components/host/HostAgentAlert.jsx`（界面提示文案）

## 镜像里有什么

| 分类 | 内容 |
| --- | --- |
| Docker | `docker-cli`、`docker-cli-compose` |
| 证书 / 时区 | `ca-certificates`、`tzdata` |
| 基础 | `bash`、`coreutils`、`grep`、`sed`、`gawk`、`findutils`、`less` |
| 网络排查 | `curl`、`wget`、`iproute2`、`net-tools`、`bind-tools`、`netcat-openbsd`、`openssh-client` |
| 文件 / 文本 | `tar`、`zip`、`unzip`、`git`、`rsync`、`tree`、`file`、`lsof` |
| JSON / YAML | `jq`、`yq` |
| 编辑 / 进程 | `vim`、`procps`、`htop` |

默认 `ENTRYPOINT ["/bin/sh", "-c"]`、`CMD` 为 `while true; do sleep 3600; done`，
平台部署时会覆盖 entrypoint/cmd 和工作目录，容器只需常驻。

> 终端默认进的是 `/bin/sh`。需要 bash 时在终端里执行 `bash`。

## 平台怎么用它

### 容器形态

平台通过 Docker API 在目标主机（本机 / TCP / SSH 三种接入方式都行）上创建：

```text
名称      docker-admin-agent
镜像      <全局设置里的地址>:<主机选择的 tag>
挂载      <挂载目录>        -> /host                        # 文件管理入口
          <挂载目录>        -> <挂载目录>（原样绝对路径）     # 让 compose 的相对 bind 路径与宿主机一致
          /var/run/docker.sock -> /var/run/docker.sock        # 容器内 docker 直接操作宿主机 daemon（可用 docker.agent.socket-path 改源）
工作目录  <挂载目录>
环境变量  DOCKER_HOST=unix:///var/run/docker.sock
          DOCKER_CONFIG=/tmp/.docker
          HOME=/tmp
          TZ=<docker.agent.tz，可选>
安全      security-opt label=disable（SELinux 主机需要，故意不用 :z）
          只读 rootfs，仅 /tmp 为 tmpfs（rw,exec,nosuid,size=512m）
          cap-drop=MKNOD,NET_RAW,AUDIT_WRITE
          无重启策略，不自动删除
```

容器上打了几个 label 用来识别与比对配置（`io.github.jiangood.docker-admin.agent`
及 `.root` / `.image` / `.socket`）。目录、镜像或 socket 路径与 label 不一致时，
界面会提示需要重建。

### 权限与安全

容器同时挂了 docker.sock 和一个宿主机目录，**权限等同于宿主机 root**：

- 只在受信任环境使用；
- 终端里 `rm`、覆盖文件、`docker rm -f` 等都直接作用于宿主机；
- 页面上的「重新连接 / 切换挂载目录 / 部署 / 帮助」需要 `host:save`，终端 WebSocket 走 `host:cli`；
- 后端仍保留 `admin/host/file` 的上传 / 下载接口（权限 `host:file`），但界面不再提供按钮。

## 独立使用（不经过平台）

镜像本身不依赖平台：挂载、环境变量、工作目录、安全选项都是平台在**创建容器时**注入的，
镜像里没有任何等待平台调用的组件。所以可以自己 `docker run` 起一个，直接在终端里干活。
下面以挂载宿主机 `/opt` 为例。

### 准备

```sh
# 镜像可以本地构建，也可以直接拉发布版
docker build -t docker-admin-agent:dev ./agent
# 或：docker pull ghcr.io/jiangood/docker-admin-agent:latest

# 挂载目录，必须是 docker daemon 能看到的路径（示例用 /opt，一般已存在）
sudo mkdir -p /opt
```

### 启动

```sh
docker run -d --name my-agent \
  --restart unless-stopped \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v /opt:/opt \
  -w /opt \
  -e DOCKER_HOST=unix:///var/run/docker.sock \
  -e DOCKER_CONFIG=/tmp/.docker \
  -e HOME=/tmp \
  -e TZ=Asia/Shanghai \
  docker-admin-agent:dev

docker exec -it my-agent sh
```

终端默认进 `/bin/sh`，需要 bash 就在里面执行 `bash`。

### 每个参数为什么需要

| 参数 | 作用 |
| --- | --- |
| `-v /var/run/docker.sock:...` | 不挂只能当普通工具箱，`docker` / `docker compose` 都用不了 |
| `-e DOCKER_HOST=...` | 明确指向 unix socket，避免容器内 CLI 找错 endpoint |
| `-v /opt:/opt` | 挂到**宿主机原绝对路径**，让 compose 里的相对 bind 路径与宿主机一致；不跑 compose 也可省略 |
| `-w /opt` | 进终端就在数据目录，省得每次 `cd` |
| `-e DOCKER_CONFIG` / `HOME` | docker CLI 需要一个可写目录放 config；后面加 `--read-only` 时尤其需要 |
| `--restart unless-stopped` | 平台会自己管容器生命周期，独立部署得让它在宿主机重启后自己起来 |

平台还会把同一目录额外挂到容器内 `/host`（`HostRootPolicy.AGENT_MOUNT`），那是它自己的
文件接口入口（`HostFileService` 把界面上的相对路径转成 `/host/...`）。独立使用时没有任何组件
依赖这个路径，所以上面没挂；想把 `/host` 当入口或以后交给平台接管，自己加
`-v /opt:/host` 即可，与 `/opt:/opt` 并存互不影响。

想更贴近平台的安全配置，可追加：

```sh
  --read-only \
  --tmpfs /tmp:rw,exec,nosuid,size=512m \
  --security-opt label=disable \
  --cap-drop MKNOD --cap-drop NET_RAW --cap-drop AUDIT_WRITE
```

`--read-only` 时必须保留 `/tmp` 这个可写目录，否则 `docker` 命令会因为写不了 config 报错
（`DOCKER_CONFIG` 指在 `/tmp` 下）。

### 连接远程 Docker（TCP 2375）

典型场景：docker daemon 在对方机器（示例 `192.168.1.10`）上，你从本地经 2375 连过去，
把 agent **部署到对方机器**，并挂载**对方机器上的目录**（示例 `/opt`）。
所有 `-v` 都由对方 daemon 解析，所以 `/opt` 指的就是对方的 `/opt`，跟本地目录无关。

```sh
# 本地执行：容器建在对方机器上
docker -H tcp://192.168.1.10:2375 run -d --name my-agent \
  --restart unless-stopped \
  --network host \
  -v /opt:/opt \
  -w /opt \
  -e DOCKER_HOST=tcp://127.0.0.1:2375 \
  -e DOCKER_CONFIG=/tmp/.docker \
  -e HOME=/tmp \
  docker-admin-agent:dev

# 进入容器（同样要带 -H），可以在本地配 alias 少打一截
docker -H tcp://192.168.1.10:2375 exec -it my-agent sh
```

要点：

- **挂载路径属于对方机器。** `-v /opt:/opt` 挂的是 192.168.1.10 上的 `/opt`，
  你在容器里改文件就是直接改对方机器；本地不需要存在这个目录。
- **必须挂原绝对路径**（`/opt:/opt`，而不是 `/opt:/data`）：容器内路径与对方宿主机一致，
  compose 里的相对 bind（`./data:/data`）才会落到对方机器上。平台的做法相同，
  只是它另外把同一目录挂到 `/host` 给自己的文件接口用，独立使用可省略。
- **`--network host` + `tcp://127.0.0.1:2375`**：容器共享对方主机的网络命名空间，
  这样才连得到对方 daemon（对方已监听 `0.0.0.0:2375`）。不加 `--network host` 时，
  容器内的 `127.0.0.1` 是它自己，必须改填对方主机 LAN IP 或 docker0 网关（常见 `172.17.0.1`）。
- 换 tag / 改挂载目录要 `docker -H ... rm -f my-agent` 后重新 run；
  容器里挂载目录之外的改动会随容器一起丢掉。
- 前提：对方 daemon 已对外监听（`dockerd -H tcp://0.0.0.0:2375` 或 `daemon.json` 的 `hosts`），
  防火墙放行该端口。

**反过来的写法**（容器跑在本地、只把 `DOCKER_HOST` 指向对方）：

```sh
docker run -d --name my-agent \
  -v /opt:/opt -w /opt \
  -e DOCKER_HOST=tcp://192.168.1.10:2375 \
  -e DOCKER_CONFIG=/tmp/.docker -e HOME=/tmp \
  docker-admin-agent:dev
```

这种形态下挂载的 `/opt` 是**本机**目录，而 compose 的 bind 仍由对方 daemon 解析，
两边路径含义不一致，容易踩坑。除非你只是借本机当跳板改文件，否则优先用上面的部署方式。

补充两点：

- **2375 是明文无鉴权端口**，谁能连上就等于拿到对方主机 root，只在可信内网使用；
  生产建议换 2376 + TLS：

  ```sh
    -v /etc/docker/certs:/certs:ro \
    -e DOCKER_HOST=tcp://192.168.1.10:2376 \
    -e DOCKER_TLS_VERIFY=1 \
    -e DOCKER_CERT_PATH=/certs
  ```

  证书文件名必须是 `ca.pem`、`cert.pem`、`key.pem`。
- 需要经隧道 / 反向代理并带自定义请求头时，docker CLI 没有传 header 的选项
  （平台侧的「自定义请求头」是平台自己实现的），独立使用只能走 TLS 或代理：
  `-e HTTPS_PROXY=...` / `-e NO_PROXY=...`。

### 和平台部署的差异

| 平台提供 | 独立运行时 |
| --- | --- |
| `io.github.jiangood.docker-admin.*` label | 无；平台界面不会认领它，也不会比对配置 |
| 只读 rootfs、tmpfs、cap-drop 默认开启 | 需要自己加参数 |
| `latest` 重建时先拉取、失败回落本地镜像 | 自己 `docker pull` |
| 切换挂载目录时重建、终端自动重连 | `docker rm -f` 后重新 `docker run` |
| WebSocket 终端、文件面板、权限校验 | 只有 `docker exec` |

### 注意

- **参数不要拆开传。** `ENTRYPOINT` 是 `["/bin/sh","-c"]`，
  `docker run my-image ls -l /opt` 实际执行的是 `sh -c "ls" -l`，`-l` 会被丢掉。
  要带参数就把整串引起来：`docker run --rm my-image "ls -l /opt"`，或改用 `--entrypoint bash`。
- **权限等同宿主机 root。** 同时挂了 `docker.sock` 和数据目录，`rm`、`docker rm -f`
  都直接作用于宿主机，只在可信环境使用。
- **compose 的相对 bind 路径**依赖「把目录挂到它的原绝对路径」这一写法（如 `/opt:/opt`）；
  只挂到别的容器路径（如 `/opt:/host`）时，`./data:/data` 会解析成宿主机上不存在的路径。
- Windows / Docker Desktop 主机的路径换算、`file is not shared from the host` 等限制
  与平台部署完全一致，见下一节。

## 挂载目录

首次部署默认 `/home`（`HostRootPolicy.DEFAULT_ROOT`），之后在 Agent 面板右上角
「切换挂载目录」按需切换。目录保存在 `Host.fileRoot`，并写入容器 label，
**只有容器成功起来且 `/host` 确实是目录时才落库**；失败时保留旧值，方便回退。

目录只接受 **Docker daemon 可见的 Linux 绝对路径**，并且：

- 必须以 `/` 开头，不能含 `\`、`..`、控制字符；
- 不能是 `/`；
- 不能是以下系统 / 危险目录及其子目录、祖先：
  `/etc`、`/root`、`/boot`、`/proc`、`/sys`、`/dev`、`/run`、`/usr`、`/bin`、
  `/sbin`、`/lib`、`/lib32`、`/lib64`、`/var/lib/docker`、`/var/run`、`/mnt`、
  `/host_mnt`、`/host`（内部挂载点）、`/tmp`（agent 的 tmpfs）；
- 部署后会执行 `test -d /host` 复核，填了文件或失效软链接会直接回滚并报错。

### Windows 主机（Docker Desktop / WSL2 / Docker VMM）

**平台跑在哪台机器上不重要，目录是「目标主机上 Docker daemon 眼里的路径」。**
Windows 上的 daemon 跑在 Docker Desktop 的 Linux 虚拟机里，看不到 `D:\data`，
必须先换算成虚拟机内的 Linux 路径。

Docker Desktop for Windows 现在有三种后端，路径规则不同：

| 目标主机环境 | 该填的路径（以 `D:\data` 为例） |
| --- | --- |
| Docker Desktop + WSL 2 后端（默认） | `/run/desktop/mnt/host/d/data` |
| Docker Desktop + Docker VMM 后端 / 旧版文件共享路径 | `/host_mnt/d/data`（4.92 实测，见下） |
| WSL 2 里原生安装的 docker（非 Docker Desktop） | `/mnt/d/data` |

同时注意：

- **不要填** `D:\data`、`D:/data`、`\\wsl$\...`，这些都不是 daemon 的路径；
- **不要挂盘符根目录**（如 `/mnt/c`、`/run/desktop/mnt/host/c`、`/host_mnt/c`），也不要挂 Windows 系统目录：
  `Windows`、`Program Files`、`Program Files (x86)`、`ProgramData`、`$Recycle.Bin`、
  `System Volume Information`、`Recovery`、`Boot`、`PerfLogs`（大小写不敏感，子目录同样拒绝）；
- **不支持 Windows 容器主机**（daemon 是 Windows 容器模式时没有这套 Linux 路径）。

`/mnt/d/...` 只属于「WSL 发行版里的原生 docker」。Docker Desktop（无论 WSL 2 还是
Docker VMM 后端）的 daemon 不在你的发行版里，填 `/mnt/d/...` 能通过平台的前端校验，
但创建容器时 daemon 会直接报 `bind source path does not exist`。

#### Docker VMM（Docker Desktop 4.86+）

Docker VMM 是 Docker 自研的虚拟机监视器（Windows 上 4.86 起可选，部分版本界面仍标注 Beta）：
在 Windows 上提供 Hyper-V 级别的隔离和接近 WSL 2 的速度，宿主目录通过 Docker Desktop
的文件共享层进入 VM，不再复用你的 WSL 2 发行版。共享后的目录在 VM 里是
`/host_mnt/<盘符小写>/...`（4.92 实测），不是 WSL 2 的 `/run/desktop/mnt/host/...`。

切换方式：**Settings → General → Virtual Machine Manager → Docker VMM → Apply & restart**，
要求 Linux VM 至少 4 GB 内存，需要硬件虚拟化 / Windows Hypervisor Platform。
回退就是把同一项切回 WSL 2 或 Hyper-V 再 Apply & restart。

对本平台影响最大的一点：**Docker VMM 不支持 bind mount 自动共享**。
辅助容器挂载宿主机目录时，Docker Desktop 不会自动把该目录加入共享，会直接返回：

```text
the path "C:\data" is not shared from the host
```

处理：**Settings → Resources → File sharing** 里把宿主机目录（Windows 路径，如 `D:\data`）
加进去，只加需要的最小目录，不要加整个盘或 `C:\Users`；然后回 Agent 页重新部署。

另外，`\\wsl$` 路径、`/mnt/d/...` 这类 WSL 语义在 Docker VMM 下都不成立，
资源限制也不再读 `.wslconfig`。

#### 确认 daemon 内部路径（推荐）

WSL 2 与 Docker VMM 的路径不同，而且**不能靠 `docker inspect` 的 `Mounts.Source` 反推**：
Docker VMM（4.92 实测）会原样显示传入的 Windows 路径 `C:\...`，不会翻译成 Linux 路径。

Docker VMM 下，共享目录在 VM 里以 `/host_mnt/<盘符小写>/<目录>` 出现。先看盘符是否挂上：

```powershell
docker run --rm -v /:/rootfs alpine ls /rootfs/host_mnt
```

再用 `--mount` 验证具体目录（**必须用 `--mount`**：`-v` 会自动创建不存在的源目录，
让你误以为路径有效；平台用的正是 `--mount` 的语义）：

```powershell
docker run --rm --mount "type=bind,source=/host_mnt/c/Users/me/data,destination=/probe" alpine ls /probe
```

- 能列出目录内容 → 把这个 `/host_mnt/...` 路径填到「切换挂载目录」；
- `is not shared from the host` → 该目录没在 Settings → Resources → File sharing 里；
- `bind source path does not exist` → 路径写错，或该盘符没有共享。

看 daemon 的真实挂载点：

```powershell
docker run --rm -v /:/rootfs alpine sh -c "grep host_mnt /rootfs/proc/mounts"
```

如果目标主机是 WSL 2 里原生安装的 docker（不是 Docker Desktop），就不需要探针，
直接确认发行版里能看到即可：

```powershell
wsl -d <发行版名> -- ls -ld /mnt/d/data
```

本机实测（Docker Desktop 4.92 + Docker VMM）：共享 `C:\Users\...\agent-probe` 后，
`/host_mnt/c/Users/.../agent-probe` 可读写；`/mnt/c/...` 报 `bind source path does not exist`；
`/host_mnt/d` 报 `D:` 盘未共享。共享只暴露添加的目录，`/host_mnt/c` 下的其他路径
（如 `/host_mnt/c/Windows/win.ini`）不可见。

Docker VMM 的已知问题：嵌套 bind mount（一个 bind 里再挂一个 volume / bind）
可能被拆开（docker/desktop-feedback#607），在 Agent 终端里跑带嵌套挂载的 compose
时留意实际挂载结果。

使用建议：

- `/mnt/d/...`、`/run/desktop/mnt/host/...`、`/host_mnt/...` 都是跨文件系统
  （9p / 虚拟文件共享）映射，大量小文件读写明显慢于 Linux 原生盘。频繁 `rsync`、解压、
  `git`、`compose build` 建议改挂 Docker Linux VM 内部的目录（WSL 2 里是发行版的
  `/home/<用户>/work`；Docker VMM 下是 VM 内的 `/home/<用户>/work`，可以通过 Agent 终端上传文件）；
- WSL2 里 `/mnt/d` 是 drvfs，文件通常表现为 777、属主映射成 root，容器里写文件后
  Windows 侧一般不受影响，但脚本里依赖权限位时要注意；
- Docker Desktop 需要把目录加入 **Settings → Resources → File sharing**：
  Docker VMM 下是必须的（不支持自动共享），WSL 2 后端较新版本默认共享全部磁盘，
  旧版本需要手动添加；
- 宿主机后续新挂载磁盘，辅助容器看不到，需要「部署」重建一次。

## 部署 / 切换 / 升级

1. 主机列表 →「操作」→「Agent」；
2. 未部署时点「部署」，选择 Agent 镜像 tag（留空 = `latest`）；首次默认挂 `/home`；
3. 「切换挂载目录」会用新目录**重建容器**，终端断开并自动重连；
4. 镜像地址在「设置 - 全局设置」维护（`docker.agent.repository`，默认
   `ghcr.io/jiangood/docker-admin-agent`，**只填地址不带 tag**），
   每台主机的 tag 存在 `Host.agentImageTag`，实际镜像 = 地址 + `:` + tag；
5. 更换 tag：在主机详情「容器」Tab 删除 `docker-admin-agent`，回到 Agent 页重新部署；
   或直接对同一主机重新部署并填新 tag。

镜像拉取策略：`latest` 重建时会先尝试拉取，失败但本地已有则继续用本地镜像；
固定版本本地存在则不拉取。无外网环境可以先 `docker load` 预加载。

## 常见报错

| 现象 | 原因 / 处理 |
| --- | --- |
| `请填写 Docker daemon 文件系统中的 Linux 绝对路径` | 填了 `D:\...` 或含反斜杠 |
| `目录「/xxx」属于系统/危险目录，不允许挂载` | 命中 `HostRootPolicy` 黑名单，换 `/home`、`/data` 等数据目录 |
| 部署失败，提示 `path ... does not exist` | 路径在 daemon 里不存在；按换算表核对，或先用探针命令确认 |
| `is not shared from the host` | 目录没在 Settings → Resources → File sharing 里；Docker VMM 不支持自动共享 |
| `挂载目录不是有效目录` | 填的是文件、失效软链接，或 `/mnt/d/...` 这类只属于 WSL 发行版的路径；容器已自动回滚 |
| `已存在同名容器 docker-admin-agent，但不是本平台创建的` | 主机上有同名手工容器，先改名或删除 |
| `辅助镜像 ... 不存在且拉取失败` | 目标主机无外网且本地无镜像，预加载或配镜像加速 |
| 终端连上后立刻断开 | 辅助容器未运行 / 被删；回 Agent 页重新部署 |

## 构建与发布

推送 `v*` tag 时，`.github/workflows/publish-docker-image.yml` 会用 `context: ./agent`
构建并推送 `docker-admin-agent:latest` 与 `:vX.Y.Z`，同时发布到：

- `ghcr.io/<owner>/docker-admin-agent`
- `registry.cn-hangzhou.aliyuncs.com/jiangood/docker-admin-agent`

本地构建：

```sh
docker build -t docker-admin-agent:dev ./agent
```

改完本目录只需发版；目标主机重新部署时按 tag 拉新镜像。
