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
| 更新 | POST | `admin/xxx/update` | `update(@RequestBody dto, RequestBodyKeys keys)` |
| 删除 | POST | `admin/xxx/delete` | `delete(@Valid @RequestBody IdReq req)` |

## 后端要点

- 推荐构造器注入（`@RequiredArgsConstructor` + `private final`）；框架基类（如 `BaseService`）允许 `@Autowired` 字段注入；Quartz 任务例外见下方「定时任务（Quartz）」
- 业务异常抛 `ServiceException`，Controller 不做 try-catch
- 使用 Java 21 Record / Pattern Matching / Switch 表达式 / Text Block
- 方法参数校验用 `@Valid` / `@Validated`

## 定时任务（Quartz）

任务类继承 `BaseJob` 并加 `@JobDescription`，注册菜单后由 Quartz 调度（新增示例见 `modules/job/` 下的框架任务）。

**实例化与注入**：Quartz 任务由调度器的 JobFactory 在**每次触发时创建新实例**（原型，非 Spring 单例，不走 AOP 代理）。Spring Boot 默认 `SpringBeanJobFactory` 通过 `beanFactory.createBean()` 实例化，因此**构造器注入与字段注入均可使用**。框架自身约定任务用 `@Resource` 字段注入（见 `BaseJob`）。

- 若任务类只有**单个构造器**（如 `@RequiredArgsConstructor` 生成的），Spring 会隐式用该构造器注入依赖，构造器注入也可正常工作；
- 若任务类存在**多个构造器**（含无参构造），Spring 不会自动选择，必须用 `@Autowired` 显式标注其中一个，否则默认走无参构造、`final` 字段不会注入（运行时 NPE）；
- 任务实例每次触发重建，不要在字段中缓存跨触发的可变状态。

## 文件认领

上传文件/图片后，`SysFile` 记录默认处于 **未认领（TEMP）** 状态；只有业务数据保存时调用 `SysFileService` 的认领方法才会转为 **使用中（IN_USE）** 并记录关联表/关联 ID。未认领文件默认 120 分钟（`sys.file.clean-unclaimed-minutes`）后被 `CleanTempFileJob` 物理删除，因此**包含文件/图片/富文本字段的业务模块必须在保存后认领文件**，否则上传的图片会莫名其妙消失。

### 声明文件字段

在实体字段上打 `@FileField` 注解即可让框架自动识别文件字段（实体无需继承 `BaseEntity`，实现 `Persistable<String>` 即可）：

```java
@FileField
private String mainImage;      // 单值文件/图片字段（objectName）

@FileField(html = true)
private String content;        // 富文本 HTML，自动提取其中引用的全部文件
```

### 认领 / 取消认领

调用 `SysFileService.claim(entity)` / `unclaim(entity)`，joinTable 自动取实体 `@Table(name)`，joinId 自动取 `entity.getId()`，单值/富文本由 `@FileField(html=true)` 自动区分，业务方无需指定表名与字段：

```java
// 新增 —— 认领与保存放同一事务（@Transactional 方法内），冲突时文章一并回滚
@Transactional
public Article save(Article input, List<String> requestKeys) {
    Article result = articleRepository.save(input);
    sysFileService.claim(result);
    return result;
}

// 更新 —— 先取消认领旧引用，再保存并认领新引用（整个流程在同一 @Transactional 方法内）
@Transactional
public Article update(Article input, List<String> requestKeys) {
    Article old = articleRepository.findById(input.getId()).orElse(null);
    Assert.notNull(old, "文章不存在");

    sysFileService.unclaim(old);
    this.updateField(input, requestKeys); // 或 articleRepository.save(input)
    sysFileService.claim(input);
    return articleRepository.findById(input.getId()).orElse(null);
}
```

> **事务边界（必须遵守）**：`unclaim` 与 `claim` 本身各带 `@Transactional`（`REQUIRED` 传播，会加入调用方已开启的事务），但**把 unclaim + save + claim 拆到多个独立提交（如 Controller 非事务方法里逐条调用）是错误用法**。删除/保存失败时，先前的 unclaim 已独立提交，文件被标记 `PENDING_DELETE`，随后会被 `CleanTempFileJob` 物理删除，造成"业务记录还在、图片已丢"。因此 unclaim + save + claim 必须整体放在**同一个 `@Transactional` Service 方法**内，且不要拆到 Controller 层。

**注意**：`unclaim` 必须放在 `save` **之前**。因为 `old` 是 JPA 托管实体，`save` 内部的 `updateField` 会直接改写它，保存后再取 `old` 的字段得到的已是新值，导致释放/认领落空（文件停留在"未认领"）。更新流程统一采用"先取消认领旧值 → save → 认领新值"的顺序；内容中未变更的文件会先 unclaim 再 claim，最终仍为使用中。

认领/取消认领方法均为 `@Transactional`（`REQUIRED`，加入调用方事务）。完整示例见框架 `ArticleService`（save/update/deleteById 三个方法）。

**单记录独占规则**：一个文件只能被一条业务记录认领。`claim` 时若目标文件已被其他业务记录认领（`joinTable/joinId` 指向不同记录），会抛出 `BusinessException`（"文件已被其他业务记录引用，不允许多个业务共享同一文件"），操作整体回滚；`unclaim` 仅释放未被认领或归本记录所有的文件，不会触碰其他记录的文件。因此同一图片/文件不应复制到多条业务记录（含富文本 HTML 引用），需在各自记录中重新上传。

### 删除业务记录

删除业务记录前建议在**同一事务**内先 `sysFileService.unclaim(entity)` 再删除，立即释放文件引用且删除失败时一并回滚；若未显式取消认领，`CleanTempFileJob` 的孤儿扫描仍会在后续定时任务中清理已不存在的业务记录所引用的文件：

```java
// 删除 —— 先取消认领，再删除，整个流程在同一个 @Transactional 方法内
@Transactional
public void deleteById(String id) {
    Article article = articleRepository.findById(id).orElse(null);
    if (article == null) {
        return;
    }
    sysFileService.unclaim(article);
    super.deleteById(id);
}
```

> 删除同样遵守上面的**事务边界**：unclaim + delete 必须在同一 `@Transactional` 方法内，删除失败时取消认领一并回滚，避免"删除失败但图片已标记待删"。

## 前端要点

- 组件大驼峰，页面文件小写开头（约定式路由：小写开头才注册为页面）
- 使用 ES6+，强制 `const`/`let`，解构赋值
- 优先使用框架组件：`ProTable`、`Page`、`FieldDictSelect` 等
- 权限控制：`<PermActions actions={[{label, perm, onClick}]} />`（数据驱动，推荐）、`<Perm code="...">`；旧式 `<Button perm="...">` 已不推荐
- 跨组件通信使用 `EventBus`（`emit` / `on` / `once` / `off`），不要使用 `document.dispatchEvent`
- 表单弹框优先用 `FormModal`（ref 调用 `open(values)`），避免静态 `Modal.confirm()` 表单场景；对话框用 `<Modal open={...}>` 控制，避免 `Modal.info()` / `Modal.confirm()` 等静态方法
- 页面生命周期：页面组件实现 `onShow()` 方法，在首次加载或 Tab 切换激活时自动调用，详见[页面生命周期](api.md#页面生命周期)

### 页面目录约定

页面文件放在 `web/src/pages/` 下（小写开头自动注册路由），按目录自动识别页面类型，无需额外配置：

| 目录 | 路由前缀 | 是否需要登录 | 是否需要 AdminLayout |
|------|---------|-------------|---------------------|
| `pages/` | `/` | ✅ 是 | ✅ 是 |
| `pages/public/` | `/public/` | ❌ 否 | ❌ 否 |
| `pages/standalone/` | `/standalone/` | ✅ 是 | ❌ 否 |

`public` 页免登录、无后台布局（如登录页）；`standalone` 页需登录但无后台布局（如强制改密页）。
