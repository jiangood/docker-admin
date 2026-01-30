import {AutoComplete, Button, Checkbox, Form, Modal, Select, Space, Tooltip} from 'antd';
import React from 'react';
import {
  CheckCircleFilled,
  ClockCircleOutlined,
  CloseCircleFilled,
  Loading3QuartersOutlined,
  MinusCircleTwoTone
} from "@ant-design/icons";
import {HttpUtils, ProTable} from "@jiangood/open-admin";
import {DateUtils} from "@jiangood/open-admin";
import dayjs from "dayjs";


let api = 'admin/buildLog/';

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
    curRow: {},

    showTrigger: false,

    hostOptions: []
  }
  actionRef = React.createRef();

  reload = () => {
    this.actionRef.current.reload()
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
      title: '分支/标签',
      dataIndex: 'value',
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
      title: '版本',
      dataIndex: 'version',
    },
    {
      title: '代码日志',
      dataIndex: 'codeMessage'
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
        return <Space>
          <Button size='small' href={logUrl} target='_blank'>日志</Button>
          <Button size='small' onClick={() => this.stop(row)}>停止</Button>
          <Button size='small' onClick={() => this.retry(row)}>重试</Button>
        </Space>
      }
    },
  ]

  retry = row => {
    HttpUtils.get("admin/project/build", row).then(rs => {
      this.reload()
    })
  }

  stop = row => {
    HttpUtils.get("admin/project/stopBuild", row).then(rs => {
      this.reload()
    })
  }

  timer = null

  componentDidMount() {
    if (this.timer) {
      clearInterval(this.timer)
    }


    this.timer = setInterval(() => {
      this.reload()
    }, 1000 * 30)


    HttpUtils.get('admin/host/options?onlyRunner=true').then(rs => {
      let hostOptions = rs;
      this.setState({hostOptions: hostOptions})
    })

  }

  componentWillUnmount() {
    if (this.timer) {
      clearInterval(this.timer)
    }
  }

  triggerPipeline = () => {
    this.setState({showTrigger: true})
  }


  submitTrigger = (values) => {
    HttpUtils.get("admin/project/build", values).then(rs => {
      this.setState({showTrigger: false})
      this.actionRef.current.reload()
    })
  }
  cleanError = () => {
    HttpUtils.get("admin/project/cleanErrorLog", {id: this.props.project.id}).then(rs => {
      this.actionRef.current.reload()
    })
  }

  render() {
    const {project} = this.props;
    const {showTrigger} = this.state

    let todayVersion = 'v' + dayjs().format('YYYYMMDDHH');
    return (<>

      <ProTable
        headerTitle='构建记录'
        toolBarRender={() => {
          return <Space><Button onClick={this.triggerPipeline} type="primary">立即构建</Button>
            <Button onClick={this.cleanError} title='清理失败的记录'>清理</Button>
          </Space>;
        }}
        actionRef={this.actionRef}
        request={(params) => {
          params.projectId = this.props.project.id
          return HttpUtils.get("admin/buildLog/list" , params);
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
            value: project.branch || 'master',
            version: todayVersion,
            projectId: project.id
          }}
          preserve={false}>
          <Form.Item name="projectId" hidden>
          </Form.Item>
          <Form.Item name="version" label="构建版本" rules={[{required: true}]}>
            <AutoComplete options={[
              {label: 'latest', value: 'latest'},
              {label: todayVersion, value: todayVersion}
            ]}></AutoComplete>
          </Form.Item>


          <Form.Item name="buildHostId" label="构建节点" rules={[{required: true, message: "请选择构建节点"}]}
                     initialValue={this.state.hostOptions[0]?.value}>
            <Select options={this.state.hostOptions}></Select>
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



