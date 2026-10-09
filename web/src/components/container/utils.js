import {UrlUtils} from "@jiangood/open-admin";

/**
 * 通用容器组件的公共工具：WebSocket 地址、状态展示、格式化。
 */

/** 通用容器日志 WebSocket 相对路径（交给 LogView 补全 contextPath 与 ws 协议）。 */
export function wsContainerLogPath(hostId, containerId) {
    return `/admin/ws/container-log/${encodeURIComponent(hostId)}/${encodeURIComponent(containerId)}`
}

/** 容器控制台 WebSocket 完整地址。 */
export function wsContainerExecUrl(hostId, containerId, shell) {
    const path = UrlUtils.contextPath(`/admin/ws/container-exec/${encodeURIComponent(hostId)}/${encodeURIComponent(containerId)}`)
    const query = shell ? `?shell=${encodeURIComponent(shell)}` : ''
    return UrlUtils.getWebsocketBaseUrl() + path + query
}

/** 容器状态对应的 antd Tag 颜色。 */
export function stateColor(state) {
    switch ((state || '').toLowerCase()) {
        case 'running':
            return 'green'
        case 'paused':
            return 'orange'
        case 'restarting':
            return 'gold'
        case 'created':
            return 'blue'
        case 'exited':
        case 'dead':
            return 'red'
        default:
            return 'default'
    }
}

/** 容器状态中文名。 */
export function stateLabel(state) {
    switch ((state || '').toLowerCase()) {
        case 'running':
            return '运行中'
        case 'paused':
            return '已暂停'
        case 'restarting':
            return '重启中'
        case 'created':
            return '已创建'
        case 'exited':
            return '已退出'
        case 'dead':
            return '已死亡'
        case 'notfound':
            return '未部署'
        default:
            return state || '-'
    }
}

/** 字节格式化。 */
export function formatBytes(size) {
    if (size === null || size === undefined || size === '') {
        return '-'
    }
    const n = Number(size)
    if (!Number.isFinite(n)) {
        return String(size)
    }
    if (n < 1024) {
        return n + ' B'
    }
    const units = ['KB', 'MB', 'GB', 'TB']
    let v = n / 1024
    let i = 0
    while (v >= 1024 && i < units.length - 1) {
        v /= 1024
        i++
    }
    return v.toFixed(v >= 10 ? 0 : 1) + ' ' + units[i]
}

/** docker created 时间戳（秒/ISO）格式化。 */
export function formatTime(value) {
    if (!value) {
        return '-'
    }
    const raw = String(value)
    // docker 未启动/未结束时返回零值时间
    if (raw.startsWith('0001-01-01')) {
        return '-'
    }
    let date
    if (typeof value === 'number' || /^\d+$/.test(raw)) {
        date = new Date(Number(value) * 1000)
    } else {
        date = new Date(value)
    }
    if (Number.isNaN(date.getTime())) {
        return raw
    }
    const pad = n => String(n).padStart(2, '0')
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} `
        + `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}
