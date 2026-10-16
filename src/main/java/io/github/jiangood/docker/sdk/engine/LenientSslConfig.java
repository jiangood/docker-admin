package io.github.jiangood.docker.sdk.engine;

import com.github.dockerjava.core.SSLConfig;

import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLContextSpi;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.SocketChannel;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * 宽松 TLS 配置：<b>信任任意服务端证书，并跳过主机名校验</b>，用于连接自签证书、
 * 或证书里没有域名（CN/SAN 与访问地址不一致）的 Docker API 网关。
 * <p>
 * 为什么要绕这么一圈：docker-java 3.7.1 的传输层 {@code ApacheDockerHttpClientImpl} 固定用
 * {@code new DefaultClientTlsStrategy(sslContext)} 构造 TLS 策略，其主机名校验策略为
 * {@code BUILTIN}——握手前会调用
 * {@code SSLParameters#setEndpointIdentificationAlgorithm("HTTPS")}，之后由 JSSE 内部按证书
 * 的 SAN/CN 校验主机名。这一步发生在 JSSE 的信任链里，<b>自定义 TrustManager 也绕不过去</b>
 * （实测：信任所有证书仍然报 {@code No subject alternative DNS name matching xxx found}）。
 * <p>
 * 传输层没开放任何开关，只能在 {@code SSLContext} 的 SocketFactory 上做手脚：返回包装过的
 * {@link SSLSocket}，把 {@code endpointIdentificationAlgorithm} 抹掉，于是只剩「信任任意证书」生效。
 * <p>
 * <b>安全性</b>：不再有 TLS 身份保证（可被中间人冒充），仅适用于受控内网 / 自签证书场景。
 * 是否启用由主机连接配置里的 {@code https://} 前缀决定，见 {@link DockerClientManager}。
 */
class LenientSslConfig implements SSLConfig {

    /**
     * 信任任意证书：只保留「加密」，不要「身份」。
     */
    private static final TrustManager[] TRUST_ALL = {
            new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }
    };

    @Override
    public SSLContext getSSLContext() throws KeyManagementException, NoSuchAlgorithmException {
        SSLContext delegate = SSLContext.getInstance("TLS");
        delegate.init(null, TRUST_ALL, new SecureRandom());
        return LenientSslContext.wrap(delegate);
    }

    /**
     * 用自实现的 {@link SSLContextSpi} 包一层，好让 {@code getSocketFactory()} 返回我们的工厂
     * （{@code SSLContext#getSocketFactory()} 是 final，没法直接重写）。
     */
    private static final class LenientSslContext extends SSLContext {

        private static final Provider PROVIDER =
                new Provider("docker-admin-lenient-tls", "1.0", "信任任意证书、跳过主机名校验的 TLS") {
                };

        private LenientSslContext(SSLContext delegate) {
            super(new DelegateSpi(delegate), PROVIDER, "TLS");
        }

        static SSLContext wrap(SSLContext delegate) {
            return new LenientSslContext(delegate);
        }
    }

    private static final class DelegateSpi extends SSLContextSpi {

        private final SSLContext delegate;
        private final SSLSocketFactory socketFactory;

        DelegateSpi(SSLContext delegate) {
            this.delegate = delegate;
            this.socketFactory = new LenientSocketFactory(delegate.getSocketFactory());
        }

        @Override
        protected void engineInit(KeyManager[] km, TrustManager[] tm, SecureRandom random) throws KeyManagementException {
            // 委托上下文在 wrap 之前已经初始化过，不允许再改，避免覆盖掉宽松 TrustManager
            throw new KeyManagementException("宽松 TLS 上下文不支持重新初始化");
        }

        @Override
        protected SSLSocketFactory engineGetSocketFactory() {
            return socketFactory;
        }

        @Override
        protected SSLServerSocketFactory engineGetServerSocketFactory() {
            return delegate.getServerSocketFactory();
        }

        @Override
        protected SSLEngine engineCreateSSLEngine() {
            return delegate.createSSLEngine();
        }

        @Override
        protected SSLEngine engineCreateSSLEngine(String host, int port) {
            return delegate.createSSLEngine(host, port);
        }

        @Override
        protected SSLSessionContext engineGetServerSessionContext() {
            return delegate.getServerSessionContext();
        }

        @Override
        protected SSLSessionContext engineGetClientSessionContext() {
            return delegate.getClientSessionContext();
        }
    }

    private static final class LenientSocketFactory extends SSLSocketFactory {

        private final SSLSocketFactory delegate;

        LenientSocketFactory(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return wrap(delegate.createSocket(s, host, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return wrap(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return wrap(delegate.createSocket(host, port, localHost, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return wrap(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return wrap(delegate.createSocket(address, port, localAddress, localPort));
        }

        private static Socket wrap(Socket socket) {
            return socket instanceof SSLSocket ? new LenientSocket((SSLSocket) socket) : socket;
        }
    }

    /**
     * SSL 套接字包装。
     * <p>
     * 真正改行为的只有 {@link #setSSLParameters}（抹掉主机名校验算法）；
     * SSL 与 Socket 两层的方法必须<b>全部</b>委托给被包装的套接字——hc5 拿到这个对象后
     * 会直接拿它做读写、超时、关闭，漏掉任何一个都会踩到未连接的"空壳"套接字。
     */
    private static final class LenientSocket extends SSLSocket {

        private final SSLSocket delegate;

        LenientSocket(SSLSocket delegate) {
            this.delegate = delegate;
        }

        @Override
        public void setSSLParameters(SSLParameters params) {
            params.setEndpointIdentificationAlgorithm(null);
            delegate.setSSLParameters(params);
        }

        // ------------------------------------------------------------------ SSL 层

        @Override
        public SSLParameters getSSLParameters() {
            return delegate.getSSLParameters();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public String[] getEnabledCipherSuites() {
            return delegate.getEnabledCipherSuites();
        }

        @Override
        public void setEnabledCipherSuites(String[] suites) {
            delegate.setEnabledCipherSuites(suites);
        }

        @Override
        public String[] getSupportedProtocols() {
            return delegate.getSupportedProtocols();
        }

        @Override
        public String[] getEnabledProtocols() {
            return delegate.getEnabledProtocols();
        }

        @Override
        public void setEnabledProtocols(String[] protocols) {
            delegate.setEnabledProtocols(protocols);
        }

        @Override
        public SSLSession getSession() {
            return delegate.getSession();
        }

        @Override
        public SSLSession getHandshakeSession() {
            return delegate.getHandshakeSession();
        }

        @Override
        public void addHandshakeCompletedListener(HandshakeCompletedListener listener) {
            delegate.addHandshakeCompletedListener(listener);
        }

        @Override
        public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) {
            delegate.removeHandshakeCompletedListener(listener);
        }

        @Override
        public void startHandshake() throws IOException {
            delegate.startHandshake();
        }

        @Override
        public void setUseClientMode(boolean mode) {
            delegate.setUseClientMode(mode);
        }

        @Override
        public boolean getUseClientMode() {
            return delegate.getUseClientMode();
        }

        @Override
        public void setNeedClientAuth(boolean need) {
            delegate.setNeedClientAuth(need);
        }

        @Override
        public boolean getNeedClientAuth() {
            return delegate.getNeedClientAuth();
        }

        @Override
        public void setWantClientAuth(boolean want) {
            delegate.setWantClientAuth(want);
        }

        @Override
        public boolean getWantClientAuth() {
            return delegate.getWantClientAuth();
        }

        @Override
        public void setEnableSessionCreation(boolean flag) {
            delegate.setEnableSessionCreation(flag);
        }

        @Override
        public boolean getEnableSessionCreation() {
            return delegate.getEnableSessionCreation();
        }

        // ------------------------------------------------------------------ Socket 层

        @Override
        public void connect(SocketAddress endpoint) throws IOException {
            delegate.connect(endpoint);
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            delegate.connect(endpoint, timeout);
        }

        @Override
        public void bind(SocketAddress bindpoint) throws IOException {
            delegate.bind(bindpoint);
        }

        @Override
        public InetAddress getInetAddress() {
            return delegate.getInetAddress();
        }

        @Override
        public InetAddress getLocalAddress() {
            return delegate.getLocalAddress();
        }

        @Override
        public int getPort() {
            return delegate.getPort();
        }

        @Override
        public int getLocalPort() {
            return delegate.getLocalPort();
        }

        @Override
        public SocketAddress getRemoteSocketAddress() {
            return delegate.getRemoteSocketAddress();
        }

        @Override
        public SocketAddress getLocalSocketAddress() {
            return delegate.getLocalSocketAddress();
        }

        @Override
        public SocketChannel getChannel() {
            return delegate.getChannel();
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return delegate.getInputStream();
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return delegate.getOutputStream();
        }

        @Override
        public void setTcpNoDelay(boolean on) throws SocketException {
            delegate.setTcpNoDelay(on);
        }

        @Override
        public boolean getTcpNoDelay() throws SocketException {
            return delegate.getTcpNoDelay();
        }

        @Override
        public void setSoLinger(boolean on, int linger) throws SocketException {
            delegate.setSoLinger(on, linger);
        }

        @Override
        public int getSoLinger() throws SocketException {
            return delegate.getSoLinger();
        }

        @Override
        public void sendUrgentData(int data) throws IOException {
            delegate.sendUrgentData(data);
        }

        @Override
        public void setOOBInline(boolean on) throws SocketException {
            delegate.setOOBInline(on);
        }

        @Override
        public boolean getOOBInline() throws SocketException {
            return delegate.getOOBInline();
        }

        @Override
        public void setSoTimeout(int timeout) throws SocketException {
            delegate.setSoTimeout(timeout);
        }

        @Override
        public int getSoTimeout() throws SocketException {
            return delegate.getSoTimeout();
        }

        @Override
        public void setSendBufferSize(int size) throws SocketException {
            delegate.setSendBufferSize(size);
        }

        @Override
        public int getSendBufferSize() throws SocketException {
            return delegate.getSendBufferSize();
        }

        @Override
        public void setReceiveBufferSize(int size) throws SocketException {
            delegate.setReceiveBufferSize(size);
        }

        @Override
        public int getReceiveBufferSize() throws SocketException {
            return delegate.getReceiveBufferSize();
        }

        @Override
        public void setKeepAlive(boolean on) throws SocketException {
            delegate.setKeepAlive(on);
        }

        @Override
        public boolean getKeepAlive() throws SocketException {
            return delegate.getKeepAlive();
        }

        @Override
        public void setTrafficClass(int tc) throws SocketException {
            delegate.setTrafficClass(tc);
        }

        @Override
        public int getTrafficClass() throws SocketException {
            return delegate.getTrafficClass();
        }

        @Override
        public void setReuseAddress(boolean on) throws SocketException {
            delegate.setReuseAddress(on);
        }

        @Override
        public boolean getReuseAddress() throws SocketException {
            return delegate.getReuseAddress();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public void shutdownInput() throws IOException {
            delegate.shutdownInput();
        }

        @Override
        public void shutdownOutput() throws IOException {
            delegate.shutdownOutput();
        }

        @Override
        public boolean isConnected() {
            return delegate.isConnected();
        }

        @Override
        public boolean isBound() {
            return delegate.isBound();
        }

        @Override
        public boolean isClosed() {
            return delegate.isClosed();
        }

        @Override
        public boolean isInputShutdown() {
            return delegate.isInputShutdown();
        }

        @Override
        public boolean isOutputShutdown() {
            return delegate.isOutputShutdown();
        }

        @Override
        public void setPerformancePreferences(int connectionTime, int latency, int bandwidth) {
            delegate.setPerformancePreferences(connectionTime, latency, bandwidth);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
