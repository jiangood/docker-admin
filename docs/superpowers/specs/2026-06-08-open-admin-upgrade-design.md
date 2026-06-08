# open-admin v1.0.0 → v2.2.8 升级设计

## 概览

将 `docker-admin` 从 open-admin 1.0.0 / Spring Boot 3.5.9 / Java 17 升级到 open-admin 2.2.8 / Spring Boot 4.0.6 / Java 21，同时升级前端 `@jiangood/open-admin` 到 v2.2.8。

## 任务清单

### A. 构建配置（6 个文件）

| 文件 | 变更 |
|------|------|
| `pom.xml` | parent → `spring-boot-starter-parent:4.0.6`; `<java.version>` → `21`; `open-admin` → `2.2.8` |
| `Dockerfile` | 构建镜像 `maven:3-openjdk-17` → `maven:3-openjdk-21`; 运行时 `amazoncorretto:17` → `amazoncorretto:21` |
| `.github/workflows/maven.yml` | `java-version: 17` → `21` |
| `web/package.json` | `@jiangood/open-admin: "1.0.0"` → `"^2.2.8"` |

### B. 运行时配置（1 个文件）

`src/main/resources/application.yml` 追加：

```yaml
spring:
  config:
    import: classpath:application-lib.yml
```

### C. Java 导入路径更新（~22 个文件）

所有 `.java` 文件中以下 import 路径替换：

| 旧包 | 新包 | 涉及文件 |
|------|------|----------|
| `common.dto.AjaxResult` | `util.dto.AjaxResult` | 7 个 controller + DockerExceptionHandler |
| `common.dto.antd.Option` | `util.dto.Option` | ProjectController, HostController, AppController |
| `modules.common.LoginTool` | `framework.auth.LoginTool` | ProjectController, AppController |
| `common.tools.SpringTool` | `util.SpringTool` | LogUrlTool |
| `common.tools.JsonTool` | `util.JsonTool` | AppConfigConverter |
| `common.tools.annotation.Remark` | `util.annotation.Remark` | App, Project, Host（entity 类） |
| `framework.data.domain.BaseEntity` | `framework.data.BaseEntity` | App, Project, Host, BuildLog, DeployLog |
| `framework.data.service.BaseService` | `framework.data.BaseService` | HostService, ProjectService, BuildLogService, AppService |
| `framework.config.argument.RequestBodyKeys` | `framework.config.RequestBodyKeys` | ProjectController, HostController, AppController |
| `framework.CodeException` | `util.BusinessException` | AppService（类名也变了） |

### D. DAO 重写（5 个文件）

每个 DAO 从 `class extends BaseDao<Entity>` 改为 `interface extends BaseRepository<Entity, String>`：

| 旧文件 | 新文件 | 自定义方法 |
|--------|--------|------------|
| `AppDao` | `AppRepository` | — |
| `ProjectDao` | `ProjectRepository` | `findByProjectIdOrderByCreateTimeDesc`（需确认） |
| `HostDao` | `HostRepository` | — |
| `BuildLogDao` | `BuildLogRepository` | — |
| `DeployLogDao` | `DeployLogRepository` | — |

所有 `@Resource`/`@Autowired` 注入 DAO 的地方需同步更新类型。

### E. Service 构造函数注入（4 个文件）

`BaseService<T>` 现在要求构造函数注入 `BaseRepository<T, String>`：

| 文件 | 变更 |
|------|------|
| `HostService` | 加 `private final HostRepository` 和构造参数 |
| `ProjectService` | 同上 |
| `BuildLogService` | 同上 |
| `AppService` | 同上 + `CodeException` → `BusinessException` |

模式：

```java
@Service
@RequiredArgsConstructor
public class HostService extends BaseService<Host> {
    private final HostRepository hostRepository;
    public HostService(HostRepository hostRepository) {
        super(hostRepository);
    }
}
```

### F. 验证步骤

```
1. mvn clean compile          # 确保 Java 编译通过
2. cd web && npm install && npm run build  # 前端构建通过
3. mvn clean package -DskipTests  # 完整打包
4. 启动应用，验证登录、项目/应用/主机 CRUD 页面可用
```
