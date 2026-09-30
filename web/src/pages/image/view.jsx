import {
  Alert, Button, Card, Descriptions, Form,
  Modal, Select, Space, Spin, Switch, Table, Tabs, Tag, Tooltip, Typography
} from 'antd';
import React from 'react';
import {
  CheckCircleFilled,
  ClockCircleOutlined,
  CloseCircleFilled,
  Loading3QuartersOutlined,
  MinusCircleTwoTone
} from "@ant-design/icons";
import {DateUtils, HttpClient, PageUtils, ProTable, UrlUtils, ViewText} from "@jiangood/open-admin";
import LogView from "../../components/LogView";


function getIcon(key, index) {
  const iconDict = {
    PENDING: <ClockCircleOutlined key={index}/>,
    PROCESSING: <Loading3QuartersOutlined key={index} spin/>,
    SUCCESS: <CheckCircleFilled key={index} style={{color: 'green'}}/>,
    ERROR: <CloseCircleFilled key={index} style={{color: 'red'}}/>,
    CANCEL: <MinusCircleTwoTone/>
  }
  return iconDict[key]
}


export default class extends React.Component {

  state = {
    image: null,
    showTrigger: false,
    logRow: null,
    tagOptions: [],
    versions: [],
    apps: [],
    webhookLoading: false
  }
  actionRef = React.createRef();
  timer = null

  componentDidMount() {
    this.id = PageUtils.currentParams().id

    this.loadImage()
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

  loadImage = () => {
    HttpClient.get('admin/image/get', {id: this.id}).then(rs => this.setState({image: rs.data}))
  }

  loadVersions = () => {
    HttpClient.get('admin/image/versions', {imageId: this.id}).then(rs => this.setState({versions: rs.data || []}))
  }

  loadApps = () => {
    HttpClient.get('admin/image/apps', {imageId: this.id}).then(rs => this.setState({apps: rs.data || []}))
  }

  reload = () => {
    this.actionRef.current?.reload()
  }

  retry = row => {
    HttpClient.get("admin/image/build", {
      imageId: row.imageId,
      tag: row.tag,
      buildHostId: row.buildHostId
    }).then(rs => {
      this.reload()
    })
  }

  stop = row => {
    HttpClient.get("admin/image/stopBuild", row).then(rs => {
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
    HttpClient.get('admin/image/tags', {imageId: this.id}).then(rs => {
      this.setState({tagOptions: rs.data || []})
    })
    this.setState({showTrigger: true})
  }

  submitTrigger = (values) => {
    HttpClient.get("admin/image/build", values).then(rs => {
      this.setState({showTrigger: false})
      this.actionRef.current.reload()
    })
  }

  cleanError = () => {
    HttpClient.get("admin/image/cleanErrorLog", {id: this.state.image.id}).then(rs => {
      this.actionRef.current.reload()
    })
  }

  resetWebhook = () => {
    HttpClient.get("admin/image/resetWebhook", {id: this.state.image.id}).then(() => {
      this.loadImage()
    })
  }

  toggleWebhook = (checked) => {
    const id = this.state.image.id
    if (checked && !this.webhookUrl()) {
      return
    }
    this.setState({webhookLoading: true})
    const request = checked
      ? HttpClient.get('admin/image/enableWebhook', {id, hookUrl: this.webhookUrl()})
      : HttpClient.get('admin/image/disableWebhook', {id})
    request.then(() => this.loadImage())
      .finally(() => this.setState({webhookLoading: false}))
  }

  webhookUrl = () => {
    const token = this.state.image?.webhookToken
    if (!token) return ''
    return window.location.origin + UrlUtils.contextPath('/admin/public/webhook/' + token)
  }

  columns = [
    {
      title: '镜像',
      dataIndex: 'imageName',
    },
    {
      title: '开始时间',
      dataIndex: 'createTime',
      render(_, row) {
        return <Tooltip title={row.createTime}> {DateUtils.friendlyTime(row.createTime)}</Tooltip>
      }
    },
    {
      title: 'tag',
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
      title: '-',
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
    if (this.state.image == null) {
      return <Spin/>
    }

    const {image, showTrigger, logRow, tagOptions} = this.state;

    return (<>

      <Card className='mb-2'>
        <Descriptions title={image.name}>
          <Descriptions.Item label='id'>{image.id}</Descriptions.Item>
          <Descriptions.Item label='代码源'>{image.gitUrl}</Descriptions.Item>
          <Descriptions.Item label='dockerfile'>{image.dockerfile}</Descriptions.Item>
          <Descriptions.Item label='创建时间'>{image.createTime}</Descriptions.Item>
        </Descriptions>

        <div style={{display: 'flex', justifyContent: 'end'}}>
          <Button onClick={this.triggerPipeline} type="primary">立即构建</Button>
        </div>

      </Card>

      <Card className='mb-2'>
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
            imageId: image.id
          }}
          preserve={false}>
          <Form.Item name="imageId" hidden>
          </Form.Item>
          <Form.Item name="tag" label="构建 tag" rules={[{required: true, message: '请选择 tag'}]}
                     help="只支持 vX.Y.Z 形式的版本 tag">
            <Select options={tagOptions} showSearch placeholder='请选择远程 tag'/>
          </Form.Item>

          <div style={{display: 'flex', justifyContent: 'end'}}>
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
          ? <LogView url={'/admin/ws/image-build-log/' + logRow.id} websocket={true}/>
          : null}
      </Modal>

    </>)
  }

  renderTabs = () => {
    const {image, versions, apps, webhookLoading} = this.state;

    const items = [
      {
        key: 'build',
        label: '构建历史',
        children: <div className='image-build-history'>
          <ProTable
            actionRef={this.actionRef}
            toolBarRender={() => (
              <div style={{display: 'flex', justifyContent: 'flex-end'}}>
                <Button onClick={this.cleanError}>清理失败记录</Button>
              </div>
            )}
            request={(params) => {
              params.imageId = image.id
              return HttpClient.get("admin/buildLog/list", params);
            }}
            columns={this.columns}
            showSearch={false}
          />
        </div>
      },
      {
        key: 'version',
        label: '镜像版本',
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
        children: <div style={{maxWidth: 900}}>
          <Alert
            type='info'
            showIcon
            message='推送 tag 自动构建'
            description='向下面的地址推送形如 vX.Y.Z 的 tag，即可使用系统默认构建节点自动构建对应版本。'
            style={{marginBottom: 16}}
          />
          <Descriptions column={1} size='small' bordered>
            <Descriptions.Item label='自动配置'>
              <Space wrap>
                <Switch
                  checked={!!image.webhookAuto}
                  loading={webhookLoading}
                  onChange={this.toggleWebhook}
                />
                <span style={{color: '#999'}}>
                  开启后在当前代码仓库（GitLab）自动创建上面的 Webhook 地址；关闭时自动删除。
                  需在【设置-代码源】中配置 GitLab 类型与访问令牌（api 权限）。
                </span>
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
              <span style={{color: '#999'}}>
                重置令牌后旧地址立即失效，需要同步更新代码仓库中的 Webhook 配置；
                若已开启自动配置，重置令牌会自动删除仓库上的旧 Webhook，需要重新开启。
              </span>
            </Descriptions.Item>
          </Descriptions>
        </div>
      }
    ]

    return <Tabs items={items}/>
  }

}
