import {Alert, Button, Card, Col, Descriptions, Empty, Input, Popconfirm, Progress, Result, Row, Segmented, Space, Spin, Statistic, Table, Tabs, Tag, Tooltip, Typography} from 'antd'
import React from 'react'
import dayjs from 'dayjs'
import {DeleteOutlined, ExportOutlined, ImportOutlined, ReloadOutlined} from '@ant-design/icons'
import {HttpClient, Page, PageUtils, PermUtils} from "@jiangood/open-admin"
import Chart from "../../components/Chart"
import HostImageExportModal from "../../components/HostImageExportModal"
import HostImageImportModal from "../../components/HostImageImportModal"
import LinkButton from "../../components/LinkButton"
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

export const pageMeta = {
    title: '主机详情',
}

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

/**
 * 连接代理展示：后端回显的是掩码后的一行地址（密码为 ***）。
 * 只在真正启用了代理时才展示（调用方用 host.proxyUrl 判断），未使用时这一行不占位置。
 */
function proxyLabel(host) {
    return (host && host.proxyUrl) || '-'
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
 * 主机详情：主机信息 + 垂直 Tab「容器 / 镜像 / 监控」，点击容器弹窗展示通用容器组件。
 * Agent 是独立页面，入口只在主机列表的「Agent」按钮。
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

        // 实时用量（docker stats）：由后端每 10 分钟的采集任务写入内存快照，页面只读
        usage: null,
        usageLoading: false,
        usageError: null,
        usageUpdatedAt: null,

        // 历史曲线：只含主机级数字，切换范围时才请求
        usageRange: '24h',
        series: null,
        seriesLoading: false,
        seriesError: null,

        activeTab: 'containers',
    }

    /** onShow 首次调用与 componentDidMount 重叠，跳过第一次，只在从容器页切回来时刷新 */
    shownOnce = false

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
        // 默认停在「容器」Tab；「监控」在最后，切过去时才拉快照与曲线并开始轮询
        this.syncUsageTimer(id)
    }

    /**
     * 从容器详情页切回本页时刷新容器列表与主机信息：
     * 停止 / 删除 / 重启都已经在容器页里完成，列表不能还是离开时的旧状态。
     * 监控数据由定时采集任务维护，不在这里重复拉取。
     */
    onShow() {
        if (!this.shownOnce) {
            this.shownOnce = true
            return
        }
        const id = this.state.id
        if (!id) {
            return
        }
        this.loadHost(id)
        this.loadInfo(id)
        this.loadContainers(id)
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

    loadInfo = id => {
        HttpClient.get('admin/host/info', {id}, {toastError: false})
            .then(rs => this.setState({info: rs.data, infoError: null}))
            .catch(e => this.setState({info: null, infoError: e.message || '无法连接主机'}))
    }

    loadContainers = id => {
        this.setState({containersLoading: true})
        return HttpClient.get('admin/container/list', {hostId: id, all: true}, {toastError: false})
            .then(rs => {
                const list = rs.data || []
                this.setState({containers: list})
                return list
            })
            .catch(() => {
                this.setState({containers: []})
                return []
            })
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
     * 读取实时用量：默认读后端每 10 分钟采集好的快照（不触发采集），refresh=true 才让后端现采一轮。
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

    /**
     * 打开容器详情页（独立页签）。tab 为页面的页签：logs / info / files / console，
     * 列表里的「概览 / 日志 / 文件 / 终端」按钮据此直达对应页签。
     * 停止 / 删除等操作也都在容器页里做，列表只负责跳转。
     */
    openContainer = (record, tab = 'logs') => {
        const id = this.state.id
        if (!id || !record) {
            return
        }
        PageUtils.open(`/container/view?hostId=${encodeURIComponent(id)}`
            + `&containerId=${encodeURIComponent(record.id)}`
            + `&tab=${encodeURIComponent(tab)}`)
    }

    /**
     * 刷新当前 Tab：容器 / 镜像 / 监控的数据在父组件 state 里，重取一次即可。
     */
    refreshTab = () => {
        const {id, activeTab, usageRange} = this.state
        if (!id) {
            return
        }
        if (activeTab === 'containers') {
            this.loadContainers(id)
        } else if (activeTab === 'images') {
            this.loadImages(id)
        } else if (activeTab === 'stats') {
            // 监控：refresh=true 让后端现采一轮，同时重读历史曲线
            this.loadUsage(id, true)
            this.loadSeries(id, usageRange)
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
     * 实时用量：概览卡片（主机汇总）+ 容器用量表格卡片，数据来自后端每 10 分钟的采集快照。
     */
    renderLiveUsage() {
        const {usage, usageLoading, usageError, usageUpdatedAt} = this.state

        const extra = <Space size={8}>
            {usageLoading && <Spin size='small'/>}
            {usageUpdatedAt && <Typography.Text type='secondary' style={{fontSize: 12}}>
                采集于 {formatClock(usageUpdatedAt)}
            </Typography.Text>}
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
                    : <Progress percent={Math.min(100, toNumber(v))} size='small'
                                strokeColor={usageColor(v)}
                                format={() => formatPercent(v)}/>
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
                       locale={{emptyText: <Empty description='暂无运行中的容器'/>}}/>
            </Card>}
        </>
    }

    render() {
        const {host, hostLoading, info, infoError, containers, containersLoading,
            images, imagesLoading, imageKeyword, activeTab, usageLoading} = this.state

        if (hostLoading) {
            return <Page padding><Spin/></Page>
        }
        if (!this.state.id) {
            return <Page padding><Result status='warning' title='缺少主机 id'/></Page>
        }

        const canContainerFile = PermUtils.hasPermission('container:file')
        const canContainerExec = PermUtils.hasPermission('container:exec')

        const containerColumns = [
            {
                title: '名称', dataIndex: 'name', width: 240,
                // 点击名称打开容器详情；超长名称换行显示，避免撑破列宽压到镜像列
                render: (name, record) => <Button type='link' size='small' style={{padding: 0, height: 'auto', whiteSpace: 'normal', textAlign: 'left'}}
                                                  onClick={() => this.openContainer(record)}>{name}</Button>
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
                // 四个入口都跳转到容器详情页：概览 / 日志只需查看权限（host:list），
                // 文件 / 终端分别要求 container:file / container:exec；停止 / 删除在容器页里做
                title: '操作', width: 170,
                render: (_, record) => <Space size={4}>
                    <LinkButton size='small'
                                onClick={() => this.openContainer(record, 'info')}>概览</LinkButton>
                    <LinkButton size='small'
                                onClick={() => this.openContainer(record, 'logs')}>日志</LinkButton>
                    {canContainerFile &&
                        <LinkButton size='small'
                                    onClick={() => this.openContainer(record, 'files')}>文件</LinkButton>}
                    {canContainerExec &&
                        <LinkButton size='small'
                                    onClick={() => this.openContainer(record, 'console')}>终端</LinkButton>}
                </Space>
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

        // 镜像操作（导入 / 清理悬空）跟着镜像数据走，按钮放在「镜像」Tab 的工具条里，
        // 基本信息卡只保留信息，不再混入操作按钮。
        const imageToolbar = <div className='mb-3'
                                  style={{display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap'}}>
            <Input allowClear placeholder='搜索仓库 / 标签 / 镜像ID'
                   style={{width: 280}}
                   value={imageKeyword}
                   onChange={e => this.setState({imageKeyword: e.target.value})}/>
            {canImage && <Space size={8} style={{marginLeft: 'auto'}}>
                <Button icon={<ImportOutlined/>}
                        onClick={() => this.importModalRef.current.show()}>导入 tar 镜像</Button>
                <Popconfirm title='清理悬空（无标签）镜像？正在被使用的镜像不会被清理'
                            onConfirm={this.pruneImages}>
                    <Button danger>清理悬空镜像</Button>
                </Popconfirm>
            </Space>}
        </div>

        const items = [
            {
                key: 'containers',
                label: `容器 (${containers.length})`,
                children: <Table rowKey='id' size='small' loading={containersLoading}
                                 dataSource={containers} columns={containerColumns}
                                 pagination={{pageSize: 20, showSizeChanger: true}}
                                 locale={{emptyText: <Empty description='暂无容器'/>}}/>
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
                key: 'stats',
                label: '监控',
                children: this.renderUsage()
            },
        ]

        const tabLoading = activeTab === 'containers' ? containersLoading
            : activeTab === 'images' ? imagesLoading
                : activeTab === 'stats' ? usageLoading
                    : false

        /**
         * 基本信息卡的所有字段都放进同一张带边框的三列表格：
         * - 标签列宽度固定、值列等宽，连接信息与引擎信息从此左右对齐，不再是两块各自排版的 Descriptions；
         * - 连接地址 / 代理 / 请求头 / Agent 目录这类长文本独占一行，避免把某一列撑歪；
         * - 名称已由 Card title 展示，代理与请求头只在真正配置后才占行。
         */
        const isTcp = (host && host.connectionType) === 'tcp'
        const infoItems = [
            {key: 'conn', label: '连接方式', children: connectionLabel(host)},
            {key: 'ip', label: 'IP 地址', children: host?.ip || '-'},
            {key: 'agentTag', label: 'Agent 镜像 Tag', children: host?.agentImageTag || 'latest'},
            {
                key: 'endpoint', label: '连接地址', span: 3,
                children: <span className='mono'>{endpointOf(host)}</span>
            },
            ...(isTcp && host?.proxyUrl
                ? [{key: 'proxy', label: '连接代理', children: proxyLabel(host), span: 3}]
                : []),
            ...(isTcp && host?.headerConfigured
                ? [{key: 'header', label: '自定义请求头', children: headerLabel(host), span: 3}]
                : []),
            ...(host?.fileRoot
                ? [{key: 'fileRoot', label: 'Agent 目录', children: <span className='mono'>{host.fileRoot}</span>, span: 3}]
                : []),
            {key: 'os', label: '操作系统', children: info?.os || '-'},
            {key: 'arch', label: '架构', children: info?.arch || '-'},
            {key: 'cpu', label: 'CPU 核数', children: info?.cpus || '-'},
            {key: 'version', label: 'Docker 版本', children: info?.dockerVersion || '-'},
            {key: 'api', label: 'API 版本', children: info?.apiVersion || '-'},
            {key: 'kernel', label: '内核版本', children: info?.kernelVersion || '-'},
            {key: 'mem', label: '内存', children: info?.memory ? formatBytes(info.memory) : '-'},
            {
                key: 'containers', label: '容器数',
                children: info ? `${info.containersRunning || 0} 运行 / ${info.containersTotal || 0} 总计` : '-'
            },
            {key: 'images', label: '镜像数', children: info ? info.images : '-'},
        ]

        return <Page padding>
            <Card className='mb-4' title={host ? host.name : '主机'}>
                {infoError && <Alert type='warning' showIcon className='mb-4'
                                     message='无法连接主机' description={infoError}/>}
                <Descriptions size='small' column={3} bordered
                              items={infoItems}
                              styles={{
                                  label: {width: 132, whiteSpace: 'nowrap'},
                                  content: {overflowWrap: 'anywhere'},
                              }}/>
            </Card>

            <Card>
                <Tabs activeKey={activeTab} items={items}
                      onChange={key => this.setState({activeTab: key})}
                      tabBarExtraContent={<Button size='small' icon={<ReloadOutlined/>}
                                                  loading={tabLoading}
                                                  onClick={this.refreshTab}>刷新</Button>}/>
            </Card>

            <HostImageImportModal ref={this.importModalRef}
                                  hostId={this.state.id}
                                  onSuccess={() => {
                                      this.loadImages(this.state.id)
                                      this.loadInfo(this.state.id)
                                  }}/>

            <HostImageExportModal ref={this.exportModalRef} hostId={this.state.id}/>
        </Page>
    }
}
