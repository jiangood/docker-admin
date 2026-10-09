import React from "react";
import {Descriptions, Empty, Table, Tag, Typography} from "antd";
import {formatTime, stateColor, stateLabel} from "./utils";

const Text = Typography.Text

/**
 * 容器基本信息（来自 docker inspect）。
 */
export default function ContainerInfo({detail}) {
    if (!detail) {
        return <Empty/>
    }

    const ports = detail.ports || []
    const mounts = detail.mounts || []
    const networks = detail.networks || []
    const env = detail.env || []
    const labels = Object.entries(detail.labels || {})

    return <>
        <Descriptions size='small' column={2} bordered
                      items={[
                          {key: 'id', label: '容器ID', children: <Text copyable>{detail.id}</Text>, span: 2},
                          {key: 'name', label: '名称', children: detail.name},
                          {key: 'image', label: '镜像', children: detail.image},
                          {
                              key: 'state', label: '状态',
                              children: <Tag color={stateColor(detail.state)}>{stateLabel(detail.state)}</Tag>
                          },
                          {key: 'status', label: '状态详情', children: detail.status},
                          {
                              key: 'exitCode', label: '退出码',
                              children: detail.exitCode === null || detail.exitCode === undefined ? '-' : detail.exitCode
                          },
                          {key: 'restartCount', label: '重启次数', children: detail.restartCount},
                          {key: 'created', label: '创建时间', children: formatTime(detail.created)},
                          {key: 'startedAt', label: '启动时间', children: formatTime(detail.startedAt)},
                          {key: 'finishedAt', label: '结束时间', children: formatTime(detail.finishedAt)},
                      ]}/>

        <Section title='端口映射'>
            <Table size='small' rowKey={r => `${r.hostIp || ''}:${r.publicPort || ''}->${r.privatePort}/${r.protocol}`}
                   pagination={false} dataSource={ports}
                   locale={{emptyText: '暂无端口映射'}}
                   columns={[
                       {title: '主机端口', dataIndex: 'publicPort', render: v => v || '-', width: 120},
                       {title: '容器端口', dataIndex: 'privatePort', width: 120},
                       {title: '协议', dataIndex: 'protocol', width: 90},
                       {title: '主机IP', dataIndex: 'hostIp', render: v => v || '-'},
                   ]}/>
        </Section>

        <Section title='挂载'>
            <Table size='small' rowKey={r => r.destination || r.source} pagination={false} dataSource={mounts}
                   locale={{emptyText: '暂无挂载'}}
                   columns={[
                       {title: '类型', dataIndex: 'type', width: 90},
                       {title: '容器路径', dataIndex: 'destination'},
                       {title: '主机路径', dataIndex: 'source', render: v => v || '-'},
                       {title: '名称', dataIndex: 'name', render: v => v || '-'},
                       {
                           title: '读写', dataIndex: 'readOnly', width: 80,
                           render: v => v ? <Tag color='orange'>只读</Tag> : <Tag color='green'>读写</Tag>
                       },
                   ]}/>
        </Section>

        <Section title='网络'>
            <Table size='small' rowKey={r => r.name} pagination={false} dataSource={networks}
                   locale={{emptyText: '暂无网络'}}
                   columns={[
                       {title: '网络', dataIndex: 'name'},
                       {title: 'IP', dataIndex: 'ipAddress', render: v => v || '-'},
                       {title: '网关', dataIndex: 'gateway', render: v => v || '-'},
                       {title: 'MAC', dataIndex: 'macAddress', render: v => v || '-'},
                   ]}/>
        </Section>

        <Section title={`环境变量 (${env.length})`}>
            {env.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}/> :
                <pre style={preStyle}>{env.join('\n')}</pre>}
        </Section>

        <Section title={`标签 (${labels.length})`}>
            {labels.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}/> :
                <pre style={preStyle}>{labels.map(([k, v]) => `${k}=${v}`).join('\n')}</pre>}
        </Section>
    </>
}

const preStyle = {
    margin: 0,
    whiteSpace: 'pre-wrap',
    wordBreak: 'break-all',
    maxHeight: 240,
    overflow: 'auto',
}

function Section({title, children}) {
    return <div style={{marginTop: 16}}>
        <div style={{fontWeight: 600, marginBottom: 8}}>{title}</div>
        {children}
    </div>
}
