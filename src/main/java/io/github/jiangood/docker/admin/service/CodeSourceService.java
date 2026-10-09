package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dao.CodeSourceRepository;
import io.github.jiangood.docker.admin.entity.CodeSource;
import io.github.jiangood.docker.admin.entity.CodeSourceAuthType;
import io.github.jiangood.docker.admin.entity.CodeSourceType;
import io.github.jiangood.docker.base.tool.GitCredential;
import io.github.jiangood.openadmin.framework.data.BaseService;
import io.github.jiangood.openadmin.util.dto.Option;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CodeSourceService extends BaseService<CodeSource> {

    private final CodeSourceRepository codeSourceRepository;

    /**
     * 按 git 地址的主机（host，含端口）匹配代码源，仅使用后台维护的配置。
     * 兼容 http(s)://、ssh://、scp 形式（user@host:path）。
     */
    public CodeSource findByGitUrl(String gitUrl) {
        String host = hostKey(gitUrl);
        if (host == null) {
            return null;
        }
        for (CodeSource source : codeSourceRepository.findAll()) {
            if (host.equals(hostKey(source.getUrl()))) {
                return source;
            }
        }
        return null;
    }

    /**
     * 解析代码源的 git 访问凭据；代码源不存在或未配置凭据时按公开仓库匿名访问。
     */
    public GitCredential credential(CodeSource source) {
        return resolveCredential(source);
    }

    /**
     * 访问方式 → git 凭据的映射规则（纯函数，便于测试）。
     */
    static GitCredential resolveCredential(CodeSource source) {
        if (source == null) {
            return GitCredential.NONE;
        }
        switch (source.getAuthType()) {
            case PASSWORD:
                if (StrUtil.isBlank(source.getPassword()) && StrUtil.isBlank(source.getUsername())) {
                    return GitCredential.NONE;
                }
                return GitCredential.password(source.getUsername(), source.getPassword());
            case SSH_KEY:
                return GitCredential.sshKey(source.getPrivateKey(), source.getPrivateKeyPassphrase());
            case TOKEN:
            default:
                return GitCredential.token(source.getUsername(), source.getToken());
        }
    }

    /**
     * 按 git 地址匹配代码源并解析访问凭据。
     */
    public GitCredential credentialByGitUrl(String gitUrl) {
        return credential(findByGitUrl(gitUrl));
    }

    /**
     * 保存代码源，敏感字段留空时保留原值。
     */
    @Transactional
    public CodeSource saveCredential(CodeSource input) {
        if (input.getType() == null) {
            input.setType(CodeSourceType.CUSTOM);
        }
        if (StrUtil.isBlank(input.getId())) {
            if (input.getAuthType() == null) {
                input.setAuthType(CodeSourceAuthType.TOKEN);
            }
            validateCredential(input);
            return create(input);
        }
        CodeSource old = findById(input.getId()).orElse(null);
        Assert.notNull(old, "代码源不存在");
        if (input.getAuthType() == null) {
            input.setAuthType(old.getAuthType());
        }
        if (StrUtil.isBlank(input.getPassword())) {
            input.setPassword(old.getPassword());
        }
        if (StrUtil.isBlank(input.getToken())) {
            input.setToken(old.getToken());
        }
        if (StrUtil.isBlank(input.getPrivateKey())) {
            input.setPrivateKey(old.getPrivateKey());
        }
        if (StrUtil.isBlank(input.getPrivateKeyPassphrase())) {
            input.setPrivateKeyPassphrase(old.getPrivateKeyPassphrase());
        }
        clearUnusedCredential(input);
        validateCredential(input);
        updateField(input, UPDATABLE_FIELDS);
        return findById(input.getId()).orElse(null);
    }

    /**
     * 允许前端更新的字段；此处显式列出，避免把 id/createTime 等未提交字段覆盖为 null。
     */
    private static final List<String> UPDATABLE_FIELDS = List.of(
            CodeSource.Fields.name,
            CodeSource.Fields.type,
            CodeSource.Fields.authType,
            CodeSource.Fields.url,
            CodeSource.Fields.username,
            CodeSource.Fields.password,
            CodeSource.Fields.token,
            CodeSource.Fields.privateKey,
            CodeSource.Fields.privateKeyPassphrase);

    /**
     * 只保留当前访问方式对应的凭据，避免切换方式后遗留旧凭据。
     */
    private void clearUnusedCredential(CodeSource input) {
        switch (input.getAuthType()) {
            case PASSWORD:
                input.setToken(null);
                input.setPrivateKey(null);
                input.setPrivateKeyPassphrase(null);
                break;
            case SSH_KEY:
                input.setPassword(null);
                input.setToken(null);
                break;
            case TOKEN:
            default:
                input.setPassword(null);
                input.setPrivateKey(null);
                input.setPrivateKeyPassphrase(null);
                break;
        }
    }

    /**
     * 按访问方式校验必填凭据。
     */
    private void validateCredential(CodeSource input) {
        switch (input.getAuthType()) {
            case PASSWORD:
                Assert.hasText(input.getUsername(), "账号密码方式请填写用户名");
                Assert.hasText(input.getPassword(), "账号密码方式请填写密码");
                break;
            case SSH_KEY:
                Assert.hasText(input.getPrivateKey(), "SSH 私钥方式请填写私钥");
                break;
            case TOKEN:
            default:
                Assert.hasText(input.getToken(), "访问令牌方式请填写令牌");
                break;
        }
    }

    /**
     * 供下拉选择使用，不返回密码。
     */
    public List<Option> options() {
        List<CodeSource> list = codeSourceRepository.findAll();
        List<Option> options = new ArrayList<>();
        for (CodeSource c : list) {
            Option option = new Option(c.getId(), c.getName());
            option.setData(Map.of(
                    "type", c.getType() == null ? CodeSourceType.CUSTOM.name() : c.getType().name(),
                    "authType", c.getAuthType().name(),
                    "url", StrUtil.nullToEmpty(c.getUrl())));
            options.add(option);
        }
        return options;
    }

    /**
     * 提取地址的主机标识（host[:port]，小写）。无法解析时返回 null。
     */
    static String hostKey(String address) {
        if (StrUtil.isBlank(address)) {
            return null;
        }
        String value = address.trim();

        if (value.contains("://")) {
            try {
                URI uri = new URI(value);
                String host = uri.getHost();
                if (StrUtil.isBlank(host)) {
                    return null;
                }
                int port = uri.getPort();
                return (port > 0 ? host + ":" + port : host).toLowerCase(Locale.ROOT);
            } catch (URISyntaxException e) {
                return null;
            }
        }

        // scp 形式 user@host:path / user@host/path，或裸地址 host[:port]
        String rest = value;
        int at = rest.indexOf('@');
        if (at >= 0) {
            rest = rest.substring(at + 1);
        }
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            rest = rest.substring(0, slash);
        }
        int colon = rest.indexOf(':');
        if (colon >= 0) {
            String after = rest.substring(colon + 1);
            // host:port 保留端口；user@host:path 丢弃路径
            if (!after.matches("\\d+")) {
                rest = rest.substring(0, colon);
            }
        }
        rest = rest.trim();
        return rest.isEmpty() ? null : rest.toLowerCase(Locale.ROOT);
    }
}
