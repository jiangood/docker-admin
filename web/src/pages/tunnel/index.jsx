import {
    Alert, Button, Card, Descriptions, Divider, Drawer, Form, Input, InputNumber,
    message, Modal, Select, Space, Switch, Table, Tabs, Tag
} from 'antd';
import React from 'react';
import {CopyOutlined, FileTextOutlined, PlusOutlined, ReloadOutlined} from '@ant-design/icons';
import {HttpClient, Page, PermActions, PermUtils} from '@jiangood/open-admin';
import LogView from '../../components/LogView';

/**
 * 复制文本：优先 Clipboard API，非 https 环境回退到 textarea + execCommand。
 */
function copyText(text) {
    if (!text) {
        return
    }
    const done = () => message.success('已复制')
    const fallback = () => {
        try {
            const ta = document.createElement('textarea')
            ta.value = text
            ta.style.position = 'fixed'
            ta.style.opacity = '0'
            document.body.appendChild(ta)
            ta.select()
            document.execCommand('copy')
            ta.remove()
            done()
        } catch (e) {
            message.error('复制失败，请手动选择内容复制')
        }
    }
    if (navigator.clipboard && window.isSecureContext) {
        navigator.clipboard.writeText(text).then(done).catch(fallback)
        return
    }
    fallback()
}

/**
 * 隧道日志：等宽展示 + 一键复制。
 */
function ConfigBlock({text}) {
    if (!text) {
        return null
    }
    return <div>
        <div style={{display: 'flex', justifyContent: 'flex-end', marginBottom: 8}}>
            <Button size='small' icon={<CopyOutlined/>} onClick={() => copyText(text)}>复制</Button>
        </div>
        <pre style={{
            margin: 0, padding: 12, background: '#f6f8fa', border: '1px solid #eaeef2', borderRadius: 6,
            maxHeight: 360, overflow: 'auto', fontSize: 12, lineHeight: 1.7,
            whiteSpace: 'pre-wrap', wordBreak: 'break-word',
        }}>{text}</pre>
    </div>
}

/**
 * 隧道（frp）：frps 服务端与各节点的 frpc 客户端都由平台部署。
 * 新增 / 修改 / 删除隧道会自动重新生成 frpc 配置并重建对应节点的 frpc 容器。
 */
export default class extends React.Component {

    state = {
        loading: true,
        setting: {},
        frpsConf: '',
        frpsToml: '',
        frpsCommand: '',
        activeTab: 'server',

        nodes: [],
        nodesLoading: false,

        tunnels: [],
        tunnelsLoading: false,

        hostOptions: [],

        logId: null,
        logVisible: false,

        nodeModal: false,
        nodeEditing: null,
        nodeSaving: false,

        tunnelModal: false,
        tunnelEditing: null,
        tunnelSaving: false,
        appOptions: [],
        appMeta: null,
    }

    formRef = React.createRef()
    nodeFormRef = React.createRef()
    tunnelFormRef = React.createRef()
    frpcDeployFormRef = React.createRef()

    componentDidMount() {
        this.load()
        this.loadNodes()
        this.loadTunnels()
        this.loadHosts()
    }

    refreshAll = () => {
        this.load()
        this.loadNodes()
        this.loadTunnels()
    }

    load = () => {
        HttpClient.get('admin/tunnel/info').then(rs => {
            const data = rs.data || {}
            const setting = data.setting || {}
            this.setState({
                setting,
                frpsConf: data.frpsConf || '',
                frpsToml: data.frpsToml || '',
                frpsCommand: data.frpsCommand || '',
                loading: false,
            })
            if (this.formRef.current) {
                this.formRef.current.setFieldsValue(this.formValues(setting))
            }
            if (this.frpcDeployFormRef.current) {
                this.frpcDeployFormRef.current.setFieldsValue(this.frpcDeployFormValues(setting))
            }
        }).catch(() => this.setState({loading: false}))
    }

    formValues = setting => {
        const s = setting || {}
        return {
            frpsAddr: s.frpsAddr,
            bindPort: s.bindPort,
            vhostHttpPort: s.vhostHttpPort,
            subDomainHost: s.subDomainHost,
            transportTls: s.transportTls === undefined ? true : s.transportTls,
            authToken: '',
        }
    }

    frpcDeployFormValues = setting => {
        const s = setting || {}
        return {
            frpcImage: s.frpcImage,
        }
    }

    loadNodes = () => {
        this.setState({nodesLoading: true})
        HttpClient.get('admin/tunnel/nodes').then(rs => {
            this.setState({nodes: rs.data || []})
        }).catch(() => {
        }).finally(() => this.setState({nodesLoading: false}))
    }

    loadTunnels = () => {
        this.setState({tunnelsLoading: true})
        HttpClient.get('admin/tunnel/tunnels').then(rs => {
            this.setState({tunnels: rs.data || []})
        }).catch(() => {
        }).finally(() => this.setState({tunnelsLoading: false}))
    }

    loadHosts = () => {
        HttpClient.get('admin/host/options').then(rs => {
            this.setState({hostOptions: rs.data || []})
        }).catch(() => {
        })
    }

    /**
     * 执行一个异步部署动作：打开实时日志，关闭日志后刷新页面数据。
     */
    run = promise => {
        promise.then(rs => {
            message.success((rs && rs.msg) || '已提交')
            const logId = rs && rs.data
            if (logId) {
                this.setState({logId, logVisible: true})
            } else {
                this.refreshAll()
            }
        })
    }

    onLogClose = () => {
        this.setState({logVisible: false}, () => this.refreshAll())
    }

    /**
     * 日志流关闭表示后台部署已结束（成功或失败）：只刷新数据，抽屉保持打开，
     * 由用户确认日志内容后手动关闭（失败时尤其需要看到报错）。
     */
    onLogFinished = () => {
        this.refreshAll()
    }

    save = values => {
        const hide = message.loading('保存中...', 0)
        HttpClient.post('admin/tunnel/save', values).then(rs => {
            message.success(rs.msg || '保存成功')
            this.load()
        }).finally(hide)
    }

    saveDeployFrpc = values => {
        const hide = message.loading('保存中...', 0)
        HttpClient.post('admin/tunnel/saveDeployFrpc', values).then(rs => {
            message.success(rs.msg || '保存成功')
            this.load()
        }).finally(hide)
    }

    // ------------------------------------------------------------------ 部署动作

    rebuildAllFrpc = () => {
        Modal.confirm({
            title: '重建全部 frpc',
            content: '按最新设置重新生成每个节点上的 frpc.toml 并重建容器。确定继续？',
            okText: '重建',
            cancelText: '取消',
            onOk: () => this.run(HttpClient.post('admin/tunnel/rebuildAllFrpc')),
        })
    }

    deployFrpc = row => {
        this.run(HttpClient.post('admin/tunnel/deployFrpc?nodeId=' + encodeURIComponent(row.id)))
    }

    removeFrpc = row => {
        Modal.confirm({
            title: '移除 frpc',
            content: '将停止并删除节点 ' + row.name + ' 上的 frpc 容器（隧道记录保留）。确定继续？',
            okText: '移除',
            okButtonProps: {danger: true},
            cancelText: '取消',
            onOk: () => this.run(HttpClient.post('admin/tunnel/removeFrpc?nodeId=' + encodeURIComponent(row.id))),
        })
    }

    // ------------------------------------------------------------------ 节点

    openNodeModal = row => {
        const editing = row || null
        this.setState({nodeModal: true, nodeEditing: editing}, () => {
            this.nodeFormRef.current && this.nodeFormRef.current.setFieldsValue({
                name: editing ? editing.name : '',
                hostId: editing ? editing.hostId : undefined,
                remark: editing ? editing.remark : '',
            })
        })
    }

    saveNode = () => {
        this.nodeFormRef.current.validateFields().then(values => {
            const editing = this.state.nodeEditing
            this.setState({nodeSaving: true})
            HttpClient.post('admin/tunnel/saveNode', {
                id: editing ? editing.id : undefined,
                name: values.name,
                host: {id: values.hostId},
                remark: values.remark,
            }).then(rs => {
                message.success(rs.msg || '保存成功')
                this.setState({nodeModal: false})
                this.loadNodes()
            }).finally(() => this.setState({nodeSaving: false}))
        })
    }

    deleteNode = row => {
        Modal.confirm({
            title: '删除节点',
            content: '将移除节点 ' + row.name + ' 上的 frpc 容器，并删除该节点及其全部隧道（' + (row.tunnelCount || 0) + ' 条）。确定继续？',
            okText: '删除',
            okButtonProps: {danger: true},
            cancelText: '取消',
            onOk: () => this.run(HttpClient.post('admin/tunnel/deleteNode?id=' + encodeURIComponent(row.id))),
        })
    }

    // ------------------------------------------------------------------ 隧道

    loadAppOptions = () => {
        if (this.state.appOptions.length > 0) {
            return
        }
        HttpClient.get('admin/app/options').then(rs => {
            this.setState({appOptions: (rs.data || []).map(o => ({label: o.label, value: o.value}))})
        }).catch(() => {
        })
    }

    openTunnelModal = row => {
        this.loadAppOptions()
        const editing = row || null
        this.setState({tunnelModal: true, tunnelEditing: editing, appMeta: null}, () => {
            const form = this.tunnelFormRef.current
            if (!form) {
                return
            }
            if (editing) {
                form.setFieldsValue({
                    subdomain: editing.subdomain,
                    port: editing.localPort,
                    remark: editing.remark,
                })
            } else {
                form.setFieldsValue({nodeId: undefined, appId: undefined, port: undefined, subdomain: '', remark: ''})
            }
        })
    }

    onTunnelAppChange = appId => {
        if (!appId) {
            this.setState({appMeta: null})
            return
        }
        HttpClient.get('admin/tunnel/appMeta', {id: appId}).then(rs => {
            const meta = rs.data || {}
            this.setState({appMeta: meta})
            const form = this.tunnelFormRef.current
            if (form) {
                const ports = meta.ports || []
                form.setFieldsValue({
                    subdomain: meta.defaultSubdomain || '',
                    port: ports.length === 1 ? ports[0].privatePort : undefined,
                    remark: meta.name || '',
                })
            }
        }).catch(() => this.setState({appMeta: null}))
    }

    saveTunnel = () => {
        this.tunnelFormRef.current.validateFields().then(values => {
            const editing = this.state.tunnelEditing
            this.setState({tunnelSaving: true})
            const done = rs => {
                message.success(rs.msg || '已保存')
                this.setState({tunnelModal: false})
                const logId = rs.data
                if (logId) {
                    this.setState({logId, logVisible: true})
                } else {
                    this.refreshAll()
                }
            }
            const req = editing
                ? HttpClient.post('admin/tunnel/editTunnel'
                    + '?id=' + encodeURIComponent(editing.id)
                    + '&subdomain=' + encodeURIComponent(values.subdomain || '')
                    + '&port=' + encodeURIComponent(values.port)
                    + '&remark=' + encodeURIComponent(values.remark || ''))
                : HttpClient.post('admin/tunnel/addTunnel'
                    + '?nodeId=' + encodeURIComponent(values.nodeId)
                    + '&appId=' + encodeURIComponent(values.appId)
                    + '&port=' + encodeURIComponent(values.port)
                    + '&subdomain=' + encodeURIComponent(values.subdomain || '')
                    + '&remark=' + encodeURIComponent(values.remark || ''))
            req.then(done).finally(() => this.setState({tunnelSaving: false}))
        })
    }

    deleteTunnel = row => {
        Modal.confirm({
            title: '删除隧道',
            content: '将删除隧道 ' + row.name + '（' + (row.url || '') + '），并重建所属节点的 frpc。确定继续？',
            okText: '删除',
            okButtonProps: {danger: true},
            cancelText: '取消',
            onOk: () => this.run(HttpClient.post('admin/tunnel/deleteTunnel?id=' + encodeURIComponent(row.id))),
        })
    }

    // ------------------------------------------------------------------ 渲染

    renderState = state => {
        if (state === 'running') {
            return <Tag color='green'>运行中</Tag>
        }
        if (state) {
            return <Tag color='orange'>已停止</Tag>
        }
        return <Tag>未部署</Tag>
    }

    renderOverview = setting => (
        <Card title='隧道服务' style={{maxWidth: 1100}}>
            <Descriptions size='small' column={2} style={{maxWidth: 900}}>
                <Descriptions.Item label='连接地址'>{setting.frpsAddr || '-'}</Descriptions.Item>
                <Descriptions.Item label='域名后缀'>{setting.subDomainHost || '-'}</Descriptions.Item>
                <Descriptions.Item label='绑定端口'>{setting.bindPort || 7000}</Descriptions.Item>
                <Descriptions.Item label='HTTP 端口'>{setting.vhostHttpPort || 80}</Descriptions.Item>
            </Descriptions>
        </Card>
    )

    /**
     * 连接地址是域名时，自动把域名后缀填成同一个值（通常是同一台机的域名）。
     */
    onServerValuesChange = changed => {
        if (changed.frpsAddr === undefined) {
            return
        }
        const form = this.formRef.current
        if (!form) {
            return
        }
        const addr = (changed.frpsAddr || '').trim()
        const suffix = (form.getFieldValue('subDomainHost') || '').trim()
        const isIp = /^\d{1,3}(\.\d{1,3}){3}$/.test(addr)
        if (!suffix && addr && !isIp) {
            form.setFieldsValue({subDomainHost: addr})
        }
    }

    renderServer = (formProps, setting) => (
        <Form ref={this.formRef} {...formProps} onFinish={this.save}
              onValuesChange={this.onServerValuesChange} style={{maxWidth: 900}}>
            <Divider titlePlacement='start' style={{marginTop: 0}}>隧道行为配置</Divider>

            <Form.Item label='连接地址' name='frpsAddr' rules={[{required: true, message: '请填写连接地址'}]}
                       tooltip='各节点 frpc 连接该地址，也是访客访问隧道的入口：填 frps 主机的公网 IP 或域名'>
                <Input style={{width: 360}} placeholder='如 1.2.3.4 或 tunnel.example.com'/>
            </Form.Item>

            <Form.Item label='域名后缀' name='subDomainHost' rules={[{required: true, message: '请填写域名后缀'}]}
                       tooltip='隧道访问地址为 子域名.域名后缀，需要把 *.域名后缀 泛解析到 frps 主机'>
                <Input style={{width: 360}} placeholder='填了域名连接地址会自动带上，如 tunnel.example.com'/>
            </Form.Item>

            <Divider titlePlacement='start'>端口与安全</Divider>

            <Form.Item label='鉴权 token' name='authToken'
                       tooltip='frps 与 frpc 共用；留空表示不修改，首次保存自动生成'>
                <Input.Password style={{width: 360}} autoComplete='new-password'
                                placeholder={setting.authTokenMasked ? '******（留空不修改）' : '留空自动生成'}/>
            </Form.Item>

            <Form.Item label='绑定端口' name='bindPort'
                       tooltip='frpc 连接 frps 的端口，frps.toml 的 bindPort，默认 7000'>
                <InputNumber min={1} max={65535} style={{width: 360}} placeholder='7000'/>
            </Form.Item>

            <Form.Item label='HTTP 端口' name='vhostHttpPort'
                       tooltip='frps 对外提供 HTTP 域名路由的端口，通常 80'>
                <InputNumber min={1} max={65535} style={{width: 360}} placeholder='80'/>
            </Form.Item>

            <Form.Item label='传输加密' name='transportTls' valuePropName='checked'
                       tooltip='frpc 用 TLS 连接 frps（transport.tls.enable），跨境 / 受限链路建议开启'>
                <Switch/>
            </Form.Item>

            <Divider titlePlacement='start'>操作</Divider>

            <Form.Item label=' '>
                <PermActions>
                    <Button perm='tunnel:save' type='primary' htmlType='submit'>保存</Button>
                </PermActions>
            </Form.Item>

            <Alert type='info' showIcon style={{marginBottom: 16}}
                   message='这里只保存配置，不下发容器'
                   description={'保存后到【部署 frps】页签复制 docker 命令，在服务端主机上手动部署 frps；'
                       + '各节点的 frpc 容器由【部署 frpc】页签下发。'}/>

            <Divider titlePlacement='start'>frps.toml（预览，token 已掩码）</Divider>
            <ConfigBlock text={this.state.frpsConf}/>
        </Form>
    )

    nodeColumns = [
        {title: '节点名称', dataIndex: 'name'},
        {title: '主机', dataIndex: 'hostName', render: v => v || '-'},
        {title: '隧道数', dataIndex: 'tunnelCount', width: 90},
        {title: '备注', dataIndex: 'remark', render: v => v || '-'},
        {
            title: '操作', dataIndex: 'option',
            render: (_, row) => (
                <PermActions>
                    <Button perm='tunnel:save' size='small' type='link'
                            onClick={() => this.openNodeModal(row)}>编辑</Button>
                    <Button perm='tunnel:save' size='small' type='link' danger
                            onClick={() => this.deleteNode(row)}>删除</Button>
                </PermActions>
            ),
        },
    ]

    deployNodeColumns = [
        {title: '节点名称', dataIndex: 'name'},
        {title: '主机', dataIndex: 'hostName', render: v => v || '-'},
        {title: 'frpc 状态', dataIndex: 'state', render: v => this.renderState(v)},
        {title: '隧道数', dataIndex: 'tunnelCount', width: 90},
        {title: '最后部署', dataIndex: 'lastDeployTime', render: v => v || '-'},
        {
            title: '操作', dataIndex: 'option',
            render: (_, row) => (
                <PermActions>
                    <Button perm='tunnel:save' size='small' type='link'
                            onClick={() => this.deployFrpc(row)}>部署</Button>
                    <Button perm='tunnel:save' size='small' type='link'
                            onClick={() => this.removeFrpc(row)}>移除</Button>
                </PermActions>
            ),
        },
    ]

    renderNodes = (nodes, nodesLoading) => (
        <div>
            <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8}}>
                <span style={{color: '#999'}}>
                    每个节点 = 一台主机上的一个 frpc 容器；这里只维护节点配置，部署到容器请到【部署 frpc】页签。
                </span>
                <Space>
                    <Button size='small' icon={<ReloadOutlined/>} onClick={this.loadNodes}>刷新</Button>
                    <Button perm='tunnel:save' size='small' type='primary' icon={<PlusOutlined/>}
                            onClick={() => this.openNodeModal(null)}>新增节点</Button>
                </Space>
            </div>

            <Table rowKey='id' size='small' loading={nodesLoading}
                   dataSource={nodes} columns={this.nodeColumns}
                   pagination={false}/>

            <div style={{color: '#999', marginTop: 8}}>
                删除节点会连同该节点上的 frpc 容器一起清理；新增节点后需到【部署 frpc】页签下发。
            </div>
        </div>
    )

    renderDeployFrps = setting => {
        const {frpsToml, frpsCommand} = this.state
        return (
            <div>
                <Alert type='info' showIcon style={{marginBottom: 16}}
                       message='frps（服务端）由你在主机上手动部署'
                       description={'平台只按【服务端】的连接配置生成 frps.toml 与 docker 命令，不在主机上下发容器。'
                           + '把配置保存到 frps 主机的 /etc/frp/frps.toml，再执行部署命令即可。'}/>

                {!frpsCommand ? (
                    <Alert type='warning' showIcon style={{maxWidth: 900}}
                           message='请先在【服务端】填写并保存连接地址与域名后缀'/>
                ) : <>
                    <Divider titlePlacement='start' style={{marginTop: 0}}>1. frps.toml（含真实 token）</Divider>
                    <Alert type='warning' showIcon style={{marginBottom: 8, maxWidth: 900}}
                           message='配置含真实鉴权 token，请勿泄露；保存到主机的 /etc/frp/frps.toml'/>
                    <ConfigBlock text={frpsToml}/>

                    <Divider titlePlacement='start'>2. docker 部署命令</Divider>
                    <ConfigBlock text={frpsCommand}/>

                    <div style={{color: '#999', marginTop: 8}}>
                        需要放行的端口：{setting.bindPort || 7000}（绑定）、{setting.vhostHttpPort || 80}（HTTP 域名）；
                        并把 *.{setting.subDomainHost || '域名后缀'} 泛解析到 frps 主机。
                    </div>
                </>}
            </div>
        )
    }

    renderDeployFrpc = (nodes, nodesLoading) => (
        <div>
            <Alert type='info' showIcon style={{marginBottom: 16}}
                   message='部署 frpc（节点客户端）是可选动作：配置保存后由你手动下发到容器'
                   description={'平台按【服务端】的连接配置与【隧道】列表生成各节点的 frpc.toml 并按主机 / 镜像部署容器；'
                       + '你也可以按这些配置在主机上自行部署。'}/>

            <Form ref={this.frpcDeployFormRef} labelCol={{flex: '120px'}} preserve={false}
                  onFinish={this.saveDeployFrpc} style={{maxWidth: 900}}>
                <Divider titlePlacement='start' style={{marginTop: 0}}>部署参数</Divider>

                <Form.Item label='frpc 镜像' name='frpcImage' tooltip='客户端镜像，默认 ghcr.io/jiangood/frpc'>
                    <Input style={{width: 360}} placeholder='ghcr.io/jiangood/frpc'/>
                </Form.Item>

                <Form.Item label=' '>
                    <PermActions>
                        <Button perm='tunnel:save' type='primary' htmlType='submit'>保存部署设置</Button>
                    </PermActions>
                </Form.Item>
            </Form>

            <Divider titlePlacement='start'>节点 frpc</Divider>

            <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8}}>
                <span style={{color: '#999'}}>每个节点 = 一台主机上的一个 frpc 容器</span>
                <Space>
                    <Button size='small' icon={<ReloadOutlined/>} onClick={this.loadNodes}>刷新</Button>
                    <Button perm='tunnel:save' size='small' onClick={this.rebuildAllFrpc}>重建全部 frpc</Button>
                </Space>
            </div>

            <Table rowKey='id' size='small' loading={nodesLoading}
                   dataSource={nodes} columns={this.deployNodeColumns}
                   pagination={false}/>

            <div style={{color: '#999', marginTop: 8}}>
                改了 token / 连接地址 / 端口后，用【重建全部 frpc】统一生效；
                单个节点可单独【部署】或【移除】。
            </div>
        </div>
    )

    tunnelColumns = [
        {title: '名称', dataIndex: 'name'},
        {
            title: '访问地址', dataIndex: 'url',
            render: v => v ? <a href={v} target='_blank' rel='noreferrer'>{v}</a> : '-'
        },
        {title: '节点', dataIndex: 'nodeName', render: v => v || '-'},
        {title: '应用', dataIndex: 'appName', render: v => v || '-'},
        {
            title: '目标', dataIndex: 'localPort',
            render: (v, row) => (row.localIp || '-') + ':' + (v || '-')
        },
        {title: '备注', dataIndex: 'remark', render: v => v || '-'},
        {
            title: '操作', dataIndex: 'option',
            render: (_, row) => (
                <PermActions>
                    <Button perm='tunnel:route' size='small' type='link'
                            onClick={() => this.openTunnelModal(row)}>编辑</Button>
                    <Button perm='tunnel:route' size='small' type='link' danger
                            onClick={() => this.deleteTunnel(row)}>删除</Button>
                </PermActions>
            ),
        },
    ]

    renderTunnels = (tunnels, tunnelsLoading, nodes) => (
        <div>
            <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8}}>
                <span style={{color: '#999'}}>
                    一条隧道 = 子域名 → 应用主机端口；增删改后会自动重建所属节点的 frpc。
                </span>
                <Space>
                    <Button size='small' icon={<ReloadOutlined/>} onClick={this.loadTunnels}>刷新</Button>
                    <Button perm='tunnel:route' size='small' type='primary' icon={<PlusOutlined/>}
                            disabled={nodes.length === 0}
                            onClick={() => this.openTunnelModal(null)}>新增隧道</Button>
                </Space>
            </div>

            {nodes.length === 0 && (
                <Alert type='warning' showIcon style={{marginBottom: 8}}
                       message='请先在【节点（frpc）】里新增一个节点'/>
            )}

            <Table rowKey='id' size='small' loading={tunnelsLoading}
                   dataSource={tunnels} columns={this.tunnelColumns}
                   pagination={false}/>

            <div style={{color: '#999', marginTop: 8}}>
                目标地址取应用所在主机的地址与端口，需保证该节点上的 frpc 能访问到；
                新增前请把 *.域名后缀 泛解析到 frps 主机。
            </div>
        </div>
    )

    render() {
        const {
            setting, activeTab, nodes, nodesLoading, tunnels, tunnelsLoading,
            logId, logVisible, nodeModal, nodeSaving, nodeEditing,
            tunnelModal, tunnelSaving, tunnelEditing, appMeta, hostOptions,
        } = this.state
        const formProps = {labelCol: {flex: '120px'}, preserve: false}
        const editing = !!tunnelEditing

        const items = [
            {
                key: 'server',
                label: '服务端（frps）',
                // 表单必须常驻，否则切换 Tab 会卸载字段
                forceRender: true,
                children: this.renderServer(formProps, setting),
            },
            {
                key: 'nodes',
                label: '节点（frpc）',
                children: this.renderNodes(nodes, nodesLoading),
            },
            {
                key: 'tunnels',
                label: '隧道',
                children: this.renderTunnels(tunnels, tunnelsLoading, nodes),
            },
            {
                key: 'deployFrps',
                label: '部署 frps',
                children: this.renderDeployFrps(setting),
            },
            {
                key: 'deployFrpc',
                label: '部署 frpc',
                forceRender: true,
                children: this.renderDeployFrpc(nodes, nodesLoading),
            },
        ]

        return <Page padding>
            {this.renderOverview(setting)}

            <Card className='mt-2' style={{maxWidth: 1100}}>
                <Tabs items={items} activeKey={activeTab} onChange={key => this.setState({activeTab: key})}/>
            </Card>

            <Modal title={nodeEditing ? '编辑节点' : '新增节点'}
                   open={nodeModal} destroyOnHidden
                   confirmLoading={nodeSaving}
                   onOk={this.saveNode}
                   onCancel={() => this.setState({nodeModal: false})}>
                <Form ref={this.nodeFormRef} labelCol={{flex: '90px'}} preserve={false}>
                    <Form.Item label='节点名称' name='name' rules={[{required: true, message: '请填写节点名称'}]}>
                        <Input placeholder='如 办公局域网'/>
                    </Form.Item>
                    <Form.Item label='主机' name='hostId' rules={[{required: true, message: '请选择主机'}]}
                               tooltip='frpc 容器部署在这台主机上，需能访问 frps 与隧道目标端口'>
                        <Select options={hostOptions} showSearch optionFilterProp='label'
                                placeholder='选择主机'/>
                    </Form.Item>
                    <Form.Item label='备注' name='remark'>
                        <Input placeholder='如 内网 192.168.1.0/24'/>
                    </Form.Item>
                </Form>
            </Modal>

            <Modal title={editing ? '编辑隧道' : '新增隧道'}
                   open={tunnelModal} destroyOnHidden width={620}
                   confirmLoading={tunnelSaving}
                   onOk={this.saveTunnel}
                   onCancel={() => this.setState({tunnelModal: false})}>
                <Form ref={this.tunnelFormRef} labelCol={{flex: '90px'}} preserve={false}
                      onValuesChange={changed => {
                          if (changed.appId !== undefined) {
                              this.onTunnelAppChange(changed.appId)
                          }
                      }}>
                    {editing ? <>
                        <Form.Item label='应用'>
                            <Input readOnly value={(tunnelEditing && tunnelEditing.appName) || '-'}/>
                        </Form.Item>
                        <Form.Item label='子域名' name='subdomain'
                                   rules={[{required: true, message: '请填写子域名'}]}
                                   extra={'访问地址：' + ('<子域名>.' + (setting.subDomainHost || '域名后缀'))}>
                            <Input placeholder='只能包含小写字母、数字和短横线'/>
                        </Form.Item>
                        <Form.Item label='端口' name='port' rules={[{required: true, message: '请选择端口'}]}
                                   help={tunnelEditing && tunnelEditing.localIp
                                       ? '目标地址：' + tunnelEditing.localIp
                                       : null}>
                            <InputNumber style={{width: 200}} min={1} max={65535}/>
                        </Form.Item>
                    </> : <>
                        <Form.Item label='节点' name='nodeId' rules={[{required: true, message: '请选择节点'}]}>
                            <Select options={nodes.map(n => ({label: n.name, value: n.id}))}
                                    placeholder='选择提供隧道的节点'/>
                        </Form.Item>
                        <Form.Item label='应用' name='appId' rules={[{required: true, message: '请选择应用'}]}>
                            <Select showSearch optionFilterProp='label' placeholder='选择要暴露的应用'
                                    options={this.state.appOptions}/>
                        </Form.Item>
                        <Form.Item label='端口' name='port' rules={[{required: true, message: '请选择端口'}]}
                                   help={appMeta && appMeta.hostAddress
                                       ? '目标地址：' + appMeta.hostAddress + '（' + (appMeta.hostName || '-') + '）'
                                       : null}>
                            <Select placeholder='选择要暴露的端口'
                                    options={((appMeta || {}).ports || []).map(p => ({
                                        label: p.label, value: p.privatePort,
                                    }))}/>
                        </Form.Item>
                        <Form.Item label='子域名' name='subdomain'
                                   rules={[{required: true, message: '请填写子域名'}]}
                                   extra={'访问地址：' + ('<子域名>.' + (setting.subDomainHost || '域名后缀'))}>
                            <Input placeholder='默认取应用名称'/>
                        </Form.Item>
                    </>}

                    <Form.Item label='备注' name='remark'>
                        <Input placeholder='默认取应用名称'/>
                    </Form.Item>
                </Form>
            </Modal>

            <Drawer title='隧道部署日志' width={860} open={logVisible}
                    mask={{closable: false}}
                    onClose={this.onLogClose}>
                {logId
                    ? <LogView key={logId} url={'/admin/ws/tunnel-log/' + logId} websocket={true}
                               onClose={this.onLogFinished}/>
                    : null}
            </Drawer>
        </Page>
    }

}
