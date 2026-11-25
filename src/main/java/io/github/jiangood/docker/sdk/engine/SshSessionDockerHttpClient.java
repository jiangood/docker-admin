package io.github.jiangood.docker.sdk.engine;

import com.github.dockerjava.transport.DockerHttpClient;
import com.jcraft.jsch.Session;

import java.io.IOException;
import java.util.Objects;

/**
 * 给使用「外部 JSch 会话」的 SSH 传输补上会话回收。
 * <p>
 * {@code JschDockerHttpClient} 通过 {@code sshSession(Session)} 接收外部会话时，
 * 自己的 {@code close()} 不会断开它（库内部用 externalSession 标记跳过），
 * 不包这一层的话每建一次客户端就会泄漏一个 SSH 会话。
 */
class SshSessionDockerHttpClient implements DockerHttpClient {

    private final DockerHttpClient delegate;
    private final Session session;

    SshSessionDockerHttpClient(DockerHttpClient delegate, Session session) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.session = Objects.requireNonNull(session, "session");
    }

    @Override
    public Response execute(Request request) {
        return delegate.execute(request);
    }

    @Override
    public void close() throws IOException {
        try {
            delegate.close();
        } finally {
            try {
                if (session.isConnected()) {
                    session.disconnect();
                }
            } catch (Exception ignored) {
                // 关闭阶段的异常不影响调用方
            }
        }
    }
}
