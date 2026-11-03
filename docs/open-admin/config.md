# 配置参考

> open-admin 配置项说明。部署期配置写在 `application.yml`；少量展示类配置可在「系统管理 → 参数设置」中运行时修改。

## 启动配置（`application.yml`，需重启）

| 配置 | 说明 | 默认值 |
|------|------|--------|
| `sys.title` | 系统标题（必填），可在参数设置页覆盖 | 管理系统 |
| `sys.short-title` | 系统简称，仅在左侧菜单顶部等空间受限处显示；留空则使用 `sys.title`，可在参数设置页覆盖 | 空 |
| `sys.file.store-type` | 文件存储 (`LOCAL`/`MINIO`) | LOCAL |
| `sys.file.upload-path` | 本地上传路径 | /home/files |
| `sys.file.clean-temp-minutes` | 临时文件自动清理时间（分钟） | 120 |
| `sys.file.minio.*` | MinIO 对象存储配置 | — |
| `sys.session-idle-time` | Session 超时（分钟） | 180 |
| `sys.maximum-sessions` | 最大并发会话数（同一账号同时在线数），超出时踢出旧会话 | 1 |
| `sys.concurrent-login-message` | 被并发登录顶下线时的提示文案 | 您的账号已在别处登录，请重新登录 |
| `sys.login-expired-message` | 会话空闲超时/未登录时的提示文案（经 `/admin/public/site-info` 下发给前端） | 登录已过期，请重新登录 |
| `sys.job-enable` | 定时任务开关 | true |
| `sys.job-log-retain-days` | 定时任务执行记录保留天数，`<=0` 表示不清理 | 30 |
| `sys.codegen.backend-dir` | 代码生成后端源码根目录（相对项目根） | src/main/java |
| `sys.codegen.resource-dir` | 代码生成资源目录（菜单 YAML 输出位置） | src/main/resources |
| `sys.codegen.frontend-dir` | 代码生成前端页面根目录 | web/src/pages |
| `sys.codegen.frontend-import` | 生成页面引入框架组件的模块名；留空自动识别（框架仓库用相对路径，业务项目用 `@jiangood/open-admin`） | 空 |

> 框架的 `.opencode/skills/` 与 `docs/open-admin/` 由 `oa-upgrade-docs` skill 从框架 GitHub Release 下载 `framework-files.zip` 同步到业务项目根目录（内容比对，无变更不写入）。无需配置。

> 「在线用户」列表复用同一套会话注册表，记录保存在内存，**仅单实例部署准确**；多实例部署请先引入 Spring Session 等共享会话方案，否则各节点只能看到本节点登录的用户。

> 本地想验证个人中心「登录设备」的多处登录提示，可用 `local` profile 启动（`-Dspring-boot.run.profiles=local` 或环境变量 `SPRING_PROFILES_ACTIVE=local`），该 profile 将 `sys.maximum-sessions` 设为 `3`。

## 参数设置（运行时可改）

「系统管理 → 参数设置」页面的数据来自 `sys_config` 表，但**默认值不落库**：

- 取值优先级：**数据库覆盖值 > `application.yml` > 代码声明的默认值**
- 只有被修改过的项才写入 `sys_config`；删除记录即恢复默认
- 覆盖值在内存中缓存，保存后本机立即生效；多节点部署时其他节点最长 30 秒后生效
- 敏感项接口不回显，修改时留空表示保持原值；清除请点「恢复默认」

### 内置配置项

| 分组 | key | 类型 | 默认值 | 说明 |
|------|-----|------|--------|------|
| 站点信息 | `sys.title` | STRING（必填） | 管理系统 | 登录页与侧栏标题 |
| 站点信息 | `sys.short-title` | STRING | 空 | 侧栏简称，空则使用 `sys.title` |
| 站点信息 | `sys.water-mark` | BOOL | false | 登录后页面右下角显示当前用户水印 |
| 站点信息 | `sys.login-box-bottom-tip` | STRING | 空 | 登录框底部提示文案 |
| 默认界面 | `sys.ui.theme-preset` | SELECT | `antd` | 未自定义用户的默认主题色预设 |
| 默认界面 | `sys.ui.tabs-enabled` | BOOL | true | 页签模式，关闭为单页模式 |
| 邮件发送 | `email.provider` | SELECT | 空 | 邮箱类型，决定 SMTP 服务器/端口/加密方式 |
| 邮件发送 | `email.username` | STRING | 空 | 发件邮箱 |
| 邮件发送 | `email.password` | STRING（敏感） | 空 | 授权码/应用专用密码，留空表示不修改 |
| 邮件发送 | `email.from.name` | STRING | 空 | 发件人显示名，可选 |
| 邮件发送 | `email.proxy` | PROXY | 空 | 代理 URL，留空表示不使用 |

其余 `sys.*` 仍在 `application.yml` 中维护，**修改后需要重启**；原因是它们属于启动期装配（最大会话数、Quartz 开关、代码生成目录、MinIO 等）或安全结构性配置。

### 邮件发送

邮件配置全部在「系统管理 → 参数设置 → 邮件发送」中维护，不需要填写 SMTP 服务器/端口/加密方式：

| 邮箱类型 | SMTP |
|------|------|
| QQ 邮箱 | smtp.qq.com:465 / SSL |
| 腾讯企业邮 | smtp.exmail.qq.com:465 / SSL |
| 网易 163 | smtp.163.com:465 / SSL |
| 网易 126 | smtp.126.com:465 / SSL |
| Outlook / Microsoft 365 | smtp.office365.com:587 / STARTTLS |
| Gmail | smtp.gmail.com:587 / STARTTLS |
| 阿里企业邮 | smtp.qiye.aliyun.com:465 / SSL |

- QQ/163/126 使用邮箱授权码，不是登录密码；Gmail 需要两步验证 + 应用专用密码；
- Outlook/M365 需要在租户/邮箱开启 SMTP AUTH；个人 Outlook.com 已停用基础认证，当前版本不支持 OAuth2；
- 代理写一行地址：`http://user:pass@192.168.1.10:8080`、`socks5://192.168.1.10:1080`；不写 scheme 按 http 处理，端口必填，留空表示不使用；
- 代理密码回显为 `***`，保存时保持 `***` 表示沿用已保存的密码；清空字段即不使用代理；
- SOCKS5 代理不支持账号密码（JavaMail 的限制），需要认证请用 HTTP 代理。

业务模块注入 `MailService` 发送：

```java
mailService.sendAsync(MailMessage.builder()
        .to("ops@example.com")
        .subject("构建成功")
        .html(MailTemplate.notification("构建成功", MailTemplate.Status.SUCCESS)
                .row("项目", "demo")
                .paragraph("镜像已推送。")
                .build())
        .build());
```

### 业务项目扩展

实现 `ConfigProvider` 并注册为 Spring Bean，配置项会自动出现在配置页：

```java
@Component
public class CvsConfigProvider implements ConfigProvider {

    @Override
    public List<ConfigGroup> define() {
        return List.of(
                Configs.group("cvs.capture", "抓拍", 10)
                        .description("抓拍任务的运行参数")
                        .add(Configs.bool("cvs.capture.enabled", "启用抓拍").defaultValue(true))
                        .add(Configs.integer("cvs.capture.interval", "抓拍间隔（秒）")
                                .defaultValue(10).min(5).max(3600))
                        .add(Configs.time("cvs.capture.start-time", "开始时间").defaultValue("09:00"))
                        .add(Configs.list("cvs.capture.channels", "通道列表").hint("逗号分隔"))
        );
    }
}
```

读取：

```java
boolean enabled = sysConfigService.getBool("cvs.capture.enabled");
int interval = sysConfigService.getInt("cvs.capture.interval");
List<String> channels = sysConfigService.getList("cvs.capture.channels");
```

约束：

- key 与分组 code 均需全局唯一，key 全小写、点分隔、段落内允许连字符，例如 `cvs.capture.start-time`
- 可选类型：`bool` / `integer` / `decimal` / `string` / `text` / `time` / `list` / `select` / `proxy`，默认值类型需与声明类型一致
- `proxy` 类型用于带凭据的代理地址：一个字段同时包含类型、主机、端口与账号密码，回显时密码为 `***`，保存时自动还原已存密码
- `.asPublic()`：随 `/admin/public/site-info` 的 `config` 字段下发给未登录页面
- `.asSecret()`：接口只返回 `hasValue`，不回显值；不能同时 `.asPublic()`
- `.asRequired()`：保存时不允许为空
- 配置文件里用相同 key 写默认值也会生效（优先级低于数据库覆盖值）
- 配置项声明错误（重复 key、非法 key、敏感项同时公开等）会让应用启动失败，便于尽早发现

> 修改配置的接口不要直接加 `@Log`：请求体可能包含敏感值。框架通过 `SysConfigOperationLogger` 只记录变更的 key。

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

框架默认回归 **antd 原生主题**（主色 `#1677ff`、侧栏 `#001529`、圆角 `6`），零配置即用。顶栏右侧的太阳/月亮图标可一键切换深色/浅色模式；调色板按钮可打开「界面设置」面板，内置多套预设主题，并可按需调整界面密度与圆角，选择后即时生效并持久化到 `localStorage`。页签模式（开启 / 关闭）由顶栏独立的切换按钮控制，关闭即单页模式。

### 预设主题

| key | 名称 | 主色 | 侧栏底色 |
|-----|------|------|----------|
| `antd` | Ant Design 默认 | `#1677ff` | `#001529` |
| `graphite` | 石墨灰 | `#2f3542` | `#1B2026` |
| `indigo` | 靛蓝 | `#4f46e5` | `#1E1B4B` |
| `purple` | 紫罗兰 | `#722ed1` | `#1E1440` |
| `pink` | 樱花粉 | `#eb2f96` | `#2B1024` |
| `red` | 中国红 | `#cf1322` | `#2A1418` |
| `orange` | 暖橙 | `#d46b08` | `#2A1D10` |
| `green` | 翠竹 | `#389e0d` | `#10291A` |
| `cyan` | 青碧 | `#13c2c2` | `#0C2E2E` |

代码中控制：

```js
import { THEME_PRESETS, applyThemePreset, getActivePresetKey, resetUserTheme } from '@jiangood/open-admin';

THEME_PRESETS;              // 预设列表
getActivePresetKey();       // 当前预设 key；未自定义时为参数设置的全局默认
applyThemePreset('cyan');   // 应用预设
resetUserTheme();           // 恢复全局默认（参数设置中的默认主题色）
```

### 业务定制配色

业务侧仍可在入口 `<Layouts>` 传入 `colors` prop，作为**基线**；用户选择的预设会覆盖基线，`resetUserTheme()` 回到参数设置的全局默认（默认预设为 `antd` 时即回到基线）：

```jsx
<Layouts colors={{
    colorPrimary: '#1961AC',
    colorSuccess: '#52c41a',
    colorWarning: '#faad14',
    colorError: '#ff4d4f',
    colorInfo: '#1677ff',
    // 页面底色：colorBgLayout 仅浅色模式生效
    colorBgLayout: '#f5f5f5',
    // 暗色模式页面底色（非 antd token）；不设则由 antd 暗色算法推导（#000000）
    darkColorBgLayout: '#0f1115',
    // 侧栏配色，不设则用 antd 默认
    siderBg: '#102A43',
    siderSubBg: '#102A43',
    siderHoverBg: 'rgba(255,255,255,0.06)',
    siderTriggerBg: '#0B2038',
}}/>
```

`colors` 为可选字段，未传的项使用框架默认值。菜单/标签栏等处的 `--primary-color` CSS 变量会随主题自动同步。

### 暗色模式

框架内置明/暗两种模式，可通过顶栏右侧的太阳/月亮图标一键切换（不在「界面设置」面板中重复提供）。切换结果持久化在 `localStorage` 的 `oa-theme-mode`，刷新后保持。

也可在代码中控制：

```js
import { getThemeMode, setThemeMode, toggleThemeMode } from '@jiangood/open-admin';

getThemeMode();        // 'light' | 'dark'
setThemeMode('dark');  // 指定模式
toggleThemeMode();     // 明暗互切
```

- 页面底色分模式生效：浅色用 `colors.colorBgLayout`，暗色用 `colors.darkColorBgLayout`（框架自定义字段，非 antd token）。暗色下**不再复用** `colorBgLayout`，未传 `darkColorBgLayout` 时由 antd 暗色算法推导（`#000000`）。暗色下若只传了浅色的 `colorBgLayout`，控制台会提示一次迁移，避免配置静默失效。
- 左侧栏独立套用 antd 暗色算法，因此分割线、边框在深色底上始终可见；业务无需额外处理。
- 颜色/预设切换通过 `EventBus` 的 `themeChange` 事件广播，`Layouts` 已内置监听并重渲染，业务无需处理。
- 自定义样式请复用框架同步的 CSS 变量以自动跟随明/暗：`--primary-color`、`--primary-color-hover`、`--oa-color-bg-container`、`--oa-color-bg-layout`、`--oa-color-border`、`--oa-color-text`、`--oa-color-text-secondary`、`--oa-color-text-tertiary`、`--oa-color-fill`、`--oa-color-fill-quaternary`、`--oa-color-primary-bg`，以及侧栏相关的 `--oa-sider-bg`、`--oa-sider-text`、`--oa-sider-hover-bg`、`--oa-sider-trigger-bg`。

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

### 页签模式

页签模式默认开启（`tabs`），可切换为单页模式（`single`）。单页模式不显示页签栏，仅渲染当前路由页面。持久化在 `localStorage` 的 `oa-layout-tabs`。

顶栏右侧的切换图标显示切换后的目标模式：页签模式下显示单页图标，单页模式下显示页签图标。

```js
import { getLayoutMode, setLayoutMode, toggleLayoutMode } from '@jiangood/open-admin';

getLayoutMode();              // 'tabs' | 'single'
setLayoutMode('single');
toggleLayoutMode();
```

- 页签模式切换通过 `EventBus` 的 `layoutChange` 事件广播，管理布局已内置监听并重渲染。
- 单页模式下业务调用 `PageUtils.closeCurrent()` 不再关闭页面（无页签可关），由业务自行跳转。

### 全局默认主题色与页签模式

管理员可在「系统管理 → 参数设置 → 默认界面」中维护未自定义用户的默认值，保存后立即生效：

| key | 类型 | 默认 | 说明 |
|-----|------|------|------|
| `sys.ui.theme-preset` | select | `antd` | 默认主题色预设，可选值与上方「预设主题」一致 |
| `sys.ui.tabs-enabled` | bool | `true` | `true` 开启页签模式，`false` 为单页模式 |

- **优先级**：用户在本地自定义的 `oa-theme-preset` / `oa-layout-tabs` 高于参数设置；已自定义用户不受默认值变更影响。
- 两项均声明为 `publicVisible`，随 `/admin/public/site-info` 的 `config` 字段下发，前端在后台布局渲染前应用，避免闪一下默认样式。
- 业务侧可通过 `applyServerUiDefaults(siteInfo.config)` 手动应用，或用 `getDefaultPresetKey()` / `getDefaultLayoutMode()` 读取当前全局默认。

### 恢复默认

「界面设置」面板底部的「恢复默认」会一次性复位全部界面设置（颜色 / 明暗 / 密度 / 圆角 / 页签模式），其中主题色与页签模式回到参数设置的全局默认。

```js
import { resetAllSettings, resetUserTheme, resetLayoutMode } from '@jiangood/open-admin';

resetAllSettings();   // 主题 + 页签模式 全复位
resetUserTheme();     // 仅主题（颜色/明暗/密度/圆角）
resetLayoutMode();    // 仅页签模式
```

尺寸与圆角相关的 CSS 变量：`--oa-border-radius`、`--oa-control-height`、`--oa-font-size`，自定义样式可复用它们以跟随密度/圆角预设。
