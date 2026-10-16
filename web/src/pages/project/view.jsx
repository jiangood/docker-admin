import {
  Alert, Button, Card, Descriptions, Form, Input,
  Modal, Segmented, Select, Space, Spin, Switch, Table, Tabs, Tooltip, Typography
} from 'antd';
import React from 'react';
import {
  CheckCircleFilled,
  ClockCircleOutlined,
  CloseCircleFilled,
  Loading3QuartersOutlined,
  MinusCircleTwoTone
} from "@ant-design/icons";
import {DateUtils, getToken, HttpClient, Page, PageUtils, ProTable, UrlUtils, ViewText} from "@jiangood/open-admin";
import LogView from "../../components/LogView";
import LinkButton from "../../components/LinkButton";
import {formatBytes, formatTime} from "../../components/container/utils";
import {sourceTag} from "../image-repo/index";


function getIcon(key, index) {
  const iconDict = {
    PENDING: <ClockCircleOutlined key={index}/>,
    PROCESSING: <Loading3QuartersOutlined key={index} spin/>,
    SUCCESS: <CheckCircleFilled key={index} style={{color: getToken().colorSuccess}}/>,
    ERROR: <CloseCircleFilled key={index} style={{color: getToken().colorError}}/>,
    CANCEL: <MinusCircleTwoTone/>
  }
  return iconDict[key]
}


export default class extends React.Component {

  state = {
    project: null,
    showTrigger: false,
    logRow: null,
    tagOptions: [],
    branchOptions: [],
    // 可选构建主机
    buildHostOptions: [],
    registryOptions: [],
    // 构建来源：BRANCH（分支最新代码）/ TAG（tag），默认从分支构建
    refType: 'BRANCH',
    versions: [],
    apps: [],
    webhookLoading: false,
    webhookBranchLoading: false,
    // 分支推送构建的分支输入框（null 表示跟随 project.webhookBranches）
    webhookBranchesText: null
  }
  actionRef = React.createRef();
  formRef = React.createRef();
  timer = null

  componentDidMount() {
    this.id = PageUtils.currentParams().id

    this.loadProject()
    this.loadVersions()
    this.loadApps()

    this.timer = setInterval(() => {
      if (document.hidden) return;
      this.reload()
    }, 1000 * 30)
  }

  componentWillUnmount() {
    if (this.timer) {
      clearInterval(this.timer)
    }
  }

  loadProject = () => {
    HttpClient.get('admin/project/get', {id: this.id}).then(rs => this.setState({project: rs.data}))
  }

  loadVersions = () => {
    HttpClient.get('admin/project/versionList', {projectId: this.id}).then(rs => this.setState({versions: rs.data || []}))
  }

  loadApps = () => {
    HttpClient.get('admin/project/apps', {projectId: this.id}).then(rs => this.setState({apps: rs.data || []}))
  }

  reload = () => {
    this.actionRef.current?.reload()
  }

  retry = row => {
    const params = {
      projectId: row.projectId,
      buildHostId: row.buildHostId
    }
    if (row.branch) {
      // 分支构建重试：重新拉取该分支最新代码
      params.refType = 'BRANCH'
      params.branch = row.branch
    } else {
      params.refType = 'TAG'
      params.tag = row.tag
    }
    HttpClient.get("admin/project/build", params).then(rs => {
      this.reload()
    })
  }

  stop = row => {
    HttpClient.get("admin/project/stopBuild", row).then(rs => {
      this.reload()
    })
  }

  openLog = row => {
    this.setState({logRow: row})
  }

  closeLog = () => {
    this.setState({logRow: null})
  }

  triggerPipeline = () => {
    // 远程 tag 列表已按版本倒序、分支列表已按常见主分支优先排序，故取第一项作为默认值
    HttpClient.get('admin/project/tags', {projectId: this.id}).then(rs => {
      const options = rs.data || []
      this.setState({tagOptions: options})
      this.selectDefault('tag', options)
    })
    HttpClient.get('admin/project/branches', {projectId: this.id}).then(rs => {
      const options = rs.data || []
      this.setState({branchOptions: options})
      this.selectDefault('branch', options)
    })
    // 构建主机列表：所有主机都可选，默认不选（留给后端用系统默认 runner）
    HttpClient.get('admin/host/options').then(rs => {
      this.setState({buildHostOptions: rs.data || []})
    })
    HttpClient.get('admin/registry/options').then(rs => {
      this.setState({registryOptions: rs.data || []})
    })
    this.setState({showTrigger: true, refType: 'BRANCH'})
  }

  // 默认选中第一个选项；用户已选择时不再覆盖
  selectDefault = (name, options) => {
    const form = this.formRef.current
    const value = options[0]?.value
    if (form && value && !form.getFieldValue(name)) {
      form.setFieldsValue({[name]: value})
    }
  }

  submitTrigger = (values) => {
    const params = {...values, refType: this.state.refType}
    HttpClient.get("admin/project/build", params).then(rs => {
      this.setState({showTrigger: false})
      this.reload()
      // 后端在触发时已同步落库并返回构建记录 id，这里直接打开日志弹窗，边构建边看输出
      if (rs.data) {
        this.openLog({id: rs.data, tag: values.tag || values.branch})
      }
    })
  }

  cleanError = () => {
    HttpClient.get("admin/project/cleanErrorLog", {id: this.state.project.id}).then(rs => {
      this.actionRef.current.reload()
    })
  }

  resetWebhook = () => {
    HttpClient.get("admin/project/resetWebhook", {id: this.state.project.id}).then(() => {
      this.loadProject()
    })
  }

  toggleWebhook = (checked) => {
    const id = this.state.project.id
    if (checked && !this.webhookUrl()) {
      return
    }
    this.setState({webhookLoading: true})
    const request = checked
      ? HttpClient.get('admin/project/enableWebhook', {id, hookUrl: this.webhookUrl()})
      : HttpClient.get('admin/project/disableWebhook', {id})
    request.then(() => this.loadProject())
      .finally(() => this.setState({webhookLoading: false}))
  }

  toggleBranchBuild = (checked) => {
    this.setState({webhookLoading: true})
    HttpClient.get('admin/project/setWebhookBranch', {id: this.state.project.id, enabled: checked})
      .then(() => this.loadProject())
      .finally(() => this.setState({webhookLoading: false}))
  }

  // 输入框当前值；未编辑时回退到项目配置，项目未配置时回退到默认 master,main
  branchText = () => {
    const {webhookBranchesText, project} = this.state
    if (webhookBranchesText != null) return webhookBranchesText
    return project?.webhookBranches || 'master,main'
  }

  saveBranches = () => {
    const id = this.state.project.id
    const branches = (this.branchText() || '').trim()
    this.setState({webhookBranchLoading: true})
    HttpClient.get('admin/project/setWebhookBranches', {id, branches})
      .then(() => {
        this.setState({webhookBranchesText: null})
        this.loadProject()
      })
      .finally(() => this.setState({webhookBranchLoading: false}))
  }

  webhookUrl = () => {
    const token = this.state.project?.webhookToken
    if (!token) return ''
    return window.location.origin + UrlUtils.contextPath('/admin/public/webhook/' + token)
  }

  columns = [
    {
      title: '项目',
      dataIndex: 'projectName',
    },
    {
      title: '开始时间',
      dataIndex: 'createTime',
      render(_, row) {
        return <Tooltip title={row.createTime}> {DateUtils.friendlyTime(row.createTime)}</Tooltip>
      }
    },
    {
      title: '来源',
      dataIndex: 'branch',
      render: (branch) => branch ? '分支 ' + branch : 'tag'
    },
    {
      title: '镜像版本',
      dataIndex: 'tag',
    },
    {
      title: '目录',
      dataIndex: 'context',
    },
    {
      title: 'Dockerfile',
      dataIndex: 'dockerfile',
    },
    {
      title: '代码日志',
      dataIndex: 'codeMessage',
      width: 200,
      render: (text) => <ViewText value={text} ellipsis maxLength={50}/>
    },
    {
      title: '构建主机',
      dataIndex: 'buildHostName',
    },
    {
      title: '状态',
      dataIndex: 'success',
      render(_, row) {
        let key = 'PROCESSING';

        if (row.success == true) {
          key = "SUCCESS";
        } else if (row.success == false) {
          key = "ERROR"
        }
        return getIcon(key, 1);
      }
    },
    {
      title: '耗时',
      dataIndex: 'timeSpend',
      render(t, row) {
        return DateUtils.friendlyTotalTime(t)
      }
    },
    {
      title: '操作',
      dataIndex: 'option',
      valueType: 'option',
      fixed: 'right',
      render: (_, row) => {
        const isProcessing = row.success == null;
        const isError = row.success == false;
        return <Space>
          <Button size='small' onClick={() => this.openLog(row)}>日志</Button>
          {isProcessing && <Button size='small' onClick={() => this.stop(row)}>停止</Button>}
          {isError && <Button size='small' onClick={() => this.retry(row)}>重试</Button>}
        </Space>
      }
    },
  ]

  render() {
    if (this.state.project == null) {
      return <Page padding><Spin/></Page>
    }

    const {project, showTrigger, logRow, tagOptions, branchOptions, buildHostOptions, registryOptions, refType} = this.state;

    return (<Page padding>

      <Card className='mb-4' title={project.name}>
        <Descriptions>
          <Descriptions.Item label='id'>{project.id}</Descriptions.Item>
          <Descriptions.Item label='镜像地址'>{project.imageUrl}</Descriptions.Item>
          <Descriptions.Item label='代码源'>{project.gitUrl}</Descriptions.Item>
          <Descriptions.Item label='dockerfile'>{project.dockerfile}</Descriptions.Item>
          <Descriptions.Item label='创建时间'>{project.createTime}</Descriptions.Item>
        </Descriptions>

        <div className='row-end'>
          <Button onClick={this.triggerPipeline} type="primary">立即构建</Button>
        </div>

      </Card>

      <Card className='mb-4'>
        {this.renderTabs()}
      </Card>

      <Modal open={showTrigger} title="手动触发流水线"
             destroyOnHidden={true}
             footer={null}
             onCancel={() => this.setState({showTrigger: false})}>

        <Form
          ref={this.formRef}
          onFinish={this.submitTrigger}
          labelCol={{flex: '100px'}}
          initialValues={{
            projectId: project.id
          }}
          preserve={false}>
          <Form.Item name="projectId" hidden>
          </Form.Item>
          <Form.Item label="构建来源">
            <Segmented
              value={refType}
              options={[
                {label: '分支', value: 'BRANCH'},
                {label: 'tag', value: 'TAG'}
              ]}
              onChange={v => this.setState({refType: v})}
            />
          </Form.Item>
          {refType === 'BRANCH' ? (
            <Form.Item name="branch" label="构建分支" rules={[{required: true, message: '请选择分支'}]}
                       help="使用该分支最新代码构建，镜像版本为「分支名 + 提交 id」">
              <Select options={branchOptions} showSearch placeholder='请选择远程分支'/>
            </Form.Item>
          ) : (
            <Form.Item name="tag" label="构建 tag" rules={[{required: true, message: '请选择 tag'}]}
                       help="只支持 vX.Y.Z 形式的 tag">
              <Select options={tagOptions} showSearch placeholder='请选择远程 tag'/>
            </Form.Item>
          )}

          <Form.Item name="registryId" label="目标注册中心"
                     help="可选：为本次构建指定推送目标，留空使用系统默认注册中心">
            <Select options={registryOptions} allowClear showSearch optionFilterProp="label"
                    placeholder='系统默认注册中心'/>
          </Form.Item>

          <Form.Item name="buildHostId" label="构建主机"
                     help="可选：改用网络更好的主机构建（如拉取基础镜像慢时），留空使用系统默认构建节点">
            <Select options={buildHostOptions} allowClear showSearch optionFilterProp="label"
                    placeholder='系统默认构建节点'/>
          </Form.Item>

          <div className='row-end'>
            <Button type='primary' htmlType="submit">确定</Button>
          </div>
        </Form>
      </Modal>

      <Modal open={!!logRow} title={'构建日志 - ' + (logRow?.tag || '')}
             width={900}
             destroyOnHidden={true}
             footer={null}
             onCancel={this.closeLog}>
        {logRow
          ? <LogView url={'/admin/ws/build-log/' + logRow.id} websocket={true}/>
          : null}
      </Modal>

    </Page>)
  }

  renderTabs = () => {
    const {project, versions, apps, webhookLoading, webhookBranchLoading} = this.state;

    const items = [
      {
        key: 'build',
        label: '构建历史',
        children: <div className='project-build-history'>
          <ProTable
            actionRef={this.actionRef}
            toolBarRightRender={() => (
              <Button onClick={this.cleanError}>清理失败记录</Button>
            )}
            request={(params) => {
              params.projectId = project.id
              return HttpClient.get("admin/buildLog/list", params);
            }}
            columns={this.columns}
            showSearch={false}
          />
        </div>
      },
      {
        key: 'version',
        label: `构建版本 (${versions.length})`,
        children: <Table
          size='small'
          rowKey='id'
          pagination={false}
          dataSource={versions}
          columns={[
            {title: 'tag', dataIndex: 'tag'},
            {
              title: '完整地址', dataIndex: 'fullUrl',
              render: (v) => <Typography.Text copyable code>{v}</Typography.Text>
            },
            {title: '来源', dataIndex: 'source', width: 90, render: (s) => sourceTag(s)},
            {title: '构建时间', dataIndex: 'buildTime', width: 160, render: (v) => formatTime(v)},
            {
              title: '大小', dataIndex: 'size', width: 100,
              render: (v) => v ? formatBytes(v) : '-'
            },
            {
              title: '声明端口', dataIndex: 'exposedPorts',
              render: (list) => (list && list.length) ? list.join(', ') : '-'
            },
            {
              title: '声明卷', dataIndex: 'volumes',
              render: (list) => (list && list.length) ? list.join(', ') : '-'
            },
          ]}
        />
      },
      {
        key: 'apps',
        label: '关联应用',
        children: <Table
          size='small'
          rowKey='id'
          pagination={false}
          dataSource={apps}
          columns={[
            {
              title: '应用', dataIndex: 'name',
              render: (name, row) => (
                <LinkButton
                  onClick={() => PageUtils.open('/app/view?id=' + row.id, '应用-' + name)}>{name}</LinkButton>
              )
            },
            {title: '主机', dataIndex: ['host', 'name']},
            {title: '版本', dataIndex: 'imageTag'}
          ]}
        />
      },
      {
        key: 'webhook',
        label: 'Webhook',
        children: <div className='page-card'>
          <Alert
            type='info'
            showIcon
            className='mb-4'
            title='推送 tag / 分支自动构建'
            description='推送形如 vX.Y.Z 的 tag 会构建对应版本；开启「分支推送构建」并配置分支后，仅推送这些分支会构建该分支最新代码（镜像版本为「分支名 + 提交 id」并更新 latest）。均使用系统默认构建节点。'
          />
          <Descriptions column={1} size='small' bordered>
            <Descriptions.Item label='自动配置'>
              <Space wrap>
                <Switch
                  checked={!!project.webhookAuto}
                  loading={webhookLoading}
                  onChange={this.toggleWebhook}
                />
                <Typography.Text type='secondary'>
                  开启后在当前代码仓库（GitLab）自动创建上面的 Webhook 地址；关闭时自动删除。
                  需在【设置-代码源】中配置 GitLab 类型与访问令牌（api 权限）。
                </Typography.Text>
              </Space>
            </Descriptions.Item>
            <Descriptions.Item label='分支推送构建'>
              <Space direction='vertical' size='small' style={{width: '100%'}}>
                <Space wrap>
                  <Switch
                    checked={!!project.webhookBranch}
                    loading={webhookLoading}
                    onChange={this.toggleBranchBuild}
                  />
                  <Typography.Text type='secondary'>
                    开启后，仅推送下列分支时才会构建该分支最新代码并更新 latest；tag push 始终构建，不受此开关影响。
                  </Typography.Text>
                </Space>
                <Space.Compact style={{maxWidth: 480, width: '100%'}}>
                  <Input
                    value={this.branchText()}
                    disabled={!project.webhookBranch}
                    placeholder='默认 master,main'
                    onChange={e => this.setState({webhookBranchesText: e.target.value})}
                    onPressEnter={this.saveBranches}
                  />
                  <Button
                    onClick={this.saveBranches}
                    loading={webhookBranchLoading}
                    disabled={!project.webhookBranch}
                  >保存</Button>
                </Space.Compact>
                <Typography.Text type='secondary'>
                  多个分支用英文逗号分隔，留空默认 master,main。
                </Typography.Text>
              </Space>
            </Descriptions.Item>
            <Descriptions.Item label='Webhook 地址'>
              <Space wrap>
                <Typography.Text copyable={{text: this.webhookUrl()}} code
                                 style={{wordBreak: 'break-all'}}>
                  {this.webhookUrl()}
                </Typography.Text>
                <Button size='small' onClick={this.resetWebhook}>重置令牌</Button>
              </Space>
            </Descriptions.Item>
            <Descriptions.Item label='说明'>
              <Typography.Text type='secondary'>
                重置令牌后旧地址立即失效，需要同步更新代码仓库中的 Webhook 配置；
                若已开启自动配置，重置令牌会自动删除仓库上的旧 Webhook，需要重新开启。
              </Typography.Text>
            </Descriptions.Item>
          </Descriptions>
        </div>
      }
    ]

    return <Tabs items={items}/>
  }

}
