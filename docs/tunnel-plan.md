# 隧道功能 · http-tunnel 方案

状态：已实现（迁移到「客户端管理 API」方案；不再使用服务端管理 API，也不再按主机网络分组）。

## 1. 定位

隧道能力由独立项目 **http-tunnel**（<https://github.com/jiangood/http-tunnel>，Rust 写的 HTTP 反向代理，NAT 穿透）提供。

平台**不做任何容器部署、不生成配置文件**，只作为 http-tunnel **客户端管理 API** 的前端：

- **服务端**（server）：用户自行部署，持有一份完整配置（`server.toml`），对外提供客户端端口与 HTTP 端口。
- **客户端**（client）：用户运行在各业务主机上，通过 `--api-port` 暴露自身的隧道管理 API：
  `http-tunnel client --remote <服务端> --name <名> --token <令牌> --api-port <管理端口>`。
- **平台**：登记客户端（名称、令牌、API 地址、域名），通过客户端管理 API 维护「隧道（域名 → 目标地址）」；
  应用在详情页「隧道」标签里**显式选择客户端**，不再按主机自动匹配。

参考部署（现有环境）：

| 项 | 值 |
| --- | --- |
| 服务端 IP | `103.38.81.109` |
| 客户端端口 | `2333`（`server_port`） |
| HTTP 访客端口 | `80`（`http_port`） |
| 服务端管理 API | `api_port`（如 `2335`，平台不再使用，可不开） |
| 客户端管理 API | `--api-port`（如 `2336`，平台调用它，需可达） |
| 客户端令牌 | 客户端启动的 `--token`，同时是管理 API 的 Bearer 令牌 |

> **http-tunnel v0.9.0+**：客户端新增可选的自身隧道管理 API（启动加 `--api-port`），
> 所有路由要求 `Authorization: Bearer <客户端令牌>`，服务端仍是唯一事实来源；
> 服务端 `server.toml` 的端口为纯整数（`server_port` / `http_port` / `api_port`）并监听 `0.0.0.0`。

## 2. 拓扑

```
访客 ──Host: app.example.com──► 服务端:80 ──┐
                                          │ 配置/控制/数据通道（单端口 2333）
                                          ▼
                                    http-tunnel client（业务主机）
                                          ▲
                          管理 API :2336  │  REST（Bearer 客户端令牌）
                                          │
                                    docker-admin 平台
```

- 访客请求由服务端按 **Host 头**路由到对应隧道，域名在服务端全局唯一，支持 `*.example.com` 泛域名。
- 平台只与客户端管理 API 通信；客户端把改动转发给服务端，服务端验证、落盘并推送给在线客户端。

## 3. 平台用到的管理 API（客户端）

认证：`Authorization: Bearer <客户端令牌>`；错误体为 `{"error":"..."}`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/status` | 客户端身份、连接状态与隧道数 |
| GET | `/api/tunnels` | 隧道列表（`name` 为域名，`local_addr` 为目标地址） |
| PUT | `/api/tunnels/{domain}` | 新增/修改隧道 `{local_addr}` |
| DELETE | `/api/tunnels/{domain}` | 删除隧道 |

改动的生效流程：客户端转发给服务端 → 服务端校验并原子落盘 `server.toml` → 推送给在线客户端。
客户端必须连接到服务端才能接受改动（没有离线队列）。

## 4. 关联模型

- **主机**（`Host`）：只用于容器/部署，**不再有网络分组**。
- **隧道客户端**（`TunnelClient`）：`name` + `token` + `apiUrl` + `domain`；应用在详情页显式选择。
- **应用**：保存开关、域名前缀、端口与所选客户端。

应用开启隧道时的推导：

```
选择客户端 → 完整域名 = 应用域名前缀 + "." + 客户端域名   （如 app.example.com）
目标地址   = 127.0.0.1 + 应用映射到主机侧的端口
```

因此应用只需选择一次客户端；客户端部署在哪台主机、服务端在哪，都与应用无关。

## 5. 数据模型

| 表 | 实体 | 说明 |
| --- | --- | --- |
| `t_host` | `Host` | 移除 `networkGroup`（网络分组） |
| `t_tunnel_client` | `TunnelClient` | 平台纳管的客户端：`name`（唯一，对应 `--name`）、`token`（WRITE_ONLY，对应 `--token`）、`apiUrl`（客户端管理 API 地址）、`domain`（域名）、`remark` |
| `t_app` | `App` | 隧道字段：`tunnelEnabled`（开关）、`tunnelPrefix`（域名前缀）、`tunnelPort`（暴露端口）、`tunnelClient`（所选客户端，ManyToOne） |

隧道本身不落库（服务端即唯一事实来源）。

> 旧表 `t_tunnel_setting` 已废弃，不再使用（无迁移脚本，遗留表可手工删除）。

## 6. 后端文件

| 文件 | 职责 |
| --- | --- |
| `entity/TunnelClient.java` | 隧道客户端实体（`Host` 移除 `networkGroup`，`App` 增加 `tunnelClient`） |
| `dao/TunnelClientRepository.java` | 客户端仓储 |
| `service/HttpTunnelApiClient.java` | 客户端管理 API 客户端（`java.net.http.HttpClient`） |
| `service/TunnelService.java` | 客户端 CRUD（仅本地）、隧道读写（走客户端 API）、应用隧道（`appTunnelMeta` / `saveAppTunnel` / `removeAppTunnel`） |
| `controller/TunnelController.java` | 客户端与隧道接口，权限 `tunnel:view` / `tunnel:save` |
| `controller/AppController.java` | 应用隧道接口 `tunnelMeta` / `updateTunnel`，权限 `app:tunnel` |
| `service/AppService.java` | 删除应用时清理其隧道 |

所有操作都是**同步** HTTP 调用，没有异步部署、没有 `logId`、没有 WebSocket 日志，也没有启动命令生成。

**注意**：迁移后不再有 `TunnelSetting` / `TunnelSettingRepository`，也没有服务端管理 API 调用。

## 7. 接口

| 接口 | 权限 | 说明 |
| --- | --- | --- |
| `admin/tunnel/clients` | `tunnel:view` | 客户端列表（本地登记 + 实时状态） |
| `admin/tunnel/saveClient` | `tunnel:save` | 新增/修改客户端（仅本地登记） |
| `admin/tunnel/deleteClient` | `tunnel:save` | 删除客户端（尽力删除其隧道并关闭引用它的应用隧道） |
| `admin/tunnel/testClient` | `tunnel:view` | 测试与某个客户端管理 API 的连通性 |
| `admin/tunnel/tunnels` | `tunnel:view` | 全部隧道（逐个客户端聚合，只读） |
| `admin/app/tunnelMeta` | `app:view` | 应用隧道元数据（前缀、可选客户端、端口、所选客户端域名） |
| `admin/app/updateTunnel` | `app:tunnel` | 应用隧道开关/客户端/前缀/端口（同步到客户端） |

## 8. 前端

**隧道管理**（`web/src/pages/tunnel/index.jsx`，辅助工具 - 隧道管理）：

- **客户端**：列表（名称/API 地址/域名/在线状态/隧道数/令牌/备注）；新增、编辑、测试连接、删除。
  表单字段：名称、API 地址（`--api-port`）、令牌、域名、备注。
- **隧道**：只读列表（访问地址/域名/客户端/目标地址）。隧道由应用详情页的「隧道」标签创建与删除。

**主机**（`web/src/pages/host/index.jsx`）：移除「网络分组」。

**应用隧道**（`web/src/pages/app/TunnelForm.jsx`，应用详情页 - 隧道标签）：

- 客户端：下拉选择已登记的客户端（必选）。
- 开关：开启 / 关闭隧道。
- 域名前缀：默认取应用名称（规范化），完整域名 = `前缀.客户端域名`，实时预览。
- 应用端口：选择要暴露的应用端口（取主机侧端口）。
- 保存即通过客户端管理 API 写入/删除隧道；关闭、换客户端、改前缀或删除应用时自动清理旧映射。

## 9. 菜单与权限

`tunnel` 菜单挂在「辅助工具」下，路径 `/tunnel`：`tunnel:view`（查看）、`tunnel:save`（客户端）。隧道的创建/删除归于应用详情页的 `app:tunnel`。

`app` 菜单含 `app:tunnel`（隧道），控制应用详情页隧道标签的保存。

## 10. 安全与边界

- 客户端 `token` 为 `WRITE_ONLY`，不回传前端；页面用掩码占位，留空表示不修改。
- 客户端令牌既是服务端认证，也是客户端管理 API 的 Bearer 令牌；泄露即等同客户端身份，请妥善保管。
- 客户端管理 API 默认是明文 HTTP 并监听 `0.0.0.0`，建议限制来源或加反向代理 / VPN。
- 删除客户端会先尽力删除其隧道，并关闭引用它的应用隧道、解除引用，然后删除本地登记；服务端残留需自行清理。
- 客户端必须在线（已连接到服务端）才能接受隧道改动。
- 换客户端或改前缀时按上一次的「前缀 + 客户端域名」重建域名去删除；若期间改过客户端域名，可能残留旧映射。
- 域名做基本格式校验，新增走 PUT 幂等；服务端会拒绝重复域名（409）。
- 平台不校验也不存储隧道的访问凭据（http-tunnel 本身不做每隧道鉴权），如需鉴权请前置网关。
