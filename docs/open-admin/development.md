# 开发规范

> 业务项目开发规范：后端命名、REST API 规范、前后端要点。

## 后端命名

| 项 | 规范 |
|----|------|
| Entity | 大驼峰单数，继承 `BaseEntity`，`@Table(name = "t_xxx")` |
| Repository | 继承 `BaseRepository<T, String>`，简单条件用派生查询，复杂用 `Spec` |
| Service | 继承 `BaseService<T>`，构造器注入，`@Transactional(readOnly = true)`，VO 不暴露 Entity |
| Controller | `admin/` 前缀 + kebab-case 复数，`@HasPermission` 控制权限，统一返回 `AjaxResult` |
| DTO | `XxxCreateReq` / `XxxUpdateReq` / `XxxPageQuery` / `XxxVO` |

## REST API 规范

| 操作 | HTTP | URL | 方法 |
|------|------|-----|------|
| 分页查询 | GET | `admin/xxx/page` | `page(Pageable)` |
| 详情 | GET | `admin/xxx/{id}` | `getById(@PathVariable id)` |
| 创建 | POST | `admin/xxx/create` | `create(@RequestBody dto)` |
| 更新 | POST | `admin/xxx/update` | `update(@RequestBody dto, RequestBodyKeys keys)` |
| 删除 | POST | `admin/xxx/delete` | `delete(@Valid @RequestBody IdReq req)` |

## 后端要点

- 强制构造器注入，禁止 `@Autowired` 字段注入
- 业务异常抛 `ServiceException`，Controller 不做 try-catch
- 使用 Java 21 Record / Pattern Matching / Switch 表达式 / Text Block
- 方法参数校验用 `@Valid` / `@Validated`

## 前端要点

- 组件大驼峰，页面文件小写开头（约定式路由：小写开头才注册为页面）
- 使用 ES6+，强制 `const`/`let`，解构赋值
- 优先使用框架组件：`ProTable`、`Page`、`FieldDictSelect` 等
- 权限控制：`<PermActions>` 包裹 `<Button perm="...">`、`<Perm code="...">`
- 跨组件通信使用 `EventBus`（`emit` / `on` / `once` / `off`），不要使用 `document.dispatchEvent`
- 对话框优先使用 `<Modal>` 组件（state 控制 `open`），避免 `Modal.info()` / `Modal.confirm()` 等静态方法
- 页面生命周期：页面组件实现 `onShow()` 方法，在首次加载或 Tab 切换激活时自动调用，详见[页面生命周期](api.md#页面生命周期)

### 页面目录约定

页面文件放在 `web/src/pages/` 下（小写开头自动注册路由），按目录自动识别页面类型，无需额外配置：

| 目录 | 路由前缀 | 是否需要登录 | 是否需要 AdminLayout |
|------|---------|-------------|---------------------|
| `pages/` | `/` | ✅ 是 | ✅ 是 |
| `pages/public/` | `/public/` | ❌ 否 | ❌ 否 |
| `pages/standalone/` | `/standalone/` | ✅ 是 | ❌ 否 |

`public` 页免登录、无后台布局（如登录页）；`standalone` 页需登录但无后台布局（如强制改密页）。
