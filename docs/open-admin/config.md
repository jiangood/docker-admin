# 配置参考

> open-admin 全部配置项说明。业务项目在 `application.yml` 中覆盖即可。

## 系统配置 (`sys.*` in `application.yml`)

| 配置 | 说明 | 默认值 |
|------|------|--------|
| `sys.title` | 系统标题（必填） | 管理系统 |
| `sys.file.store-type` | 文件存储 (`LOCAL`/`MINIO`) | LOCAL |
| `sys.file.upload-path` | 本地上传路径 | /home/files |
| `sys.file.clean-temp-minutes` | 临时文件自动清理时间（分钟） | 120 |
| `sys.file.minio.*` | MinIO 对象存储配置 | — |
| `sys.session-idle-time` | Session 超时（分钟） | 180 |
| `sys.job-enable` | 定时任务开关 | true |
| `sys.codegen.backend-dir` | 代码生成后端源码根目录（相对项目根） | src/main/java |
| `sys.codegen.resource-dir` | 代码生成资源目录（菜单 YAML 输出位置） | src/main/resources |
| `sys.codegen.frontend-dir` | 代码生成前端页面根目录 | web/src/pages |
| `sys.codegen.frontend-import` | 生成页面引入框架组件的模块名；留空自动识别（框架仓库用相对路径，业务项目用 `@jiangood/open-admin`） | 空 |

> 框架的 `.opencode/skills/` 与 `docs/open-admin/` 由 `oa-upgrade-docs` skill 从框架 GitHub Release 下载 `framework-files.zip` 同步到业务项目根目录（内容比对，无变更不写入）。无需配置。

## 数据库

框架自身（示例应用 / 独立运行 / Docker 镜像）默认使用**内置 H2 文件数据库**（MySQL 兼容模式），零外部依赖、开箱即用：

| 配置 | 说明 | 默认 |
|------|------|------|
| `db_path` | H2 数据库文件路径（不含扩展名） | /data/db/open-admin |
| `spring.jpa.hibernate.ddl-auto` | 建表策略 | update（自动建表/更新） |

- 数据文件、上传文件、日志均在 `/data` 下，容器部署请持久化该目录（见 `docker-compose/docker-compose.yml`）
- 本地开发可用 `-Ddb_path=./data/db/open-admin` 覆盖
- 切换 MySQL：`--spring.profiles.active=mysql`，连接参数见 `application-mysql.yml` 中的 `db_*` 变量
- 业务项目自行配置数据源；推荐同样默认 H2、可选 MySQL（参考 open-admin-example）
- 建表由 JPA 完成，种子数据由 `SeedDataInitializer` 幂等写入（不覆盖已有记录）

## 文件存储

通过 `sys.file.store-type` 选择后端（`LOCAL` / `MINIO`）：

- `LOCAL` — 本地文件系统，保存到 `sys.file.upload-path`，按 `public/`、`private/` 子目录区分可见性
- `MINIO` — MinIO 对象存储（官方 `io.minio:minio` 客户端），配置 `sys.file.minio.{endpoint,accessKey,secretKey,bucketName}`；`endpoint` 需带协议前缀（如 `http://localhost:9000`），bucket 需提前创建。业务项目需自行添加 `io.minio:minio` 与 `com.squareup.okhttp3:okhttp-jvm` 依赖（minio 9.x 的 Maven 元数据引用的 okhttp 为空壳，实际类在 okhttp-jvm）

文件 `objectName` 带可见性前缀（如 `public/202607/xxx.jpg` / `private/202607/xxx.pdf`），本地磁盘路径 = `sys.file.upload-path` + `objectName`，与 URL `/file/{objectName}` 完全一致。

## 临时文件自动清理

上传文件默认标记为未绑定业务记录的临时文件 (`joinTable=null`)，仅在业务数据保存后通过 `SysFileService.confirmTempFiles(entity)`（实体文件字段打 `@FileField` 注解）设置 `joinTable/joinId` 后方转为使用中。临时文件超过期限后由 Quartz 定时任务 `CleanTempFileJob` 自动删除。

- **确认时机**：业务实体对文件字段打 `@FileField` 注解，在 Service 的 `@Transactional` 方法中调用确认/丢弃临时文件（update 时先 `sysFileService.discardTempFiles(old)` 丢弃旧引用，save 后再 `sysFileService.confirmTempFiles(entity)` 确认新引用）。discardTempFiles + save + confirmTempFiles 必须放在**同一个事务方法**内，不要在 Controller 层调用（详见 development.md「临时文件确认」）。旧方法名 `claim` / `unclaim` 已标记 `@Deprecated`
- **清理配置**：`sys.file.clean-temp-minutes=120`（默认 2 小时）
- **清理频率**：每 10 分钟执行一次（cron `0 */10 * * * ?`）
- **孤儿文件**：业务数据删除后残留的使用中文件，同一任务会检查对应业务表（主键列约定为 `id`）中记录是否已不存在，不存在则一并清理

完整配置项见 `SystemProperties.java`。

## Servlet Context-Path

| 位置 | 配置 |
|------|------|
| 后端 `application.yml` | `server.servlet.context-path` |
| 前端 `web/.env` | `VITE_SERVER_SERVLET_CONTEXT_PATH` |

前端 `HttpClient` 自动带上 context-path 前缀；硬编码 URL 用 `UrlUtils.contextPath(path)` 拼接。

## 主题定制

框架默认回归 **antd 原生主题**（主色 `#1677ff`、侧栏 `#001529`、圆角 `6`），零配置即用。顶栏右侧的调色板按钮可打开「界面设置」面板，内置多套预设主题，并可按需调整界面密度、圆角与界面布局，选择后即时生效并持久化到 `localStorage`。

### 预设主题

| key | 名称 | 主色 | 侧栏底色 |
|-----|------|------|----------|
| `antd` | Ant Design 默认 | `#1677ff` | `#001529` |
| `cyan` | 青碧 | `#13c2c2` | `#0C2E2E` |
| `green` | 翠竹 | `#389e0d` | `#10291A` |
| `purple` | 紫罗兰 | `#722ed1` | `#1E1440` |
| `red` | 中国红 | `#cf1322` | `#2A1418` |
| `orange` | 暖橙 | `#d46b08` | `#2A1D10` |
| `graphite` | 石墨灰 | `#2f3542` | `#1B2026` |

代码中控制：

```js
import { THEME_PRESETS, applyThemePreset, getActivePresetKey, resetUserTheme } from '@jiangood/open-admin';

THEME_PRESETS;              // 预设列表
getActivePresetKey();       // 当前预设 key，默认 'antd'
applyThemePreset('cyan');   // 应用预设
resetUserTheme();           // 恢复默认（antd）
```

### 业务定制配色

业务侧仍可在入口 `<Layouts>` 传入 `colors` prop，作为**基线**；用户选择的预设会覆盖基线，`resetUserTheme()` 回到基线：

```jsx
<Layouts colors={{
    colorPrimary: '#1961AC',
    colorSuccess: '#52c41a',
    colorWarning: '#faad14',
    colorError: '#ff4d4f',
    colorInfo: '#1677ff',
    colorBgLayout: '#f5f5f5',
    // 侧栏配色，不设则用 antd 默认
    siderBg: '#102A43',
    siderSubBg: '#102A43',
    siderHoverBg: 'rgba(255,255,255,0.06)',
    siderTriggerBg: '#0B2038',
}}/>
```

`colors` 为可选字段，未传的项使用框架默认值。菜单/标签栏等处的 `--primary-color` CSS 变量会随主题自动同步。

### 暗色模式

框架内置明/暗两种模式，可通过顶栏的「界面设置」面板（调色板图标）中的「外观模式」切换。切换结果持久化在 `localStorage` 的 `oa-theme-mode`，刷新后保持。

也可在代码中控制：

```js
import { getThemeMode, setThemeMode, toggleThemeMode } from '@jiangood/open-admin';

getThemeMode();        // 'light' | 'dark'
setThemeMode('dark');  // 指定模式
toggleThemeMode();     // 明暗互切
```

- 暗色模式下 `colorBgLayout` **不再套用默认浅灰**，交由 antd 暗色算法推导；若业务显式在 `colors` 中传入 `colorBgLayout`，两种模式都会尊重。
- 左侧栏独立套用 antd 暗色算法，因此分割线、边框在深色底上始终可见；业务无需额外处理。
- 颜色/预设切换通过 `EventBus` 的 `themeChange` 事件广播，`Layouts` 已内置监听并重渲染，业务无需处理。
- 自定义样式请复用框架同步的 CSS 变量以自动跟随明/暗：`--primary-color`、`--primary-color-hover`、`--oa-color-bg-container`、`--oa-color-bg-layout`、`--oa-color-border`、`--oa-color-text`、`--oa-color-text-secondary`、`--oa-color-text-tertiary`、`--oa-color-fill`、`--oa-color-primary-bg`，以及侧栏相关的 `--oa-sider-bg`、`--oa-sider-text`、`--oa-sider-hover-bg`、`--oa-sider-trigger-bg`。

### 界面密度

在「界面设置」面板的「界面密度」中选择 `默认` 或 `紧凑`，切换即时生效，持久化在 `localStorage` 的 `oa-theme-density`。紧凑模式叠加 antd `compactAlgorithm`，并缩小控件高度与字号。

代码中控制：

```js
import { DENSITY_PRESETS, applyDensityPreset, getActiveDensityKey } from '@jiangood/open-admin';

DENSITY_PRESETS;              // 密度预设列表
getActiveDensityKey();        // 'default' | 'compact'
applyDensityPreset('compact');// 应用紧凑
```

### 圆角

「界面设置」面板的「圆角」提供 `默认 / 直角 / 圆润`（对应 `6 / 0 / 10`），默认回归 antd 原生 `6`。持久化在 `localStorage` 的 `oa-theme-radius`。

```js
import { RADIUS_PRESETS, applyRadiusPreset, getActiveRadiusKey } from '@jiangood/open-admin';

RADIUS_PRESETS;               // 圆角预设列表
getActiveRadiusKey();         // 'sharp' | 'default' | 'round'
applyRadiusPreset('round');
```

### 界面布局

「界面布局」提供 `多标签页`（默认）与 `单页` 两种模式。单页模式不显示标签栏，仅渲染当前路由页面。持久化在 `localStorage` 的 `oa-layout-tabs`。

```js
import { getLayoutMode, setLayoutMode, toggleLayoutMode } from '@jiangood/open-admin';

getLayoutMode();              // 'tabs' | 'single'
setLayoutMode('single');
toggleLayoutMode();
```

- 布局切换通过 `EventBus` 的 `layoutChange` 事件广播，管理布局已内置监听并重渲染。
- 单页模式下业务调用 `PageUtils.closeCurrent()` 不再关闭标签（无标签可关），由业务自行跳转。

### 恢复默认

「界面设置」面板底部的「恢复默认」会一次性复位全部界面设置（颜色 / 明暗 / 密度 / 圆角 / 布局）。

```js
import { resetAllSettings, resetUserTheme, resetLayoutMode } from '@jiangood/open-admin';

resetAllSettings();   // 主题 + 布局 全复位
resetUserTheme();     // 仅主题（颜色/明暗/密度/圆角）
resetLayoutMode();    // 仅布局
```

尺寸与圆角相关的 CSS 变量：`--oa-border-radius`、`--oa-control-height`、`--oa-font-size`，自定义样式可复用它们以跟随密度/圆角预设。
