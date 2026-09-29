package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dao.CodeSourceRepository;
import io.github.jiangood.docker.admin.entity.CodeSource;
import io.github.jiangood.docker.admin.entity.CodeSourceType;
import io.github.jiangood.openadmin.framework.data.BaseService;
import io.github.jiangood.openadmin.util.dto.Option;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
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
     * 保存代码源，密码留空时保留原密码。
     */
    @Transactional
    public CodeSource saveCredential(CodeSource input) {
        if (input.getType() == null) {
            input.setType(CodeSourceType.CUSTOM);
        }
        if (StrUtil.isBlank(input.getId())) {
            return create(input);
        }
        CodeSource old = findById(input.getId()).orElse(null);
        Assert.notNull(old, "代码源不存在");
        if (StrUtil.isBlank(input.getPassword())) {
            input.setPassword(old.getPassword());
        }
        return update(input, null);
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
