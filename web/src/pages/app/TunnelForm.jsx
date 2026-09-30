import {Alert, AutoComplete, Button, message, Space, Switch, Tag} from 'antd';
import React from 'react';
import {HttpClient, PermActions} from '@jiangood/open-admin';

/**
 * 应用隧道：开关 + 暴露端口 + 子域名，并展示内网访问地址与隧道访问地址。
 * <p>
 * 端口用 AutoComplete：下拉列出应用已配置的端口，也允许手动输入。
 */
const slug = name => (name || '')
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+/, '')
    .replace(/-+$/, '')
    .slice(0, 63)
    .replace(/-+$/, '');

export default class extends React.Component {

    state = {
        enabled: false,
        subdomain: '',
        port: undefined,
        portOptions: [],
        info: {},
        saving: false,
    }

    componentDidMount() {
        this.init(this.props.app)
        this.loadMeta()
    }

    componentDidUpdate(prevProps) {
        if (prevProps.app !== this.props.app) {
            this.init(this.props.app)
        }
    }

    init = app => {
        const a = app || {}
        this.setState({
            enabled: !!a.tunnelEnabled,
            subdomain: a.tunnelSubdomain || slug(a.name),
            port: a.tunnelPort,
            info: a.tunnelInfo || {},
        })
    }

    loadMeta = () => {
        const app = this.props.app || {}
        HttpClient.get('admin/app/configMeta', {id: app.id}).then(rs => {
            const meta = rs.data || {}
            const mode = (app.config || {}).networkMode || 'bridge'
            const options = (meta.ports || []).map(p => {
                const hostPort = mode === 'host' ? p.privatePort : p.publicPort
                return {
                    value: String(p.privatePort),
                    label: `容器 ${p.privatePort}/${(p.protocol || 'TCP').toLowerCase()}`
                        + (hostPort ? ` → 主机 ${hostPort}` : '（未映射主机端口）'),
                }
            })
            this.setState({portOptions: options})
        }).catch(() => {
        })
    }

    save = () => {
        const {enabled, subdomain, port} = this.state
        if (enabled && !port) {
            message.warning('请选择要暴露的端口')
            return
        }
        this.setState({saving: true})
        const hide = message.loading('保存并同步中...', 0)
        HttpClient.get('admin/app/updateTunnel', {
            id: this.props.app.id,
            enabled,
            subdomain: subdomain || undefined,
            port: port || undefined,
        }).then(rs => {
            message.success(rs.msg || '已保存')
            if (this.props.onChange) {
                this.props.onChange()
            }
        }).finally(() => {
            hide()
            this.setState({saving: false})
        })
    }

    render() {
        const {enabled, subdomain, port, portOptions, info, saving} = this.state
        const config = this.props.app.config || {}
        const mode = config.networkMode || 'bridge'

        return <>
            {mode === 'none' && (
                <Alert type='warning' showIcon style={{marginBottom: 16}}
                       message='该应用网络模式为 none，无法暴露端口'/>
            )}

            <div style={{marginBottom: 16}}>
                <Space>
                    <span>隧道开关</span>
                    <Switch checked={enabled} onChange={v => this.setState({enabled: v})}/>
                    <span style={{color: '#999'}}>开启后经 nps 以「子域名.域名后缀」对外提供 HTTP 访问</span>
                </Space>
            </div>

            <div style={{marginBottom: 16}}>
                <Space align='start'>
                    <span style={{display: 'inline-block', width: 68}}>暴露端口</span>
                    <AutoComplete
                        value={port === undefined || port === null ? '' : String(port)}
                        options={portOptions}
                        style={{width: 320}}
                        placeholder='选择应用端口，或直接填写主机侧端口'
                        onChange={v => this.setState({port: v})}
                        filterOption={(input, option) => String(option.value).includes(input)}
                    />
                    <span style={{color: '#999'}}>
                        {mode === 'bridge'
                            ? '桥接模式：需在【容器配置】里已映射主机端口'
                            : '主机模式：使用容器端口'}
                    </span>
                </Space>
            </div>

            <div style={{marginBottom: 16}}>
                <Space>
                    <span style={{display: 'inline-block', width: 68}}>子域名</span>
                    <AutoComplete
                        value={subdomain}
                        options={[{value: slug(this.props.app.name)}]}
                        style={{width: 320}}
                        placeholder='默认取应用名称'
                        onChange={v => this.setState({subdomain: v})}
                    />
                    <span style={{color: '#999'}}>只能包含小写字母、数字和短横线</span>
                </Space>
            </div>

            {info.error && (
                <Alert type='warning' showIcon style={{marginBottom: 16}} message={info.error}/>
            )}

            <div style={{marginBottom: 16}}>
                <Space direction='vertical'>
                    <div>
                        内网访问地址：{info.innerUrl
                        ? <a href={info.innerUrl} target='_blank' rel='noreferrer'>{info.innerUrl}</a>
                        : <span style={{color: '#999'}}>—</span>}
                    </div>
                    <div>
                        隧道访问地址：{info.tunnelUrl
                        ? <a href={info.tunnelUrl} target='_blank' rel='noreferrer'>{info.tunnelUrl}</a>
                        : <span style={{color: '#999'}}>—</span>}
                        {enabled && <Tag style={{marginLeft: 8}} color='blue'>需把 *.{'{域名后缀}'} 解析到 nps 主机</Tag>}
                    </div>
                </Space>
            </div>

            <PermActions>
                <Button perm='app:tunnel' type='primary' loading={saving} onClick={this.save}>保存隧道配置</Button>
            </PermActions>
        </>
    }

}
