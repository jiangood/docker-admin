import React from 'react'
import {Alert, Button, Card, Space, Tooltip} from 'antd'
import {CloudServerOutlined, PlusOutlined, ReloadOutlined, UploadOutlined} from '@ant-design/icons'
import {PermUtils} from '@jiangood/open-admin'
import AgentUpload from './AgentUpload'
import HostAgentAlert from './HostAgentAlert'
import HostCli from './HostCli'

/**
 * 主机 Agent 面板：整块用 Card 承载，标题是「主机名 · Agent」，
 * 容器状态（运行中 / 已停止 / 未部署）连同重新连接、上传文件、切换挂载目录、部署与帮助都放在
 * Card 右上角（extra），正文只有终端。
 * <p>
 * 上传文件（需 host:file 权限且辅助容器运行中）打开 {@link AgentUpload} 弹窗，
 * 在弹窗里逐级选择挂载目录下的目标路径，同名文件不允许覆盖；
 * 下载、查看、编辑、重命名等其余文件操作统一在终端里完成。
 * 删除容器走主机详情「容器」Tab（辅助容器即普通容器）。
 * 顶层只渲染一次 HostAgentAlert，状态、提示与按钮由它通过 render prop 提供，避免重复请求 agent 状态。
 */
export default class extends React.Component {

    state = {
        status: null,
    }

    cli = React.createRef()

    upload = React.createRef()

    canCli = () => PermUtils.hasPermission('host:cli')

    canUpload = () => PermUtils.hasPermission('host:file')

    reconnectCli = () => {
        const cli = this.cli.current
        if (cli) {
            cli.reconnect()
        }
    }

    newCliSession = () => {
        const cli = this.cli.current
        if (cli) {
            cli.newSession()
        }
    }

    onAgentChange = status => {
        this.setState({status})
        if (this.props.onStatus) {
            this.props.onStatus(status)
        }
    }

    renderTitle = host => <Space size={8} align='center' wrap>
        <CloudServerOutlined/>
        <span>{host ? host.name : '主机'} · Agent</span>
    </Space>

    render() {
        const {hostId, host} = this.props
        const {status} = this.state
        const canCli = this.canCli()
        const height = this.props.height || 680

        if (!canCli) {
            return <Card title={this.renderTitle(host)}>
                <Alert type='warning' showIcon
                       title='缺少 host:cli 权限，无法使用主机 Agent'/>
            </Card>
        }

        const deployed = !!(status && status.deployed)
        const running = !!(status && status.running)
        const uploadable = this.canUpload() && deployed && running

        const extra = <Space size={0}>
            {deployed && <>
                <Tooltip title='重新连接终端（保留当前 shell 与历史）'>
                    <Button type='text' icon={<ReloadOutlined/>}
                            onClick={this.reconnectCli}>重新连接</Button>
                </Tooltip>
                <Tooltip title='新建会话（结束当前 shell，换一个干净的）'>
                    <Button type='text' icon={<PlusOutlined/>}
                            onClick={this.newCliSession}>新建会话</Button>
                </Tooltip>
            </>}
            {uploadable &&
                <Tooltip title='上传文件到挂载目录（在弹窗里选择目标路径，同名文件不允许覆盖）'>
                    <Button type='text' icon={<UploadOutlined/>}
                            onClick={() => this.upload.current.open()}>上传文件</Button>
                </Tooltip>}
        </Space>

        const body = <HostCli ref={this.cli} hostId={hostId} status={status} height={height}/>

        return <HostAgentAlert hostId={hostId} onChange={this.onAgentChange}
                               defaultTag={this.props.defaultTag}
                               defaultRoot={this.props.defaultRoot}
                               onConfigChange={this.props.onConfigChange}
                               extra={extra}>
            {({summary, notice, actions}) => <Card title={this.renderTitle(host)}
                                                  extra={<Space size={8} align='center' wrap>
                                                      {summary}
                                                      {actions}
                                                  </Space>}
                                                  styles={{body: {padding: 12}}}>
                {notice}
                {body}
                <AgentUpload ref={this.upload} hostId={hostId}
                             mountedRoot={status && status.mountedRoot}/>
            </Card>}
        </HostAgentAlert>
    }
}
