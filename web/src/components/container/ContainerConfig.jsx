import React from "react";
import {Descriptions, Empty, Tag} from "antd";

/**
 * 容器配置（只读，来自 docker inspect）。通用容器组件不提供修改容器配置的能力。
 * 环境变量 / 标签统一在「概览」页签展示，此处不再重复。
 */
export default function ContainerConfig({detail}) {
    if (!detail) {
        return <Empty/>
    }
    const cmd = (detail.cmd || []).join(' ')
    const entrypoint = (detail.entrypoint || []).join(' ')

    return (
        <Descriptions size='small' column={1} bordered
                      items={[
                          {key: 'cmd', label: '启动命令', children: <code>{cmd || '-'}</code>},
                          {key: 'entrypoint', label: '入口点', children: <code>{entrypoint || '-'}</code>},
                          {key: 'workdir', label: '工作目录', children: detail.workingDir || '-'},
                          {key: 'user', label: '用户', children: detail.user || '-'},
                          {key: 'hostname', label: '主机名', children: detail.hostname || '-'},
                          {key: 'network', label: '网络模式', children: detail.networkMode || '-'},
                          {key: 'restart', label: '重启策略', children: detail.restartPolicy || '-'},
                          {key: 'log', label: '日志驱动', children: detail.logDriver || '-'},
                          {
                              key: 'privileged', label: '特权模式',
                              children: detail.privileged ? <Tag color='red'>是</Tag> : <Tag>否</Tag>
                          },
                      ]}/>
    )
}
