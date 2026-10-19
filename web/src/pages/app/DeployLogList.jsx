import {Alert, Collapse, Empty, Spin, Tag, Typography} from 'antd';
import {
    CheckCircleFilled,
    CloseCircleFilled,
    Loading3QuartersOutlined
} from '@ant-design/icons';
import React from 'react';
import {DateUtils, getToken, HttpClient} from '@jiangood/open-admin';
import LogView from '../../components/LogView';

/** 部署进行中时的列表刷新间隔（毫秒）：新部署记录是 @Async 里才落库的，需要等它出现 */
const POLL_INTERVAL = 2000

/**
 * 单条日志最多重连次数。日志 WS 在「任务未登记为运行中」时会在几轮宽限后断开，
 * 部署刚发起时可能存在这个窗口，因此允许有限重连；超过次数即认定任务已结束，
 * 避免记录状态异常（如进程崩溃导致 success 永远为空）时无限重连。
 */
const MAX_WATCH_ATTEMPTS = 5

/** 单次部署状态：成功 / 失败 / 进行中（success 为空即进行中）。 */
function statusOf(log) {
    if (log.success === true) {
        return {text: '成功', icon: <CheckCircleFilled style={{color: getToken().colorSuccess}}/>}
    }
    if (log.success === false) {
        return {text: '失败', icon: <CloseCircleFilled style={{color: getToken().colorError}}/>}
    }
    return {text: '部署中', icon: <Loading3QuartersOutlined spin/>}
}

/**
 * 部署日志：按部署记录列出历史，默认展开最新一次，其余点击查看。
 * 每次部署一个独立日志文件（logId = 部署记录 id），不再把所有部署混在一起。
 * <p>
 * 进行中的部署走 WebSocket 实时 tail（与构建、镜像同步日志一致），任务结束后 WS 会补发
 * 剩余内容并断开，随后列表切换为一次性 HTTP 读取。部署记录由 @Async 任务在部署开始时才
 * 落库，因此列表需要轮询，直到最新记录从「部署中」变成终态。
 */
export default class extends React.Component {

    state = {
        loading: true,
        logs: [],
        // 当前展开的部署记录 id；默认最新一条
        activeKey: null,
        // logId -> 已重连次数，用于给 LogView 换 key 触发重连
        watchAttempts: {},
        // 已放弃实时跟随的 logId（WS 断开且不再重连），其记录不再驱动轮询
        watchStopped: {}
    }

    componentDidMount() {
        this._mounted = true
        this.loadLogs(true)
    }

    componentDidUpdate(prevProps) {
        // 部署刚发起：新记录要等 @Async 任务落库后才出现，先开始轮询
        if (this.props.deploying && !prevProps.deploying) {
            this.loadLogs()
        }
    }

    componentWillUnmount() {
        this._mounted = false
        clearTimeout(this.pollTimer)
    }

    /**
     * 拉取部署记录列表。
     * @param first 首次加载：显示 loading，并把最新一条设为展开项
     */
    loadLogs = (first = false) => {
        return HttpClient.get('admin/app/deployLogs', {id: this.props.appId}).then(rs => {
            if (!this._mounted) {
                return
            }
            const logs = rs.data || []
            this.setState(s => {
                const next = {loading: false, logs}
                if (first || !logs.some(log => String(log.id) === s.activeKey)) {
                    // 首次进入，或原先展开的记录已不在列表里（如切换应用）：展开最新一条
                    next.activeKey = logs.length ? String(logs[0].id) : null
                }
                return next
            }, () => this.schedulePoll(logs))
        }).catch(() => {
            if (this._mounted) {
                this.setState({loading: false})
            }
        })
    }

    /**
     * 还有需要继续观察的部署时继续轮询，避免空转。
     */
    schedulePoll = (logs) => {
        clearTimeout(this.pollTimer)
        const watching = (logs || this.state.logs).some(log =>
            log.success == null && !this.state.watchStopped[String(log.id)])
        if (watching || this.props.deploying) {
            this.pollTimer = setTimeout(() => this.loadLogs(), POLL_INTERVAL)
        }
    }

    /**
     * 日志 WS 断开：先刷新列表拿最终状态；记录仍在部署中才有限重连，超过次数即停止跟随。
     * 任务正常结束时后端已先写库再断开，因此这次刷新通常能直接拿到终态，无需重连。
     */
    handleLogClose = (logId) => {
        if (!this._mounted) {
            return
        }
        const key = String(logId)
        HttpClient.get('admin/app/deployLogs', {id: this.props.appId}).then(rs => {
            if (!this._mounted) {
                return
            }
            const logs = rs.data || []
            const log = logs.find(l => String(l.id) === key)
            const stillRunning = !!log && log.success == null
            this.setState(s => {
                const attempts = stillRunning ? (s.watchAttempts[key] || 0) + 1 : (s.watchAttempts[key] || 0)
                const stopped = stillRunning && attempts >= MAX_WATCH_ATTEMPTS
                return {
                    loading: false,
                    logs,
                    watchAttempts: {...s.watchAttempts, [key]: attempts},
                    watchStopped: stopped ? {...s.watchStopped, [key]: true} : s.watchStopped
                }
            }, () => this.schedulePoll(logs))
        }).catch(() => this.loadLogs())
    }

    render() {
        const {loading, logs, activeKey} = this.state
        if (loading) {
            return <Spin/>
        }
        if (!logs.length) {
            return <Empty description='暂无部署记录'/>
        }

        const items = logs.map(log => ({
            key: String(log.id),
            label: this.renderLabel(log),
            children: this.renderLog(log)
        }))

        return <Collapse
            accordion
            destroyOnHidden
            activeKey={activeKey}
            onChange={key => this.setState({activeKey: Array.isArray(key) ? key[0] : key})}
            items={items}
        />
    }

    renderLabel = (log) => {
        const status = statusOf(log)
        const duration = log.timeSpend != null ? DateUtils.friendlyTotalTime(log.timeSpend) : null
        return <span style={{display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap'}}>
            {status.icon}
            <span>{log.createTime}</span>
            <Typography.Text type='secondary'>{status.text}</Typography.Text>
            {log.imageTag ? <Tag>{log.imageTag}</Tag> : null}
            {duration ? <Typography.Text type='secondary'>耗时 {duration}</Typography.Text> : null}
        </span>
    }

    renderLog = (log) => {
        const key = String(log.id)

        // 进行中：日志文件可能刚创建，走 WebSocket 实时 tail
        if (log.success == null && !this.state.watchStopped[key]) {
            return <LogView key={key + '-' + (this.state.watchAttempts[key] || 0)}
                            url={'/admin/ws/deploy-log/' + key} websocket={true}
                            onClose={() => this.handleLogClose(key)}/>
        }
        if (!log.logExists) {
            // 旧版本记录：同一应用的多次部署共用 appId.log，没有按次拆分的独立日志
            return <Alert type='warning' showIcon
                          title='该次部署为历史记录，没有按次拆分的独立日志'/>
        }
        return <LogView key={key} url={'/admin/sys/log/' + key} websocket={false}/>
    }

}
