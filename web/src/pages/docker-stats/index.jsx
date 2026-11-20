import {Card, Col, Empty, Row, Segmented, Select, Space, Spin, Table, Tabs, Tag, Typography} from 'antd'
import React from 'react'
import dayjs from 'dayjs'
import {HttpClient, Page} from '@jiangood/open-admin'
import Chart from '../../components/Chart'
import {formatBytes, formatClock, formatPercent, toNumber} from '../../components/container/utils'

export const pageMeta = {
    title: '资源统计',
}

const RANGES = [
    {label: '1 小时', value: '1h'},
    {label: '6 小时', value: '6h'},
    {label: '24 小时', value: '24h'},
    {label: '7 天', value: '7d'},
    {label: '30 天', value: '30d'},
]

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

function shortName(name, max = 18) {
    if (!name) return '-'
    return name.length > max ? name.slice(0, max) + '…' : name
}

function buildBarOption({title, items, valueKey, formatter, color}) {
    const data = [...(items || [])]
        .filter(i => toNumber(i[valueKey]) != null)
        .sort((a, b) => toNumber(b[valueKey]) - toNumber(a[valueKey]))
        .slice(0, 10)
        .reverse()
    return {
        animation: false,
        title: {text: title, left: 0, top: 0, textStyle: {fontSize: 13, fontWeight: 500}},
        grid: {left: 8, right: 20, top: 36, bottom: 8, containLabel: true},
        tooltip: {
            trigger: 'axis',
            axisPointer: {type: 'shadow'},
            formatter: params => {
                const p = params && params[0]
                if (!p) return ''
                const item = data[p.dataIndex]
                const label = item ? `${item.name || '-'}${item.hostName ? ' (' + item.hostName + ')' : ''}` : p.name
                return `${label}<br/>${title}: ${formatter(p.value)}`
            }
        },
        xAxis: {
            type: 'value',
            axisLabel: {fontSize: 11, formatter},
            splitLine: {lineStyle: {type: 'dashed', color: '#f0f0f0'}},
        },
        yAxis: {
            type: 'category',
            data: data.map(i => shortName(i.name)),
            axisLabel: {fontSize: 11, width: 120, overflow: 'truncate'},
        },
        series: [{
            type: 'bar',
            data: data.map(i => toNumber(i[valueKey]) || 0),
            itemStyle: {color: color || '#1677ff', borderRadius: [0, 3, 3, 0]},
            barMaxWidth: 22,
            label: {show: true, position: 'right', fontSize: 11, formatter: p => formatter(p.value)},
        }],
    }
}

export default class extends React.Component {

    state = {
        hosts: [],
        activeTab: 'latest',

        latestHostId: '',
        latest: [],
        latestLoading: false,

        hisHostId: '',
        hisRange: '24h',
        series: null,
        seriesLoading: false,
        seriesError: null,
    }

    componentDidMount() {
        this.loadHosts()
        this.loadLatest()
    }

    componentDidUpdate(prevProps, prevState) {
        if (prevState.latestHostId !== this.state.latestHostId) {
            this.loadLatest()
        }
        if (prevState.hisHostId !== this.state.hisHostId || prevState.hisRange !== this.state.hisRange) {
            if (this.state.hisHostId) this.loadSeries()
        }
    }

    loadHosts = () => {
        HttpClient.get('admin/host/options', {}, {toastError: false})
            .then(rs => {
                const hosts = rs.data || []
                this.setState(s => ({
                    hosts,
                    hisHostId: s.hisHostId || (hosts[0] ? hosts[0].value : ''),
                }), () => {
                    if (this.state.hisHostId) this.loadSeries()
                })
            })
            .catch(() => this.setState({hosts: []}))
    }

    loadLatest = () => {
        this.setState({latestLoading: true})
        const params = this.state.latestHostId ? {hostId: this.state.latestHostId} : {}
        HttpClient.get('admin/docker-stats/latest', params, {toastError: false})
            .then(rs => this.setState({latest: rs.data || []}))
            .catch(() => this.setState({latest: []}))
            .finally(() => this.setState({latestLoading: false}))
    }

    loadSeries = () => {
        const {hisHostId, hisRange} = this.state
        if (!hisHostId) return
        this.setState({seriesLoading: true, seriesError: null})
        HttpClient.get('admin/docker-stats/series', {hostId: hisHostId, range: hisRange}, {toastError: false})
            .then(rs => this.setState({series: rs.data, seriesError: null}))
            .catch(e => this.setState({series: null, seriesError: e.message || '读取失败'}))
            .finally(() => this.setState({seriesLoading: false}))
    }

    renderLatest() {
        const {hosts, latestHostId, latest, latestLoading} = this.state
        const hostOptions = [{value: '', label: '全部主机'}, ...hosts]
        const latestTime = latest.reduce((m, i) => Math.max(m, toNumber(i.collectedAt) || 0), 0)

        const columns = [
            {title: '主机', dataIndex: 'hostName', width: 140, ellipsis: true, render: v => v || '-'},
            {title: '容器 ID', dataIndex: 'id', width: 140, render: v => v ? String(v).slice(0, 12) : '-'},
            {
                title: '名称', dataIndex: 'name', width: 220, ellipsis: true,
                render: (v, r) => <Space size={4}>
                    <span title={v || r.id}>{v || r.id || '-'}</span>
                    {r.error ? <Tag color='red' title={r.error}>失败</Tag> : null}
                </Space>
            },
            {
                title: 'CPU %', dataIndex: 'cpuPercent', width: 100,
                sorter: (a, b) => toNumber(a.cpuPercent) - toNumber(b.cpuPercent),
                render: v => formatPercent(v)
            },
            {
                title: '内存使用 / 上限', key: 'mem', width: 200,
                sorter: (a, b) => toNumber(a.memUsage) - toNumber(b.memUsage),
                render: (_, r) => (r.memUsage == null && r.memLimit == null)
                    ? '-'
                    : `${formatBytes(r.memUsage)} / ${formatBytes(r.memLimit)}`
            },
            {
                title: '内存 %', dataIndex: 'memPercent', width: 100,
                sorter: (a, b) => toNumber(a.memPercent) - toNumber(b.memPercent),
                render: v => formatPercent(v)
            },
            {
                title: '网络 I/O', key: 'net', width: 200,
                render: (_, r) => (r.netRx == null && r.netTx == null)
                    ? '-'
                    : `↓ ${formatBytes(r.netRx)}  ↑ ${formatBytes(r.netTx)}`
            },
            {
                title: '块 I/O', key: 'block', width: 200,
                render: (_, r) => (r.blockRead == null && r.blockWrite == null)
                    ? '-'
                    : `读 ${formatBytes(r.blockRead)}  写 ${formatBytes(r.blockWrite)}`
            },
            {title: 'PIDS', dataIndex: 'pids', width: 90, render: v => v ?? '-'},
        ]

        const memBar = buildBarOption({title: '内存使用 Top 10', items: latest, valueKey: 'memUsage', formatter: v => formatBytes(v), color: '#52c41a'})
        const cpuBar = buildBarOption({title: 'CPU 使用率 Top 10', items: latest, valueKey: 'cpuPercent', formatter: v => formatPercent(v), color: '#1677ff'})

        return <>
            <Space style={{marginBottom: 16}} wrap>
                <span>主机：</span>
                <Select value={latestHostId} onChange={v => this.setState({latestHostId: v})}
                        options={hostOptions} style={{width: 220}}/>
                {latestTime ? <Typography.Text type='secondary'>采集时间：{formatClock(latestTime)}</Typography.Text> : null}
                <Typography.Text type='secondary'>共 {latest.length} 个容器</Typography.Text>
            </Space>

            {latestLoading && latest.length === 0
                ? <div className='text-center' style={{padding: '60px 0'}}><Spin/></div>
                : latest.length === 0
                    ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description='暂无快照数据'/>
                    : <>
                        <Card size='small' title='容器用量' style={{marginBottom: 16}}>
                            <Table rowKey={r => (r.hostId || '') + ':' + r.id} size='small'
                                   dataSource={latest} columns={columns}
                                   scroll={{x: 'max-content'}}
                                   pagination={{pageSize: 20, showSizeChanger: true}}
                                   loading={latestLoading}/>
                        </Card>
                        <Row gutter={16}>
                            <Col xs={24} lg={12}><Card size='small'><Chart height={320} option={memBar}/></Card></Col>
                            <Col xs={24} lg={12}><Card size='small'><Chart height={320} option={cpuBar}/></Card></Col>
                        </Row>
                    </>}
        </>
    }

    renderHistory() {
        const {hosts, hisHostId, hisRange, series, seriesLoading, seriesError} = this.state
        const points = (series && series.points) || []
        const timePattern = (hisRange === '7d' || hisRange === '30d') ? 'MM-DD HH:mm' : 'HH:mm'

        let body
        if (!hisHostId) {
            body = <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description='请选择主机'/>
        } else if (seriesLoading && points.length === 0) {
            body = <div className='text-center' style={{padding: '60px 0'}}><Spin/></div>
        } else if (seriesError) {
            body = <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={seriesError}/>
        } else if (points.length === 0) {
            body = <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description='暂无历史数据（每 10 分钟采样一次）'/>
        } else {
            body = <>
                <Typography.Text type='secondary' style={{display: 'block', marginBottom: 8}}>
                    采样点：{series.sampleCount}（按主机聚合）
                </Typography.Text>
                <Chart height={240} option={buildTrendOption({
                    points, defs: TREND_CPU_DEFS, percent: true,
                    axisFormatter: v => `${v}%`, timePattern,
                })}/>
                <Chart height={220} option={buildTrendOption({
                    points, defs: TREND_IO_DEFS, percent: false,
                    axisFormatter: v => formatBytes(v), timePattern,
                })}/>
            </>
        }

        return <>
            <Space style={{marginBottom: 16}} wrap>
                <span>主机：</span>
                <Select value={hisHostId} onChange={v => this.setState({hisHostId: v})}
                        options={hosts} style={{width: 220}} placeholder='选择主机'/>
                <Segmented size='small' value={hisRange} options={RANGES}
                           onChange={v => this.setState({hisRange: v})}/>
                {seriesLoading && points.length > 0 && <Spin size='small'/>}
            </Space>
            <Card size='small'>
                {body}
            </Card>
        </>
    }

    render() {
        const {activeTab} = this.state
        const items = [
            {key: 'latest', label: '最新快照', children: this.renderLatest()},
            {key: 'history', label: '历史趋势', children: this.renderHistory()},
        ]
        return <Page padding>
            <Card>
                <Tabs activeKey={activeTab} onChange={activeTab => this.setState({activeTab})} items={items}/>
            </Card>
        </Page>
    }
}
