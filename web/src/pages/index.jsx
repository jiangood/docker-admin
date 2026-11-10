import {useCallback, useEffect, useState} from 'react'
import {Alert, Button, Card, Col, Progress, Row, Skeleton, Space, Statistic, Table, Tag, Tooltip, Typography} from 'antd'
import {ReloadOutlined} from '@ant-design/icons'
import {HttpClient, Page, PageUtils, PermUtils} from '@jiangood/open-admin'
import LinkButton from '../components/LinkButton'
import {formatBytes, formatClock, formatPercent, toNumber, usageColor} from '../components/container/utils'

/**
 * 首页看板，参考 Rancher 首页：顶部一组全局资源概览，下面是逐主机的资源明细。
 * <p>
 * 数据分两条链路：
 * <ul>
 *   <li>静态容量：{@code admin/home/hostSummary}（docker info），快，首屏先出；</li>
 *   <li>实时用量：{@code admin/home/hostUsage}（等价 docker stats），读的是后端定时任务
 *       （每 10 分钟一次）写下的快照，轮询本身不会再压主机；页面隐藏时暂停。</li>
 * </ul>
 * 实时用量采集失败或超时只让对应单元格显示占位，不会阻塞或破坏静态概览。
 */

/**
 * 实时用量轮询间隔。数据源每 10 分钟才更新一次，这里仍按 30 秒读快照：
 * 只是后端一次内存读，开销可以忽略，手动「刷新」现采后的结果也能尽快反映到界面上。
 */
const USAGE_INTERVAL_MILLIS = 30_000

/**
 * 单行用量：进度条与文本并排，保证每个单元格只有一行，行高不会被撑成三行。
 * hint 放进 tooltip；空值不画「0%」避免误解。
 */
function InlineUsage({percent, text, hint}) {
    const valid = percent !== null && percent !== undefined && Number.isFinite(Number(percent))
    const body = <Space size={8} style={{whiteSpace: 'nowrap'}}>
        <Progress percent={valid ? Math.min(100, Math.max(0, Number(percent))) : 0}
                  showInfo={false} size='small' style={{width: 56, marginBottom: 0}}
                  strokeColor={valid ? usageColor(percent) : '#d9d9d9'}/>
        <Typography.Text type={valid ? undefined : 'secondary'} style={{fontSize: 12}}>{text}</Typography.Text>
    </Space>
    return hint ? <Tooltip title={hint}>{body}</Tooltip> : body
}

function HostResourceBoard() {
    const [data, setData] = useState(null)
    const [loading, setLoading] = useState(true)
    const [failed, setFailed] = useState(false)

    /** hostId -> HostUsageVo（实时用量，异步到达） */
    const [usageMap, setUsageMap] = useState({})
    const [usageAt, setUsageAt] = useState(null)
    const [usageError, setUsageError] = useState(false)

    const load = useCallback(refresh => {
        setLoading(true)
        setFailed(false)
        HttpClient.get('admin/home/hostSummary', refresh ? {refresh: true} : {}, {toastError: false})
            .then(rs => setData(rs.data))
            .catch(() => setFailed(true))
            .finally(() => setLoading(false))
    }, [])

    const loadUsage = useCallback(refresh => {
        HttpClient.get('admin/home/hostUsage', refresh ? {refresh: true} : {}, {toastError: false})
            .then(rs => {
                const map = {}
                let latest = 0
                for (const item of rs.data || []) {
                    map[item.hostId] = item
                    // 用后端采集时间：快照可能已经是一分钟前的了，不能用本地收到响应的时间
                    latest = Math.max(latest, toNumber(item.collectedAt))
                }
                setUsageMap(map)
                setUsageAt(latest || null)
                setUsageError(false)
            })
            .catch(() => setUsageError(true))
    }, [])

    useEffect(() => load(false), [load])

    // 实时用量独立轮询：静态概览已经渲染，这里慢或失败都不影响首屏
    useEffect(() => {
        loadUsage(false)
        const timer = setInterval(() => {
            if (!document.hidden) {
                loadUsage(false)
            }
        }, USAGE_INTERVAL_MILLIS)
        const onVisibilityChange = () => {
            if (!document.hidden) {
                loadUsage(false)
            }
        }
        document.addEventListener('visibilitychange', onVisibilityChange)
        return () => {
            clearInterval(timer)
            document.removeEventListener('visibilitychange', onVisibilityChange)
        }
    }, [loadUsage])

    const refreshAll = () => {
        load(true)
        loadUsage(true)
    }

    const hosts = data?.hosts || []
    const running = data?.containersRunning || 0
    const containers = data?.containersTotal || 0
    const containerPercent = containers > 0 ? Math.round((running / containers) * 100) : 0

    // 汇总口径与后端一致：只统计可连接且采集成功的主机
    const usageList = hosts.map(h => usageMap[h.id]).filter(u => u && u.reachable)
    const usageReady = usageList.length > 0
    const cpuTotal = toNumber(data?.cpuTotal)
    const memoryTotal = toNumber(data?.memoryTotal)
    const cpuCoresUsed = usageList.reduce((sum, u) => sum + toNumber(u.cpuCoresUsed), 0)
    const memoryUsed = usageList.reduce((sum, u) => sum + toNumber(u.memUsed), 0)
    const cpuPercent = usageReady && cpuTotal > 0
        ? Math.min(100, (cpuCoresUsed / cpuTotal) * 100)
        : null
    const memoryPercent = usageReady && memoryTotal > 0
        ? Math.min(100, (memoryUsed / memoryTotal) * 100)
        : null

    /**
     * 统一的「实时用量」取值：不可连接 / 采集失败 / 尚未采集都给占位，
     * 避免每个拆分出来的列重复判断一遍。
     */
    const usageCell = (record, render) => {
        if (!record.reachable) {
            return '-'
        }
        const usage = usageMap[record.id]
        if (!usage) {
            return <Typography.Text type='secondary'>采集中…</Typography.Text>
        }
        if (!usage.reachable) {
            return <Tooltip title={usage.error || '采集失败'}>
                <Typography.Text type='secondary'>-</Typography.Text>
            </Tooltip>
        }
        return render(usage)
    }

    const columns = [
        {
            title: '主机', dataIndex: 'name',
            render: (name, record) => <LinkButton
                onClick={() => PageUtils.open('/host/view?id=' + record.id)}>{name}</LinkButton>
        },
        {
            title: '状态', dataIndex: 'reachable', width: 90,
            render: (reachable, record) => reachable
                ? <Tag color='green'>可用</Tag>
                : <Tooltip title={record.error || '无法连接'}><Tag color='red'>不可用</Tag></Tooltip>
        },
        {
            title: 'CPU',
            children: [
                {
                    title: '使用率', width: 120,
                    render: (_, record) => usageCell(record, usage => <InlineUsage
                        percent={usage.cpuPercent}
                        text={formatPercent(usage.cpuPercent)}
                        hint='容器合计占主机 CPU 的比例'/>)
                },
                {
                    // 核心数直接展示，不再只放在悬浮提示里；主机核数优先取静态概览，缺失时退回实时采集
                    title: '核数', width: 110,
                    render: (_, record) => usageCell(record, usage => {
                        const used = usage.cpuCoresUsed == null ? null : toNumber(usage.cpuCoresUsed)
                        const total = record.cpus ?? usage.cpus
                        return <Typography.Text style={{fontSize: 12, whiteSpace: 'nowrap'}}>
                            {used == null ? '-' : used.toFixed(2)} / {total == null ? '-' : total} 核
                        </Typography.Text>
                    })
                },
            ]
        },
        {
            title: '内存',
            children: [
                {
                    title: '使用率', width: 120,
                    render: (_, record) => usageCell(record, usage =>
                        <InlineUsage percent={usage.memPercent}
                                     text={formatPercent(usage.memPercent)}
                                     hint='容器内存合计占主机内存的比例，不含容器之外的进程与页面缓存'/>)
                },
                {
                    title: '用量', width: 160,
                    render: (_, record) => usageCell(record, usage =>
                        <Typography.Text style={{fontSize: 12, whiteSpace: 'nowrap'}}>
                            {formatBytes(usage.memUsed)} / {formatBytes(record.memory)}
                        </Typography.Text>)
                },
            ]
        },
        {
            title: '容器',
            children: [
                {
                    title: '运行中', dataIndex: 'containersRunning', width: 80,
                    render: (value, record) => record.reachable ? (value ?? 0) : '-'
                },
                {
                    title: '总数', dataIndex: 'containersTotal', width: 80,
                    render: (value, record) => record.reachable ? (value ?? 0) : '-'
                },
            ]
        },
        {title: '镜像', dataIndex: 'images', width: 80, render: v => v == null ? '-' : v},
    ]

    const extra = <Space size={12}>
        {usageError
            ? <Typography.Text type='warning' style={{fontSize: 12}}>实时用量暂不可用</Typography.Text>
            : usageAt && <Tooltip title='后台每 10 分钟采集一次，页面读的是最近一次快照'>
                <Typography.Text type='secondary' style={{fontSize: 12}}>
                    数据时间 {formatClock(usageAt)}
                </Typography.Text>
            </Tooltip>}
        <Button size='small' icon={<ReloadOutlined/>} loading={loading}
                onClick={refreshAll}>刷新</Button>
    </Space>

    return <Card className='page-card page-card-fluid' title='资源概览' extra={extra}>
        {failed &&
            <Alert type='error' showIcon className='mb-4' message='读取主机资源失败'
                   description='可能是后端不可用，请稍后重试。'/>}

        {loading && !data
            ? <Skeleton active paragraph={{rows: 4}}/>
            : failed && !data
                ? null
                : <>
                    <Row gutter={[16, 16]}>
                        <Col flex='1 1 150px'>
                            <Statistic title='主机'
                                       value={data.hostReachable}
                                       suffix={`/ ${data.hostTotal}`}/>
                        </Col>
                        <Col flex='1 1 170px'>
                            <Statistic title='CPU 使用率'
                                       value={cpuPercent == null ? '-' : cpuPercent.toFixed(1)}
                                       suffix={cpuPercent == null ? null : '%'}/>
                            <Progress percent={cpuPercent == null ? 0 : cpuPercent} showInfo={false} size='small'
                                      strokeColor={cpuPercent == null ? '#d9d9d9' : usageColor(cpuPercent)}/>
                            <Typography.Text type='secondary' style={{fontSize: 12}}>
                                {usageReady
                                    ? `${cpuCoresUsed.toFixed(2)} 核${cpuTotal > 0 ? ` / ${cpuTotal} 核` : ''}`
                                    : '采集中…'}
                            </Typography.Text>
                        </Col>
                        <Col flex='1 1 190px'>
                            <Statistic title='内存使用'
                                       value={usageReady ? formatBytes(memoryUsed) : '-'}
                                       suffix={usageReady ? `/ ${formatBytes(memoryTotal)}` : null}/>
                            <Progress percent={memoryPercent == null ? 0 : memoryPercent} showInfo={false} size='small'
                                      strokeColor={memoryPercent == null ? '#d9d9d9' : usageColor(memoryPercent)}/>
                            <Typography.Text type='secondary' style={{fontSize: 12}}>
                                {usageReady ? `容器合计，占整机 ${formatPercent(memoryPercent)}` : '采集中…'}
                            </Typography.Text>
                        </Col>
                        <Col flex='1 1 150px'>
                            <Statistic title='容器' value={running} suffix={`/ ${containers}`}/>
                            <Progress percent={containerPercent} showInfo={false} size='small'/>
                        </Col>
                        <Col flex='1 1 150px'>
                            <Statistic title='镜像' value={data.imagesTotal}/>
                        </Col>
                    </Row>

                    <Table className='mt-5' rowKey='id' size='small' loading={loading}
                           dataSource={data.hosts} columns={columns} pagination={false}
                           scroll={{x: 'max-content'}}
                           locale={{emptyText: '尚未添加主机，可在「主机」中添加'}}/>
                </>}
    </Card>
}

export default function () {
    const canViewHost = PermUtils.hasPermission('host:list')
    // 看板页铺满内容区（与表单/详情页的 960 区分开），有无资源概览都要占满
    const cardClass = canViewHost ? 'page-card page-card-fluid mt-4' : 'page-card page-card-fluid'

    return <Page padding>
        {canViewHost && <HostResourceBoard/>}

        <Card className={cardClass}>
            <Typography.Title level={4} style={{marginTop: 0}}>欢迎来到 Docker Admin</Typography.Title>
            <Typography.Text type='secondary'>版本 v{__APP_VERSION__}</Typography.Text>
        </Card>
    </Page>
}
