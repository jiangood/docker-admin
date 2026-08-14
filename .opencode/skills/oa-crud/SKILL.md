---
name: oa-crud
description: 在业务项目中创建 CRUD 业务模块，遵循 open-admin 框架的 Entity→Repository→Service→Controller→前端页面→菜单配置的完整模式。适用于以 Maven JAR + npm 包方式引入 open-admin 的业务项目。
---

# oa-crud — 业务模块创建指南

## 适用范围

当业务项目已通过 Maven JAR（`io.github.jiangood:open-admin`）和 npm 包（`@jiangood/open-admin`）方式集成了 open-admin 框架，需要创建新的业务 CRUD 模块时使用。

## 前提条件检查

开始之前，Claude 必须读取业务项目的以下文件确认集成状态：

### Maven

`pom.xml` 中必须有：

```xml
<dependency>
    <groupId>io.github.jiangood</groupId>
    <artifactId>open-admin</artifactId>
    <version>${open-admin.version}</version>
</dependency>
```

### Spring Boot 配置

`application.yml` 已导入框架默认配置（必选）：

```yaml
spring:
  config:
    import: classpath:application-lib.yml
```

同时检查 `@SpringBootApplication` 或 `@ComponentScan` 是否覆盖了业务项目自身的包扫描范围。业务项目需要确保自己的包（如 `com.mycompany.myproject`）被扫描到，框架的 `OpenAdminConfiguration` 只扫描 `io.github.jiangood.openadmin` 包。

### npm

`package.json` 中包含：

```json
"dependencies": {
    "@jiangood/open-admin": "^3.0.1",
    "antd": "^6.0.0",
    "react": "^19.0.0",
    ...
},
"devDependencies": {
    "vite": "^8.0.0",
    "@vitejs/plugin-react": "^6.0.0",
    ...
}
```

并确认 `vite.config.ts` 使用了框架插件（`import openAdmin from '@jiangood/open-admin/vite-plugin'`）。

### 目录结构检查

```
业务项目 src/ 下应包含：
  main/java/com/xxx/          # Java 源码
  main/resources/             # 配置资源
    config/                    # 菜单/字典 YAML（可选，无则使用框架默认）
  main/resources/application.yml
```

## 第一步：需求确认

向开发者确认以下信息：

1. **业务实体名称**：中英文名（如"客户 / Customer"）
2. **字段列表**：每个字段的名称、类型（String / Integer / BigDecimal / Boolean / LocalDateTime / 枚举）、是否必填、是否作为查询条件、是否为字典项
3. **权限规划**：增删改查分别用什么权限 code（如 `biz-customer:read`、`biz-customer:create`、`biz-customer:update`、`biz-customer:delete` 等）
4. **菜单位置**：顶级菜单还是挂在现有菜单下

## 第二步：后端模块创建

### 命名约定

| 元素 | 命名规则 | 示例 |
|------|---------|------|
| 基础包 | `{groupId}.{project}` | `com.mycompany.myproject` |
| 模块包 | `{base}.modules.{module}` | `com.mycompany.myproject.modules.customer` |
| 实体类 | `{Entity}` | `Customer` |
| 数据库表 | 小写、下划线分隔、`biz_` 前缀 | `biz_customer` |
| 请求路径 | `admin/{kebab-module}` | `admin/customer` |
| 权限前缀 | `{kebab-module}:{action}` | `customer:read`, `customer:create`, `customer:update`, `customer:delete` |

### Entity

- 包：`{base}.modules.{module}.entity.{Entity}`
- 继承 `io.github.jiangood.openadmin.framework.data.BaseEntity`（自带 id/UUIDv7、createTime、updateTime、createUser、updateUser）
- `@Table(name = "biz_xxx")` 指定物理表名
- `@Getter` `@Setter` `@FieldNameConstants`（Lombok）
- Java 21 的 `String` 类型字段不需要 `@Column`（Hibernate 自动驼峰转下划线），除非需要指定 `length` 或 `nullable`
- 校验用 `jakarta.validation` 注解（`@NotBlank`、`@NotNull`、`@Size`）
- 枚举字段使用 `@Enumerated(EnumType.STRING)` + 普通 Java enum
- 可选：`@Remark("字段说明")` 来自 `io.github.jiangood.openadmin.util.annotation.Remark`

```java
package com.mycompany.myproject.modules.customer.entity;

import io.github.jiangood.openadmin.framework.data.BaseEntity;
import io.github.jiangood.openadmin.util.annotation.Remark;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldNameConstants;

@Remark("客户")
@Entity
@Getter
@Setter
@FieldNameConstants
@Table(name = "biz_customer")
public class Customer extends BaseEntity {
    private static final long serialVersionUID = 1L;

    @NotBlank
    @Remark("客户名称")
    @Size(max = 100)
    private String name;

    @Remark("联系人")
    @Size(max = 50)
    private String contact;

    @Remark("联系电话")
    @Size(max = 20)
    private String phone;

    @NotNull
    @Remark("状态")
    private Boolean enabled;
}
```

### Repository

- 包：`{base}.modules.{module}.repository.{Entity}Repository`
- 继承 `io.github.jiangood.openadmin.framework.data.BaseRepository<Entity, String>`
- 无需额外方法，通用 CRUD + Spec 动态查询 + 分页由 BaseRepository 提供
- 复杂查询用 `Spec` 构建（`io.github.jiangood.openadmin.framework.data.specification.Spec`）

```java
package com.mycompany.myproject.modules.customer.repository;

import com.mycompany.myproject.modules.customer.entity.Customer;
import io.github.jiangood.openadmin.framework.data.BaseRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CustomerRepository extends BaseRepository<Customer, String> {
}
```

### Service

- 包：`{base}.modules.{module}.service.{Entity}Service`
- 继承 `io.github.jiangood.openadmin.framework.data.BaseService<Entity>`
- 使用 `@RequiredArgsConstructor` 注入 Repository
- 通用方法由 BaseService 提供：`findAll()`、`findById()`、`save()`、`create()`、`update()`、`updateField()`、`deleteById()`、`findByField()`、`isFieldExist()`、`isUnique()` 等
- 自定义业务逻辑在此层添加

**简单 CRUD（无额外逻辑）：**

```java
package com.mycompany.myproject.modules.customer.service;

import com.mycompany.myproject.modules.customer.entity.Customer;
import com.mycompany.myproject.modules.customer.repository.CustomerRepository;
import io.github.jiangood.openadmin.framework.data.BaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
public class CustomerService extends BaseService<Customer> {

    private final CustomerRepository repository;
}
```

**需要自定义 save 逻辑（如唯一性校验）：**

```java
@RequiredArgsConstructor
@Service
public class CustomerService extends BaseService<Customer> {

    private final CustomerRepository repository;

    @Transactional
    public Customer save(Customer input, List<String> requestKeys) throws Exception {
        if (input.isNew()) {
            if (repository.existsByCode(input.getCode())) {
                throw new RuntimeException("编码已存在");
            }
            return repository.save(input);
        }
        this.updateField(input, requestKeys);
        return repository.findById(input.getId()).orElse(null);
    }
}
```

### Controller

- 包：`{base}.modules.{module}.controller.{Entity}Controller`
- `@RestController` + `@RequestMapping("admin/{kebab-module}")`
- `@RequiredArgsConstructor` 构造器注入 Service
- `@HasPermission("{module}:{action}")` 权限控制
- 统一返回 `io.github.jiangood.openadmin.util.dto.AjaxResult`

标准 5 个端点：

| 端点 | 方法 | URL | 权限 | 说明 |
|------|------|-----|------|------|
| 分页查询 | `@RequestMapping("page")` | `admin/{module}/page` | `{module}:read` | 支持 searchText 模糊搜索 + Pageable |
| 详情 | `@GetMapping("info/{id}")` | `admin/{module}/info/{id}` | `{module}:read` | 返回单条记录 |
| 创建 | `@PostMapping("create")` | `admin/{module}/create` | `{module}:create` | @RequestBody @Valid + @Log |
| 更新 | `@PostMapping("update")` | `admin/{module}/update` | `{module}:update` | @RequestBody @Valid + RequestBodyKeys + @Log |
| 删除 | `@PostMapping("delete")` | `admin/{module}/delete` | `{module}:delete` | @RequestBody IdReq + @Log |
| 选项列表 | `@GetMapping("options")` | `admin/{module}/options` | `{module}:read` | 下拉框数据源（非必选） |

```java
package com.mycompany.myproject.modules.customer.controller;

import com.mycompany.myproject.modules.customer.entity.Customer;
import com.mycompany.myproject.modules.customer.service.CustomerService;
import io.github.jiangood.openadmin.framework.config.RequestBodyKeys;
import io.github.jiangood.openadmin.framework.log.Log;
import io.github.jiangood.openadmin.framework.data.specification.Spec;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import io.github.jiangood.openadmin.util.dto.IdReq;
import io.github.jiangood.openadmin.util.dto.Option;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("admin/customer")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService service;

    @HasPermission("customer:read")
    @RequestMapping("page")
    public AjaxResult page(String searchText,
            @PageableDefault(direction = Sort.Direction.DESC, sort = "updateTime") Pageable pageable) {
        Spec<Customer> q = Spec.of().orLike(searchText, "name", "contact");
        Page<Customer> page = service.findAll(q, pageable);
        return AjaxResult.ok().data(page);
    }

    @HasPermission("customer:read")
    @GetMapping("info/{id}")
    public AjaxResult info(@PathVariable String id) {
        return service.findById(id)
                .map(c -> AjaxResult.ok().data(c))
                .orElse(AjaxResult.fail().msg("记录不存在"));
    }

    @Log("客户-创建")
    @HasPermission("customer:create")
    @PostMapping("create")
    public AjaxResult create(@Valid @RequestBody Customer input) throws Exception {
        service.save(input, null);
        return AjaxResult.ok().msg("创建成功");
    }

    @Log("客户-更新")
    @HasPermission("customer:update")
    @PostMapping("update")
    public AjaxResult update(@Valid @RequestBody Customer input, RequestBodyKeys updateFields) throws Exception {
        service.save(input, updateFields);
        return AjaxResult.ok().msg("更新成功");
    }

    @Log("客户-删除")
    @HasPermission("customer:delete")
    @PostMapping("delete")
    public AjaxResult delete(@Valid @RequestBody IdReq req) {
        service.deleteById(req.getId());
        return AjaxResult.ok().msg("删除成功");
    }

    @HasPermission("customer:read")
    @GetMapping("options")
    public AjaxResult options(String searchText) {
        Spec<Customer> q = Spec.of().orLike(searchText, "name");
        List<Customer> list = service.findAll(q, Sort.by("name"));
        List<Option> options = list.stream().map(a -> new Option(a.getId(), a.getName())).toList();
        return AjaxResult.ok().data(options);
    }
}
```

### 文件认领（必须）

实体如果包含上传文件/图片字段（`FieldUploadFile`、`FieldUploadImage`、`FieldEditor` 富文本），业务保存后**必须调用 `SysFileService.claim(entity)` 认领文件**，否则文件一直处于"未认领(TEMP)"状态，默认 120 分钟后会被清理任务物理删除。

- 上传的文件初始状态为 `TEMP`（未认领），认领后变为 `IN_USE`（使用中）并记录关联表/关联 ID
- 实体字段打 `@FileField` 注解（单值文件/图片字段不加参数；富文本字段加 `html = true`，内部自动从 HTML 提取框架文件 URL）；实体无需继承 `BaseEntity`，实现 `Persistable<String>` 即可
- `sysFileService.claim(entity)`：认领实体所有 `@FileField` 字段引用的文件，joinTable 自动取实体 `@Table(name)`、joinId 自动取 `entity.getId()`，无需指定表名与字段
- `sysFileService.unclaim(entity)`：取消认领（置为 `PENDING_DELETE`）
- 更新时**先 `unclaim(old)` 再 save**（`old` 是 JPA 托管实体，save 后会变成新值，晚取无效），save 后再 `claim(entity)` 认领新引用
- 删除业务记录时建议在同一事务内先 `unclaim(entity)` 再删除（立即释放引用且随删除回滚）；未显式调用时 `CleanTempFileJob` 的孤儿扫描仍会兜底清理

参照框架示例 `ArticleController.java`：

```java
// 新增
Article result = articleService.save(param, null);
sysFileService.claim(result);

// 更新 —— 先取消认领旧引用，再保存并认领新引用
Article old = service.findById(param.getId()).orElse(null);
sysFileService.unclaim(old);

Article result = articleService.save(param, updateFields);

sysFileService.claim(result);
```

> 若实体无任何文件/图片/富文本字段，可跳过本小节。

## 第三步：前端页面创建

### 路由机制说明

框架的 `@jiangood/open-admin/vite-plugin`（Vite 插件）在构建时自动扫描 `src/pages/` 目录和 `node_modules/@jiangood/open-admin/src/pages/` 目录，根据文件名和目录结构生成虚拟路由模块 `virtual:open-admin/routes`。**只需在 `src/pages/{模块}/index.jsx`（也支持 `.tsx`）创建页面文件，无需手动配置路由。**

业务项目的 `vite.config.ts` 应注册 `@jiangood/open-admin/vite-plugin`。

### 页面模板

使用 class 组件，遵循框架现有页面风格：

```jsx
import {PlusOutlined} from '@ant-design/icons'
import {Button, Form, Input, Popconfirm} from 'antd'
import React from 'react'
import {FormModal, HttpUtils, Page, PermActions, ProTable} from "@jiangood/open-admin";

export default class extends React.Component {

    modalRef = React.createRef()
    tableRef = React.createRef()

    columns = [
        { title: '名称', dataIndex: 'name' },
        { title: '联系人', dataIndex: 'contact' },
        { title: '联系电话', dataIndex: 'phone' },
        { title: '状态', dataIndex: 'enabled', render: (v) => v ? '启用' : '停用' },
        { title: '创建时间', dataIndex: 'createTime' },
        { title: '操作', dataIndex: 'option',
            render: (_, record) => (
                <PermActions>
                    <Button size='small' perm='customer:update' onClick={() => this.handleEdit(record)}>编辑</Button>
                    <Popconfirm perm='customer:delete' title='确定删除？' onConfirm={() => this.handleDelete(record)}>
                        <Button size='small'>删除</Button>
                    </Popconfirm>
                </PermActions>
            ),
        },
    ]

    handleAdd = () => this.modalRef.current.open({})
    handleEdit = record => this.modalRef.current.open({...record})
    handleSubmit = values => {
        const url = values.id ? 'admin/customer/update' : 'admin/customer/create'
        return HttpUtils.post(url, values).then(() => this.tableRef.current.reload())
    }
    handleDelete = record => {
        HttpUtils.post('admin/customer/delete', {id: record.id}).then(() => this.tableRef.current.reload())
    }

    render() {
        return <Page padding={true}>
            <ProTable
                actionRef={this.tableRef}
                toolBarRender={() => (
                    <PermActions>
                        <Button perm='customer:create' type='primary' icon={<PlusOutlined/>} onClick={this.handleAdd}>新增</Button>
                    </PermActions>
                )}
                request={(params) => HttpUtils.get('admin/customer/page', params)}
                columns={this.columns}
                searchFormRender={() => (
                    <Form.Item label='名称' name='name'>
                        <Input/>
                    </Form.Item>
                )}
            />
            <FormModal ref={this.modalRef} title='客户信息' onFinish={this.handleSubmit}>
                <Form.Item label='名称' name='name' rules={[{required: true}]}>
                    <Input/>
                </Form.Item>
                <Form.Item label='联系人' name='contact'>
                    <Input/>
                </Form.Item>
                <Form.Item label='联系电话' name='phone'>
                    <Input/>
                </Form.Item>
                <Form.Item label='状态' name='enabled'>
                    <Switch/>
                </Form.Item>
            </FormModal>
        </Page>
    }
}
```

### 页面生命周期

多 Tab 布局中，所有页面保持 mounted（仅 `display` 切换）。框架提供 `onShow()` 生命周期方法，在页面首次加载或从其他 Tab 切回时自动调用：

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

> 仅 class 组件支持，方法名固定为 `onShow`。

### 字段组件选用指南

当字段需要特殊业务组件时，从 `@jiangood/open-admin` 引入并替换模板中的 `Input`：

| 业务需求 | 组件 | import |
|---------|------|--------|
| 字典下拉 | `FieldDictSelect typeCode="dict_type"` | `@jiangood/open-admin` |
| 远程搜索下拉 | `FieldRemoteSelect url="admin/xxx/options"` | `@jiangood/open-admin` |
| 远程树 | `FieldRemoteTree url="..."` | `@jiangood/open-admin` |
| 远程树选择 | `FieldRemoteTreeSelect url="..."` | `@jiangood/open-admin` |
| 远程树级联 | `FieldRemoteTreeCascader url="..."` | `@jiangood/open-admin` |
| 组织树选择 | `FieldSysOrgTreeSelect` | `@jiangood/open-admin` |
| 组织树 | `FieldSysOrgTree` | `@jiangood/open-admin` |
| 部门树 | `FieldDeptTreeSelect` | `@jiangood/open-admin` |
| 单位树 | `FieldUnitTreeSelect` | `@jiangood/open-admin` |
| 用户选择 | `FieldUserSelect` | `@jiangood/open-admin` |
| 用户多选 | `FieldUserSelectMultiple` | `@jiangood/open-admin` |
| 组织多选 | `FieldOrgTreeMultipleSelect` | `@jiangood/open-admin` |
| 布尔开关 | `FieldBoolean` | `@jiangood/open-admin` |
| 日期选择 | `FieldDate` / `FieldDateRange` | `@jiangood/open-admin` |
| 数字范围 | `FieldNumberRange` | `@jiangood/open-admin` |
| 富文本 | `FieldEditor` | `@jiangood/open-admin` |
| 文件上传 | `FieldUploadFile` | `@jiangood/open-admin` |
| 图片上传（裁剪/压缩） | `FieldUploadImage` | `@jiangood/open-admin` |
| 表格选择 | `FieldTableSelect` | `@jiangood/open-admin` |
| 百分比 | `FieldPercent` | `@jiangood/open-admin` |
| 表格内嵌 | `FieldTable` | `@jiangood/open-admin` |

### 展示视图组件选用指南

在表格列中渲染字段值时使用：

| 场景 | 组件 | import |
|------|------|--------|
| 布尔值（是/否） | `ViewBoolean` | `@jiangood/open-admin` |
| 布尔值（启用/停用开关） | `ViewSwitch` | `@jiangood/open-admin` |
| 审批状态 | `ViewApproveStatus` | `@jiangood/open-admin` |
| 图片预览 | `ViewImage` | `@jiangood/open-admin` |
| 文件下载 | `ViewFile` / `ViewFileButton` | `@jiangood/open-admin` |
| 密码脱敏 | `ViewPassword` | `@jiangood/open-admin` |
| 纯文本展示 | `ViewText` | `@jiangood/open-admin` |
| 范围展示 | `ViewRange` | `@jiangood/open-admin` |

## 第四步：菜单与权限配置

### YAML 菜单定义

业务项目在 `src/main/resources/application-menu*.yml` 中定义自己的菜单（Map 格式，key 为菜单 id，`pid` 表达父子关系）。框架的 `SysMenuRepositoryImpl` 扫描 `classpath*:application-menu*.yml` 自动合并，框架默认菜单与业务菜单互不干扰。

```yaml
# src/main/resources/application-menu-customer.yml
menus:
  customer:
    name: 客户管理
    icon: TeamOutlined
    seq: 20000
  customer-list:
    pid: customer
    name: 客户列表
    path: /customer
    perms:
      - {name: 读取, code: read}
      - {name: 创建, code: create}
      - {name: 更新, code: update}
      - {name: 删除, code: delete}
```

如需要将菜单挂在框架已有菜单下（如挂在"系统管理"下），将 `pid` 指定为框架菜单 id：

```yaml
  customer-list:
    pid: system   # 挂在系统管理菜单下
    name: 客户管理
    path: /customer
```

### 权限对应关系

三层权限对应关系：

| 层级 | 配置位置 | 写法 |
|------|---------|------|
| 后端 | Controller `@HasPermission` | `@HasPermission("customer:create")` |
| 前端 | Button `perm` prop | `<Button perm="customer:create">新增</Button>` |
| 菜单 | YAML `perms` | `- {name: 创建, code: read}`（框架自动拼接为 `{menuId}:{code}` 即 `customer-list:read`） |

> 注意：菜单 YAML 中 `code` 填写短名称（如 `read`、`create`），框架的 `MenuDefinition.getPermCodes()` 会自动拼接为 `{菜单id}:{code}`。如需跨菜单复用权限码，可直接填写完整码（包含冒号）。

框架通过 `@HasPermission` 注解 + AOP 切面拦截未授权请求。前端 `PermActions` 和 `Perm` 组件根据当前用户的权限动态显示/隐藏按钮。

## 第五步：验证清单

完成后逐项确认：

### 编译验证
- [ ] `mvn compile` 编译通过
- [ ] 前端 `npm run build` 或 `npm run dev` 正常

### 后端验证
- [ ] `GET admin/{module}/page` 返回正确分页数据
- [ ] `GET admin/{module}/info/{id}` 返回单条数据
- [ ] `POST admin/{module}/create` 新增成功
- [ ] `POST admin/{module}/update` 修改成功
- [ ] `POST admin/{module}/delete` 删除成功

### 前端验证
- [ ] 页面通过菜单访问正常显示
- [ ] 列表数据正常展示
- [ ] 搜索/分页功能正常
- [ ] 新增/编辑弹窗正常
- [ ] 删除操作正常

### 权限验证
- [ ] 无权限用户看不到操作按钮
- [ ] 未授权 API 返回 403

## 代码规范约束

- 业务 Service 使用构造器注入（`@RequiredArgsConstructor` + `private final`），禁止 `@Resource` / `@Autowired` 字段注入
- 有 `BaseService<T>` 时继承，使用 `@RequiredArgsConstructor` 注入 repository
- Controller 统一返回 `AjaxResult`
- 需要操作日志的端点加 `@Log("业务-操作描述")`
- 敏感端点加 `@RateLimit` 限流（如登录、短信验证码）
- Java import 使用框架的全限定名（参见上文模板）
- 前端 import 使用 `@jiangood/open-admin` 包名（框架组件位于此包中）
- 直接输出代码，避免冗余说明；确保代码完整可运行

## 参考

需要时查阅框架源码：

- `io.github.jiangood.openadmin.framework.data.specification.Spec` — 动态查询构建器
- `io.github.jiangood.openadmin.framework.data.BaseEntity` — 实体基类
- `io.github.jiangood.openadmin.framework.data.BaseRepository` — Repository 基类
- `io.github.jiangood.openadmin.framework.data.BaseService` — Service 基类（含 `save`、`create`、`update`、`updateField`、`deleteById`、`findByField`、`isUnique` 等方法）
- `io.github.jiangood.openadmin.framework.validator` — 自定义校验注解包（`@ValidateMobile`、`@ValidateIdNum`、`@ValidatePassword` 等）
- `io.github.jiangood.openadmin.framework.log.Log` — 操作日志注解
- `io.github.jiangood.openadmin.framework.ratelimit.RateLimit` — 接口限流注解
- `io.github.jiangood.openadmin.util.dto.AjaxResult` — 统一响应体
- `io.github.jiangood.openadmin.util.dto.Option` — 下拉选项 DTO
- `web/src/framework/components/ProTable/` — 表格组件源码
- `web/src/framework/components/FormModal/` — 弹窗表单组件源码
