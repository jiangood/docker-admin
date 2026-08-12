---
name: oa-upgrade
description: 在业务项目中升级 open-admin 框架版本 — 读取 Release Notes 和 Git 提交日志，自动/辅助完成 Maven + npm 依赖升级和代码迁移。适用于以 Maven JAR + npm 包方式引入 open-admin 的业务项目。
---

# oa-upgrade — 框架版本升级指南

## 适用范围

当业务项目已通过 Maven JAR（`io.github.jiangood:open-admin`）和 npm 包（`@jiangood/open-admin`）方式集成了 open-admin 框架，需要升级框架版本时使用。

## 前提条件检查

开始之前，Claude 必须确认：

### 业务项目框架版本信息

读取业务项目的以下文件获取当前版本：

- `pom.xml` — 查找 `<artifactId>open-admin</artifactId>` 所在 `<dependency>` 的 `<version>` 标签
- `package.json` — 查找 `dependencies` 中的 `"@jiangood/open-admin"` 版本

如果没有精确匹配，检查 `<properties>` 中的版本属性变量。

### 工具检查

- Maven 可用（`mvn --version`）
- Node.js / npm 可用（`node --version`）—— 用 `npm view` 验证版本

## 升级流程

### 1. 确认当前版本与可用版本

展示当前版本，然后拉取框架的可用版本：

```bash
npm view @jiangood/open-admin versions --json
npm view @jiangood/open-admin version
```

向开发者展示可用版本，询问目标版本号（如 `3.0.2`、`4.0.0` 等）。

### 2. 读取变更信息

同时获取两个数据源，用 webfetch 工具抓取以下页面，合并展示给开发者：

```text
# 2a. Git 提交日志（当前版本到目标版本之间的所有提交）
https://github.com/jiangood/open-admin/compare/v{current_version}...v{target_version}

# 2b. 目标版本的 Release Notes（含 Breaking Changes 和迁移说明）
https://github.com/jiangood/open-admin/releases/tag/v{target_version}
```

展示要点：
- 提交数、参与开发者数
- 按类型归类：feat / fix / refactor / chore
- **高亮 Breaking Changes**（提交信息中含 `BREAKING`、`break`、`migration` 等关键词的提交）
- Release Notes 中的迁移指南部分

### 3. 确认升级

向开发者展示变更概览，询问是否继续。如确认则进入下一步。

### 4. 更新依赖版本

**Maven（pom.xml）：**

找到 `<artifactId>open-admin</artifactId>` 对应的 `<version>` 标签（可能在 `<dependency>` 中或 `<properties>` 中引用了变量），更新为目标版本号。

**npm（package.json）：**

找到 `"@jiangood/open-admin"` 的版本号，更新为目标版本号（保留 `^` 前缀习惯）。

### 5. 处理迁移

根据 Release Notes 和 Git 提交日志中的迁移指引，逐项执行代码修改：

| 变更类型 | 典型操作 |
|---------|---------|
| 依赖增减 | 添加/移除 pom.xml 或 package.json 中的依赖项 |
| API 签名变化 | 更新 Java 代码中调用的方法名或参数 |
| 配置变更 | 更新 application.yml 中的配置项 |
| 前端组件变更 | 更新 JSX 中的组件引用 |
| 注解变化 | 更新 `@HasPermission`、`@Log` 等注解的用法 |
| 数据库迁移 | 执行 Flyway 迁移脚本或 DDL |

### 6. 编译与测试验证

顺序执行以下步骤，任何一项失败则停止并修复：

```bash
# 6a. 后端编译
cd {业务项目目录}
mvn clean compile

# 6b. 后端测试
cd {业务项目目录}
mvn test

# 6c. 前端构建
cd {业务项目目录/web}
npm run build
```

如有编译或测试失败，根据错误信息修复后重新从失败步骤开始验证。

### 7. 框架文件自动同步（skills + docs + AGENTS.md）

框架 JAR 内置业务侧 skills 与文档（`META-INF/open-admin/framework-files/`），业务项目**启动后端时按内容比对自动同步**到项目根目录：

- `<项目根>/.opencode/skills/oa-crud/`、`oa-upgrade/` — 覆盖写入（不删除业务本地 skill）
- `<项目根>/docs/open-admin/*.md` — 全量镜像（删除孤儿文件）
- `<项目根>/AGENTS.md` — 仅在不存在时生成（不覆盖业务自定义）；新版本随 `docs/open-admin/AGENTS.md` 提供

无需手动复制。**升级完成后必须启动一次后端**，`FrameworkFileSyncer` 会按内容比对把新版 `.opencode/skills/`、`docs/open-admin/`、`AGENTS.md` 重新同步到项目根目录；未启动后端则这些文件不会更新。请在最后显式提醒开发者：启动后端后确认框架相关文件已重新生成。

### 8. 其他检查（可选）

- 启动项目确认登录/页面正常
- 核心业务流程可用

## 验证清单

- [ ] `mvn compile` 编译通过
- [ ] `mvn test` 全部通过
- [ ] `npm run build` 正常
- [ ] Release Notes 中的 Breaking Changes 已逐项处理
- [ ] Git 提交日志中涉及业务代码的变更已适配
- [ ] 升级后启动后端，`.opencode/skills/` 与 `docs/open-admin/` 已同步为新版本内容
- [ ] 升级后功能正常（登录、菜单、CRUD 操作）

## 代码规范

- Java import 使用框架的全限定名
- 前端 import 使用 `@jiangood/open-admin` 包名
- 构造器注入，禁止 `@Autowired` 字段注入
- 使用 Release Notes 中推荐的新 API 替代废弃 API

## 参考

- 框架仓库: https://github.com/jiangood/open-admin
- npm 包页面（`npm view` 查询版本）: https://www.npmjs.com/package/@jiangood/open-admin
