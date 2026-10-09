import {Alert, Button, Card, Form, Input, message, Modal, Space, Table, Tabs, Tag, Tooltip, Typography} from 'antd';
import {PlusOutlined, ReloadOutlined} from '@ant-design/icons';
import React from 'react';
import {HttpClient, Page, PermActions} from '@jiangood/open-admin';

/**
 * 隧道（http-tunnel）：平台作为 http-tunnel 客户端管理 API 的前端，
 * 登记客户端（名称 + 令牌 + API 地址 + 域名）并只读展示隧道（域名 → 目标地址）。
 * 隧道由应用详情页的「隧道」标签创建与删除，应用在那里显式选择客户端。
 * 客户端进程由用户自行部署（http-tunnel client ... --api-port），平台不做部署、不生成配置。
 */
export default class extends React.Component {

    state = {
        activeTab: 'clients',

        clients: [],
        clientsLoading: false,
        testingId: null,

        tunnels: [],
        tunnelsLoading: false,

        clientModal: false,
        clientEditing: null,
        clientFormValues: {},
        clientSaving: false,
    }

    clientFormRef = React.createRef()

    componentDidMount() {
        this.loadClients()
        this.loadTunnels()
    }

    refreshAll = () => {
        this.loadClients()
        this.loadTunnels()
    }

    loadClients = () => {
        this.setState({clientsLoading: true})
        HttpClient.get('admin/tunnel/clients').then(rs => {
            this.setState({clients: rs.data || []})
        }).catch(() => {
        }).finally(() => this.setState({clientsLoading: false}))
    }

    loadTunnels = () => {
        this.setState({tunnelsLoading: true})
        HttpClient.get('admin/tunnel/tunnels').then(rs => {
            this.setState({tunnels: rs.data || []})
        }).catch(() => {
        }).finally(() => this.setState({tunnelsLoading: false}))
    }

    // ------------------------------------------------------------------ 客户端

    openClientModal = row => {
        const editing = row || null
        // 通过 initialValues 回填：Modal 使用 destroyOnHidden，表单会随弹窗销毁/重建，
        // 在 setState 回调里 setFieldsValue 时表单尚未挂载，数据会丢失。
        this.setState({
            clientModal: true,
            clientEditing: editing,
            clientFormValues: editing ? {
                name: editing.name,
                apiUrl: editing.apiUrl,
                domain: editing.domain,
                remark: editing.remark,
            } : {},
        })
    }

    saveClient = () => {
        this.clientFormRef.current.validateFields().then(values => {
            const editing = this.state.clientEditing
            this.setState({clientSaving: true})
            HttpClient.post('admin/tunnel/saveClient', {
                id: editing && editing.id ? editing.id : undefined,
                name: values.name,
                apiUrl: values.apiUrl,
                domain: values.domain,
                token: values.token,
                remark: values.remark,
            }).then(rs => {
                message.success(rs.msg || '保存成功')
                this.setState({clientModal: false})
                this.refreshAll()
            }).finally(() => this.setState({clientSaving: false}))
        })
    }

    testClient = row => {
        this.setState({testingId: row.id})
        HttpClient.get('admin/tunnel/testClient', {id: row.id})
            .then(rs => message.success(rs.msg || '连接成功'))
            .finally(() => this.setState({testingId: null}))
    }

    deleteClient = row => {
        Modal.confirm({
            title: '删除客户端',
            content: '将尽力删除该客户端上的全部隧道，并关闭引用它的应用隧道；确定删除客户端 ' + row.name + ' 的本地登记？',
            okText: '删除',
            okButtonProps: {danger: true},
            cancelText: '取消',
            onOk: () => HttpClient.post('admin/tunnel/deleteClient?id=' + encodeURIComponent(row.id)).then(() => {
                message.success('已删除')
                this.refreshAll()
            }),
        })
    }

    renderClientStatus = row => {
        if (!row.configured) {
            return <Tag>未配置</Tag>
        }
        if (row.connected === true) {
            return <Tag color='green'>在线</Tag>
        }
        if (row.connected === false) {
            return <Tag color='red'>未连接</Tag>
        }
        return (
            <Tooltip title={row.statusError || '无法连接客户端 API'}>
                <Tag color='orange'>不可达</Tag>
            </Tooltip>
        )
    }

    clientColumns = [
        {title: '客户端名称', dataIndex: 'name'},
        {title: 'API 地址', dataIndex: 'apiUrl', render: v => v || '-'},
        {title: '域名', dataIndex: 'domain', render: v => v || '-'},
        {title: '状态', dataIndex: 'connected', width: 100, render: (_, row) => this.renderClientStatus(row)},
        {title: '隧道数', dataIndex: 'tunnelCount', width: 90, render: v => v == null ? '-' : v},
        {title: '令牌', dataIndex: 'tokenMasked', width: 90, render: v => v || '-'},
        {title: '备注', dataIndex: 'remark', render: v => v || '-'},
        {
            title: '操作', dataIndex: 'option',
            render: (_, row) => (
                <PermActions>
                    <Button perm='tunnel:save' size='small' type='link'
                            loading={this.state.testingId === row.id} disabled={!row.configured}
                            onClick={() => this.testClient(row)}>测试连接</Button>
                    <Button perm='tunnel:save' size='small' type='link'
                            onClick={() => this.openClientModal(row)}>编辑</Button>
                    <Button perm='tunnel:save' size='small' type='link' danger
                            onClick={() => this.deleteClient(row)}>删除</Button>
                </PermActions>
            ),
        },
    ]

    renderClients = () => {
        const {clients, clientsLoading} = this.state
        return (
            <div>
                <div className='mb-2' style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center'}}>
                    <Typography.Text type='secondary'>
                        一个客户端 = 一台业务主机上运行的 http-tunnel client 进程，名称、令牌与 API 地址需与之匹配。
                    </Typography.Text>
                    <Space>
                        <Button size='small' icon={<ReloadOutlined/>} onClick={this.loadClients}>刷新</Button>
                        <Button perm='tunnel:save' size='small' type='primary' icon={<PlusOutlined/>}
                                onClick={() => this.openClientModal(null)}>新增客户端</Button>
                    </Space>
                </div>

                <Table rowKey={row => row.id || row.name} size='small' loading={clientsLoading}
                       dataSource={clients} columns={this.clientColumns}
                       pagination={false}/>

                <Typography.Text type='secondary' className='mt-2' style={{display: 'block'}}>
                    客户端需以 <code>--api-port</code> 启动管理 API；API 地址形如 http://主机:2336，令牌与客户端 <code>--token</code> 一致。
                </Typography.Text>
            </div>
        )
    }

    tunnelColumns = [
        {
            title: '访问地址', dataIndex: 'url',
            render: v => v ? <a href={v} target='_blank' rel='noreferrer'>{v}</a> : '-'
        },
        {title: '域名', dataIndex: 'domain'},
        {title: '客户端', dataIndex: 'client', render: v => v || '-'},
        {title: '目标地址', dataIndex: 'localAddr', render: v => v || '-'},
    ]

    renderTunnels = () => {
        const {tunnels, tunnelsLoading} = this.state
        return (
            <div>
                <div className='mb-2' style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center'}}>
                    <Typography.Text type='secondary'>
                        隧道由应用详情页的「隧道」标签创建与删除，这里仅查看。
                    </Typography.Text>
                    <Button size='small' icon={<ReloadOutlined/>} onClick={this.loadTunnels}>刷新</Button>
                </div>

                <Table rowKey={row => row.client + '|' + row.domain} size='small' loading={tunnelsLoading}
                       dataSource={tunnels} columns={this.tunnelColumns}
                       pagination={false}/>

                <Typography.Text type='secondary' className='mt-2' style={{display: 'block'}}>
                    域名需解析到隧道服务端 IP；服务端按 Host 头路由，重名域名会被拒绝。
                </Typography.Text>
            </div>
        )
    }

    render() {
        const {activeTab, clientModal, clientSaving, clientEditing, clientFormValues} = this.state

        const items = [
            {key: 'clients', label: '客户端', forceRender: true, children: this.renderClients()},
            {key: 'tunnels', label: '隧道', children: this.renderTunnels()},
        ]

        return <Page padding>
            <Card className='page-card'>
                <Alert type='info' showIcon className='mb-3'
                       title='服务端与客户端都由你自行部署'
                       description={'平台只登记客户端并通过其管理 API 维护隧道；改动会由客户端转发给服务端，'
                           + '验证后落盘 server.toml 并推送给在线客户端。'}/>
                <Tabs items={items} activeKey={activeTab} onChange={key => this.setState({activeTab: key})}/>
            </Card>

            <Modal title={clientEditing ? '编辑客户端' : '新增客户端'}
                   open={clientModal} destroyOnHidden
                   confirmLoading={clientSaving}
                   onOk={this.saveClient}
                   onCancel={() => this.setState({clientModal: false})}>
                <Form ref={this.clientFormRef} labelCol={{flex: '90px'}}
                      initialValues={clientFormValues} preserve={false}>
                    <Form.Item label='名称' name='name' rules={[{required: true, message: '请填写客户端名称'}]}>
                        <Input disabled={!!clientEditing} placeholder='如 home，需与客户端 --name 一致'/>
                    </Form.Item>
                    <Form.Item label='API 地址' name='apiUrl'
                               rules={[{required: true, message: '请填写客户端 API 地址'}]}
                               tooltip='客户端管理 API 地址，对应启动命令的 --api-port，如 http://192.168.1.10:2336'>
                        <Input placeholder='http://192.168.1.10:2336'/>
                    </Form.Item>
                    <Form.Item label='令牌' name='token'
                               rules={clientEditing ? [] : [{required: true, message: '请填写客户端令牌'}]}
                               tooltip='需与客户端 --token 一致，同时也用于客户端管理 API 认证；留空时新建自动生成、编辑则沿用原令牌'>
                        <Input.Password autoComplete='new-password'
                                        placeholder={clientEditing && clientEditing.hasToken ? '******（留空不修改）'
                                            : '留空自动生成'}/>
                    </Form.Item>
                    <Form.Item label='域名' name='domain'
                               tooltip='应用完整域名 = 域名前缀 + "." + 该域名，如 example.com'>
                        <Input allowClear placeholder='如 example.com'/>
                    </Form.Item>
                    <Form.Item label='备注' name='remark'>
                        <Input placeholder='如 内网 192.168.1.0/24'/>
                    </Form.Item>
                </Form>
            </Modal>
        </Page>
    }

}
