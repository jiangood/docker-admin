# 开发规范

> 业务项目开发规范：后端命名、REST API 规范、前后端要点。

## 后端命名

| 项 | 规范 |
|----|------|
| Entity | 大驼峰单数，继承 `BaseEntity`，`@Table(name = "t_xxx")` |
| Repository | 继承 `BaseRepository<T, String>`，简单条件用派生查询，复杂用 `Spec` |
| Service | 继承 `BaseService<T>`，业务依赖用 `@RequiredArgsConstructor` 构造器注入，VO 不暴露 Entity |
| Controller | `admin/` 前缀 + kebab-case（资源名单数），`@HasPermission` 控制权限，统一返回 `AjaxResult` |
| DTO | `XxxCreateReq` / `XxxUpdateReq` / `XxxPageQuery` / `XxxVO` |

## REST API 规范

| 操作 | HTTP | URL | 方法 |
|------|------|-----|------|
| 分页查询 | GET | `admin/xxx/page` | `page(Pageable)` |
| 详情 | GET | `admin/xxx/info/{id}` | `info(@PathVariable id)` |
| 创建 | POST | `admin/xxx/create` | `create(@RequestBody dto)` |
| 更新 | POST | `admin/xxx/update` | `update(@RequestBody dto, @RequestHeader(value = "X-Body-Fields", required = false) List<String> fields)` |
| 删除 | POST | `admin/xxx/delete` | `delete(@Valid @RequestBody IdReq req)` |

> **部分更新语义**：`update` 采用 PATCH 式语义 —— 请求头 `X-Body-Fields` 携带本次 body 的顶层字段名（逗号分隔），后端只更新其中列出的字段，未列出的保持原值；显式传 `null` 的字段会被清空。前端 `HttpClient` 对 POST 的普通对象 body 会自动附加该请求头（值为 `undefined` 的键会被序列化丢弃，故不在其中），业务页面无需手动处理。

### CRUD 辅助（CrudOps）

代码生成器生成的 Controller 默认使用 `io.github.jiangood.openadmin.framework.web.CrudOps`，统一详情、分页、成功响应以及更新/删除校验，避免每个模块重复写同样的判断：

| 方法 | 作用 |
|------|------|
| `info(Optional<T>, notFoundMsg)` | 存在返回 `data`，否则返回业务错误 |
| `page(service, pageable, specCustomizer)` | 构造 Spec 并分页，`customizer` 可为 `null` |
| `created()` / `created(saved)` / `updated()` / `deleted()` | 标准成功响应文案 |
| `requireUpdate(id, fields, existingSupplier, notFoundMsg)` | 依次校验 id 非空、确有字段提交、记录存在 |
| `requireDelete(existing, notFoundMsg)` | 校验记录存在，返回已有记录便于写日志 |

`requireUpdate` 在 id 为空或字段为空时直接抛 `BusinessException`，不会先执行查询；校验失败由全局异常处理器转成 `AjaxResult.err` 业务文案，不会被兜底成「服务器忙」。

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

> `X-Body-Fields` 为可选请求头，缺失时由 `CrudOps` 返回「没有提交任何字段」，而不是框架层的 400「缺少请求头」。

## 后端要点

- 推荐构造器注入（`@RequiredArgsConstructor` + `private final`）；框架基类（如 `BaseService`）允许 `@Autowired` 字段注入；Quartz 任务例外见下方「定时任务（Quartz）」
- 业务异常抛 `BusinessException`，Controller 不做 try-catch
- 操作日志用 `@Log("模块-动作")`；需要人话详情时加 `detail = "新增XX「${#input.name}」"`，删除/重置等只有 id 的场景用 `OperationLogContext.setDetail(...)`；详情里不要拼密码、令牌等敏感字段
- 使用 Java 21 Record / Pattern Matching / Switch 表达式 / Text Block
- 方法参数校验用 `@Valid` / `@Validated`

## 参数设置

运行时可改的参数优先实现 `ConfigProvider`，不要为每个参数单独写 Controller 和页面：

- key 以模块前缀开头，全小写、点分隔，段落内可用连字符（如 `cvs.capture.interval`），全局唯一
- 默认值写在代码里（`.defaultValue(...)`）；需要部署期覆盖时，在 `application.yml` 中使用同名 key，优先级低于数据库覆盖值
- 敏感值加 `.asSecret()`；配置修改接口不要直接挂 `@Log`，操作日志由框架只记录变更的 key
- 需要随 `/admin/public/site-info` 下发给未登录页面的展示类参数加 `.asPublic()`，值会放在 `siteInfo.config` 中
- 代理地址用 `Configs.proxyUrl(...)`（`proxy` 类型）：一个字段包含类型、主机、端口与凭据，接口回显自动把密码替换成 `***`
- 指纹、初始化标记等内部键加 `.asHidden()`：不出现在配置页，也不能被页面接口修改；业务侧用 `putInternal` / `removeInternal` 读写
- 已下线的 key 通过 `ConfigProvider.deprecatedKeys()` 声明，启动时自动清理数据库残留行（只记录 key，不记录值）
- 业务代码通过 `SysConfigService` 的 `getBool/getInt/getString/getList` 读取，不要直接查 `SysConfigRepository`
- 只把「运行期读取、改了立即生效」的参数放进来；启动期装配的配置仍留在 `application.yml`

完整声明方式见 [config.md](config.md) 的「参数设置（运行时可改）」。

## 定时任务（Quartz）

任务类继承 `BaseJob` 并加 `@JobDescription`，注册菜单后由 Quartz 调度（新增示例见 `modules/job/` 下的框架任务）。

**自动注册**：启动时会扫描 `BaseJob` 子类以及带 `@JobDescription` 的任务类，把缺失的记录补建到 `sys_job`（默认禁用），不需要再为每个业务任务手写 seed。已有记录不会被覆盖，删除记录后重启会重新补建；不想运行请禁用而不是删除。可通过 `sys.job-auto-register: false` 关闭。注解属性：

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `key` | 全限定类名 | 稳定标识；类改名/换包时保持不变即可复用原记录 |
| `label` | 必填 | 任务名称；重名时自动追加类名 |
| `defaultCron` | 空 | 自动注册时的默认 cron；为空则只补建不启用调度 |
| `defaultEnabled` | false | 自动注册时是否启用（需同时配置 `defaultCron`） |
| `logAggregation` | `PER_RUN` | 执行记录方式 |
| `autoRegister` | true | 是否参与启动自动注册；依赖库中的任务可设为 false |
| `params` / `paramsProvider` | 空 | 任务参数定义 |

**稳定 key 与升级**：`sys_job.job_key` 可空且唯一，新记录写入 `key`；旧数据启动时按 `jobClass` 回填。匹配顺序为 `job_key` → `jobClass`，因此类改名/换包后不会重复补建。若 key 已存在但执行类不同，框架只输出 warn，不会覆盖数据库中的执行类；按提示在「系统管理 → 定时任务」里修改即可。key 重复时保留类名排序靠前的一个。

换包且未显式声明 `key` 时，会再做一次保守推断：若数据库中恰好有一条「执行类已不存在、简单类名相同、仍使用默认 key」的记录，则提示「疑似换包」并跳过补建，避免重复；显式改过 key 的记录不参与推断。该逻辑只提示，不自动修改执行类。

**JobRegistrar SPI**：业务可实现 `JobRegistrar` 并注册为 Spring Bean，显式注册任务或覆盖注解默认值（`name` / `cron` / `enabled` / `logAggregation` / `jobData`）。SPI 优先级高于注解，只影响新建记录，不修改已有配置：

```java
@Bean
public JobRegistrar reminderJobs() {
    return () -> List.of(JobDefinition.builder()
            .key("reminder.send")                    // 可省略，默认全限定类名
            .jobClass(ReminderJob.class)
            .name("提醒-发送")
            .cron("0 0/5 * * * ?")
            .enabled(true)
            .logAggregation(JobLogAggregation.DAILY)
            .build());
}
```

**管理页导入**：定时任务页面的「导入任务」会列出代码中存在、数据库未注册的任务，支持单条或全部导入。已存在但执行类不存在的记录仍只提示，不会自动删除。

**实例化与注入**：Quartz 任务由调度器的 JobFactory 在**每次触发时创建新实例**（原型，非 Spring 单例，不走 AOP 代理）。Spring Boot 默认 `SpringBeanJobFactory` 通过 `beanFactory.createBean()` 实例化，因此**构造器注入与字段注入均可使用**。框架自身约定任务用 `@Resource` 字段注入（见 `BaseJob`）。

- 若任务类只有**单个构造器**（如 `@RequiredArgsConstructor` 生成的），Spring 会隐式用该构造器注入依赖，构造器注入也可正常工作；
- 若任务类存在**多个构造器**（含无参构造），Spring 不会自动选择，必须用 `@Autowired` 显式标注其中一个，否则默认走无参构造、`final` 字段不会注入（运行时 NPE）；
- 任务实例每次触发重建，不要在字段中缓存跨触发的可变状态。

### 进度上报

长任务可调用 `BaseJob` 提供的 `progress(...)` 把运行进度暴露给前端「定时任务」页面（页面每 3 秒轮询 `admin/job/progress`）：

```java
List<Row> rows = load();
for (int i = 0; i < rows.size(); i++) {
    sync(rows.get(i));
    progress(i + 1, rows.size(), "已同步 " + (i + 1) + "/" + rows.size());
}
return "同步完成 " + rows.size() + " 条";
```

- 三种重载：`progress(percent, msg)` / `progress(current, total, msg)`（百分比自动算）/ `progress(msg)`
- 进度是**内存态**（`JobProgressRegistry`），不落库；重启后运行中进度丢失，但 `sys_job_log` 执行记录完整
- 只对继承 `BaseJob` 的任务生效；直接实现 `org.quartz.Job` 的类没有进度
- 进度按 `SysJob.id` 记录，同一任务受 `@DisallowConcurrentExecution` 保护不会并发覆盖
- 建议按批次/每秒最多一次上报，避免无谓开销；任务结束后状态保留 30 分钟自动清理

### 执行记录方式

高频任务（如每 5 秒执行）会在 `sys_job_log` 和日志目录产生海量记录。任务定义上的「执行记录方式」属性（`SysJob.logAggregation`，前端新建/编辑时可选）控制执行记录与日志文件的粒度：

| 取值 | 执行记录 | 日志文件 |
|---|---|---|
| `PER_RUN` 每次一份记录（默认） | 每次一行 | 每次一个文件 |
| `HOURLY` 每小时一份记录 | 每任务每小时一行 | 每任务每小时一个文件 |
| `DAILY` 每天一份记录 | 每任务每天一行 | 每任务每天一个文件 |

- 聚合模式下复用同一条 `SysJobLog`（id 不变），日志按 `job/{logId}` 追加到同一文件；「执行情况 → 日志」按钮打开的就是该周期的完整文件日志。
- 聚合记录字段：`beginTime` 为周期内第一次执行时间，`jobRunTime` 为最近一次耗时，`totalRunTime` 为累计耗时，`executeCount`/`failCount` 为执行/失败次数，`success` 表示周期内是否全部成功，`result` 为最近一次返回值。
- 过期执行记录与日志文件按 `sys.job-log-retain-days`（默认 30 天，`<=0` 不清理）惰性清理：任意定时任务执行结束后触发一次检查（同一自然日最多清理一次，清理在独立线程异步执行）；没有任务执行就不会触发清理。删除任务时也会一并删除其执行记录与日志文件。

### 日志写入与在线查看

任务的 `logger` 参数由 `BaseJob` 通过 `FileLog` 绑定到 `job/{logId}`，任务内所有日志都会写入该文件；日志文件为**追加**模式，聚合模式下同一周期的内容会持续追加到同一文件。

前端「执行情况 → 日志」弹窗使用框架 `LogViewer` 组件：任务运行中按 1.5s 增量拉取新增内容（只读文件新增字节，不会整文件重读），任务结束后停止轮询并补拉最后一段；「在新窗口打开」走 `admin/sys/log/raw/job/{logId}` 返回纯文本。

`admin/sys/log/{key}` 的查看权限按 key 前缀注册（定时任务日志注册为 `job:read`）。业务模块自己的文件日志（如 `FileLog.bind("order/" + id)`）需要实现 `LogKeyPermissionProvider` 声明前缀权限，否则该日志一律拒绝访问——详见 [api.md](api.md#运行日志)。

## 临时文件确认

上传文件/图片后，`SysFile` 记录默认处于 **临时（TEMP）** 状态；只有业务数据保存时调用 `SysFileService` 的确认方法才会转为 **使用中（IN_USE）** 并记录关联表/关联 ID。临时文件默认 120 分钟（`sys.file.clean-temp-minutes`）后被 `CleanTempFileJob` 物理删除，因此**包含文件/图片/富文本字段的业务模块必须在保存后确认临时文件**，否则上传的图片会莫名其妙消失。

### 声明文件字段

在实体字段上打 `@FileField` 注解即可让框架自动识别文件字段（实体无需继承 `BaseEntity`，实现 `Persistable<String>` 即可）：

```java
@FileField
private String mainImage;      // 单值文件/图片字段（objectName）

@FileField(html = true)
private String content;        // 富文本 HTML，自动提取其中引用的全部文件
```

### 确认 / 丢弃临时文件

调用 `SysFileService.confirmTempFiles(entity)` / `discardTempFiles(entity)`，joinTable 自动取实体 `@Table(name)`，joinId 自动取 `entity.getId()`，单值/富文本由 `@FileField(html=true)` 自动区分，业务方无需指定表名与字段：

```java
// 新增 —— 确认临时文件与保存放同一事务（@Transactional 方法内），冲突时文章一并回滚
@Transactional
public Article save(Article input, List<String> requestKeys) {
    Article result = articleRepository.save(input);
    sysFileService.confirmTempFiles(result);
    return result;
}

// 更新 —— 先丢弃旧引用，再保存并确认新引用（整个流程在同一 @Transactional 方法内）
@Transactional
public Article update(Article input, List<String> requestKeys) {
    Article old = articleRepository.findById(input.getId()).orElse(null);
    Assert.notNull(old, "文章不存在");

    sysFileService.discardTempFiles(old);
    this.updateField(input, requestKeys); // 或 articleRepository.save(input)
    sysFileService.confirmTempFiles(input);
    return articleRepository.findById(input.getId()).orElse(null);
}
```

> **事务边界（必须遵守）**：`discardTempFiles` 与 `confirmTempFiles` 本身各带 `@Transactional`（`REQUIRED` 传播，会加入调用方已开启的事务），但**把 discardTempFiles + save + confirmTempFiles 拆到多个独立提交（如 Controller 非事务方法里逐条调用）是错误用法**。删除/保存失败时，先前的 discardTempFiles 已独立提交，文件被标记 `PENDING_DELETE`，随后会被 `CleanTempFileJob` 物理删除，造成"业务记录还在、图片已丢"。因此 discardTempFiles + save + confirmTempFiles 必须整体放在**同一个 `@Transactional` Service 方法**内，且不要拆到 Controller 层。

**注意**：`discardTempFiles` 必须放在 `save` **之前**。因为 `old` 是 JPA 托管实体，`save` 内部的 `updateField` 会直接改写它，保存后再取 `old` 的字段得到的已是新值，导致丢弃/确认落空（文件停留在"临时"）。更新流程统一采用"先丢弃旧值 → save → 确认新值"的顺序；内容中未变更的文件会先 discardTempFiles 再 confirmTempFiles，最终仍为使用中。

确认/丢弃临时文件方法均为 `@Transactional`（`REQUIRED`，加入调用方事务）。完整示例见框架 `ArticleService`（save/update/deleteById 三个方法）。旧方法名 `claim` / `unclaim` 已标记 `@Deprecated`，仍可用但建议迁移到新名。

**单记录独占规则**：一个文件只能被一条业务记录确认使用。`confirmTempFiles` 时若目标文件已被其他业务记录引用（`joinTable/joinId` 指向不同记录），会抛出 `BusinessException`（"文件已被其他业务记录引用，不允许多个业务共享同一文件"），操作整体回滚；`discardTempFiles` 仅丢弃未被确认或归本记录所有的文件，不会触碰其他记录的文件。因此同一图片/文件不应复制到多条业务记录（含富文本 HTML 引用），需在各自记录中重新上传。

### 删除业务记录

删除业务记录前建议在**同一事务**内先 `sysFileService.discardTempFiles(entity)` 再删除，立即释放文件引用且删除失败时一并回滚；若未显式丢弃，`CleanTempFileJob` 的孤儿扫描仍会在后续定时任务中清理已不存在的业务记录所引用的文件：

```java
// 删除 —— 先丢弃，再删除，整个流程在同一个 @Transactional 方法内
@Transactional
public void deleteById(String id) {
    Article article = articleRepository.findById(id).orElse(null);
    if (article == null) {
        return;
    }
    sysFileService.discardTempFiles(article);
    super.deleteById(id);
}
```

> 删除同样遵守上面的**事务边界**：discardTempFiles + delete 必须在同一 `@Transactional` 方法内，删除失败时丢弃一并回滚，避免"删除失败但图片已标记待删"。

## 前端要点

- **UI 定位**：面向正常业务人员的**桌面端**后台，**不要求无障碍（a11y）与移动端适配**（不强制 tablist/键盘导航、触摸目标、移动布局等）；样式优先使用 `--oa-*` CSS 变量 / antd token，**禁止硬编码颜色**（须同时兼容明暗主题）
- 页面文件用 kebab-case（小写开头，如 `force-update-pwd.jsx`，会自动注册为路由），组件文件用 PascalCase（大驼峰，不会被注册为页面）
- 使用 ES6+，强制 `const`/`let`，解构赋值
- 优先使用框架组件：`ProTable`、`Page`、`FieldDictSelect` 等
- 权限控制：`<PermActions actions={[{label, perm, onClick}]} />`（数据驱动，推荐）、`<Perm code="...">`；旧式 `<Button perm="...">` 已不推荐
- **列表页工具栏**：操作按钮（新增/导出/清理/刷新）统一放右侧 `toolBarRightRender`，左槽 `toolBarRender` 只放状态/说明类内容（如「已选 N 项」）；与筛选卡的「查询/重置」、分页器同处右侧，避免同一屏出现两个方向的按钮列
- 跨组件通信使用 `EventBus`（`emit` / `on` / `once` / `off`），不要使用 `document.dispatchEvent`
- 表单弹框优先用 `FormModal`（ref 调用 `open(values)`），避免静态 `Modal.confirm()` 表单场景；对话框用 `<Modal open={...}>` 控制，避免 `Modal.info()` / `Modal.confirm()` 等静态方法
- **Modal 内表单回填**：受控 `<Modal open={...}>` 首次打开时内容可能尚未挂载，在打开回调里用 `setFieldsValue` 会因 `ref.current` 为空而静默失败（表现为必填项为空、编辑不生效）。应给 `Modal` 加 `destroyOnHidden`，并用 `Form` 的 `initialValues` 传入当前数据，每次打开重建表单即可正确回填；不要用 `forceRender` 提前挂载来绕开
- 页面生命周期：页面组件实现 `onShow()` 方法，在首次加载或 Tab 切换激活时自动调用，详见[页面生命周期](api.md#页面生命周期)

### 页面目录约定

页面文件放在 `web/src/pages/` 下（小写开头的 kebab-case 文件名自动注册路由），按目录自动识别页面类型，无需额外配置：

| 目录 | 路由前缀 | 是否需要登录 | 是否需要 AdminLayout |
|------|---------|-------------|---------------------|
| `pages/` | `/` | ✅ 是 | ✅ 是 |
| `pages/public/` | `/public/` | ❌ 否 | ❌ 否 |
| `pages/standalone/` | `/standalone/` | ✅ 是 | ❌ 否 |

`public` 页免登录、无后台布局（如公开文章页）；`standalone` 页需登录但无后台布局（如强制改密页）。目录只提供默认值，页面可通过 `pageMeta` 显式覆盖：

```jsx
// 页面文件（可选导出）
export const pageMeta = {
    title: '角色权限设置', // 页面标题
    requiresAuth: true,   // 是否需要登录
    layout: 'admin',      // 'admin' | 'blank'
};
```

非菜单的详情页、公共页推荐声明 `pageMeta.title`；页面标题为静态元信息，不支持运行时动态回填。`PageUtils.open(path, label)` 的 `label` 参数与 URL 中的 `_label` 已弃用且不再生效，传入时控制台会提示。

框架内置登录页为 `/login`（页面文件 `pages/login.jsx`），通过 `pageMeta` 声明为公开页；业务项目覆盖 `pages/login.jsx` 时即使不写 `pageMeta`，插件也会按 `/login` 默认公开页处理。`/public/login` 仅保留为旧地址兼容跳转，新代码请直接使用 `/login`。
