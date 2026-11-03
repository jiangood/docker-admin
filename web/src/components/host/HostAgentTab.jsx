import React from 'react'
import {Alert, Button, Card, Empty, Space, Tooltip} from 'antd'
import {CloudServerOutlined, ReloadOutlined} from '@ant-design/icons'
import {PermUtils} from '@jiangood/open-admin'
import HostAgentAlert from './HostAgentAlert'
import HostFileActions from './HostFileActions'
import HostCli from './HostCli'

/**
 * 主机 Agent 面板：整块用 Card 承载，标题是「主机名 · Agent + 容器状态」，
 * 上传 / 下载、重新连接、切换挂载目录、部署 / 删除与帮助都放在 Card 右上角（extra），正文只有终端。
 * <p>
 * 不再渲染文件树，查看、编辑、删除、重命名、新建目录等操作统一走终端；
 * 顶层只渲染一次 HostAgentAlert，状态、提示与按钮由它通过 render prop 提供，避免重复请求 agent 状态。
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

    renderTitle = (summary, host) => <Space size={8} align='center' wrap>
        <CloudServerOutlined/>
        <span>{host ? host.name : '主机'} · Agent</span>
        {summary}
    </Space>

    render() {
        const {hostId, host} = this.props
        const {status} = this.state
        const canFile = this.canFile()
        const canCli = this.canCli()
        const height = this.props.height || 680

        if (!canFile && !canCli) {
            return <Card title={this.renderTitle(null, host)}>
                <Alert type='warning' showIcon
                       title='缺少 host:file / host:cli 权限，无法使用主机 Agent'/>
            </Card>
        }

        const deployed = !!(status && status.deployed)
        const fileActions = canFile
            ? <HostFileActions hostId={hostId} status={status}/>
            : null
        const reconnect = canCli && deployed
            ? <Tooltip title='重新连接终端'>
                <Button type='text' shape='circle' icon={<ReloadOutlined/>}
                        onClick={this.reconnectCli}/>
            </Tooltip>
            : null

        const body = canCli
            ? <HostCli ref={this.cli} hostId={hostId} status={status} height={height}/>
            : <div style={{height, display: 'flex', alignItems: 'center', justifyContent: 'center'}}>
                <Empty description='缺少 host:cli 权限，只能使用右上角的上传 / 下载'/>
            </div>

        return <HostAgentAlert hostId={hostId} onChange={this.onAgentChange}
                               extra={<>{fileActions}{reconnect}</>}>
            {({summary, notice, actions}) => <Card title={this.renderTitle(summary, host)}
                                                  extra={<Space size={8} wrap>{actions}</Space>}
                                                  styles={{body: {padding: 12}}}>
                {notice}
                {body}
            </Card>}
        </HostAgentAlert>
    }
}
