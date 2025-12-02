import {Button, message} from "antd";
import React from "react";
import {HttpUtils} from "@jiangood/springboot-admin-starter";


/**
 * 容器状态
 */
export default class extends React.Component {

    state = {
        status: '-', // running
    }

    componentDidMount() {
        this.loadStatus();
    }

    loadStatus = () => {
        const {hostId, containerId} = this.props
        HttpUtils.get("admin/container/status", {hostId, containerId}).then(rs => {
            this.setState({status: rs})
        }).catch(() => {
            this.setState({status: '未知'})
        })
    };

    start = () => {
        const {hostId, containerId} = this.props
        HttpUtils.get("admin/container/start", {hostId, containerId}).then(rs => {
            message.success("启动命令已执行")
            this.loadStatus()
        })
    };

    stop = () => {
        const {hostId, containerId} = this.props
        HttpUtils.get("admin/container/stop", {hostId, containerId}).then(rs => {
            message.success("停止命令已执行")
            this.loadStatus()
        })
    };

    remove = () => {
        const {hostId, containerId} = this.props
        HttpUtils.get("admin/container/remove", {hostId, containerId}).then(rs => {
            message.success("删除命令已执行")
            this.loadStatus()
        })
    };

    render() {
        const s = this.state.status;
        if (s == null) {
            return
        }
        const running = s === 'running';

        return <Button.Group>
            <Button type='primary' disabled={running} onClick={this.start}>启动</Button>
            <Button type='primary' danger disabled={!running} onClick={this.stop}>停止</Button>
            <Button type='primary' danger disabled={running} onClick={this.remove}>删除</Button>

        </Button.Group>

    }
}
