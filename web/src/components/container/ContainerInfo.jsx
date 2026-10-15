import React from "react";
import {Button, Descriptions, Empty, Table, Tag, Typography, message} from "antd";
import {CopyOutlined} from "@ant-design/icons";
import SectionTitle from "./SectionTitle";
import {formatTime} from "./utils";

const Text = Typography.Text

/** 复制纯文本到剪贴板。 */
function copyText(text) {
    const value = text || ''
    if (!value) {
        return
    }
    if (navigator.clipboard) {
        navigator.clipboard.writeText(value)
            .then(() => message.success('已复制'))
            .catch(() => message.error('复制失败'))
    } else {
        message.warning('当前浏览器不支持剪贴板')
    }
}

/** 表格空态：与其余分区保持一致的简洁空态。 */
function emptyTable(text) {
    return {emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={text}/>}
}

/**
 * 容器基本信息（来自 docker inspect）。
 * 名称 / 状态 / 操作按钮由外层 ContainerDetail 的固定头部展示，此处不再重复。
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
    const hasExitCode = detail.exitCode !== null && detail.exitCode !== undefined

    return <>
        <Descriptions size='small' column={2} bordered
                      items={[
                          {key: 'id', label: '容器ID', span: 2, children: <Text copyable>{detail.id}</Text>},
                          {key: 'image', label: '镜像', children: detail.image || '-'},
                          {key: 'restartCount', label: '重启次数', children: detail.restartCount ?? '-'},
                          {key: 'status', label: '状态详情', span: 2, children: detail.status || '-'},
                          {key: 'created', label: '创建时间', children: formatTime(detail.created)},
                          {key: 'startedAt', label: '启动时间', children: formatTime(detail.startedAt)},
                          {key: 'finishedAt', label: '结束时间', children: formatTime(detail.finishedAt)},
                          {key: 'exitCode', label: '退出码', children: hasExitCode ? detail.exitCode : '-'},
                      ]}/>

        <Section title='端口映射'>
            <Table size='small' rowKey={r => `${r.hostIp || ''}:${r.publicPort || ''}->${r.privatePort}/${r.protocol}`}
                   pagination={false} dataSource={ports} locale={emptyTable('暂无端口映射')}
                   columns={[
                       {title: '主机端口', dataIndex: 'publicPort', render: v => v || '-', width: 120},
                       {title: '容器端口', dataIndex: 'privatePort', width: 120},
                       {title: '协议', dataIndex: 'protocol', width: 90},
                       {title: '主机IP', dataIndex: 'hostIp', render: v => v || '-'},
                   ]}/>
        </Section>

        <Section title='挂载'>
            <Table size='small' rowKey={r => r.destination || r.source} pagination={false} dataSource={mounts}
                   locale={emptyTable('暂无挂载')}
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
                   locale={emptyTable('暂无网络')}
                   columns={[
                       {title: '网络', dataIndex: 'name'},
                       {title: 'IP', dataIndex: 'ipAddress', render: v => v || '-'},
                       {title: '网关', dataIndex: 'gateway', render: v => v || '-'},
                       {title: 'MAC', dataIndex: 'macAddress', render: v => v || '-'},
                   ]}/>
        </Section>

        <Section title={`环境变量 (${env.length})`}
                 extra={env.length > 0 &&
                     <Button type='text' size='small' icon={<CopyOutlined/>}
                             onClick={() => copyText(env.join('\n'))}>复制</Button>}>
            {env.length === 0
                ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}/>
                : <pre className='code-block mono container-code-panel'>{env.join('\n')}</pre>}
        </Section>

        <Section title={`标签 (${labels.length})`}
                 extra={labels.length > 0 &&
                     <Button type='text' size='small' icon={<CopyOutlined/>}
                             onClick={() => copyText(labels.map(([k, v]) => `${k}=${v}`).join('\n'))}>复制</Button>}>
            {labels.length === 0
                ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}/>
                : <pre className='code-block mono container-code-panel'>{labels.map(([k, v]) => `${k}=${v}`).join('\n')}</pre>}
        </Section>
    </>
}

function Section({title, extra, children}) {
    return <>
        <SectionTitle title={title} extra={extra}/>
        {children}
    </>
}
