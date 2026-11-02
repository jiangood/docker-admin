import React from 'react'
import {Badge, Button, Input, Modal, Popconfirm, Space, Spin, Tooltip, Typography, message} from 'antd'
import {CloudServerOutlined, FolderOpenOutlined, QuestionCircleOutlined} from '@ant-design/icons'
import {HttpClient, PermUtils} from '@jiangood/open-admin'

/**
 * Agent 容器状态条：右上角只显示当前状态与操作（部署 / 切换挂载目录 / 删除）。
 * <p>
 * 目录同时影响文件面板和终端，所以切换入口放在这一层，与删除按钮同级；
 * 镜像、目录、权限等说明统一收进问号弹窗。
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
        removing: false,
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

    /**
     * 删除辅助容器：远程主机上 stop + remove 可能要几秒，
     * 返回 Promise 让 Popconfirm 的确认按钮同步显示 loading，失败也要有提示。
     */
    remove = () => {
        const {hostId} = this.props
        this.setState({removing: true})
        return HttpClient.post('admin/host/agent/remove', null, {id: hostId}, {toastError: false})
            .then(() => {
                message.success('辅助容器已删除')
                this.load()
            })
            .catch(e => {
                message.error(e.message || '删除辅助容器失败')
                this.load()
            })
            .finally(() => this.setState({removing: false}))
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

    renderSummary() {
        const {status, loading, error, removing} = this.state
        if (removing) {
            return <Space size={6}>
                <Spin size='small'/>
                <Typography.Text type='secondary'>正在删除辅助容器…</Typography.Text>
            </Space>
        }
        if (error) {
            return <Typography.Text type='danger'>
                读取状态失败：{error}　<a onClick={this.load}>重试</a>
            </Typography.Text>
        }
        if (loading && !status) {
            return <Space size={6}>
                <Spin size='small'/>
                <Typography.Text type='secondary'>读取辅助容器状态…</Typography.Text>
            </Space>
        }
        if (status && status.message) {
            return <Typography.Text type='danger'>{status.message}</Typography.Text>
        }
        if (!status || !status.deployed) {
            return <Typography.Text type='secondary'>辅助容器未部署</Typography.Text>
        }
        return <Space size={8} wrap>
            <Badge status={status.running ? 'success' : 'default'} text={status.running ? '运行中' : '已停止'}/>
            <Typography.Text type='secondary'>
                目录 <Typography.Text code>{status.mountedRoot || '-'}</Typography.Text>
            </Typography.Text>
            {status.needsRebuild &&
                <Typography.Text type='warning'>镜像配置已变更，删除后重新部署</Typography.Text>}
        </Space>
    }

    render() {
        const {status, deployOpen, helpOpen, rootOpen, rootInput, rootSubmitting, removing} = this.state
        const canSave = PermUtils.hasPermission('host:save')
        const deployed = !!(status && status.deployed)
        const {extra} = this.props

        return <>
            <div style={{
                display: 'flex', alignItems: 'center', justifyContent: 'flex-end',
                gap: 8, flexWrap: 'wrap', marginBottom: 12,
            }}>
                {this.renderSummary()}
                {extra}
                <Tooltip title='辅助容器说明'>
                    <Button type='text' size='small' shape='circle' icon={<QuestionCircleOutlined/>}
                            disabled={removing}
                            onClick={() => this.setState({helpOpen: true})}/>
                </Tooltip>
                {canSave && (deployed
                    ? <>
                        <Button size='small' icon={<FolderOpenOutlined/>}
                                disabled={removing}
                                onClick={this.openRootModal}>切换挂载目录</Button>
                        <Popconfirm title='删除辅助容器？宿主机上的文件不会被删除'
                                    okText='删除' cancelText='取消'
                                    okButtonProps={{danger: true, loading: removing}}
                                    onConfirm={this.remove}>
                            <Button size='small' danger loading={removing}>删除 Agent</Button>
                        </Popconfirm>
                    </>
                    : <Button size='small' type='primary' icon={<CloudServerOutlined/>}
                              onClick={this.openDeploy}>部署辅助容器</Button>)}
                {!canSave && !deployed &&
                    <Typography.Text type='secondary'>需要 host:save 权限才能部署</Typography.Text>}
            </div>

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
                <div>只接受 Docker daemon 可见的 Linux 绝对路径；Windows 主机填 Docker Desktop 的
                    <code> /run/desktop/mnt/host/d/...</code> 或 WSL2 的 <code>/mnt/d/...</code>。
                </div>
                <div>切换会用新目录重建辅助容器，终端、上传与下载都会切到新目录，终端断开后自动重连；不支持 /、/etc 等系统目录。</div>
            </div>
        </Modal>
    }

    renderHelpModal() {
        const {helpOpen, status} = this.state
        return <Modal title='辅助容器说明' open={helpOpen} width={720} footer={null}
                      onCancel={() => this.setState({helpOpen: false})} destroyOnHidden>
            <div style={{lineHeight: 1.9}}>
                <Typography.Paragraph>
                    文件与终端依赖在目标主机上运行的辅助容器 <code>docker-admin-agent</code>，
                    容器内已装 docker / docker compose，并接入宿主机 docker.sock。
                </Typography.Paragraph>

                <Typography.Title level={5}>权限与安全</Typography.Title>
                <ul style={{margin: '0 0 8px 18px', padding: 0}}>
                    <li>容器挂载 docker.sock 与一个宿主机目录，权限等同于宿主机 root，请只在受信任环境使用。</li>
                    <li>上传会写入当前挂载目录并覆盖同名文件，下载会把该目录打成 tar，请谨慎操作。</li>
                </ul>

                <Typography.Title level={5}>文件目录</Typography.Title>
                <ul style={{margin: '0 0 8px 18px', padding: 0}}>
                    <li>部署时不再指定目录，首次默认 <code>/home</code>；在右上角点「切换挂载目录」按需切换。</li>
                    <li>切换会用新目录重建容器，终端会断开并自动重连。</li>
                    <li>只接受 Docker daemon 可见的 Linux 绝对路径（以 / 开头）：
                        Docker Desktop 一般是 <code>/run/desktop/mnt/host/d/data</code>，
                        WSL2 原生 docker 是 <code>/mnt/d/data</code>；不要填 <code>D:\data</code> 这类 Windows 路径。
                    </li>
                    <li>不允许挂载 <code>/</code>、<code>/etc</code> 等系统目录，也不支持 Windows 容器主机。</li>
                </ul>

                <Typography.Title level={5}>文件操作</Typography.Title>
                <Typography.Paragraph>
                    右上角只保留上传与下载，都作用于当前挂载目录；查看、编辑、删除、重命名、新建目录等请用终端。
                </Typography.Paragraph>

                <Typography.Title level={5}>镜像</Typography.Title>
                <Typography.Paragraph>
                    镜像地址在「设置 - 全局设置」维护（不含 tag），每台主机部署时选择 tag，默认 latest；
                    删除容器后重新部署即可更换 tag。
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
