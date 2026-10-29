import React from "react";
import {Alert, Button, Space, Spin, Tabs, Tag, Typography} from "antd";
import {ReloadOutlined} from "@ant-design/icons";
import {HttpClient, PermActions} from "@jiangood/open-admin";
import ContainerInfo from "./ContainerInfo";
import ContainerConfig from "./ContainerConfig";
import ContainerLogs from "./ContainerLogs";
import ContainerFiles from "./ContainerFiles";
import ContainerConsole from "./ContainerConsole";
import {stateColor, stateLabel, wsContainerLogPath} from "./utils";

/**
 * 通用容器组件：垂直 Tab 展示日志 / 概览 / 文件 / 终端（日志在最前，方便排查）。
 * 参照 Docker Desktop，按 hostId + containerId 定位容器，可用于主机详情与应用详情。
 * 容器名称、状态、ID、操作按钮与配置信息位于「概览」Tab 内部。
 * <p>
 * 传入 appId 时会改用应用维度的接口（额外校验组织数据权限）：
 * 详情 {@code admin/app/containerDetail}、日志 {@code /admin/ws/log/{appId}}。
 */
export default class extends React.Component {

    state = {
        loading: true,
        detail: null,
        error: null,
        activeKey: 'logs',
    }

    componentDidMount() {
        this.load()
    }

    componentDidUpdate(prevProps) {
        // 上层不再靠卸载重建本组件（避免整块骨架屏），容器漂移（如重新部署换了容器）时自行重新拉取
        if (prevProps.containerId !== this.props.containerId) {
            this.load()
        }
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

        const header = <div className='mb-3 gap-3'
                            style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center'}}>
            <Space wrap>
                <Typography.Title level={5} style={{margin: 0}}>{detail.name}</Typography.Title>
                <Tag color={stateColor(detail.state)}>{stateLabel(detail.state)}</Tag>
                <Typography.Text type='secondary'>{detail.idShort}</Typography.Text>
            </Space>
            <Space>
                {extra}
                <PermActions size='small' actions={[
                    {label: '启动', perm: 'container:operate', disabled: running, onClick: () => this.action('start')},
                    {label: '停止', perm: 'container:operate', disabled: !running, onClick: () => this.action('stop')},
                    {label: '重启', perm: 'container:operate', disabled: !running, onClick: () => this.action('restart')},
                    {
                        label: '删除', perm: 'container:operate', danger: true,
                        confirm: '确定删除该容器？删除后不可恢复', onClick: () => this.action('remove')
                    },
                ]}/>
                <Button size='small' icon={<ReloadOutlined/>} onClick={this.load}>刷新</Button>
            </Space>
        </div>

        const items = [
            {
                key: 'logs',
                label: '日志',
                children: <ContainerLogs url={logUrl} running={running} height={tabHeight}/>
            },
            {
                key: 'info',
                label: '概览',
                children: <div style={{height: tabHeight, display: 'flex', flexDirection: 'column'}}>
                    {header}
                    <div style={{flex: 1, overflow: 'auto'}}>
                        <ContainerInfo detail={detail}/>
                        <ContainerConfig detail={detail}/>
                    </div>
                </div>
            },
            {
                key: 'files',
                label: '文件',
                children: <div style={{height: tabHeight, overflow: 'auto'}}>
                    {running
                        ? <ContainerFiles hostId={this.props.hostId} containerId={this.props.containerId}
                                         mounts={detail.mounts}/>
                        : <Alert type='warning' showIcon title='容器未运行，无法浏览文件'/>}
                </div>
            },
            {
                key: 'console',
                label: '终端',
                children: <ContainerConsole hostId={this.props.hostId} containerId={this.props.containerId}
                                            mounts={detail.mounts} running={running} height={tabHeight}/>
            },
        ]

        return <Tabs tabPlacement='start' activeKey={activeKey} items={items}
                     onChange={key => this.setState({activeKey: key})}/>
    }
}
