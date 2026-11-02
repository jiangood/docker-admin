import {Alert, Button, Card, Col, Descriptions, Empty, Input, Modal, Popconfirm, Progress, Result, Row, Segmented, Space, Spin, Statistic, Table, Tabs, Tag, Tooltip, Typography} from 'antd'
import React from 'react'
import dayjs from 'dayjs'
import {DeleteOutlined, ExportOutlined, ImportOutlined, ReloadOutlined} from '@ant-design/icons'
import {HttpClient, Page, PageUtils, PermUtils} from "@jiangood/open-admin"
import Chart from "../../components/Chart"
import ContainerDetail from "../../components/container/ContainerDetail"
import HostImageExportModal from "../../components/HostImageExportModal"
import HostImageImportModal from "../../components/HostImageImportModal"
import HostAgentTab from "../../components/host/HostAgentTab"
import {
    formatBytes,
    formatClock,
    formatPercent,
    formatTime,
    stateColor,
    stateLabel,
    toNumber,
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
 * 历史曲线的可选范围，与后端 {@code HostUsageSampleService.Range} 一一对应。
 */
const USAGE_RANGES = [
    {label: '1 小时', value: '1h'},
    {label: '6 小时', value: '6h'},
    {label: '24 小时', value: '24h'},
    {label: '7 天', value: '7d'},
    {label: '30 天', value: '30d'},
]

/** 实时用量的轮询间隔：只是「多久看到新数字」，不会增加主机负载（读的是后端快照）。 */
const USAGE_POLL_INTERVAL = 30 * 1000

/**
 * 曲线配置：时间轴 + 折线，值格式化交给调用方（百分比 / 字节速率）。
 * 累计计数器在容器重启时会归零，后端已把那一小段置空，这里靠 connectNulls=false 断开。
 */
function buildTrendOption({points, defs, percent, axisFormatter, timePattern}) {
    return {
        animation: false,
        grid: {left: 4, right: 12, top: 32, bottom: 4, containLabel: true},
        tooltip: {
            trigger: 'axis',
            confine: true,
            valueFormatter: value => value == null || value === ''
                ? '-'
                : (percent ? `${Number(value).toFixed(1)}%` : formatBytes(value)),
        },
        legend: {top: 0, left: 0, icon: 'roundRect', itemWidth: 10, itemHeight: 10, textStyle: {fontSize: 12}},
        xAxis: {
            type: 'time',
            axisLabel: {fontSize: 11, hideOverlap: true, formatter: value => dayjs(value).format(timePattern)},
            splitLine: {show: false},
        },
        yAxis: {
            type: 'value',
            min: 0,
            max: percent ? 100 : undefined,
            axisLabel: {fontSize: 11, formatter: axisFormatter},
            splitLine: {lineStyle: {type: 'dashed', color: '#f0f0f0'}},
        },
        series: defs.map(def => ({
            name: def.name,
            type: 'line',
            smooth: true,
            showSymbol: false,
            connectNulls: false,
            lineStyle: {width: 1.5},
            itemStyle: {color: def.color},
            data: points.map(point => [point.t, point[def.key]]),
        })),
    }
}

const TREND_CPU_DEFS = [
    {name: 'CPU', key: 'cpuPercent', color: '#1677ff'},
    {name: '内存', key: 'memPercent', color: '#52c41a'},
]

const TREND_IO_DEFS = [
    {name: '网络 ↓', key: 'netRxBps', color: '#13c2c2'},
    {name: '网络 ↑', key: 'netTxBps', color: '#faad14'},
    {name: '磁盘 读', key: 'readBps', color: '#722ed1'},
    {name: '磁盘 写', key: 'writeBps', color: '#eb2f96'},
]

/**
 * 主机详情：主机信息 + 垂直 Tab「监控 / 容器 / 镜像 / Agent」，点击容器弹窗展示通用容器组件。
 */
export default class extends React.Component {

    importModalRef = React.createRef()
    exportModalRef = React.createRef()

    /** 「监控」Tab 的轮询定时器：只在 Tab 激活时存在，页面隐藏时暂停请求 */
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

        // 实时用量（docker stats）：由后端每分钟的采集任务写入内存快照，页面只读
        usage: null,
        usageLoading: false,
        usageError: null,
        usageUpdatedAt: null,

        // 历史曲线：只含主机级数字，切换范围时才请求
        usageRange: '24h',
        series: null,
        seriesLoading: false,
        seriesError: null,

        detail: {open: false, containerId: null, name: ''},
        activeTab: 'stats',
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
        // 默认就停在「监控」Tab，直接拉一次快照与曲线并开始轮询
        this.syncUsageTimer(id)
    }

    componentDidUpdate(prevProps, prevState) {
        if (prevState.activeTab !== this.state.activeTab) {
            this.syncUsageTimer()
        }
        if (prevState.usageRange !== this.state.usageRange) {
            this.loadSeries(this.state.id, this.state.usageRange)
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

    /** Agent 当前挂载的目录就是文件管理目录，切换后同步到详情，避免显示成旧值 */
    syncAgentStatus = status => {
        const root = status && status.mountedRoot
        if (!root || !this.state.host || this.state.host.fileRoot === root) {
            return
        }
        this.setState(state => ({host: {...state.host, fileRoot: root}}))
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
     * 读取实时用量：默认读后端每分钟采集好的快照（不触发采集），refresh=true 才让后端现采一轮。
     * 因此这里的轮询间隔只影响「多久看到新数字」，不会增加主机侧负载。
     */
    loadUsage = (id = this.state.id, refresh = false) => {
        if (!id) {
            return
        }
        this.setState({usageLoading: true})
        HttpClient.get('admin/host/usage', refresh ? {id, refresh: true} : {id}, {toastError: false})
            .then(rs => this.setState({
                usage: rs.data,
                usageError: null,
                // 用后端采集时间，而不是本地收到响应的时间：快照最多可能是一分钟前的
                usageUpdatedAt: (rs.data && rs.data.collectedAt) || Date.now(),
            }))
            .catch(e => this.setState({usageError: e.message || '采集失败'}))
            .finally(() => this.setState({usageLoading: false}))
    }

    /**
     * 读取历史曲线：与实时用量分开，只在「监控」Tab 激活或切换时间范围时请求，不参与轮询。
     */
    loadSeries = (id = this.state.id, range = this.state.usageRange) => {
        if (!id) {
            return
        }
        this.setState({seriesLoading: true})
        HttpClient.get('admin/host/usageHistory', {id, range}, {toastError: false})
            .then(rs => this.setState({series: rs.data, seriesError: null}))
            .catch(e => this.setState({series: null, seriesError: e.message || '读取历史数据失败'}))
            .finally(() => this.setState({seriesLoading: false}))
    }

    clearUsageTimer = () => {
        if (this.usageTimer) {
            clearInterval(this.usageTimer)
            this.usageTimer = null
        }
    }

    /** 切 Tab 时重建定时器；首次进入该 Tab 立即读一次快照与曲线 */
    syncUsageTimer = (id = this.state.id) => {
        this.clearUsageTimer()
        if (this.state.activeTab !== 'stats' || !id) {
            return
        }
        if (!this.state.usage) {
            this.loadUsage(id, false)
        }
        if (!this.state.series) {
            this.loadSeries(id, this.state.usageRange)
        }
        this.usageTimer = setInterval(() => {
            if (!document.hidden) {
                this.loadUsage(id, false)
            }
        }, USAGE_POLL_INTERVAL)
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
     * 「监控」Tab：历史曲线在上，docker stats 版式（主机汇总 + 逐容器明细）在下。
     */
    renderUsage() {
        return <>
            {this.renderTrend()}
            {this.renderLiveUsage()}
        </>
    }

    /**
     * 历史曲线卡片：只在切换时间范围时请求，不跟着实时用量轮询。
     * 即使当前采集失败（主机离线），历史仍然能看——这正是落库的意义。
     */
    renderTrend() {
        const {series, seriesLoading, seriesError, usageRange} = this.state
        const points = (series && series.points) || []
        const timePattern = usageRange === '7d' || usageRange === '30d' ? 'MM-DD HH:mm' : 'HH:mm'

        const extra = <Space size={8}>
            {seriesLoading && points.length > 0 && <Spin size='small'/>}
            <Segmented size='small' value={usageRange} options={USAGE_RANGES}
                       onChange={v => this.setState({usageRange: v})}/>
        </Space>

        let body
        if (seriesLoading && points.length === 0) {
            body = <div className='text-center' style={{padding: '80px 0'}}><Spin/></div>
        } else if (seriesError) {
            body = <Alert type='warning' showIcon message='历史数据读取失败' description={seriesError}/>
        } else if (points.length === 0) {
            body = <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description='暂无历史数据'/>
        } else {
            body = <>
                <Chart height={220} option={buildTrendOption({
                    points, defs: TREND_CPU_DEFS, percent: true,
                    axisFormatter: v => `${v}%`, timePattern,
                })}/>
                <Chart height={200} option={buildTrendOption({
                    points, defs: TREND_IO_DEFS, percent: false,
                    axisFormatter: v => formatBytes(v), timePattern,
                })}/>
            </>
        }

        return <Card size='small' className='mb-4' title='资源趋势' extra={extra}>
            {body}
        </Card>
    }

    /**
     * 实时用量：概览卡片（主机汇总）+ 容器用量表格卡片，数据来自后端每分钟的采集快照。
     */
    renderLiveUsage() {
        const {usage, usageLoading, usageError, usageUpdatedAt} = this.state

        const extra = <Space size={8}>
            {usageUpdatedAt && <Typography.Text type='secondary' style={{fontSize: 12}}>
                采集于 {formatClock(usageUpdatedAt)}
            </Typography.Text>}
            <Button size='small' icon={<ReloadOutlined/>} loading={usageLoading}
                    onClick={() => this.loadUsage(this.state.id, true)}>刷新</Button>
        </Space>

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

        const reachable = usage && !usageError && usage.reachable

        let summary
        if (usageLoading && !usage) {
            summary = <div className='text-center' style={{padding: '48px 0'}}><Spin/></div>
        } else if (usageError) {
            summary = <Alert type='warning' showIcon message='实时用量采集失败' description={usageError}/>
        } else if (!usage) {
            summary = <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description='暂无数据'/>
        } else if (!usage.reachable) {
            summary = <Alert type='warning' showIcon message='无法采集实时用量'
                             description={usage.error || '主机可能已离线'}/>
        } else {
            summary = <Row gutter={[16, 8]}>
                <Col span={8}>
                    <Statistic title='CPU 使用率'
                               value={usage.cpuPercent == null ? '-' : toNumber(usage.cpuPercent).toFixed(1)}
                               suffix={usage.cpuPercent == null ? null : '%'}/>
                    <Progress percent={usage.cpuPercent == null ? 0 : toNumber(usage.cpuPercent)} showInfo={false}
                              size='small'
                              strokeColor={usage.cpuPercent == null ? '#d9d9d9' : usageColor(usage.cpuPercent)}/>
                    <Typography.Text type='secondary' style={{fontSize: 12}}>
                        {usage.cpuCoresUsed == null
                            ? '容器合计 -'
                            : `容器合计 ${toNumber(usage.cpuCoresUsed).toFixed(2)} 核`}
                        {usage.cpus ? ` / 主机 ${toNumber(usage.cpus)} 核` : ''}
                    </Typography.Text>
                </Col>
                <Col span={8}>
                    <Statistic title='容器内存合计' value={formatBytes(usage.memUsed)}
                               suffix={usage.memoryTotal ? `/ ${formatBytes(usage.memoryTotal)}` : null}/>
                    <Progress percent={usage.memPercent == null ? 0 : usage.memPercent} showInfo={false}
                              size='small'
                              strokeColor={usage.memPercent == null ? '#d9d9d9' : usageColor(usage.memPercent)}/>
                    <Typography.Text type='secondary' style={{fontSize: 12}}>
                        占主机 {formatPercent(usage.memPercent)}
                    </Typography.Text>
                </Col>
                <Col span={8}>
                    <Statistic title='运行容器'
                               value={usage.containersRunning == null
                                   ? (usage.containers || []).length
                                   : usage.containersRunning}/>
                </Col>
            </Row>
        }

        return <>
            <Card size='small' className='mb-4' title='实时用量' extra={extra}>
                {summary}
            </Card>

            {reachable && <Card size='small' title='容器用量'>
                <Table rowKey='id' size='small' dataSource={usage.containers || []}
                       columns={usageColumns}
                       scroll={{x: 'max-content'}}
                       pagination={{pageSize: 20, showSizeChanger: true}}
                       locale={{emptyText: <Empty description='暂无运行中的容器'/>}}
                       onRow={record => ({onDoubleClick: () => this.openContainer(record)})}/>
            </Card>}
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
                key: 'stats',
                label: '监控',
                children: this.renderUsage()
            },
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
            {
                key: 'agent',
                label: 'Agent',
                children: <HostAgentTab hostId={this.state.id} onStatus={this.syncAgentStatus}/>
            },
        ]

        // 主机信息 / 运行环境分成两块、各按 3 列排：原来 16 项排 2 列要 10 行，
        // 现在 6~7 行就够。代理与自定义请求头只对远程 TCP 有意义，其他连接方式不占行。
        const isTcp = (host && host.connectionType) === 'tcp'
        const hostItems = [
            {key: 'name', label: '名称', children: host?.name || '-'},
            {key: 'ip', label: 'IP 地址', children: host?.ip || '-'},
            {key: 'conn', label: '连接方式', children: connectionLabel(host)},
            {key: 'endpoint', label: '连接地址', children: endpointOf(host), span: 2},
            {key: 'agentTag', label: 'Agent 镜像 Tag', children: host?.agentImageTag || 'latest'},
            ...(isTcp ? [
                {key: 'proxy', label: '连接代理', children: proxyLabel(host), span: 2},
                {key: 'header', label: '自定义请求头', children: headerLabel(host)},
            ] : []),
            {key: 'fileRoot', label: '文件管理目录', children: host?.fileRoot || '/home', span: 3},
        ]
        const runtimeItems = [
            {key: 'os', label: '操作系统', children: info?.os || '-'},
            {key: 'arch', label: '架构', children: info?.arch || '-'},
            {key: 'cpu', label: 'CPU', children: info?.cpus || '-'},
            {key: 'version', label: 'Docker 版本', children: info?.dockerVersion || '-'},
            {key: 'api', label: 'API 版本', children: info?.apiVersion || '-'},
            {key: 'mem', label: '内存', children: info?.memory ? formatBytes(info.memory) : '-'},
            {
                key: 'containers', label: '容器数',
                children: info ? `${info.containersRunning || 0} 运行 / ${info.containersTotal || 0} 总计` : '-'
            },
            {key: 'images', label: '镜像数', children: info ? info.images : '-'},
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
                <Descriptions size='small' column={3} title='主机信息'
                              items={hostItems}
                              styles={{content: {overflowWrap: 'anywhere'}}}/>
                <Descriptions size='small' column={3} title='运行环境' className='mt-4'
                              items={runtimeItems}
                              styles={{content: {overflowWrap: 'anywhere'}}}/>
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
