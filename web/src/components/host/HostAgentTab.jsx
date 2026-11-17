import React from 'react'
import {Alert, Button, Card, Popconfirm, Space, Tooltip} from 'antd'
import {CloudServerOutlined, PlusOutlined, StopOutlined, UploadOutlined} from '@ant-design/icons'
import {HttpClient, PermUtils} from '@jiangood/open-admin'
import AgentCommands from './AgentCommands'
import AgentUpload from './AgentUpload'
import HostAgentAlert from './HostAgentAlert'
import HostCli from './HostCli'

/**
 * 主机 Agent 面板：整块用 Card 承载，标题是「主机名 · Agent」，
 * 容器状态（运行中 / 已停止 / 未部署）连同新建会话、关闭会话、常用命令、
 * 上传文件与「更多」下拉（切换挂载目录 / 更新 / 帮助 / 重新部署）都放在 Card 右上角（extra），
 * 正文只有终端。
 * <p>
 * 会话保持让 shell 不因浏览器断开而中断，但也意味着服务端会一直占着资源；
 * 「关闭会话」（需二次确认）是它的主动出口：立即结束 shell 并停止自动重连，
 * 之后点「新建会话」可再次使用。
 * 常用命令（deployed 时出现）是 {@link AgentCommands} 下拉菜单：点菜单项把命令敲进终端
 * 但不回车，由用户确认后人工执行；命令按主机各自维护（存在 Agent 配置的 commands 字段里，
 * 非全局），「管理命令」打开弹窗增删改。
 * 上传文件（需 host:file 权限且辅助容器运行中）打开 {@link AgentUpload} 弹窗，
 * 在弹窗里逐级选择挂载目录下的目标路径，同名文件不允许覆盖；
 * 下载、查看、编辑、重命名等其余文件操作统一在终端里完成。
 * 删除容器走主机详情「容器」Tab（辅助容器即普通容器）。
 * 顶层只渲染一次 HostAgentAlert，状态、提示与按钮由它通过 render prop 提供，避免重复请求 agent 状态。
 */
export default class extends React.Component {

    state = {
        status: null,
        /** 本主机的常用命令列表（挂载时读取，抽屉里增删改后整体保存并以此刷新）。 */
        commands: [],
    }

    cli = React.createRef()

    upload = React.createRef()

    componentDidMount() {
        const {hostId} = this.props
        if (!hostId) {
            return
        }
        HttpClient.get('admin/host-agent-config/commands', {hostId}, {toastError: false})
            .then(rs => this.setState({commands: rs.data || []}))
            .catch(() => {
            })
    }

    canCli = () => PermUtils.hasPermission('host:cli')

    canUpload = () => PermUtils.hasPermission('host:file')

    newCliSession = () => {
        const cli = this.cli.current
        if (cli) {
            cli.newSession()
        }
    }

    /** 把命令敲进终端（不回车）：终端未连接时 HostCli 链路内部静默忽略。 */
    inputCommand = text => {
        const cli = this.cli.current
        if (cli) {
            cli.input(text)
        }
    }

    /**
     * 整体保存常用命令：后端只动 commands 字段，成功后以返回的清洗结果为准刷新列表。
     * 返回 Promise 供抽屉在保存失败时保持弹窗不关。
     */
    saveCommands = list => {
        const {hostId} = this.props
        return HttpClient.post('admin/host-agent-config/commands/save', list, {hostId})
            .then(rs => {
                const saved = rs.data || list
                this.setState({commands: saved})
                return saved
            })
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
                <Tooltip title='新建会话（结束当前 shell，换一个干净的）'>
                    <Button type='text' icon={<PlusOutlined/>}
                            onClick={this.newCliSession}>新建会话</Button>
                </Tooltip>
                <Popconfirm title='关闭当前终端会话？'
                            description='服务端 shell 与运行中的命令会立即结束并释放资源，之后点「新建会话」可再次使用'
                            okText='关闭会话'
                            okButtonProps={{danger: true}}
                            onConfirm={() => this.cli.current && this.cli.current.endSession()}>
                    <Tooltip title='关闭会话并释放服务端资源（shell 由服务端保持到浏览器之外，不用时可在此手动释放）'>
                        <Button type='text' icon={<StopOutlined/>}>关闭会话</Button>
                    </Tooltip>
                </Popconfirm>
                <AgentCommands commands={this.state.commands}
                               onChange={this.saveCommands}
                               onInput={this.inputCommand}/>
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
