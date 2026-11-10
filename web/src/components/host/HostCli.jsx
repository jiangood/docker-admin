import React from 'react'
import {Alert, Empty} from 'antd'
import {PermUtils} from '@jiangood/open-admin'
import ContainerTerminal from '../container/ContainerTerminal'
import {wsHostCliUrl} from './utils'

const TOKEN_MIN_LENGTH = 8

/**
 * 生成会话令牌。注意 http（非 secure context）下 crypto.randomUUID 不可用，
 * 不能只依赖它，否则内网 http 访问会拿到 undefined。
 */
function newToken() {
    try {
        if (window.crypto && typeof window.crypto.randomUUID === 'function') {
            return window.crypto.randomUUID()
        }
    } catch (e) {
        // ignore
    }
    return `${Math.random().toString(36).slice(2, 10)}${Date.now().toString(36)}`
}

function storageKey(hostId) {
    return `docker-admin-host-cli-session:${hostId}`
}

function refreshToken(hostId) {
    const token = newToken()
    try {
        window.sessionStorage.setItem(storageKey(hostId), token)
    } catch (e) {
        // 隐私模式下 sessionStorage 可能不可用，退化为内存令牌
    }
    return token
}

function loadToken(hostId) {
    try {
        const saved = window.sessionStorage.getItem(storageKey(hostId))
        if (saved && saved.length >= TOKEN_MIN_LENGTH) {
            return saved
        }
    } catch (e) {
        // ignore
    }
    return refreshToken(hostId)
}

/**
 * 主机 Docker CLI：在辅助容器内打开交互式 shell。
 * <p>
 * 辅助容器内已配置 DOCKER_HOST 指向宿主机 docker.sock，所以直接敲 docker / docker compose
 * 命令即可；宿主机目录在容器内保持相同绝对路径。需要 host:cli 权限。
 * <p>
 * shell 由服务端按「用户 + 主机 + 标签页令牌」持久保存：切走页面、刷新、短暂断网后
 * 会自动重连并回放断开期间的输出，长耗时命令不会中断。令牌存在 sessionStorage，
 * 所以刷新 / 返回同一页面能接回原来的 shell，「新建会话」则换一个干净的 shell。
 */
export default class extends React.Component {

    state = {
        status: null,
        deployed: false,
        root: '',
        key: 0,
    }

    terminal = React.createRef()
    /** 会话令牌不放进 state：只影响 URL，避免无意义的整树重渲染。 */
    token = ''

    componentDidMount() {
        this.applyStatus(this.props.status)
    }

    componentDidUpdate(prevProps) {
        if (prevProps.hostId !== this.props.hostId) {
            this.token = ''
        }
        if (prevProps.status !== this.props.status || prevProps.hostId !== this.props.hostId) {
            this.applyStatus(this.props.status)
        }
    }

    currentToken() {
        if (!this.token) {
            this.token = loadToken(this.props.hostId)
        }
        return this.token
    }

    /** 父组件 HostAgentTab 持有 agent 状态，这里只负责根据状态维护终端连接。 */
    applyStatus = status => {
        const root = (status && (status.mountedRoot || status.desiredRoot)) || ''
        const deployed = !!(status && status.deployed)
        const prev = this.state
        const containerChanged = !!(prev.status && status && prev.status.containerId !== status.containerId)
        // 首次部署、重建（容器 ID 变化）或目录变化后旧 shell 已经不存在，换一个会话令牌
        const needNewKey = deployed && (!prev.deployed || containerChanged || prev.root !== root)
        if (needNewKey) {
            this.token = refreshToken(this.props.hostId)
        }
        this.setState({
            status,
            deployed,
            root,
            key: needNewKey ? prev.key + 1 : prev.key,
        })
    }

    /** 重连当前 shell（保留终端内容）。 */
    reconnect = () => {
        if (this.terminal.current) {
            this.terminal.current.reconnect()
        }
    }

    /** 丢弃当前 shell，换一个干净的。 */
    newSession = () => {
        if (this.terminal.current) {
            this.terminal.current.closeSession()
        }
        this.token = refreshToken(this.props.hostId)
        this.setState(state => ({key: state.key + 1}))
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
            <ContainerTerminal key={key}
                               ref={this.terminal}
                               url={wsHostCliUrl(hostId, this.currentToken())}
                               autoReconnect
                               height='100%'/>
        </div>
    }
}
