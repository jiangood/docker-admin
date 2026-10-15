package io.github.jiangood.docker.sdk.engine;

import com.github.dockerjava.transport.DockerHttpClient;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 给每个 Docker HTTP 请求追加一个固定请求头（本平台固定为 {@code Authorization}）。
 * <p>
 * 为什么要自己包一层：docker-java 3.7.1 的 {@code ApacheDockerHttpClient.Builder} 没有公开的
 * 「加请求头」入口，而配合 docker-api-auth 这类带认证的 Docker 代理时，<b>每个</b>请求
 * （version / 容器 / 镜像 / 日志，以及 exec / attach 的连接升级）都必须带上
 * {@code Authorization: Bearer <token>}，否则代理一律返回 401。
 * <p>
 * 只重写请求头：{@code method} / {@code path} / {@code body} / {@code hijackedInput} 全部原样委托，
 * 因此 exec、attach、logs?follow 等依赖连接升级（hijack）的能力不受影响。
 */
class AuthHeaderDockerHttpClient implements DockerHttpClient {

    private final DockerHttpClient delegate;
    private final String headerName;
    private final String headerValue;

    AuthHeaderDockerHttpClient(DockerHttpClient delegate, String headerName, String headerValue) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.headerName = Objects.requireNonNull(headerName, "headerName");
        this.headerValue = Objects.requireNonNull(headerValue, "headerValue");
    }

    @Override
    public Response execute(Request request) {
        return delegate.execute(new Request() {
            @Override
            public String method() {
                return request.method();
            }

            @Override
            public String path() {
                return request.path();
            }

            @Override
            public InputStream body() {
                return request.body();
            }

            @Override
            public byte[] bodyBytes() {
                return request.bodyBytes();
            }

            @Override
            public InputStream hijackedInput() {
                return request.hijackedInput();
            }

            @Override
            public Map<String, String> headers() {
                Map<String, String> headers = new LinkedHashMap<>();
                Map<String, String> original = request.headers();
                if (original != null) {
                    headers.putAll(original);
                }
                headers.put(headerName, headerValue);
                return headers;
            }
        });
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
