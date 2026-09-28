import {
  Button, Card, Checkbox, Descriptions, Form,
  Modal, Select, Space, Spin, Table, Tag, Tooltip, Typography
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
    hostOptions: [],
    tagOptions: [],
    versions: [],
    apps: []
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

    HttpClient.get('admin/host/options?onlyRunner=true').then(rs => {
      this.setState({hostOptions: rs.data})
    })
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
        const logUrl = "admin/sys/log/" + row.id;
        const isProcessing = row.success == null;
        const isError = row.success == false;
        return <Space>
          <Button size='small' href={logUrl} target='_blank'>日志</Button>
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

    const {image, showTrigger, hostOptions, tagOptions, versions, apps} = this.state;

    return (<>

      <Card className='mb-2'>
        <Descriptions title={image.name}>
          <Descriptions.Item label='id'>{image.id}</Descriptions.Item>
          <Descriptions.Item label='中文名称'>{image.cnName}</Descriptions.Item>
          <Descriptions.Item label='代码源'>{image.gitUrl}</Descriptions.Item>
          <Descriptions.Item label='dockerfile'>{image.dockerfile}</Descriptions.Item>
          <Descriptions.Item label='创建时间'>{image.createTime}</Descriptions.Item>
        </Descriptions>

        <Descriptions size='small' column={1} style={{marginTop: 8}}>
          <Descriptions.Item label='Webhook'>
            <Space wrap>
              <Typography.Text copyable={{text: this.webhookUrl()}} code>
                {this.webhookUrl()}
              </Typography.Text>
              <Button size='small' onClick={this.resetWebhook}>重置</Button>
              <span style={{color: '#999'}}>推送 tag（vX.Y.Z）到该地址即可自动构建</span>
            </Space>
          </Descriptions.Item>
        </Descriptions>
      </Card>

      <Card className='mb-2' title='版本' size='small'>
        <Space wrap>
          {versions.length === 0 ? <span>-</span> : versions.map(t => <Tag key={t} color='blue'>{t}</Tag>)}
        </Space>
      </Card>

      <Card className='mb-2' title='关联应用' size='small'>
        <Table
          size='small'
          rowKey='id'
          pagination={false}
          dataSource={apps}
          columns={[
            {title: '应用', dataIndex: 'name'},
            {title: '中文名称', dataIndex: 'cnName'},
            {title: '主机', dataIndex: ['host', 'name']},
            {title: '版本', dataIndex: 'imageTag'}
          ]}
        />
      </Card>

      <ProTable
        headerTitle='构建记录'
        toolBarRender={() => {
          return <Space>
            <Button onClick={this.triggerPipeline} type="primary">立即构建</Button>
            <Button onClick={this.cleanError} title='清理失败的记录'>清理</Button>
          </Space>;
        }}
        actionRef={this.actionRef}
        request={(params) => {
          params.imageId = image.id
          return HttpClient.get("admin/buildLog/list", params);
        }}
        columns={this.columns}
        showSearch={false}
      />

      <Modal open={showTrigger} title="手动触发流水线"
             destroyOnHidden={true}
             footer={null}
             onCancel={() => this.setState({showTrigger: false})}>

        <Form
          onFinish={this.submitTrigger}
          labelCol={{flex: '100px'}}
          initialValues={{
            imageId: image.id,
            buildHostId: hostOptions[0]?.value
          }}
          preserve={false}>
          <Form.Item name="imageId" hidden>
          </Form.Item>
          <Form.Item name="tag" label="构建 tag" rules={[{required: true, message: '请选择 tag'}]}
                     help="只支持 vX.Y.Z 形式的版本 tag">
            <Select options={tagOptions} showSearch placeholder='请选择远程 tag'/>
          </Form.Item>

          <Form.Item name="buildHostId" label="构建节点" rules={[{required: true, message: "请选择构建节点"}]}
                     initialValue={hostOptions[0]?.value}>
            <Select options={hostOptions}></Select>
          </Form.Item>

          <div style={{display: 'flex', gap: 24}}>
            <Form.Item name="useCache" label="使用缓存" initialValue={true} valuePropName='checked'>
              <Checkbox/>
            </Form.Item>
            <Form.Item name="pull" label="拉基础镜像" initialValue={false} valuePropName='checked'>
              <Checkbox/>
            </Form.Item>
          </div>

          <div style={{display: 'flex', justifyContent: 'end'}}>
            <Button type='primary' htmlType="submit">确定</Button>
          </div>
        </Form>
      </Modal>

    </>)
  }

}
