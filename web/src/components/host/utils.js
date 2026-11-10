import {UrlUtils} from "@jiangood/open-admin";

/**
 * 主机 Docker CLI WebSocket 完整地址。
 *
 * @param hostId 主机 id
 * @param session 标签页会话令牌（sessionStorage），断线重连后接回同一个 shell；
 *                缺省时服务端按一次性会话处理
 */
export function wsHostCliUrl(hostId, session) {
    const query = session ? `?session=${encodeURIComponent(session)}` : ''
    const path = UrlUtils.contextPath(`/admin/ws/host-cli/${encodeURIComponent(hostId)}${query}`)
    return UrlUtils.getWebsocketBaseUrl() + path
}
