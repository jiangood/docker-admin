import {Alert, Button, Drawer, Empty, Input, Popconfirm, Result, Space, Spin, Table, Tag, Typography} from 'antd'
import {CloudDownloadOutlined, EditOutlined, FolderOpenOutlined, ReloadOutlined} from '@ant-design/icons'
import React from 'react'
import {HttpClient} from '@jiangood/open-admin'
import LogView from './LogView'
import ComposeEditorModal from './ComposeEditorModal'

const STORAGE_PREFIX = 'docker-admin.compose.dirs.'

const STATUS_META = {
    running: {color: 'green', text: '运行中'},
    partial: {color: 'orange', text: '部分运行'},
    exited: {color: 'default', text: '已停止'},
    unknown: {color: 'default', text: '未运行'},
}

/**
 * 主机详情页的 Compose 面板。
 * <p>
 * 项目列表来自 {@code docker compose ls}，没登记过的目录可以手工打开（记住在本机浏览器里）。
 * 只提供三种操作：pull / up / down，以及编辑 compose 文件；命令在主机上的临时容器里执行，
 * 日志通过 WebSocket 实时展示。
 */
export default class HostComposePanel extends React.Component {

    state = {
        loading: true,
        capability: null,
        projects: [],
        error: null,
        openError: null,
        dirInput: '',
        opening: false,
        starting: false,
        // 正在执行的命令：同一项目执行期间按钮置灰
        runningDir: null,
        // 日志抽屉
        logId: null,
        logTitle: '',
        // 拉取辅助镜像
        preparing: false,
    }

    editorRef = React.createRef()

    componentDidMount() {
        this.loadAll()
    }

    // ------------------------------------------------------------------ 数据

    storedDirs = () => {
        try {
            const raw = localStorage.getItem(STORAGE_PREFIX + this.props.hostId)
            const list = JSON.parse(raw || '[]')
            return Array.isArray(list) ? list : []
        } catch (e) {
            return []
        }
    }

    rememberDir = dir => {
        const dirs = new Set(this.storedDirs())
        dirs.add(dir)
        localStorage.setItem(STORAGE_PREFIX + this.props.hostId, JSON.stringify([...dirs]))
    }

    forgetDir = dir => {
        const dirs = this.storedDirs().filter(d => d !== dir)
        localStorage.setItem(STORAGE_PREFIX + this.props.hostId, JSON.stringify(dirs))
    }

    loadAll = async () => {
        const {hostId} = this.props
        this.setState({loading: true, error: null, openError: null})
        try {
            const capRs = await HttpClient.get('admin/host/compose/capability', {id: hostId}, {toastError: false})
            const capability = capRs.data || {}
            this.setState({capability})
            if (!capability.supported) {
                this.setState({loading: false})
                return
            }

            const listRs = await HttpClient.get('admin/host/compose/list', {id: hostId}, {toastError: false})
            const map = new Map()
            ;(listRs.data || []).forEach(p => map.set(p.dir, p))

            // 手工打开过、但 compose ls 里没有的项目，单独取一次
            const opened = await Promise.all(this.storedDirs().map(dir =>
                HttpClient.get('admin/host/compose/open', {id: hostId, dir}, {toastError: false})
                    .then(rs => rs.data)
                    .catch(() => null)))
            opened.filter(Boolean).forEach(p => map.set(p.dir, {...(map.get(p.dir) || {}), ...p}))

            const projects = [...map.values()].sort((a, b) =>
                String(a.name || a.dir).localeCompare(String(b.name || b.dir)))
            this.setState({projects, loading: false})
        } catch (e) {
            this.setState({loading: false, error: e.message || '加载失败'})
        }
    }

    openDir = () => {
        const dir = this.state.dirInput.trim()
        if (!dir || this.state.opening) {
            return
        }
        this.setState({opening: true, openError: null})
        HttpClient.get('admin/host/compose/open', {id: this.props.hostId, dir}, {toastError: false})
            .then(rs => {
                const project = rs.data
                this.rememberDir(project.dir)
                this.setState(prev => ({
                    dirInput: '',
                    projects: [project, ...prev.projects.filter(p => p.dir !== project.dir)],
                }))
            })
            .catch(e => this.setState({openError: e.message || '打开目录失败'}))
            .finally(() => this.setState({opening: false}))
    }

    // ------------------------------------------------------------------ 操作

    run = (record, action) => {
        if (this.state.starting || this.state.runningDir) {
            return
        }
        this.setState({starting: true, openError: null})
        HttpClient.postForm('admin/host/compose/run', {id: this.props.hostId, dir: record.dir, action}, {toastError: false})
            .then(rs => this.setState({
                starting: false,
                logId: rs.data,
                logTitle: `${action} - ${record.name || record.dir}`,
                runningDir: record.dir,
            }))
            .catch(e => this.setState({starting: false, openError: e.message || '执行失败'}))
    }

    cancelRun = () => {
        const {logId} = this.state
        if (!logId) {
            return
        }
        HttpClient.postForm('admin/host/compose/cancel', {id: this.props.hostId, logId})
            .catch(() => null)
    }

    prepare = () => {
        if (this.state.preparing) {
            return
        }
        this.setState({preparing: true})
        HttpClient.postForm('admin/host/compose/prepare', {id: this.props.hostId}, {toastError: false})
            .then(rs => this.setState({
                preparing: false,
                logId: rs.data,
                logTitle: `准备运行环境 - ${this.state.capability?.helperImage || 'docker:cli'}`,
            }))
            .catch(e => this.setState({preparing: false, openError: e.message || '无法拉取辅助镜像'}))
    }

    openEditor = record => {
        this.editorRef.current?.show(record)
    }

    closeLog = () => {
        this.setState({logId: null, runningDir: null}, () => this.loadAll())
    }

    /** 日志流结束（任务结束）：刷新列表，但保留抽屉让用户看最后的输出 */
    onLogClose = () => {
        this.setState({runningDir: null})
        this.loadAll()
    }

    // ------------------------------------------------------------------ 渲染

    renderLogDrawer() {
        const {logId, logTitle, runningDir} = this.state
        return <Drawer
            title={logTitle || '执行日志'}
            open={!!logId}
            width={900}
            onClose={this.closeLog}
            destroyOnHidden
            extra={runningDir
                ? <Button danger size='small' onClick={this.cancelRun}>取消执行</Button>
                : null}>
            {logId &&
                <LogView key={logId}
                         url={'/admin/ws/host-compose-log/' + logId}
                         websocket={true}
                         height={520}
                         onClose={this.onLogClose}/>}
        </Drawer>
    }

    renderUnsupported() {
        const {capability, preparing} = this.state
        return <Space direction='vertical' size='middle' style={{width: '100%'}}>
            <Alert type='warning' showIcon
                   message='该主机暂不能做 Compose 管理'
                   description={capability?.reason || '未知原因'}/>
            <Space wrap>
                <Button icon={<ReloadOutlined/>} onClick={this.loadAll}>重新检测</Button>
                {capability && capability.canPrepare &&
                    <Button type='primary' icon={<CloudDownloadOutlined/>} loading={preparing}
                            onClick={this.prepare}>
                        准备运行环境（拉取 {capability.helperImage}）
                    </Button>}
            </Space>
            <Typography.Text type='secondary'>
                说明：平台通过 docker API 在主机上启动临时容器执行 compose，
                因此主机上需要有一个自带 docker CLI 与 compose 插件的镜像。
            </Typography.Text>
        </Space>
    }

    renderTable() {
        const {projects, loading, runningDir, starting} = this.state
        const busy = !!runningDir || starting

        const columns = [
            {
                title: '项目', dataIndex: 'name', width: 220,
                render: (name, record) => <Space direction='vertical' size={0}>
                    <Typography.Text strong>{name || record.dir}</Typography.Text>
                    <Typography.Text type='secondary' style={{fontSize: 12, wordBreak: 'break-all'}}>
                        {record.dir}
                    </Typography.Text>
                </Space>
            },
            {
                title: '状态', dataIndex: 'status', width: 130,
                render: (status, record) => {
                    const meta = STATUS_META[status] || STATUS_META.unknown
                    return <Space size={4}>
                        <Tag color={meta.color}>{meta.text}</Tag>
                        {record.total ? <Typography.Text type='secondary'>
                            {record.running || 0}/{record.total}
                        </Typography.Text> : null}
                    </Space>
                }
            },
            {
                title: 'compose 文件', dataIndex: 'composeFile', width: 200,
                render: v => v ? <Typography.Text code>{v}</Typography.Text> : '-'
            },
            {
                title: '操作', width: 300,
                render: (_, record) => {
                    const rowBusy = runningDir === record.dir
                    return <Space size={0} wrap>
                        <Button type='link' size='small' icon={<EditOutlined/>}
                                disabled={busy}
                                onClick={() => this.openEditor(record)}>编辑</Button>
                        <Button type='link' size='small' disabled={busy}
                                onClick={() => this.run(record, 'pull')}>pull</Button>
                        <Button type='link' size='small' disabled={busy}
                                onClick={() => this.run(record, 'up')}>up</Button>
                        <Popconfirm
                            title='确定执行 down？'
                            description='会停止并删除该项目的容器与网络（不删除命名卷）'
                            okButtonProps={{danger: true}}
                            onConfirm={() => this.run(record, 'down')}>
                            <Button type='link' size='small' danger disabled={busy}>
                                {rowBusy ? '执行中…' : 'down'}
                            </Button>
                        </Popconfirm>
                    </Space>
                }
            },
        ]

        return <Table rowKey='dir' size='small' loading={loading}
                      dataSource={projects} columns={columns}
                      pagination={false}
                      locale={{emptyText: <Empty description='还没有 compose 项目：可以在上面输入项目目录打开，或先在主机上执行过 docker compose up'/>}}/>
    }

    render() {
        const {capability, error, openError, dirInput, opening} = this.state

        const toolbar = <Space wrap className='mb-3'>
            <Input allowClear
                   placeholder='项目目录（宿主机绝对路径，如 /opt/compose/app）'
                   style={{width: 380}}
                   value={dirInput}
                   onChange={e => this.setState({dirInput: e.target.value})}
                   onPressEnter={this.openDir}/>
            <Button icon={<FolderOpenOutlined/>} loading={opening} onClick={this.openDir}>打开目录</Button>
            <Button icon={<ReloadOutlined/>} onClick={this.loadAll}>刷新</Button>
        </Space>

        const capabilityLine = capability && capability.supported
            ? <Typography.Text type='secondary' style={{fontSize: 12}}>
                辅助镜像 {capability.helperImage}
                {capability.composeVersion ? ` · compose ${capability.composeVersion}` : ''}
                {capability.daemonMode ? ` · 连接方式 ${capability.daemonMode}` : ''}
            </Typography.Text>
            : null

        return <>
            {capability?.warning && <Alert type='warning' showIcon className='mb-3' message={capability.warning}/>}

            {error
                ? <Result status='warning' title='加载失败' subTitle={error}
                          extra={<Button onClick={this.loadAll}>重试</Button>}/>
                : !capability
                    ? <div style={{padding: 32, textAlign: 'center'}}><Spin/></div>
                    : !capability.supported
                        ? this.renderUnsupported()
                        : <>
                            {toolbar}
                            {openError && <Alert type='error' showIcon className='mb-3' message={openError}
                                                 closable onClose={() => this.setState({openError: null})}/>}
                            {this.renderTable()}
                            {capabilityLine && <div className='mt-2'>{capabilityLine}</div>}
                        </>}

            {this.renderLogDrawer()}

            <ComposeEditorModal ref={this.editorRef}
                                hostId={this.props.hostId}
                                onSaved={() => this.loadAll()}/>
        </>
    }
}
