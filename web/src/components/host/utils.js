import {UrlUtils} from "@jiangood/open-admin";

/** 主机 Docker CLI WebSocket 完整地址。 */
export function wsHostCliUrl(hostId) {
    const path = UrlUtils.contextPath(`/admin/ws/host-cli/${encodeURIComponent(hostId)}`)
    return UrlUtils.getWebsocketBaseUrl() + path
}
