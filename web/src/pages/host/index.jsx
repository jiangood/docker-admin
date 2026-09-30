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

/** 兼容旧数据的连接方式取值（unix -> 本机） */
function normalizeType(v) {
    if (v === 'unix') {
        return 'local'
    }
    return v || 'local'
}

function connectionTypeLabel(v) {
    const item = CONNECTION_TYPES.find(i => i.value === normalizeType(v))
    return item ? item.label : '本机'
}

function endpointOf(record) {
    const type = normalizeType(record.connectionType)
    if (type === 'ssh') {
        return `ssh://${record.sshUser || 'root'}@${record.sshHost}:${record.sshPort || 22}`
    }
    if (type === 'local') {
        return record.dockerHost || '本机（默认）'
    }
    return record.dockerHost
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
            render: connectionTypeLabel
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
                    <Button size='small' perm='host:save' onClick={() => this.handleTestRow(record)}>
                        {this.state.testingId === record.id ? '测试中...' : '测试连接'}
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
        this.setState({formOpen: true, formValues: {connectionType: 'local', isRunner: false, sshPort: 22}})
    }

    handleEdit = record => {
        const type = normalizeType(record.connectionType)
        // 远程 TCP 只展示 IP/主机（保存时后端再补 tcp:// 与默认端口）
        const dockerHost = type === 'tcp' && record.dockerHost
            ? record.dockerHost.replace(/^tcp:\/\//i, '')
            : record.dockerHost
        this.setState({
            formOpen: true,
            formValues: {
                ...record,
                dockerHost,
                connectionType: type,
                sshPort: record.sshPort || 22,
                sshUser: record.sshUser || 'root',
            }
        })
    }


    onFinish = values => {
        HttpClient.post('admin/host/save', values).then(rs => {
            this.setState({formOpen: false})
            this.tableRef.current.reload()
        })
    }


    handleTest = () => {
        this.formRef.current.validateFields().then(values => {
            this.setState({testing: true})
            HttpClient.post('admin/host/test', values)
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
                    <Form.Item label='构建节点' name='isRunner' rules={[{required: true}]}>
                        <FieldBoolean/>
                    </Form.Item>

                    <Form.Item label='连接方式' name='connectionType' rules={[{required: true}]}>
                        <Select options={CONNECTION_TYPES}/>
                    </Form.Item>

                    <Form.Item noStyle shouldUpdate={(prev, cur) => normalizeType(prev.connectionType) !== normalizeType(cur.connectionType)}>
                        {({getFieldValue}) => {
                            const type = normalizeType(getFieldValue('connectionType'))
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
                                return <Form.Item label='主机地址' name='dockerHost' rules={[{required: true}]}
                                                  tooltip={<div style={{width: 420}}>只填 IP 或主机名即可，默认端口
                                                      2375。<br/>如需指定端口可写 192.168.1.2:2376。</div>}>
                                    <Input placeholder='192.168.1.2（默认端口 2375）'/>
                                </Form.Item>
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


