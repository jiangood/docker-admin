import {Button, Col, Descriptions, Divider, Form, Input, Row, Select, Space, Spin, Switch, Tag, Typography} from 'antd';
import React from 'react';
import {HttpClient, Perm} from '@jiangood/open-admin';

/**
 * 应用隧道：在应用详情页显式选择隧道客户端，完整域名 = 域名前缀 + "." + 客户端域名。
 * 目标地址取应用映射到主机侧的端口；客户端由用户自行部署，平台通过其管理 API 下发隧道。
 * 配置是解耦的：只调用隧道接口，不改变应用本身的容器配置。
 */
export default class extends React.Component {

    state = {
        loading: true,
        meta: {},
        enabled: false,
        prefix: '',
        clientId: undefined,
        port: undefined,
        saving: false,
    }

    componentDidMount() {
        this.load()
    }

    /**
     * 拉取隧道元数据。
     * @param preserveSelection 保存后刷新时为 true：只更新「运行信息」等服务端字段，
     *                          保留用户在界面上的客户端 / 前缀 / 端口选择，避免被默认规则重置
     */
    load = (preserveSelection = false) => {
        HttpClient.get('admin/app/tunnelMeta', {id: this.props.app.id}).then(rs => {
            const meta = rs.data || {}
            const prev = this.state
            // 进入页面时应用若尚未选择客户端，默认选中第一个可用的（没有可用的则取第一个），省去手动选择
            const clients = meta.clients || []
            const fallback = clients.find(c => c.configured) || clients[0]
            const clientId = preserveSelection
                ? prev.clientId
                : (meta.clientId != null ? meta.clientId : (fallback ? fallback.id : undefined))
            // 进入页面时应用若尚未选择端口，默认选中第一个 TCP 端口
            const firstTcp = (meta.ports || [])
                .find(p => String(p.protocol || 'TCP').toUpperCase() === 'TCP')
            const port = preserveSelection
                ? prev.port
                : (meta.port != null ? meta.port : (firstTcp ? firstTcp.privatePort : undefined))
            const prefix = preserveSelection ? prev.prefix : (meta.prefix || '')
            this.setState({
                meta,
                enabled: !!meta.enabled,
                prefix,
                clientId,
                port,
                loading: false,
            })
        }).catch(() => this.setState({loading: false}))
    }

    selectedClient = () => {
        const {meta, clientId} = this.state
        return (meta.clients || []).find(c => c.id === clientId)
    }

    fullDomain = () => {
        const {prefix} = this.state
        const client = this.selectedClient()
        if (!client || !client.domain || !prefix) {
            return null
        }
        return prefix + '.' + client.domain
    }

    save = enabled => {
        this.setState({saving: true, enabled})
        HttpClient.post('admin/app/updateTunnel'
            + '?id=' + encodeURIComponent(this.props.app.id)
            + '&enabled=' + (enabled ? 'true' : 'false')
            + '&clientId=' + encodeURIComponent(this.state.clientId || '')
            + '&prefix=' + encodeURIComponent(this.state.prefix || '')
            + '&port=' + encodeURIComponent(this.state.port == null ? '' : this.state.port))
            .then(() => {
                // 隧道不影响容器，不重拉容器；只刷新运行信息并让父级更新应用记录
                // （tunnelEnabled 变了，名称 / 配置 / 迁移 / 删除的拦截提示要跟着更新）
                this.load(true)
                this.props.onChange && this.props.onChange({appOnly: true})
            }).finally(() => this.setState({saving: false}))
    }

    render() {
        const {loading, meta, enabled, prefix, port, saving} = this.state
        if (loading) {
            return <Spin/>
        }

        const clients = meta.clients || []
        const client = this.selectedClient()
        const clientOptions = clients.map(c => ({
            value: c.id,
            label: c.name + (c.domain ? '（' + c.domain + '）' : '（未配置域名）'),
            disabled: !c.configured,
        }))
        const rawPorts = meta.ports || []
        // 下拉只显示主机端口：隧道转发目标是主机侧端口，容器端口对使用者无意义
        const ports = rawPorts.map(p => ({
            label: p.hostPort != null ? String(p.hostPort) : '未映射主机端口',
            value: p.privatePort,
        }))
        const clientDomain = client && client.domain ? client.domain : ''
        const fullDomain = this.fullDomain()
        const canSave = !!client && client.configured && !!client.domain && ports.length > 0
        // 隧道目标地址 = 应用所在主机 IP + 映射到主机侧的端口（后端下发值，便于现场核对）
        const selectedPort = rawPorts.find(p => p.privatePort === port)
        const targetAddr = meta.targetHost && selectedPort && selectedPort.hostPort != null
            ? meta.targetHost + ':' + selectedPort.hostPort : null

        return <>
            {/* 状态条：左侧状态说明，右侧启用开关 */}
            <Row justify='space-between' align='middle' gutter={16}>
                <Col flex='auto' style={{minWidth: 0}}>
                    <Space size={8} wrap>
                        <Tag color={enabled ? 'success' : 'default'}>{enabled ? '已启用' : '未启用'}</Tag>
                        <Typography.Text type='secondary'>
                            通过 http-tunnel 把该应用暴露到公网域名{client ? '（客户端：' + client.name + '）' : ''}
                        </Typography.Text>
                    </Space>
                </Col>
                <Col>
                    <Space>
                        <Typography.Text>开启隧道</Typography.Text>
                        <Switch checked={enabled} loading={saving}
                                onChange={v => this.save(v)}
                                disabled={!enabled && !canSave}/>
                    </Space>
                </Col>
            </Row>

            <Divider style={{margin: '16px 0'}}/>

            {/* 配置项：始终可编辑，保存后按当前开关状态下发 */}
            <Form layout='vertical' style={{maxWidth: 520}}>
                <Form.Item label='隧道客户端'
                           tooltip='选择一个已登记的客户端，平台通过它的管理 API 下发隧道'>
                    <Select value={this.state.clientId} options={clientOptions}
                            placeholder='选择隧道客户端'
                            onChange={v => this.setState({clientId: v})}/>
                </Form.Item>

                <Form.Item label='域名前缀' tooltip='完整域名 = 前缀 + "." + 客户端域名'>
                    <Input value={prefix} placeholder='默认取应用名称'
                           addonAfter={'.' + (clientDomain || '域名')}
                           onChange={e => this.setState({prefix: e.target.value})}/>
                </Form.Item>

                <Form.Item label='应用端口' tooltip='应用映射到主机侧、由客户端转发访问的端口'>
                    <Select value={port} options={ports}
                            placeholder='选择要暴露的端口'
                            onChange={v => this.setState({port: v})}/>
                </Form.Item>

                <Form.Item style={{marginBottom: 0}}>
                    <Perm code='app:tunnel'>
                        <Button type='primary' loading={saving}
                                disabled={!canSave}
                                onClick={() => this.save(true)}>
                            {enabled ? '保存' : '保存并启用'}
                        </Button>
                    </Perm>
                </Form.Item>
            </Form>

            <Divider style={{margin: '16px 0'}}/>

            {/* 运行信息 */}
            <Descriptions size='small' column={2}>
                <Descriptions.Item label='运行主机'>{meta.hostName || '-'}</Descriptions.Item>
                <Descriptions.Item label='隧道客户端'>{client ? client.name : '-'}</Descriptions.Item>
                <Descriptions.Item label='完整域名'>{enabled ? (fullDomain || '-') : '-'}</Descriptions.Item>
                <Descriptions.Item label='目标地址'>{targetAddr || '-'}</Descriptions.Item>
                <Descriptions.Item label='访问地址' span={2}>
                    {enabled && meta.url ?
                        <a href={meta.url} target='_blank' rel='noreferrer'>{meta.url}</a> : '-'}
                </Descriptions.Item>
            </Descriptions>
        </>
    }
}
