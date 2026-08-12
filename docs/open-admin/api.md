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
| `Page` | 页面容器（标题/描述/右侧操作区） |
| `ProTable` | 数据表格，分页/筛选/工具栏/树形模式，`request`/`columns`/`treeMode`/`toolBarRender` |
| `LinkButton` | 链接跳转按钮 |
| `NamedIcon` | 通过名称渲染 Ant Design 图标 |
| `PermActions` | 权限操作区：数据驱动 `actions`（支持 `perm` 过滤、`more` 下拉、`size`）或按子元素 `perm` 属性过滤（不推荐） |
| `Perm` | 权限控制容器（`code` 属性） |
| `ViewText` / `ViewBoolean` / `ViewFile` / `ViewImage` 等 | 展示组件 |
| `DownloadModal` | 下载弹框，通过 ref 调用 `download()` 方法，支持进度追踪/取消/重试 |

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
| `FieldRemoteSelect` | 远程搜索选择框 |
| `FieldDictSelect` | 字典选择 |
| `FieldBoolean` | 布尔值选择（select/radio/checkbox/switch） |
| `FieldDate` / `FieldDateRange` | 日期/日期范围 |
| `FieldSysOrgTreeSelect` | 系统组织树选择 |
| `FieldUploadFile` | 文件上传（`/admin/sysFile/upload`） |
| `FieldEditor` | 富文本编辑器 |
| `FieldPercent` | 百分比输入 |
| `FieldTable` / `FieldTableSelect` | 表格字段/选择 |

### 文件上传预览

文件按可见性分为公共/私有，objectName 前缀即目录（`public/` / `private/`），URL 与磁盘路径保持一致：

- `/file/{objectName}` 预览，如 `/file/public/202607/xxx.jpg`（公共，免登录）、`/file/private/202607/xxx.pdf`（私有，需登录）

上传/下载接口（需登录）：
- `POST /admin/sysFile/upload` — 上传，表单参数 `visibility`（`public` / `private`，默认 `public`）
- `GET /admin/sysFile/download/{objectName}` — 下载

上传文件默认标记为临时，保存业务数据后后端自动确认（详见[临时文件自动清理](config.md#未认领文件自动清理)）。

前端字段直接存储文件 `objectName`（如 `public/202607/xxx.jpg`），`ViewImage` / `ViewFile` / `FieldUploadFile` 自动拼接 `/file/{objectName}` 展示；上传组件通过 `visibility` prop 指定可见性，默认 `public`（私有文件显式传 `visibility='private'`）：

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

### 工具类

| 类 | 主要方法 |
|----|---------|
| `HttpUtils` | `get` / `post` / `postForm`（axios 封装，自动 context-path） |
| `DownloadModal` | `download` 实例方法，弹框显示下载进度和状态，支持取消/重试 |
| `UrlUtils` | `contextPath(path)` 拼接 context-path / URL 参数处理 |
| `DictUtils` | `dictList` / `dictLabel` / `dictOptions` / `dictTag` |
| `TreeUtils` | `buildTree` / `treeToList` / `walk` |
| `DateUtils` | `formatDate` / `formatTime` / `formatDateTime` |
| `EventBus` | `on` / `once` / `emit` / `off` — 跨组件通信，优先使用，替代 `document.dispatchEvent` |
