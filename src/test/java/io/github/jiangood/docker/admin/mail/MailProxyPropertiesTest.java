package io.github.jiangood.docker.admin.mail;

import io.github.jiangood.docker.admin.proxy.ProxySettings;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 邮件侧只看「代理配置能翻译成哪些 JavaMail 属性」，解析规则由 {@link ProxySettings} 负责。
 */
class MailProxyPropertiesTest {

    @Test
    void disabledProxyHasNoMailProperties() {
        assertTrue(MailProxyProperties.of(ProxySettings.disabled()).isEmpty());
        assertTrue(MailProxyProperties.of(null).isEmpty());
    }

    @Test
    void enabledButIncompleteProxyHasNoMailProperties() {
        ProxySettings noHost = new ProxySettings(ProxySettings.Type.HTTP, null, 8080, null, null);
        ProxySettings noPort = new ProxySettings(ProxySettings.Type.HTTP, "192.168.1.10", null, null, null);
        assertTrue(noHost.enabled());
        assertFalse(noHost.configured());
        assertTrue(MailProxyProperties.of(noHost).isEmpty());
        assertFalse(noPort.configured());
        assertTrue(MailProxyProperties.of(noPort).isEmpty());
    }

    @Test
    void httpProxyMapsToAngusMailProperties() {
        ProxySettings proxy = new ProxySettings(
                ProxySettings.Type.HTTP, " 192.168.1.10 ", 8080, " user ", "secret");
        Map<String, String> props = MailProxyProperties.of(proxy);
        assertEquals("192.168.1.10", props.get("mail.smtp.proxy.host"));
        assertEquals("8080", props.get("mail.smtp.proxy.port"));
        assertEquals("user", props.get("mail.smtp.proxy.user"));
        assertEquals("secret", props.get("mail.smtp.proxy.password"));
        assertFalse(props.containsKey("mail.smtp.socks.host"));
        assertEquals("192.168.1.10:8080", proxy.address());
    }

    @Test
    void httpProxyWithoutUsernameSendsNoAuthorization() {
        ProxySettings proxy = new ProxySettings(
                ProxySettings.Type.HTTP, "proxy.local", 3128, null, null);
        Map<String, String> props = MailProxyProperties.of(proxy);
        assertFalse(props.containsKey("mail.smtp.proxy.user"));
        assertFalse(props.containsKey("mail.smtp.proxy.password"));
    }

    @Test
    void socks5ProxyMapsToSocksPropertiesOnly() {
        // JDK 的 SOCKS 实现不支持账号密码，凭据在这里被忽略（保存时就会拒绝这类配置）
        ProxySettings proxy = new ProxySettings(
                ProxySettings.Type.SOCKS5, "192.168.1.10", 1080, "ignored", "ignored");
        Map<String, String> props = MailProxyProperties.of(proxy);
        assertEquals("192.168.1.10", props.get("mail.smtp.socks.host"));
        assertEquals("1080", props.get("mail.smtp.socks.port"));
        assertFalse(props.containsKey("mail.smtp.proxy.host"));
        assertFalse(props.containsKey("mail.smtp.proxy.user"));
    }
}
