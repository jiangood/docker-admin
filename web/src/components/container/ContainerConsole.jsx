import React from "react";
import {Alert, Button, Select, Space, Typography} from "antd";
import {PermUtils} from "@jiangood/open-admin";
import ContainerTerminal from "./ContainerTerminal";
import {wsContainerExecUrl} from "./utils";

const SHELLS = [
    {value: '/bin/sh', label: '/bin/sh'},
    {value: '/bin/bash', label: '/bin/bash'},
    {value: '/bin/ash', label: '/bin/ash'},
]

/**
 * 容器交互式控制台（xterm + exec WebSocket）。
 */
export default class extends React.Component {

    state = {
        shell: '/bin/sh',
        key: 0,
    }

    render() {
        const {hostId, containerId, running} = this.props
        if (!running) {
            return <Alert type='warning' showIcon title='容器未运行，无法打开控制台'/>
        }
        if (!PermUtils.hasPermission('container:exec')) {
            return <Alert type='warning' showIcon title='缺少 container:exec 权限，无法打开控制台'/>
        }

        return <>
            <Space style={{marginBottom: 8}}>
                <span>Shell</span>
                <Select size='small' style={{width: 160}} value={this.state.shell} options={SHELLS}
                        onChange={v => this.setState({shell: v, key: this.state.key + 1})}/>
                <Button size='small' onClick={() => this.setState({key: this.state.key + 1})}>重新连接</Button>
                <Typography.Text type='secondary'>需要 container:exec 权限</Typography.Text>
            </Space>
            <ContainerTerminal key={this.state.key}
                               url={wsContainerExecUrl(hostId, containerId, this.state.shell)}
                               height={this.props.height || 420}/>
        </>
    }
}
