import {Alert, Collapse, Empty, Spin, Tag, Typography} from 'antd';
import {
    CheckCircleFilled,
    CloseCircleFilled,
    Loading3QuartersOutlined
} from '@ant-design/icons';
import React from 'react';
import {DateUtils, getToken, HttpClient} from '@jiangood/open-admin';
import LogView from '../../components/LogView';

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
 */
export default class extends React.Component {

    state = {
        loading: true,
        logs: [],
        // 当前展开的部署记录 id；默认最新一条
        activeKey: null
    }

    componentDidMount() {
        HttpClient.get('admin/app/deployLogs', {id: this.props.appId}).then(rs => {
            const logs = rs.data || []
            this.setState({
                loading: false,
                logs,
                activeKey: logs.length ? String(logs[0].id) : null
            })
        }).catch(() => this.setState({loading: false}))
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
        if (!log.logExists) {
            // 进行中的部署：日志文件还没写出第一行（已落库、任务尚未开始写日志）
            if (log.success == null) {
                return <Alert type='info' showIcon title='部署进行中，日志稍后展示'/>
            }
            // 旧版本记录：同一应用的多次部署共用 appId.log，没有按次拆分的独立日志
            return <Alert type='warning' showIcon
                          title='该次部署为历史记录，没有按次拆分的独立日志'/>
        }
        return <LogView key={log.id} url={'/admin/sys/log/' + log.id} websocket={false}/>
    }

}
