import {Button, Descriptions, Form, Input, message, Select, Space, Spin, Switch} from 'antd';
import React from 'react';
import {HttpClient, PermActions} from '@jiangood/open-admin';

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

    load = () => {
        HttpClient.get('admin/app/tunnelMeta', {id: this.props.app.id}).then(rs => {
            const meta = rs.data || {}
            this.setState({
                meta,
                enabled: !!meta.enabled,
                prefix: meta.prefix || '',
                clientId: meta.clientId,
                port: meta.port,
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
            .then(rs => {
                message.success(rs.msg || '已保存')
                this.load()
                this.props.onChange && this.props.onChange()
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
        const ports = (meta.ports || []).map(p => ({label: p.label, value: p.privatePort}))
        const fullDomain = this.fullDomain()
        const canEnable = !!client && client.configured && !!client.domain && ports.length > 0

        return <>
            <Form colon={false} labelCol={{flex: '100px'}} style={{maxWidth: 720}}>
                <Form.Item label='隧道客户端'
                           tooltip='选择一个已登记的客户端，平台通过它的管理 API 下发隧道'>
                    <Select style={{width: 360}} value={this.state.clientId} options={clientOptions}
                            placeholder='选择隧道客户端'
                            onChange={v => this.setState({clientId: v})}
                            disabled={enabled}/>
                </Form.Item>

                <Form.Item label='开启隧道'>
                    <Space>
                        <Switch checked={enabled} loading={saving}
                                onChange={v => this.save(v)}
                                disabled={!enabled && !canEnable}/>
                        <span style={{color: '#999'}}>
                            通过 http-tunnel 把该应用暴露到公网域名（客户端：{client ? client.name : '未选择'}）
                        </span>
                    </Space>
                </Form.Item>

                <Form.Item label='域名前缀' tooltip={'完整域名 = 前缀 + "." + 客户端域名'}>
                    <Input style={{width: 240}} value={prefix} placeholder='默认取应用名称'
                           onChange={e => this.setState({prefix: e.target.value})}
                           disabled={!enabled}/>
                    <span style={{marginLeft: 8, color: '#999'}}>.{client && client.domain ? client.domain : '域名'}</span>
                </Form.Item>

                <Form.Item label='应用端口' tooltip='应用映射到主机侧、由客户端转发访问的端口'>
                    <Select style={{width: 280}} value={port} options={ports}
                            placeholder='选择要暴露的端口' disabled={!enabled}
                            onChange={v => this.setState({port: v})}/>
                </Form.Item>

                <Form.Item label=' '>
                    <PermActions>
                        <Button perm='app:tunnel' type='primary' loading={saving}
                                disabled={!enabled || !canEnable}
                                onClick={() => this.save(true)}>保存</Button>
                    </PermActions>
                </Form.Item>
            </Form>

            <Descriptions size='small' column={1} style={{maxWidth: 720}}>
                <Descriptions.Item label='运行主机'>{meta.hostName || '-'}</Descriptions.Item>
                <Descriptions.Item label='隧道客户端'>{client ? client.name : '-'}</Descriptions.Item>
                <Descriptions.Item label='完整域名'>{enabled ? (fullDomain || '-') : '-'}</Descriptions.Item>
                <Descriptions.Item label='访问地址'>
                    {enabled && meta.url ? <a href={meta.url} target='_blank' rel='noreferrer'>{meta.url}</a> : '-'}
                </Descriptions.Item>
            </Descriptions>
        </>
    }
}
