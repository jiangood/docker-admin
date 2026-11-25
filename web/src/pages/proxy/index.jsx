import {Alert, Button, Card, Form, Input, InputNumber, Modal, Popconfirm, Select, Space, Spin, Table, Tag, Typography, message} from 'antd'
import {ArrowDownOutlined, ArrowUpOutlined} from '@ant-design/icons'
import React from 'react'
import {HttpClient, Page, PermActions, PermUtils} from '@jiangood/open-admin'

const PROTOCOLS = [
    {value: 'http', label: 'HTTP'},
    {value: 'socks5', label: 'SOCKS5'},
]

const DEFAULT_TEST_TARGET = 'www.baidu.com:443'

/**
 * 设置 - 代理管理：维护主机接入用的连接代理（主要是 HTTP），支持测试与排序。
 * <p>
 * 主机表单里的「代理」下拉读这里的配置；保存主机时后端把代理的地址与账号密码
 * 复制到主机的代理列，代理被修改后会自动同步到引用它的主机。
 */
export default class extends React.Component {

    state = {
        loading: true,
        rows: [],
        modalOpen: false,
        editing: false,
        formValues: {},
        testOpen: false,
        testValues: {},
        testTarget: DEFAULT_TEST_TARGET,
        testing: false,
        testResult: null,
    }

    formRef = React.createRef()

    // 首次加载由 onShow 触发（PageFrame 挂载时会调一次），这里不再重复请求
    onShow() {
        this.load()
    }

    load = () => {
        this.setState({loading: true})
        HttpClient.get('admin/proxy/list')
            .then(rs => this.setState({rows: rs.data || [], loading: false}))
            .catch(() => this.setState({loading: false}))
    }

    // 回填走 initialValues：Modal 的 children 首次 open 才挂载，
    // 在 setState 回调里 setFieldsValue 时表单尚未挂载，数据会丢失。
    create = () => {
        this.setState({modalOpen: true, editing: false, formValues: {type: 'http'}})
    }

    edit = record => {
        this.setState({modalOpen: true, editing: true, formValues: {...record, password: ''}})
    }

    save = values => {
        const hide = message.loading('保存中…', 0)
        HttpClient.post('admin/proxy/save', values)
            .then(() => {
                this.setState({modalOpen: false})
                this.load()
            }).finally(hide)
    }

    remove = id => {
        HttpClient.postForm('admin/proxy/delete', {id})
            .then(() => this.load())
    }

    move = (record, direction) => {
        HttpClient.postForm('admin/proxy/move', {id: record.id, direction})
            .then(() => this.load())
    }

    openTest = record => {
        this.setState({
            testOpen: true,
            testValues: {...record, password: ''},
            testTarget: DEFAULT_TEST_TARGET,
            testResult: null,
        })
    }

    runTest = () => {
        const {testValues, testTarget} = this.state
        this.setState({testing: true, testResult: null})
        HttpClient.post('admin/proxy/test', {...testValues, target: testTarget})
            .then(rs => this.setState({testResult: rs.data || null}))
            .catch(() => {
            })
            .finally(() => this.setState({testing: false}))
    }

    render() {
        const {loading, rows, modalOpen, editing, formValues, testOpen, testValues, testTarget, testing, testResult} = this.state
        const canTest = PermUtils.hasPermission('proxy:test')

        const columns = [
            {
                title: '排序',
                width: 88,
                render: (_, record) => {
                    const index = rows.findIndex(r => r.id === record.id)
                    return <Space size={0}>
                        <Button type='text' size='small' icon={<ArrowUpOutlined/>} disabled={index <= 0}
                                title='上移' onClick={() => this.move(record, 'up')}/>
                        <Button type='text' size='small' icon={<ArrowDownOutlined/>}
                                disabled={index < 0 || index >= rows.length - 1}
                                title='下移' onClick={() => this.move(record, 'down')}/>
                    </Space>
                },
            },
            {title: '名称', dataIndex: 'name'},
            {
                title: '协议',
                dataIndex: 'type',
                width: 100,
                render: v => <Tag color={v === 'socks5' ? 'purple' : 'blue'}>{(v || 'http').toUpperCase()}</Tag>,
            },
            {title: '代理地址', dataIndex: 'address'},
            {title: '用户名', dataIndex: 'username', render: v => v || '-'},
            {
                title: '密码',
                dataIndex: 'passwordMasked',
                width: 100,
                render: v => v ? '已配置' : '未配置',
            },
            {
                title: '备注',
                dataIndex: 'remark',
                render: v => v
                    ? <Typography.Text ellipsis={{tooltip: v}} style={{maxWidth: 220}}>{v}</Typography.Text>
                    : '-',
            },
            {title: '更新时间', dataIndex: 'updateTime', width: 165},
            {
                title: '操作',
                key: 'action',
                width: 200,
                render: (_, row) => <Space>
                    {canTest &&
                        <Button type='link' onClick={() => this.openTest(row)}>测试</Button>}
                    <Button type='link' onClick={() => this.edit(row)}>编辑</Button>
                    <Popconfirm title='确定删除该代理？'
                                description='已被主机使用的代理不能删除；删除前请先在主机里改为其他代理。'
                                onConfirm={() => this.remove(row.id)}>
                        <Button type='link' danger>删除</Button>
                    </Popconfirm>
                </Space>,
            },
        ]

        return <Page padding>
            <Card title='代理管理' className='page-card'
                  extra={<PermActions actions={[
                      {label: '新增代理', perm: 'proxy:save', type: 'primary', onClick: this.create},
                  ]}/>}>
                <div style={{marginBottom: 12}}>
                    <Typography.Text type='secondary'>
                        主机接入时经一台两边都能访问的机器中转 Docker 请求；目前主要使用 HTTP
                        （CONNECT 隧道 + Basic 认证），也支持 SOCKS5。排序越靠前，主机表单下拉里越靠前。
                    </Typography.Text>
                </div>
                <Table rowKey='id' loading={loading} dataSource={rows} columns={columns} pagination={false}/>
            </Card>

            <Modal title={editing ? '编辑代理' : '新增代理'} open={modalOpen} destroyOnHidden width={620}
                   onCancel={() => this.setState({modalOpen: false})}
                   onOk={() => this.formRef.current?.submit()}>
                <Form ref={this.formRef} layout='vertical' onFinish={this.save}
                      initialValues={formValues} preserve={false}>
                    <Form.Item name='id' hidden><Input/></Form.Item>
                    <Form.Item label='名称' name='name'
                               tooltip='仅用于在代理管理和主机表单里识别；留空时用地址:端口'
                               rules={[{max: 100, message: '名称不能超过 100 个字符'}]}>
                        <Input placeholder='留空用地址:端口，如 办公网代理'/>
                    </Form.Item>
                    <Form.Item label='协议' name='type' rules={[{required: true}]}
                               tooltip='HTTP 代理最常见；SOCKS5 需代理端支持用户名密码认证（可选）'>
                        <Select options={PROTOCOLS}/>
                    </Form.Item>
                    <Form.Item label='代理地址' required
                               tooltip='平台与目标主机网络不通时，经这台代理中转。平台跑在容器里时不能填 127.0.0.1，要填宿主机网桥网关或局域网 IP'>
                        <Space.Compact style={{width: '100%'}}>
                            <Form.Item name='host' noStyle
                                       rules={[{required: true, message: '请填写代理地址'}]}>
                                <Input allowClear style={{width: '100%'}}
                                       placeholder='192.168.1.10 或 proxy.example.com'/>
                            </Form.Item>
                            <Form.Item name='port' noStyle
                                       rules={[{required: true, message: '请填写代理端口'}]}>
                                <InputNumber style={{width: 130}} min={1} max={65535} placeholder='端口'/>
                            </Form.Item>
                        </Space.Compact>
                    </Form.Item>
                    <Form.Item label='认证账号' tooltip='没有认证要求时留空'>
                        <Space.Compact style={{width: '100%'}}>
                            <Form.Item name='username' noStyle>
                                <Input allowClear style={{width: '40%'}} placeholder='用户名（可选）'/>
                            </Form.Item>
                            <Form.Item name='password' noStyle>
                                <Input.Password autoComplete='new-password' style={{width: '60%'}}
                                                placeholder={editing ? '密码，留空表示不修改' : '密码（可选）'}/>
                            </Form.Item>
                        </Space.Compact>
                    </Form.Item>
                    <Form.Item label='备注' name='remark'>
                        <Input.TextArea rows={2} maxLength={500} showCount placeholder='用途、归属网络等'/>
                    </Form.Item>
                </Form>
            </Modal>

            <Modal title='测试代理' open={testOpen} destroyOnHidden width={560}
                   onCancel={() => this.setState({testOpen: false})}
                   footer={[
                       <Button key='close' onClick={() => this.setState({testOpen: false})}>关闭</Button>,
                       <Button key='test' type='primary' loading={testing} onClick={this.runTest}>开始测试</Button>,
                   ]}>
                <div style={{marginBottom: 12}}>
                    <Typography.Text strong>{testValues.name || testValues.address}</Typography.Text>
                    <Typography.Text type='secondary' style={{marginLeft: 8}}>
                        {testValues.address}{testValues.username ? '，账号 ' + testValues.username : '，匿名'}
                    </Typography.Text>
                </div>
                <Form layout='vertical'>
                    <Form.Item label='测试目标'
                               tooltip='通过代理 CONNECT 到该目标，能同时验证代理是否可达、认证是否通过、能否转发；留空只测试代理端口能否连上'>
                        <Input value={testTarget} allowClear
                               onChange={e => this.setState({testTarget: e.target.value})}
                               placeholder='如 www.baidu.com:443，留空只测代理端口'/>
                    </Form.Item>
                </Form>
                {testing && <div style={{marginBottom: 12}}><Spin/> <Typography.Text type='secondary'>正在测试…</Typography.Text></div>}
                {testResult &&
                    <Alert type={testResult.ok ? 'success' : 'error'} showIcon
                           message={testResult.ok ? '测试通过' : '测试失败'}
                           description={<div>
                               <div>{testResult.message}</div>
                               <Typography.Text type='secondary'>耗时 {testResult.elapsedMs} ms</Typography.Text>
                           </div>}/>}
            </Modal>
        </Page>
    }
}
