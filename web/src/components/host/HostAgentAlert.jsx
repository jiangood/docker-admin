import React from 'react'
import {Alert, Badge, Button, Dropdown, Input, Modal, Space, Spin, Typography, message} from 'antd'
import {CloudServerOutlined, FolderOpenOutlined, MoreOutlined, QuestionCircleOutlined, ReloadOutlined, SyncOutlined} from '@ant-design/icons'
import {HttpClient, PermUtils} from '@jiangood/open-admin'
import TaskLogDrawer from '../TaskLogDrawer'

/**
 * Agent 容器状态与操作：把「状态」「提示（异常）」「按钮」通过 render prop 交给外层 Card，
 * 自己只负责取状态、弹窗和请求。
 * <p>
 * 明面上只留主操作（未部署时的「部署辅助容器」、配置不一致时的「重新部署」，均 primary）
 * 与一个「更多」下拉（低频操作：切换挂载目录 / 检查更新 / 帮助），保持工具栏整洁。
 * 目录决定终端里的挂载基准，所以切换入口也在这一层；镜像、目录、权限等说明统一收进问号弹窗。
 * 不提供「删除 Agent」入口：辅助容器就是普通容器，需要删除时走主机详情的「容器」Tab。
 * 用法：<HostAgentAlert hostId={id} onChange={fn}>{
 *     ({summary, notice, actions}) => <Card title={summary} extra={actions}>…</Card>
 * }</HostAgentAlert>
 */
export default class extends React.Component {

    state = {
        status: null,
        loading: true,
        error: null,
        deployOpen: false,
        helpOpen: false,
        rootOpen: false,
        rootInput: '',
        rootSubmitting: false,
        updateOpen: false,
        updating: false,
        check: null,
        tag: 'latest',
        submitting: false,
        logId: null,
        logVisible: false,
    }

    componentDidMount() {
        this.load()
    }

    componentDidUpdate(prevProps) {
        if (prevProps.hostId !== this.props.hostId
            || prevProps.defaultTag !== this.props.defaultTag
            || prevProps.defaultRoot !== this.props.defaultRoot) {
            this.load()
        }
    }

    load = () => {
        const {hostId, defaultTag, defaultRoot} = this.props
        if (!hostId) {
            return
        }
        this.setState({loading: true, error: null})
        HttpClient.get('admin/host/agent/status', {id: hostId, tag: defaultTag, root: defaultRoot}, {toastError: false})
            .then(rs => {
                const status = rs.data || {}
                this.setState({status, loading: false})
                this.notify(status)
            })
            .catch(e => this.setState({loading: false, error: e.message || '读取辅助容器状态失败'}))
    }

    notify = status => {
        if (this.props.onChange) {
            this.props.onChange(status)
        }
    }

    /** 部署 / 切换目录后配置被后端回写，通知外层重新读取配置，避免期望值停留在旧值 */
    notifyConfigChange = () => {
        if (this.props.onConfigChange) {
            this.props.onConfigChange()
        }
    }

    openDeploy = () => {
        const {status} = this.state
        const tag = this.props.defaultTag || (status && status.tag) || 'latest'
        this.setState({deployOpen: true, tag})
    }

    closeDeploy = () => this.setState({deployOpen: false})

    deploy = () => {
        const {hostId, defaultRoot} = this.props
        const {tag} = this.state
        const tagValue = (tag || '').trim() || 'latest'
        if (!/^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$/.test(tagValue)) {
            message.warning('镜像 Tag 只允许字母、数字、下划线、点和短横线，例如 latest、v4.8.40')
            return
        }
        this.setState({submitting: true})
        HttpClient.post('admin/host/agent/deploy', null,
            {id: hostId, tag: tagValue, root: defaultRoot})
            .then(rs => {
                // 部署是异步任务：接口返回 logId，弹窗原地切换为实时日志
                message.success((rs && rs.message) || '部署已开始')
                this.setState({deployOpen: false})
                this.notifyConfigChange()
                this.openLog(rs.data)
            })
            .catch(() => {
                // 部署失败时容器可能已经被删掉，重新读一次状态，让按钮回到「部署」
                this.load()
            })
            .finally(() => this.setState({submitting: false}))
    }

    openRootModal = () => {
        const {status} = this.state
        const root = (status && (status.mountedRoot || status.desiredRoot))
            || this.props.defaultRoot || '/home'
        this.setState({rootOpen: true, rootInput: root})
    }

    closeRootModal = () => this.setState({rootOpen: false})

    /**
     * 切换挂载目录：辅助容器只能挂一个目录，后台会用新目录重建容器，
     * 文件面板与终端都跟着新目录走，终端会断开并自动重连。
     */
    switchRoot = () => {
        const {hostId} = this.props
        const value = (this.state.rootInput || '').trim()
        if (!value.startsWith('/')) {
            message.warning('请填写以 / 开头的 Linux 绝对路径')
            return
        }
        this.setState({rootSubmitting: true})
        HttpClient.post('admin/host/agent/root', null, {id: hostId, root: value}, {toastError: false})
            .then(rs => {
                // 切换是异步任务（重建容器）：弹窗原地切换为实时日志
                message.success((rs && rs.message) || '切换已开始')
                this.setState({rootOpen: false})
                this.notifyConfigChange()
                this.openLog(rs.data)
            })
            .catch(e => {
                message.error(e.message || '切换挂载目录失败')
                // 重建失败时旧容器可能已经删掉，刷新状态让按钮回到「部署」
                this.load()
            })
            .finally(() => this.setState({rootSubmitting: false}))
    }

    /**
     * 检查更新：读注册中心里当前 tag 指向的 manifest 摘要与主机上实际镜像比较，
     * 已是最新则只提示，不发请求；发现新镜像才打开更新弹窗，由用户确认后执行更新。
     * 摘要一致但版本落后于平台版本（固定了旧版本 tag）时，更新镜像没有意义，提示先改 tag。
     */
    checkUpdate = () => {
        const {hostId} = this.props
        message.loading({content: '正在检查更新…', key: 'agent-update-check', duration: 0})
        HttpClient.get('admin/host/agent/check-update', {id: hostId}, {toastError: false})
            .then(rs => {
                const check = rs.data || {}
                if (check.upToDate) {
                    message.success({content: check.message || '已是最新版本', key: 'agent-update-check'})
                } else if (check.digestUpToDate) {
                    message.warning({content: check.message, key: 'agent-update-check', duration: 8})
                } else {
                    message.success({content: check.message || '发现新版本', key: 'agent-update-check'})
                    this.setState({check, updateOpen: true})
                }
            })
            .catch(e => message.error({content: e.message || '检查更新失败', key: 'agent-update-check'}))
    }

    /**
     * 更新镜像：按当前配置的 tag 重新拉取并重建辅助容器。
     * 仅在「检查更新」发现注册中心里有新镜像后调用；无论 latest 还是固定版本 tag 都会先
     * 尝试拉取远端，拉取失败但本地已有镜像时继续使用本地镜像。
     */
    update = () => {
        const {hostId, defaultTag, defaultRoot} = this.props
        const {status} = this.state
        const tag = defaultTag || (status && status.tag)
        this.setState({updating: true})
        HttpClient.post('admin/host/agent/deploy', null,
            {id: hostId, tag, root: defaultRoot, recreate: true})
            .then(rs => {
                // 更新是异步任务：接口返回 logId，弹窗原地切换为实时日志
                message.success((rs && rs.message) || '更新已开始')
                this.setState({updateOpen: false})
                this.notifyConfigChange()
                this.openLog(rs.data)
            })
            .catch(() => {
                // 重建失败时旧容器可能已经删掉，刷新状态让按钮回到「部署」
                this.setState({updateOpen: false})
                this.load()
            })
            .finally(() => this.setState({updating: false}))
    }

    openLog = logId => {
        if (logId) {
            this.setState({logId, logVisible: true})
        }
    }

    /**
     * 部署 / 更新 / 切换目录任务结束（日志 WS 关闭）时刷新状态与配置：
     * 成功则版本徽标与按钮恢复正常，失败则按钮回到「部署」。
     */
    onLogEnd = () => {
        this.load()
        this.notifyConfigChange()
    }

    closeLog = () => {
        this.setState({logVisible: false})
        this.onLogEnd()
    }

    /**
     * 「更多」下拉菜单项：deployed 时的版本信息（仅展示、不可点）+ 低频容器操作 + 帮助
     * （未部署时只有帮助）。agent 与平台同 tag 发版，落后于平台版本时附上最新版本。
     */
    moreMenuItems() {
        const {status} = this.state
        const deployed = !!(status && status.deployed)
        const items = []
        if (deployed) {
            items.push({key: 'version', disabled: true,
                label: `当前版本 ${status.agentVersion || '未知'}`})
            if (status && status.updateAvailable && status.platformVersion) {
                items.push({key: 'latest-version', disabled: true,
                    label: `最新版本 v${status.platformVersion}`})
            }
            items.push({type: 'divider'})
            items.push({key: 'root', icon: <FolderOpenOutlined/>, label: '切换挂载目录'})
            if (status && !status.needsRebuild) {
                items.push({key: 'update', icon: <SyncOutlined/>, label: '检查更新'})
            }
            if (PermUtils.hasPermission('host:save')) {
                items.push({key: 'restart', icon: <ReloadOutlined/>, label: '重启辅助容器'})
            }
            items.push({type: 'divider'})
        }
        items.push({key: 'help', icon: <QuestionCircleOutlined/>, label: '帮助'})
        return items
    }

    onMoreMenuClick = ({key}) => {
        if (key === 'root') {
            this.openRootModal()
        } else if (key === 'update') {
            this.checkUpdate()
        } else if (key === 'restart') {
            this.restart()
        } else if (key === 'help') {
            this.setState({helpOpen: true})
        }
    }

    restart = () => {
        Modal.confirm({
            title: '重启辅助容器？',
            content: '容器内 shell 会中断，终端会话会丢失并自动重连',
            okText: '重启',
            onOk: () => {
                const {hostId} = this.props
                return HttpClient.post('admin/host/agent/restart', null, {id: hostId})
                    .then(rs => {
                        message.success((rs && rs.message) || '辅助容器已重启')
                        this.load()
                    })
            },
        })
    }

    /** 卡片标题里的状态块：加载中 / 运行中 / 已停止 / 未部署；版本号收进「更多」菜单展示。 */
    renderSummary() {
        const {status, loading, error} = this.state
        if (error) {
            return <Typography.Text type='danger'>状态读取失败</Typography.Text>
        }
        if (loading && !status) {
            return <Space size={6}>
                <Spin size='small'/>
                <Typography.Text type='secondary'>读取状态…</Typography.Text>
            </Space>
        }
        if (!status || !status.deployed) {
            return <Badge status='default' text='未部署'/>
        }
        return <Badge status={status.running ? 'success' : 'default'}
                      text={status.running ? '运行中' : '已停止'}/>
    }

    /** 卡片正文顶部的提示：只有异常 / 需要处理时才出现，正常状态不占空间。 */
    renderNotice() {
        const {status, error} = this.state
        if (error) {
            return <Alert className='mb-3' type='error' showIcon title='读取辅助容器状态失败'
                          description={<>{error}　<a onClick={this.load}>重试</a></>}/>
        }
        if (status && status.message) {
            return <Alert className='mb-3' type='error' showIcon title={status.message}/>
        }
        if (status && status.needsRebuild) {
            return <Alert className='mb-3' type='warning' showIcon
                          title='配置与容器实际参数不一致（镜像 tag 或挂载目录已变更），点「重新部署」即可应用'/>
        }
        if (status && status.updateAvailable) {
            return <Alert className='mb-3' type='info' showIcon
                          title={<span>agent 镜像有新版本 {status.platformVersion}（当前
                              {status.agentVersion || '未知'}），点右上角「更多 - 检查更新」，
                              确认有新镜像后按当前 tag 重新拉取并重建容器即可升级</span>}/>
        }
        if (!PermUtils.hasPermission('host:save') && !(status && status.deployed)) {
            return <Alert className='mb-3' type='info' showIcon title='需要 host:save 权限才能部署辅助容器'/>
        }
        return null
    }

    render() {
        const {status, deployOpen, helpOpen, rootOpen, rootInput, rootSubmitting, updateOpen,
            logId, logVisible} = this.state
        const canSave = PermUtils.hasPermission('host:save')
        const deployed = !!(status && status.deployed)
        const {extra, children} = this.props

        const actions = <>
            {extra}
            {canSave && !deployed &&
                <Button type='primary' icon={<CloudServerOutlined/>}
                        onClick={this.openDeploy}>部署辅助容器</Button>}
            {canSave && deployed && status && status.needsRebuild &&
                <Button type='primary' icon={<CloudServerOutlined/>}
                        onClick={this.openDeploy}>重新部署</Button>}
            <Dropdown menu={{
                items: this.moreMenuItems(),
                onClick: this.onMoreMenuClick,
                style: {maxHeight: '60vh', overflowY: 'auto'},
            }} trigger={['click']} placement='bottomRight'>
                <Button type='text' icon={<MoreOutlined/>}>更多</Button>
            </Dropdown>
        </>

        return <>
            {typeof children === 'function' && children({
                summary: this.renderSummary(),
                notice: this.renderNotice(),
                actions,
            })}

            {this.renderDeployModal()}
            {this.renderRootModal(rootOpen, rootInput, rootSubmitting)}
            {this.renderUpdateModal(updateOpen)}
            {this.renderHelpModal()}

            <TaskLogDrawer open={logVisible} logId={logId}
                           title='Agent 任务日志'
                           onClose={this.closeLog}
                           onEnd={this.onLogEnd}/>
        </>
    }

    renderDeployModal() {
        const {deployOpen, tag, submitting, status} = this.state
        const repository = (status && status.repository) || ''
        const effectiveTag = (tag || '').trim() || 'latest'
        const imagePreview = repository ? `${repository}:${effectiveTag}` : effectiveTag
        return <Modal title='部署辅助容器'
                      open={deployOpen}
                      width={560}
                      confirmLoading={submitting}
                      okText='部署'
                      onOk={this.deploy}
                      onCancel={this.closeDeploy}
                      destroyOnHidden>
            <Typography.Text strong>Agent 镜像 Tag</Typography.Text>
            <Input className='mt-1' value={tag}
                   placeholder='latest'
                   onChange={e => this.setState({tag: e.target.value})}/>
            <div className='mt-2' style={{fontSize: 12, lineHeight: 1.8}}>
                <div>镜像地址（系统参数）：<code>{repository || '-'}</code></div>
                <div>将使用：<code>{imagePreview}</code></div>
                <div>tag 留空表示 latest；生产建议固定为版本 tag，如 <code>v4.8.40</code>。</div>
            </div>
            <Typography.Text type='secondary' style={{display: 'block', marginTop: 12}}>
                挂载目录来自 Agent 配置（当前 <code>{this.props.defaultRoot || (status && status.desiredRoot) || '/home'}</code>），
                部署后可在右上角「更多 - 切换挂载目录」调整，切换会重建容器。
            </Typography.Text>
        </Modal>
    }

    renderRootModal(rootOpen, rootInput, rootSubmitting) {
        return <Modal open={rootOpen} width={640} destroyOnHidden
                      title='切换挂载目录'
                      okText='切换并重建'
                      confirmLoading={rootSubmitting}
                      onOk={this.switchRoot}
                      onCancel={this.closeRootModal}>
            <Typography.Text strong>宿主机目录</Typography.Text>
            <Input className='mt-1' value={rootInput} placeholder='/home'
                   onChange={e => this.setState({rootInput: e.target.value})}
                   onPressEnter={this.switchRoot}/>
            <Space size={4} wrap className='mt-2'>
                {['/home', '/data', '/opt', '/srv', '/var/www'].map(path =>
                    <Button key={path} size='small'
                            onClick={() => this.setState({rootInput: path})}>{path}</Button>)}
            </Space>
            <div className='mt-3' style={{fontSize: 12, lineHeight: 1.8}}>
                <div>只接受 Docker daemon 可见的 Linux 绝对路径；Windows 主机：WSL 2 后端填
                    <code> /run/desktop/mnt/host/d/...</code>，Docker VMM 填 <code>/host_mnt/d/...</code>，
                    WSL 2 原生 docker 填 <code>/mnt/d/...</code>。
                </div>
                <div>Docker VMM 还需先在 Settings → Resources → File sharing 共享该目录（不支持自动共享）。</div>
                <div>切换会用新目录重建辅助容器，终端会切到新目录并断开自动重连；不支持 /、/etc 等系统目录。</div>
            </div>
        </Modal>
    }

    /**
     * 更新确认弹窗：仅在「检查更新」发现注册中心里有新镜像后弹出。
     * 展示比较前后的镜像摘要与版本，确认后按当前 tag 重新拉取镜像并重建容器，
     * 终端会话会中断并自动重连。
     */
    renderUpdateModal(updateOpen) {
        const {check, updating} = this.state
        const shortDigest = d => {
            if (!d) return '-'
            const hex = (d.split('@').pop() || '').replace(/^sha256:/, '')
            return hex.slice(0, 12) || '-'
        }
        return <Modal open={updateOpen} width={560}
                      title='更新辅助容器镜像'
                      okText='更新'
                      confirmLoading={updating}
                      onOk={this.update}
                      onCancel={() => this.setState({updateOpen: false})}>
            <Typography.Paragraph>
                注册中心里该 tag 已有新镜像。将按当前 tag 重新拉取镜像并重建辅助容器，
                终端会话会中断并自动重连。任务在后台执行，确认后弹窗会切换为实时日志（任务中心也可查看）。
            </Typography.Paragraph>
            <div style={{fontSize: 12, lineHeight: 1.8}}>
                <div>镜像：<code>{(check && check.image) || '-'}</code></div>
                {check && check.localDigest && check.remoteDigest &&
                    <div>镜像摘要：<code>sha256:{shortDigest(check.localDigest)}</code>
                        {' → '}<code>sha256:{shortDigest(check.remoteDigest)}</code></div>}
                {check && check.agentVersion && check.platformVersion &&
                    <div>版本：<code>{check.agentVersion}</code>
                        {' → '}<code>v{check.platformVersion.replace(/^[vV]/, '')}</code></div>}
                <div>拉取失败但本地已有镜像时会继续使用本地镜像（离线主机也能重建）。</div>
            </div>
        </Modal>
    }

    renderHelpModal() {
        const {helpOpen, status} = this.state
        return <Modal title='辅助容器说明' open={helpOpen} width={720} footer={null}
                      onCancel={() => this.setState({helpOpen: false})} destroyOnHidden>
            <div style={{lineHeight: 1.9}}>
                <Typography.Paragraph>
                    终端依赖在目标主机上运行的辅助容器 <code>docker-admin-agent</code>，
                    容器内已装 docker / docker compose，并接入宿主机 docker.sock。
                    终端里可直接用常见运维工具：<code>curl</code> / <code>wget</code> / <code>ping</code> /
                    <code>ss</code> / <code>netstat</code> / <code>dig</code> / <code>ssh</code> /
                    <code>git</code> / <code>jq</code> / <code>yq</code> / <code>rsync</code> /
                    <code>vim</code> / <code>htop</code> / <code>ps</code> / <code>lsof</code> /
                    <code>tree</code> 等（bash 需手动执行 <code>bash</code> 切换）。
                </Typography.Paragraph>

                <Typography.Title level={5}>权限与安全</Typography.Title>
                <ul style={{margin: '0 0 8px 18px', padding: 0}}>
                    <li>容器挂载 docker.sock 与一个宿主机目录，权限等同于宿主机 root，请只在受信任环境使用。</li>
                    <li>终端里执行命令会直接影响宿主机，删除、覆盖文件等危险操作请谨慎。</li>
                </ul>

                <Typography.Title level={5}>会话保持</Typography.Title>
                <ul style={{margin: '0 0 8px 18px', padding: 0}}>
                    <li>shell 保存在平台服务端：切走页面、刷新或短暂断网后会自动重连，并回放断开期间的输出，
                        长耗时命令不会中断。
                    </li>
                    <li>只要终端还在产出（例如 <code>docker pull</code> 刷进度），会话就一直保留；
                        断开后连续 30 分钟没有输出 / 输入才回收，断开超过 24 小时则强制回收。
                        两个值可在「系统管理 - 系统参数 - 主机终端」调整。
                    </li>
                    <li>完全没有输出、仍在运行的长命令（如 <code>sleep 3600</code>）仍按上面的静默时间回收。</li>
                    <li>点右上角「新建会话」可立即换一个干净的 shell（关闭会话后也用它再次开始）。</li>
                    <li>平台重启、辅助容器重建或切换挂载目录后会话会丢失（shell 运行在辅助容器内）。</li>
                    <li>同一个会话同时只允许一个窗口使用：新窗口打开会接管，旧窗口收到提示后停止自动重连。</li>
                </ul>

                <Typography.Title level={5}>挂载目录</Typography.Title>
                <ul style={{margin: '0 0 8px 18px', padding: 0}}>
                    <li>挂载目录来自「工具 - 主机 Agent」的配置，留空时首次默认 <code>/home</code>。</li>
                    <li>在右上角「更多」菜单里点「切换挂载目录」按需切换，切换会用新目录重建容器，终端会断开并自动重连。</li>
                    <li>只接受 Docker daemon 可见的 Linux 绝对路径（以 / 开头）：
                        WSL 2 后端是 <code>/run/desktop/mnt/host/d/data</code>，
                        Docker VMM 是 <code>/host_mnt/d/data</code>，
                        WSL 2 原生 docker 是 <code>/mnt/d/data</code>；不要填 <code>D:\data</code> 这类 Windows 路径。
                        Docker VMM 还必须在 Settings → Resources → File sharing 里共享该目录。
                    </li>
                    <li>不允许挂载 <code>/</code>、<code>/etc</code> 等系统目录，也不支持 Windows 容器主机。</li>
                </ul>

                <Typography.Title level={5}>文件操作</Typography.Title>
                <Typography.Paragraph>
                    点右上角「上传文件」可把本地文件上传到挂载目录：弹窗里逐级选择目标路径，目标目录里已有
                    同名文件 / 目录时不允许覆盖。下载、查看、编辑、删除、重命名、新建目录等其余操作都在终端里用
                    <code>cp</code> / <code>rsync</code> / <code>scp</code> / <code>curl</code>
                    等命令完成。
                </Typography.Paragraph>

                <Typography.Title level={5}>镜像</Typography.Title>
                <Typography.Paragraph>
                    镜像地址在「系统管理 - 系统参数 - 主机辅助容器」维护（不含 tag）；镜像 tag 与挂载目录在
                    「工具 - 主机 Agent」的配置里维护。配置与容器实际参数不一致时，
                    面板右上角会出现「重新部署」，点它即可按配置重建容器。
                </Typography.Paragraph>
                <Typography.Paragraph>
                    agent 镜像发新版后，点右上角「更多 - 检查更新」即可升级：先读注册中心里当前 tag
                    指向的镜像摘要与实际镜像比较，已是最新只做提示；确认有新镜像后再拉取并重建容器
                    （无论 latest 还是固定版本 tag 都会先尝试拉取远端，拉取失败但本地已有镜像时继续用本地镜像）。
                    若当前是固定的旧版本 tag（tag 内容没变、只是落后于平台版本），会提示先在配置里改 tag
                    再「重新部署」。更新会中断终端会话，会话会自动重连。
                </Typography.Paragraph>

                {status && status.deployed && <>
                    <Typography.Title level={5}>当前状态</Typography.Title>
                    <ul style={{margin: '0 0 0 18px', padding: 0}}>
                        <li>镜像：<code>{status.image || '-'}</code>
                            {status.mountedImage && status.mountedImage !== status.image &&
                                <>（实际：<code>{status.mountedImage}</code>）</>}
                        </li>
                        <li>镜像版本：<code>{status.agentVersion || '未知（本功能上线前的旧镜像）'}</code>
                            {status.updateAvailable && status.platformVersion &&
                                <>（最新：<code>{status.platformVersion}</code>）</>}
                            {status.agentVersion &&
                                <>，终端里 <code>echo $AGENT_VERSION</code>、
                                    <code>cat /etc/docker-admin-agent-release</code> 也能看到</>}
                        </li>
                        <li>挂载目录：<code>{status.mountedRoot || '-'}</code></li>
                        <li>docker.sock：<code>{status.socketPath || '-'}</code></li>
                        <li>容器 ID：<code>{status.containerId ? status.containerId.slice(0, 12) : '-'}</code></li>
                    </ul>
                </>}
            </div>
        </Modal>
    }
}
