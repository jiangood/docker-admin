import {Alert, Button, Form, Input, message, Select, Spin} from "antd";
import React from "react";
import EditTable from "../../components/EditTable";
import CodeMirrorEditor from "../../components/CodeMirrorEditor";
import {HttpClient} from "@jiangood/open-admin";

/**
 * 容器配置：镜像来自镜像表（平台构建/已知）且声明了端口/卷时，对应部分按声明只读；
 * 否则（如公共镜像、未声明）可自由配置端口与卷。
 */
export default class extends React.Component {

    state = {
        loading: true,
        meta: null,
    }

    componentDidMount() {
        HttpClient.get('admin/app/configMeta', {id: this.props.app.id}).then(rs => {
            this.setState({meta: rs.data, loading: false})
        }).catch(() => {
            this.setState({loading: false})
        })
    }

    update = (form) => {
        const hide = message.loading("修改配置中...", 0)
        HttpClient.post('admin/app/updateConfig?id=' + this.props.app.id, form).then(rs => {
            this.props.onChange(rs.data)
        }).finally(hide)
    }

    formRef = React.createRef()

    portsColumns = (strict) => strict ? [
        {title: '容器端口', dataIndex: 'privatePort', readonly: true},
        {title: '协议', dataIndex: 'protocol', readonly: true},
        {title: '主机端口', dataIndex: 'publicPort', dataType: 'InputNumber'},
    ] : [
        {title: '容器端口', dataIndex: 'privatePort', dataType: 'InputNumber'},
        {title: '协议', dataIndex: 'protocol', dataType: 'Select', valueEnum: {TCP: 'TCP', UDP: 'UDP'}},
        {title: '主机端口', dataIndex: 'publicPort', dataType: 'InputNumber'},
    ]

    bindsColumns = (strict) => strict ? [
        {title: '容器路径', dataIndex: 'privateVolume', readonly: true},
        {title: '主机路径', dataIndex: 'publicVolume', dataType: 'Input'},
    ] : [
        {title: '容器路径', dataIndex: 'privateVolume', dataType: 'Input'},
        {title: '主机路径', dataIndex: 'publicVolume', dataType: 'Input'},
    ]

    deviceColumns = [
        {
            title: 'driver', dataIndex: 'driver', dataType: 'Select',
            valueEnum: {nvidia: 'nvidia', amd: 'amd'},
        },
        {
            title: 'count', dataIndex: 'count', dataType: 'Select',
            options: [
                {label: '全部(all)', value: -1},
                {label: '1', value: 1},
                {label: '2', value: 2},
                {label: '3', value: 3},
                {label: '4', value: 4},
            ],
        },
        {
            title: 'capabilities', dataIndex: 'capabilities', dataType: 'Select', mode: 'multiple',
            valueEnum: {gpu: 'gpu', compute: 'compute', utility: 'utility', graphics: 'graphics', video: 'video'},
            format: v => Array.isArray(v) ? v.flat() : v,
            parse: v => (v && v.length) ? [v] : [],
        },
    ]

    render() {
        const {app} = this.props
        const {loading, meta} = this.state
        if (loading) {
            return <Spin/>
        }
        if (!app.config) {
            return <Spin/>
        }

        const strictPorts = !!(meta && meta.strictPorts)
        const strictVolumes = !!(meta && meta.strictVolumes)

        const initialValues = {
            ...app.config,
            ports: meta ? meta.ports : (app.config.ports || []),
            binds: meta ? meta.volumes : (app.config.binds || []),
        }

        return <>

            <Form ref={this.formRef} colon={false} labelCol={{flex: '100px'}} onFinish={this.update}
                  initialValues={initialValues}>

                {(!strictPorts || !strictVolumes) &&
                    <Alert type='info' showIcon style={{marginBottom: 16}}
                           message='该镜像不在镜像表中或其未声明端口/卷，端口与卷可自由配置'/>}

                <Form.Item label='网络模式' name='networkMode'>
                    <Select style={{width: 200}}
                        options={[
                            {label: '桥接模式', value: 'bridge',},
                            {label: '主机模式', value: 'host',},
                            {label: '无需网络', value: 'none',},
                        ]}
                    >
                    </Select>
                </Form.Item>

                <Form.Item noStyle dependencies={['networkMode']}>
                    {(fm) => {
                        const networkMode = fm.getFieldValue('networkMode')
                        if (networkMode === 'bridge') {
                            return <Form.Item label='端口映射' name='ports'
                                              tooltip={strictPorts ? '端口来自镜像声明，仅可修改主机端口' : '镜像未声明端口，可自由配置'}>
                                <EditTable columns={this.portsColumns(strictPorts)}
                                           canAdd={!strictPorts} canRemove={!strictPorts}
                                           extra='暂无端口'/>
                            </Form.Item>
                        }
                    }}
                </Form.Item>

                <Form.Item label='文件映射' name='binds'
                           tooltip={strictVolumes ? '卷来自镜像声明，仅可修改主机路径' : '镜像未声明卷，可自由配置'}>
                    <EditTable columns={this.bindsColumns(strictVolumes)}
                               canAdd={!strictVolumes} canRemove={!strictVolumes}
                               extra='暂无卷'/>
                </Form.Item>

                <Form.Item label='环境变量' tooltip='yml格式' name='environmentYAML'>
                    <CodeMirrorEditor/>
                </Form.Item>

                <Form.Item label='启动命令' name='cmd'>
                    <Input/>
                </Form.Item>

                <Form.Item label='extraHosts' name='extraHosts' tooltip='域名IP映射,类似dns,hosts文件'>
                    <Input placeholder='域名:IP 域名2:IP2'/>
                </Form.Item>

                <Form.Item label='设备请求' name='deviceRequests'
                           tooltip='GPU 等设备透传，对应 docker run --gpus；需目标主机已安装 nvidia-container-toolkit'>
                    <EditTable columns={this.deviceColumns}
                               defaultRow={{driver: 'nvidia', count: -1, capabilities: [['gpu']]}}
                               extra='未配置设备请求'/>
                </Form.Item>


                <Form.Item label=' '>
                    <Button type="primary" danger htmlType='submit' size={"large"}>保存并重启</Button>
                </Form.Item>
            </Form>



        </>

    }
}
