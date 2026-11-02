import React from 'react'
import {Alert, Button, Input, Modal, Popconfirm, Space, Spin, Typography, message} from 'antd'
import {CloudServerOutlined, DeleteOutlined, ReloadOutlined} from '@ant-design/icons'
import {HttpClient, PermUtils} from '@jiangood/open-admin'

/**
 * 主机辅助容器状态与部署入口。
 * <p>
 * 文件 / Docker CLI 两个 Tab 都依赖这一个容器：挂载一个宿主机目录，并把 docker.sock
 * 挂进容器。容器权限等价于宿主机 root，所以必须由 host:save 显式部署。
 */
export default class extends React.Component {

    state = {
        status: null,
        loading: true,
        error: null,
        modalOpen: false,
        root: '/home',
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
        const root = (status && (status.desiredRoot || status.mountedRoot)) || '/home'
        const tag = (status && status.tag) || 'latest'
        this.setState({modalOpen: true, root, tag})
    }

    closeDeploy = () => this.setState({modalOpen: false})

    deploy = () => {
        const {hostId} = this.props
        const {root, tag} = this.state
        const value = (root || '').trim()
        if (!value.startsWith('/')) {
            message.warning('请填写以 / 开头的 Linux 绝对路径')
            return
        }
        const tagValue = (tag || '').trim() || 'latest'
        if (!/^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$/.test(tagValue)) {
            message.warning('镜像 Tag 只允许字母、数字、下划线、点和短横线，例如 latest、v4.8.40')
            return
        }
        const recreate = !!(this.state.status && this.state.status.deployed)
        this.setState({submitting: true})
        HttpClient.post('admin/host/agent/deploy', null, {
            id: hostId, root: value, tag: tagValue, recreate
        })
            .then(rs => {
                message.success((rs && rs.message) || '辅助容器已就绪')
                this.setState({modalOpen: false})
                this.load()
            })
            .catch(() => {
            })
            .finally(() => this.setState({submitting: false}))
    }

    remove = () => {
        const {hostId} = this.props
        HttpClient.post('admin/host/agent/remove', null, {id: hostId})
            .then(() => {
                message.success('辅助容器已删除')
                this.load()
            })
            .catch(() => {
            })
    }

    render() {
        const {status, loading, error, modalOpen, root, submitting} = this.state
        const canSave = PermUtils.hasPermission('host:save')

        if (loading && !status) {
            return <div className='mb-3'><Spin size='small'/></div>
        }
        if (error) {
            return <Alert className='mb-3' type='error' showIcon title='读取辅助容器状态失败'
                          description={<Space>
                              <span>{error}</span>
                              <Button size='small' onClick={this.load}>重试</Button>
                          </Space>}/>
        }
        if (status && status.message) {
            return <Alert className='mb-3' type='error' showIcon title={status.message}
                          description={<Button size='small' icon={<ReloadOutlined/>}
                                               onClick={this.load}>刷新</Button>}/>
        }

        const deployed = !!(status && status.deployed)
        const running = !!(status && status.running)
        const needsRebuild = !!(status && status.needsRebuild)

        const toolbar = <Space size={8}>
            <Button size='small' icon={<ReloadOutlined/>} loading={loading} onClick={this.load}>刷新</Button>
            {canSave &&
                <Button size='small' onClick={this.openDeploy}>
                    {deployed ? '重建' : '部署辅助容器'}
                </Button>}
            {canSave && deployed &&
                <Popconfirm title='删除辅助容器？宿主机上的文件不会被删除'
                            onConfirm={this.remove}>
                    <Button size='small' danger icon={<DeleteOutlined/>}>删除</Button>
                </Popconfirm>}
        </Space>

        if (!deployed) {
            return <>
                <Alert className='mb-3' type='warning' showIcon icon={<CloudServerOutlined/>}
                       title='主机文件 / Docker CLI 需要先部署辅助容器'
                       description={<div>
                           <div>辅助容器会挂载一个宿主机目录，并把 docker.sock 挂进容器；
                               容器内权限等同于宿主机 root，请仅在受信任环境使用。</div>
                           <Space className='mt-2'>{toolbar}</Space>
                           {!canSave && <div>
                               <Typography.Text type='secondary'>需要 host:save 权限才能部署，请联系管理员</Typography.Text>
                           </div>}
                       </div>}/>
                {this.renderDeployModal()}
            </>
        }

        return <>
            <Alert className='mb-3' type={needsRebuild ? 'warning' : 'info'} showIcon
                   title={`辅助容器${running ? '运行中' : '已停止'}${needsRebuild ? '，目录或镜像配置已变更' : ''}`}
                   description={<div>
                       <div>挂载目录：<Typography.Text code>{status.mountedRoot || '-'}</Typography.Text>
                            {'　'}镜像：<Typography.Text code>{status.image || '-'}</Typography.Text>
                       </div>
                       {needsRebuild &&
                           <div style={{marginTop: 4}}>
                               当前容器：<Typography.Text code>{status.mountedImage || '-'}</Typography.Text>
                               ，重建后使用 <Typography.Text code>{status.image || '-'}</Typography.Text>
                           </div>}
                       <Space className='mt-2'>{toolbar}</Space>
                   </div>}/>
            {this.renderDeployModal()}
        </>
    }

    renderDeployModal() {
        const {modalOpen, root, tag, submitting, status} = this.state
        const rebuilding = !!(status && status.deployed)
        const repository = (status && status.repository) || ''
        const effectiveTag = (tag || '').trim() || 'latest'
        const imagePreview = repository ? `${repository}:${effectiveTag}` : effectiveTag
        return <Modal title={rebuilding ? '重建辅助容器' : '部署辅助容器'}
                      open={modalOpen}
                      width={640}
                      confirmLoading={submitting}
                      okText={rebuilding ? '重建' : '部署'}
                      onOk={this.deploy}
                      onCancel={this.closeDeploy}
                      destroyOnHidden>
            <Alert className='mb-3' type='warning' showIcon
                   title='该容器会挂载宿主机目录并接入宿主机 docker.sock，权限等同于宿主机 root'/>

            <Typography.Text strong>宿主机目录</Typography.Text>
            <Input className='mt-1' value={root}
                   placeholder='/home'
                   onChange={e => this.setState({root: e.target.value})}/>

            <Typography.Text strong style={{display: 'block', marginTop: 16}}>Agent 镜像 Tag</Typography.Text>
            <Input className='mt-1' value={tag}
                   placeholder='latest'
                   onChange={e => this.setState({tag: e.target.value})}/>
            <div className='mt-2' style={{fontSize: 12, lineHeight: 1.8}}>
                <div>镜像地址（全局设置）：<code>{repository || '-'}</code></div>
                <div>将使用：<code>{imagePreview}</code></div>
                <div>tag 留空表示 latest；生产建议固定为版本 tag，如 <code>v4.8.40</code>。</div>
            </div>

            <div className='mt-2' style={{fontSize: 12, lineHeight: 1.8}}>
                <div>一次只能挂载一个目录，只接受 Docker daemon 可见的 Linux 绝对路径（以 / 开头）：</div>
                <ul style={{margin: '6px 0 0 18px', padding: 0}}>
                    <li>Linux 主机：<code>/home</code>、<code>/data</code>、<code>/opt/xxx</code></li>
                    <li>Windows + Docker Desktop（Linux 容器）：通常是
                        <code> /run/desktop/mnt/host/d/data</code>（旧版本可能是 <code>/host_mnt/d/data</code>）
                    </li>
                    <li>WSL2 原生安装的 docker：<code>/mnt/d/data</code></li>
                    <li>Linux 主机挂载了 Windows 共享盘：填 Linux 挂载点，如 <code>/mnt/share</code></li>
                </ul>
                <div style={{marginTop: 6}}>
                    不要填写 <code>D:\data</code>、<code>C:\Users</code> 这类 Windows 路径；
                    不支持 Windows 容器主机（辅助镜像是 Linux 镜像）。
                </div>
                <div style={{marginTop: 6}}>
                    目录必须是真实存在的目录，不要填文件，也不要用指向 <code>/etc</code> 等系统目录的软链接。
                    容器内会保持相同的绝对路径，<code>docker compose</code> 的相对路径与宿主机一致。
                </div>
                <div style={{marginTop: 6}}>
                    建议先验证路径可见：
                    <code>docker run --rm -v &lt;上面的路径&gt;:/check alpine ls -la /check</code>
                </div>
            </div>
        </Modal>
    }
}
