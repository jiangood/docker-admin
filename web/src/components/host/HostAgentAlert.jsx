import React from 'react'
import {Alert, Badge, Button, Input, Modal, Space, Spin, Tooltip, Typography, message} from 'antd'
import {CloudServerOutlined, FolderOpenOutlined, QuestionCircleOutlined} from '@ant-design/icons'
import {HttpClient, PermUtils} from '@jiangood/open-admin'

/**
 * Agent 容器状态与操作：把「标题（状态）」「提示（异常）」「按钮（部署 / 切换挂载目录 / 帮助）」
 * 通过 render prop 交给外层 Card，自己只负责取状态、弹窗和请求。
 * <p>
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
        tag: 'latest',
        submitting: false,
    }

    componentDidMount() {
        this.load()
    }

    componentDidUpdate(prevProps) {
        if (prevProps.hostId !== this.props.hostId) {
            this.load()
        }
    }

    load = () => {
        const {hostId} = this.props
        if (!hostId) {
            return
        }
        this.setState({loading: true, error: null})
        HttpClient.get('admin/host/agent/status', {id: hostId}, {toastError: false})
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

    openDeploy = () => {
        const {status} = this.state
        const tag = (status && status.tag) || 'latest'
        this.setState({deployOpen: true, tag})
    }

    closeDeploy = () => this.setState({deployOpen: false})

    deploy = () => {
        const {hostId} = this.props
        const {tag} = this.state
        const tagValue = (tag || '').trim() || 'latest'
        if (!/^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$/.test(tagValue)) {
            message.warning('镜像 Tag 只允许字母、数字、下划线、点和短横线，例如 latest、v4.8.40')
            return
        }
        this.setState({submitting: true})
        HttpClient.post('admin/host/agent/deploy', null, {id: hostId, tag: tagValue})
            .then(rs => {
                message.success((rs && rs.message) || '辅助容器已就绪')
                this.setState({deployOpen: false})
                this.load()
            })
            .catch(() => {
                // 部署失败时容器可能已经被删掉，重新读一次状态，让按钮回到「部署」
                this.load()
            })
            .finally(() => this.setState({submitting: false}))
    }

    openRootModal = () => {
        const {status} = this.state
        const root = (status && (status.mountedRoot || status.desiredRoot)) || '/home'
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
                message.success((rs && rs.message) || '目录已切换')
                this.setState({rootOpen: false})
                this.load()
            })
            .catch(e => {
                message.error(e.message || '切换挂载目录失败')
                // 重建失败时旧容器可能已经删掉，刷新状态让按钮回到「部署」
                this.load()
            })
            .finally(() => this.setState({rootSubmitting: false}))
    }

    /** 卡片标题里的状态块：加载中 / 运行中 / 已停止 / 未部署。 */
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
                          title='镜像配置已变更，删除 docker-admin-agent 容器后重新部署即可更换镜像'/>
        }
        if (!PermUtils.hasPermission('host:save') && !(status && status.deployed)) {
            return <Alert className='mb-3' type='info' showIcon title='需要 host:save 权限才能部署辅助容器'/>
        }
        return null
    }

    render() {
        const {status, deployOpen, helpOpen, rootOpen, rootInput, rootSubmitting} = this.state
        const canSave = PermUtils.hasPermission('host:save')
        const deployed = !!(status && status.deployed)
        const {extra, children} = this.props

        const actions = <>
            {extra}
            {canSave && (deployed
                ? <Button icon={<FolderOpenOutlined/>}
                          onClick={this.openRootModal}>切换挂载目录</Button>
                : <Button type='primary' icon={<CloudServerOutlined/>}
                          onClick={this.openDeploy}>部署辅助容器</Button>)}
            <Tooltip title='辅助容器说明'>
                <Button type='text' shape='circle' icon={<QuestionCircleOutlined/>}
                        onClick={() => this.setState({helpOpen: true})}/>
            </Tooltip>
        </>

        return <>
            {typeof children === 'function' && children({
                summary: this.renderSummary(),
                notice: this.renderNotice(),
                actions,
            })}

            {this.renderDeployModal()}
            {this.renderRootModal(rootOpen, rootInput, rootSubmitting)}
            {this.renderHelpModal()}
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
                <div>镜像地址（全局设置）：<code>{repository || '-'}</code></div>
                <div>将使用：<code>{imagePreview}</code></div>
                <div>tag 留空表示 latest；生产建议固定为版本 tag，如 <code>v4.8.40</code>。</div>
            </div>
            <Typography.Text type='secondary' style={{display: 'block', marginTop: 12}}>
                首次部署默认挂载 /home，之后可在右上角「切换挂载目录」，切换会重建容器。
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

                <Typography.Title level={5}>挂载目录</Typography.Title>
                <ul style={{margin: '0 0 8px 18px', padding: 0}}>
                    <li>部署时不再指定目录，首次默认 <code>/home</code>；在右上角点「切换挂载目录」按需切换。</li>
                    <li>切换会用新目录重建容器，终端会断开并自动重连。</li>
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
                    页面不再提供上传 / 下载按钮：该目录同时按原始绝对路径挂进容器，
                    在终端里用 <code>cp</code> / <code>rsync</code> / <code>scp</code> / <code>curl</code>
                    等命令上传下载，查看、编辑、删除、重命名、新建目录也都在终端里完成。
                </Typography.Paragraph>

                <Typography.Title level={5}>镜像</Typography.Title>
                <Typography.Paragraph>
                    镜像地址在「设置 - 全局设置」维护（不含 tag），每台主机部署时选择 tag，默认 latest；
                    需要更换 tag 时，在主机详情的「容器」Tab 删除 <code>docker-admin-agent</code> 容器，再回到本页重新部署。
                </Typography.Paragraph>

                {status && status.deployed && <>
                    <Typography.Title level={5}>当前状态</Typography.Title>
                    <ul style={{margin: '0 0 0 18px', padding: 0}}>
                        <li>镜像：<code>{status.image || '-'}</code>
                            {status.mountedImage && status.mountedImage !== status.image &&
                                <>（实际：<code>{status.mountedImage}</code>）</>}
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
