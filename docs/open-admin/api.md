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
| `@ValidateMobile` / `@ValidateIdNum` / ... | 字段格式校验 |

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

进度上报（`BaseJob` 受保护方法，供前端「定时任务」页面轮询展示）：

| 方法 | 说明 |
|------|------|
| `progress(int percent, String message)` | 直接上报百分比（0-100） |
| `progress(long current, long total, String message)` | 上报「已处理/总数」，百分比自动计算 |
| `progress(String message)` | 仅更新描述，进度未知时使用 |

进度只保存在内存（`JobProgressRegistry`），重启后丢失；执行结果仍由 `sys_job_log` 落库。

| 端点 | HTTP | 权限 | 说明 |
|------|------|------|------|
| `admin/job/progress` | GET | `job:read` | 全部任务运行进度快照，按开始时间倒序 |
| `admin/job/progress/{jobId}` | GET | `job:read` | 单个任务进度，从未运行时 `data` 为 `null` |

快照字段：`jobId` / `jobName` / `logId` / `state`(`RUNNING`/`SUCCESS`/`FAILED`) / `stateLabel` / `stateColor` / `percent` / `current` / `total` / `message` / `beginTime` / `updateTime` / `endTime` / `runTime` / `result`。

### 日志聚合

`SysJob.logAggregation`（新建/编辑任务时选择，字典 `jobLogAggregation`）控制执行记录与日志文件粒度：

| 取值 | 执行记录 | 日志文件 |
|---|---|---|
| `PER_RUN` 每次执行（默认） | 每次一行 | 每次一个文件 |
| `HOURLY` 按小时 | 每任务每小时一行 | 每任务每小时一个文件 |
| `DAILY` 按天 | 每任务每天一行 | 每任务每天一个文件 |

聚合记录的 `SysJobLog` 额外字段：`logBucket`（聚合桶）、`executeCount`（执行次数）、`failCount`（失败次数）、`totalRunTime`（累计耗时）/`totalRunTimeLabel`；`jobRunTime`/`result` 为最近一次，`success` 表示周期内是否全部成功。执行记录与日志文件按 `sys.job-log-retain-days`（默认 30 天）自动清理。

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

### 代码生成

系统管理 →「代码生成」页面：动态扫描已注册实体（继承 `BaseEntity` 且标注 `@Entity`），按选中实体生成常见 CRUD 代码并写入项目源码目录（先预览确认，再写入）。

| 端点 | HTTP | 权限 | 说明 |
|------|------|------|------|
| `admin/codegen/entity-options` | GET | `sys-codegen:read` | 扫描实体列表（`value` 全限定类名，`label` 中文名，`data` 含 `module`/`packageName`/`frameworkEntity`） |
| `admin/codegen/entity-info` | GET | `sys-codegen:read` | 单实体字段元数据（`className`） |
| `admin/codegen/preview` | POST | `sys-codegen:generate` | 只生成不写盘，返回各文件路径/内容/是否已存在 |
| `admin/codegen/generate` | POST | `sys-codegen:generate` | 写入文件，返回 `written`/`skipped` |

请求体（`CodegenReq`）：`className`（必填）、`module`（缺省取类名 kebab-case）、`label`（缺省取实体 `@Remark`）、`parentMenu`（默认 `sys`）、`overwrite`（是否覆盖已存在文件）。

生成物：

| 类型 | 路径 | 说明 |
|------|------|------|
| Repository | `{实体包去掉 .entity}/repository/{Entity}Repository.java` | 继承 `BaseRepository<Entity, String>` |
| Service | `{模块包}/service/{Entity}Service.java` | 继承 `BaseService`，含 `@FileField` 时自动生成 confirmTempFiles/discardTempFiles 事务方法 |
| Controller | `{模块包}/controller/{Entity}Controller.java` | `admin/{module}` 下 `page/info/create/update/delete`，`@HasPermission` + `@Log` |
| 前端页面 | `web/src/pages/{module}/index.jsx` | `ProTable` + `FormModal`，按字段类型选用 `Field*`/`View*` 组件 |
| 菜单 | `src/main/resources/application-menu-{module}.yml` | 挂在指定父菜单（默认 `sys`），含读/建/改/删权限 |

字段映射：String→`Input`、`@Lob`/TEXT→`Input.TextArea`、数字→`InputNumber`、布尔→`FieldBoolean`、日期→`FieldDate`、枚举（`@DictType`）→`FieldDictSelect`、`@FileField(html=true)`→`FieldEditor`、图片类文件→`FieldUploadImage`、其他文件→`FieldUploadFile`；查询条件为可搜索 String 字段的 `searchText` 模糊匹配 + 枚举/布尔等值过滤。

写盘根目录由 `sys.codegen.*` 配置（见 [config.md](config.md)），路径越界会被拒绝；目标文件已存在且未开启 `overwrite` 时跳过。

## 前端

### 组件

| 组件 | 用途 |
|------|------|
| `Page` | 页面容器（标题/描述/右侧操作区），`padding`/`debug`/`title`/`description`/`actions`（推荐传 `PermAction[]`，兼容带 `perm` 属性的节点）；`backgroundGray` 已废弃（页面底色默认跟随布局底色，该参数不再产生差异） |
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
| `LogViewer` | 文件日志查看器，按字节偏移增量读取；`logKey` + `running` 时自动轮询新内容、任务结束自动补拉最后一段；`url` 可指向业务模块自建接口 |

#### PageFrame

多 Tab 布局的内核：根据当前 hash 匹配路由渲染页面组件。页面通过 `onShow()` 生命周期响应激活（详见[页面生命周期](#页面生命周期)），整个 `PageFrame` 由 `ErrorBoundary` 包裹，页面渲染异常不会影响整体布局。

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
| `defaultPageSize` | 默认每页条数 |
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
| `FieldUploadFile` | 通用文件上传（`/admin/sysFile/upload`） |
| `FieldUploadImage` | 图片上传（裁剪/压缩，`/admin/sysFile/uploadImage`） |
| `FieldEditor` | 富文本编辑器 |
| `FieldPercent` | 百分比输入（0~100，内部按 0~1 存储） |
| `FieldTable` / `FieldTableSelect` | 可编辑表格 / 下拉表格选择 |

#### 业务快捷组件

系统数据的预配置快捷组件（内部已绑定对应系统端点，无需 `url`）：

| 组件 | 用途 |
|------|------|
| `FieldUserSelect` | 系统用户下拉（`/admin/sysUser/options`） |
| `FieldUserSelectMultiple` | 系统用户多选 |
| `FieldUserPicker` | 用户选择弹窗（机构树 + 服务端分页多选，`value` 为用户 id 数组，适合同名多、用户量大的场景；默认数据源 `/admin/sysUser/page`、回显 `/admin/sysUser/by-ids`，可用 `requestUrl`/`usersUrl` 覆盖） |
| `FieldUnitTreeSelect` | 系统单位树选择（`/admin/sysOrg/unit-tree`） |
| `FieldDeptTreeSelect` | 系统部门树选择（`/admin/sysOrg/dept-tree`） |
| `FieldOrgTreeSelect` | `FieldDeptTreeSelect` 的别名 |
| `FieldOrgTreeMultipleSelect` | 系统部门树多选 |

#### 文件上传字段

文件按可见性分为公共/私有，objectName 前缀即目录（`public/` / `private/`），URL 与磁盘路径保持一致：

- `/file/{*objectName}` 预览，如 `/file/public/202607/xxx.jpg`（公共，免登录）、`/file/private/202607/xxx.pdf`（私有，需登录）
- `/file/{*objectName}?thumb=1` 优先返回缩略图（`xxx.thumb.jpg`），缩略图不存在时回退原图

上传/下载接口（需登录）：
- `POST /admin/sysFile/upload` — 通用文件上传，表单参数 `file`、`isPublic`（`true` 公开免登录 / `false` 私有需登录，默认 `true`）
- `POST /admin/sysFile/uploadImage` — 图片上传，表单参数 `file`、`thumb`（缩略图）、`isPublic`
- `GET /admin/sysFile/download/{*objectName}` — 下载

上传文件默认标记为临时，保存业务数据后后端自动确认（详见[临时文件自动清理](config.md#临时文件自动清理)）。

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
| `DateUtils` | `formatDate` / `formatTime` / `formatDateTime` / `formatDateCn` / `friendlyTime` |
| `StringUtils` | `ellipsis` / `random` / `contains` / `subBefore` / `subAfter` / `split` / `join` |
| `ArrayUtils` | 数组操作 |
| `ObjectUtils` | 对象操作（`copyPropertyIfPresent` 等） |
| `StorageUtils` | `set` / `get` / `remove`（localStorage 封装） |
| `DeviceUtils` | `isMobileDevice` |
| `PageUtils` | `open(path, label)` 打开新 Tab / `closeCurrent` / `redirectToLogin` / `currentUrl` / `currentParams` |
| `EventBus` | `on` / `once` / `emit` / `off` — 跨组件通信，优先使用，替代 `document.dispatchEvent` |

`EventBus.on` / `once` 返回取消订阅函数；`on` 支持传 `ctx`（this）。`PageUtils.open(path, label)` 用于跳转到页面（`label` 会作为 Tab 标题，通过 `currentLabel()` 读取）。

框架内置事件：登录失效时 `HttpClient` 广播 `loginExpired`（载荷 `{message, reason}`，`reason` 为 `CONCURRENT_LOGIN` 表示同一账号并发登录被顶下线），`Layouts` 已内置监听并弹出提示，业务无需处理；提示文案由 `sys.login-expired-message` / `sys.concurrent-login-message` 配置（见 [config.md](config.md)）。

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
