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

/**
 * 转数字：后端把包装类型 Long 序列化成字符串（避免 JS 丢精度），
 * 直接参与 `+` 会变成字符串拼接，参与计算前必须先转成 number。
 */
export function toNumber(value) {
    const n = Number(value)
    return Number.isFinite(n) ? n : 0
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

/** 规范化容器内路径：补前导斜杠、去掉末尾斜杠，根目录仍为 '/'。 */
function normalizeContainerPath(path) {
    const p = ('/' + String(path || '').replace(/^\/+/, '')).replace(/\/+$/, '')
    return p || '/'
}

/**
 * 容器内可写挂载点（mounts 的 destination）：只有这些目录下的内容在容器重建后保留。
 * 匿名卷（type=volume 但没有名称）重建时通常会换成新卷，因此不计入。
 */
export function writableMountDestinations(mounts) {
    return (mounts || [])
        .filter(m => m && m.destination && !m.readOnly)
        .filter(m => m.type !== 'volume' || (m.name && String(m.name).trim()))
        .map(m => normalizeContainerPath(m.destination))
        .filter((d, i, arr) => arr.indexOf(d) === i)
}

/** 容器内路径是否位于某个可写挂载点之下（挂载点本身也算）。 */
export function isPersistentPath(path, mounts) {
    const p = normalizeContainerPath(path)
    return writableMountDestinations(mounts).some(d => d === '/' || p === d || p.startsWith(d + '/'))
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

/** 毫秒时间戳格式化为 HH:mm:ss（「更新于」这类文案用）。 */
export function formatClock(value) {
    if (!value) {
        return '-'
    }
    const date = new Date(Number(value))
    if (Number.isNaN(date.getTime())) {
        return '-'
    }
    const pad = n => String(n).padStart(2, '0')
    return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

/** 百分比格式化：空值显示 -，默认保留 1 位小数。 */
export function formatPercent(value, digits = 1) {
    if (value === null || value === undefined || value === '') {
        return '-'
    }
    const n = Number(value)
    return Number.isFinite(n) ? n.toFixed(digits) + '%' : '-'
}

/** 用量进度条颜色：越接近上限越警示（CPU / 内存共用）。 */
export function usageColor(percent) {
    const n = Number(percent)
    if (!Number.isFinite(n)) {
        return undefined
    }
    if (n >= 90) {
        return '#ff4d4f'
    }
    if (n >= 70) {
        return '#faad14'
    }
    return '#52c41a'
}
