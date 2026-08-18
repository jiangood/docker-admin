# 配置参考

> open-admin 全部配置项说明。业务项目在 `application.yml` 中覆盖即可。

## 系统配置 (`sys.*` in `application.yml`)

| 配置 | 说明 | 默认值 |
|------|------|--------|
| `sys.title` | 系统标题（必填） | 管理系统 |
| `sys.file.store-type` | 文件存储 (`LOCAL`/`MINIO`) | LOCAL |
| `sys.file.upload-path` | 本地上传路径 | /home/files |
| `sys.file.clean-unclaimed-minutes` | 未认领文件自动清理时间（分钟） | 120 |
| `sys.file.minio.*` | MinIO 对象存储配置 | — |
| `sys.session-idle-time` | Session 超时（分钟） | 180 |
| `sys.job-enable` | 定时任务开关 | true |

> 框架启动时会自动同步 `.opencode/skills/` 与 `docs/open-admin/` 到业务项目根目录（内容比对，无变更不写入），并在根目录生成 `AGENTS.md`（仅当不存在时）。无需配置；生产 jar 部署（向上查找无 `pom.xml`）时跳过同步，不写入任何文件。

## 文件存储

通过 `sys.file.store-type` 选择后端（`LOCAL` / `MINIO`）：

- `LOCAL` — 本地文件系统，保存到 `sys.file.upload-path`，按 `public/`、`private/` 子目录区分可见性
- `MINIO` — MinIO 对象存储（官方 `io.minio:minio` 客户端），配置 `sys.file.minio.{endpoint,accessKey,secretKey,bucketName}`；`endpoint` 需带协议前缀（如 `http://localhost:9000`），bucket 需提前创建

文件 `objectName` 带可见性前缀（如 `public/202607/xxx.jpg` / `private/202607/xxx.pdf`），本地磁盘路径 = `sys.file.upload-path` + `objectName`，与 URL `/file/{objectName}` 完全一致。

## 未认领文件自动清理

上传文件默认标记为未认领 (`joinTable=null`)，仅在业务数据保存后通过 `SysFileService.claim(entity)`（实体文件字段打 `@FileField` 注解）设置 `joinTable/joinId` 后方变为已认领。未认领的文件超过期限后由 Quartz 定时任务 `CleanTempFileJob` 自动删除。

- **确认时机**：业务实体对文件字段打 `@FileField` 注解，Controller 的 create/update 中，update 时先 `sysFileService.unclaim(old)` 取消认领旧引用，save 后再 `sysFileService.claim(entity)` 认领新引用（详见 development.md「文件认领」）
- **清理配置**：`sys.file.clean-unclaimed-minutes=120`（默认 2 小时）
- **清理频率**：每 10 分钟执行一次（cron `0 */10 * * * ?`）
- **孤儿文件**：业务数据删除后残留的已认领文件，同一任务会检查对应业务表（主键列约定为 `id`）中记录是否已不存在，不存在则一并清理

完整配置项见 `SystemProperties.java`。

## Servlet Context-Path

| 位置 | 配置 |
|------|------|
| 后端 `application.yml` | `server.servlet.context-path` |
| 前端 `web/.env` | `VITE_SERVER_SERVLET_CONTEXT_PATH` |

前端 `HttpClient` 自动带上 context-path 前缀；硬编码 URL 用 `UrlUtils.contextPath(path)` 拼接。

## 主题定制

主题颜色默认值内置框架（`#1961AC` 主色等），业务项目**零配置**即可获得默认主题。需要定制时，在入口组件 `<Layouts>` 传入 `colors` prop 覆盖：

```jsx
<Layouts colors={{
    colorPrimary: '#1961AC',
    colorSuccess: '#52c41a',
    colorWarning: '#faad14',
    colorError: '#ff4d4f',
    colorBgLayout: '#f5f5f5',
}}/>
```

`colors` 为可选字段，未传的项使用框架默认值。菜单/标签栏等处的 `--primary-color` CSS 变量会随主题自动同步。
