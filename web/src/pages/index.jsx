import {useCallback, useEffect, useState} from 'react'
import {Alert, Button, Card, Col, Progress, Row, Skeleton, Statistic, Table, Tag, Tooltip, Typography} from 'antd'
import {ReloadOutlined} from '@ant-design/icons'
import {HttpClient, Page, PageUtils, PermUtils} from '@jiangood/open-admin'
import LinkButton from '../components/LinkButton'
import {formatBytes} from '../components/container/utils'

/**
 * 首页看板，参考 Rancher 首页：顶部一组全局资源概览，下面是逐主机的资源明细。
 * <p>
 * 口径与取舍见后端 HostSummaryVo：只统计静态容量（CPU 核数、内存容量），
 * 不做实时使用率（需要 docker stats 持续轮询，代价远高于首页能承受的范围）。
 */

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

    const unreachable = (data?.hosts || []).filter(h => !h.reachable)
    const running = data?.containersRunning || 0
    const containers = data?.containersTotal || 0
    const containerPercent = containers > 0 ? Math.round((running / containers) * 100) : 0

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
        {title: 'CPU', dataIndex: 'cpus', width: 100, render: v => v == null ? '-' : v + ' 核'},
        {title: '内存', dataIndex: 'memory', width: 120, render: v => v == null ? '-' : formatBytes(v)},
        {
            title: '容器', width: 140,
            render: (_, record) => record.reachable
                ? <ContainerBar running={record.containersRunning} total={record.containersTotal}/>
                : '-'
        },
        {title: '镜像', dataIndex: 'images', width: 90, render: v => v == null ? '-' : v},
    ]

    return <Card className='page-card'
                 title='资源概览'
                 extra={<Button size='small' icon={<ReloadOutlined/>} loading={loading}
                                onClick={() => load(true)}>刷新</Button>}>
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
                        <Col flex='1 1 150px'>
                            <Statistic title='CPU 总核数' value={data.cpuTotal} suffix='核'/>
                        </Col>
                        <Col flex='1 1 150px'>
                            <Statistic title='总内存' value={formatBytes(data.memoryTotal)}/>
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
