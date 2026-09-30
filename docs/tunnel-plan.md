# 隧道功能 · 完整方案（nps 版）

> 状态：**方案已端到端实测通过**；**P1（服务端）+ P2（应用侧）均已实现并验证**，P3（对账 Job / 清理 / 可选 HTTPS）待做。
>
> 实测环境：香港 VPS `103.38.81.109`（Rocky 9 / docker 29.8.1 / 出口即公网 IP）作 nps 服务端
> ＋ `docker1`（CentOS 7 / docker 20.10.24 / 出口 `222.85.139.63`）作客户端。
> 实测结论见第 16 节。

## 0. 决策总览

| # | 事项 | 结论 |
|---|---|---|
| 1 | 技术选型 | **nps**（服务端 `yisier1/nps`，客户端 `yisier1/npc`） |
| 2 | 本期范围 | 仅 **HTTP 域名（子域名）路由**；TCP/UDP 端口隧道、P2P、HTTPS 留待后续 |
| 3 | 镜像源 | 默认 **官方原始地址**：`yisier1/nps` / `yisier1/npc`（Docker Hub）。**实测**：国内节点直连 Docker Hub 超时；DaoCloud 白名单**不含** `yisier1`；`docker.1ms.run` 可拉。→ 字段可配，跨网节点用加速站，或由能直连 Docker Hub 的节点（如香港）走【镜像同步】推注册中心 |
| 4 | nps 容器网络 | **`--net=host`**（conf 里的监听端口直接绑在宿主机上） |
| 5 | npc 容器网络 | `--net=host`（使 `target=127.0.0.1:<端口>` 命中节点上应用发布的端口） |
| 6 | `nps.conf` | **由平台生成**（含 `auth_key` / `web_password` / `auth_crypt_key`）、经 `docker cp` 写入、页面**可手动编辑并保存** |
| 7 | 监听端口 | 页面**手动填写**，写入 `nps.conf`，由 nps 在 host 网络下直接绑定宿主机端口；部署前必须检查占用 |
| 8 | 节点↔nps | 一个节点 = 一个 nps **客户端**（平台生成 `vkey`），npc 用它连接 |
| 9 | 应用↔nps | 一个开启隧道的应用 = 一条 **域名解析** 记录（`host` → `client_id` + `target`） |
| 10 | 子域名默认值 | **应用名称**（按 DNS label 规范化后），可修改 |
| 11 | 应用详情 | 同时展示**内网访问地址**与**隧道访问地址** |
| 12 | **功能总开关** | 隧道页面顶部一个全局 `enabled` 开关：关闭时停止并移除 nps 与全部 npc 容器、应用侧开关置灰；开启时按当前配置重新部署并自动对账 |
| 13 | **手动清理容器** | 扫描并列出各主机上平台创建的 nps / npc 容器（含已失联的残留容器），支持勾选停止并删除；只清容器与平台侧引用，**不动数据卷与配置** |
| 14 | **桥接端口与传输** | 默认 `tls_enable = true` + `tls_bridge_port = 443`，npc 用 `-server=<ip>:443 -tls_enable=true`。**实测**：跨境线路只放行 80/443，8024 等端口被 RST；改用 443+TLS 后一次连通 |

---

## 1. 目标

1. **隧道页面**选择一台主机作为 nps 服务端，用容器部署 / 重启 nps。
2. **应用详情页**增加隧道开关，配置子域名与要暴露的端口。
3. 采用 **域名（子域名）路由**，仅 HTTP。
4. 只为「存在已开启隧道应用」的节点部署 npc 容器。
5. 隧道页面提供**全局总开关**，可一键停用/启用整套隧道能力。

### 1.1 功能总开关（全局）

对应 `TunnelSetting.enabled`，在隧道页面顶部用一个 `Switch` 呈现（`tunnel:deploy` 权限）。

| 状态 | 行为 |
|---|---|
| **关闭 → 开启** | 校验设置完整性 → 重新部署 nps（含 conf 写入）→ 对每个含隧道应用的节点重建 npc → 按各应用的子域名/端口对账域名解析 → 状态标记为已启用 |
| **开启 → 关闭** | 停止并**移除** `docker-admin-nps` 与全部 `docker-admin-npc` 容器 → 清空 `TunnelNode.containerId` / `npsContainerId` → 状态标记为未启用 |
| 关闭状态下 | 应用详情的隧道开关 `disabled`，提示「隧道功能未启用，请先在【隧道】页面开启」；`admin/app/updateTunnel` 直接拒绝；`/admin/ws/tunnel-log/*` 之外的一切隧道接口返回明确错误 |
| 保留的数据 | named volume `docker-admin-nps-conf`（含 nps.conf 与 nps 侧的客户端/路由数据）、`TunnelSetting` 全部配置、各节点的 `vkey` 与 `npsClientId` —— 保证重新开启后能快速恢复 |
| 未配置时 | `TunnelSetting` 不存在或缺少主机，视为「未启用且不可启用」，应用侧同样置灰 |

设计取舍：**关闭时移除容器而非仅停止**（与 `AppService.deploy()` 先删后建的既有习惯一致），避免长时间停机后容器状态漂移；重新开启按配置全量重建，结果始终与平台数据一致。

操作走异步任务，带 logId 与实时日志（与部署 nps 共用同一条日志链路）。

### 1.2 手动清理容器

平台创建的隧道容器（`docker-admin-nps` / `docker-admin-npc`）可能因为手工改动、节点删除、部署中断等原因变成**残留容器**（宿主机上还在，但平台侧已无对应记录，或记录已失效）。提供一个手动清理入口。

**列表**：扫描各主机，按标签 `docker-admin.role in (nps, npc)` 收集容器（再用容器名 `docker-admin-nps` / `docker-admin-npc` 兜底），每行展示：

| 列 | 说明 |
|---|---|
| 主机 | 容器所在主机 |
| 容器名 / 容器 id | |
| 角色 | `nps` / `npc` |
| 状态 | running / exited |
| 镜像 | 使用的镜像 |
| 平台引用 | 是否被 `TunnelSetting.npsContainerId` / `TunnelNode.containerId` 引用；标记「**残留**」表示无引用或引用不匹配 |

**清理**：勾选后停止并删除容器，同时清空对应的平台侧引用（`npsContainerId` / `containerId`），返回 logId 走实时日志。

**明确不做**：不删除 named volume `docker-admin-nps-conf`、不删除 `TunnelSetting` / `TunnelNode`、不清空应用的隧道字段。因此清理之后重新部署即可恢复，数据（nps.conf、客户端、域名解析）仍在。

与总开关的关系：

| 操作 | 用途 |
|---|---|
| 总开关关闭 | 正常的「停服」，按平台记录逐个清理并置为未启用状态 |
| 手动清理容器 | 兜底手段：清掉平台记录覆盖不到的残留容器（例如节点已删、部署中断留下的） |

权限：`tunnel:clean`。前端在容器区域提供「刷新」「清理选中」「清理残留」按钮，`Modal.confirm` 二次确认。

---

## 2. 为什么是 nps

最初按 frp 设计，最重的部分是「配置进容器」：每改一次配置就要重算哈希 → 重新构建镜像 → 重建容器。nps 把这部分整段消掉。

| 维度 | frp | nps |
|---|---|---|
| 子域名路由 | `subDomainHost` + frpc 侧 `proxy.subdomain` | 服务端「域名解析」：`host → client + target` |
| 客户端配置 | 必须把 `frpc.toml` 送进容器（打镜像 / docker cp） | **无配置文件模式**：`npc -server=ip:8024 -vkey=xxx`，配置全在服务端 |
| 改一个应用的路由 | 重算哈希 → 重建该节点镜像 → 重建容器 | 一次 WebAPI 调用（`/index/edithost/`），**不动任何容器** |
| 管理面 | 无 | 自带 Web 后台 + 完整 WebAPI |
| 官方镜像 | 第三方维护（snowdreamtech 等） | 官方 `yisier1/nps` / `yisier1/npc` |

**因此不需要**：`ConfigImageBuilder`、Dockerfile 模板、frp release 下载与缓存、配置哈希、镜像 tag 与旧镜像清理。

---

## 3. 拓扑

```
                    用户
                     │ http://blog.tunnel.example.com:8080
                     ▼  (*.tunnel.example.com 解析到 nps 主机)
   ┌────────────────────────────────────────────────────┐
   │ nps 主机（隧道页面选定）                            │
   │ 容器 docker-admin-nps   --net=host                  │
   │  直接监听：<httpProxyPort> / <bridgePort> / <webPort> │
   │  /conf ← named volume（nps.conf + 客户端/路由数据）  │
   └──────┬─────────────────────────────────────┬───────┘
          │ npc 桥接连接（默认 TLS 443）          │
   ┌──────▼──────────────┐            ┌─────────▼─────────┐
   │ 节点 A               │            │ 节点 B             │
   │ docker-admin-npc     │            │ docker-admin-npc   │
   │  --net=host          │            │  --net=host        │
   │  -vkey=AAA           │            │  -vkey=BBB         │
   │  域名解析:            │            │  域名解析:          │
   │   blog → :18080      │            │   api  → :13000    │
   └──────────────────────┘            └────────────────────┘

无隧道应用的节点不部署 npc。
```

---

## 4. nps 关键事实（官方文档 + 实测核对）

- 镜像：`yisier1/nps`、`yisier1/npc`（Docker Hub 官方原始地址，默认值）。实测：国内节点直连 Docker Hub 超时、DaoCloud 白名单**不含** `yisier1`、`docker.1ms.run` 可拉 → 拉不到时在页面改加速地址。
- **镜像为 scratch 型**：容器内**没有 shell**（`docker exec ls` 报 not found），配置只能靠 `docker cp` 读写，不能用 exec 改文件。
- **实测版本 `0.26.38`**，与官方文档的 API 描述一致。
- 服务端：`--net=host -v <conf>:/conf`；首次启动若 `conf/nps.conf` **不存在**才自动生成随机凭据，**已存在则直接使用**（实测写入后 `diff` 为 `CONF-SAME`，未被覆盖）。
- **端口被占用时 nps 直接 fatal 退出**，配合 `restart=always` 会形成「启动→退出→重启」循环（实测 443 被占用时如此）→ 部署前必须查占用，启动后必须校验 running。
- 客户端：`--net=host yisier1/npc -server=<ip>:<port> -vkey=<vkey>`，**无配置文件模式**；TLS 桥接再加 `-tls_enable=true`。
- 默认端口：`bridge_port=8024`、`tls_bridge_port=8025`、`http_proxy_port=80`、`https_proxy_port=443`、`web_port=8081`。
- WebAPI 鉴权：
  - `GET /auth/gettime/` 取服务端时间戳（免鉴权，先取可规避两端时钟偏差）；`GET /auth/getauthkey/` 返回 AES-CBC 加密的 auth_key；
  - 签名 `auth_key = md5(conf 中的 auth_key + timestamp)`，与 `timestamp` 一起作为表单参数；**有效期 20 秒**，每次请求重新生成；
  - 无鉴权请求返回 **302** 跳登录页（实测）。
- 域名匹配按 HTTP `Host` 头，**带端口也能匹配**（实测 `Host: xxx:80` 可用）；**未匹配的域名返回空 200**（`Content-Length: 0`），**不是 404**。
- 本方案用到的接口（均已实测）：
  - 客户端：`POST /client/add/`、`/client/edit/`、`/client/del/`、`/client/list/`
  - 域名解析：`POST /index/addhost/`、`/index/edithost/`、`/index/delhost/`、`/index/hostlist/`
  - 隧道（本期不用，仅作扩展）：`/index/add/`、`/index/edit/`、`/index/del/`

---

## 5. 概念映射

| 平台概念 | nps 概念 | 说明 |
|---|---|---|
| 节点 `Host` | 客户端 `Client` | 平台为每个节点生成 `vkey`，建一个 nps 客户端；npc 用它连接 |
| 应用的隧道开关 | 域名解析 `Host` | `host=<子域名>.<subDomainHost>`，`target=127.0.0.1:<端口>`，`client_id=该节点客户端 id` |
| 改子域名 / 端口 | `/index/edithost/` | 纯 API 调用，容器不动 |
| 关闭开关 / 删应用 | `/index/delhost/` | 该节点再无隧道应用时，删除 npc 容器与客户端记录 |

---

## 6. 数据模型

```java
// 全局唯一（用法同 Registry：取最新一条）
@Entity @Table(name = "t_tunnel_setting")
class TunnelSetting extends BaseEntity {

    @ManyToOne Host host;                    // nps 主机
    String  npsAddr;                         // npc 连接地址（默认从 host 推导，可改为公网/VPN 地址）

    // nps 监听端口（写入 nps.conf，host 网络下直接绑定宿主机）
    Integer httpProxyPort;                   // 域名 HTTP 代理端口，页面手动填写
    Integer bridgePort;                      // npc 明文桥接端口，默认 8024
    Integer tlsBridgePort;                   // npc TLS 桥接端口，默认 443（跨境链路推荐）
    Integer webPort;                         // Web 后台端口，默认 8081
    /** 节点连接方式：plain（走 bridgePort）/ tls（走 tlsBridgePort）*/
    String  nodeBridgeMode;

    String  subDomainHost;                   // tunnel.example.com
    String  webUsername;                     // 默认 admin

    @JsonProperty(WRITE_ONLY) String webPassword;    // 平台生成
    @JsonProperty(WRITE_ONLY) String authKey;        // 平台生成
    @JsonProperty(WRITE_ONLY) String authCryptKey;   // 平台生成，必须 16 位

    String  npsImage;                        // yisier1/nps（默认官方；可改加速地址）
    String  npcImage;                        // yisier1/npc（默认官方；可改加速地址）
    String  confVolume;                      // docker-admin-nps-conf

    String  npsConf;                         // nps.conf 原文（平台生成，页面可编辑）
    String  confHash;                        // 当前生效的 conf 哈希（判断是否需要重启）
    String  npsContainerId;
    String  npsStatus;                       // running / exited / error
    LocalDateTime lastDeployTime;

    Boolean enabled;                         // 全局总开关（关闭时停止并移除 nps / npc 容器）
}

// 每个节点一行
@Entity @Table(name = "t_tunnel_node")
class TunnelNode extends BaseEntity {
    @ManyToOne Host host;
    Integer npsClientId;                     // nps 客户端 id
    @JsonProperty(WRITE_ONLY) String vkey;
    String  containerId;                     // npc 容器 id
    String  status;                          // running / exited / error
    String  lastError;
    LocalDateTime lastSyncTime;
}

// App 增加三个字段（ddl-auto=update 自动建列）
Boolean tunnelEnabled;
String  tunnelSubdomain;                     // 默认取应用名称（规范化后）
Integer tunnelPort;                          // 引用 App.config.ports 中的 privatePort
```

---

## 7. 端口与子域名规则

### 7.1 应用侧暴露的端口

`AppService.deploy()` 仅在 `bridge` 模式且配置了 `publicPort` 时才做端口映射（`AppService.java:149-163`），据此：

| App `networkMode` | 域名解析的 `target` | 前置校验 |
|---|---|---|
| `bridge` | `127.0.0.1:<publicPort>` | 必须已映射主机端口，否则拒绝开启并提示 |
| `host` | `127.0.0.1:<privatePort>` | 直接可用 |
| `none` | 不支持 | 界面置灰并说明 |

前端用 **AutoComplete**：候选项来自 `admin/app/configMeta`（bridge 给 `publicPort`，host 给 `privatePort`，label 带上容器端口信息），**允许手输**；手输值不在候选中时给出「请确认主机上真实监听该端口」的提示。

### 7.2 子域名

- **默认取应用名称**，按 DNS label 规范化：转小写、非 `[a-z0-9-]` 替换为 `-`、去首尾 `-`、长度 ≤63；规范化后为空（如纯中文名）时强制手填，界面展示规范化结果。
- 集群内**全局唯一**（域名解析的 `host` 不可重复），保存时校验。
- 访问地址：`http://<子域名>.<subDomainHost>`；`httpProxyPort ≠ 80` 时拼上端口。
- DNS：需把 `*.<subDomainHost>` 解析到 nps 主机（页面显式提示）。

---

## 8. 容器部署

### 8.1 nps 服务端（nps 主机上）

```bash
# 平台生成 conf 后，按顺序：
# 1) 创建容器（host 网络 + volume）
docker create --name docker-admin-nps \
  --restart=always \
  --net=host \
  -v docker-admin-nps-conf:/conf \
  yisier1/nps

# 2) 写入 nps.conf（平台生成）
#    copyArchiveToContainerCmd(containerId, "/conf") ← tar{nps.conf}

# 3) 启动
docker start docker-admin-nps
# 4) 轮询 GET http://<npsAddr>:<webPort>/auth/gettime/ 直到就绪
```

| 用途 | 端口（页面填写，写入 conf） | 绑定位置 |
|---|---|---|
| 域名 HTTP 代理 | `httpProxyPort` → `http_proxy_port` | host 网络下直接绑宿主机 |
| npc 桥接（明文） | `bridgePort` → `bridge_port` | 同上 |
| npc 桥接（TLS，**默认启用**） | `tlsBridgePort` → `tls_bridge_port`（默认 **443**） | 同上 |
| Web 后台 | `webPort` → `web_port` | 同上 |

- **`--net=host`**：nps 直接用 conf 里的端口在宿主机上监听，不做端口映射，避免「conf 说 80、实际映射 8080」的错位，访问地址与 nps 后台展示始终一致。
- 因此**部署前必须检查宿主机端口占用**（80 / bridgePort / tlsBridgePort / webPort）：与已有容器端口、系统进程冲突时直接报错。
- **实测：端口冲突时 nps 会 fatal 退出并被 `restart=always` 反复拉起**，所以启动后还要校验：容器 running 且 `/auth/gettime/` 可访问；不满足就读容器日志（日志里会明确打印 `listen tcp 0.0.0.0:443: bind: address already in use`）。
- `--restart=always`；日志限制 `max-size=50m`。

### 8.2 npc 客户端（各节点上）

```bash
docker run -d --restart=always --name docker-admin-npc --net=host \
  yisier1/npc \
  -server=<npsAddr>:443 -vkey=<vkey> -tls_enable=true
```

- 无配置文件、无镜像构建、无绑定挂载。
- 配置变化（新增/修改/删除应用的隧道）时**只需要 nps API 调用**，npc 容器不动；只有节点首次需要 npc 或需要换 vkey 时才重建容器。
- **桥接端口的选择必须按节点实测**：同一台 nps 上，`bridge_port=8024`（明文）与 `tls_bridge_port=443`（TLS）可并存，节点按自己网络能通过的端口来连。
- 平台侧应提供**节点桥接连通性检测**：逐个端口（bridgePort / tlsBridgePort）做 TCP 连通性测试，全不通就明确提示「该节点到 nps 的链路不可用，请换端口或改用 443+TLS」。

### 8.3 统一标签

```
docker-admin.managed = "true"
docker-admin.role    = "nps" | "npc"
docker-admin.host    = <hostId>
```

---

## 9. nps.conf 管理

### 9.1 生成

平台生成完整 conf，密钥由平台随机生成（`auth_crypt_key` **必须 16 位**）。模板：

```ini
appname = nps
runmode = release

bridge_type = tcp
bridge_ip = 0.0.0.0
bridge_port = <表单 bridgePort>
tls_enable = true
tls_bridge_port = <表单 tlsBridgePort，默认 443>
disconnect_timeout = 60
log_level = 6
log_path = nps.log
flow_store_interval = 1

http_proxy_ip = 0.0.0.0
http_proxy_port = <表单 httpProxyPort>
https_proxy_port =              # 本期留空，关闭 443 监听
show_http_proxy_port = true
http_add_origin_header = true

web_host = a.o.com
web_ip = 0.0.0.0
web_port = <表单 webPort>
web_username = <表单，默认 admin>
web_password = <平台生成随机>
web_open_ssl = false
open_captcha = false
allow_user_login = false        # 不允许用 vkey 登录 Web 后台

auth_key = <平台生成随机>
auth_crypt_key = <平台生成，16 位>

allow_local_proxy = false       # 只允许指向客户端侧目标
```

### 9.2 写入

- 通过 `copyArchiveToContainerCmd(containerId, "/conf")` 写入 tar（内含 `nps.conf`）。
- **时机**：容器 `create` 之后、`start` 之前。nps 启动时 conf 已存在，直接使用，不会覆盖。
- **兜底**（实现时必须先验证 named volume 是否生效）：若写入未生效，切换到
  - **A（首选兜底）**：改用宿主机目录 bind mount（页面填写路径，如 `/data/nps/conf`）；
  - **B**：先启动让 nps 自行生成，再 `copyArchiveFromContainerCmd` 读回，定点替换相关键后写回并重启。

### 9.3 读回与编辑

- 保留 `copyArchiveFromContainerCmd(containerId, "/conf/nps.conf")` 读回，用于**校验 + 页面展示**（不再是密钥来源）。
- 页面用 `components/CodeMirrorEditor.jsx` 编辑 conf 原文；保存 → 写回 → 重启容器；提供「生成默认配置」按钮恢复模板。
- 高级参数（`allow_user_login`、`allow_local_proxy`、各类限制开关）也可直接在 nps 自带 Web 后台调整；页面提供后台入口与账号密码。
- `confHash` 变化即触发 nps 重建/重启；conf 未变时部署按钮只做状态检查。

---

## 10. 后端实现

### 10.1 文件清单

| 文件 | 作用 |
|---|---|
| `entity/TunnelSetting.java`、`entity/TunnelNode.java` | 新实体（带 `@Remark`） |
| `dao/TunnelSettingRepository.java`、`dao/TunnelNodeRepository.java` | 仓储 |
| `service/NpsConfManager.java` | nps.conf 模板渲染、密钥生成、docker cp 读写、关键项解析 |
| `service/NpsApiClient.java` | 鉴权（gettime + md5 签名）、表单 POST、结果解析；客户端/域名解析 CRUD 封装 |
| `sdk/engine/ContainerHelper.java` | 隧道容器的创建/删除/状态/端口占用检查（也可并入 TunnelService） |
| `service/TunnelService.java` | 部署 nps/npc、域名解析增删改、节点对账、子域名校验；`@Async` + `MDC logFileId` 输出日志 |
| `controller/TunnelController.java` | 隧道页面接口 |
| `controller/AppController.java` | 新增 `updateTunnel`，`get` 返回隧道信息 |
| `service/AppService.java` | 保存/改配置/改名/复制/删除后触发 `tunnelService.onAppChanged(oldHostId, newHostId)` |
| `websocket/TunnelLogHandshakeInterceptor.java` + `websocket/WebSocketConfig.java` | 注册 `/admin/ws/tunnel-log/{logId}`，复用 `SyncLogWebSocketHandler`（校验 `tunnel:view`） |
| `resources/logback-spring.xml` | 给 `TunnelService` 挂 `docker-log-appender`（`MDC logFileId` → `/data/logs/{logId}.log`） |
| `resources/application-menu-docker.yml` | 新增隧道菜单与权限 |

### 10.2 关键流程

**deployNps(setting)**

0. 前置校验：`enabled=true`，否则直接返回「隧道功能未启用」。
1. 校验设置（host / npsAddr / 三个端口 / subDomainHost / 镜像）。
2. 生成/复用密钥，渲染 `nps.conf`，计算 `confHash`。
3. `getClient(host)` → 检查宿主机端口占用 → 删除旧的 `docker-admin-nps`。
4. `create` 容器（host 网络 + named volume）。
5. `copyArchiveToContainerCmd` 写入 `/conf/nps.conf`。
6. `start` → 轮询 `GET /auth/gettime/` 直到就绪（超时报错并保留容器日志）。
7. 记录 `npsContainerId` / `npsStatus` / `confHash` / `lastDeployTime`。

**ensureNode(hostId)**

1. 查 `TunnelNode`；已有则复用（含 `vkey` / `npsClientId`）。
2. 无则生成 `vkey`，`POST /client/add/`（`remark=主机名`、`config_conn_allow=false`）→ 拿 `id`。
3. 部署 npc 容器：`--net=host yisier1/npc -server=<npsAddr>:<bridgePort> -vkey=<vkey>`。
4. 落 `TunnelNode`。

**upsertRoute(app)**：`POST /index/addhost/`（或 `/index/edithost/`）
参数：`client_id` / `host=<子域名>.<subDomainHost>` / `scheme=http` / `target=127.0.0.1:<端口>` / `remark=<应用名>`。

**removeRoute(app)**：`POST /index/delhost/`。

**syncNode(hostId)**

0. 前置校验：`enabled=true`，否则直接返回。
1. 计算该节点全部隧道应用的**期望路由集合**。
2. 拉取实际路由 `POST /index/hostlist/`（按 `client_id` 过滤），比对后做**增/改/删**。
3. 若该节点已无隧道应用 → 删除 npc 容器 → `POST /client/del/` → 删除 `TunnelNode`。

**enableTunnel() / disableTunnel()（总开关，异步带日志）**

- `enableTunnel`：置 `enabled=true` → `deployNps()` → 对所有含隧道应用的节点 `ensureNode()` + `syncNode()` → 逐条 `upsertRoute()`（以应用当前配置为准，覆盖 nps 上的历史数据）。
- `disableTunnel`：置 `enabled=false` → 遍历 `TunnelNode` 停止并移除 npc 容器、清空 `containerId` → 停止并移除 `docker-admin-nps`、清空 `npsContainerId` → 保留 named volume 与 `vkey` / `npsClientId`。
- 两个方向都写 MDC 日志，前端用同一套 WebSocket 实时日志查看。

**cleanContainers(containerIds)（手动清理容器，异步带日志）**

1. 按 id 逐个处理：容器 running 则先 `stopContainerCmd`，再 `removeContainerCmd`。
2. 同步清空平台侧引用：命中 nps 则清 `TunnelSetting.npsContainerId`；命中 npc 则清对应 `TunnelNode.containerId`。
3. 单条失败不影响其他条目，最后汇总成功/失败。
4. **不触碰** named volume、`TunnelSetting` / `TunnelNode` 记录本身、应用的隧道字段。

**probeNode(hostId)（节点到 nps 的桥接连通性检测）**

1. 取该节点的 `npsAddr` 与候选端口（`bridgePort` 明文 / `tlsBridgePort` TLS）。
2. 从**该节点的 docker 环境**（`docker run --rm --net=host yisier1/npc` 或直接 TCP 探测）逐个做连通性测试。
3. 返回每个端口的结果 + 建议（哪个能通就用哪个；都不通则提示「该节点到 nps 的链路不可用，请换端口或改用 443+TLS」）。
4. 结果作为 `ensureNode()` 选择 `-tls_enable` 与端口的依据，并展示在节点表格里。

**listContainers()（容器列表，同步）**

遍历主机 → `listContainersCmd().withShowAll(true).withLabelFilter(docker-admin.role in (nps,npc))` → 兜底按容器名匹配 → 与 `npsContainerId` / `containerId` 比对，标记是否为「残留」。

**触发点**

- 应用：`save` / `updateBaseInfo` / `updateConfig` / `updateTunnel` / `rename` / `copyApp` / `delete` → `onAppChanged(oldHostId, newHostId)`（内部先判 `enabled`）。
- `TunnelSetting` 保存：conf 变化则重建 nps；再对所有含隧道应用的节点 `syncNode`。
- 总开关切换：见上。
- 启动时对账一次；可选加 Quartz `BaseJob`（同 `CleanImageJob`）定时对账。

### 10.3 前置条件

**docker-admin 后端需要能访问 nps 主机的 `webPort`**（API 调用）。`deployNps` 中做连通性校验并给出明确报错。

---

## 11. 接口

| 接口 | 权限 | 说明 |
|---|---|---|
| `admin/tunnel/info` | `tunnel:view` | 设置（密钥打码）+ nps 容器状态 + Web 后台地址 |
| `admin/tunnel/save` | `tunnel:save` | 保存设置（端口、域名后缀、镜像、conf 原文） |
| `admin/tunnel/deployNps` | `tunnel:deploy` | 重建并部署 nps，返回 logId |
| `admin/tunnel/toggle` | `tunnel:deploy` | 总开关：`enabled=true/false`，返回 logId |
| `admin/tunnel/containers` | `tunnel:view` | 各主机上的 nps / npc 容器列表（含「残留」标记） |
| `admin/tunnel/clean` | `tunnel:clean` | 清理选中容器（停止 + 删除 + 清空平台引用），返回 logId |
| `admin/tunnel/generateConf` | `tunnel:view` | 按表单字段渲染默认 nps.conf，供编辑器初始化 / 重置 |
| `admin/tunnel/conf` | `tunnel:view` | 返回当前 conf 原文与解析出的关键项 |
| `admin/tunnel/nodes` | `tunnel:view` | 各节点 npc 状态（客户端 id / 容器状态 / 路由数 / 最后同步） |
| `admin/tunnel/syncNode?hostId=` | `tunnel:deploy` | 手动重同步，返回 logId |
| `admin/tunnel/probeNode?hostId=` | `tunnel:view` | 节点到 nps 的桥接端口连通性检测（明文/TLS 各一） |
| `admin/tunnel/apps` | `tunnel:view` | 隧道列表（应用 / 子域名 / 访问地址） |
| `admin/app/updateTunnel` | `app:tunnel` | 开启关闭 + 子域名 + 端口 |
| `admin/app/get` | `app:view` | 增加 `{enabled, subdomain, port, innerUrl, tunnelUrl, error}` |
| `ws /admin/ws/tunnel-log/{logId}` | `tunnel:view` | 实时日志，复用 `SyncLogWebSocketHandler` |

---

## 12. 前端

页面由 Vite 插件按 `web/src/pages/**` 自动注册，`/tunnel` → `web/src/pages/tunnel/index.jsx`。

### 12.1 `pages/tunnel/index.jsx`

参考 `pages/image-sync/index.jsx` + `pages/registry/index.jsx`。

- **服务端设置 Card**
  - **顶部总开关 `Switch`**（`enabled`）：切换即调用 `admin/tunnel/toggle`，返回 logId 后打开日志抽屉；关闭状态下其余配置项只读
  - 主机：`FieldRemoteSelect url="admin/host/options"`
  - `npsAddr`、`httpProxyPort`（手动填写）、`bridgePort`、`webPort`、`subDomainHost`、镜像
  - Web 后台账号/密码（打码展示）
  - **nps.conf 原文编辑器**（`components/CodeMirrorEditor.jsx`）+「生成默认配置」
  - 按钮「保存」「部署/重启 nps」，`PermActions` + `perm='tunnel:deploy'`
  - nps 就绪后给出 Web 后台链接
- **nps 状态**：容器状态 Tag + 「查看日志」→ `Drawer` + `LogView url={'/admin/ws/tunnel-log/'+logId} websocket`
- **npc 节点表格**：主机 / 客户端 id / 容器状态 / 路由数 / 最后同步 / 「重新同步」「查看日志」
- **隧道列表**：应用 → 子域名 → 访问地址（可复制）
- **顶部 Alert**：提示 DNS 要求 `*.{subDomainHost}` → nps 主机
- **容器清理区**（`tunnel:clean` 权限）：列出各主机上的 nps / npc 容器（主机 / 名称 / 角色 / 状态 / 镜像 / 是否残留），提供「刷新」「清理选中」「清理残留」；`Modal.confirm` 二次确认，执行后返回 logId 并打开日志抽屉；文案说明「只删除容器，不影响数据卷与配置」

### 12.2 `pages/app/TunnelForm.jsx`（新增，挂在应用详情 Tab 内）

- `Switch` 开关；**总开关未启用或未配置时整体置灰**，并提示「隧道功能未启用，请先在【隧道】页面开启」。
- **端口**：`AutoComplete`，候选项来自 `admin/app/configMeta`，允许手输。
- **子域名**：`Input`，初始值为**应用名称规范化结果**，允许修改。
- 只读展示 **内网访问地址**（`http://<主机IP>:<端口>`）与 **隧道访问地址**（`http://<子域名>.<subDomainHost>[:端口]`），均可复制。

### 12.3 其它

- `pages/app/view.jsx`：新增 `隧道` Tab / Descriptions 项展示上述两条地址。
- `pages/app/index.jsx`：可选列，展示隧道地址或开关状态。

---

## 13. 菜单与权限

`src/main/resources/application-menu-docker.yml`，在 `tool` 下新增：

```yaml
tunnel:
  pid: tool
  name: 隧道
  path: /tunnel
  icon: ApiOutlined
  seq: 300
  perms:
    - {name: 查看, code: tunnel:view}
    - {name: 保存, code: tunnel:save}
    - {name: 部署, code: tunnel:deploy}
    - {name: 清理容器, code: tunnel:clean}
```

并在 `app` 菜单 perms 追加 `{name: 隧道, code: app:tunnel}`。

---

## 14. 安全与边界

| 项 | 处理 |
|---|---|
| `auth_key` / `web_password` / `auth_crypt_key` | 平台随机生成，字段 `WRITE_ONLY`，前端只回传打码值，日志不打印 |
| `vkey` | 同上（`WRITE_ONLY`） |
| nps Web 后台暴露 | `webPort` 默认 8081；建议提示改端口 / 加防火墙；`allow_user_login=false` 防止用 vkey 登录后台 |
| `httpProxyPort` 被占用 | 页面手动填写；部署前检查宿主机端口占用并提示 |
| 后端访问不到 nps Web 端口 | `deployNps` 连通性校验 + 明确报错 |
| 写 conf 到 named volume 是否生效 | 实现时先验证；不生效则切 bind mount 目录（兜底 A），或先启动生成再定点写回（兜底 B） |
| `auth_crypt_key` 长度 | 必须 **16 位**，否则 `/auth/getauthkey` 异常；生成时强校验 |
| nps 数据持久化 | `/conf` 用 named volume；`docker-admin-nps` 容器可随时重建 |
| 端口调整 | 平台写 conf + 重建容器即可，不涉及宿主机文件 |
| 端口冲突（host 网络） | host 网络下 Docker 不会替平台报「端口被占用」，必须**部署前主动检查**宿主机端口（已有容器端口 + 系统监听），冲突直接报错 |
| 端口冲突未检出时 | 启动后校验容器 running + `/auth/gettime/` 可达；失败则读容器日志定位 |
| 子域名冲突 / 非法 | 应用名称规范化 + 全局唯一 + DNS label 正则校验 |
| 节点拉不到 `yisier1/nps` / `yisier1/npc` | 镜像字段可配，改加速地址（如 `docker.m.daocloud.io/...`）；也可先用【镜像同步】把镜像预热到内网 registry |
| 应用无 `publicPort` / `networkMode=none` | 开关处前置校验并提示 |
| 手输端口不在应用映射中 | 允许但提示需确认主机上真实监听 |
| **跨境/受限链路只放行部分端口**（实测 80/443） | 桥接默认走 `tlsBridgePort=443` + `tls_enable=true`；提供 `probeNode` 连通性检测，自动选可用端口 |
| **端口冲突导致 nps fatal + 反复重启** | 部署前查端口占用；启动后校验容器 running 且 `gettime` 可达；读日志给出可读报错 |
| 未匹配域名返回空 200 | 前端/文档说明这不是 404；排障时以「是否有 `Server` 头」区分 |
| 节点拉不到 `yisier1/*` 镜像 | 镜像源可配（`docker.1ms.run` 实测可用）；或用能直连 Docker Hub 的节点做【镜像同步】 |
| 域名解析是否依赖 httpProxy 隧道 | 理论上仅域名解析即可完成 Host 路由；实现时实测，若必需则每个应用再建一条 `port=0` 的 httpProxy 隧道并在 `remark` 上关联 |
| 节点删除 / 应用全部关闭 | 清理路由 → 删除 npc 容器 → 删除 nps 客户端 |
| 残留容器（节点已删 / 部署中断 / 手工改动） | 容器清理区按标签扫描并列出，标记「残留」，支持手动清理；只删容器，不动数据卷与配置 |
| 清理后误以为功能被停用 | 文案明确「只删除容器」；重新部署即可恢复，数据仍在 named volume |
| 总开关 / 部署 / 清理耗时较长 | 走异步任务 + logId + WebSocket 日志；按钮 loading 直到日志流关闭 |

---

## 15. 实施阶段与验收

### P1 服务端

- 范围：`TunnelSetting` 实体/DAO、`NpsConfManager`（生成 + cp 写入 + 解析）、`NpsApiClient`（鉴权打通）、`TunnelService.deployNps` + `enableTunnel` / `disableTunnel` + `listContainers` / `cleanContainers` / `probeNode`、隧道页面服务端部分（含总开关、容器清理区、节点连通性检测）、隧道日志 WS + 菜单权限。
- 验收：
  - 指定主机后可部署 nps，conf 由平台生成并成功写入容器（回读一致）；
  - **端口占用能提前拦截**；启动后校验 running + `gettime`，异常时给出可读报错（不陷入重启循环）；
  - **总开关**：关闭后 nps 容器被移除、应用侧开关置灰；重新开启后自动重建容器并按应用配置恢复路由；
  - **容器清理**：能列出各主机的 nps/npc 容器（含残留）；清理后容器消失、平台侧 `containerId` 引用清空；数据卷与 `TunnelSetting` / `TunnelNode` 记录不受影响，重新部署可恢复；
  - Web 后台可用平台账号登录；
  - `/auth/gettime/` 连通，`md5(auth_key+ts)` 鉴权通过（如能成功调 `/client/list/`）；
  - 页面编辑 conf 保存后重启生效；
  - 日志可通过 WebSocket 实时查看。

### P2 应用侧

- 范围：节点客户端注册 + npc 部署、App 隧道字段、应用页开关（AutoComplete 选/输端口）、域名解析 CRUD、内网/隧道两条地址展示。
- 验收：
  - 两台节点各一个应用开隧道 → 仅出现两个 npc 容器与两条域名解析；
  - **改子域名只调用 API，不重建任何容器**；
  - 子域名默认等于应用名称（规范化后）；
  - 关闭全部隧道 → 路由、客户端记录、npc 容器被清理。

### P3 完善

- 对账 Job（Quartz `BaseJob`）、旧容器清理、文档；
- 可选：HTTPS（域名解析里配 `cert_file_path` / `key_file_path` 或 `AutoHttps`）；
- 可选：TCP/UDP 端口型隧道。

---

## 16. 实测结果（2026-09-30）

在 **香港 `103.38.81.109`（nps 服务端）＋ docker1（npc 客户端）** 上按最终形态完整跑通：

```
公网用户 ──► 香港:80 (Host: test.soulsoup.cn)
             └ nps 域名解析匹配 ──► TLS 桥接 443 ──► docker1 npc
                                                      └► 127.0.0.1:18090 (nginx)
```

| # | 验证项 | 结果 |
|---|---|---|
| 1 | `docker cp` 写入 named volume 下的 `/conf/nps.conf` | ✅ `diff` 为 `CONF-SAME`，nps 未重新生成，日志无随机凭据 |
| 2 | 平台生成的 conf 生效 | ✅ 按 conf 起 8024 / 80 / 8081；`tls_enable` 生效；`https_proxy_port=` 留空 → 443 不监听 https |
| 3 | scratch 型镜像 | ✅ 无 shell，`docker exec ls` 报 not found → 只能 `docker cp` |
| 4 | API 鉴权 | ✅ `gettime`/`getauthkey` 可用；无鉴权 302；`md5(auth_key+ts)` 通过 |
| 5 | 客户端管理 | ✅ `/client/add/` → `id`，`/client/list/` 正常 |
| 6 | npc 无配置连接 | ✅ `-server=…:443 -vkey=… -tls_enable=true` 一次连通，`IsConnect: true` |
| 7 | **域名解析单独即可路由** | ✅ 未建任何 httpProxy 隧道，`Host: test.soulsoup.cn` 直达客户端 nginx（`Server: nginx/1.31.4`） |
| 8 | `Host` 带端口 | ✅ `Host: xxx:80` 也能匹配 |
| 9 | 未匹配域名 | ⚠️ 返回**空 200**（`Content-Length: 0`），不是 404 |
| 10 | 端口冲突 | ⚠️ nps **fatal 退出** + `restart=always` 反复拉起；日志有 `bind: address already in use` |
| 11 | 跨境链路端口 | ⚠️ docker1 出口 `222.85.139.63` 到香港**只通 80/443**；8024/8081/17924/8443/8080/8880/2052/2082/2096/12345/9000/7000/6666/53 全被 RST。→ 桥接改 **443 + TLS 后成功** |
| 12 | 镜像获取 | ⚠️ docker1 直连 Docker Hub 超时；DaoCloud 白名单不含 `yisier1`；**`docker.1ms.run` 可拉**；香港可直连 Docker Hub |
| 13 | nps 主机入向 | ✅ 香港无 firewalld、无 iptables 限制，公网 80 可达 |

**待实现时确认**

1. 容器清理：按标签 `docker-admin.role` 能否稳定扫到所有 nps/npc 容器（含仅按名字创建的旧容器）；容器已停但未删时 `removeContainerCmd` 是否需要先 `stop`。
2. 总开关「关闭 → 开启」的恢复路径：确认 named volume 中的 `nps.conf`、客户端与域名解析数据能原样复用，无需重新生成密钥。

**衍生出的平台能力（建议纳入 P1/P2）**

- 节点桥接连通性检测（`probeNode`）：跨境节点必须能自动发现「只能走 443+TLS」。
- 部署前端口占用检查 + 启动后 `running`/`gettime` 校验（否则会陷入重启循环且页面显示不正常）。
- 镜像源可配 + 优先选能直连 Docker Hub 的节点做【镜像同步】的同步主机。
