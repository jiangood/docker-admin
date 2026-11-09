import React from "react";
import {Alert, Button, DatePicker, message} from "antd";
import dayjs from "dayjs";
import {HttpClient} from "@jiangood/open-admin";
import LogView from "../LogView";

const {RangePicker} = DatePicker

/**
 * 容器日志：复用 LogView，走容器日志 WebSocket。
 * url 由上层根据上下文给出（主机维度通用 WS / 应用维度 app WS）。
 * 运行状态由页面头部展示，这里不再重复提示；容器停止后 WebSocket 只回放历史日志。
 * downloadUrl 为对应的日志下载接口（相对路径），logsUrl 为按时间段查询接口。
 * 选择时间范围后不再实时 tail，改为静态展示该时间段日志（不自动滚动）。
 */
export default class extends React.Component {

    state = {
        range: null,
        rangeSince: null,
        rangeUntil: null,
        rangeLoading: false,
        rangeText: null,
        rangeError: null,
        downloading: false,
    }

    _reqId = 0

    componentWillUnmount() {
        this._reqId++
    }

    rangePresets = () => [
        {label: '最近 5 分钟', value: [dayjs().subtract(5, 'minute'), dayjs()]},
        {label: '最近 15 分钟', value: [dayjs().subtract(15, 'minute'), dayjs()]},
        {label: '最近 1 小时', value: [dayjs().subtract(1, 'hour'), dayjs()]},
        {label: '最近 24 小时', value: [dayjs().subtract(24, 'hour'), dayjs()]},
    ]

    onRangeChange = (range) => {
        if (!range || !range[0] || !range[1]) {
            this.clearRange()
            return
        }
        if (!this.props.logsUrl) {
            return
        }
        const since = Math.floor(range[0].valueOf() / 1000)
        const until = Math.floor(range[1].valueOf() / 1000)
        const reqId = ++this._reqId
        this.setState({range, rangeSince: since, rangeUntil: until, rangeLoading: true, rangeText: null, rangeError: null})
        HttpClient.get(this.props.logsUrl, {since, until}, {toastError: false})
            .then(rs => {
                if (this._reqId !== reqId) {
                    return
                }
                this.setState({
                    rangeText: localizeTimestamps(rs.data == null ? '' : String(rs.data)),
                    rangeLoading: false,
                })
            })
            .catch(e => {
                if (this._reqId !== reqId) {
                    return
                }
                this.setState({rangeError: e.message || '按时间段查询日志失败', rangeLoading: false})
            })
    }

    clearRange = () => {
        this._reqId++
        this.setState({
            range: null,
            rangeSince: null,
            rangeUntil: null,
            rangeLoading: false,
            rangeText: null,
            rangeError: null,
        })
    }

    /**
     * 下载日志：走 HttpClient.download，超出「下载最多天数」时能正常弹出后端错误提示，
     * 而不是把错误 JSON 当成文件打开。筛选状态下带上同一时间段。
     */
    downloadLog = () => {
        const {downloadUrl} = this.props
        const {rangeSince, rangeUntil, downloading} = this.state
        if (!downloadUrl || downloading) {
            return
        }
        const params = rangeSince == null ? undefined : {since: rangeSince, until: rangeUntil}
        this.setState({downloading: true})
        const hide = message.loading('日志下载中…', 0)
        HttpClient.download({url: downloadUrl, params})
            .catch(() => {
            })
            .finally(() => {
                hide()
                this.setState({downloading: false})
            })
    }

    render() {
        const {url, height, downloadUrl, logsUrl} = this.props
        const {range, rangeSince, rangeUntil, rangeLoading, rangeText, rangeError, downloading} = this.state
        const filtered = !!range

        const toolbarExtra = <div style={{display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap'}}>
            <RangePicker size='small'
                         showTime
                         format='YYYY-MM-DD HH:mm:ss'
                         value={range}
                         presets={this.rangePresets()}
                         onChange={this.onRangeChange}
                         allowClear
                         placeholder={['开始时间', '结束时间']}
                         style={{width: 320}}/>
            {filtered && <Button size='small' type='link' style={{padding: 0}}
                                 onClick={this.clearRange}>返回实时日志</Button>}
            {filtered && !rangeLoading && !rangeError && <span style={{fontSize: 12, opacity: 0.65}}>
                {rangeText ? rangeText.split('\n').filter(line => line.length > 0).length : 0} 行
            </span>}
        </div>

        return <>
            {rangeError && <Alert type='error' showIcon title={rangeError} className='mb-2'/>}
            <LogView url={url} websocket height={height || 500}
                     text={filtered ? (rangeText || '') : null}
                     loading={rangeLoading}
                     toolbar
                     toolbarExtra={logsUrl ? toolbarExtra : null}
                     downloadUrl={downloadUrl}
                     onDownload={downloadUrl ? this.downloadLog : undefined}
                     downloadLoading={downloading}/>
        </>
    }
}

/**
 * Docker 时间戳是 UTC（RFC3339），按浏览器本地时区转成 YYYY-MM-DD HH:mm:ss.SSS，
 * 与时间范围选择器的显示保持一致；没有时间戳前缀的行原样返回。
 */
function localizeTimestamps(text) {
    if (!text) {
        return text
    }
    return text.split('\n').map(line => {
        const m = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})) (.*)$/.exec(line)
        if (!m) {
            return line
        }
        return dayjs(m[1].replace(/(\.\d{3})\d+/, '$1')).format('YYYY-MM-DD HH:mm:ss.SSS') + ' ' + m[2]
    }).join('\n')
}
