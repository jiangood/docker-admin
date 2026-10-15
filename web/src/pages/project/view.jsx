import {
  Alert, Button, Card, Descriptions, Form,
  Modal, Segmented, Select, Space, Spin, Switch, Table, Tabs, Tag, Tooltip, Typography
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
    // 构建来源：TAG（版本 tag）/ BRANCH（分支最新代码）
    refType: 'TAG',
    versions: [],
    apps: [],
    webhookLoading: false
  }
  actionRef = React.createRef();
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
    HttpClient.get('admin/project/versions', {projectId: this.id}).then(rs => this.setState({versions: rs.data || []}))
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
    HttpClient.get('admin/project/tags', {projectId: this.id}).then(rs => {
      this.setState({tagOptions: rs.data || []})
    })
    HttpClient.get('admin/project/branches', {projectId: this.id}).then(rs => {
      this.setState({branchOptions: rs.data || []})
    })
    this.setState({showTrigger: true, refType: 'TAG'})
  }

  submitTrigger = (values) => {
    const params = {...values, refType: this.state.refType}
    HttpClient.get("admin/project/build", params).then(rs => {
      this.setState({showTrigger: false})
      this.actionRef.current.reload()
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

    const {project, showTrigger, logRow, tagOptions, branchOptions, refType} = this.state;

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
                {label: '版本 tag', value: 'TAG'},
                {label: '分支', value: 'BRANCH'}
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
                       help="只支持 vX.Y.Z 形式的版本 tag">
              <Select options={tagOptions} showSearch placeholder='请选择远程 tag'/>
            </Form.Item>
          )}

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
    const {project, versions, apps, webhookLoading} = this.state;

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
        label: '构建版本',
        children: <Space wrap>
          {versions.length === 0 ? <span>-</span> : versions.map(t => {
            // 接口返回的是 {value, label, data} 形式的 Option，取其中的 tag 文本渲染
            const tag = t?.value ?? t;
            return <Tag key={tag} color='blue'>{tag}</Tag>
          })}
        </Space>
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
            {title: '应用', dataIndex: 'name'},
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
            description='推送形如 vX.Y.Z 的 tag 会构建对应版本；开启「分支推送构建」后，推送任意分支会构建该分支最新代码（镜像版本为「分支名 + 提交 id」并更新 latest）。均使用系统默认构建节点。'
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
              <Space wrap>
                <Switch
                  checked={!!project.webhookBranch}
                  loading={webhookLoading}
                  onChange={this.toggleBranchBuild}
                />
                <Typography.Text type='secondary'>
                  开启后，推送任意分支（含 tag push 之外的分支提交）都会构建该分支最新代码并更新 latest；
                  tag push 始终构建，不受此开关影响。
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
