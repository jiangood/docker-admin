/**
 * 代理地址（一行）的前端校验：主机接入与邮件发送共用。
 *
 * 只挡明显写错的格式，scheme / 端口 / SOCKS5 认证等细节以后端解析为准
 * （后端见 io.github.jiangood.docker.admin.proxy.ProxySettings）。
 */
export function validateProxyUrl(_, value) {
    const v = (value || '').trim()
    if (!v) {
        return Promise.resolve()
    }
    const ok = /^[a-z][a-z0-9+.-]*:\/\/\S+$/i.test(v) || /^(\S+@)?[\w.-]+:\d+$/.test(v)
    return ok ? Promise.resolve() : Promise.reject(new Error('格式如 http://192.168.1.10:8080'))
}
