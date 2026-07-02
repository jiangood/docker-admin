# docker-admin

Multi-host container management platform with CI/CD. UI is Chinese.

## Stack

- **Backend:** Spring Boot 4.1.0 + Java 21, Maven → `target/app.jar`
- **Frontend:** Umi 4 + React 19 + Ant Design 6 + TypeScript 5, dev on port 51105
- **Base framework:** `io.github.jiangood:open-admin` 2.4.6 (handles CRUD, auth, menus)
- **Docker SDK:** docker-java 3.5.3 via TCP (tcp://localhost:2375)
- **Database:** MySQL (default port 3306, override via `db_port`)
- **Entrypoint:** `io.github.jiangood.DockerAdminBootApplication` (`src/main/java/.../DockerAdminBootApplication.java`)

## Commands

```sh
# Build
mvn clean package -DskipTests           # backend only, output target/app.jar
cd web && npm install && npm run build   # frontend only, output web/dist/

# Dev
cd web && npm run dev                    # frontend dev server on :51105

# CI (GitHub Actions, triggers on v* tags)
cd web && npm install && npm run build
cp -r web/dist/* src/main/resources/static
mvn package

# Docker build (multi-stage)
docker build -t docker-admin .
```

## Key facts

- **Active profiles:** `default,prod` at runtime. Config in `src/main/resources/application.yml` (default) + `docker-compose/application-prod.yml` (prod overrides).
- **Database config** is via custom props (`db_ip`, `db_port`, `db_database`, `db_username`, `db_password`), **not** standard Spring datasource.
- **Default admin:** `superAdmin` / printed at startup (default: `jz1@20241029`).
- **Tests** are JUnit-free `main()` methods, not runnable via `mvn test`. Run them individually in IDE.
- **Logs** use Logback SiftingAppender: per-task build logs go to `${LOG_PATH}/{logFileId}.log`.
- **Permissions** via `@HasPermission("app:view")` annotation from open-admin, not standard Spring Security annotations.
- **Frontend** is a thin layer over `@jiangood/open-admin`. Pages live in `web/src/pages/`, config in `web/config/config.js`.
- **Static files** are served from the JAR; in dev, frontend proxies to backend.
- **Menu config** in `src/main/resources/config/application-data-menu.yml`, overridable in `-override.yml`.
- **Docker** daemon must be reachable at `tcp://localhost:2375` (or configured via `open-admin`).
- **CI** pushes Docker image to `ghcr.io/{owner}/docker-admin`. Authenticates via `GITHUB_TOKEN`.
- **Context path:** `server.servlet.context-path=/docker-admin` — so backend and dev proxy are served under `http://host:port/docker-admin/...`.
- **No codegen, no migrations** — schema is managed manually or via open-admin defaults.
