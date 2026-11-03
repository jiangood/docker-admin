import React from 'react'
import {Alert, Button, Empty, Tooltip} from 'antd'
import {ReloadOutlined} from '@ant-design/icons'
import {PermUtils} from '@jiangood/open-admin'
import HostAgentAlert from './HostAgentAlert'
import HostFileActions from './HostFileActions'
import HostCli from './HostCli'

/**
 * 主机详情的「Agent」Tab：终端占满整个页面，上传 / 下载按钮放在右上角状态条里。
 * <p>
 * 不再渲染文件树，查看、编辑、删除、重命名、新建目录等操作统一走终端；
 * 顶层只渲染一次 HostAgentAlert，避免重复请求 agent 状态。
 * 整页只有状态条一行工具栏，重新连接也作为图标按钮放在这一行。
 */
export default class extends React.Component {

    state = {
        status: null,
    }

    cli = React.createRef()

    canFile = () => PermUtils.hasPermission('host:file')

    canCli = () => PermUtils.hasPermission('host:cli')

    reconnectCli = () => {
        const cli = this.cli.current
        if (cli) {
            cli.reconnect()
        }
    }

    onAgentChange = status => {
        this.setState({status})
        if (this.props.onStatus) {
            this.props.onStatus(status)
        }
    }

    render() {
        const {hostId} = this.props
        const {status} = this.state
        const canFile = this.canFile()
        const canCli = this.canCli()

        if (!canFile && !canCli) {
            return <Alert type='warning' showIcon
                          title='缺少 host:file / host:cli 权限，无法使用主机 Agent'/>
        }

        const height = this.props.height || 680
        const deployed = !!(status && status.deployed)
        const fileActions = canFile
            ? <HostFileActions hostId={hostId} status={status}/>
            : null
        const reconnect = canCli && deployed
            ? <Tooltip title='重新连接终端'>
                <Button type='text' size='small' shape='circle' icon={<ReloadOutlined/>}
                        onClick={this.reconnectCli}/>
            </Tooltip>
            : null

        const body = canCli
            ? <HostCli ref={this.cli} hostId={hostId} status={status} height={height}/>
            : <div style={{height, display: 'flex', alignItems: 'center', justifyContent: 'center'}}>
                <Empty description='缺少 host:cli 权限，只能使用右上角的上传 / 下载'/>
            </div>

        return <>
            <HostAgentAlert hostId={hostId} onChange={this.onAgentChange}
                            extra={<>{reconnect}{fileActions}</>}/>
            {body}
        </>
    }
}
