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
│   ├── spi/            # 扩展点接口（OrgTypeProvider, FileOperator, StartupHook）
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
| `FileOperator` | 自定义文件存储后端（默认提供 `LOCAL` / `MINIO` 两种实现） | `@Component` |
| `StartupHook` | 系统启动钩子（JPA 建表前/种子数据前后） | `@Component` |

实现类被 `@ComponentScan` 自动发现，无需手动注册。

### 核心架构模式

- **BaseEntity** — 所有实体继承 BaseEntity（UUIDv7 id 由 `@PrePersist` 自动生成，含 createTime/createUser、updateTime/updateUser）
- **Spec 动态查询** — `Spec<T>` 链式构建 JPA Specification（eq/like/in/between/or 等），通过 `SpecImpl` + `ExpressionTool` 执行
- **PageExt** — 扩展 PageImpl，支持返回额外数据（如汇总行）
- **菜单加载** — `classpath*:application-menu*.yml` YAML 定义菜单（Map 格式，key 为菜单 id），也支持数据库存储
- **权限控制** — `@HasPermission` 注解 + AOP 切面，支持 SpEL 表达式
- **ID 生成** — 默认 UUIDv7（时间排序，MySQL 友好）
- **文件存储** — 支持 `local` / `minio`，通过 `FileOperator` 接口抽象
- **操作日志** — `@Log` 注解 + AOP 切面，异步记录
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
- **YAML 定义**：`application-menu*.yml` 中用 `perms` 对象列表定义

### 数据字典

- **枚举驱动**：框架内置字典（`approveStatus`、`sex`、`yesNo`、`dataPermType`、`articlePosition`、`fileStatus`）由 Java 枚举自动同步生成——枚举类型标注 `@DictType(code, label)`、常量标注 `@DictItem(label, color?)`（`color` 为预设色名 DEFAULT/PROCESSING/SUCCESS/ERROR/WARNING/RED/BLUE/GREEN/GRAY 或十六进制 `#rgb`/`#rrggbb`，如 `#ff0000`，可省略），每次启动经 `DictSeedSync` 同步，枚举是唯一数据源，无需写 SQL 种子
- **业务扩展**：业务模块自定义枚举加 `@DictType`/`@DictItem` 后，放在业务基础包下即可自动入字典（启动时自动扫描业务基础包与框架包）；基础包之外的枚举不在自动扫描范围
- **前端使用**：`<FieldDictSelect typeCode="sex" />` 字典选择器；`DictUtils.dictList("sex")` / `DictUtils.dictLabel("sex", "MALE")` / `DictUtils.dictTag("approveStatus", "APPROVED")`
- **管理界面**：仍可通过字典管理界面维护非枚举类型（业务自建）字典；枚举类型的 label/color 由代码维护

### 其他内置功能

| 功能 | 说明 |
|------|------|
| 定时任务 | 基于 Quartz，动态创建/暂停/恢复，继承 `BaseJob` + `@JobDescription` |
| 文件管理 | `sys.file.store-type` 配置（`LOCAL` / `MINIO`），统一上传下载预览；临时文件自动清理（TTL 可配置） |
| 操作日志 | `@Log` 注解 + AOP 切面，异步记录（独立线程池 `operationLogExecutor`） |
| 运行日志查看 | 在线查看日志文件 |

## 添加业务模块

1. **Entity** — 继承 `BaseEntity`，JPA 自动建表
2. **Repository** — 继承 `BaseRepository<T, String>`，通用 CRUD + 动态查询
3. **Service** — 继承 `BaseService<T>`，通用业务逻辑
4. **Controller** — RESTful，返回 `AjaxResult`，`@HasPermission` 控制权限；含文件上传字段的实体打 `@FileField` 注解，save 后调用 `sysFileService.claim(entity)` 确认临时文件
5. **菜单** — `src/main/resources/application-menu*.yml` 定义菜单树
6. **前端** — 使用 `ProTable` + `Field*` 组件快速搭建 CRUD 页面

> 在业务项目中创建完整 CRUD 模块可借助 opencode skill `oa-crud`（由 `oa-upgrade-docs` skill 从框架 Release 同步到 `.opencode/skills/`）。也可在「系统管理 → 代码生成」中选中已建好的实体，一键生成 Repository / Service / Controller / 前端页面 / 菜单 代码并写入项目源码目录（先预览确认）。

## 内置模块

| 模块 | 包路径 | 功能 |
|------|--------|------|
| system | `modules/system/` | 用户/角色/菜单/组织/字典/文件/日志管理 |
| job | `modules/job/` | Quartz 定时任务 |
| logviewer | `modules/logviewer/` | 运行日志在线查看 |
| codegen | `modules/codegen/` | 实体扫描与 CRUD 代码生成（系统管理 →「代码生成」） |

## FAQ

**种子数据如何管理？** 框架内置的种子数据（默认字典类型、机构、管理员用户/角色、默认文章、清理任务）在首次启动、JPA 建表完成后由 `SeedDataInitializer`（`framework/config/SeedDataInitializer.java`）写入：每条记录先按 id 判断是否已存在，存在则跳过，不覆盖后续修改，因此可重复启动、幂等执行，且不依赖数据库方言（H2 / MySQL 均可）。

**业务项目如何添加自己的种子数据？** 实现 `StartupHook` 并注册为 Spring Bean，在 `afterSeedDataInitialize()` 中写入业务数据（框架会先写内置种子数据，再调用该方法）。建议同样采用「按业务键判断是否已存在，存在则跳过」的幂等写法。业务数据通过 JPA Repository 保存即可，无需 Flyway 等版本化迁移工具。

> 框架不再使用 Flyway：早期内置的 `db/migration/*.sql` 已移除，历史库表的兼容迁移由业务项目按需自行处理。


**前端依赖安装失败？** `npm install --registry=https://registry.npmmirror.com`

**端口被占用？** 后端默认 8080，前端默认 3000，可通过环境变量 `SERVER_PORT` 修改后端端口。
