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

### 注解

| 注解 | 用途 |
|------|------|
| `@HasPermission("resource:action")` | 权限控制 |
| `@Log` | 操作日志 |
| `@RateLimit(count=10, duration=60)` | IP 限流 |
| `@JobDescription` | 定时任务定义 |
| `@ValidateMobile` / `@ValidateIdCard` / ... | 字段格式校验 |

### 工具类

| 类 | 主要方法 |
|----|---------|
| `ExcelTool` | `importExcel` / `exportExcel` |
| `JdbcRunner` (framework.data) | `findById` / `findAll` / `save` / `deleteById` / `count` |
| `LoginTool` | `getUserId` / `getUser` / `getPermissions` / `isAdmin` |
| `TreeTool` | `buildTree` / `walk` / `treeToList` / `getLeafs` |
| `BeanTool` / `JsonTool` / `StringTool` | 常用对象/JSON/字符串操作 |
| `PasswordTool` | 密码加密 |

### 定时任务

```java
@JobDescription(label = "数据同步", params = {
    @FieldDescription(name = "syncType", label = "同步类型", required = true)
})
public class DataSyncJob extends BaseJob {
    public String execute(JobDataMap data, Logger logger) { ... }
}
```

## 前端

### 组件

| 组件 | 用途 |
|------|------|
| `Page` | 页面容器（标题/描述/右侧操作区），`padding`/`backgroundGray`/`debug`/`title`/`description`/`actions` |
| `PageLoading` | 页面加载中提示（`message` 单条 / `messages` 多阶段） |
| `ProTable` | 数据表格，分页/筛选/工具栏/树形模式，详见[下方](#protable) |
| `LinkButton` | 链接跳转按钮（`path` + `label`，通过 `PageUtils.open` 打开页面） |
| `Link` | hash 路由跳转链接（`to`） |
| `NamedIcon` | 通过名称渲染 Ant Design 图标（按需加载，`name`） |
| `PermActions` | 权限操作区：数据驱动 `actions`（支持 `perm` 过滤、`more` 下拉、`size`）或按子元素 `perm` 属性过滤（不推荐） |
| `Perm` | 权限控制容器（`code` 属性），无权限时不渲染子元素 |
| `FormModal` | 表单弹框，通过 ref 调用 `open(values)`，`onFinish` 校验通过后回调 |
| `ContextMenu` | 右键菜单（`x`/`y`/`items`/`onClick`/`onClose`） |
| `ErrorBoundary` | 渲染错误边界（`fallback`/`minimal`/`onError`），布局与页面自动包裹 |
| `Gap` | 间隔（`size`：xs/sm/md/lg/xl/xxl；`direction`：vertical/horizontal） |
| `OrgSwitcher` | 组织机构切换器，配合 `Layouts` 的 `showOrgSwitcher` 使用 |
| `ValueType` | 按类型渲染字段/视图（`renderField(type, props)` / `renderView(type, props)`） |
| `View*` | 展示组件，见[展示组件](#展示组件) |
| `DownloadModal` | 下载弹框，通过 ref 调用 `download()` 方法，支持进度追踪/取消/重试 |

#### PageFrame

多 Tab 布局的内核：根据当前 hash 匹配路由渲染页面组件。页面通过 `onShow()` 生命周期响应激活（详见[页面生命周期](#页面生命周期)），整个 `PageFrame` 由 `ErrorBoundary` 包裹，页面渲染异常不会影响整体布局。

#### FormModal

表单弹框，推荐替代静态 `Modal.confirm()` 表单场景。通过 ref 调用 `open(values)` 打开（传入值则回填表单，否则重置），`onFinish` 返回 `Promise` 时自动控制提交 loading，完成后关闭弹框：

```jsx
import { FormModal } from '@jiangood/open-admin';

class CustomerPage extends React.Component {
  modalRef = React.createRef();
  handleAdd = () => this.modalRef.current.open({});
  handleEdit = record => this.modalRef.current.open({...record});
  handleSubmit = values =>
    HttpUtils.post(values.id ? 'admin/customer/update' : 'admin/customer/create', values)
      .then(() => this.tableRef.current.reload());

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

推荐使用 `actions` 数据驱动模式，按每个 action 的 `perm` 过滤；`more` 开启下拉折叠（多余操作进入 `...` 菜单），`size` 透传到按钮：

```jsx
import { PermActions } from '@jiangood/open-admin';

<PermActions
    more
    size="small"
    actions={[
        { label: '编辑', perm: 'sys-user:update', onClick: () => this.handleEdit(record) },
        { label: '删除', perm: 'sys-user:delete', danger: true,
          confirm: '是否确定删除用户', onClick: () => this.handleDelete(record) },
    ]}
/>
```

`PermAction` 字段：`label`、`perm`、`onClick`、`confirm`（确认提示）、`danger`、`disabled`、`icon`、`type`（`primary`/`dashed`/`link`/`text`，仅按钮生效）。`more` 为布尔值，开启后多余操作折叠进 `...` 下拉。

> 传统 children 写法（包裹 `<Button perm="...">`）已不推荐，控制台会输出迁移提示，建议改用 `actions`。

#### ProTable

`request` 接收 `{page, size, sort}` 及搜索表单值（params），返回 `{content, totalElements, extData}`（Spring Data Page 序列化结构）。`actionRef` 暴露 `reload()` / `clearSelection()`，`formRef` 暴露搜索表单实例：

```jsx
<ProTable
  actionRef={this.tableRef}
  formRef={this.searchFormRef}
  request={(params) => HttpUtils.get('admin/customer/page', params)}
  columns={columns}
  rowSelection={true}                    // true 为 checkbox，对象可覆盖 {type, onChange}
  treeMode                              // 树形数据模式（关闭分页，不传 page/size）
  searchFormCols={3}                     // 搜索栏每行列数，默认 4
  searchFormRender={() => (
    <Form.Item label="名称" name="name"><Input/></Form.Item>
  )}
  toolBarRender={(params, {selectedRows, selectedRowKeys}) => (
    <Button type="primary" onClick={this.handleAdd}>新增</Button>
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
| `toolBarRender` | 工具栏渲染，参数为当前搜索值 + 行选择状态 |
| `rowSelection` | 行选择：`true` 为 checkbox，对象可覆盖 `type`/`onChange` |
| `treeMode` | 树形数据模式，关闭分页 |
| `searchFormRender` | 搜索表单渲染函数，返回 `Form.Item` 列表 |
| `searchFormCols` | 搜索栏每行列数，默认 4 |
| `defaultPageSize` | 默认每页条数 |
| `scrollY` | 表格纵向滚动高度 |
| `bordered` | 是否显示边框 |

`extData.summary` 会渲染为表格底部汇总行（见 `PageExt`）。

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
| `FieldSysOrgTree` / `FieldSysOrgTreeSelect` | 系统组织树 / 树选择（`type`：dept/unit/shop） |
| `FieldUploadFile` | 通用文件上传（`/admin/sysFile/upload`） |
| `FieldUploadImage` | 图片上传（裁剪/压缩，`/admin/sysFile/uploadImage`） |
| `FieldEditor` | 富文本编辑器 |
| `FieldPercent` | 百分比输入（0~100，内部按 0~1 存储） |
| `FieldTable` / `FieldTableSelect` | 可编辑表格 / 下拉表格选择 |

#### 文件上传字段

文件按可见性分为公共/私有，objectName 前缀即目录（`public/` / `private/`），URL 与磁盘路径保持一致：

- `/file/{objectName}` 预览，如 `/file/public/202607/xxx.jpg`（公共，免登录）、`/file/private/202607/xxx.pdf`（私有，需登录）
- `/file/{objectName}?thumb=1` 优先返回缩略图（`xxx.thumb.jpg`），缩略图不存在时回退原图

上传/下载接口（需登录）：
- `POST /admin/sysFile/upload` — 通用文件上传，表单参数 `file`、`isPublic`（`true` 公开免登录 / `false` 私有需登录，默认 `true`）
- `POST /admin/sysFile/uploadImage` — 图片上传，表单参数 `file`、`thumb`（缩略图）、`isPublic`
- `GET /admin/sysFile/download/{objectName}` — 下载

上传文件默认标记为临时，保存业务数据后后端自动确认（详见[临时文件自动清理](config.md#未认领文件自动清理)）。

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
| `HttpUtils` | `get` / `post` / `postForm`（axios 封装，自动 context-path，返回 `data`） |
| `UrlUtils` | `contextPath(path)` 拼接 context-path / `getParams` / `setParam` / `getPathname` |
| `DictUtils` | `dictList` / `dictLabel` / `dictOptions` / `dictTag` |
| `TreeUtils` | `walk` / `findByKey` / `flattenTree` / `getKeyList` / `getChildRecursive` |
| `DateUtils` | `formatDate` / `formatTime` / `formatDateTime` / `formatDateCn` / `friendlyTime` |
| `StringUtils` | `ellipsis` / `random` / `contains` / `subBefore` / `subAfter` / `split` / `join` |
| `ArrayUtils` | 数组操作 |
| `ObjectUtils` | 对象操作（`copyPropertyIfPresent` 等） |
| `StorageUtils` | `set` / `get` / `remove`（localStorage 封装） |
| `DeviceUtils` | `isMobileDevice` |
| `PageUtils` | `open(path, label)` 打开新 Tab / `closeCurrent` / `redirectToLogin` / `currentUrl` / `currentParams` |
| `EventBus` | `on` / `once` / `emit` / `off` — 跨组件通信，优先使用，替代 `document.dispatchEvent` |

`EventBus.on` / `once` 返回取消订阅函数；`on` 支持传 `ctx`（this）。`PageUtils.open(path, label)` 用于跳转到页面（`label` 会作为 Tab 标题，通过 `currentLabel()` 读取）。

### 路由

框架使用自研 hash 路由（`#/path`），页面由 vite-plugin 扫描 `src/pages` 自动注册，入口调用 `registerRoutes(routes)`：

| API | 说明 |
|-----|------|
| `history` | `push(url)` / `replace(url)` / `listen(fn)`（hash 变更监听，返回取消函数） |
| `registerRoutes(defs)` | 注册路由表（`{path, component}[]`，`path` 支持 `:param` 动态段） |
| `matchRoute(pathname)` | 匹配路由，返回 `{component, params}` |
| `Link` | `<Link to="/path">` hash 跳转链接 |
| `PageFrame` | 路由容器，根据 hash 渲染匹配页面，自动调用 `onShow()` |

业务代码中通过 `history.push('/xxx')` 或 `<Link to="/xxx">` 跳转，通过 `PageUtils.open(path, label)` 打开页面（`label` 显示为 Tab 标题）。
