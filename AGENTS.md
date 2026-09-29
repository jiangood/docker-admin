# docker-admin

Multi-host container management platform with CI/CD. UI is Chinese.

## Stack

- **Backend:** Spring Boot 4.1.0 + Java 21, Maven → `target/app.jar`（`<finalName>app</finalName>`）
- **Frontend:** Vite 8 + React 19 + Ant Design 6 + TypeScript 7
- **Base framework:** `io.github.jiangood:open-admin` 3.1.2（后端 Maven）/ `@jiangood/open-admin` 3.1.2（前端 npm），提供 CRUD、鉴权、菜单
- **Docker SDK:** docker-java 3.7.1（TCP 传输 httpclient5）
- **SSH 远程 docker:** `com.github.mwiede.dockerjava:docker-java-transport-jsch` 1.4
- **数据库:** 默认内置 H2（文件模式，持久化到 `./data`），也支持外部 MySQL
- **Entrypoint:** `io.github.jiangood.docker.DockerAdminBootApplication`（`src/main/java/.../DockerAdminBootApplication.java`）

## Commands

```sh
# Build
mvn clean package -DskipTests           # backend only, output target/app.jar
cd web && npm install && npm run build   # frontend only, output web/dist/

# Dev
cd web && npm run dev                    # frontend dev server on :7700, proxies to :7701
```

## Key facts

- **端口：** 后端 `7701`（`application.yml`；`Dockerfile` 内置 `ENV SERVER_PORT=7701`，本地开发与容器同一端口），
  前端 dev `7700`（`web/.env`）。`docker run` 无需再传 `-e SERVER_PORT`。
- **Context path:** 默认 `/docker-admin`（`application.yml`，仅本地开发用）；
  容器部署由 `Dockerfile` 内置的 `ENV SERVER_SERVLET_CONTEXT_PATH=/` 覆盖为根路径。
  环境变量优先级高于 `application.yml`，因此 `docker run` 与 `docker compose` 行为一致，
  且可用 `-e SERVER_SERVLET_CONTEXT_PATH=/docker-admin` 覆盖回带前缀的路径。
  **已无 `prod` profile，也没有 `application-<profile>.yml` 覆盖文件。**
  - 本地开发必须用 `VITE_SERVER_SERVLET_CONTEXT_PATH=/docker-admin`（见 `web/.env.development`），
    否则 Vite 代理键为 `/` 会把前端静态资源请求也转发到后端。
- **Profiles:** 只用默认 `default`。
  compose 编排见仓库根目录 `docker-compose.yml`。
- **依赖版本集中管理在 `pom.xml` 的 `<properties>` + `<dependencyManagement>`**，不要在 `<dependencies>` 里硬编码版本。
  - `hutool.version` 必须与 open-admin 传递引入的 hutool 各模块同版本，改动前用
    `mvn dependency:tree -Dincludes=cn.hutool` 核对。
  - `docker-java.version` 核心与传输层共用同一属性，保持同版本。
  - lombok **不显式指定版本**，由 `spring-boot-starter-parent` 管理（当前 1.18.46）。
- **数据库配置**走自定义属性（`db_ip`、`db_port`、`db_database`、`db_username`、`db_password`），
  不是标准 Spring datasource；默认走 H2 无需配置。
- **菜单配置**在 `src/main/resources/application-menu-docker.yml`，可用 `-override.yml` 覆盖。
- **Tests** 是 `main()` 方法（如 `DockerSdkTest`、`PrintTest`），**不是** JUnit 测试，
  无法通过 `mvn test` 运行，需要在 IDE 里单独执行。
- **权限**用 open-admin 的 `@HasPermission("app:view")`，不是标准 Spring Security 注解。
- **前端**是 `@jiangood/open-admin` 之上的薄层，页面在 `web/src/pages/`，构建配置 `web/vite.config.js`。
- **静态文件**从 JAR 提供；开发时前端通过 Vite 代理访问后端。
- **CodeMirror 5** 被锁定在 5.x（`codemirror@^5.0.0`）。CM6 是破坏性重写，
  `web/src/components/CodeMirrorEditor.jsx` 依赖 CM5 专有路径与 API，
  升级前必须先完成迁移。原因见 `.github/dependabot.yml` 中的 ignore 规则。
- **`web/package-lock.json` 已纳入版本管理**，构建应可复现，不要再 gitignore 它。
- **CI：** `.github/workflows/build.yml` 在 push/PR 上跑前后端构建；
  `.github/workflows/publish-docker-image.yml` 在 `v*` tag 上发布镜像到 ghcr.io 与阿里云。
- **No codegen, no migrations** — schema 手工管理或使用 open-admin 默认结构。
