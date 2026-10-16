import {PlusOutlined} from '@ant-design/icons'
import {Button, Checkbox, Form, Input, InputNumber, Modal, Popconfirm, Select, Space} from 'antd'
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
 * 解析远程 TCP 地址：主机、端口、是否 https。
 * 后端规范化后 dockerHost 形如 tcp://192.168.1.2:2375 或 https://docker.example.com。
 */
function parseTcpHost(dockerHost) {
    const text = (dockerHost || '').trim()
    const https = /^https:\/\//i.test(text)
    // 去掉协议前缀与路径（粘整条 URL 也能用，如 https://host:443/info）
    const authority = text.replace(/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//, '').split('/')[0]
    const matched = /^(.*):(\d+)$/.exec(authority)
    return {
        https,
        host: matched ? matched[1] : authority,
        port: matched ? Number(matched[2]) : null,
    }
}

/**
 * 编辑回显：主机 / 端口 / SSL 拆到服务地址那一行的几个控件里（保存时再拼回 dockerHost）。
 */
function splitTcpHost(dockerHost) {
    const {https, host, port} = parseTcpHost(dockerHost)
    return {tcpHost: host, tcpPort: port, tcpTls: https}
}

/**
 * 提交前把主机 + 端口 + SSL 拼回 dockerHost，并清掉表单专用字段（实体没有这几列）。
 * https:// 前缀是后端识别 TLS 的标记；端口以「端口」框为准，框里空着就用地址里写的。
 */
function buildPayload(values) {
    const payload = {...values}
    if ((payload.connectionType || 'local') === 'tcp') {
        const parsed = parseTcpHost(payload.tcpHost)
        const https = payload.tcpTls === true || parsed.https
        const port = payload.tcpPort || parsed.port
        payload.dockerHost = (https ? 'https://' : '') + parsed.host + (port ? `:${port}` : '')
    }
    delete payload.tcpHost
    delete payload.tcpPort
    delete payload.tcpTls
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
                tcpPort: DEFAULT_TCP_PORT,
                tcpTls: false,
            }
        })
    }

    /**
     * 地址框失焦时做一次整理：粘进来的 `https://host:443` 会被拆成
     * 主机 = host、端口 = 443、SSL = 勾选，所见即所得，不留隐藏前缀。
     */
    handleHostBlur = e => {
        const raw = (e.target.value || '').trim()
        const form = this.formRef.current
        if (!raw || !form) {
            return
        }
        const {https, host, port} = parseTcpHost(raw)
        form.setFieldValue('tcpHost', host)
        if (https) {
            form.setFieldValue('tcpTls', true)
        }
        if (port) {
            // 地址里显式写了端口，就搬到端口框（以用户写的为准）
            form.setFieldValue('tcpPort', port)
        }
    }

    handleEdit = record => {
        const type = record.connectionType || 'local'
        const formValues = {
            ...record,
            connectionType: type,
            sshPort: record.sshPort || 22,
            sshUser: record.sshUser || 'root',
        }
        if (type === 'tcp') {
            // 远程 TCP 拆成主机地址（可带 https:// 前缀）+ 端口编辑（保存时再拼回 dockerHost）
            Object.assign(formValues, splitTcpHost(record.dockerHost))
            delete formValues.dockerHost
        }
        this.setState({formOpen: true, formValues})
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
                                    <Form.Item label='服务地址'
                                               tooltip={<div style={{width: 460}}>Docker 所在机器的 IP 或域名，
                                                   端口紧跟在后面（默认 2375，留空表示不指定，按 http/https 默认端口
                                                   80/443 访问）。<br/>勾选 <b>SSL</b> 走 https 加密，且不校验证书
                                                   身份（自签证书、证书里没填域名的网关也能连）。</div>}>
                                        <div style={{display: 'flex', alignItems: 'center', gap: 8}}>
                                            <Space.Compact style={{flex: 1, minWidth: 0}}>
                                                <Form.Item name='tcpHost' noStyle rules={[{required: true}]}>
                                                    <Input allowClear style={{width: '100%'}}
                                                           placeholder='192.168.1.2 或 docker.example.com'
                                                           onBlur={this.handleHostBlur}/>
                                                </Form.Item>
                                                <Form.Item name='tcpPort' noStyle initialValue={DEFAULT_TCP_PORT}>
                                                    <InputNumber style={{width: 110}} min={1} max={65535}
                                                                 placeholder='端口'/>
                                                </Form.Item>
                                            </Space.Compact>
                                            <Form.Item name='tcpTls' noStyle valuePropName='checked'>
                                                <Checkbox>SSL</Checkbox>
                                            </Form.Item>
                                        </div>
                                    </Form.Item>

                                    <Form.Item label='自定义请求头'
                                               tooltip={<div style={{width: 460}}>代理要求的请求头：左边是名称
                                                   （header name，如 Authorization），右边是值。
                                                   <br/><b>名称为空则不发送该请求头</b>；值照原样发送，需要
                                                   <b>Bearer</b> 自己写在值里；清空即删除。</div>}>
                                        <Space.Compact style={{width: '100%'}}>
                                            <Form.Item name='headerName' noStyle
                                                       rules={[({getFieldValue}) => ({
                                                           validator(_, value) {
                                                               if (!value && getFieldValue('headerValue')) {
                                                                   return Promise.reject(new Error('填了值就必须填名称，否则不会发送'))
                                                               }
                                                               return Promise.resolve()
                                                           }
                                                       })]}>
                                                <Input allowClear style={{width: '42%'}}
                                                       placeholder='名称，如 Authorization'/>
                                            </Form.Item>
                                            <Form.Item name='headerValue' noStyle>
                                                <Input.Password autoComplete='new-password'
                                                                style={{width: '58%'}}
                                                                placeholder='值，留空则不发送'/>
                                            </Form.Item>
                                        </Space.Compact>
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


