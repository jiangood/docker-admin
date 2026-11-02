import {UrlUtils} from "@jiangood/open-admin";

/** 主机 Docker CLI WebSocket 完整地址。 */
export function wsHostCliUrl(hostId) {
    const path = UrlUtils.contextPath(`/admin/ws/host-cli/${encodeURIComponent(hostId)}`)
    return UrlUtils.getWebsocketBaseUrl() + path
}

/** 相对路径拼接：dir 为空表示挂载目录根。 */
export function joinRelative(dir, name) {
    const d = (dir || '').replace(/^\/+|\/+$/g, '')
    const n = (name || '').trim().replace(/^\/+|\/+$/g, '')
    return d ? `${d}/${n}` : n
}

/** 相对路径的上一级；根目录返回空串。 */
export function parentRelative(path) {
    const p = (path || '').replace(/^\/+|\/+$/g, '')
    if (!p) {
        return ''
    }
    const idx = p.lastIndexOf('/')
    return idx < 0 ? '' : p.substring(0, idx)
}
