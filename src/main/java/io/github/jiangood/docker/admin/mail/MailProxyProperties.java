package io.github.jiangood.docker.admin.mail;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.proxy.ProxySettings;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 {@link ProxySettings} 翻译成 Angus Mail（jakarta.mail 的实现）认识的代理属性。
 * <p>
 * 直接用 JavaMail 内置的代理能力，不自己写隧道：
 * <ul>
 *   <li>HTTP：{@code mail.smtp.proxy.*}，由 JavaMail 发 {@code CONNECT} 建隧道，支持 Basic 认证；</li>
 *   <li>SOCKS5：{@code mail.smtp.socks.*}，走 JDK 的 SOCKS 实现，<b>不支持账号密码</b>。</li>
 * </ul>
 * 两者都在 SSL（465）与 STARTTLS（587）之前建好 TCP 通道，TLS 仍是端到端到 SMTP 服务器的，
 * 所以证书校验行为与直连一致（JavaMail 默认不校验主机名）。
 * <p>
 * SOCKS5 的账号密码在这里被忽略（JavaMail 无此能力）：界面保存时已由
 * {@code SystemConfigService#saveEmailProxy} 拒绝这类配置，这里只是不做二次转发。
 */
public final class MailProxyProperties {

    private MailProxyProperties() {
    }

    /** JavaMail 需要的代理属性；未启用或配置不完整时返回空。 */
    public static Map<String, String> of(ProxySettings proxy) {
        Map<String, String> props = new LinkedHashMap<>();
        if (proxy == null || !proxy.configured()) {
            return props;
        }
        if (proxy.type() == ProxySettings.Type.SOCKS5) {
            props.put("mail.smtp.socks.host", proxy.host());
            props.put("mail.smtp.socks.port", String.valueOf(proxy.port()));
            return props;
        }
        props.put("mail.smtp.proxy.host", proxy.host());
        props.put("mail.smtp.proxy.port", String.valueOf(proxy.port()));
        if (StrUtil.isNotBlank(proxy.username())) {
            // JavaMail 只在 user 与 password 都非 null 时才发 Proxy-Authorization
            props.put("mail.smtp.proxy.user", proxy.username());
            props.put("mail.smtp.proxy.password", StrUtil.nullToEmpty(proxy.password()));
        }
        return props;
    }
}
