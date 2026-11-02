import React from 'react'
import {Alert, Button, Empty, Space, Typography} from 'antd'
import {ReloadOutlined} from '@ant-design/icons'
import {PermUtils} from '@jiangood/open-admin'
import ContainerTerminal from '../container/ContainerTerminal'
import {wsHostCliUrl} from './utils'

/**
 * 主机 Docker CLI：在辅助容器内打开交互式 shell。
 * <p>
 * 辅助容器内已配置 DOCKER_HOST 指向宿主机 docker.sock，所以直接敲 docker / docker compose
 * 命令即可；宿主机目录在容器内保持相同绝对路径。需要 host:cli 权限。
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

    render() {
        const {hostId} = this.props
        const {deployed, root, key} = this.state
        const height = this.props.height || 520

        if (!PermUtils.hasPermission('host:cli')) {
            return <Alert type='warning' showIcon title='缺少 host:cli 权限，无法打开主机命令行'/>
        }

        if (!deployed) {
            return <div style={{height, display: 'flex', alignItems: 'center', justifyContent: 'center'}}>
                <Empty description='辅助容器未部署，请先在右上角部署'/>
            </div>
        }

        return <div style={{height, display: 'flex', flexDirection: 'column'}}>
            <Space className='mb-2'>
                <Button size='small' icon={<ReloadOutlined/>} onClick={this.reconnect}>重新连接</Button>
                <Typography.Text type='secondary'>
                    容器：docker-admin-agent，当前目录：{root || '-'}
                </Typography.Text>
            </Space>

            <div style={{flex: 1, minHeight: 200}}>
                <ContainerTerminal key={key} url={wsHostCliUrl(hostId)} height='100%'/>
            </div>
        </div>
    }
}
