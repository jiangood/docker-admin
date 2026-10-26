package io.github.jiangood.docker.admin.mail;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MailProxySettingsTest {

    @Test
    void typeParsingIsLenient() {
        assertEquals(MailProxySettings.Type.NONE, MailProxySettings.Type.parse(null));
        assertEquals(MailProxySettings.Type.NONE, MailProxySettings.Type.parse(""));
        assertEquals(MailProxySettings.Type.NONE, MailProxySettings.Type.parse("none"));
        assertEquals(MailProxySettings.Type.NONE, MailProxySettings.Type.parse("unknown"));
        assertEquals(MailProxySettings.Type.HTTP, MailProxySettings.Type.parse("http"));
        assertEquals(MailProxySettings.Type.HTTP, MailProxySettings.Type.parse(" HTTP "));
        assertEquals(MailProxySettings.Type.HTTP, MailProxySettings.Type.parse("https"));
        assertEquals(MailProxySettings.Type.SOCKS5, MailProxySettings.Type.parse("socks5"));
        assertEquals("socks5", MailProxySettings.Type.SOCKS5.code());
    }

    @Test
    void disabledProxyHasNoMailProperties() {
        MailProxySettings proxy = MailProxySettings.disabled();
        assertFalse(proxy.enabled());
        assertTrue(proxy.mailProperties().isEmpty());
    }

    @Test
    void enabledButIncompleteProxyHasNoMailProperties() {
        MailProxySettings noHost = new MailProxySettings(MailProxySettings.Type.HTTP, null, 8080, null, null);
        MailProxySettings noPort = new MailProxySettings(MailProxySettings.Type.HTTP, "192.168.1.10", null, null, null);
        assertTrue(noHost.enabled());
        assertFalse(noHost.configured());
        assertTrue(noHost.mailProperties().isEmpty());
        assertFalse(noPort.configured());
        assertTrue(noPort.mailProperties().isEmpty());
    }

    @Test
    void httpProxyMapsToAngusMailProperties() {
        MailProxySettings proxy = new MailProxySettings(
                MailProxySettings.Type.HTTP, " 192.168.1.10 ", 8080, " user ", "secret");
        Map<String, String> props = proxy.mailProperties();
        assertEquals("192.168.1.10", props.get("mail.smtp.proxy.host"));
        assertEquals("8080", props.get("mail.smtp.proxy.port"));
        assertEquals("user", props.get("mail.smtp.proxy.user"));
        assertEquals("secret", props.get("mail.smtp.proxy.password"));
        assertFalse(props.containsKey("mail.smtp.socks.host"));
        assertEquals("192.168.1.10:8080", proxy.address());
    }

    @Test
    void httpProxyWithoutUsernameSendsNoAuthorization() {
        MailProxySettings proxy = new MailProxySettings(
                MailProxySettings.Type.HTTP, "proxy.local", 3128, null, null);
        Map<String, String> props = proxy.mailProperties();
        assertFalse(props.containsKey("mail.smtp.proxy.user"));
        assertFalse(props.containsKey("mail.smtp.proxy.password"));
    }

    @Test
    void socks5ProxyMapsToSocksPropertiesOnly() {
        MailProxySettings proxy = new MailProxySettings(
                MailProxySettings.Type.SOCKS5, "192.168.1.10", 1080, "ignored", "ignored");
        Map<String, String> props = proxy.mailProperties();
        assertEquals("192.168.1.10", props.get("mail.smtp.socks.host"));
        assertEquals("1080", props.get("mail.smtp.socks.port"));
        assertFalse(props.containsKey("mail.smtp.proxy.host"));
        assertFalse(props.containsKey("mail.smtp.proxy.user"));
    }
}
