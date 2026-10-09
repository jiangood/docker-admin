import React from "react";
import {Alert, Button, Popconfirm, Space, Spin, Tabs, Tag, Typography} from "antd";
import {ReloadOutlined} from "@ant-design/icons";
import {HttpClient, PermActions} from "@jiangood/open-admin";
import ContainerInfo from "./ContainerInfo";
import ContainerConfig from "./ContainerConfig";
import ContainerLogs from "./ContainerLogs";
import ContainerFiles from "./ContainerFiles";
import ContainerConsole from "./ContainerConsole";
import {stateColor, stateLabel, wsContainerLogPath} from "./utils";

/**
 * 通用容器组件：垂直 Tab 展示概览 / 日志 / 配置 / 文件 / 终端。
 * 参照 Docker Desktop，按 hostId + containerId 定位容器，可用于主机详情与应用详情。
 * <p>
 * 传入 appId 时会改用应用维度的接口（额外校验组织数据权限）：
 * 详情 {@code admin/app/containerDetail}、日志 {@code /admin/ws/log/{appId}}。
 */
export default class extends React.Component {

    state = {
        loading: true,
        detail: null,
        error: null,
        activeKey: 'info',
    }

    componentDidMount() {
        this.load()
    }

    load = () => {
        const {hostId, containerId, appId} = this.props
        const request = appId
            ? HttpClient.get('admin/app/containerDetail', {id: appId}, {toastError: false})
            : (hostId && containerId
                ? HttpClient.get('admin/container/inspect', {hostId, containerId}, {toastError: false})
                : null)
        if (!request) {
            this.setState({loading: false, error: '缺少容器信息'})
            return
        }
        this.setState({loading: true, error: null})
        request
            .then(rs => this.setState({detail: rs.data, loading: false}))
            .catch(e => this.setState({detail: null, loading: false, error: e.message || '读取容器信息失败'}))
    }

    action = name => {
        const {hostId, containerId} = this.props
        HttpClient.post(`admin/container/${name}`, null, {hostId, containerId}).then(rs => {
            if (name === 'remove') {
                // 容器已删除，关闭/通知上层（避免继续 inspect 报“资源不存在”）
                if (this.props.onClose) {
                    this.props.onClose()
                } else {
                    this.load()
                }
                return
            }
            setTimeout(this.load, 600)
        })
    }

    render() {
        const {loading, detail, error, activeKey} = this.state
        const {height, extra} = this.props

        if (loading) {
            return <div className='center-box'><Spin/></div>
        }
        if (error) {
            return <Alert type='error' showIcon title={error}/>
        }
        if (!detail) {
            return <Alert type='info' showIcon title='容器不存在'/>
        }

        const running = !!detail.running
        const tabHeight = height || 460
        const logUrl = this.props.appId
            ? `/admin/ws/log/${this.props.appId}`
            : wsContainerLogPath(this.props.hostId, this.props.containerId)

        const items = [
            {
                key: 'info',
                label: '概览',
                children: <div style={{height: tabHeight, overflow: 'auto'}}><ContainerInfo detail={detail}/></div>
            },
            {
                key: 'logs',
                label: '日志',
                children: <ContainerLogs url={logUrl} running={running} height={tabHeight}/>
            },
            {
                key: 'config',
                label: '配置',
                children: <div style={{height: tabHeight, overflow: 'auto'}}><ContainerConfig detail={detail}/></div>
            },
            {
                key: 'files',
                label: '文件',
                children: <div style={{height: tabHeight, overflow: 'auto'}}>
                    {running
                        ? <ContainerFiles hostId={this.props.hostId} containerId={this.props.containerId}/>
                        : <Alert type='warning' showIcon title='容器未运行，无法浏览文件'/>}
                </div>
            },
            {
                key: 'console',
                label: '终端',
                children: <ContainerConsole hostId={this.props.hostId} containerId={this.props.containerId}
                                            running={running} height={tabHeight - 40}/>
            },
        ]

        return <>
            <div className='mb-3 gap-3' style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center'}}>
                <Space wrap>
                    <Typography.Title level={5} style={{margin: 0}}>{detail.name}</Typography.Title>
                    <Tag color={stateColor(detail.state)}>{stateLabel(detail.state)}</Tag>
                    <Typography.Text type='secondary'>{detail.idShort}</Typography.Text>
                </Space>
                <Space>
                    {extra}
                    <PermActions>
                        <Button perm='container:operate' size='small' disabled={running}
                                onClick={() => this.action('start')}>启动</Button>
                        <Button perm='container:operate' size='small' disabled={!running}
                                onClick={() => this.action('stop')}>停止</Button>
                        <Button perm='container:operate' size='small' disabled={!running}
                                onClick={() => this.action('restart')}>重启</Button>
                        <Popconfirm perm='container:operate' title='确定删除该容器？删除后不可恢复'
                                    onConfirm={() => this.action('remove')}>
                            <Button perm='container:operate' size='small' danger>删除</Button>
                        </Popconfirm>
                    </PermActions>
                    <Button size='small' icon={<ReloadOutlined/>} onClick={this.load}>刷新</Button>
                </Space>
            </div>

            <Tabs tabPlacement='start' activeKey={activeKey} items={items}
                  onChange={key => this.setState({activeKey: key})}/>
        </>
    }
}
