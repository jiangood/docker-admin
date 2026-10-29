import React from "react";
import {Alert, Button, Select, Space, Typography} from "antd";
import {PermUtils} from "@jiangood/open-admin";
import ContainerTerminal from "./ContainerTerminal";
import ContainerPersistHint from "./ContainerPersistHint";
import {wsContainerExecUrl} from "./utils";

const SHELLS = [
    {value: '/bin/sh', label: '/bin/sh'},
    {value: '/bin/bash', label: '/bin/bash'},
    {value: '/bin/ash', label: '/bin/ash'},
]

/**
 * 容器交互式控制台（xterm + exec WebSocket）。
 * 顶部提示终端里的修改同样只写在容器内：非挂载目录的内容会在容器重建后丢失。
 */
export default class extends React.Component {

    state = {
        shell: '/bin/sh',
        key: 0,
    }

    render() {
        const {hostId, containerId, running, mounts, height} = this.props
        if (!running) {
            return <Alert type='warning' showIcon title='容器未运行，无法打开控制台'/>
        }
        if (!PermUtils.hasPermission('container:exec')) {
            return <Alert type='warning' showIcon title='缺少 container:exec 权限，无法打开控制台'/>
        }

        return <div style={{height: height || 420, display: 'flex', flexDirection: 'column'}}>
            <Space className='mb-2'>
                <span>Shell</span>
                <Select size='small' style={{width: 160}} value={this.state.shell} options={SHELLS}
                        onChange={v => this.setState({shell: v, key: this.state.key + 1})}/>
                <Button size='small' onClick={() => this.setState({key: this.state.key + 1})}>重新连接</Button>
                <Typography.Text type='secondary'>需要 container:exec 权限</Typography.Text>
            </Space>
            <ContainerPersistHint mounts={mounts} className='mb-2'/>
            <div style={{flex: 1, minHeight: 160}}>
                <ContainerTerminal key={this.state.key}
                                   url={wsContainerExecUrl(hostId, containerId, this.state.shell)}
                                   height='100%'/>
            </div>
        </div>
    }
}
