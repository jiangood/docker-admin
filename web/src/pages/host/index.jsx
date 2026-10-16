import {PlusOutlined} from '@ant-design/icons'
import {Button, Form, Input, InputNumber, Modal, Popconfirm, Select} from 'antd'
import React from 'react'

import {PermActions, FieldBoolean, HttpClient, Page, PageUtils, ProTable} from "@jiangood/open-admin"


const CONNECTION_TYPES = [
    {value: 'local', label: '本机'},
    {value: 'tcp', label: '远程 TCP'},
    {value: 'ssh', label: 'SSH'},
]

const LOCAL_TIP = '留空则使用平台本机 Docker：Linux 为 unix:///var/run/docker.sock，Windows 为 tcp://localhost:2375。'
    + '如需 rootless 等自定义路径，可在此填写 socket 路径。'

const DEFAULT_TCP_PORT = 2375

const PROTOCOLS = [
    {value: 'http', label: 'HTTP'},
    {value: 'https', label: 'HTTPS'},
]

/** 各协议的常见端口，切换协议时自动跟着换 */
const PROTOCOL_PORTS = {http: DEFAULT_TCP_PORT, https: 443}

function isHttps(record) {
    return /^https:\/\//i.test((record && record.dockerHost) || '')
}

function connectionTypeLabel(v) {
    const item = CONNECTION_TYPES.find(i => i.value === (v || 'local'))
    return item ? item.label : '本机'
}

function endpointOf(record) {
    const type = record.connectionType || 'local'
    if (type === 'ssh') {
        return `ssh://${record.sshUser || 'root'}@${record.sshHost}:${record.sshPort || 22}`
    }
    if (type === 'local') {
        return record.dockerHost || '本机（默认）'
    }
    return record.dockerHost
}

/**
 * 远程 TCP 地址拆成协议、主机、端口，供表单分开编辑。
 * 后端规范化后 dockerHost 形如 tcp://192.168.1.2:2375 或 https://docker.example.com；
 * 不带端口（域名直连）时端口为空。
 */
function splitTcpHost(dockerHost) {
    const raw = (dockerHost || '').trim()
    const tcpProtocol = /^https:\/\//i.test(raw) ? 'https' : 'http'
    const authority = raw.replace(/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//, '')
    const matched = /^(.*):(\d+)$/.exec(authority)
    if (matched) {
        return {tcpProtocol, tcpHost: matched[1], tcpPort: Number(matched[2])}
    }
    // 没写端口就留空：保存时不补端口，传输层按 http/https 默认端口（80/443）访问
    return {tcpProtocol, tcpHost: authority, tcpPort: null}
}

/**
 * 提交前把协议 + 主机 + 端口拼回 dockerHost，并清掉 TCP 专用字段（实体没有这几列）。
 * https 前缀是后端识别 TLS 的标记。
 */
function buildPayload(values) {
    const payload = {...values}
    if ((payload.connectionType || 'local') === 'tcp') {
        const host = (payload.tcpHost || '').trim()
        const port = payload.tcpPort
        const prefix = payload.tcpProtocol === 'https' ? 'https://' : ''
        payload.dockerHost = prefix + host + (port ? `:${port}` : '')
    }
    delete payload.tcpHost
    delete payload.tcpPort
    delete payload.tcpProtocol
    return payload
}


export default class extends React.Component {

    state = {
        formValues: {},
        formOpen: false,
        testing: false,
        testingId: null
    }

    formRef = React.createRef()
    tableRef = React.createRef()

    columns = [

        {
            title: '名称',
            dataIndex: 'name',
        },

        {
            title: 'IP地址',
            dataIndex: 'ip',
            render: v => v || '-',
        },

        {
            title: '构建节点',
            dataIndex: 'isRunner',
            valueType: 'boolean',
            render(v) {
                return v ? '是':'否';
            }

        },

        {
            title: '连接方式',
            dataIndex: 'connectionType',
            render: (v, record) => isHttps(record) ? '远程 TCP(HTTPS)' : connectionTypeLabel(v)
        },


        {
            title: '连接地址',
            dataIndex: 'dockerHost',
            render: (_, record) => endpointOf(record)
        },


        {
            title: '操作',
            dataIndex: 'option',
            valueType: 'option',
            render: (_, record) => (
                <PermActions>
                    <Button size='small' perm='host:list'
                            onClick={() => PageUtils.open('/host/view?id=' + record.id, '主机-' + record.name)}>
                        查看
                    </Button>
                    <Button size='small' perm='host:save' onClick={() => this.handleTestRow(record)}>
                        {this.state.testingId === record.id ? '测试中…' : '测试连接'}
                    </Button>
                    <Button size='small' perm='host:save' onClick={() => this.handleEdit(record)}>修改</Button>
                    <Popconfirm perm='host:delete' title='是否确定删除主机' onConfirm={() => this.handleDelete(record)}>
                        <Button size='small'>删除</Button>
                    </Popconfirm>
                </PermActions>
            ),
        },
    ]

    handleAdd = () => {
        this.setState({
            formOpen: true,
            formValues: {
                connectionType: 'local',
                isRunner: false,
                sshPort: 22,
                tcpProtocol: 'http',
                tcpPort: DEFAULT_TCP_PORT,
                headerName: 'Authorization',
            }
        })
    }

    handleEdit = record => {
        const type = record.connectionType || 'local'
        const formValues = {
            ...record,
            connectionType: type,
            sshPort: record.sshPort || 22,
            sshUser: record.sshUser || 'root',
            // 老数据没有请求头名称（列可能是 NULL），回显默认值
            headerName: record.headerName || 'Authorization',
        }
        if (type === 'tcp') {
            // 远程 TCP 拆成协议 + 主机 + 端口编辑（保存时再拼回 dockerHost）
            Object.assign(formValues, splitTcpHost(record.dockerHost))
            delete formValues.dockerHost
        }
        this.setState({formOpen: true, formValues})
    }

    /**
     * 切换 HTTP/HTTPS 时，把「上一个协议的默认端口」跟着换成新协议的默认端口；
     * 用户自己填过（或刻意清空）的端口不动。
     */
    handleProtocolChange = changed => {
        const protocol = changed.tcpProtocol
        if (!protocol || !this.formRef.current) {
            return
        }
        const other = protocol === 'https' ? 'http' : 'https'
        if (this.formRef.current.getFieldValue('tcpPort') === PROTOCOL_PORTS[other]) {
            this.formRef.current.setFieldValue('tcpPort', PROTOCOL_PORTS[protocol])
        }
    }


    onFinish = values => {
        HttpClient.post('admin/host/save', buildPayload(values)).then(rs => {
            this.setState({formOpen: false})
            this.tableRef.current.reload()
        })
    }


    handleTest = () => {
        this.formRef.current.validateFields().then(values => {
            this.setState({testing: true})
            HttpClient.post('admin/host/test', buildPayload(values))
                .catch(() => {
                })
                .finally(() => this.setState({testing: false}))
        }).catch(() => {
        })
    }


    // 列表内测试：直接提交该行配置，SSH 密码由后端按 id 补全
    handleTestRow = record => {
        this.setState({testingId: record.id})
        HttpClient.post('admin/host/test', record)
            .catch(() => {
            })
            .finally(() => this.setState({testingId: null}))
    }


    handleDelete = record => {
        HttpClient.postForm('admin/host/delete', {id: record.id}).then(rs => {
            this.tableRef.current.reload()
        })
    }

    render() {
        return <Page padding>
            <ProTable
                actionRef={this.tableRef}
                toolBarRender={() => {
                    return <PermActions>
                        <Button perm='host:save' type='primary' onClick={this.handleAdd}>
                            <PlusOutlined/> 新增
                        </Button>
                    </PermActions>
                }}
                request={(params) => HttpClient.get('admin/host/page', params)}
                columns={this.columns}
            />

            <Modal title='主机'
                   open={this.state.formOpen}
                   onCancel={() => this.setState({formOpen: false})}
                   destroyOnHidden
                   footer={[
                       <Button key='cancel' onClick={() => this.setState({formOpen: false})}>取消</Button>,
                       <Button key='test' loading={this.state.testing} onClick={this.handleTest}>测试连接</Button>,
                       <Button key='ok' type='primary' onClick={() => this.formRef.current.submit()}>确定</Button>,
                   ]}
            >

                <Form ref={this.formRef} labelCol={{flex: '100px'}}
                      initialValues={this.state.formValues}
                      onValuesChange={this.handleProtocolChange}
                      onFinish={this.onFinish}>
                    <Form.Item name='id' noStyle></Form.Item>

                    <Form.Item label='名称' name='name' rules={[{required: true}]}>
                        <Input/>
                    </Form.Item>
                    <Form.Item label='IP地址' name='ip' rules={[{required: true, message: '请填写主机 IP 地址'}]}
                               tooltip='隧道转发目标：目标地址 = 该 IP + 应用映射到主机侧的端口，需隧道客户端所在机器可访问'>
                        <Input placeholder='192.168.1.10'/>
                    </Form.Item>
                    <Form.Item label='构建节点' name='isRunner' rules={[{required: true}]}>
                        <FieldBoolean/>
                    </Form.Item>

                    <Form.Item label='连接方式' name='connectionType' rules={[{required: true}]}>
                        <Select options={CONNECTION_TYPES}/>
                    </Form.Item>

                    <Form.Item noStyle shouldUpdate={(prev, cur) => (prev.connectionType || 'local') !== (cur.connectionType || 'local')}>
                        {({getFieldValue}) => {
                            const type = getFieldValue('connectionType') || 'local'
                            if (type === 'ssh') {
                                return <>
                                    <Form.Item label='SSH地址' name='sshHost' rules={[{required: true}]}>
                                        <Input placeholder='192.168.1.2'/>
                                    </Form.Item>
                                    <Form.Item label='SSH端口' name='sshPort' rules={[{required: true}]}>
                                        <InputNumber style={{width: '100%'}} min={1} max={65535}/>
                                    </Form.Item>
                                    <Form.Item label='SSH用户' name='sshUser' rules={[{required: true}]}>
                                        <Input placeholder='root'/>
                                    </Form.Item>
                                    <Form.Item label='SSH密码' name='sshPassword'
                                               tooltip='编辑时留空表示不修改密码'>
                                        <Input.Password autoComplete='new-password'/>
                                    </Form.Item>
                                </>
                            }
                            if (type === 'tcp') {
                                return <>
                                    <Form.Item label='协议' name='tcpProtocol' initialValue='http'
                                               tooltip={<div style={{width: 460}}>HTTP 走明文；HTTPS 会加密传输，
                                                   但<b>不校验证书与主机名</b>（自签证书、证书里没填域名的 Docker API
                                                   网关用这个）。</div>}>
                                        <Select options={PROTOCOLS}/>
                                    </Form.Item>
                                    <Form.Item label='主机地址' name='tcpHost' rules={[{required: true}]}
                                               tooltip='Docker 所在机器的 IP 或域名，端口在下一项单独填写'>
                                        <Input placeholder='192.168.1.2 或 docker.example.com'/>
                                    </Form.Item>
                                    <Form.Item label='端口' name='tcpPort' initialValue={DEFAULT_TCP_PORT}
                                               tooltip={<div style={{width: 460}}>Docker daemon 监听端口，默认
                                                   2375（HTTPS 常见 443）。<br/>留空表示不指定端口，适用于由反向代理
                                                   直接暴露的域名（按 http/https 默认端口 80/443 访问）。</div>}>
                                        <InputNumber style={{width: '100%'}} min={1} max={65535}
                                                     placeholder='留空则不指定端口'/>
                                    </Form.Item>
                                    <Form.Item label='自定义请求头' name='headerName' initialValue='Authorization'
                                               tooltip={<div style={{width: 460}}>代理要求的请求头名称（header name），
                                                   默认 <b>Authorization</b>（docker-api-auth）；http-tunnel
                                                   等隧道代理填它约定的自定义 key。</div>}>
                                        <Input placeholder='Authorization'/>
                                    </Form.Item>
                                    <Form.Item label='请求头值' name='headerValue'
                                               tooltip={<div style={{width: 460}}>名称为 <b>Authorization</b>
                                                   时只填 token 会自动补 Bearer 前缀，其它名称原样发送。<br/>
                                                   编辑时留空表示不修改。</div>}>
                                        <Input.Password autoComplete='new-password'
                                                        placeholder='留空则不带请求头，如 your-token'/>
                                    </Form.Item>
                                </>
                            }
                            return <Form.Item label='Socket 路径' name='dockerHost'
                                              tooltip={<div style={{width: 460}}>{LOCAL_TIP}</div>}>
                                <Input allowClear placeholder='留空使用默认（unix:///var/run/docker.sock）'/>
                            </Form.Item>
                        }}
                    </Form.Item>

                </Form>
            </Modal>
        </Page>


    }
}


