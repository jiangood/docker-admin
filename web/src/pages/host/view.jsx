import {Alert, Button, Card, Checkbox, Col, Descriptions, Empty, Input, Modal, Popconfirm, Progress, Result, Row, Segmented, Space, Spin, Statistic, Table, Tabs, Tag, Tooltip, Typography} from 'antd'
import React from 'react'
import dayjs from 'dayjs'
import {DeleteOutlined, ExportOutlined, ImportOutlined, ReloadOutlined} from '@ant-design/icons'
import {HttpClient, Page, PageUtils, PermUtils} from "@jiangood/open-admin"
import Chart from "../../components/Chart"
import ContainerDetail from "../../components/container/ContainerDetail"
import HostImageExportModal from "../../components/HostImageExportModal"
import HostImageImportModal from "../../components/HostImageImportModal"
import {
    formatBytes,
    formatClock,
    formatPercent,
    formatTime,
    isRunningState,
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
        activeTab: 'containers',

        // 删除容器确认弹窗：force 仅在容器运行中时可选（普通删除会被 Docker 拒绝）
        removeTarget: null,
        removeForce: false,
        removing: false,

        // 正在停止的容器 id：只让那一行的按钮转圈
        stoppingId: null,
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
        // 默认停在「容器」Tab；「监控」在最后，切过去时才拉快照与曲线并开始轮询
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

    /**
     * 停止容器（主机维度）：无需二次确认（可随时再启动）。
     * 停止成功后稍等片刻再刷新，否则可能仍读到「运行中」的旧状态。
     */
    stopContainer = record => {
        const id = this.state.id
        if (!id || !record) {
            return
        }
        this.setState({stoppingId: record.id})
        HttpClient.post('admin/container/stop', null, {hostId: id, containerId: record.id})
            .then(() => {
                setTimeout(() => {
                    this.loadContainers(id)
                    this.loadInfo(id)
                }, 600)
            })
            .finally(() => this.setState({stoppingId: null}))
    }

    /**
     * 删除容器（主机维度）：先弹窗确认，运行中的容器必须勾选「强制删除」才能提交，
     * 否则 Docker 会拒绝（不能删除 running / paused / restarting 的容器）。
     * 成功后刷新容器列表与主机信息里的容器计数。
     */
    openRemoveContainer = record => {
        this.setState({removeTarget: record, removeForce: false, removing: false})
    }

    confirmRemoveContainer = () => {
        const {id, removeTarget, removeForce} = this.state
        if (!id || !removeTarget) {
            return
        }
        const force = isRunningState(removeTarget.state) ? removeForce : false
        this.setState({removing: true})
        HttpClient.post('admin/container/remove', null, {hostId: id, containerId: removeTarget.id, force})
            .then(() => {
                this.setState({removeTarget: null, removeForce: false})
                this.loadContainers(id)
                this.loadInfo(id)
            })
            .finally(() => this.setState({removing: false}))
    }

    closeRemoveContainer = () => {
        this.setState({removeTarget: null, removeForce: false, removing: false})
    }

    closeContainer = () => {
        this.setState({detail: {open: false, containerId: null, name: ''}})
        if (this.state.id) {
            this.loadContainers(this.state.id)
        }
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
     * 实时用量：概览卡片（主机汇总）+ 容器用量表格卡片，数据来自后端每分钟的采集快照。
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
            images, imagesLoading, imageKeyword, detail, activeTab, usageLoading,
            removeTarget, removeForce, removing, stoppingId} = this.state

        if (hostLoading) {
            return <Page padding><Spin/></Page>
        }
        if (!this.state.id) {
            return <Page padding><Result status='warning' title='缺少主机 id'/></Page>
        }

        const canContainerOperate = PermUtils.hasPermission('container:operate')

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
                title: '操作', width: 120,
                render: (_, record) => canContainerOperate
                    ? <Space size={4}>
                        <Button size='small'
                                disabled={!isRunningState(record.state)}
                                loading={stoppingId === record.id}
                                onClick={() => this.stopContainer(record)}>停止</Button>
                        <Button size='small' danger
                                onClick={() => this.openRemoveContainer(record)}>删除</Button>
                      </Space>
                    : null
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

        const removeRunning = !!removeTarget && isRunningState(removeTarget.state)

        // 顶部只留必要信息：名称由 Card title 展示，代理 / 请求头 / Agent 目录只在真正配置后才占行，
        // 所以两块 Descriptions 都不再带标题（标题只是重复），并按 3 列把长字段单独占满一行。
        const isTcp = (host && host.connectionType) === 'tcp'
        const hostItems = [
            {key: 'conn', label: '连接方式', children: connectionLabel(host)},
            {key: 'ip', label: 'IP 地址', children: host?.ip || '-'},
            {key: 'agentTag', label: 'Agent 镜像 Tag', children: host?.agentImageTag || 'latest'},
            {key: 'endpoint', label: '连接地址', children: endpointOf(host), span: 3},
            ...(isTcp && host?.proxyUrl
                ? [{key: 'proxy', label: '连接代理', children: proxyLabel(host), span: 3}]
                : []),
            ...(isTcp && host?.headerConfigured
                ? [{key: 'header', label: '自定义请求头', children: headerLabel(host), span: 3}]
                : []),
            ...(host?.fileRoot
                ? [{key: 'fileRoot', label: 'Agent 目录', children: host.fileRoot, span: 3}]
                : []),
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
                <Descriptions size='small' column={3}
                              items={hostItems}
                              styles={{content: {overflowWrap: 'anywhere'}}}/>
                <Descriptions size='small' column={3} className='mt-4'
                              items={runtimeItems}
                              styles={{content: {overflowWrap: 'anywhere'}}}/>
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

            {/* 删除容器：默认普通删除（force=false），运行中的容器必须显式勾选强制删除 */}
            <Modal title='删除容器'
                   open={!!removeTarget}
                   okText='删除'
                   cancelText='取消'
                   okButtonProps={{danger: true, loading: removing, disabled: removeRunning && !removeForce}}
                   onOk={this.confirmRemoveContainer}
                   onCancel={this.closeRemoveContainer}
                   destroyOnHidden
                   width={520}>
                {removeTarget && <>
                    <div>
                        确定删除容器 <Typography.Text strong>{removeTarget.name}</Typography.Text>？删除后不可恢复。
                    </div>
                    {removeRunning && <>
                        <Alert className='mt-3' type='warning' showIcon
                               title='容器正在运行，普通删除会被 Docker 拒绝'
                               description='勾选下方「强制删除」后平台会先停止（kill）容器再删除；也可以先点「停止」，等状态变成已退出后再删除。'/>
                        <Checkbox className='mt-3' checked={removeForce}
                                  onChange={e => this.setState({removeForce: e.target.checked})}>
                            强制删除（停止并删除运行中的容器）
                        </Checkbox>
                    </>}
                </>}
            </Modal>
        </Page>
    }
}
