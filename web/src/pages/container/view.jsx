import React from 'react'
import {Alert, Button, Card, Checkbox, Modal, Result, Space, Spin, Tabs, Tag, Typography} from 'antd'
import {ReloadOutlined} from '@ant-design/icons'
import {HttpClient, Page, PageUtils, PermActions} from '@jiangood/open-admin'
import ContainerInfo from '../../components/container/ContainerInfo'
import ContainerConfig from '../../components/container/ContainerConfig'
import ContainerLogs from '../../components/container/ContainerLogs'
import ContainerFiles from '../../components/container/ContainerFiles'
import ContainerConsole from '../../components/container/ContainerConsole'
import {formatTime, isRunningState, stateColor, stateLabel, wsContainerLogPath} from '../../components/container/utils'

export const pageMeta = {
    title: '容器详情',
}

/** 页签内容区最小高度：窗口很矮时也不至于只剩一条缝 */
const MIN_PANE_HEIGHT = 360

/**
 * 视口里不属于页签内容的固定部分（后台页签条、页面内边距、基本信息卡、页签卡的内边距与页签条）。
 * 只是估算值，宁可略大一点让页面滚动，也不让日志 / 终端被裁掉。
 */
const VIEWPORT_CHROME = 350

/**
 * 容器详情页（独立页面，替代原来的容器弹窗）。
 * <p>
 * 入口：主机详情页的容器列表 / 监控表、应用详情页「容器日志」（应用详情页只保留日志，不再提供跳转入口）。
 * 参数：{@code hostId} + {@code containerId}（主机维度），或 {@code appId}（应用维度，额外校验数据权限），
 * {@code tab} 指定首次打开的页签（logs / info / files / console）。
 * <p>
 * 上方是基本信息与操作，下方是横向页签（日志 / 文件 / 终端 / 详情）。
 * 页签开启 destroyOnHidden：切走即关闭日志 / 终端 WebSocket，回来时重新拉取。
 */
export default class extends React.Component {

    state = {
        params: PageUtils.currentParams(),
        loading: true,
        detail: null,
        error: null,
        activeKey: 'logs',
        paneHeight: MIN_PANE_HEIGHT,

        // 删除确认弹窗：运行中的容器必须显式勾选「强制删除」（Docker 拒绝普通删除）
        removeOpen: false,
        removeForce: false,
        removing: false,
    }

    componentDidMount() {
        const {tab} = this.state.params
        this.setState({activeKey: tab || 'logs', paneHeight: this.measurePaneHeight()})
        window.addEventListener('resize', this.handleResize)
        this.load()
    }

    componentWillUnmount() {
        window.removeEventListener('resize', this.handleResize)
    }

    measurePaneHeight = () => Math.max(MIN_PANE_HEIGHT, (window.innerHeight || 0) - VIEWPORT_CHROME)

    handleResize = () => {
        const paneHeight = this.measurePaneHeight()
        if (paneHeight !== this.state.paneHeight) {
            this.setState({paneHeight})
        }
    }

    /** 读取容器 inspect：应用维度走 admin/app/containerDetail（带组织数据权限），主机维度走主机 + 容器 */
    load = () => {
        const {hostId, containerId, appId} = this.state.params
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

    /**
     * 容器操作：启动 / 停止 / 重启 / 删除。
     * 删除走 confirmRemove（需要「强制删除」勾选），不从这里直接调用。
     */
    action = (name, extra) => {
        const {hostId, containerId} = this.state.params
        const targetId = (this.state.detail && this.state.detail.id) || containerId
        return HttpClient.post(`admin/container/${name}`, null,
            {...extra, hostId, containerId: targetId}).then(() => {
            if (name !== 'remove') {
                setTimeout(this.load, 600)
            }
        })
    }

    openRemoveModal = () => {
        this.setState({removeOpen: true, removeForce: false, removing: false})
    }

    closeRemoveModal = () => {
        this.setState({removeOpen: false, removeForce: false, removing: false})
    }

    /**
     * 确认删除：运行中的容器必须勾选「强制删除」，否则 Docker 会直接拒绝；
     * 成功后关闭本页签回到来源页面，失败时保留弹窗方便重试（错误提示由 HttpClient 弹出）。
     */
    confirmRemove = () => {
        const {detail, removeForce} = this.state
        const force = detail && isRunningState(detail.state) ? removeForce : false
        this.setState({removing: true})
        this.action('remove', {force})
            .then(() => PageUtils.closeCurrent())
            .catch(() => {
            })
            .finally(() => this.setState({removing: false}))
    }

    render() {
        const {loading, detail, error, activeKey, paneHeight,
            removeOpen, removeForce, removing} = this.state
        const {hostId, containerId, appId} = this.state.params

        if (!containerId && !appId) {
            return <Page padding>
                <Result status='warning' title='缺少容器信息'
                        subTitle='请从主机详情或应用详情页的容器入口打开'/>
            </Page>
        }

        if (loading) {
            return <Page padding><div className='center-box'><Spin/></div></Page>
        }
        if (error) {
            return <Page padding><Alert type='error' showIcon title={error}/></Page>
        }
        if (!detail) {
            return <Page padding><Alert type='info' showIcon title='容器不存在'/></Page>
        }

        const running = !!detail.running
        // 删除确认弹窗里的「强制删除」只在容器处于运行 / 暂停 / 重启中时要求
        const removeRunning = isRunningState(detail.state)
        // 以 inspect 结果为准：应用重新部署后 URL 里的 containerId 可能已经过期
        const targetId = detail.id || containerId
        const logUrl = appId ? `/admin/ws/log/${appId}` : wsContainerLogPath(hostId, targetId)
        const logDownloadUrl = appId
            ? `/admin/app/containerLogDownload?id=${encodeURIComponent(appId)}`
            : `/admin/container/logDownload?hostId=${encodeURIComponent(hostId)}`
                + `&containerId=${encodeURIComponent(targetId)}`
        const logsUrl = appId
            ? `/admin/app/containerLogs?id=${encodeURIComponent(appId)}`
            : `/admin/container/logs?hostId=${encodeURIComponent(hostId)}`
                + `&containerId=${encodeURIComponent(targetId)}`

        const header = <Card size='small' className='mb-3' styles={{body: {padding: '12px 16px'}}}>
            <div style={{
                display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                gap: 16, flexWrap: 'wrap',
            }}>
                <div style={{minWidth: 0}}>
                    <Space size={8} wrap>
                        <Typography.Title level={4} style={{margin: 0, wordBreak: 'break-all'}}>
                            {detail.name}
                        </Typography.Title>
                        <Tag color={stateColor(detail.state)}>{stateLabel(detail.state)}</Tag>
                        {detail.status && <Typography.Text type='secondary'>{detail.status}</Typography.Text>}
                    </Space>
                    <div style={{marginTop: 6}}>
                        <Typography.Text type='secondary' style={{fontSize: 12}}>
                            镜像 {detail.image || '-'}　·　容器 ID {detail.idShort}　·　创建于 {formatTime(detail.created)}
                        </Typography.Text>
                    </div>
                </div>
                <Space>
                    <PermActions size='small' actions={[
                        {
                            label: '启动', perm: 'container:operate', disabled: running,
                            onClick: () => this.action('start').catch(() => {
                            })
                        },
                        {
                            label: '停止', perm: 'container:operate', disabled: !running,
                            onClick: () => this.action('stop').catch(() => {
                            })
                        },
                        {
                            label: '重启', perm: 'container:operate', disabled: !running,
                            onClick: () => this.action('restart').catch(() => {
                            })
                        },
                        {
                            label: '删除', perm: 'container:operate', danger: true,
                            onClick: this.openRemoveModal
                        },
                    ]}/>
                    <Button size='small' icon={<ReloadOutlined/>} onClick={this.load}>刷新</Button>
                </Space>
            </div>
        </Card>

        const items = [
            {
                key: 'logs',
                label: '日志',
                children: <ContainerLogs url={logUrl} downloadUrl={logDownloadUrl} logsUrl={logsUrl}
                                         height={paneHeight}/>
            },
            {
                key: 'files',
                label: '文件',
                children: <div style={{height: paneHeight, overflow: 'auto'}}>
                    {!hostId && !appId
                        ? <Alert type='warning' showIcon title='缺少主机信息，无法浏览文件'/>
                        : running
                            ? <ContainerFiles hostId={hostId} containerId={targetId}/>
                            : <Alert type='warning' showIcon title='容器未运行，无法浏览文件'/>}
                </div>
            },
            {
                key: 'console',
                label: '终端',
                children: !hostId && !appId
                    ? <Alert type='warning' showIcon title='缺少主机信息，无法打开终端'/>
                    : <ContainerConsole hostId={hostId} containerId={targetId} running={running}
                                            height={paneHeight}/>,
            },
            {
                key: 'info',
                label: '详情',
                // 长路径 / 长 ID 强制换行，避免表格撑宽内容区出现横向滚动条
                children: <div className='container-detail-pane' style={{height: paneHeight, overflowY: 'auto'}}>
                    <ContainerInfo detail={detail}/>
                    <ContainerConfig detail={detail}/>
                </div>
            },
        ]

        return <Page padding>
            {header}
            <Card size='small' styles={{body: {padding: 12}}}>
                <Tabs destroyOnHidden activeKey={activeKey} items={items}
                      onChange={key => this.setState({activeKey: key})}/>
            </Card>

            {/* 删除确认：运行中的容器必须显式勾选「强制删除」，否则 Docker 会拒绝普通删除 */}
            <Modal title='删除容器'
                   open={removeOpen}
                   okText='删除'
                   cancelText='取消'
                   okButtonProps={{danger: true, loading: removing, disabled: removeRunning && !removeForce}}
                   onOk={this.confirmRemove}
                   onCancel={this.closeRemoveModal}
                   destroyOnHidden
                   width={520}>
                <div>
                    确定删除容器 <Typography.Text strong>{detail.name}</Typography.Text>？删除后不可恢复。
                </div>
                {removeRunning && <>
                    <Alert className='mt-3' type='warning' showIcon
                           title='容器正在运行，普通删除会被 Docker 拒绝'
                           description='勾选下方「强制删除」后平台会先停止（kill）容器再删除；也可以先在「详情」页签点「停止」，等状态变成已退出后再删除。'/>
                    <Checkbox className='mt-3' checked={removeForce}
                              onChange={e => this.setState({removeForce: e.target.checked})}>
                        强制删除（停止并删除运行中的容器）
                    </Checkbox>
                </>}
            </Modal>
        </Page>
    }
}
