import {Button, Card, Col, Descriptions, Empty, Input, Modal, Popconfirm, Progress, Result, Row, Select, Space, Spin, Statistic, Switch, Table, Tabs, Tag, Tooltip, Typography} from 'antd'
import React from 'react'
import {DeleteOutlined, ExportOutlined, ImportOutlined, ReloadOutlined} from '@ant-design/icons'
import {HttpClient, Page, PageUtils, PermUtils} from "@jiangood/open-admin"
import ContainerDetail from "../../components/container/ContainerDetail"
import HostImageExportModal from "../../components/HostImageExportModal"
import HostImageImportModal from "../../components/HostImageImportModal"
import {
    formatBytes,
    formatClock,
    formatPercent,
    formatTime,
    stateColor,
    stateLabel,
    usageColor
} from "../../components/container/utils"

const CONNECTION_LABELS = {local: '本机', tcp: '远程 TCP', ssh: 'SSH'}

function connectionLabel(host) {
    const type = host && host.connectionType
    const label = CONNECTION_LABELS[type] || CONNECTION_LABELS[(type || '').toLowerCase()] || type || '-'
    if (type === 'tcp' && /^https:\/\//i.test((host && host.dockerHost) || '')) {
        return '远程 TCP(HTTPS)'
    }
    return label
}

/** 自定义请求头展示：名称为空就是不发送，不臆造默认名称 */
function headerLabel(host) {
    if (!host || host.connectionType !== 'tcp') {
        return '-'
    }
    const name = host.headerName
    if (!name) {
        return host.headerConfigured ? '未设置名称（不会发送）' : '未配置'
    }
    return `${name} ${host.headerConfigured ? '已配置' : '未配置'}`
}

/** 连接代理展示：后端回显的是掩码后的一行地址（密码为 ***） */
function proxyLabel(host) {
    if (!host || host.connectionType !== 'tcp') {
        return '-'
    }
    return host.proxyUrl || '未使用'
}

function endpointOf(host) {
    if (!host) {
        return '-'
    }
    const type = host.connectionType
    if (type === 'ssh') {
        return `ssh://${host.sshUser || 'root'}@${host.sshHost}:${host.sshPort || 22}`
    }
    if (type === 'local') {
        return host.dockerHost || '本机（默认）'
    }
    return host.dockerHost || '-'
}

/**
 * 主机详情：主机信息 + 垂直 Tab「容器 / 实时资源 / 镜像」，点击容器弹窗展示通用容器组件。
 */
export default class extends React.Component {

    importModalRef = React.createRef()
    exportModalRef = React.createRef()

    /** 「实时资源」Tab 的轮询定时器：只在 Tab 激活且开启自动刷新时存在 */
    usageTimer = null

    state = {
        id: null,
        host: null,
        hostLoading: true,

        info: null,
        infoError: null,

        containers: [],
        containersLoading: false,

        images: [],
        imagesLoading: false,
        imageKeyword: '',

        // 实时用量（docker stats）：数据量大且要连所有容器，按需加载
        usage: null,
        usageLoading: false,
        usageError: null,
        usageUpdatedAt: null,
        usageAuto: true,
        usageInterval: 5,

        detail: {open: false, containerId: null, name: ''},
        activeTab: 'containers',
    }

    componentDidMount() {
        const id = PageUtils.currentParams().id
        this.setState({id})
        if (!id) {
            this.setState({hostLoading: false, infoError: '缺少主机 id'})
            return
        }
        this.loadHost(id)
        this.loadInfo(id)
        this.loadContainers(id)
        this.loadImages(id)
    }

    componentDidUpdate(prevProps, prevState) {
        if (prevState.activeTab !== this.state.activeTab
            || prevState.usageAuto !== this.state.usageAuto
            || prevState.usageInterval !== this.state.usageInterval) {
            this.syncUsageTimer()
        }
    }

    componentWillUnmount() {
        this.clearUsageTimer()
    }

    loadHost = id => {
        HttpClient.get('admin/host/get', {id}, {toastError: false})
            .then(rs => this.setState({host: rs.data, hostLoading: false}))
            .catch(() => this.setState({hostLoading: false}))
    }

    loadInfo = id => {
        HttpClient.get('admin/host/info', {id}, {toastError: false})
            .then(rs => this.setState({info: rs.data, infoError: null}))
            .catch(e => this.setState({info: null, infoError: e.message || '无法连接主机'}))
    }

    loadContainers = id => {
        this.setState({containersLoading: true})
        HttpClient.get('admin/container/list', {hostId: id, all: true}, {toastError: false})
            .then(rs => this.setState({containers: rs.data || []}))
            .catch(() => this.setState({containers: []}))
            .finally(() => this.setState({containersLoading: false}))
    }

    loadImages = id => {
        this.setState({imagesLoading: true})
        HttpClient.get('admin/host/images', {id}, {toastError: false})
            .then(rs => this.setState({images: rs.data || []}))
            .catch(() => this.setState({images: []}))
            .finally(() => this.setState({imagesLoading: false}))
    }

    /**
     * 采集实时用量（等价 docker stats）：后端每轮都会现连主机，单轮以秒计，
     * 因此只在「实时资源」Tab 激活时按设定间隔轮询，页面隐藏时跳过。
     */
    loadUsage = (id = this.state.id, refresh = false) => {
        if (!id) {
            return
        }
        this.setState({usageLoading: true})
        HttpClient.get('admin/host/usage', refresh ? {id, refresh: true} : {id}, {toastError: false})
            .then(rs => this.setState({usage: rs.data, usageError: null, usageUpdatedAt: Date.now()}))
            .catch(e => this.setState({usageError: e.message || '采集失败'}))
            .finally(() => this.setState({usageLoading: false}))
    }

    clearUsageTimer = () => {
        if (this.usageTimer) {
            clearInterval(this.usageTimer)
            this.usageTimer = null
        }
    }

    /** 切 Tab、改刷新开关或间隔时重建定时器；首次进入该 Tab 立即采集一次 */
    syncUsageTimer = () => {
        this.clearUsageTimer()
        if (this.state.activeTab !== 'stats' || !this.state.id) {
            return
        }
        if (!this.state.usage) {
            this.loadUsage(this.state.id, false)
        }
        if (!this.state.usageAuto) {
            return
        }
        this.usageTimer = setInterval(() => {
            if (!document.hidden) {
                this.loadUsage(this.state.id, false)
            }
        }, Math.max(2, this.state.usageInterval) * 1000)
    }

    /**
     * 删除镜像：image 为 repo:tag 时删除单个标签，为镜像 ID 时删除整个镜像。
     */
    removeImage = image => {
        const id = this.state.id
        HttpClient.post('admin/host/removeImage', null, {id, image})
            .then(() => this.loadImages(id))
    }

    pruneImages = () => {
        const id = this.state.id
        HttpClient.post('admin/host/pruneImages', null, {id})
            .then(() => {
                this.loadImages(id)
                this.loadInfo(id)
            })
    }

    openContainer = record => {
        this.setState({detail: {open: true, containerId: record.id, name: record.name}})
    }

    closeContainer = () => {
        this.setState({detail: {open: false, containerId: null, name: ''}})
        if (this.state.id) {
            this.loadContainers(this.state.id)
        }
    }

    /**
     * 「实时资源」Tab：docker stats 版式（主机汇总 + 逐容器 CPU / 内存 / 网络 / 块 I/O / PID）。
     */
    renderUsage() {
        const {usage, usageLoading, usageError, usageAuto, usageInterval, usageUpdatedAt} = this.state

        const toolbar = <div className='mb-3'
                             style={{display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap'}}>
            <Space size={4}>
                <Typography.Text type='secondary'>自动刷新</Typography.Text>
                <Switch size='small' checked={usageAuto} onChange={v => this.setState({usageAuto: v})}/>
            </Space>
            <Select size='small' style={{width: 96}} value={usageInterval}
                    onChange={v => this.setState({usageInterval: v})}
                    options={[{value: 2, label: '2 秒'}, {value: 5, label: '5 秒'}, {value: 10, label: '10 秒'}]}/>
            <Button size='small' icon={<ReloadOutlined/>} loading={usageLoading}
                    onClick={() => this.loadUsage(this.state.id, true)}>刷新</Button>
            {usageUpdatedAt && <Typography.Text type='secondary' style={{fontSize: 12}}>
                更新于 {formatClock(usageUpdatedAt)}
                {usage && usage.durationMillis != null ? `，耗时 ${usage.durationMillis} ms` : ''}
            </Typography.Text>}
        </div>

        if (usageError) {
            return <>
                {toolbar}
                <Result status='warning' title='实时用量采集失败' subTitle={usageError} style={{padding: 0}}/>
            </>
        }
        if (!usage) {
            return <>{toolbar}<div style={{padding: 32, textAlign: 'center'}}><Spin/></div></>
        }
        if (!usage.reachable) {
            return <>
                {toolbar}
                <Result status='warning' title='无法采集实时用量'
                        subTitle={usage.error || '主机可能已离线'} style={{padding: 0}}/>
            </>
        }

        const usageColumns = [
            {
                title: '容器', dataIndex: 'name', width: 260,
                render: (name, record) => <Space size={4}>
                    <Button type='link' size='small' style={{padding: 0, height: 'auto'}}
                            onClick={() => this.openContainer(record)}>{name}</Button>
                    {record.error &&
                        <Tooltip title={record.error}><Tag color='red'>采集失败</Tag></Tooltip>}
                </Space>
            },
            {title: 'CPU', dataIndex: 'cpuPercent', width: 90, render: v => formatPercent(v)},
            {
                title: '内存使用', width: 190,
                render: (_, record) => `${formatBytes(record.memUsage)} / ${formatBytes(record.memLimit)}`
            },
            {
                title: '内存%', dataIndex: 'memPercent', width: 110,
                render: v => v == null
                    ? '-'
                    : <Progress percent={Math.min(100, v)} size='small' strokeColor={usageColor(v)}/>
            },
            {
                title: '网络 I/O', width: 190,
                render: (_, record) => record.netRx == null && record.netTx == null
                    ? '-'
                    : `↓ ${formatBytes(record.netRx)}　↑ ${formatBytes(record.netTx)}`
            },
            {
                title: '块 I/O', width: 190,
                render: (_, record) => record.blockRead == null && record.blockWrite == null
                    ? '-'
                    : `读 ${formatBytes(record.blockRead)}　写 ${formatBytes(record.blockWrite)}`
            },
            {title: 'PID', dataIndex: 'pids', width: 80, render: v => v ?? '-'},
        ]

        return <>
            {toolbar}

            <Row gutter={[16, 8]} className='mb-3'>
                <Col span={6}>
                    <Statistic title='CPU 使用率'
                               value={usage.cpuPercent == null ? '-' : usage.cpuPercent.toFixed(1)}
                               suffix={usage.cpuPercent == null ? null : '%'}/>
                    <Progress percent={usage.cpuPercent == null ? 0 : usage.cpuPercent} showInfo={false}
                              size='small'
                              strokeColor={usage.cpuPercent == null ? '#d9d9d9' : usageColor(usage.cpuPercent)}/>
                    <Typography.Text type='secondary' style={{fontSize: 12}}>
                        {usage.cpuCoresUsed == null ? '容器合计 -' : `容器合计 ${usage.cpuCoresUsed.toFixed(2)} 核`}
                        {usage.cpus ? ` / 主机 ${usage.cpus} 核` : ''}
                    </Typography.Text>
                </Col>
                <Col span={6}>
                    <Statistic title='容器内存合计' value={formatBytes(usage.memUsed)}
                               suffix={usage.memoryTotal ? `/ ${formatBytes(usage.memoryTotal)}` : null}/>
                    <Progress percent={usage.memPercent == null ? 0 : usage.memPercent} showInfo={false}
                              size='small'
                              strokeColor={usage.memPercent == null ? '#d9d9d9' : usageColor(usage.memPercent)}/>
                    <Typography.Text type='secondary' style={{fontSize: 12}}>
                        占主机 {formatPercent(usage.memPercent)}
                    </Typography.Text>
                </Col>
                <Col span={6}>
                    <Statistic title='运行容器'
                               value={usage.containersRunning == null
                                   ? (usage.containers || []).length
                                   : usage.containersRunning}/>
                </Col>
                <Col span={6}>
                    <Statistic title='采集耗时' value={usage.durationMillis ?? '-'} suffix='ms'/>
                </Col>
            </Row>

            <Table rowKey='id' size='small' dataSource={usage.containers || []}
                   columns={usageColumns}
                   scroll={{x: 'max-content'}}
                   pagination={{pageSize: 20, showSizeChanger: true}}
                   locale={{emptyText: <Empty description='主机上没有运行中的容器'/>}}
                   onRow={record => ({onDoubleClick: () => this.openContainer(record)})}/>
        </>
    }

    render() {
        const {host, hostLoading, info, infoError, containers, containersLoading,
            images, imagesLoading, imageKeyword, detail, activeTab} = this.state

        if (hostLoading) {
            return <Page padding><Spin/></Page>
        }
        if (!this.state.id) {
            return <Page padding><Result status='warning' title='缺少主机 id'/></Page>
        }

        const containerColumns = [
            {
                title: '名称', dataIndex: 'name', width: 240,
                // 不可点击（打开容器走「操作」列或双击行），超长名称换行显示，避免撑破列宽压到镜像列
                render: name => <span style={{wordBreak: 'break-all', whiteSpace: 'normal'}}>{name}</span>
            },
            {title: '镜像', dataIndex: 'image', ellipsis: true},
            {
                title: '状态', dataIndex: 'state', width: 220,
                render: (state, record) => <>
                    <Tag color={stateColor(state)}>{stateLabel(state)}</Tag>
                    <Typography.Text type='secondary'>{record.status}</Typography.Text>
                </>
            },
            {
                title: '端口', width: 220,
                render: (_, record) => (record.ports || [])
                    .filter(p => p.publicPort)
                    .map(p => `${p.publicPort}→${p.privatePort}/${p.protocol}`)
                    .join(', ') || '-'
            },
            {title: '创建时间', dataIndex: 'created', width: 170, render: v => formatTime(v)},
            {
                title: '操作', width: 90,
                render: (_, record) => <Button size='small' type='link' onClick={() => this.openContainer(record)}>查看</Button>
            },
        ]

        const canImage = PermUtils.hasPermission('host:image')
        const keyword = imageKeyword.trim().toLowerCase()
        const filteredImages = !keyword ? images : images.filter(img =>
            (img.repoTags || []).some(t => t.toLowerCase().includes(keyword)) ||
            (img.idShort || '').toLowerCase().includes(keyword))

        const imageColumns = [
            {
                title: '仓库 / 标签', dataIndex: 'repoTags',
                render: (tags, record) => {
                    if (!tags || !tags.length) {
                        return <Space size={4}>
                            <Tag>无标签</Tag>
                            {canImage &&
                                <Popconfirm title='确定删除该镜像？删除后不可恢复'
                                            onConfirm={() => this.removeImage(record.id)}>
                                    <Button type='link' size='small' danger icon={<DeleteOutlined/>}/>
                                </Popconfirm>}
                        </Space>
                    }
                    return tags.map(t => <div key={t} style={{display: 'flex', alignItems: 'center', gap: 4}}>
                        <span style={{flex: 1, wordBreak: 'break-all'}}>{t}</span>
                        {canImage &&
                            <Popconfirm
                                title={tags.length === 1
                                    ? `确定删除镜像 ${t}？这是最后一个标签，将删除整个镜像`
                                    : `确定删除标签 ${t}？`}
                                onConfirm={() => this.removeImage(t)}>
                                <Button type='link' size='small' danger icon={<DeleteOutlined/>}/>
                            </Popconfirm>}
                    </div>)
                }
            },
            {title: '镜像ID', dataIndex: 'idShort', width: 160},
            {title: '大小', dataIndex: 'size', width: 120, render: v => formatBytes(v)},
            {title: '创建时间', dataIndex: 'created', width: 170, render: v => formatTime(v)},
            {title: '容器数', dataIndex: 'containers', width: 90, render: v => v ?? '-'},
            {
                title: '操作', width: 90,
                render: (_, record) => canImage
                    ? <Button type='link' size='small' icon={<ExportOutlined/>}
                              onClick={() => this.exportModalRef.current.show(record)}>导出</Button>
                    : null
            },
        ]

        const imageToolbar = <div className='mb-3' style={{display: 'flex', alignItems: 'center', gap: 8}}>
            <Input allowClear placeholder='搜索仓库 / 标签 / 镜像ID'
                   style={{width: 280}}
                   value={imageKeyword}
                   onChange={e => this.setState({imageKeyword: e.target.value})}/>
            <Button icon={<ReloadOutlined/>} onClick={() => this.loadImages(this.state.id)}>刷新</Button>
        </div>

        const items = [
            {
                key: 'containers',
                label: `容器 (${containers.length})`,
                children: <Table rowKey='id' size='small' loading={containersLoading}
                                 dataSource={containers} columns={containerColumns}
                                 pagination={{pageSize: 20, showSizeChanger: true}}
                                 locale={{emptyText: <Empty description='暂无容器'/>}}
                                 onRow={record => ({onDoubleClick: () => this.openContainer(record)})}/>
            },
            {
                key: 'stats',
                label: '实时资源',
                children: this.renderUsage()
            },
            {
                key: 'images',
                label: `镜像 (${images.length})`,
                children: <>
                    {imageToolbar}
                    <Table rowKey='id' size='small' loading={imagesLoading}
                           dataSource={filteredImages} columns={imageColumns}
                           pagination={{pageSize: 20, showSizeChanger: true}}
                           locale={{emptyText: <Empty description={keyword ? '无匹配镜像' : '暂无镜像'}/>}}/>
                </>
            },
        ]

        return <Page padding>
            <Card className='mb-4' title={host ? host.name : '主机'}
                  extra={canImage
                      ? <Space size={8}>
                          <Button icon={<ImportOutlined/>}
                                  onClick={() => this.importModalRef.current.show()}>导入 tar 镜像</Button>
                          <Popconfirm title='清理悬空（无标签）镜像？正在被使用的镜像不会被清理'
                                      onConfirm={this.pruneImages}>
                              <Button danger>清理悬空镜像</Button>
                          </Popconfirm>
                      </Space>
                      : null}>
                {infoError && <Result status='warning' title='无法连接主机' subTitle={infoError}
                                     className='mb-3' style={{padding: 0}}/>}
                <Descriptions size='small' column={2}
                              items={[
                                  {key: 'name', label: '名称', children: host?.name || '-'},
                                  {key: 'ip', label: 'IP地址', children: host?.ip || '-'},
                                  {key: 'conn', label: '连接方式', children: connectionLabel(host)},
                                  {key: 'header', label: '自定义请求头', children: headerLabel(host)},
                                  {key: 'proxy', label: '连接代理', children: proxyLabel(host), span: 2},
                                  {key: 'endpoint', label: '连接地址', children: endpointOf(host), span: 2},
                                  {key: 'version', label: 'Docker 版本', children: info?.dockerVersion || '-'},
                                  {key: 'api', label: 'API 版本', children: info?.apiVersion || '-'},
                                  {key: 'os', label: '操作系统', children: info?.os || '-'},
                                  {key: 'arch', label: '架构', children: info?.arch || '-'},
                                  {
                                      key: 'containers', label: '容器数',
                                      children: info ? `${info.containersRunning || 0} 运行 / ${info.containersTotal || 0} 总计` : '-'
                                  },
                                  {key: 'images', label: '镜像数', children: info ? info.images : '-'},
                                  {key: 'mem', label: '内存', children: info?.memory ? formatBytes(info.memory) : '-'},
                                  {key: 'cpu', label: 'CPU', children: info?.cpus || '-'},
                              ]}/>
            </Card>

            <Card>
                <Tabs activeKey={activeTab} items={items}
                      onChange={key => this.setState({activeTab: key})}/>
            </Card>

            <HostImageImportModal ref={this.importModalRef}
                                  hostId={this.state.id}
                                  onSuccess={() => {
                                      this.loadImages(this.state.id)
                                      this.loadInfo(this.state.id)
                                  }}/>

            <HostImageExportModal ref={this.exportModalRef} hostId={this.state.id}/>

            <Modal title={`容器 - ${detail.name}`}
                   open={detail.open}
                   onCancel={this.closeContainer}
                   footer={null}
                   width={1100}
                   destroyOnHidden
                   styles={{body: {minHeight: 520}}}>
                {detail.open && detail.containerId &&
                    <ContainerDetail hostId={this.state.id} containerId={detail.containerId}
                                     onClose={this.closeContainer}/>}
            </Modal>
        </Page>
    }
}
