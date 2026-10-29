import {useCallback, useEffect, useState} from 'react'
import {Alert, Button, Card, Col, Progress, Row, Skeleton, Space, Statistic, Table, Tag, Tooltip, Typography} from 'antd'
import {ReloadOutlined} from '@ant-design/icons'
import {HttpClient, Page, PageUtils, PermUtils} from '@jiangood/open-admin'
import LinkButton from '../components/LinkButton'
import {formatBytes, formatClock, formatPercent, usageColor} from '../components/container/utils'

/**
 * 首页看板，参考 Rancher 首页：顶部一组全局资源概览，下面是逐主机的资源明细。
 * <p>
 * 数据分两条链路：
 * <ul>
 *   <li>静态容量：{@code admin/home/hostSummary}（docker info），快，首屏先出；</li>
 *   <li>实时用量：{@code admin/home/hostUsage}（等价 docker stats），慢一个数量级，
 *       首屏后异步加载并每 10 秒轮询，页面隐藏时暂停。</li>
 * </ul>
 * 实时用量采集失败或超时只让对应单元格显示占位，不会阻塞或破坏静态概览。
 */

/** 实时用量轮询间隔：dockerd 的 stats 每轮要采两次样本，间隔再短没有意义。 */
const USAGE_INTERVAL_MILLIS = 10_000

/** 主机资源表格里的「运行 / 总数」小进度条。 */
function ContainerBar({running, total}) {
    const used = running || 0
    const all = total || 0
    const percent = all > 0 ? Math.round((used / all) * 100) : 0
    return <div style={{width: 120}}>
        <Typography.Text type='secondary' style={{fontSize: 12}}>{used} / {all}</Typography.Text>
        <Progress percent={percent} showInfo={false} size='small'/>
    </div>
}

/** 用量条：文本 + 进度条，hint 放进 tooltip；空值不画「0%」避免误解。 */
function UsageBar({percent, text, hint}) {
    const valid = percent !== null && percent !== undefined && Number.isFinite(Number(percent))
    const body = <div style={{width: 150}}>
        <Typography.Text type={valid ? undefined : 'secondary'} style={{fontSize: 12}}>{text}</Typography.Text>
        <Progress percent={valid ? Math.min(100, Math.max(0, Number(percent))) : 0}
                  showInfo={false} size='small'
                  strokeColor={valid ? usageColor(percent) : '#d9d9d9'}/>
    </div>
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
                for (const item of rs.data || []) {
                    map[item.hostId] = item
                }
                setUsageMap(map)
                setUsageAt(Date.now())
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
    const unreachable = hosts.filter(h => !h.reachable)
    const running = data?.containersRunning || 0
    const containers = data?.containersTotal || 0
    const containerPercent = containers > 0 ? Math.round((running / containers) * 100) : 0

    // 汇总口径与后端一致：只统计可连接且采集成功的主机
    const usageList = hosts.map(h => usageMap[h.id]).filter(u => u && u.reachable)
    const usageReady = usageList.length > 0
    const cpuCoresUsed = usageList.reduce((sum, u) => sum + (u.cpuCoresUsed || 0), 0)
    const memoryUsed = usageList.reduce((sum, u) => sum + (u.memUsed || 0), 0)
    const cpuPercent = usageReady && data?.cpuTotal > 0
        ? Math.min(100, (cpuCoresUsed / data.cpuTotal) * 100)
        : null
    const memoryPercent = usageReady && data?.memoryTotal > 0
        ? Math.min(100, (memoryUsed / data.memoryTotal) * 100)
        : null

    const columns = [
        {
            title: '主机', dataIndex: 'name',
            render: (name, record) => <LinkButton
                onClick={() => PageUtils.open('/host/view?id=' + record.id, '主机-' + name)}>{name}</LinkButton>
        },
        {
            title: '状态', dataIndex: 'reachable', width: 100,
            render: (reachable, record) => reachable
                ? <Tag color='green'>可用</Tag>
                : <Tooltip title={record.error || '无法连接'}><Tag color='red'>不可用</Tag></Tooltip>
        },
        {
            title: 'CPU', width: 170,
            render: (_, record) => {
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
                return <UsageBar percent={usage.cpuPercent}
                                 text={formatPercent(usage.cpuPercent)}
                                 hint={`容器合计 ${usage.cpuCoresUsed == null ? '-' : usage.cpuCoresUsed.toFixed(2)} 核`
                                     + ` / 主机 ${record.cpus == null ? '-' : record.cpus} 核`}/>
            }
        },
        {
            title: '内存', width: 190,
            render: (_, record) => {
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
                return <UsageBar percent={usage.memPercent}
                                 text={`${formatBytes(usage.memUsed)} / ${formatBytes(record.memory)}`}
                                 hint='容器内存合计，不含容器之外的进程与页面缓存'/>
            }
        },
        {
            title: '容器', width: 140,
            render: (_, record) => record.reachable
                ? <ContainerBar running={record.containersRunning} total={record.containersTotal}/>
                : '-'
        },
        {title: '镜像', dataIndex: 'images', width: 90, render: v => v == null ? '-' : v},
    ]

    const extra = <Space size={12}>
        {usageError
            ? <Typography.Text type='warning' style={{fontSize: 12}}>实时用量暂不可用</Typography.Text>
            : usageAt && <Typography.Text type='secondary' style={{fontSize: 12}}>
                用量更新于 {formatClock(usageAt)}
            </Typography.Text>}
        <Button size='small' icon={<ReloadOutlined/>} loading={loading}
                onClick={refreshAll}>刷新</Button>
    </Space>

    return <Card className='page-card' title='资源概览' extra={extra}>
        {failed &&
            <Alert type='error' showIcon className='mb-4' message='读取主机资源失败'
                   description='可能是后端不可用，请稍后重试。'/>}

        {loading && !data
            ? <Skeleton active paragraph={{rows: 4}}/>
            : failed && !data
                ? null
                : <>
                    {unreachable.length > 0 &&
                        <Alert type='warning' showIcon className='mb-4'
                               message={`${unreachable.length} / ${data.hostTotal} 台主机暂时无法连接，未计入汇总`}
                               description={unreachable.map(h => h.name).join('、')}/>}

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
                                {cpuPercent == null
                                    ? '采集中…'
                                    : `${cpuCoresUsed.toFixed(2)} 核 / ${data.cpuTotal} 核`}
                            </Typography.Text>
                        </Col>
                        <Col flex='1 1 190px'>
                            <Statistic title='内存使用'
                                       value={usageReady ? formatBytes(memoryUsed) : '-'}
                                       suffix={usageReady ? `/ ${formatBytes(data.memoryTotal)}` : null}/>
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
                           locale={{emptyText: '尚未添加主机，可在「设置 / 主机管理」中添加'}}/>
                </>}
    </Card>
}

export default function () {
    const canViewHost = PermUtils.hasPermission('host:list')

    return <Page padding>
        {canViewHost && <HostResourceBoard/>}

        <Card className={canViewHost ? 'page-card mt-4' : 'page-card'}>
            <Typography.Title level={4} style={{marginTop: 0}}>欢迎来到 Docker Admin</Typography.Title>
            <Typography.Text type='secondary'>版本 v{__APP_VERSION__}</Typography.Text>
        </Card>
    </Page>
}
