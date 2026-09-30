package io.github.jiangood.docker.base.tool;

import cn.hutool.core.util.StrUtil;
import lombok.Getter;

/**
 * git 访问凭据：由「代码源」配置解析而来，供 {@link GitTool} 使用。
 */
@Getter
public class GitCredential {

    public enum Kind {
        /** 无凭据，按公开仓库访问 */
        NONE,
        /** 账号密码（HTTP Basic） */
        PASSWORD,
        /** 访问令牌（HTTP Basic，用户名可空） */
        TOKEN,
        /** SSH 私钥 */
        SSH_KEY
    }

    public static final GitCredential NONE = new GitCredential(Kind.NONE, null, null, null, null);

    private final Kind kind;
    /** 账号密码方式的用户名；令牌方式可空（默认 oauth2） */
    private final String username;
    /** 密码或访问令牌 */
    private final String secret;
    /** SSH 私钥（PEM 文本） */
    private final String privateKey;
    /** SSH 私钥口令，可空 */
    private final String passphrase;

    private GitCredential(Kind kind, String username, String secret, String privateKey, String passphrase) {
        this.kind = kind;
        this.username = username;
        this.secret = secret;
        this.privateKey = privateKey;
        this.passphrase = passphrase;
    }

    public static GitCredential password(String username, String password) {
        if (StrUtil.isBlank(password)) {
            return NONE;
        }
        return new GitCredential(Kind.PASSWORD, username, password, null, null);
    }

    public static GitCredential token(String username, String token) {
        if (StrUtil.isBlank(token)) {
            return NONE;
        }
        return new GitCredential(Kind.TOKEN, username, token, null, null);
    }

    public static GitCredential sshKey(String privateKey, String passphrase) {
        if (StrUtil.isBlank(privateKey)) {
            return NONE;
        }
        return new GitCredential(Kind.SSH_KEY, null, null, privateKey, passphrase);
    }

    public boolean isNone() {
        return kind == Kind.NONE;
    }
}
