# 隧道功能 · frp 方案

> 状态：**已实现**。当前形态为「平台用 docker-java 部署 frps 服务端与各节点的 frpc 客户端，
> 隧道（子域名 → 应用端口）落库，新增 / 修改 / 删除隧道时自动重写 frpc 配置并重建 frpc 容器」。
>
> 历史：本功能最初按 nps 设计并实现（见 git 历史 `bc87f2b`），因「手工部署容器 + 手工登记节点 +
> 直连 nps WebAPI 维护解析」过于繁琐，改为 frp + 平台托管的容器镜像方案。相关 nps 代码
> （`NpsApiClient` / `NpsConfManager`）已移除。

## 1. 形态总览

| 事项 | 做法 |
|---|---|
| 服务端 frps | 在设置里选择一台平台主机，点【部署 / 重建 frps】，平台拉取 `ghcr.io/jiangood/frps` 并部署 `docker-admin-frps` 容器 |
| 客户端 frpc | 「节点」= 平台已有主机（`Host`）+ 一个 `TunnelNode`；平台在节点主机上部署 `docker-admin-frpc-<nodeId>` 容器 |
| 配置下发 | 平台生成 `frps.toml` / `frpc.toml`，经 `docker cp` 写入容器 `/etc/frp/`，再启动容器 |
| 隧道 | 落库的 `Tunnel`（子域名 → 应用主机端口），对应 frpc.toml 里的一段 `[[proxies]]` |
| 改动生效 | 新增 / 修改 / 删除隧道 → 重新生成该节点的 `frpc.toml` → 重建该 frpc 容器（自动，无需手点） |
| 改设置 | 改了 token / 连接地址 / 端口后，用【重建全部 frpc】统一生效 |
| 实时日志 | 所有部署 / 移除动作走异步任务，返回 `logId`，前端经 `/admin/ws/tunnel-log/{logId}` 实时查看 |

不再需要：手工复制脚本到宿主机、在 nps 后台登记 vkey、直连 nps WebAPI 维护域名解析。

## 2. 拓扑

```
                       访客
                        │ http://blog.tunnel.example.com
                        ▼  (*.tunnel.example.com 解析到 frps 主机)
   ┌────────────────────────────────────────────────────┐
   │ frps 主机（设置里选定）                              │
   │ 容器 docker-admin-frps   --net=host                 │
   │  监听 <bindPort=7000> / <vhostHttpPort=80>           │
   │  /etc/frp/frps.toml  ← docker cp                    │
   └──────┬─────────────────────────────────────┬───────┘
          │ frpc 连接（token 鉴权，可开 TLS）      │
   ┌──────▼──────────────┐            ┌─────────▼─────────┐
   │ 节点 A               │            │ 节点 B             │
   │ docker-admin-frpc-*  │            │ docker-admin-frpc-*│
   │  --net=host          │            │  --net=host        │
   │  /etc/frp/frpc.toml  │            │  /etc/frp/frpc.toml│
   │  [[proxies]]         │            │  [[proxies]]       │
   │   blog → 127.0.0.1…  │            │   api  → 192.168…  │
   └──────────────────────┘            └────────────────────┘
```

## 3. 容器约定（镜像契约）

镜像由使用者自行制作，平台按下述约定使用：

| 镜像 | 容器名 | 配置路径 | 网络 | 说明 |
|---|---|---|---|---|
| `ghcr.io/jiangood/frps` | `docker-admin-frps` | `/etc/frp/frps.toml` | `host` | 容器启动即按该文件运行 frps |
| `ghcr.io/jiangood/frpc` | `docker-admin-frpc-<nodeId>` | `/etc/frp/frpc.toml` | `host` | 容器启动即按该文件运行 frpc |

- 平台流程：拉取镜像 → 删除旧容器 → 创建容器（host 网络、`--restart=always`、日志 `max-size=50m`）
  → `docker cp` 写入配置 → 启动。
- 因此镜像只需保证「启动时读取 `/etc/frp/*.toml`」，无需内置默认配置、无需接收命令行参数。
- 镜像已发布并实测（[jiangood/docker-frp](https://github.com/jiangood/docker-frp)，frp **0.71.0**，`alpine` 基础镜像、默认 root）：
  `ENTRYPOINT ["frps"|"frpc"]` + `CMD ["-c","/etc/frp/*.toml"]`，镜像内置 `/etc/frp/*.toml`；
  平台的 `docker cp` 会在启动前覆盖它。未指定 tag 时用 `latest`，需要固定版本可写 `ghcr.io/jiangood/frps:0.71.0`。
- `host` 网络：frps 的 `bindPort` / `vhostHttpPort` 直接绑在宿主机上；frpc 侧 `localIP=127.0.0.1`
  能直达同宿主机上的应用端口，也能填其它地址（如 `192.168.x.x`）。

## 4. frp 配置（由平台生成）

`frps.toml`：

```toml
bindPort = 7000
vhostHTTPPort = 80
subDomainHost = "tunnel.example.com"

[auth]
method = "token"
token = "<平台生成 / 设置的 token>"

[log]
to = "console"
level = "info"
```

`frpc.toml`（每个节点一份，`proxies` 来自该节点的全部隧道）：

```toml
serverAddr = "1.2.3.4"
serverPort = 7000

[auth]
method = "token"
token = "<同上>"

[transport.tls]
enable = true

[[proxies]]
name = "blog"
type = "http"
localIP = "1.2.3.4"
localPort = 18080
subdomain = "blog"
```

配置由 `FrpConfigManager` 生成，不接受页面手改；`frps.toml` 预览里 token 会掩码。

## 5. 数据模型

```java
// 全局唯一（取最新一条，用法同 Registry）
@Entity @Table(name = "t_tunnel_setting")
class TunnelSetting extends BaseEntity {
    String  frpsAddr;              // 客户端与访客访问的地址（公网 IP / 域名）
    Integer bindPort;              // 默认 7000
    Integer vhostHttpPort;         // 默认 80
    String  subDomainHost;         // tunnel.example.com
    @JsonProperty(WRITE_ONLY) String authToken;   // frps / frpc 共用
    Boolean transportTls;          // frpc 是否 TLS 连接，默认 true
    String  frpsImage;             // ghcr.io/jiangood/frps（无页面入口，可用默认值）
    String  frpcImage;             // ghcr.io/jiangood/frpc
    String  frpsContainerId;       // 平台最后部署的 frps 容器（自行部署时为空）
    String  frpsStatus;            // ok / unreachable
    LocalDateTime lastCheckTime;
    String  lastError;
}
// 注：frps 部署是无状态的，主机在【部署 frps】页签即时选择，不落库

// 节点 = 平台主机 + 一个 frpc 容器
@Entity @Table(name = "t_tunnel_node")
class TunnelNode extends BaseEntity {
    @Column(unique = true) String name;
    @ManyToOne @NotNull Host host;
    String  remark;
    String  containerId;
    LocalDateTime lastDeployTime;
    String  lastError;
}

// 隧道：子域名 → 应用主机端口
@Entity @Table(name = "t_tunnel")
class Tunnel extends BaseEntity {
    @Column(unique = true) String name;   // 代理名，取子域名
    @ManyToOne TunnelNode node;
    @ManyToOne App app;
    String  subdomain;                    // 全局唯一
    String  localIp;                      // 目标地址（应用主机地址）
    Integer localPort;                    // 目标端口（bridge 取主机映射端口 / host 取容器端口）
    String  scheme;                       // http
    String  remark;
}
```

> 与 nps 版不同：隧道**落库**（nps 版解析直接存在 nps 上）。因为 frpc 配置由平台推导，隧道必须持久化。

## 6. 后端实现

| 文件 | 作用 |
|---|---|
| `entity/TunnelSetting.java`、`entity/TunnelNode.java`、`entity/Tunnel.java` | 实体 |
| `dao/TunnelSettingRepository.java`、`dao/TunnelNodeRepository.java`、`dao/TunnelRepository.java` | 仓储 |
| `service/FrpConfigManager.java` | 生成 `frps.toml` / `frpc.toml`，token 掩码 |
| `service/TunnelDeployService.java` | docker-java 部署 frps / frpc：拉镜像 → 建容器 → `docker cp` 写配置 → 启动；按标签 `docker-admin.role` / `docker-admin.node` 定位与清理容器。`@Async` + `MDC logFileId` 输出日志 |
| `service/TunnelService.java` | 配置保存、部署参数保存、节点与隧道的增删改查、子域名校验、frps 状态探测；隧道变更后触发对应节点 frpc 重建 |
| `controller/TunnelController.java` | 隧道页面接口；部署类动作返回 `logId` |
| `websocket/TunnelLogHandshakeInterceptor.java` + `websocket/WebSocketConfig.java` | 注册 `/admin/ws/tunnel-log/{logId}`，复用 `SyncLogWebSocketHandler`，校验 `tunnel:view` |
| `resources/logback-spring.xml` | 给 `TunnelDeployService` 挂 `docker-log-appender` |
| `resources/application-menu-docker.yml` | 隧道菜单与权限 |

### 异步动作（`TunnelDeployService.run`）

| action | 行为 |
|---|---|
| `deployFrps` | 部署 / 重建 frps（强制拉镜像） |
| `removeFrps` | 停止并删除 frps 容器 |
| `deployFrpc` | 部署 / 重建单个节点的 frpc（强制拉镜像） |
| `redeployFrpc` | 隧道变更后自动重建（镜像已存在则跳过拉取） |
| `removeFrpc` | 停止并删除某节点的 frpc 容器 |
| `deployAllFrpc` | 重建全部节点的 frpc |
| `deleteNode` | 删除节点：移除其 frpc 容器 + 删除该节点及其全部隧道 |

## 7. 接口

| 接口 | 权限 | 说明 |
|---|---|---|
| `admin/tunnel/info` | `tunnel:view` | 设置（token 不回传）+ frps.toml 预览（token 掩码） |
| `admin/tunnel/save` | `tunnel:save` | 保存隧道行为配置（连接地址 / 域名后缀 / 端口 / token / 加密） |
| `admin/tunnel/saveDeployFrpc` | `tunnel:save` | 保存 frpc 部署参数（客户端镜像），与配置互不覆盖 |
| `admin/tunnel/frpsStatus` | `tunnel:view` | 实时探测指定主机 `hostId` 上 frps 容器状态并记录（无状态） |
| `admin/tunnel/deployFrps` / `removeFrps` | `tunnel:save` | 按 `hostId` 即时部署 / 移除 frps（不保存主机）；返回 logId |
| `admin/tunnel/nodes` | `tunnel:view` | 节点列表（含主机、frpc 容器状态、隧道数） |
| `admin/tunnel/saveNode` / `deleteNode` | `tunnel:save` | 新增 / 修改节点；删除节点返回 logId |
| `admin/tunnel/deployFrpc` / `removeFrpc` / `rebuildAllFrpc` | `tunnel:save` | 返回 logId |
| `admin/tunnel/tunnels` | `tunnel:view` | 隧道列表 |
| `admin/tunnel/addTunnel` / `editTunnel` / `deleteTunnel` | `tunnel:route` | 变更后自动重建对应节点 frpc，返回 logId |
| `admin/tunnel/appMeta` | `tunnel:view` | 隧道表单元数据：应用主机地址、可选端口、默认子域名 |

## 8. 前端

`web/src/pages/tunnel/index.jsx`（`/tunnel`），配置与部署分离为五个 Tab：

- **概览卡**：frps 状态（可按最近选择的主机刷新）、连接地址、域名后缀、端口（只读展示）。

配置类 Tab（只保存配置，不下发容器）：

- **服务端（frps）**：连接地址 / 域名后缀 / token / 端口 / 传输加密（端口等收进「高级设置」折叠）；
  操作：保存；底部展示 `frps.toml` 预览。
- **节点（frpc）**：表格（名称 / 主机 / 隧道数 / 备注）+ 新增 / 编辑 / 删除。
- **隧道**：表格（名称 / 访问地址 / 节点 / 应用 / 目标 / 备注）+ 新增 / 编辑 / 删除；
  新增时选节点 + 应用 + 端口 + 子域名，脚本自动带出目标地址与默认子域名。

部署类 Tab（配置保存后手动下发，属辅助功能）：

- **部署 frps**：无状态工具，不保存参数；选主机后【检测】/【部署·重建 frps】/【移除 frps】。
- **部署 frpc**：frpc 镜像 + 保存部署设置；重建全部 frpc + 各节点 frpc 部署 / 移除。

所有部署类动作会打开「隧道部署日志」抽屉（WebSocket 实时日志），关闭后自动刷新页面数据。

## 9. 菜单与权限

`src/main/resources/application-menu-docker.yml`：

```yaml
tunnel:
  pid: tool
  name: 隧道管理
  path: /tunnel
  icon: ApiOutlined
  seq: 300
  perms:
    - {name: 查看, code: tunnel:view}
    - {name: 配置与节点, code: tunnel:save}   # 保存设置 / 部署与移除容器 / 维护节点
    - {name: 隧道, code: tunnel:route}         # 隧道增删改
```

## 10. 安全与边界

| 项 | 处理 |
|---|---|
| `authToken` | 字段 `WRITE_ONLY`，接口不回传；页面留空表示不修改 |
| 子域名 | DNS label 正则校验 + 全局唯一；默认取应用名称规范化 |
| bridge 模式无主机端口映射 | `resolveHostPort` 前置校验并提示先填主机端口 |
| 端口冲突（host 网络） | frps 直接绑宿主机端口；部署日志里会体现 `bind: address already in use`，按需更换端口 |
| 节点拉不到镜像 | 镜像地址可配；拉取失败但本地已有镜像时降级使用（自动重建时默认不拉取） |
| 隧道目标不可达 | 目标取应用主机地址；需保证该节点的 frpc 能访问 |
| 删除节点 | 连同该节点的隧道与 frpc 容器一起清理（二次确认） |
| 改设置后未生效 | 页面提示用【重建全部 frpc】统一重建 |
| 残留容器 | 按标签 `docker-admin.role` / `docker-admin.node` 定位，重建时先清理同名 / 同标签容器 |
