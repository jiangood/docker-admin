# oa-upgrade — 框架版本升级指南

## 适用范围

当业务项目已通过 Maven JAR（`io.github.jiangood:open-admin`）和 npm 包（`@jiangood/open-admin`）方式集成了 open-admin 框架，需要升级框架版本时使用。正式版与预览版（SNAPSHOT）都走本流程，但**依赖写法和变更信息来源不同**。

## 版本通道识别（先做这一步）

| 通道 | Maven 版本 | npm 版本 | git tag | 变更信息来源 | 框架文件来源 |
|---|---|---|---|---|---|
| 正式版 | `3.4.0` | `3.4.0` | `v3.4.0` | Release Notes + compare | tag 源码归档（按 `docs/framework-sync.json` 清单同步） |
| 预览版 | `3.4.0-SNAPSHOT` | `3.4.0-SNAPSHOT.1` | `snapshot-v3.4.0-1` | 只有 compare 提交日志 | tag 源码归档（按 `docs/framework-sync.json` 清单同步） |

判定规则：

- 版本含 `-SNAPSHOT` 或 `-SNAPSHOT.<build>`，或 tag 以 `snapshot-v` 开头 → **预览版**
- 否则 → **正式版**

预览版要点（与正式版的关键差异）：

- **没有 GitHub Release**，所以没有 Release Notes（变更信息以 compare 提交日志为准）
- npm 同一版本不可覆盖，每个构建的 npm 版本号都不同（`.1`、`.2`…），升级预览版 = 换一个精确版本号
- Maven 走 Central 快照仓库，需要业务项目额外配置仓库，且要放行全局镜像
- SNAPSHOT 约 90 天后会被 Central 清理，长期使用需回到正式版
- 框架文件（skill/文档）与正式版一样，通过 tag 源码归档按清单同步，无需 GitHub Release 资产

## 前提条件检查

开始之前，必须确认：

### 业务项目框架版本信息

读取业务项目的以下文件获取当前版本：

- `pom.xml` — 查找 `<artifactId>open-admin</artifactId>` 所在 `<dependency>` 的 `<version>` 标签
- `package.json` — 查找 `dependencies` 中的 `"@jiangood/open-admin"` 版本

如果没有精确匹配，检查 `<properties>` 中的版本属性变量。

### 工具检查

- Maven 可用（`mvn --version`）
- Node.js / npm 可用（`node --version`）—— 用 `npm view` 验证版本
- 工作区干净（有未提交改动时先让开发者确认，避免升级与业务改动混在一起）

## 升级流程

### 1. 确认当前版本与可用版本

展示当前版本，然后拉取框架的可用版本：

```bash
npm view @jiangood/open-admin dist-tags --json   # 含 latest / snapshot 指针
npm view @jiangood/open-admin versions --json    # 全部已发布版本（含 SNAPSHOT）
```

向开发者展示可用版本，询问目标版本号。

- 正式版：`3.4.0`
- 预览版：建议先看 `snapshot` dist-tag 得到最新构建号，再确认要锁定的精确版本（如 `3.3.4-SNAPSHOT.1`）
- 若 npm 查询失败，加 `--registry=https://registry.npmjs.org`

### 2. 校验目标版本

确定目标版本后、读取变更或修改依赖前，按通道做快速校验。npm 与 GitHub 侧均为即时生效；**不要**把 Maven Central 可见性作为前置条件（仅正式版 Maven 同步到 Central 可能有 ~10 分钟延迟，见第 7 步）。

**正式版**（`X.Y.Z`）—— 以 GitHub Release/tag 为准（Release Notes 依赖 release；框架文件从 tag 源码归档按清单同步，不再依赖 release 资产）：

```bash
npm view @jiangood/open-admin@{target_version} version
curl -fsSI -o /dev/null "https://github.com/jiangood/open-admin/releases/tag/v{target_version}"
```

**预览版**（`X.Y.Z-SNAPSHOT.N`）—— 无 GitHub Release，以 snapshot tag 为准：

```bash
npm view @jiangood/open-admin@{base_version}-SNAPSHOT.{build} version
git ls-remote --exit-code --tags https://github.com/jiangood/open-admin.git refs/tags/snapshot-v{base_version}-{build}
# 或：curl -fsS -o /dev/null "https://api.github.com/repos/jiangood/open-admin/git/ref/tags/snapshot-v{base_version}-{build}"
```

- 未指定 build 号时，先用 `npm view @jiangood/open-admin dist-tags --json` 或列出 `snapshot-v{base_version}-*` tag 确定构建号，再校验。
- 任一校验失败则停止并说明原因（版本号错误/未发布/Release 或资产缺失），不要继续升级。

### 3. 读取变更信息

按通道取数据，合并展示给开发者。

**正式版**（两个数据源都要）：

```text
# 3a. Git 提交日志（当前版本到目标版本之间的所有提交）
https://github.com/jiangood/open-admin/compare/v{current_version}...v{target_version}

# 3b. 目标版本的 Release Notes（含 Breaking Changes 和迁移说明）
https://github.com/jiangood/open-admin/releases/tag/v{target_version}
```

**预览版**（只有提交日志）：

```text
# 起点：业务项目当前所在的正式版 tag 或上一个快照 tag
# 终点：目标快照 tag，如 snapshot-v3.3.4-1
https://github.com/jiangood/open-admin/compare/v{current_version}...snapshot-v{base_version}-{build}
```

- 当前已在预览版时，起点用上一个快照 tag，例如 `...compare/snapshot-v3.3.4-1...snapshot-v3.3.4-2`
- 预览版没有 Release Notes，Breaking Changes 只能靠提交信息判断；含义不明时提示开发者去仓库确认，不要猜
- 数据源取不到（网络/私有环境）时，如实说明「无法获取变更日志」，不要编造变更内容

展示要点：
- 提交数、参与开发者数
- 按类型归类：feat / fix / refactor / chore
- **高亮 Breaking Changes**（提交信息中含 `BREAKING`、`break`、`migration` 等关键词的提交）
- 正式版额外展示 Release Notes 中的迁移指南部分

### 4. 确认升级

向开发者展示变更概览，询问是否继续。如确认则进入下一步。

### 5. 更新依赖版本

**正式版**

- Maven（`pom.xml`）：找到 `<artifactId>open-admin</artifactId>` 对应的 `<version>`（依赖内或 `<properties>` 变量），更新为目标版本号
- npm（`package.json`）：更新 `"@jiangood/open-admin"` 版本号，保留 `^` 前缀习惯

**预览版**

Maven：

1. 依赖版本改为 `{base_version}-SNAPSHOT`（不带 build 号；Maven 不认 `-SNAPSHOT.1`）
2. 根 `pom.xml` 需有快照仓库（没有则新增到 `<repositories>`）：

   ```xml
   <repository>
     <id>central-snapshots</id>
     <url>https://central.sonatype.com/repository/maven-snapshots/</url>
     <releases><enabled>false</enabled></releases>
     <snapshots><enabled>true</enabled><updatePolicy>always</updatePolicy></snapshots>
   </repository>
   ```

3. 检查全局镜像是否拦住了快照仓库（国内项目常见，`~/.m2/settings.xml`）：

   ```xml
   <mirror>
     <id>aliyun</id>
     <mirrorOf>*,!central-snapshots</mirrorOf>   <!-- 关键：放行 central-snapshots -->
     <url>https://maven.aliyun.com/repository/public</url>
   </mirror>
   ```

   `mirrorOf` 为 `*` 或 `central` 时，快照请求会被镜像接管并返回 404（表现为 `Could not find artifact ...-SNAPSHOT`）。
   **不要擅自修改开发者的全局 `settings.xml`**：把需要改的片段贴给对方确认后再动手，或让开发者自己改。

npm：

- 使用**精确版本**，不加 `^` / `~`（预览版依赖需要可复现）：

  ```bash
  cd web && npm install @jiangood/open-admin@{base_version}-SNAPSHOT.{build}
  ```

- 不要把手写的 dist-tag（如 `"@jiangood/open-admin": "snapshot"`）写进 `package.json`，会导致每次构建版本漂移
- 安装失败先试 `--registry=https://registry.npmjs.org`

### 6. 处理迁移

根据变更信息中的迁移指引，逐项执行代码修改：

| 变更类型 | 典型操作 |
|---------|---------|
| 依赖增减 | 添加/移除 pom.xml 或 package.json 中的依赖项 |
| API 签名变化 | 更新 Java 代码中调用的方法名或参数 |
| 配置变更 | 更新 application.yml 中的配置项 |
| 前端组件变更 | 更新 JSX 中的组件引用 |
| 注解变化 | 更新 `@HasPermission`、`@Log` 等注解的用法 |
| 数据库迁移 | 执行 DDL 或数据迁移脚本（框架已不再使用 Flyway） |

### 7. 编译与测试验证

顺序执行以下步骤，任何一项失败则停止并修复：

```bash
# 7a. 后端编译（预览版必须带 -U，强制刷新 SNAPSHOT）
cd {业务项目目录}
mvn -U clean compile

# 7b. 后端测试
mvn test

# 7c. 前端构建
cd {业务项目目录/web}
npm run build
```

排障：

- **正式版 Maven Central 同步延迟（唯一可能延迟的场景）**：若目标为刚发布的正式版，且 `mvn` 报 `Could not find artifact io.github.jiangood:open-admin:{target_version}`，但第 2 步 npm/GitHub Release 校验已通过，通常是 Maven Central 同步延迟（约 10 分钟）。稍后执行 `mvn -U clean compile` 重试即可；npm、GitHub Release/tag 已即时生效属正常现象。
- `Could not find artifact ...-SNAPSHOT` → 快照仓库缺失，或被全局镜像拦截（见第 5 步）
- 依赖还是旧内容 → 加 `-U`，必要时删掉 `~/.m2/repository/io/github/jiangood/open-admin/{version}` 再拉
- npm 404 → 版本号写错或未同步；用 `npm view @jiangood/open-admin dist-tags --json` 核对

### 8. 框架文件同步（skills + docs）

升级后需将新版本的框架文件同步到业务项目根目录，供 opencode 使用新版本 skill 与文档。同步范围**以目标 tag 内的 `docs/framework-sync.json` 清单为准**，来源统一为 tag 源码归档（正式版/快照版一致，不再使用 `framework-files.zip`）。

**调用 `oa-upgrade-docs` skill**，参数按通道传：

- 正式版：传版本号或 tag，如 `3.4.0` / `v3.4.0`
- 预览版：传快照 tag 或 npm 版本，如 `snapshot-v3.3.4-1` / `3.3.4-SNAPSHOT.1`（skill 会自行换算 tag）

> 若该 skill 不可用或为旧版本（例如历史项目仍依赖已移除的 `framework-files.zip`），按 `oa-upgrade-docs` skill 中的「历史项目引导/自举」或 README「历史项目升级」说明，从目标 tag 归档按清单手工同步一次，不要静默跳过。

### 9. 其他检查（可选）

- 启动项目确认登录/页面正常
- 核心业务流程可用

## 回退

预览版回退成本低，正式版回退需注意数据库/配置改动：

| 场景 | 操作 |
|---|---|
| 预览版 → 正式版 | 版本号改回正式版；`mvn -U clean compile`；`cd web && npm install`；快照仓库可保留（不影响正式依赖） |
| 预览版 → 上一个预览版 | 把 npm 版本改成上一个精确版本，`npm install`；Maven 版本不变（同为 `-SNAPSHOT`，需 `-U` 并在必要时清本地缓存） |

## 验证清单

- [ ] 已识别版本通道（正式版 / 预览版）
- [ ] 目标版本已校验：正式版 npm + GitHub Release 可用；预览版 npm 精确版本 + snapshot tag 存在
- [ ] `mvn compile` 编译通过（预览版用 `mvn -U clean compile`）
- [ ] `mvn test` 全部通过
- [ ] `npm run build` 正常
- [ ] 正式版：Release Notes 中的 Breaking Changes 已逐项处理
- [ ] 预览版：快照仓库已配置，且全局镜像已放行 `central-snapshots`
- [ ] 预览版：npm 依赖为精确版本，未使用 `^` / `~`
- [ ] 提交日志中涉及业务代码的变更已适配
- [ ] 升级后已调用 `oa-upgrade-docs` skill 同步 `.opencode/skills/` 与 `docs/open-admin/`
- [ ] 升级后功能正常（登录、菜单、CRUD 操作）

## 代码规范

- 迁移后的代码遵循 [development.md](../../../docs/open-admin/development.md) 开发规范（命名、构造器注入、`AjaxResult` 等）
- Java import 使用框架的全限定名；前端 import 使用 `@jiangood/open-admin` 包名
- 使用推荐的新 API 替代废弃 API

## 参考

- 框架仓库: https://github.com/jiangood/open-admin
- npm 包页面（`npm view` 查询版本）: https://www.npmjs.com/package/@jiangood/open-admin
- Maven 快照仓库: https://central.sonatype.com/repository/maven-snapshots/
