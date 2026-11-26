import {useCallback, useEffect, useState} from 'react'
import {Alert, Button, Card, Col, Progress, Row, Skeleton, Space, Statistic, Table, Tag, Tooltip, Typography} from 'antd'
import {ReloadOutlined} from '@ant-design/icons'
import {HttpClient, Page, PageUtils, PermUtils} from '@jiangood/open-admin'
import LinkButton from '../components/LinkButton'
import {formatBytes} from '../components/container/utils'

/**
 * 首页看板，参考 Rancher 首页：顶部一组全局资源概览，下面是逐主机的资源明细。
 * <p>
 * 数据只来自 {@code admin/home/hostSummary}（一次 {@code docker info}，后端 30 秒缓存），
 * 展示主机数、CPU 核数、内存容量、容器与镜像数等静态容量，不触发 {@code docker stats}。
 */

function HostResourceBoard() {
    const [data, setData] = useState(null)
    const [loading, setLoading] = useState(true)
    const [failed, setFailed] = useState(false)

    const load = useCallback(refresh => {
        setLoading(true)
        setFailed(false)
        HttpClient.get('admin/home/hostSummary', refresh ? {refresh: true} : {}, {toastError: false})
            .then(rs => setData(rs.data))
            .catch(() => setFailed(true))
            .finally(() => setLoading(false))
    }, [])

    useEffect(() => load(false), [load])

    const hosts = data?.hosts || []
    const running = data?.containersRunning || 0
    const containers = data?.containersTotal || 0
    const containerPercent = containers > 0 ? Math.round((running / containers) * 100) : 0

    // 不设列宽：交给浏览器按内容自适应，避免固定宽度把剩余空间全推给主机列
    const columns = [
        {
            title: '主机', dataIndex: 'name',
            render: (name, record) => <LinkButton
                onClick={() => PageUtils.open('/host/view?id=' + record.id)}>{name}</LinkButton>
        },
        {
            title: '状态', dataIndex: 'reachable',
            render: (reachable, record) => reachable
                ? <Tag color='green'>可用</Tag>
                : <Tooltip title={record.error || '无法连接'}><Tag color='red'>不可用</Tag></Tooltip>
        },
        {
            title: 'CPU', dataIndex: 'cpus',
            render: cpus => cpus == null ? '-' : `${cpus} 核`
        },
        {
            title: '内存', dataIndex: 'memory',
            render: memory => memory == null ? '-' : formatBytes(memory)
        },
        {
            title: '容器',
            render: (_, record) => record.reachable
                ? `${record.containersRunning ?? 0} / ${record.containersTotal ?? 0}`
                : '-'
        },
        {title: '镜像', dataIndex: 'images', render: v => v == null ? '-' : v},
    ]

    const extra = <Space size={12}>
        <Button size='small' icon={<ReloadOutlined/>} loading={loading}
                onClick={() => load(true)}>刷新</Button>
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
                        <Col flex='1 1 150px'>
                            <Statistic title='CPU 核数' value={data.cpuTotal} suffix='核'/>
                        </Col>
                        <Col flex='1 1 190px'>
                            <Statistic title='内存容量' value={formatBytes(data.memoryTotal)}/>
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
