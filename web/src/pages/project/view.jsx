import {
  Button, Card, Checkbox, Col, Descriptions, Form, Input,
  Modal, Popconfirm, Row, Segmented, Select, Space, Spin, Switch, Table, Tabs, Tag, Tooltip, Typography
} from 'antd';
import React from 'react';
import {
  CheckCircleFilled,
  ClockCircleOutlined,
  CloseCircleFilled,
  Loading3QuartersOutlined,
  MinusCircleTwoTone
} from "@ant-design/icons";
import {DateUtils, getToken, HttpClient, Page, PageUtils, PermUtils, ProTable, UrlUtils, ViewText} from "@jiangood/open-admin";
import LogView from "../../components/LogView";
import LinkButton from "../../components/LinkButton";
import {formatBytes, formatTime} from "../../components/container/utils";
import {sourceTag} from "../image/index";


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


/**
 * 提取地址的主机标识（host[:port]，小写），与后端 CodeSourceService.hostKey 保持一致，
 * 用于判断项目与哪个代码源匹配。兼容 http(s)://、ssh://、scp 形式（user@host:path）。
 */
function hostKey(address) {
  if (!address) return null
  const value = address.trim()
  if (value.includes('://')) {
    try {
      const url = new URL(value)
      if (!url.hostname) return null
      return (url.port ? url.hostname + ':' + url.port : url.hostname).toLowerCase()
    } catch (e) {
      return null
    }
  }
  let rest = value
  const at = rest.indexOf('@')
  if (at >= 0) rest = rest.substring(at + 1)
  const slash = rest.indexOf('/')
  if (slash >= 0) rest = rest.substring(0, slash)
  const colon = rest.indexOf(':')
  if (colon >= 0 && !/^\d+$/.test(rest.substring(colon + 1))) {
    rest = rest.substring(0, colon)
  }
  rest = rest.trim()
  return rest ? rest.toLowerCase() : null
}

/**
 * 找出与项目仓库地址匹配的 GitLab 代码源；未匹配或类型不是 GitLab 时返回 null。
 * 自动配置 Webhook 只支持 GitLab，非 GitLab 项目不展示该功能。
 */
function findGitLabSource(gitUrl, codeSources) {
  const host = hostKey(gitUrl)
  if (!host) return null
  const matched = (codeSources || []).find(o => hostKey(o.data?.url) === host)
  return matched?.data?.type === 'GITLAB' ? matched : null
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
    // 目标注册中心选项，仅用于详情展示
    registryOptions: [],
    // 构建来源：BRANCH（分支最新代码）/ TAG（tag），默认从分支构建
    refType: 'BRANCH',
    versions: [],
    apps: [],
    // 自动配置 Webhook 的开关 loading
    webhookAutoLoading: false,
    webhookBranchLoading: false,
    // 构建结果邮件通知开关 loading
    notifyEmailLoading: false,
    // 分支推送构建的分支输入框（null 表示跟随 project.webhookBranches）
    webhookBranchesText: null,
    // 代码源选项（含类型），用于判断当前项目能否使用 GitLab 自动配置；null 表示尚未加载
    codeSources: null
  }
  actionRef = React.createRef();
  formRef = React.createRef();
  timer = null
  // 构建历史最新一条记录的 id；只有它失败时才提供重试入口，否则表里会出现一堆重复的「重试」
  latestLogId = null
  // 是否存在构建中的记录（success 为空）；后端采用「新的覆盖旧的」，构建中也可以重试（会先停掉当前构建）
  buildProcessing = false

  componentDidMount() {
    this.id = PageUtils.currentParams().id

    this.loadProject()
    this.loadVersions()
    this.loadApps()
    if (PermUtils.hasPermission('registry:view')) {
      HttpClient.get('admin/registry/options').then(rs => this.setState({registryOptions: rs.data || []}))
    }
    // 代码源类型决定是否展示「GitLab 自动配置」（该接口仅要求登录）
    HttpClient.get('admin/code-source/options')
      .then(rs => this.setState({codeSources: rs.data || []}))
      .catch(() => this.setState({codeSources: []}))

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

  /** 目标注册中心展示文案：绑定的注册中心地址，未绑定显示系统默认。 */
  registryLabel = () => {
    const registryId = this.state.project?.registryId
    if (!registryId) {
      return '系统默认'
    }
    const option = this.state.registryOptions.find(o => o.value === registryId)
    return option ? option.label : registryId
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
    // 构建主机列表：所有主机都可选；默认选中全局设置的默认构建主机（未配置则不选，后端回退默认 runner）
    HttpClient.get('admin/host/options').then(rs => {
      this.setState({buildHostOptions: rs.data || []})
    })
    HttpClient.get('admin/host/default').then(rs => {
      this.selectValue('buildHostId', rs.data)
    })
    this.setState({showTrigger: true, refType: 'BRANCH'})
  }

  // 默认选中第一个选项；用户已选择时不再覆盖
  selectDefault = (name, options) => this.selectValue(name, options[0]?.value)

  // 默认选中指定值；值为空或用户已选择时不再覆盖
  selectValue = (name, value) => {
    const form = this.formRef.current
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
    this.setState({webhookAutoLoading: true})
    const request = checked
      ? HttpClient.get('admin/project/enableWebhook', {id, hookUrl: this.webhookUrl()})
      : HttpClient.get('admin/project/disableWebhook', {id})
    request.then(() => this.loadProject())
      .finally(() => this.setState({webhookAutoLoading: false}))
  }

  toggleBranchBuild = (checked) => {
    this.setState({webhookBranchLoading: true})
    HttpClient.get('admin/project/setWebhookBranch', {id: this.state.project.id, enabled: checked})
      .then(() => {
        // 开关状态变了，丢弃未保存的输入，避免显示的配置与实际生效的不一致
        this.setState({webhookBranchesText: null})
        this.loadProject()
      })
      .finally(() => this.setState({webhookBranchLoading: false}))
  }

  toggleNotifyEmail = (checked) => {
    this.setState({notifyEmailLoading: true})
    HttpClient.get('admin/project/setNotifyEmail', {id: this.state.project.id, enabled: checked})
      .then(() => this.loadProject())
      .finally(() => this.setState({notifyEmailLoading: false}))
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
      title: '构建上下文',
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
        // 仅最新一条记录失败时显示重试：列表按开始时间倒序，重试旧记录等于再触发一次同样的构建，意义不大
        const canRetry = row.success == false && row.id === this.latestLogId;
        return <Space>
          <Button size='small' onClick={() => this.openLog(row)}>日志</Button>
          {isProcessing && <Button size='small' onClick={() => this.stop(row)}>停止</Button>}
          {canRetry && (
            <Tooltip title={this.buildProcessing ? '将停止当前构建，重新开始' : null}>
              <span>
                <Button size='small' onClick={() => this.retry(row)}>重试</Button>
              </span>
            </Tooltip>
          )}
        </Space>
      }
    },
  ]

  render() {
    if (this.state.project == null) {
      return <Page padding><Spin/></Page>
    }

    const {project, showTrigger, logRow, tagOptions, branchOptions, buildHostOptions, refType, notifyEmailLoading} = this.state;

    return (<Page padding>

      <Card className='mb-4' title={project.name}>
        <Descriptions>
          <Descriptions.Item label='id'>{project.id}</Descriptions.Item>
          <Descriptions.Item label='镜像地址'>{project.imageUrl}</Descriptions.Item>
          <Descriptions.Item label='目标注册中心'>{this.registryLabel()}</Descriptions.Item>
          <Descriptions.Item label='代码源'>{project.gitUrl}</Descriptions.Item>
          <Descriptions.Item label='dockerfile'>{project.dockerfile}</Descriptions.Item>
          <Descriptions.Item label='构建上下文'>{project.context}</Descriptions.Item>
          <Descriptions.Item label='创建时间'>{project.createTime}</Descriptions.Item>
          <Descriptions.Item label='构建结果邮件'>
            <Space wrap>
              <Switch
                checked={!!project.notifyEmail}
                checkedChildren='开启'
                unCheckedChildren='关闭'
                loading={notifyEmailLoading}
                disabled={!PermUtils.hasPermission('project:save') || (!project.notifyEmail && !project.createUserEmail)}
                onChange={this.toggleNotifyEmail}
              />
              {project.notifyEmail && project.createUserEmail && (
                <Typography.Text type='secondary'>发送至 {project.createUserEmail}</Typography.Text>
              )}
              {!project.createUserEmail && (
                <Typography.Text type='warning'>
                  项目创建人未配置邮箱，请到「系统管理 - 用户管理」补充
                  {project.notifyEmail ? '（当前不会发送）' : '后才能开启'}
                </Typography.Text>
              )}
            </Space>
          </Descriptions.Item>
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
                       tooltip="使用该分支最新代码构建，镜像版本为「分支名-年月日-时分-提交 id」">
              <Select options={branchOptions} showSearch placeholder='请选择远程分支'/>
            </Form.Item>
          ) : (
            <Form.Item name="tag" label="构建 tag" rules={[{required: true, message: '请选择 tag'}]}
                       tooltip="只支持 vX.Y.Z 形式的 tag">
              <Select options={tagOptions} showSearch placeholder='请选择远程 tag'/>
            </Form.Item>
          )}

          <Form.Item name="buildHostId" label="构建主机">
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
    const {project, versions, apps} = this.state;

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
              return HttpClient.get("admin/buildLog/list", params).then(rs => {
                // 第一页的内容才是项目最新记录，用于决定「重试」按钮归属（翻页时沿用已有值）
                if (!params.page || params.page === 1) {
                  const content = rs.data?.content || []
                  this.latestLogId = content[0]?.id ?? null
                  this.buildProcessing = content.some(row => row.success == null)
                }
                return rs
              })
            }}
            columns={this.columns}
            showSearch={false}
          />
        </div>
      },
      {
        key: 'version',
        label: '构建版本',
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
        children: this.renderWebhook()
      }
    ]

    return <Tabs items={items}/>
  }

  renderWebhook = () => {
    const {project, codeSources, webhookAutoLoading, webhookBranchLoading} = this.state
    const canManage = PermUtils.hasPermission('project:webhook')
    // 自动配置只支持 GitLab：匹配不到 GitLab 代码源时不展示；历史上已开启过的仍展示，否则没有入口关闭
    const gitlabSource = findGitLabSource(project.gitUrl, codeSources)
    const sshOnly = gitlabSource?.data?.authType === 'SSH_KEY'
    const showAutoConfig = !!gitlabSource || !!project.webhookAuto

    return (<div className='page-card page-card-wide'>
      <Row gutter={16} align='top'>
        <Col xs={24} lg={15} xl={17} xxl={18}>
          <Descriptions column={1} size='small' bordered styles={{label: {whiteSpace: 'nowrap'}}}>
            <Descriptions.Item label='Webhook 地址'>
              <Space wrap>
                <Typography.Text copyable={{text: this.webhookUrl()}} code style={{wordBreak: 'break-all'}}>
                  {this.webhookUrl()}
                </Typography.Text>
                <Popconfirm
                  title='重置 Webhook 令牌？'
                  description='旧地址会立即失效，需要同步更新代码仓库中的 Webhook；若已开启自动配置，会同时删除仓库上的旧 Webhook 并关闭自动配置。'
                  okText='重置'
                  cancelText='取消'
                  onConfirm={this.resetWebhook}>
                  <Button size='small' disabled={!canManage}>重置令牌</Button>
                </Popconfirm>
              </Space>
            </Descriptions.Item>

            <Descriptions.Item label='说明'>
              <Space direction='vertical' size={4}>
                <Typography.Text type='secondary'>
                  推送形如 vX.Y.Z 的 tag 会构建对应版本，镜像版本即 tag 名。
                </Typography.Text>
                <Typography.Text type='secondary'>
                  勾选「分支推送」后，仅推送所配置的分支才会构建该分支最新代码，镜像版本为
                  「分支名-年月日-时分-提交 id」并更新 latest。
                </Typography.Text>
                <Typography.Text type='secondary'>
                  所有 Webhook 触发的构建均使用系统默认构建节点。
                </Typography.Text>
                <Typography.Text type='secondary'>
                  手动配置时，把上面的地址填入代码仓库的 Webhook，事件勾选 Push 与 Tag push。
                </Typography.Text>
              </Space>
            </Descriptions.Item>

            <Descriptions.Item label='推送触发'>
              <Space direction='vertical' size='small' style={{width: '100%'}}>
                <Space wrap>
                  <Checkbox checked disabled readOnly>tag 推送</Checkbox>
                  <Typography.Text type='secondary'>vX.Y.Z 形式的 tag 始终构建，暂不支持关闭</Typography.Text>
                </Space>
                <Space wrap>
                  <Checkbox
                    checked={!!project.webhookBranch}
                    disabled={!canManage || webhookBranchLoading}
                    onChange={e => this.toggleBranchBuild(e.target.checked)}
                  >分支推送</Checkbox>
                  <Typography.Text type='secondary'>仅构建下列分支的最新代码</Typography.Text>
                </Space>
                {project.webhookBranch && (
                  <div style={{paddingLeft: 24}}>
                    <Space.Compact style={{maxWidth: 480, width: '100%'}}>
                      <Input
                        value={this.branchText()}
                        disabled={!canManage}
                        placeholder='默认 master,main'
                        onChange={e => this.setState({webhookBranchesText: e.target.value})}
                        onPressEnter={this.saveBranches}
                      />
                      <Button
                        onClick={this.saveBranches}
                        loading={webhookBranchLoading}
                        disabled={!canManage}
                      >保存</Button>
                    </Space.Compact>
                  </div>
                )}
                <Typography.Text type='secondary'>
                  多个分支用英文逗号分隔，留空默认 master,main。
                </Typography.Text>
                {!canManage && (
                  <Typography.Text type='secondary'>缺少 Webhook 权限，仅可查看。</Typography.Text>
                )}
              </Space>
            </Descriptions.Item>
          </Descriptions>
        </Col>

        {showAutoConfig && (
          <Col xs={24} lg={9} xl={7} xxl={6}>
            <Card
              size='small'
              title='GitLab 自动配置'
              extra={<Tag color={project.webhookAuto ? 'success' : 'default'}>
                {project.webhookAuto ? '已开启' : '未开启'}
              </Tag>}>
              <Space direction='vertical' size='small' style={{width: '100%'}}>
                <Space wrap>
                  <Switch
                    checked={!!project.webhookAuto}
                    loading={webhookAutoLoading}
                    disabled={!canManage || (sshOnly && !project.webhookAuto)}
                    onChange={this.toggleWebhook}
                  />
                  <Typography.Text type='secondary'>
                    {project.webhookAuto ? '已在本项目的 GitLab 仓库上创建' : '开启后自动创建，关闭时删除'}
                  </Typography.Text>
                </Space>
                <Typography.Text type='secondary' style={{fontSize: 12}}>
                  {sshOnly
                    ? '当前代码源使用 SSH 私钥，无法调用 GitLab API，请改为访问令牌或账号密码。'
                    : '需要代码源访问令牌（api 权限）或账号密码。其他类型代码源请手动添加 Webhook。'}
                </Typography.Text>
                {!gitlabSource && (
                  <Typography.Text type='warning' style={{fontSize: 12}}>
                    未匹配到 GitLab 代码源，建议关闭并到【设置-代码源】检查。
                  </Typography.Text>
                )}
              </Space>
            </Card>
          </Col>
        )}
      </Row>
    </div>)
  }

}
