# open-admin v1.0.0 → v2.2.8 Upgrade Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade docker-admin from open-admin 1.0.0 / Spring Boot 3.5.9 / Java 17 → open-admin 2.2.8 / Spring Boot 4.0.6 / Java 21, including frontend package.

**Architecture:** The upgrade involves: (1) build config versions in pom.xml, Dockerfile, CI, package.json, (2) new runtime config spring.config.import, (3) 5 DAO class→interface rewrites, (4) 4 service constructor injection updates, (5) ~10 import path changes across ~22 Java files, (6) frontend package version bump.

**Tech Stack:** Spring Boot 4.0.6, Java 21, Maven, Umi 4, Ant Design 6

---

### Task 1: Build configuration versions (5 files)

**Files:**
- Modify: `pom.xml`
- Modify: `Dockerfile`
- Modify: `.github/workflows/maven.yml`
- Modify: `web/package.json`

- [ ] **Step 1: Update pom.xml — parent, Java, open-admin version**

```xml
<!-- pom.xml line 8-9: parent version -->
<version>4.0.6</version>

<!-- line 16: java version -->
<java.version>21</java.version>

<!-- line 22-23: open-admin dependency -->
<version>2.2.8</version>
```

- [ ] **Step 2: Update Dockerfile — JDK images**

```
Line 12: FROM maven:3-openjdk-21 AS java
Line 23: FROM amazoncorretto:21
```

- [ ] **Step 3: Update CI workflow — JDK version**

`.github/workflows/maven.yml` line 21: `java-version: '21'`

- [ ] **Step 4: Update frontend package version**

`web/package.json` line 14: `"@jiangood/open-admin": "^2.2.8"`

- [ ] **Step 5: Commit build config changes**

```bash
git add pom.xml Dockerfile .github/workflows/maven.yml web/package.json
git commit -m "chore: bump build config for open-admin 2.2.8 / SB 4.0.6 / Java 21"
```

---

### Task 2: Add runtime config for open-admin 2.x

**Files:**
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Add `spring.config.import`**

After line 2 (`db_port: 3306`), add:
```yaml
spring:
  config:
    import: classpath:application-lib.yml
  profiles:
    active: default
  application:
    name: docker-admin
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/application.yml
git commit -m "chore: add spring.config.import for open-admin 2.x lib config"
```

---

### Task 3: Rewrite DAOs — classes to interfaces (5 files)

**Files:**
- Rename: `src/main/java/io/github/jiangood/docker/admin/dao/AppDao.java` → delete, create `AppRepository.java`
- Rename: `src/main/java/io/github/jiangood/docker/admin/dao/ProjectDao.java` → delete, create `ProjectRepository.java`
- Rename: `src/main/java/io/github/jiangood/docker/admin/dao/HostDao.java` → delete, create `HostRepository.java`
- Rename: `src/main/java/io/github/jiangood/docker/admin/dao/BuildLogDao.java` → delete, create `BuildLogRepository.java`
- Rename: `src/main/java/io/github/jiangood/docker/admin/dao/DeployLogDao.java` → delete, create `DeployLogRepository.java`

open-admin 2.x removes `BaseDao<T>` (abstract class) and replaces it with `BaseRepository<T, ID>` (interface extending `JpaRepository + JpaSpecificationExecutor`). Custom query methods using `Spec` must become Spring Data `@Query` or be moved to services. Since these DAOs all use `Spec` internally, the cleanest approach is: **keep the Spec-based custom methods by inlining them directly in the service classes**, converting the DAOs to pure Spring Data repository interfaces.

**AppDao.java** — custom method `findByTagIsNull()`:
This is only used in `AppService.onBuildSuccess()` at line 387: `List<App> list = appDao.findAll();` — wait, that's `findAll()`, not `findByTagIsNull()`. Let me check.

Actually looking at `AppService.java`:
- Line 387: `List<App> list = appDao.findAll();` — this uses inherited `findAll()` from `BaseDao`, which `BaseRepository` also has via `JpaRepository`.
- The custom `findByTagIsNull()` is defined in AppDao but not called anywhere in AppService. It might be unused.

For the repositories, we define them as interfaces extending `BaseRepository<Entity, String>`. Custom `Spec`-based methods that are still used (like `findByNameLike`, `findTop1ByIsRunnerOrderByModifyTimeDesc`, etc.) need to be reimplemented — but actually `BaseRepository` inherits `JpaSpecificationExecutor` which provides `findAll(Specification)`, so the Spec-based logic can move to the services.

The safest approach: **Repository interfaces have NO custom methods — all Spec-based queries move to the service layer**, since `BaseRepository` already provides `findAll(Specification)` and `findOne(Specification)` through `JpaSpecificationExecutor`.

- [ ] **Step 1: Create AppRepository.java**

```java
package io.github.jiangood.docker.admin.dao;

import io.github.jiangood.docker.admin.entity.App;
import io.github.jiangood.openadmin.framework.data.BaseRepository;

public interface AppRepository extends BaseRepository<App, String> {
}
```

Delete `AppDao.java`.

- [ ] **Step 2: Create ProjectRepository.java**

```java
package io.github.jiangood.docker.admin.dao;

import io.github.jiangood.docker.admin.entity.Project;
import io.github.jiangood.openadmin.framework.data.BaseRepository;

public interface ProjectRepository extends BaseRepository<Project, String> {
}
```

Delete `ProjectDao.java`. The custom method `findByNameLike` moves to `ProjectService.findAll()`.

- [ ] **Step 3: Create HostRepository.java**

```java
package io.github.jiangood.docker.admin.dao;

import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.openadmin.framework.data.BaseRepository;

public interface HostRepository extends BaseRepository<Host, String> {
}
```

Delete `HostDao.java`. The custom method `findTop1ByIsRunnerOrderByModifyTimeDesc` moves to `HostService.getDefaultDockerRunner()`.

- [ ] **Step 4: Create BuildLogRepository.java**

```java
package io.github.jiangood.docker.admin.dao;

import io.github.jiangood.docker.admin.entity.BuildLog;
import io.github.jiangood.openadmin.framework.data.BaseRepository;

public interface BuildLogRepository extends BaseRepository<BuildLog, String> {
}
```

Delete `BuildLogDao.java`. All 5 custom methods move to `BuildLogService`.

- [ ] **Step 5: Create DeployLogRepository.java**

```java
package io.github.jiangood.docker.admin.dao;

import io.github.jiangood.docker.admin.entity.DeployLog;
import io.github.jiangood.openadmin.framework.data.BaseRepository;

public interface DeployLogRepository extends BaseRepository<DeployLog, String> {
}
```

Delete `DeployLogDao.java`.

- [ ] **Step 6: Commit DAO changes**

```bash
git add src/main/java/io/github/jiangood/docker/admin/dao/
git rm src/main/java/io/github/jiangood/docker/admin/dao/*Dao.java
git commit -m "refactor: convert DAO classes to BaseRepository interfaces"
```

---

### Task 4: Update Service constructors and inline DAO methods (4 files)

**Files:**
- Modify: `src/main/java/io/github/jiangood/docker/admin/service/HostService.java`
- Modify: `src/main/java/io/github/jiangood/docker/admin/service/ProjectService.java`
- Modify: `src/main/java/io/github/jiangood/docker/admin/service/BuildLogService.java`
- Modify: `src/main/java/io/github/jiangood/docker/admin/service/AppService.java`

Each service needs: (a) remove `@Resource`-injected DAO, (b) add `private final` Repository + constructor, (c) replace old DAO queries with inlined `Spec` calls via the repository.

- [ ] **Step 1: Update HostService.java**

Replace the field injection and constructor:

```java
// Remove:
// @Resource
// HostDao hostDao;

// Add:
private final HostRepository hostRepository;

public HostService(HostRepository hostRepository) {
    super(hostRepository);
    this.hostRepository = hostRepository;
}
```

Update `getDefaultDockerRunner()` to inline the Spec query:
```java
public Host getDefaultDockerRunner() {
    Spec<Host> q = Spec.of();
    q.eq(Host.Fields.isRunner, true);
    return hostRepository.findOne(q, Sort.by(Sort.Direction.DESC, "updateTime")).orElse(null);
}
```

Update all other `hostDao.xxx()` calls to `hostRepository.xxx()`:
- `hostDao.findOne(id)` → `hostRepository.findById(id).orElse(null)` (or keep `hostRepository.findOne(Spec.of().eq("id", id))` — but actually `BaseRepository` from open-admin likely has `findById` from JpaRepository)
- `hostDao.count()` → `hostRepository.count()`

Since `BaseService<T>` uses the inherited `BaseRepository<T, String>` internally through `getRepository()`, and the existing service methods like `findOne`, `save`, `deleteById`, `findAll`, `findPageByRequest` are all from `BaseService`, they don't need changes. Only `hostDao.xxx()` direct calls need conversion.

For services that call `dao.findAll(pageable)` (like `ProjectService.findAll()`), the repository also has `findAll(Pageable)` from JpaRepository.

- [ ] **Step 2: Update ProjectService.java**

```java
// Remove:
// @Resource
// ProjectDao projectDao;

// Add:
private final ProjectRepository projectRepository;

public ProjectService(ProjectRepository projectRepository) {
    super(projectRepository);
    this.projectRepository = projectRepository;
}
```

Update `findAll()` method:
```java
public Page<Project> findAll(String searchText, Pageable pageable) {
    if (StrUtil.isNotEmpty(searchText)) {
        Spec<Project> q = Spec.of();
        q.like("name", "%" + searchText.trim() + "%");
        return projectRepository.findAll(q, pageable);
    }
    return projectRepository.findAll(pageable);
}
```

Update `buildImageJob()`: `projectDao.findOne(projectId)` → `projectRepository.findById(projectId).orElse(null)`

- [ ] **Step 3: Update BuildLogService.java**

```java
// Remove:
// @Resource
// BuildLogDao dao;

// Add:
private final BuildLogRepository buildLogRepository;

public BuildLogService(BuildLogRepository buildLogRepository) {
    super(buildLogRepository);
    this.buildLogRepository = buildLogRepository;
}
```

Inline all 5 custom DAO methods. Note: `saveLog` uses `dao.saveAndFlush()` which is available from `JpaRepository.saveAndFlush()`.

```java
public List<String> versions(String projectId) {
    Spec<BuildLog> q = Spec.of();
    q.eq(BuildLog.Fields.projectId, projectId);
    q.eq(BuildLog.Fields.success, true);
    List<BuildLog> list = buildLogRepository.findAll(q);
    List<String> versions = list.stream().map(BuildLog::getVersion).distinct().collect(Collectors.toList());
    Collections.sort(versions);
    Collections.reverse(versions);
    return versions;
}

public BuildLog saveLog(BuildLog buildLog) {
    return buildLogRepository.saveAndFlush(buildLog);
}

public List<BuildLog> findByProject(String projectId) {
    Spec<BuildLog> q = Spec.of();
    q.eq(BuildLog.Fields.projectId, projectId);
    return buildLogRepository.findAll(q);
}

public void cleanErrorLog(String projectId) {
    Spec<BuildLog> q = Spec.of();
    q.eq(BuildLog.Fields.projectId, projectId);
    q.eq(BuildLog.Fields.success, false);
    List<BuildLog> list = buildLogRepository.findAll(q);
    buildLogRepository.deleteAll(list);
}

public List<BuildLog> findByProjectProcessing(String projectId) {
    Spec<BuildLog> q = Spec.of();
    q.eq(BuildLog.Fields.projectId, projectId);
    q.isNull(BuildLog.Fields.success);
    return buildLogRepository.findAll(q);
}

public BuildLog findTop1ByProject(String projectId) {
    Spec<BuildLog> q = Spec.of();
    q.eq(BuildLog.Fields.projectId, projectId);
    return buildLogRepository.findOne(q, Sort.by("createTime")).orElse(null);
}
```

- [ ] **Step 4: Update AppService.java**

```java
// Remove:
// @Resource
// private AppDao appDao;
// @Resource
// private HostDao hostDao;
// @Resource
// DeployLogDao deployLogDao;

// Add:
private final AppRepository appRepository;
private final HostRepository hostRepository;
private final DeployLogRepository deployLogRepository;

public AppService(AppRepository appRepository, HostRepository hostRepository, DeployLogRepository deployLogRepository) {
    super(appRepository);
    this.appRepository = appRepository;
    this.hostRepository = hostRepository;
    this.deployLogRepository = deployLogRepository;
}
```

Replace all `appDao.xxx()` calls with `appRepository.xxx()`:
- `appDao.save(x)` → `appRepository.save(x)`
- `appDao.findOne(x)` → `appRepository.findById(x).orElse(null)`
- `appDao.deleteById(x)` → `appRepository.deleteById(x)`
- `appDao.findAll()` → `appRepository.findAll()`

Replace `hostDao.findOne(x)` with `hostRepository.findById(x).orElse(null)`.

Replace `deployLogDao.save(x)` with `deployLogRepository.save(x)`.

Replace `CodeException` with `BusinessException`:
```java
// Remove:
// import io.github.jiangood.openadmin.framework.CodeException;
// Add:
import io.github.jiangood.openadmin.util.BusinessException;

// line 328: throw new CodeException("查询容器状态失败", e);
// Change to:
throw new BusinessException("查询容器状态失败", e);
```

- [ ] **Step 5: Commit service changes**

```bash
git add src/main/java/io/github/jiangood/docker/admin/service/
git commit -m "refactor: update services with constructor injection and inlined Spec queries"
```

---

### Task 5: Update import paths — entities (5 files)

**Files:**
- Modify: `src/main/java/io/github/jiangood/docker/admin/entity/App.java`
- Modify: `src/main/java/io/github/jiangood/docker/admin/entity/Project.java`
- Modify: `src/main/java/io/github/jiangood/docker/admin/entity/Host.java`
- Modify: `src/main/java/io/github/jiangood/docker/admin/entity/BuildLog.java`
- Modify: `src/main/java/io/github/jiangood/docker/admin/entity/DeployLog.java`

Replace imports:

| File | Old import | New import |
|------|-----------|------------|
| App.java | `framework.data.domain.BaseEntity` | `framework.data.BaseEntity` |
| App.java | `common.tools.annotation.Remark` | `util.annotation.Remark` |
| Project.java | `framework.data.domain.BaseEntity` | `framework.data.BaseEntity` |
| Project.java | `common.tools.annotation.Remark` | `util.annotation.Remark` |
| Host.java | `framework.data.domain.BaseEntity` | `framework.data.BaseEntity` |
| Host.java | `common.tools.annotation.Remark` | `util.annotation.Remark` |
| BuildLog.java | `framework.data.domain.BaseEntity` | `framework.data.BaseEntity` |
| DeployLog.java | `framework.data.domain.BaseEntity` | `framework.data.BaseEntity` |

Use `replaceAll: true` on each file for package path replacements.

- [ ] **Step 1: Update entity imports (5 files)**
- [ ] **Step 2: Commit**

```bash
git add src/main/java/io/github/jiangood/docker/admin/entity/
git commit -m "chore: update entity import paths for open-admin 2.2.8"
```

---

### Task 6: Update import paths — controllers and other Java files (7 files)

**Files:**
- Modify: `src/main/java/io/github/jiangood/docker/admin/controller/AppController.java`
- Modify: `src/main/java/io/github/jiangood/docker/admin/controller/ProjectController.java`
- Modify: `src/main/java/io/github/jiangood/docker/admin/controller/HostController.java`
- Modify: `src/main/java/io/github/jiangood/docker/admin/controller/HomeController.java`
- Modify: `src/main/java/io/github/jiangood/docker/admin/controller/BuildLogController.java`
- Modify: `src/main/java/io/github/jiangood/docker/admin/controller/ContainerController.java`
- Modify: `src/main/java/io/github/jiangood/docker/admin/controller/LogUrlTool.java`
- Modify: `src/main/java/io/github/jiangood/docker/base/DockerExceptionHandler.java`
- Modify: `src/main/java/io/github/jiangood/docker/admin/entity/converter/AppConfigConverter.java`

Replace mappings (use `replaceAll` on `old package` → `new package` per file):

| Old | New | Affected files |
|-----|-----|---------------|
| `common.dto.AjaxResult` | `util.dto.AjaxResult` | all 6 controllers + DockerExceptionHandler |
| `common.dto.antd.Option` | `util.dto.Option` | AppController, ProjectController, HostController |
| `modules.common.LoginTool` | `framework.auth.LoginTool` | AppController, ProjectController |
| `framework.config.argument.RequestBodyKeys` | `framework.config.RequestBodyKeys` | AppController, ProjectController, HostController |
| `common.tools.SpringTool` | `util.SpringTool` | LogUrlTool |
| `common.tools.JsonTool` | `util.JsonTool` | AppConfigConverter |
| `framework.CodeException` | `util.BusinessException` | AppService (already done in Task 4) |

- [ ] **Step 1: Update imports in all 9 files using replaceAll per package path change**
- [ ] **Step 2: Commit**

```bash
git add src/main/java/io/github/jiangood/docker/admin/controller/ src/main/java/io/github/jiangood/docker/base/ src/main/java/io/github/jiangood/docker/admin/entity/converter/
git commit -m "chore: update controller/util import paths for open-admin 2.2.8"
```

---

### Task 7: Fix BaseExceptionHandler import

**Files:**
- Modify: `src/main/java/io/github/jiangood/base/DockerExceptionHandler.java`

This file catches `CodeException` — now it should catch `BusinessException`:

```java
// import io.github.jiangood.openadmin.framework.CodeException;
import io.github.jiangood.openadmin.util.BusinessException;
```

- [ ] **Step 1: Update import and exception class name in DockerExceptionHandler**
- [ ] **Step 2: Commit**

---

### Task 8: Full build verification

- [ ] **Step 1: Maven compilation check**

Run: `mvn clean compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 2: Frontend build check**

```bash
cd web
npm install
npm run build
```
Expected: `web/dist/` generated without errors

- [ ] **Step 3: Full package**

```bash
mvn clean package -DskipTests
```
Expected: `target/app.jar` generated

- [ ] **Step 4: Final commit of all remaining changes**

```bash
git add -A
git commit -m "chore: complete open-admin 2.2.8 upgrade"
```
