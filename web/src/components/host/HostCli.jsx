import React from 'react'
import {Alert, Empty, notification, Typography} from 'antd'
import {PermUtils} from '@jiangood/open-admin'
import ContainerTerminal from '../container/ContainerTerminal'
import {wsHostCliUrl} from './utils'

/**
 * 主机 Docker CLI：在辅助容器内打开交互式 shell。
 * <p>
 * 辅助容器内已配置 DOCKER_HOST 指向宿主机 docker.sock，所以直接敲 docker / docker compose
 * 命令即可；宿主机目录在容器内保持相同绝对路径。需要 host:cli 权限。
 * 终端占满剩余高度，页面上不再有单独的工具行：重新连接由 HostAgentTab 放进状态条。
 */
export default class extends React.Component {

    state = {
        status: null,
        deployed: false,
        root: '',
        key: 0,
    }

    componentDidMount() {
        this.applyStatus(this.props.status)
    }

    componentDidUpdate(prevProps) {
        if (prevProps.status !== this.props.status) {
            this.applyStatus(this.props.status)
        }
    }

    /** 父组件 HostAgentTab 持有 agent 状态，这里只负责根据状态维护终端连接。 */
    applyStatus = status => {
        const root = (status && (status.mountedRoot || status.desiredRoot)) || ''
        const deployed = !!(status && status.deployed)
        this.setState(state => {
            const containerChanged = !!(state.status && status
                && state.status.containerId !== status.containerId)
            return {
                status,
                deployed,
                root,
                // 首次部署、重建（容器 ID 变化）或目录变化后重新建立 WebSocket
                key: (deployed && (!state.deployed || containerChanged || state.root !== root))
                    ? state.key + 1
                    : state.key,
            }
        })
    }

    reconnect = () => this.setState(state => ({key: state.key + 1}))

    /** 进入容器的说明：改为右下角通知，不再写进终端回滚区。 */
    notifyEnter = () => {
        const {root} = this.state
        notification.info({
            key: 'host-cli-agent',
            placement: 'bottomRight',
            duration: 8,
            message: '已进入主机辅助容器',
            description: <div style={{fontSize: 12, lineHeight: 1.8}}>
                <div>目录 <Typography.Text code>{root || '-'}</Typography.Text> 在容器内保持相同路径</div>
                <div>docker / docker compose 已指向宿主机 daemon</div>
                <div>终端权限等同于宿主机 root，请谨慎操作</div>
            </div>,
        })
    }

    render() {
        const {hostId} = this.props
        const {deployed, key} = this.state
        const height = this.props.height || 520

        if (!PermUtils.hasPermission('host:cli')) {
            return <Alert type='warning' showIcon title='缺少 host:cli 权限，无法打开主机命令行'/>
        }

        if (!deployed) {
            return <div style={{height, display: 'flex', alignItems: 'center', justifyContent: 'center'}}>
                <Empty description='辅助容器未部署，请先在右上角部署'/>
            </div>
        }

        return <div style={{height}}>
            <ContainerTerminal key={key} url={wsHostCliUrl(hostId)} height='100%'
                              onOpen={this.notifyEnter}/>
        </div>
    }
}
