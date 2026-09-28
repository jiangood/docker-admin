import {
  Button, Card, Checkbox, Descriptions, Form,
  Modal, Select, Space, Spin, Tooltip, Typography
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
    project: null,
    showTrigger: false,
    hostOptions: [],
    tagOptions: []
  }
  actionRef = React.createRef();
  timer = null

  componentDidMount() {
    this.id = PageUtils.currentParams().id

    this.loadProject()

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

  loadProject = () => {
    HttpClient.get('admin/project/get', {id: this.id}).then(rs => this.setState({project: rs.data}))
  }

  reload = () => {
    this.actionRef.current?.reload()
  }

  retry = row => {
    HttpClient.get("admin/project/build", {
      projectId: row.projectId,
      tag: row.tag,
      buildHostId: row.buildHostId
    }).then(rs => {
      this.reload()
    })
  }

  stop = row => {
    HttpClient.get("admin/project/stopBuild", row).then(rs => {
      this.reload()
    })
  }

  triggerPipeline = () => {
    HttpClient.get('admin/project/tags', {projectId: this.id}).then(rs => {
      this.setState({tagOptions: rs.data || []})
    })
    this.setState({showTrigger: true})
  }

  submitTrigger = (values) => {
    HttpClient.get("admin/project/build", values).then(rs => {
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
    if (this.state.project == null) {
      return <Spin/>
    }

    const {project, showTrigger, hostOptions, tagOptions} = this.state;

    return (<>

      <Card className='mb-2'>
        <Descriptions title={project.name}>
          <Descriptions.Item label='id'>{project.id}</Descriptions.Item>
          <Descriptions.Item label='中文名称'>{project.cnName}</Descriptions.Item>
          <Descriptions.Item label='代码源'>{project.gitUrl}</Descriptions.Item>
          <Descriptions.Item label='dockerfile'>{project.dockerfile}</Descriptions.Item>
          <Descriptions.Item label='创建时间'>{project.createTime}</Descriptions.Item>
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
          params.projectId = project.id
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
            projectId: project.id,
            buildHostId: hostOptions[0]?.value
          }}
          preserve={false}>
          <Form.Item name="projectId" hidden>
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
