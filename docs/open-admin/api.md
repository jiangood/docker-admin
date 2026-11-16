# API 参考

> 后端（Spec / 注解 / 工具类 / 定时任务）与前端（组件 / 字段组件 / 工具类）API 参考。

## 后端

### Spec 动态查询

```java
Spec<User> spec = Spec.of()
    .eq("status", 1).like("name", "张")
    .between("createTime", start, end)
    .or(Spec.of().like("name", "张"), Spec.of().like("name", "李"))
    .eq("user.id", userId);  // 关联查询
repository.findAll(spec, pageable);
```

### CrudOps CRUD 辅助

`io.github.jiangood.openadmin.framework.web.CrudOps` 收敛 Controller 固定逻辑（代码生成器默认使用）：

| 方法 | 说明 |
|------|------|
| `info(Optional<T>, notFoundMsg)` | 存在返回 `data`，否则 `AjaxResult.err(msg)` |
| `page(service, pageable, specCustomizer)` | 构造 Spec 并分页；`specCustomizer` 可为 `null` |
| `created()` / `created(saved)` / `updated()` / `deleted()` | 标准成功响应 |
| `requireUpdate(id, fields, existingSupplier, notFoundMsg)` | 校验 id 非空、确有字段提交、记录存在，返回已有记录 |
| `requireDelete(existing, notFoundMsg)` | 校验记录存在，返回已有记录（便于写日志详情） |

校验失败抛 `BusinessException`，由全局异常处理器转成 `AjaxResult.err` 业务文案。`requireUpdate` 先校验 id/字段，再按需查询，id 为空时不会执行无效查询。

```java
@PostMapping("update")
public AjaxResult update(@RequestBody Customer input,
        @RequestHeader(value = "X-Body-Fields", required = false) List<String> updateFields) {
    CrudOps.requireUpdate(input.getId(), updateFields,
            () -> service.findById(input.getId()), "记录不存在");
    service.save(input, updateFields);
    return CrudOps.updated();
}
```

### 注解

| 注解 | 用途 |
|------|------|
| `@HasPermission("resource:action")` | 权限控制 |
| `@Log("业务-操作")` | 操作日志（可加 `detail` 详情模板） |
| `@RateLimit(count=10, duration=60)` | IP 限流 |
| `@JobDescription` | 定时任务定义（`key` / `label` / `defaultCron` / `defaultEnabled` / `logAggregation` / `autoRegister`） |
| `@ValidateMobile` / `@ValidateIdNum` / ... | 字段格式校验 |

### 操作日志

| 写法 | 用途 |
|---|---|
| `@Log("用户-创建")` | 只记录操作名 |
| `@Log(value = "用户-创建", detail = "新增用户「${#input.name}」")` | 声明式详情；`${}` 内是 SpEL，变量为方法参数（`#参数名`，或 `#p0` / `#a0`） |
| `OperationLogContext.setDetail("删除用户「" + user.getName() + "」")` | 代码补充详情，适合只有 id、或需要旧值的场景（优先级高于注解模板） |

- 求值失败或结果为 null 时按空串处理，不影响业务方法；详情最长 500 字符
- 部分更新场景可用 SpEL 的 `?:` 兜底，如 `${#input.name ?: #input.id}`，避免出现空的「」
- **不要拼接密码、令牌等敏感字段**：`params` 会按字段名自动脱敏，`detail` 不会
- 日志页的「操作详情」列来自 `detail`，请求参数在行详情抽屉里查看
- 每条日志同时记录 IP 与浏览器指纹：前端 `HttpClient` 自动附加请求头 `X-Browser-Fingerprint`（值为浏览器本地缓存的设备特征哈希），日志页可按完整指纹或备注名筛选；指纹可被伪造，仅作辅助排查，不能作为安全凭证
- 日志页可直接给指纹加备注（备注名 + 说明，如「张三的办公电脑-Chrome」），备注按指纹保存在 `sys_browser_fingerprint` 表，跨日志复用；只有**主动标注**过的指纹才会进入该表，未标注的指纹只存在于操作日志中，不会自动入库
- 备注编辑/取消标记需要 `sys-log:write` 权限；在日志页点击指纹即可标记，已有标记时弹窗左下角提供「取消标记」，备注名和说明都留空保存也等同于取消标记
- 登录/登出由框架手动写入（登录时尚未认证、登出时上下文已清空，切面拿不到操作者）：操作名为「认证-登录」「认证-登录失败」「认证-登出」，失败登录会记录原因（如「账号或密码错误」「账户已被锁定」），不记录请求参数，避免密码进入日志

### 工具类

| 类 | 主要方法 |
|----|---------|
| `ExcelTool` | `importExcel` / `exportExcel` |
| `JdbcRunner` (framework.data) | `findById` / `findAll` / `save` / `deleteById` / `count` |
| `JdbcValues` | `asLong` / `roundedLong` / `asInt` / `asDouble` / `bool` / `triBool` / `str` / `strTrim` / `strOrNull` / `localDateTime` / `epochSec` / `longOrNull` / `intOrNull` |
| `FileTool` | `resolveUnder(root, name)` / `existingFile(root, name)` 目录穿越安全解析（`LocalFileOperator` 已复用）；`findByMainName` / `findParentByName` |
| `ThreadTool` | `execute` / `daemon` / `daemonFixed` / `singleScheduler` / `singleWorker` / `boundedPool` / `close` / `closeNow` / `sleepQuietly` |
| `LoginTool` | `getUserId` / `getUser` / `getPermissions` / `isAdmin` |
| `TreeTool` | `buildTree` / `walk` / `treeToList` / `getLeafs` |
| `BeanTool` / `JsonTool` / `StringTool` | 常用对象/JSON/字符串操作 |
| `PasswordTool` | 密码加密 |

`JdbcValues` 用于 `JdbcRunner` 返回的 `Map<String, Object>` 取值：`asLong` 截断、`roundedLong` 四舍五入、`triBool` 区分「未知」与 `false`，不要直接强转。`ThreadTool` 的线程工厂一律 daemon；`boundedPool` 队列满时抛 `RejectedExecutionException`；`close` 最多等在途任务 5 秒。取本地文件时优先用 `FileTool.resolveUnder` / `existingFile`，不要自行拼接目录。

### 定时任务

```java
@JobDescription(label = "数据同步", params = {
    @FieldDescription(name = "syncType", label = "同步类型", required = true)
})
public class DataSyncJob extends BaseJob {
    public String execute(JobDataMap data, Logger logger) { ... }
}
```

启动时框架会按 `BaseJob` 子类 / `@JobDescription` 自动补建缺失的 `sys_job` 记录（默认禁用，不覆盖已有配置），也支持 `JobRegistrar` SPI 显式注册；`key` / `defaultCron` / `defaultEnabled` / `logAggregation` / `autoRegister` 见 [development.md](development.md) 的「自动注册」。

进度上报（`BaseJob` 受保护方法，供前端「定时任务」页面轮询展示）：

| 方法 | 说明 |
|------|------|
| `progress(int percent, String message)` | 直接上报百分比（0-100） |
| `progress(long current, long total, String message)` | 上报「已处理/总数」，百分比自动计算 |
| `progress(String message)` | 仅更新描述，进度未知时使用 |

进度只保存在内存（`JobProgressRegistry`），重启后丢失；执行结果仍由 `sys_job_log` 落库。

> cron 使用 Quartz 格式，共 6 或 7 段：**秒 分 时 日 月 周 [年]**，第一位是秒。例如 `0 */5 * * * ?` 表示每 5 分钟，`*/5 * * * * ?` 表示每 5 秒。

| 端点 | HTTP | 权限 | 说明 |
|------|------|------|------|
| `admin/job/progress` | GET | `job:read` | 全部任务运行进度快照，按开始时间倒序 |
| `admin/job/progress/{jobId}` | GET | `job:read` | 单个任务进度，从未运行时 `data` 为 `null` |

快照字段：`jobId` / `jobName` / `logId` / `state`(`RUNNING`/`SUCCESS`/`FAILED`) / `stateLabel` / `stateColor` / `percent` / `current` / `total` / `message` / `beginTime` / `updateTime` / `endTime` / `runTime` / `result`。

页面顶部统计卡片（运行中 / 今日执行 / 日志占用 / 调度器）：

| 端点 | HTTP | 权限 | 说明 |
|------|------|------|------|
| `admin/job/stat` | GET | `job:read` | `jobCount` / `enabledCount` / `runningCount` / `todayExecuteCount` / `todayFailCount` / `scheduler` |
| `admin/job/log-size` | GET | `job:read` | 全部任务日志占用：`bytes` / `files` / `warnMb` / `warn` |
| `admin/job/{jobId}/log-size` | GET | `job:read` | 指定任务的日志占用：`bytes` / `files` |

`admin/job/stat` 的 `scheduler` 字段：`version` / `schedulerName` / `instanceId` / `started` / `standby` / `runningSince` / `jobsExecuted` / `threadPoolSize` / `jobStoreClass` / `persistent` / `clustered`，读取失败时带 `error`。

日志占用只读统计 `${logging.file.path}/job/*.log`，不会创建目录；结果在服务端缓存 30 秒，删除任务或执行记录清理后会主动失效。超过 `sys.job-log-size-warn-mb`（默认 1024 MB，`<=0` 关闭）时 `warn` 为 `true`，管理页以红色提示，不自动清理；清理仍由 `sys.job-log-retain-days` 控制（任务执行后惰性触发，见下文）。

### 执行记录方式

`SysJob.logAggregation`（前端字段名「执行记录方式」，新建/编辑任务时选择，字典 `jobLogAggregation`）控制执行记录与日志文件粒度：

| 取值 | 执行记录 | 日志文件 |
|---|---|---|
| `PER_RUN` 每次一份记录（默认） | 每次一行 | 每次一个文件 |
| `HOURLY` 每小时一份记录 | 每任务每小时一行 | 每任务每小时一个文件 |
| `DAILY` 每天一份记录 | 每任务每天一行 | 每任务每天一个文件 |

聚合记录的 `SysJobLog` 额外字段：`logBucket`（聚合桶）、`executeCount`（执行次数）、`failCount`（失败次数）、`totalRunTime`（累计耗时）/`totalRunTimeLabel`；`jobRunTime`/`result` 为最近一次，`success` 表示周期内是否全部成功。执行记录与日志文件按 `sys.job-log-retain-days`（默认 30 天）惰性清理：任意定时任务执行结束后触发一次检查（同一自然日最多清理一次，清理在独立线程异步执行），没有任务执行就不会触发；删除任务时也会一并删除其执行记录与日志文件。

### 运行日志

前端展示的文件日志（定时任务、以及业务模块自己的异步任务）统一由 `modules/logviewer/` 提供：日志写入、增量读取与查看权限按 key 前缀解耦。

写入（`FileLog`）：`bind(key)` 把当前线程绑定到 `${logging.file.path}/{key}.log`，`logger()` 取日志 logger，`unbind()` 解绑；写入哪个文件只取决于当前线程绑定的 key，与包名/类名无关。

| 端点 | HTTP | 权限 | 说明 |
|------|------|------|------|
| `admin/sys/log/{key}` | GET | 按 key 前缀注册（见下） | 增量读取日志，返回 `{content, nextOffset, eof}` |
| `admin/sys/log/raw/{key}` | GET | 同上 | 纯文本返回完整日志，供新窗口打开 |

> **升级注意（3.1.9）**：`admin/sys/log/{key}` 在 3.1.8 及之前返回 `text/plain` 全量内容，3.1.9 起改为增量 JSON（纯文本改用 `admin/sys/log/raw/{key}`）；查看权限由硬编码 `job:read` 改为按 key 前缀注册，未注册的前缀一律拒绝。原 `FileLogTool` 已更名为 `FileLog`。

`admin/sys/log/{key}` 查询参数：`offset`（起始字节偏移，默认 0）、`length`（单次最多读取字节数，默认 256KB，上限 4MB）。日志会随任务持续增长，前端按返回的 `nextOffset` 反复请求即可只取新增部分；读取按 UTF-8 字符边界切分，不会截断多字节字符。

查看权限由各模块通过实现 `LogKeyPermissionProvider` 声明（key 前缀 → 权限），通用模块不写死业务权限，未注册的前缀一律拒绝：

```java
@Component
public class OrderLogPermissionProvider implements LogKeyPermissionProvider {
    @Override
    public Map<String, String> logKeyPermissions() {
        return Map.of("order", "order:read");   // order/{logId} 需要 order:read
    }
}
```

前端可直接使用框架的 `LogViewer` 组件增量跟随运行中的日志（参见[组件](#组件)）。

日志如果属于业务数据、需要行级/数据权限校验，不要复用上面的通用接口（它只能校验权限码）：由业务模块自己提供读取接口，并让 `LogViewer` 通过 `url` 指向它，返回同样的 `{content, nextOffset, eof}` 即可复用增量跟随。

### 系统参数

运行时可改的全局参数，以及页面顶部只读展示的启动期参数。元数据由业务模块/框架实现 `ConfigProvider` 声明，`sys_config` 只保存被覆盖的值（见 [config.md](config.md)）。

| 端点 | HTTP | 权限 | 说明 |
|------|------|------|------|
| `admin/sys-config/items` | GET | `sys-config:read` | 分组 + 元数据 + 当前生效值；隐藏项与只含隐藏项的分组不会返回 |
| `admin/sys-config/system` | GET | `sys-config:read` | 只读系统参数（来自 `application.yml` / 代码默认值），返回 `{items:[{key,label,value,group}]}`；`group` 为 `runtime`（运行时状态，页面定时刷新）、`version`（版本信息）或 `startup`（启动期配置） |
| `admin/sys-config/update` | POST | `sys-config:update` | 只提交改动项：`{values:[{key,value}]}`；隐藏项会被拒绝 |
| `admin/sys-config/reset` | POST | `sys-config:update` | `{keys:[...]}`；`keys` 为空表示全部恢复默认（保留隐藏项）；显式传隐藏项会被拒绝 |
| `admin/sys-config/value` | GET | `sys-config:read` | 单键读取，查询参数 `key` |

> `system` 只返回非敏感的启动期参数，值已按业务含义格式化；修改仍需改 `application.yml` 并重启。

`items` 返回 `{groups:[{code,label,description,items:[...]}]}`，配置项字段：

| 字段 | 说明 |
|------|------|
| `key` / `label` / `hint` | 配置键、显示名、说明 |
| `type` | `bool` / `int` / `float` / `string` / `text` / `time` / `list` / `select` / `proxy` |
| `defaultValue` | 生效默认值（`application.yml` 或代码声明）；敏感项为 `null` |
| `value` | 当前生效值；敏感项为 `null` |
| `overridden` | 是否被数据库覆盖 |
| `source` | `db`（数据库覆盖）/ `env`（application.yml）/ `code`（代码默认） |
| `secret` / `hasValue` | 是否敏感项、敏感项是否已设置 |
| `min` / `max` / `options` / `required` | 输入约束 |
| `allowEmpty` | 仅 `select`：是否允许保存空值（表示「不设置」） |

> `select` 的 `value` 与 `options[].value` 统一为字符串。`options` 可以在每次请求时动态求值（`Configs.select(key, label, Supplier)`），新增 / 删除业务表记录后重新进入参数页即可看到，无需重启；求值失败时该项按空选项返回，不影响其他配置项。

> 隐藏项（`.asHidden()`）不会出现在 `items` 中，也不能通过 `update` / `reset` 修改；业务侧使用 `SysConfigService.putInternal` / `removeInternal`。`reset` 全部恢复时会保留隐藏项的值。

> `update` 请求体可能包含敏感值，控制器未使用 `@Log`；操作日志由框架只记录变更的 key。

### 邮件

邮件配置统一走上面的系统参数接口，不提供单独的设置读写接口。

业务侧通过 `MailService` 发送，支持 HTML 正文、附件和内联图片：

```java
mailService.sendAsync(MailMessage.builder()
        .to("ops@example.com")
        .bcc("audit@example.com")                       // 可选
        .subject("巡检报告")
        .html("<p>趋势见下图：</p><img src=\"cid:trend\">")
        .inline(MailInline.of("trend", trendFile))       // 文件
        .inline(MailInline.of("logo", logoBytes, "image/png"))  // 字节数组
        .build());
```

- `MailInline` 的 `cid` 支持 `trend` / `cid:trend` / `<trend>` 三种写法，HTML 统一用 `cid:trend` 引用；发送时 `Content-ID` 会用尖括号包裹；
- 未填 `to` 时优先使用系统参数里的 `email.default-to`；`to` / `cc` / `bcc` 全为空才报「收件邮箱不能为空」，只有 `cc` / `bcc` 时会用发件邮箱补 `To`；
- 发送结果统一走 `MailSendResult` / `MailErrorType`，与附件、代理、认证失败的处理一致。

### 在线用户

系统管理 →「在线用户」页面：一个登录会话一行，展示账号、姓名、机构、IP、登录时间、最后活跃时间与最近操作（取自操作日志，可点击跳转到按该用户 + IP 过滤的操作日志），支持把指定会话强制下线。

| 端点 | HTTP | 权限 | 说明 |
|------|------|------|------|
| `admin/sys-online-user/page` | GET | `sys-online:read` | 分页查询，查询参数 `keyword`（账号/姓名/IP/机构模糊匹配）、`page`、`size` |
| `admin/sys-online-user/kick` | POST | `sys-online:kick` | 请求体 `{id}`；`id` 为列表返回的随机标识，非 sessionId |

字段：`id` / `username`（账号）/ `name`（姓名）/ `unitId` / `unitName` / `orgId` / `orgName` / `ip` / `userAgent` / `loginTime` / `lastActiveTime` / `lastOperation`（最近操作名称）/ `lastOperationTime`（最近操作时间）/ `lastOperationTimeText`（友好时间，如「5分钟前」）/ `current`（是否当前请求所在会话）。真实 `sessionId` 不会下发给前端。

说明：

- 在线记录保存在内存，**仅单实例部署准确**；应用重启后 `HttpSession` 会从落盘文件恢复，在线记录则在用户下一次请求时自动补登记（重启后尚未访问的用户不会立即出现），多实例部署需要 Spring Session 等共享会话方案（见 [config.md](config.md)）。
- `lastActiveTime` 由 Spring Security `SessionRegistry` 的 `lastRequest` 实时计算，无需业务上报。
- `lastOperation` / `lastOperationTime` 按「用户 + IP」从操作日志表 `sys_log` 批量取最新一条；`lastOperationTimeText` 由 `FriendlyTool.getPastTime` 计算。同一账号从不同 IP 登录时，各会话只显示与自己 IP 一致的最近操作。登录成功本身也会写入一条「认证-登录」，所以刚登录的会话最近操作通常显示为登录。
- 「最近操作」跳转格式：`/system/log?userId=...&username=...&ip=...`，操作日志页支持 `username`（模糊）、`userId`（精确）、`ip`（精确）筛选。
- 强制下线调用 `SessionInformation.expireNow()`，对方下一次请求会收到 401，并复用并发登录提示（见下文「框架内置事件」）。
- 不允许强制下线当前登录会话；会话被踢后记录立即从列表移除，对方会话在下一次请求时失效。
- 并发登录被顶下线的旧会话、以及空闲超时的会话，会在下次查询列表时自动清理，不会长期残留。

### 个人中心

个人中心「登录设备」只查询当前登录账号自己的会话，普通用户无需 `sys-online:*` 权限。

| 端点 | HTTP | 权限 | 说明 |
|------|------|------|------|
| `admin/user-center/sessions` | GET | 登录即可 | 当前账号的活动会话列表，按登录时间倒序 |
| `admin/user-center/sessions/kick` | POST | 登录即可 | 请求体 `{id}`；下线自己的其他设备，不能下线当前会话 |
| `admin/user-center/shortcuts` | GET | 登录即可 | 当前用户有效快捷菜单，按个人中心配置顺序返回 |
| `admin/user-center/shortcut-options` | GET | 登录即可 | 快捷菜单配置项：当前用户有权限的页面菜单树，目录节点仅用于分组 |
| `admin/user-center/shortcuts` | POST | 登录即可 | 请求体 `{menuIds: string[]}`，保存顺序；空数组表示清空，最多 10 个 |

字段：`id`（随机标识，非 sessionId）/ `ip` / `userAgent` / `loginTime` / `lastActiveTime` / `current`。

快捷菜单字段：`id`（菜单 id）/ `name` / `path` / `icon`。服务端只保存菜单 id，读取时按当前角色权限解析；权限被回收或菜单被删除的条目会自动从快捷菜单中消失，保存时也会校验菜单必须属于当前用户可访问的页面菜单。快捷菜单与导航菜单（`admin/menu-info`）都会应用 `MenuCustomizer` 的定制结果（改名、移动、排序、隐藏），见 [guide.md](guide.md)「框架扩展点」。

快捷菜单保存在 `sys_user.shortcut_menus`（TEXT，JSON 数组）。使用 JPA 自动建表时会自动新增；如项目自行管理表结构，需要同步添加该列。

说明：

- 是否会出现多处登录取决于 `sys.maximum-sessions`：默认 `1`，配合 `sys.max-sessions-prevents-login`（默认 `true`）时新登录会被拒绝、旧会话继续有效；将该开关设为 `false` 则改为踢掉旧会话。调大后多个会话并存，个人中心在会话数大于 1 时显示提示。
- 服务端会校验会话归属，只能下线自己的会话，也允许用户自助下线自己的其他设备。
- 在线记录保存在内存，**仅单实例部署准确**（同「在线用户」）；应用重启后随用户请求自动恢复登记。

### 代码生成

系统管理 →「代码生成」页面：动态扫描已注册实体（继承 `BaseEntity` 且标注 `@Entity`），按选中实体生成常见 CRUD 代码并写入项目源码目录（先预览确认，再在预览抽屉里勾选要写入的文件）。

| 端点 | HTTP | 权限 | 说明 |
|------|------|------|------|
| `admin/codegen/entity-options` | GET | `sys-codegen:read` | 扫描实体列表（`value` 全限定类名，`label` 中文名，`data` 含 `module`/`packageName`/`frameworkEntity`） |
| `admin/codegen/entity-info` | GET | `sys-codegen:read` | 单实体字段元数据（`className`） |
| `admin/codegen/preview` | POST | `sys-codegen:generate` | 只生成不写盘，返回各文件路径/内容/是否已存在 |
| `admin/codegen/generate` | POST | `sys-codegen:generate` | 仅写入 `paths` 指定的文件，返回 `written`/`skipped` |

请求体（`CodegenReq`）：`className`（必填）、`module`（缺省取类名 kebab-case）、`label`（缺省取实体 `@Remark`）、`parentMenu`（默认 `sys`）、`paths`（要写入的文件相对路径，来自预览结果；不能为空，未包含的文件跳过）。

生成物：

| 类型 | 路径 | 说明 |
|------|------|------|
| Repository | `{实体包去掉 .entity}/repository/{Entity}Repository.java` | 继承 `BaseRepository<Entity, String>` |
| Service | `{模块包}/service/{Entity}Service.java` | 继承 `BaseService`，含 `@FileField` 时自动生成 confirmTempFiles/discardTempFiles 事务方法 |
| Controller | `{模块包}/controller/{Entity}Controller.java` | `admin/{module}` 下 `page/info/create/update/delete`，`@HasPermission` + `@Log`，默认使用 `CrudOps` 统一响应与校验 |
| 前端页面 | `web/src/pages/{module}/index.jsx` | `ProTable` + `FormModal`，按字段类型选用 `Field*`/`View*` 组件 |
| 菜单 | `src/main/resources/menu-{module}.yml` | 挂在指定父菜单（默认 `sys`），含读/建/改/删权限 |

字段映射：String→`Input`、`@Lob`/TEXT→`Input.TextArea`、数字→`InputNumber`、布尔→`FieldBoolean`、日期→`FieldDate`、枚举（`@DictType`）→`FieldDictSelect`、`@FileField(html=true)`→`FieldEditor`、图片类文件→`FieldUploadImage`、其他文件→`FieldUploadFile`；查询条件为可搜索 String 字段的 `searchText` 模糊匹配 + 枚举/布尔等值过滤。

写盘根目录由 `sys.codegen.*` 配置（见 [config.md](config.md)），路径越界会被拒绝；未出现在 `paths` 中的文件跳过，出现在其中的已存在文件会被覆盖。前端预览抽屉默认只勾选新增文件，已存在文件需手动勾选。

## 前端

### 组件

| 组件 | 用途 |
|------|------|
| `Page` | 页面容器（右侧操作区），`padding`/`debug`/`actions`（推荐传 `PermAction[]`，兼容带 `perm` 属性的节点）；页面标题由 `pageMeta.title` / 菜单提供，`Page` 不再提供 `title`/`description` 属性；`backgroundGray` 已废弃（页面底色默认跟随布局底色，该参数不再产生差异） |
| `PageLoading` | 页面加载中提示（`message` 单条 / `messages` 多阶段） |
| `ProTable` | 数据表格，分页/筛选/工具栏/树形模式，详见[下方](#protable) |
| `LinkButton` | 链接跳转按钮（`path` + `label`，通过 `PageUtils.open` 打开页面；`label` 已弃用，目标页请使用 `pageMeta.title`） |
| `Link` | hash 路由跳转链接（`to`） |
| `NamedIcon` | 通过名称渲染 Ant Design 图标（按需加载，`name`） |
| `PermActions` | 权限操作区：数据驱动 `actions`（支持 `perm` 过滤、`more` 下拉与 `visibleCount` 展示数、`size`）或按子元素 `perm` 属性过滤（不推荐） |
| `Perm` | 权限控制容器（`code` 属性），无权限时不渲染子元素 |
| `FormModal` | 表单弹框，通过 ref 调用 `open(values)`，`onFinish` 校验通过后回调 |
| `ContextMenu` | 右键菜单（`x`/`y`/`items`/`onClick`/`onClose`） |
| `ErrorBoundary` | 渲染错误边界（`fallback`/`minimal`/`onError`），布局与页面自动包裹 |
| `Gap` | 间隔（`size`：xs/sm/md/lg/xl/xxl；`direction`：vertical/horizontal） |
| `OrgSwitcher` | 组织机构切换器，配合 `Layouts` 的 `showOrgSwitcher` 使用 |
| `ValueType` | 按类型渲染字段/视图（`renderField(type, props)` / `renderView(type, props)`） |
| `View*` | 展示组件，见[展示组件](#展示组件) |
| `DownloadModal` | 下载弹框，通过 ref 调用 `download()` 方法，支持进度追踪/取消/重试 |
| `LogViewer` | 文件日志查看器，按字节偏移增量读取；`logKey` + `running` 时自动轮询新内容、任务结束自动补拉最后一段；`url` 可指向业务模块自建接口 |
| `CronEditor` | cron 表达式输入框 + 常用表达式助手（按间隔/按时间点/自定义），值为 Quartz 格式「秒 分 时 日 月 周 [年]」 |

#### Layouts

应用根布局（由 `web/src/main.jsx` 渲染），负责站点信息/登录校验、主题与后台布局（侧栏 + 顶栏 + 页签）。

| 属性 | 类型 | 说明 |
|---|---|---|
| `headerExtra` | `(context: HeaderExtraContext) => React.ReactNode` | 顶栏额外内容，默认渲染在 `HeaderRight` 左侧；`context` 含 `activeTopMenu`、`loginInfo` |
| `headerExtraStretch` | `boolean` | 为 `true` 时 `headerExtra` 紧跟一级菜单并撑满顶栏剩余空间；默认 `false`（保持原位置）。需要常驻条（如预警滚动条）时使用，不要覆盖 `.top-nav` |
| `showOrgSwitcher` | `(context: HeaderExtraContext) => boolean` | 是否显示组织机构切换器 |
| `colors` | `Partial<ThemeColors>` | 主题颜色基线，见 [config.md](config.md) |

#### PageFrame

多 Tab 布局的内核：根据当前 hash 匹配路由渲染页面组件。页面通过 `onShow()` 生命周期响应激活（详见[页面生命周期](#页面生命周期)），整个 `PageFrame` 由 `ErrorBoundary` 包裹，页面渲染异常不会影响整体布局。

#### 页面元信息 pageMeta

页面文件可选导出 `pageMeta`，由 vite 插件在扫描路由时读取并写入路由表：

```jsx
// web/src/pages/system/role/role-perm.jsx
export const pageMeta = {
    title: '角色权限设置', // 页面标题；未设置时回退到后端菜单名
    requiresAuth: true,   // 是否需要登录，默认 true
    layout: 'admin',      // 'admin'（后台菜单布局）| 'blank'（仅页面内容）
};
```

TS 页面可 `import type {PageMeta} from '@jiangood/open-admin'` 并标注 `pageMeta` 类型。

不写时按目录约定推断：

| 页面位置 | `requiresAuth` | `layout` |
|---|---|---|
| `pages/public/**` | `false` | `blank` |
| `pages/standalone/**` | `true` | `blank` |
| 其他 `pages/**` | `true` | `admin` |

`/login`（`pages/login.jsx`，登录页）默认按 `requiresAuth: false` + `layout: 'blank'` 处理，业务项目覆盖登录页时无需重复声明。`requiresAuth: false` 与 `layout: 'admin'` 是非法组合，会自动修正为 `blank` 并输出 `console.warn`。路由表条目为 `{path, component, meta}`，`matchRoute(pathname)` 的返回值包含 `meta`。

页面标题优先级（同时用于页签和浏览器标题）：

```
pageMeta.title
  > 后端菜单名
  > 未命名
```

URL 中的 `_label` 已弃用且**不再生效**，检测到时只在控制台提示一次；旧 URL 会按菜单名 / `pageMeta.title` 正常显示。

页面标题为静态元信息，不支持运行时动态回填。浏览器标题统一格式为 `系统标题 | 页面标题`（页面无标题时仅显示系统标题）；其中的系统标题优先使用 `sys.short-title`，未配置时回退到 `sys.title`。

#### FormModal

表单弹框，推荐替代静态 `Modal.confirm()` 表单场景。通过 ref 调用 `open(values)` 打开（传入值则回填表单，否则重置），`onFinish` 返回 `Promise` 时自动控制提交 loading；`onFinish` 的 Promise **resolve 后关闭弹框，reject 时保持打开**（如后端业务校验失败，错误提示由 `HttpClient` 弹出，表单数据保留以便修改重试）：

```jsx
import { FormModal, HttpClient } from '@jiangood/open-admin';

class CustomerPage extends React.Component {
  modalRef = React.createRef();
  handleAdd = () => this.modalRef.current.open({});
  handleEdit = record => this.modalRef.current.open({...record});
  handleSubmit = async values => {
    // await 提交：失败时 HttpClient 自动弹错并 reject，弹框保持打开
    await HttpClient.post(values.id ? 'admin/customer/update' : 'admin/customer/create', values);
    this.tableRef.current.reload();
  };

  render() {
    return <FormModal ref={this.modalRef} title="客户信息" onFinish={this.handleSubmit}>
      <Form.Item label="名称" name="name" rules={[{required: true}]}><Input/></Form.Item>
    </FormModal>;
  }
}
```

props：`title`、`onFinish(values)`、`onValuesChange`、`width`（默认 600）、`labelCol`。ref 暴露 `open(values)` 与 `formInstance`（Form 实例）。

#### ContextMenu

```jsx
<ContextMenu
  x={x} y={y}
  items={[
    { key: 'edit', label: '编辑', icon: <EditOutlined/> },
    { key: 'divider-1', divider: true },
    { key: 'delete', label: '删除', danger: true },
  ]}
  onClick={({key}) => this.handleMenu(key)}
  onClose={() => this.setState({menu: null})}
/>
```

`items` 项：`key`、`label`、`icon`、`danger`、`disabled`、`divider`（分隔线）。点击外部、滚动或按 Esc 自动关闭。

#### PermActions

推荐使用 `actions` 数据驱动模式，按每个 action 的 `perm` 过滤；`more` 开启下拉折叠（多余操作进入 `...` 菜单），`visibleCount` 控制直接展示多少个操作，`size` 透传到按钮：

```jsx
import { PermActions } from '@jiangood/open-admin';

<PermActions
    more
    visibleCount={2}
    size="small"
    actions={[
        { label: '编辑', perm: 'sys-user:update', onClick: () => this.handleEdit(record) },
        { label: '授权', perm: 'sys-user:grant-permission', onClick: () => this.permRef.current.show(record) },
        { label: '删除', perm: 'sys-user:delete', danger: true,
          confirm: '是否确定删除用户', onClick: () => this.handleDelete(record) },
    ]}
/>
```

`PermAction` 字段：`label`、`perm`、`onClick`、`confirm`（确认提示）、`danger`、`disabled`、`icon`、`type`（`primary`/`dashed`/`link`/`text`，仅按钮生效）。`more` 为布尔值，开启后多余操作折叠进 `...` 下拉；`visibleCount`（默认 `1`）指定 `more` 模式下直接展示的操作个数，常用操作可设为 `2` 或 `3` 避免被折叠。

> `confirm` 在普通按钮上使用 Popconfirm，在 `more` 下拉中使用全局弹窗（`getModalApi().confirm`）。下拉菜单项点击后会关闭菜单，Popconfirm 会失去锚点，因此折叠项不再使用 Popconfirm。`danger` 为 `true` 时确认弹窗的「确定」按钮同步为危险色。

> 传统 children 写法（包裹 `<Button perm="...">`）已不推荐，控制台会输出迁移提示，建议改用 `actions`。

#### ProTable

`request` 为 Promise 式：接收 `(params)`（params 含 `{page, size, sort}` 及搜索表单值），返回 `HttpClient` 的 Promise，ProTable 自动取 `AjaxResult` 的 `data`（Spring Data Page 序列化结构 `{content, totalElements, extData}`）。失败时 `HttpClient` 自动弹错，ProTable 静默复位 loading。`actionRef` 暴露 `reload()` / `clearSelection()`，`formRef` 暴露搜索表单实例：

```jsx
<ProTable
  actionRef={this.tableRef}
  formRef={this.searchFormRef}
  request={(params) => HttpClient.get('admin/customer/page', params)}
  columns={columns}
  rowSelection={true}                    // true 为 checkbox，对象可覆盖 {type, onChange}
  treeMode                              // 树形数据模式（关闭分页，不传 page/size）
  searchFormCols={3}                     // 搜索栏每行列数，默认 4
  searchFormRender={() => (
    <Form.Item label="名称" name="name"><Input/></Form.Item>
  )}
  searchInline                           // 搜索表单内联到工具栏左侧（单条件页面），默认 false
  defaultParams={{status: 'ENABLED'}}    // 初始筛选条件，首次加载即生效并回填表单
  toolBarRightRender={(params, {selectedRowKeys}) => (        // 操作按钮统一放右侧
    <Button type="primary" onClick={this.handleAdd}>新增</Button>
  )}
  toolBarRender={(params) => (                          // 左侧只放状态/说明类内容（如「已选 N 项」）
    <span>已选 {params.selectedRowKeys?.length ?? 0} 项</span>
  )}
  defaultPageSize={20}
  scrollY={500}
/>
```

| prop | 说明 |
|------|------|
| `request` | 数据请求，框架自动注入 `page`/`size`/`sort` 及搜索值 |
| `columns` | antd Table 列定义 |
| `actionRef` | 表格操作句柄（`reload` / `clearSelection`） |
| `formRef` | 搜索表单实例（`getFieldsValue` 等） |
| `toolBarRender` | 工具栏左侧渲染，参数为当前搜索值 + 行选择状态；**只放状态/说明类内容**（如「已选 N 项」），操作按钮请用右侧插槽 |
| `toolBarRightRender` | 工具栏右侧渲染，参数同 `toolBarRender`；**操作按钮统一放这里**（新增/导出/清理/刷新等） |
| `rowSelection` | 行选择：`true` 为 checkbox，对象可覆盖 `type`/`onChange` |
| `treeMode` | 树形数据模式，关闭分页 |
| `searchFormRender` | 搜索表单渲染函数，返回 `Form.Item` 列表 |
| `searchFormCols` | 搜索栏每行列数，默认 4（`searchInline` 时忽略） |
| `searchInline` | 搜索表单内联到工具栏左侧，与操作按钮同一行（单条件页面省一行空间）；默认 `false` = 独立筛选卡 |
| `defaultParams` | 初始筛选条件，首次加载即按此条件查询并回填搜索表单（适合从其他页面带参数跳转） |
| `defaultPageSize` | 默认每页条数 |
| `scrollX` | 表格横向宽度：默认 `'max-content'`（按内容撑开，超宽时横向滚动）；传 `true` 让表格自适应容器宽度、单元格内容按列宽换行；传数字为表格最小宽度（容器更宽时铺满，更窄时横向滚动） |
| `scrollY` | 表格纵向滚动高度 |
| `bordered` | 是否显示边框 |

`extData.summary` 会渲染为表格底部汇总行（见 `PageExt`）。

`searchInline` 只建议用于**单条件**页面（如「主机」「镜像」这种只按关键词搜索的列表）：搜索控件内联在工具栏左侧，与 `toolBarRightRender` 的操作按钮同一行，省掉整张筛选卡。内联模式不渲染「查询/重置」按钮——回车即提交，条件被清空时自动回到无筛选状态；`searchFormRender` 无返回时按无搜索表单处理。多条件页面继续用默认筛选卡，行为不变。搜索框宽度可用 `--oa-search-inline-width` 覆盖（默认 260px）。

> 限制：只有文本框符合「输入后回车」的自然交互。`Select`/日期等控件**选中后不会自动查询**（仅在清空时自动查询），需要用户在表单内按回车（在日期框里按回车同样会提交）；若希望选中即查询，可在业务侧 `onChange` 里调用 `formRef.current.submit()`，或继续使用默认筛选卡。

```jsx
<ProTable
  searchInline
  searchFormRender={() => (
    <Form.Item name="searchText" noStyle>
      <Input prefix={<SearchOutlined/>} placeholder="搜索主机名 / IP" allowClear/>
    </Form.Item>
  )}
  toolBarRightRender={() => <Button type="primary" perm="host:save">新增</Button>}
  request={(params) => HttpClient.get('admin/host/page', params)}
  columns={columns}
/>
```

### 页面生命周期

多 Tab 布局中，所有页面保持 mounted（仅 `display` 切换）。框架提供 `onShow()` 生命周期方法，在页面首次加载或从其他 Tab 切回时自动调用。

```jsx
export default class extends React.Component {
  tableRef = React.createRef()

  onShow() {
    this.tableRef.current?.reload()
  }

  render() {
    return <ProTable actionRef={this.tableRef} ... />
  }
}
```

| 触发场景 | onShow 是否调用 |
|---------|:--------------:|
| 首次打开 Tab | ✅ |
| 切换到其他 Tab 再切回来 | ✅ |
| 右键「刷新」Tab | ✅（组件重建后立即调用） |
| Tab 始终激活（无切换） | ❌ |

> 注：仅 class 组件支持，方法名固定为 `onShow`。

### 下载弹框

通过 ref 调用 `download()` 方法触发下载，`title` 可自定义对话框标题，`onFinish` 在下载完成后回调：

```jsx
import { DownloadModal } from '@jiangood/open-admin';

class ReportPage extends React.Component {
  dlRef = React.createRef();

  handleExport = () => {
    this.dlRef.current.download({
      url: '/admin/report/export',
      params: { type: 'monthly', year: 2026, month: 7 },
    });
  };

  // POST 请求，指定文件名
  handleBatchExport = () => {
    this.dlRef.current.download({
      url: '/admin/report/export',
      method: 'POST',
      data: { ids: ['1', '2', '3'] },
      fileName: '批量导出.xlsx',
    });
  };

  render() {
    return (
      <>
        <Button onClick={this.handleExport}>导出报表</Button>
        <DownloadModal ref={this.dlRef} title="导出报表" onFinish={() => this.tableRef.refresh()} />
      </>
    );
  }
}
```

弹框展示三种状态：下载中（进度条 + 已下载/总计 + 速度）、已完成（✅ + 文件大小）、失败（❌ + 错误消息）。下载中不可关闭弹框，失败后可重试。

### 字段组件

| 组件 | 用途 |
|------|------|
| `FieldRemoteSelect` | 远程搜索选择框（`url`，可 `multiple` 多选） |
| `FieldRemoteTree` | 远程树（多选，扁平展示） |
| `FieldRemoteTreeSelect` | 远程树选择（`url`） |
| `FieldRemoteTreeCascader` | 远程树级联（`url`） |
| `FieldDictSelect` | 字典选择（`typeCode`） |
| `FieldBoolean` | 布尔值选择（`type`：select/radio/checkbox/switch） |
| `FieldDate` / `FieldDateRange` | 日期/日期范围（`type`：如 `YYYY-MM-DD`、`YYYY-MM`、`YYYY-QQ`、`YYYY-MM-DD HH:mm:ss`、`HH:mm:ss`） |
| `FieldNumberRange` | 数字范围（值形如 `"1/100"`） |
| `FieldSysOrgTree` / `FieldSysOrgTreeSelect` | 系统组织树 / 树选择（`type`：dept/unit） |
| `FieldUploadFile` | 通用文件上传（`/admin/sys-file/upload`） |
| `FieldUploadImage` | 图片上传（裁剪/压缩，`/admin/sys-file/upload-image`） |
| `FieldEditor` | 富文本编辑器 |
| `FieldPercent` | 百分比输入（0~100，内部按 0~1 存储） |
| `FieldTable` / `FieldTableSelect` | 可编辑表格 / 下拉表格选择 |

#### 业务快捷组件

系统数据的预配置快捷组件（内部已绑定对应系统端点，无需 `url`）：

| 组件 | 用途 |
|------|------|
| `FieldUserSelect` | 系统用户下拉（`/admin/sys-user/options`） |
| `FieldUserSelectMultiple` | 系统用户多选 |
| `FieldUserPicker` | 用户选择弹窗（机构树 + 服务端分页多选，`value` 为用户 id 数组，适合同名多、用户量大的场景；默认数据源 `/admin/sys-user/page`、回显 `/admin/sys-user/by-ids`，可用 `requestUrl`/`usersUrl` 覆盖） |
| `FieldUnitTreeSelect` | 系统单位树选择（`/admin/sys-org/unit-tree`） |
| `FieldDeptTreeSelect` | 系统部门树选择（`/admin/sys-org/dept-tree`） |
| `FieldOrgTreeSelect` | `FieldDeptTreeSelect` 的别名 |
| `FieldOrgTreeMultipleSelect` | 系统部门树多选 |

#### 文件上传字段

文件按可见性分为公共/私有，objectName 前缀即目录（`public/` / `private/`），URL 与磁盘路径保持一致：

- `/file/{*objectName}` 预览，如 `/file/public/202607/xxx.jpg`（公共，免登录）、`/file/private/202607/xxx.pdf`（私有，需登录）
- `/file/{*objectName}?thumb=1` 优先返回缩略图（`xxx.thumb.jpg`），缩略图不存在时回退原图

上传/下载接口（需登录）：
- `POST /admin/sys-file/upload` — 通用文件上传，表单参数 `file`、`isPublic`（`true` 公开免登录 / `false` 私有需登录，默认 `true`）
- `POST /admin/sys-file/upload-image` — 图片上传，表单参数 `file`、`thumb`（缩略图）、`isPublic`
- `GET /admin/sys-file/download/{*objectName}` — 下载
- `GET /admin/sys-file/folder-tree` — 文件管理左侧文件夹树，按 objectName 的存储目录聚合，节点为 `key`/`title`/`count`/`children`；`GET /admin/sys-file/page` 可传 `folder`（目录前缀，含子目录）过滤

上传文件默认标记为临时，保存业务数据后后端自动确认（详见[临时文件自动清理](config.md#临时文件自动清理)）。本地存储会对 `objectName` 做目录穿越校验（绝对路径与 `../` 越界直接拒绝），业务不要把未经校验的外部路径拼到 `sys.file.upload-path` 下。

前端字段直接存储文件 `objectName`（如 `public/202607/xxx.jpg`），`ViewImage` / `ViewFile` / `FieldUploadFile` 自动拼接 `/file/{objectName}` 展示；上传组件通过 `isPublic` prop 指定是否公开，默认 `true`（私有文件显式传 `isPublic={false}`）：

##### FieldUploadImage

图片上传组件，内置**裁切**（自由/1:1/4:3/3:4/3:2/16:9 比例）与**压缩**（最大宽度/目标体积，可"推荐压缩"）能力。多图时 `value` 为逗号分隔的 objectName 列表。

| prop | 说明 | 默认 |
|------|------|------|
| `maxCount` | 最大上传数量 | 1 |
| `thumbWidth` | 缩略图最长边（px） | 300 |
| `isPublic` | 是否公开免登录访问 | true |
| `accept` | 接受的文件类型 | image/* |

##### FieldUploadFile

通用文件上传（不含图片压缩/裁切，图片请用 `FieldUploadImage`）。`value` 为逗号分隔的 objectName 列表。

| prop | 说明 | 默认 |
|------|------|------|
| `maxCount` | 最大上传数量 | 1 |
| `listType` | 上传列表样式 | picture-card |
| `accept` | 接受的文件类型 | — |
| `isPublic` | 是否公开免登录访问 | true |
| `onFileChange` | 文件列表变化回调 | — |

##### nginx 直连公共文件

公共文件可通过 nginx 直接代理，完全绕过 Spring（`sys.file.upload-path` 对应磁盘目录，注意 nginx 配置需与 `alias` 前缀一致）：

```nginx
# 公共文件直连（磁盘路径 = {upload-path}/public/...）
location /file/public/ {
    alias /home/files/;
    expires 7d;
    add_header Cache-Control "public";
}
# /file/private/ 不配置 location，继续走 Spring 鉴权
```

### 展示组件

| 组件 | 用途 |
|------|------|
| `ViewText` | 文本展示（`ellipsis` 开启省略号 + 点击弹窗查看全文，`maxLength` 默认 15） |
| `ViewBoolean` | 布尔值（是/否） |
| `ViewSwitch` | 布尔值（启用/禁用 Tag） |
| `ViewApproveStatus` | 审批状态 Tag（字典 `approveStatus`） |
| `ViewImage` | 图片展示（`size` 默认 60、`borderRadius`、`preview` 放大预览、`previewTitle`、`placeholder`、`style`） |
| `ViewFile` | 文件预览（iframe，单文件直接展示，多文件走马灯，`height`） |
| `ViewFileButton` | "查看文件"按钮 + 弹窗预览 |
| `ViewPassword` | 密码脱敏（`******`，点击切换明文） |
| `ViewRange` | 范围展示（`min` - `max`） |

### 工具类

| 类 | 主要方法 |
|----|---------|
| `HttpClient` | `get` / `post` / `postForm` / `download` / `ajax`（axios 封装，自动 context-path；所有请求方法返回 `Promise<AjaxBody>`：成功 resolve 整个 AjaxResult 响应体（`{success, code, data, message, traceId, ...}`，业务取数据用 `rs.data`、提示文案用 `rs.message`），失败 `reject({code, message})` 且默认自动弹错；成功时后端返回了 `message` 会自动 `message.success` 弹出（传 `{toastSuccess: false}` 关闭），失败弹错传 `{toastError: false}` 可静默仅 reject。**例外**：`401`（HTTP 401 或业务 code=401）不单独弹错，而是广播 `loginExpired` 由全局登录失效弹窗提示；非 `AjaxResult` 结构的响应体一律按失败处理） |
| `UrlUtils` | `contextPath(path)` 拼接 context-path / `getParams` / `setParam` / `getPathname` |
| `DictUtils` | `dictList` / `dictLabel` / `dictOptions` / `dictTag` |
| `TreeUtils` | `walk` / `findByKey` / `flattenTree` / `getKeyList` / `getChildRecursive` |
| `DateUtils` | `toDate` / `formatDate` / `formatTime` / `formatDateTime` / `formatDateCn` / `friendlyTime` |
| `StringUtils` | `ellipsis` / `random` / `contains` / `subBefore` / `subAfter` / `split` / `join` |
| `ArrayUtils` | 数组操作 |
| `ObjectUtils` | 对象操作（`copyPropertyIfPresent` 等） |
| `StorageUtils` | `set` / `get` / `remove`（localStorage 封装） |
| `DeviceUtils` | `isMobileDevice` |
| `PageUtils` | `open(path)` 打开新 Tab / `currentTitle()` 获取当前页面标题 / `closeCurrent` / `redirectToLogin` / `currentUrl` / `currentParams`；常量 `LOGIN_PATH` 为登录页路径（`/login`）。`open` 的 `label` 参数已弃用且不再生效，`currentLabel()` 已弃用 |
| `EventBus` | `on` / `once` / `emit` / `off` — 跨组件通信，优先使用，替代 `document.dispatchEvent` |

`DateUtils.toDate` 统一解析后端时间字段：`Date`、epoch 秒、epoch 毫秒、ISO 串、`YYYY-MM-DD HH:mm:ss`、纯日期（按本地零点），无效或空值返回 `null`；`formatDate*` 同样接受这些入参，解析失败返回空串。后端时间字段可能是 epoch 秒、epoch 毫秒或 ISO 串，业务侧请直接用 `toDate`，不要手写 `v * 1000` 或 `new Date(v)`。`friendlyTime(value, options?)` 的选项：`showSeconds`（默认 `true`，`false` 时不足 1 分钟显示「刚刚」）、`daysOnly`（默认 `false`，`true` 时不换算成「周」）、`future`（默认 `'after'`，`'clamp'` 时未来时间显示「刚刚」）、`empty`（无效值占位，默认 `undefined`）。

`EventBus.on` / `once` 返回取消订阅函数；`on` 支持传 `ctx`（this）。`PageUtils.open(path)` 用于跳转到页面，`PageUtils.currentTitle()` 返回当前页面标题（不含系统标题，未设置时返回 `undefined`）；页面标题请由目标页面通过 `pageMeta.title` 提供。`PageUtils.open(path, label)` 的 `label` 参数与 URL 中的 `_label` 已弃用且不再生效，传入时控制台会输出一次弃用提示。登录相关跳转请使用 `LOGIN_PATH`，不要硬编码 `/public/login`。

框架内置事件：登录失效时 `HttpClient` 广播 `loginExpired`（载荷 `{message, reason}`，`reason` 为 `CONCURRENT_LOGIN` 表示同一账号并发登录被顶下线），`Layouts` 已内置监听并弹出提示，业务无需处理；提示文案由 `sys.login-expired-message` / `sys.concurrent-login-message` 配置（见 [config.md](config.md)）。

### 路由

框架使用自研 hash 路由（`#/path`），页面由 vite-plugin 扫描 `src/pages` 自动注册，入口调用 `registerRoutes(routes)`：

| API | 说明 |
|-----|------|
| `history` | `push(url)` / `replace(url)` / `listen(fn)`（hash 变更监听，返回取消函数） |
| `registerRoutes(defs)` | 注册路由表（`{path, component, meta?}[]`，`path` 支持 `:param` 动态段） |
| `matchRoute(pathname)` | 匹配路由，返回 `{component, params, meta}` |
| `Link` | `<Link to="/path">` hash 跳转链接 |
| `PageFrame` | 路由容器，根据 hash 渲染匹配页面，自动调用 `onShow()` |

业务代码中通过 `history.push('/xxx')` 或 `<Link to="/xxx">` 跳转，通过 `PageUtils.open(path)` 打开页面；页面标题由目标页面的 `pageMeta.title` 决定。
