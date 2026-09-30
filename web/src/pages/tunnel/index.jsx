import {Alert, Button, Card, Drawer, Form, Input, InputNumber, message, Modal, Select, Space, Switch, Table, Tag} from 'antd';
import React from 'react';
import {FileTextOutlined} from '@ant-design/icons';
import {FieldRemoteSelect, HttpClient, Page, PermActions} from '@jiangood/open-admin';
import CodeMirrorEditor from '../../components/CodeMirrorEditor';
import LogView from '../../components/LogView';

/**
 * 隧道（nps）：
 * 选一台主机作为 nps 服务端并容器化部署，节点按需下发 npc 客户端，
 * 应用侧的隧道开关决定 nps 上的域名解析记录。
 */
export default class extends React.Component {

    state = {
        loading: true,
        setting: {},
        conf: '',
        confKey: 0,
        logId: null,
        logVisible: false,
        busy: false,

        containers: [],
        containersLoading: false,
        selectedIds: [],

        nodes: [],
        nodesLoading: false,

        probe: null,
        probeLoading: false,
    }

    formRef = React.createRef()

    componentDidMount() {
        this.load()
        this.loadContainers()
        this.loadNodes()
    }

    load = () => {
        HttpClient.get('admin/tunnel/info').then(rs => {
            const data = rs.data || {}
            const setting = data.setting || {}
            this.setState({setting, conf: data.conf || '', loading: false}, () => {
                if (this.formRef.current) {
                    this.formRef.current.setFieldsValue(this.formValues(setting))
                }
            })
        }).catch(() => this.setState({loading: false}))
    }

    /**
     * 后端字段 → 表单字段（主机需要展开成 host.id）。
     */
    formValues = setting => {
        const s = setting || {}
        return {
            ...s,
            host: {id: s.host ? s.host.id : undefined},
            webPassword: '',
            npsConf: undefined,
        }
    }

    loadContainers = () => {
        this.setState({containersLoading: true})
        HttpClient.get('admin/tunnel/containers').then(rs => {
            this.setState({containers: rs.data || [], selectedIds: []})
        }).catch(() => {
        }).finally(() => this.setState({containersLoading: false}))
    }

    loadNodes = () => {
        this.setState({nodesLoading: true})
        HttpClient.get('admin/tunnel/nodes').then(rs => {
            this.setState({nodes: rs.data || []})
        }).catch(() => {
        }).finally(() => this.setState({nodesLoading: false}))
    }

    syncNode = hostId => {
        this.setState({busy: true})
        HttpClient.post('admin/tunnel/syncNode?hostId=' + hostId).then(rs => {
            this.openLog(rs.data)
        }).catch(() => this.setState({busy: false}))
    }

    openLog = logId => {
        if (logId) {
            this.setState({logId, logVisible: true})
        }
    }

    save = values => {
        const hide = message.loading('保存中...', 0)
        HttpClient.post('admin/tunnel/save', values).then(rs => {
            message.success(rs.msg || '保存成功')
            this.load()
        }).finally(hide)
    }

    generateConf = () => {
        const values = this.formRef.current.getFieldsValue()
        HttpClient.post('admin/tunnel/generateConf', values).then(rs => {
            this.formRef.current.setFieldsValue({npsConf: rs.data})
            this.setState(s => ({confKey: s.confKey + 1}))
        })
    }

    deploy = () => {
        this.setState({busy: true})
        HttpClient.post('admin/tunnel/deployNps').then(rs => {
            this.openLog(rs.data)
        }).catch(() => {
            this.setState({busy: false})
        })
    }

    toggle = enabled => {
        const doToggle = () => {
            this.setState({busy: true})
            HttpClient.post('admin/tunnel/toggle?enabled=' + enabled).then(rs => {
                this.openLog(rs.data)
            }).catch(() => {
                this.setState({busy: false})
            })
        }
        if (enabled) {
            doToggle()
            return
        }
        Modal.confirm({
            title: '停用隧道',
            content: '将停止并移除 nps 与全部 npc 容器（数据卷与配置保留，可随时重新启用）。确定继续？',
            okText: '停用',
            cancelText: '取消',
            onOk: doToggle,
        })
    }

    onLogFinished = () => {
        this.setState({busy: false})
        this.load()
        this.loadContainers()
        this.loadNodes()
    }

    probe = () => {
        this.setState({probeLoading: true})
        HttpClient.get('admin/tunnel/probe').then(rs => {
            this.setState({probe: rs.data || {}})
        }).finally(() => this.setState({probeLoading: false}))
    }

    clean = orphansOnly => {
        const ids = orphansOnly
            ? this.state.containers.filter(c => c.orphan).map(c => c.containerId)
            : this.state.selectedIds
        if (!ids || ids.length === 0) {
            message.warning(orphansOnly ? '没有残留容器' : '请先选择容器')
            return
        }
        Modal.confirm({
            title: orphansOnly ? '清理残留容器' : '清理选中容器',
            content: '只删除容器与平台引用，不影响 nps 数据卷与配置。确定继续？',
            okText: '清理',
            cancelText: '取消',
            onOk: () => {
                this.setState({busy: true})
                HttpClient.post('admin/tunnel/clean', ids).then(rs => {
                    this.openLog(rs.data)
                }).catch(() => this.setState({busy: false}))
            },
        })
    }

    nodeColumns = [
        {title: '主机', dataIndex: 'hostName'},
        {title: 'nps 客户端', dataIndex: 'npsClientId'},
        {
            title: '连接', dataIndex: 'connected',
            render: v => <Tag color={v ? 'green' : 'red'}>{v ? '已连接' : '未连接'}</Tag>
        },
        {title: '隧道应用', dataIndex: 'appCount'},
        {title: '状态', dataIndex: 'status'},
        {title: '最后同步', dataIndex: 'lastSyncTime'},
        {
            title: '操作', dataIndex: 'option',
            render: (_, row) => (
                <PermActions>
                    <Button perm='tunnel:deploy' size='small' onClick={() => this.syncNode(row.hostId)}>重新同步</Button>
                </PermActions>
            ),
        },
    ]

    columns = [
        {title: '主机', dataIndex: 'hostName'},
        {title: '容器名', dataIndex: 'name'},
        {
            title: '角色', dataIndex: 'role',
            render: v => <Tag color={v === 'nps' ? 'blue' : 'green'}>{v}</Tag>
        },
        {
            title: '状态', dataIndex: 'state',
            render: (v, row) => <Tag color={v === 'running' ? 'green' : 'red'}>{row.status || v}</Tag>
        },
        {title: '镜像', dataIndex: 'image'},
        {
            title: '平台引用', dataIndex: 'referenced',
            render: (v, row) => v ? '已记录' : <Tag color='orange'>残留</Tag>
        },
    ]

    render() {
        const {setting, conf, logId, logVisible, busy, containers, containersLoading, selectedIds, probe, probeLoading, nodes, nodesLoading} = this.state
        const enabled = !!setting.enabled
        const configured = !!(setting.host && setting.host.id)
        const formProps = {labelCol: {flex: '130px'}, preserve: false}

        return <Page padding>
            <Alert type='info' showIcon style={{marginBottom: 16}}
                   message='nps 服务端建议部署在能直连各节点的公网主机上'
                   description='需要把 *.域名后缀 泛解析到 nps 主机；跨境/受限链路通常只放行 80/443，此时应使用 TLS 桥接端口（默认 443）。'/>

            <Card title='服务端设置' style={{maxWidth: 900}}
                  extra={<Space>
                      {logId ? <Button type='link' size='small' icon={<FileTextOutlined/>}
                                       onClick={() => this.setState({logVisible: true})}>查看日志</Button> : null}
                      <span>总开关</span>
                      <Switch checked={enabled} loading={busy} disabled={!configured}
                              onChange={this.toggle}/>
                  </Space>}>

                {!configured && (
                    <Alert type='warning' showIcon style={{marginBottom: 16}}
                           message='尚未配置 nps 主机，保存后即可开启总开关并部署'/>
                )}

                {setting.npsStatus && (
                    <div style={{marginBottom: 16}}>
                        服务端状态：<Tag color={setting.npsStatus === 'running' ? 'green' : 'red'}>{setting.npsStatus}</Tag>
                        {setting.lastDeployTime ? <span style={{color: '#999'}}>最近部署 {setting.lastDeployTime}</span> : null}
                        {setting.lastError ? <div style={{color: '#cf1322', marginTop: 4}}>{setting.lastError}</div> : null}
                    </div>
                )}

                <Form ref={this.formRef} {...formProps} onFinish={this.save}>

                    <Form.Item label='nps 主机' name={['host', 'id']} rules={[{required: true, message: '请选择 nps 主机'}]}
                               tooltip='nps 服务端容器部署在这台主机上'>
                        <FieldRemoteSelect showSearch url='admin/host/options' placeholder='请选择主机'/>
                    </Form.Item>

                    <Form.Item label='连接地址' name='npsAddr' rules={[{required: true, message: '请填写 npc 连接地址'}]}
                               tooltip='节点（npc）连接的地址，填 nps 主机的公网 IP 或域名'>
                        <Input placeholder='如 1.2.3.4'/>
                    </Form.Item>

                    <Form.Item label='域名后缀' name='subDomainHost' rules={[{required: true, message: '请填写域名后缀'}]}
                               tooltip='应用隧道地址为 子域名.域名后缀，需要把 *.域名后缀 泛解析到 nps 主机'>
                        <Input placeholder='如 tunnel.example.com'/>
                    </Form.Item>

                    <Form.Item label='HTTP 代理端口' name='httpProxyPort' tooltip='nps 对外提供 HTTP 域名路由的端口，通常 80'>
                        <InputNumber min={1} max={65535} style={{width: 200}}/>
                    </Form.Item>

                    <Form.Item label='明文桥接端口' name='bridgePort' tooltip='npc 明文桥接端口（默认 8024）'>
                        <InputNumber min={1} max={65535} style={{width: 200}}/>
                    </Form.Item>

                    <Form.Item label='TLS 桥接端口' name='tlsBridgePort'
                               tooltip='npc TLS 桥接端口（默认 443）。跨境/受限链路建议用 443，伪装成 HTTPS'>
                        <InputNumber min={1} max={65535} style={{width: 200}}/>
                    </Form.Item>

                    <Form.Item label='节点连接方式' name='nodeBridgeMode'
                               tooltip='节点用哪种方式连接 nps；受限链路选 tls'>
                        <Select style={{width: 200}} options={[
                            {label: 'TLS（推荐）', value: 'tls'},
                            {label: '明文', value: 'plain'},
                        ]}/>
                    </Form.Item>

                    <Form.Item label='Web 后台端口' name='webPort'>
                        <InputNumber min={1} max={65535} style={{width: 200}}/>
                    </Form.Item>

                    <Form.Item label='Web 用户名' name='webUsername'>
                        <Input style={{width: 200}}/>
                    </Form.Item>

                    <Form.Item label='Web 密码' name='webPassword'
                               tooltip='留空表示不修改；密码由平台随机生成'>
                        <Input.Password style={{width: 200}} autoComplete='new-password'
                                        placeholder={setting.webPasswordMasked ? '******（留空不修改）' : ''}/>
                    </Form.Item>

                    <Form.Item label='nps 镜像' name='npsImage' tooltip='拉不到 Docker Hub 时可填加速地址'>
                        <Input style={{width: 400}} placeholder='yisier1/nps'/>
                    </Form.Item>

                    <Form.Item label='npc 镜像' name='npcImage'>
                        <Input style={{width: 400}} placeholder='yisier1/npc'/>
                    </Form.Item>

                    <Form.Item label='nps.conf'
                               tooltip='保存以原文为准；密钥以 ****** 展示，留空表示沿用原值'>
                        <Space direction='vertical' style={{width: '100%'}}>
                            <Space>
                                <Button size='small' onClick={this.generateConf}>按表单生成默认配置</Button>
                                <span style={{color: '#999'}}>可直接编辑原文，保存后按此部署</span>
                            </Space>
                            <Form.Item name='npsConf' noStyle>
                                <CodeMirrorEditor key={this.state.confKey}/>
                            </Form.Item>
                        </Space>
                    </Form.Item>

                    <Form.Item label=' '>
                        <Space>
                            <PermActions>
                                <Button perm='tunnel:save' type='primary' htmlType='submit'>保存</Button>
                                <Button perm='tunnel:deploy' type='primary' danger loading={busy}
                                        onClick={this.deploy}>部署 / 重启 nps</Button>
                                <Button perm='tunnel:view' onClick={this.probe} loading={probeLoading}>连通性检测</Button>
                            </PermActions>
                        </Space>
                    </Form.Item>
                </Form>

                {probe && (
                    <Alert type='info' style={{marginBottom: 8}}
                           message={'WebAPI：' + (probe.apiReady ? '可用' : '不可用')}
                           description={<div>
                               {(probe.ports || []).map(p => (
                                   <div key={p.mode}>
                                       桥接 {p.mode} 端口 {p.port}：
                                       <Tag color={p.reachable ? 'green' : 'red'}>{p.reachable ? '可达' : '不可达'}</Tag>
                                       {p.error ? <span style={{color: '#999'}}>{p.error}</span> : null}
                                   </div>
                               ))}
                               {probe.npcConnected !== undefined && probe.npcConnected !== null && (
                                   <div>节点 npc：
                                       <Tag color={probe.npcConnected ? 'green' : 'red'}>
                                           {probe.npcConnected ? '已连接' : '未连接'}
                                       </Tag>
                                   </div>
                               )}
                           </div>}/>
                )}
            </Card>

            <Card title='节点（npc）' className='mt-2' style={{maxWidth: 1100}}
                  extra={<Space>
                      <Button size='small' onClick={this.loadNodes}>刷新</Button>
                  </Space>}>
                <Table rowKey='hostId' size='small' loading={nodesLoading}
                       dataSource={nodes} columns={this.nodeColumns}
                       pagination={false}/>
                <div style={{color: '#999', marginTop: 8}}>
                    只有存在「已开启隧道应用」的节点才会部署 npc 客户端。
                </div>
            </Card>

            <Card title='容器' className='mt-2' style={{maxWidth: 1100}}
                  extra={<Space>
                      <Button size='small' onClick={this.loadContainers}>刷新</Button>
                      <PermActions>
                          <Button perm='tunnel:clean' size='small'
                                  onClick={() => this.clean(false)}>清理选中</Button>
                          <Button perm='tunnel:clean' size='small' danger
                                  onClick={() => this.clean(true)}>清理残留</Button>
                      </PermActions>
                  </Space>}>
                <Table rowKey='containerId' size='small' loading={containersLoading}
                       dataSource={containers} columns={this.columns}
                       pagination={false}
                       rowSelection={{
                           selectedRowKeys: selectedIds,
                           onChange: keys => this.setState({selectedIds: keys}),
                       }}/>
                <div style={{color: '#999', marginTop: 8}}>
                    残留指未被平台记录引用的容器（例如节点已删除、部署中断留下的）。清理只删除容器，不影响数据卷与配置。
                </div>
            </Card>

            <Drawer title='隧道日志' width={860} open={logVisible}
                    mask={{closable: false}}
                    onClose={() => this.setState({logVisible: false})}>
                {logId
                    ? <LogView key={logId} url={'/admin/ws/tunnel-log/' + logId} websocket={true}
                               onClose={this.onLogFinished}/>
                    : null}
            </Drawer>
        </Page>
    }

}
