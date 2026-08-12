# AGENTS.md

本文件为 AI 编程助手（opencode / Claude Code 等）提供本业务项目的开发指引。

> 本文件由 open-admin 框架在业务项目启动后端时自动生成（仅当不存在时生成，不覆盖本地自定义内容）；升级框架后新版本会在 `docs/open-admin/AGENTS.md` 提供更新版本文档。

## 项目概览

本项目基于 open-admin 框架（可嵌入的后台管理系统），通过 Maven / npm 依赖获得完整的后台管理能力（用户管理、角色权限、数据字典、Quartz 调度、文件管理等）。

## 技术栈与目录约定

- **后端**：Java 21, Spring Boot 4+, JPA (Hibernate), Spring Security, Quartz, MySQL 8+
- **前端**：React 19, Ant Design 6, Vite 8, TypeScript
- **后端源码**：`src/main/java/{groupId}.{project}/`（业务包）
- **前端页面**：`web/src/pages/`（小写开头文件自动注册路由）
- **配置**：`src/main/resources/application.yml`、`application-menu*.yml`（菜单/权限）

## 框架文档

- 本目录框架文档：`docs/open-admin/`（guide / api / config / development）
- 框架仓库：https://github.com/jiangood/open-admin

## opencode Skills

本项目自带 opencode skills（由框架自动同步到 `.opencode/skills/`，升级后启动后端自动更新）：

| Skill | 用途 |
|-------|------|
| `oa-crud` | 创建 CRUD 业务模块（Entity→Repository→Service→Controller→前端页面→菜单配置） |
| `oa-upgrade` | 升级框架版本（依赖版本 + 代码迁移 + 验证） |

使用 opencode 进行开发时，优先调用上述 skill。

## 开发规范

- Java 使用构造器注入（`@RequiredArgsConstructor` + `private final`），禁止 `@Autowired` 字段注入
- 业务实体继承 `BaseEntity`；Repository 继承 `BaseRepository<T, String>`；Service 继承 `BaseService<T>`
- Controller 统一返回 `AjaxResult`，权限用 `@HasPermission("resource:action")`
- 前端优先使用框架组件：`ProTable`、`Page`、`Field*`、`PermActions`
- 权限码全小写两段式 `{资源}:{操作}`（如 `biz-customer:read`）
- 详细规范见 `docs/open-admin/development.md`

## 常用命令

```bash
mvn clean compile                                   # 后端编译
mvn -Pdev spring-boot:run                           # 后端启动
cd web && npm install && npm run dev                # 前端开发
cd web && npm run build                             # 前端构建
```
