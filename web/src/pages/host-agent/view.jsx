import React from 'react'
import {Alert, Result, Skeleton} from 'antd'
import {HttpClient, Page, PageUtils} from '@jiangood/open-admin'
import HostAgentTab from '../../components/host/HostAgentTab'

export const pageMeta = {
    title: '主机 Agent',
}

/**
 * 工具 - 主机 Agent 的终端页：通过 {@code ?id=<配置id>} 打开。
 * <p>
 * 配置只决定默认主机 / 镜像 tag / 挂载目录；状态、部署、切换目录仍由
 * {@link HostAgentTab} 里的 Agent 面板负责，部署成功后会把实际参数回写到配置。
 */
export default class extends React.Component {

    state = {
        id: null,
        config: null,
        host: null,
        loading: true,
        error: null,
    }

    componentDidMount() {
        const {id} = PageUtils.currentParams()
        if (!id) {
            this.setState({loading: false, error: '缺少 Agent 配置 id'})
            return
        }
        this.setState({id})
        this.load(id)
    }

    load = id => {
        this.setState({loading: true, error: null})
        HttpClient.get('admin/host-agent-config/info', {id}, {toastError: false})
            .then(rs => {
                const config = rs.data
                this.setState({config, loading: false})
                this.loadHost(config.hostId)
            })
            .catch(e => this.setState({loading: false, error: e.message || '读取 Agent 配置失败'}))
    }

    /** 部署 / 切换目录后配置被后端回写，静默刷新一次，页面不闪骨架屏 */
    reloadConfig = () => {
        const {id} = this.state
        if (!id) {
            return
        }
        HttpClient.get('admin/host-agent-config/info', {id}, {toastError: false})
            .then(rs => this.setState({config: rs.data}))
            .catch(() => {
            })
    }

    /** 主机名只用于卡片标题，失败不影响终端连接 */
    loadHost = hostId => {
        HttpClient.get('admin/host/get', {id: hostId}, {toastError: false})
            .then(rs => this.setState({host: rs.data}))
            .catch(() => {
            })
    }

    render() {
        const {id, config, host, loading, error} = this.state

        if (loading) {
            return <Page padding><Skeleton active paragraph={{rows: 4}}/></Page>
        }

        if (error) {
            return <Page padding>
                <Result status='warning' title={error}/>
            </Page>
        }

        return <Page padding>
            {config && !config.hostId &&
                <Alert className='mb-3' type='error' showIcon title='Agent 配置缺少主机'/>}
            <HostAgentTab hostId={config.hostId} host={host}
                          defaultTag={config.imageTag}
                          defaultRoot={config.fileRoot}
                          onConfigChange={this.reloadConfig}
                          height='calc(100vh - 200px)'/>
        </Page>
    }

}
