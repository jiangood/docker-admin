import React from "react";
import {Alert, Button, DatePicker} from "antd";
import dayjs from "dayjs";
import {HttpClient} from "@jiangood/open-admin";
import LogView from "../LogView";
import ContainerHint from "./ContainerHint";

const {RangePicker} = DatePicker

/**
 * 容器日志：复用 LogView，走容器日志 WebSocket。
 * url 由上层根据上下文给出（主机维度通用 WS / 应用维度 app WS）。
 * 容器已停止时只提一句「仅历史日志」，用轻量提示而不是 Alert，不挤占日志区域。
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

    render() {
        const {url, height, containerKey, downloadUrl, logsUrl} = this.props
        const {range, rangeSince, rangeUntil, rangeLoading, rangeText, rangeError} = this.state
        const filtered = !!range

        // 时间段模式下下载同一段日志，普通模式仍下载最近若干行
        let effectiveDownloadUrl = downloadUrl
        if (filtered && downloadUrl) {
            effectiveDownloadUrl = `${downloadUrl}&since=${rangeSince}&until=${rangeUntil}`
        }

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
            <ContainerHint type='info' text='容器未运行，仅显示历史日志'
                           id={containerKey ? `logs-stopped:${containerKey}` : undefined} className='mb-2'/>
            {rangeError && <Alert type='error' showIcon title={rangeError} className='mb-2'/>}
            <LogView url={url} websocket height={height || 500}
                     text={filtered ? (rangeText || '') : null}
                     loading={rangeLoading}
                     toolbar
                     toolbarExtra={logsUrl ? toolbarExtra : null}
                     downloadUrl={effectiveDownloadUrl}/>
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
