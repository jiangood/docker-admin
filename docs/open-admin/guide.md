# 指南（架构 / 核心功能 / 业务开发）

> open-admin 使用指南：架构设计、核心功能、业务模块开发、内置模块与 FAQ。

- 快速集成与快速开始见 [README](../../README.md)
- API 参考见 [api.md](api.md)
- 配置参考见 [config.md](config.md)
- 开发规范见 [development.md](development.md)

## 架构设计

```
┌─────────────────────────────────────────────────────┐
│  前端: React 19 + Ant Design 6 + Vite 8             │
│  ┌─────────────────────────────────────────────┐    │
│  │ @jiangood/open-admin (组件库 + 管理页面)     │    │
│  └─────────────────────────────────────────────┘    │
├─────────────────── HTTP API ────────────────────────┤
│  后端: Java 21 + Spring Boot 4+ + JPA + Security   │
│  ┌──────────┐ ┌──────────┐ ┌────────┐ ┌──────────┐ │
│  │ modules  │ │framework │ │  util  │ │  config  │ │
│  │ (业务层) │ │  (框架层) │ │ (工具) │ │  (配置)  │ │
│  └──────────┘ └──────────┘ └────────┘ └──────────┘ │
├─────────────────── JDBC ────────────────────────────┤
│          H2（默认，文件模式） / MySQL 8+             │
└─────────────────────────────────────────────────────┘
```

### 项目结构

```
src/main/java/io/github/jiangood/openadmin/
├── framework/          # 框架基础层
│   ├── spi/            # 扩展点接口（OrgTypeProvider, FileOperator, StartupHook, ConfigProvider, MenuCustomizer）
│   ├── config/         # Spring 配置（Security, JPA, Jackson）
│   ├── data/           # BaseEntity, BaseRepository, Spec
│   ├── perm/           # @HasPermission 注解 + 切面
│   ├── log/            # @Log 操作日志注解 + 切面
│   ├── auth/           # 登录认证（AuthController, LoginTool）
│   └── common/         # 通用端点（站点信息、工具）
├── util/               # 工具类库（BeanTool, JsonTool, TreeTool, ExcelTool 等）
└── modules/
    ├── system/         # 用户/角色/菜单/组织/字典/文件/日志
    └── job/            # Quartz 定时任务
web/
├── src/framework/      # @jiangood/open-admin 框架组件库
├── src/pages/          # 业务页面
└── src/layouts/        # 布局组件
```

### 自动配置机制

框架通过 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 注册 `OpenAdminConfiguration`（含 `@ComponentScan` / `@EntityScan` / `@EnableJpaRepositories`，扫描包 `io.github.jiangood.openadmin`）。默认配置在 `application-lib.yml`，业务项目通过 `spring.config.import` 引入。

### 框架扩展点 (`framework.spi`)

`io.github.jiangood.openadmin.framework.spi` 包集中存放框架的 SPI 接口，业务项目通过实现这些接口来扩展框架行为：

| 接口 | 用途 | 注册方式 |
|------|------|---------|
| `OrgTypeProvider` | 自定义机构类型（如新增"门店"类型） | `@Component` |
| `FileOperator` | 自定义文件存储后端（默认提供 `LOCAL` / `S3` 两种实现） | `@Component` |
| `StartupHook` | 系统启动钩子（JPA 建表前/种子数据前后） | `@Component` |
| `ConfigProvider` | 声明运行时可改的参数项（自动出现在「系统参数」页） | `@Component` |
| `MenuCustomizer` | 调整登录用户看到的菜单：改名、移动、排序、隐藏（不支持增删） | `@Component` |

实现类被 `@ComponentScan` 自动发现，无需手动注册。

#### 菜单定制示例

```java
@Component
public class CvsMenuCustomizer implements MenuCustomizer {

    @Override
    public void customize(MenuCustomization customization) {
        // 查看当前菜单（调试用）
        log.debug("定制前菜单:\n{}", customization.dump());

        customization.rename("cvs-capture", "抓拍管理")
                     .moveTo("cvs-playback", "cvs-monitor")
                     .moveBefore("cvs-alarm", "cvs-playback")
                     .hide("cvs-debug");
    }
}
```

- 整个项目只允许一个 `MenuCustomizer`，多个实现会导致启动失败
- 隐藏目录时会连同所有后代一起隐藏
- 定制只影响导航菜单和个人中心快捷菜单，不影响角色授权页、用户权限页，也不改变权限码
- 菜单隐藏后页面仍可通过 URL 访问，后端 `@HasPermission` 校验照常生效

### 核心架构模式

- **BaseEntity** — 所有实体继承 BaseEntity（UUIDv7 id 由 `@PrePersist` 自动生成，含 createTime/createUser、updateTime/updateUser）
- **Spec 动态查询** — `Spec<T>` 链式构建 JPA Specification（eq/like/in/between/or 等），通过 `SpecImpl` + `ExpressionTool` 执行
- **PageExt** — 扩展 PageImpl，支持返回额外数据（如汇总行）
- **菜单加载** — `classpath*:menu.yml` + `classpath*:menu-*.yml` YAML 定义菜单（Map 格式，key 为菜单 id）；id 必须全局唯一，重复定义启动失败。旧名 `application-menu*.yml` 已不再识别，需改名为 `menu.yml` / `menu-*.yml`
- **权限控制** — `@HasPermission` 注解 + AOP 切面，支持 SpEL 表达式
- **ID 生成** — 默认 UUIDv7（时间排序，MySQL 友好）
- **文件存储** — 支持 `local` / `s3`（AWS SDK v2，兼容 RustFS / OSS / COS），通过 `FileOperator` 接口抽象
- **操作日志** — `@Log` 注解 + AOP 切面，异步记录；`@Log.detail` 或 `OperationLogContext` 记录人类可读详情（如「删除用户：张三」），不要把密码/令牌写进详情
- **页面生命周期** — 多 Tab 下页面保持 mounted，通过 `PageFrame` 的 `show` prop + ref 机制自动调用组件 `onShow()` 方法（首次打开 / Tab 切回时触发）

## 技术栈

| 层级 | 技术 |
|------|------|
| 前端 | React 19, Ant Design 6, Vite 8, TypeScript |
| 后端 | Java 21, Spring Boot 4+, JPA (Hibernate), Spring Security, Quartz |
| 数据库 | H2（默认，文件模式，零外部依赖） / MySQL 8+（可选） |
| 构建 | Maven (后端), npm (前端) |

## 核心功能

### 用户权限管理

- **用户管理**：列表/创建/编辑/重置密码/授权数据
- **角色管理**：列表/创建/编辑/分配权限（菜单 + 按钮）
- **权限控制**：后端 `@HasPermission("resource:action")` 注解 + AOP 切面，支持 SpEL；前端推荐 `<PermActions actions={[{label, perm, onClick}]} />` 数据驱动模式（旧式 `<Button perm="xxx:yyy" />` 子元素写法已不推荐）和 `<Perm code="xxx">` 组件
- **权限码格式**：全小写两段式 `{资源}:{操作}`，资源 kebab-case（如 `sys-user:read`、`sys-role:grant-permission`）
- **YAML 定义**：`menu*.yml` 中用 `perms` 对象列表定义
- **菜单分隔线**：支持在 YAML 中配置分隔线（`type: divider`，无需 `name`/`path`，用 `pid` + `seq` 控制位置）对左侧菜单项分组
- **快捷菜单**：用户在个人中心「快捷菜单」Tab 从有权限的页面菜单中勾选（最多 10 个，可拖拽排序），配置结果固定显示在左侧菜单顶部

#### 菜单组织与导航布局

菜单由 `menu.yml` / `menu-*.yml` 定义，`/admin/menu-info` 返回完整菜单树。框架**不设顶部菜单**，整棵树统一渲染在左侧菜单中：

- **左侧菜单**：直接渲染完整菜单树，`children` 节点为可展开目录，支持多级展开；`type: divider` 节点渲染为分隔线分组。打开页面时会自动展开当前菜单所属的各级目录。
- **根级叶子菜单**：老项目把业务菜单直接写在根级（不设 `pid`）的写法同样支持，这些菜单会直接作为左侧菜单项显示，无需再包一层虚拟分组。

业务项目常见的写法是在 `menu-*.yml` 中定义一个目录（如 id 为 `biz` 的「业务模块」「容器平台」），业务菜单写 `pid: biz`；框架自带的 `sys` 目录（系统管理）同理。这些目录会作为左侧菜单中的可展开节点出现，点开即可看到其下菜单。**框架对 `biz` 这个 id 没有任何特殊处理**，前端也不按 id 判断业务/系统菜单；`pid: biz` 和根级叶子两种写法都能正常渲染，按需选择即可。

同级菜单按 `seq` 升序排列（`seq` 相同时保持 YAML 声明顺序），根目录与其子菜单均按此规则排序。框架自带的 `sys` 使用较大的 `seq`（`999999`），确保系统管理默认排在所有业务菜单之后；业务菜单请使用较小的 `seq`。

手机等窄屏（≤768px）下，左侧栏完全收起，顶栏左上角汉堡按钮打开抽屉，抽屉内展示完整左侧菜单树与快捷菜单，内容区固定使用单页模式（不显示页签栏）。

### 数据字典

- **枚举驱动**：框架内置字典（`approveStatus`、`sex`、`yesNo`、`dataPermType`、`articlePosition`、`fileStatus`）由 Java 枚举自动同步生成——枚举类型标注 `@DictType(code, label)`、常量标注 `@DictItem(label, color?)`（`color` 为预设色名 DEFAULT/PROCESSING/SUCCESS/ERROR/WARNING/RED/BLUE/GREEN/GRAY 或十六进制 `#rgb`/`#rrggbb`，如 `#ff0000`，可省略），每次启动经 `DictSeedSync` 同步，枚举是唯一数据源，无需写 SQL 种子
- **业务扩展**：业务模块自定义枚举加 `@DictType`/`@DictItem` 后，放在业务基础包下即可自动入字典（启动时自动扫描业务基础包与框架包）；基础包之外的枚举不在自动扫描范围
- **前端使用**：`<FieldDictSelect typeCode="sex" />` 字典选择器；`DictUtils.dictList("sex")` / `DictUtils.dictLabel("sex", "MALE")` / `DictUtils.dictTag("approveStatus", "APPROVED")`
- **管理界面**：仍可通过字典管理界面维护非枚举类型（业务自建）字典；枚举类型的 label/color 由代码维护

### 其他内置功能

| 功能 | 说明 |
|------|------|
| 系统参数 | 运行时可改的全局配置：数据库只存覆盖值，`application.yml` 与代码默认值兜底；页面顶部只读展示最大并发会话数、会话超时、文件存储等启动期配置；业务模块实现 `ConfigProvider` 即可在通用配置页维护自己的参数 |
| 定时任务 | 基于 Quartz，动态创建/暂停/恢复，继承 `BaseJob` + `@JobDescription`；执行记录方式（每次/每小时/每天各一份记录）与保留期清理 |
| 文件管理 | `sys.file.store-type` 配置（`LOCAL` / `S3`），统一上传下载预览；临时文件自动清理（TTL 可配置） |
| 操作日志 | `@Log` 注解 + AOP 切面，异步记录（独立线程池 `operationLogExecutor`）；`@Log.detail` 或 `OperationLogContext` 记录人类可读详情，日志页支持详情列与行详情抽屉；每条日志记录 IP 与浏览器指纹（前端 `HttpClient` 自动附加请求头 `X-Browser-Fingerprint`），日志页可给指纹加备注名（如「张三的办公电脑」）并按备注筛选、可取消标记，便于判断是否为同一浏览器；只有主动标注过的指纹才写入指纹备注表，未标注的指纹不会自动入库；登录/登出及失败登录由框架自动记录 |
| 在线用户 | 一个登录会话一行，展示账号/姓名/机构/IP/登录时间、最后活跃与最近操作（取自操作日志，按用户 + IP 匹配，可跳转日志），支持强制下线；在线记录在内存，仅单实例部署准确；登录会话默认落盘，正常重启后免登录，在线记录随用户请求自动恢复 |
| 运行日志查看 | 在线查看日志文件 |
| 邮件发送 | 内置 QQ/腾讯企业邮/163/126/Outlook/Gmail/阿里企业邮模板；配置在「系统参数 → 邮件发送」，业务注入 `MailService` 即可发送通知邮件，支持 HTML、附件与 HTTP/SOCKS5 代理 |

## 添加业务模块

1. **Entity** — 继承 `BaseEntity`，JPA 自动建表
2. **Repository** — 继承 `BaseRepository<T, String>`，通用 CRUD + 动态查询
3. **Service** — 继承 `BaseService<T>`，通用业务逻辑
4. **Controller** — RESTful，返回 `AjaxResult`，`@HasPermission` 控制权限；含文件上传字段的实体打 `@FileField` 注解，save 后调用 `sysFileService.confirmTempFiles(entity)` 确认临时文件
5. **菜单** — `src/main/resources/menu.yml`（或 `menu-*.yml`）定义菜单树
6. **前端** — 使用 `ProTable` + `Field*` 组件快速搭建 CRUD 页面

> 在业务项目中创建完整 CRUD 模块可借助 opencode skill `oa-crud`（由 `oa-upgrade-docs` skill 按 `docs/framework-sync.json` 清单从对应 tag 源码归档同步到 `.opencode/skills/`，正式版/快照版一致）。也可在「系统管理 → 代码生成」中选中已建好的实体，一键生成 Repository / Service / Controller / 前端页面 / 菜单 代码并写入项目源码目录（先预览确认）。

## 内置模块

| 模块 | 包路径 | 功能 |
|------|--------|------|
| system | `modules/system/` | 用户/角色/菜单/组织/字典/文件/日志/在线用户管理 |
| config | `modules/config/` | 运行时可改的全局参数（KV 覆盖 + 元数据注册 + 通用配置页） |
| job | `modules/job/` | Quartz 定时任务 |
| logviewer | `modules/logviewer/` | 运行日志在线查看（写入 `FileLog` + 增量读取接口 + 按 key 前缀鉴权） |
| mail | `modules/mail/` | 邮件发送（服务商模板 + 参数项 + `MailService` / `MailTemplate`） |
| codegen | `modules/codegen/` | 实体扫描与 CRUD 代码生成（系统管理 →「代码生成」） |

## FAQ

**种子数据如何管理？** 框架内置的种子数据（默认字典类型、机构、管理员用户/角色、默认文章、清理任务）在首次启动、JPA 建表完成后由 `SeedDataInitializer`（`framework/config/SeedDataInitializer.java`）写入：每条记录先按 id 判断是否已存在，存在则跳过，不覆盖后续修改，因此可重复启动、幂等执行，且不依赖数据库方言（H2 / MySQL 均可）。

**管理员初始密码是什么？** 首次启动创建 `admin` 时，初始密码随机生成并打印在启动日志中（`WARN` 级别，含「初始密码为」字样，Docker 部署用 `docker logs` 查看），且 `lastPasswordChangeTime` 置空——首次登录成功即被引导强制修改密码（与「管理员重置密码」同一机制）。框架不提供固定默认密码，也不提供配置项固定初始密码。若存量项目升级后启动日志出现「检测到管理员仍使用旧版内置默认密码」，说明 admin 密码从未改过，下次登录须修改。

**管理员忘记密码如何重置？**
- **有其他可登录的管理员/有权限用户**：进入「系统管理 → 用户管理」对管理员账号执行「重置密码」（权限 `sys-user:reset-password`），重置后下次登录需强制修改密码。
- **无法登录（如唯一管理员忘记密码）**：使用命令行一次性重置（仅支持命令行参数 `--reset-admin-password`，不支持指定密码，每次生成随机强密码）：
  - **执行方式**：以与目标环境一致的数据库配置启动一次，传入参数 `--reset-admin-password`，命令执行完会自动退出（成功退出码 0，失败非 0）。为避免端口冲突，建议同时加 `--spring.main.web-application-type=none`。
    - Jar：`java -jar app.jar --spring.main.web-application-type=none --reset-admin-password`
    - Docker 一次性容器：`docker run --rm -v <data卷>:/data <image> --spring.main.web-application-type=none --reset-admin-password`
    - Docker Compose：`docker compose run --rm open-admin --spring.main.web-application-type=none --reset-admin-password`
    - Kubernetes：一次性 `Job`，容器 `args: ["--spring.main.web-application-type=none", "--reset-admin-password"]`
  - **取回密码**：新密码**仅输出到控制台（stdout）**，不会写入应用日志文件；可从终端输出或容器/任务日志中查找 `[ADMIN-PASSWORD-RESET]` 获取。应用日志仅记录“已重置”审计信息（不含密码）。取回后请及时登录并完成强制改密。
  - **数据库与停机**：默认 **H2 文件库**（`jdbc:h2:file:...`）同一时间仅允许一个进程访问，执行前必须先停止正在运行的业务服务/容器，避免文件锁导致重置失败；重置完成后再正常启动服务。使用 **MySQL 等外部数据库** 无文件锁限制，一般无需停机，但仍需使用相同数据源配置并关闭 Web（避免端口冲突）。
  - **注意**：执行前建议备份数据库/数据卷；不要将 `--reset-admin-password` 保留在常驻服务启动参数中（否则每次启动都会重置并退出）。若管理员账号被禁用，重置后仍需先启用账号才能登录。已有登录会话不会因改密立即失效。

**业务项目如何添加自己的种子数据？** 实现 `StartupHook` 并注册为 Spring Bean，在 `afterSeedDataInitialize()` 中写入业务数据（框架会先写内置种子数据，再调用该方法）。建议同样采用「按业务键判断是否已存在，存在则跳过」的幂等写法。业务数据通过 JPA Repository 保存即可，无需 Flyway 等版本化迁移工具。

> 框架不再使用 Flyway：早期内置的 `db/migration/*.sql` 已移除，历史库表的兼容迁移由业务项目按需自行处理。


**前端依赖安装失败？** `npm install --registry=https://registry.npmmirror.com`

**端口被占用？** 后端默认 8888，前端默认 7777，可通过环境变量 `SERVER_PORT` 修改后端端口。

**页签显示「未命名」怎么办？** 菜单页未声明 `pageMeta.title` 时会自动回退到后端菜单名；非菜单的详情页/公共页需要在页面文件导出 `pageMeta.title`。不要再通过 `PageUtils.open(path, label)` 写 `_label` 参数（已弃用且不再生效，首次使用会在控制台提示）。
