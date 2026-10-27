package io.github.jiangood.docker.sdk.engine;

import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.transport.NamedPipeSocket;
import com.github.dockerjava.transport.SSLConfig;
import com.github.dockerjava.transport.UnixSocket;
import io.github.jiangood.docker.admin.entity.HostProxySettings;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.DefaultHttpClientConnectionOperator;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.io.DetachedSocketFactory;
import org.apache.hc.client5.http.io.HttpClientConnectionOperator;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ConnectionClosedException;
import org.apache.hc.core5.http.ContentLengthStrategy;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.impl.DefaultContentLengthStrategy;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EmptyInputStream;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.apache.hc.core5.net.URIAuthority;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 带「连接代理」的 Docker HTTP 客户端。
 * <p>
 * docker-java 3.7.1 的 {@code ApacheDockerHttpClient.Builder} 不暴露代理入口，
 * {@code DockerClientConfig} 也没有代理配置，因此这里把它的 {@code ApacheDockerHttpClientImpl}
 * 同步一份，唯一改动是：建立 TCP 连接时改用 {@link ProxyTunnelSocket}（经代理握手），
 * 其余连接池 / TLS / hijack（exec、attach、日志 follow）行为与官方实现一致。
 * <p>
 * 只有「远程 TCP + 启用代理」的主机才会走本类；没有代理时仍使用官方
 * {@code ApacheDockerHttpClient}，不受本类影响。
 * <p>
 * <b>维护提示：</b>升级 docker-java 后若上游实现有变化，请对照
 * {@code com.github.dockerjava.httpclient5.ApacheDockerHttpClientImpl} 同步本类。
 */
class ProxyAwareApacheDockerHttpClient implements DockerHttpClient {

    private final CloseableHttpClient httpClient;
    private final HttpHost host;
    private final String pathPrefix;

    ProxyAwareApacheDockerHttpClient(
            URI dockerHost,
            SSLConfig sslConfig,
            int maxConnections,
            Duration connectionTimeout,
            Duration responseTimeout,
            HostProxySettings proxy
    ) {
        SSLContext sslContext;
        try {
            sslContext = sslConfig != null ? sslConfig.getSSLContext() : null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        boolean proxied = proxy != null && proxy.configured();
        if (!proxied) {
            throw new IllegalArgumentException("未配置连接代理，请使用直连客户端");
        }

        HttpClientConnectionOperator connectionOperator = createConnectionOperator(dockerHost, sslContext, proxy);

        switch (dockerHost.getScheme()) {
            case "unix":
            case "npipe":
                pathPrefix = "";
                host = new HttpHost(dockerHost.getScheme(), "localhost", 2375);
                break;
            case "tcp":
                String rawPath = dockerHost.getRawPath();
                pathPrefix = rawPath == null || rawPath.isEmpty() ? ""
                        : rawPath.endsWith("/") ? rawPath.substring(0, rawPath.length() - 1) : rawPath;
                host = new HttpHost(
                        sslContext != null ? "https" : "http",
                        dockerHost.getHost(),
                        dockerHost.getPort()
                );
                break;
            default:
                throw new IllegalArgumentException("Unsupported protocol scheme: " + dockerHost);
        }

        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager(
                connectionOperator,
                null,
                null,
                null,
                new ManagedHttpClientConnectionFactory(
                        null,
                        null,
                        null,
                        null,
                        message -> {
                            Header transferEncodingHeader = message.getFirstHeader(HttpHeaders.TRANSFER_ENCODING);
                            if (transferEncodingHeader != null) {
                                if ("identity".equalsIgnoreCase(transferEncodingHeader.getValue())) {
                                    return ContentLengthStrategy.UNDEFINED;
                                }
                            }
                            return DefaultContentLengthStrategy.INSTANCE.determineLength(message);
                        },
                        null
                )
        );
        // 与官方实现一致：读写超时交给请求级配置，socket 本身不设超时
        connectionManager.setDefaultSocketConfig(
                SocketConfig.copy(SocketConfig.DEFAULT)
                        .setSoTimeout(Timeout.ZERO_MILLISECONDS)
                        .build()
        );
        connectionManager.setMaxTotal(maxConnections);
        connectionManager.setDefaultMaxPerRoute(maxConnections);
        connectionManager.setDefaultConnectionConfig(ConnectionConfig.custom()
                .setValidateAfterInactivity(TimeValue.NEG_ONE_SECOND)
                .setConnectTimeout(connectionTimeout != null ? Timeout.of(connectionTimeout.toNanos(), TimeUnit.NANOSECONDS) : null)
                .build());

        httpClient = HttpClients.custom()
                .setRequestExecutor(new HijackingHttpRequestExecutor(null))
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(responseTimeout != null ? Timeout.of(responseTimeout.toNanos(), TimeUnit.NANOSECONDS) : null)
                        .build())
                .disableConnectionState()
                .build();
    }

    @Override
    public Response execute(Request request) {
        HttpContext context = new HttpCoreContext();
        HttpUriRequestBase httpUriRequest = new HttpUriRequestBase(request.method(), URI.create(pathPrefix + request.path()));
        httpUriRequest.setScheme(host.getSchemeName());
        httpUriRequest.setAuthority(new URIAuthority(host.getHostName(), host.getPort()));

        request.headers().forEach(httpUriRequest::addHeader);

        byte[] bodyBytes = request.bodyBytes();
        if (bodyBytes != null) {
            httpUriRequest.setEntity(new ByteArrayEntity(bodyBytes, null));
        } else {
            InputStream body = request.body();
            if (body != null) {
                httpUriRequest.setEntity(new InputStreamEntity(body, null));
            }
        }

        if (request.hijackedInput() != null) {
            context.setAttribute(HijackingHttpRequestExecutor.HIJACKED_INPUT_ATTRIBUTE, request.hijackedInput());
            httpUriRequest.setHeader("Upgrade", "tcp");
            httpUriRequest.setHeader("Connection", "Upgrade");
        }

        try {
            ClassicHttpResponse response = httpClient.executeOpen(host, httpUriRequest, context);

            return new ApacheResponse(httpUriRequest, response);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() throws IOException {
        httpClient.close();
    }

    /**
     * 连接操作符的构造：与官方实现的唯一差异。
     * <p>
     * 代理模式下：
     * <ul>
     *     <li>socket 工厂返回 {@link ProxyTunnelSocket}，连接时先与代理握手；</li>
     *     <li>DNS 解析返回<b>未解析</b>地址，目标主机名交给代理解析——
     *     否则平台本地解析失败就根本走不到代理，无法解决「网络不通 / 内网域名」的场景。</li>
     * </ul>
     */
    private static HttpClientConnectionOperator createConnectionOperator(
            URI dockerHost,
            SSLContext sslContext,
            HostProxySettings proxy
    ) {
        String dockerHostScheme = dockerHost.getScheme();
        String dockerHostPath = dockerHost.getPath();
        TlsSocketStrategy tlsSocketStrategy = sslContext != null
                ? new DefaultClientTlsStrategy(sslContext)
                : DefaultClientTlsStrategy.createSystemDefault();

        DetachedSocketFactory socketFactory = socksProxy -> {
            if ("unix".equalsIgnoreCase(dockerHostScheme)) {
                return UnixSocket.get(dockerHostPath);
            } else if ("npipe".equalsIgnoreCase(dockerHostScheme)) {
                return new NamedPipeSocket(dockerHostPath);
            } else if (proxy != null && proxy.configured()) {
                return new ProxyTunnelSocket(proxy);
            } else {
                return socksProxy == null ? new Socket() : new Socket(socksProxy);
            }
        };

        DnsResolver dnsResolver = proxy != null && proxy.configured()
                ? UnresolvedDnsResolver.INSTANCE
                : SystemDefaultDnsResolver.INSTANCE;

        return new DefaultHttpClientConnectionOperator(
                socketFactory,
                DefaultSchemePortResolver.INSTANCE,
                dnsResolver,
                name -> "https".equalsIgnoreCase(name) ? tlsSocketStrategy : null);
    }

    /**
     * 代理模式下的 DNS：返回未解析地址，让 {@link ProxyTunnelSocket} 把主机名交给代理解析。
     * <p>
     * 单参数 {@link DnsResolver#resolve(String)} 在 hc5 的连接流程里不会被调用，
     * 保留系统默认实现作为兜底。
     */
    private static final class UnresolvedDnsResolver implements DnsResolver {

        static final UnresolvedDnsResolver INSTANCE = new UnresolvedDnsResolver();

        @Override
        public java.net.InetAddress[] resolve(String host) throws java.net.UnknownHostException {
            return SystemDefaultDnsResolver.INSTANCE.resolve(host);
        }

        @Override
        public String resolveCanonicalHostname(String host) {
            return host;
        }

        @Override
        public List<InetSocketAddress> resolve(String host, int port) {
            return List.of(InetSocketAddress.createUnresolved(host, port));
        }
    }

    static class ApacheResponse implements Response {

        private static final Logger LOGGER = LoggerFactory.getLogger(ApacheResponse.class);

        private final HttpUriRequestBase request;

        private final ClassicHttpResponse response;

        ApacheResponse(HttpUriRequestBase httpUriRequest, ClassicHttpResponse response) {
            this.request = httpUriRequest;
            this.response = response;
        }

        @Override
        public int getStatusCode() {
            return response.getCode();
        }

        @Override
        public Map<String, List<String>> getHeaders() {
            return Stream.of(response.getHeaders()).collect(Collectors.groupingBy(
                    NameValuePair::getName,
                    Collectors.mapping(NameValuePair::getValue, Collectors.toList())
            ));
        }

        @Override
        public String getHeader(String name) {
            Header firstHeader = response.getFirstHeader(name);
            return firstHeader != null ? firstHeader.getValue() : null;
        }

        @Override
        public InputStream getBody() {
            try {
                return response.getEntity() != null
                        ? response.getEntity().getContent()
                        : EmptyInputStream.INSTANCE;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void close() {
            try {
                request.abort();
            } catch (Exception e) {
                LOGGER.debug("Failed to abort the request", e);
            }

            try {
                response.close();
            } catch (ConnectionClosedException e) {
                LOGGER.trace("Failed to close the response", e);
            } catch (Exception e) {
                LOGGER.debug("Failed to close the response", e);
            }
        }
    }
}
