# docker-admin

Multi-host container management platform with CI/CD. UI is Chinese.

## Stack

- **Backend:** Spring Boot 4.1.0 + Java 21, Maven → `target/app.jar`
- **Frontend:** Umi 4 + React 19 + Ant Design 6 + TypeScript 5, dev on port 51105
- **Base framework:** `io.github.jiangood:open-admin` 3.1.2 (handles CRUD, auth, menus)
- **Docker SDK:** docker-java 3.7.1 via TCP (tcp://localhost:2375)
- **Database:** H2 内置数据库（文件模式，MySQL 兼容模式），数据文件默认 `/data/db/docker-admin`，通过 `db_path` 覆盖
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

- **Active profile:** `default` at runtime. Base config in `src/main/resources/application.yml`; the optional `docker-compose/application-prod.yml` is mounted to `/home/application.yml` (higher precedence) for port/registry/git settings.
- **Database:** embedded H2 in MySQL compatibility mode (`jdbc:h2:file:${db_path};MODE=MySQL`). Tables are auto-created by Hibernate (`ddl-auto: update`) and seeded by open-admin's Flyway migrations; no external database required.
- **Default admin:** `admin` / `Open@1234` (seeded by open-admin Flyway migration `V10000__framework__seed_data.sql`; change it after first login).
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
