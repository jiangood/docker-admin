package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dao.HostRepository;
import io.github.jiangood.docker.admin.dao.ProxyRepository;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.entity.Proxy;
import io.github.jiangood.docker.sdk.engine.ProxyTestSupport;
import io.github.jiangood.openadmin.framework.data.BaseService;
import io.github.jiangood.openadmin.framework.data.specification.Spec;
import io.github.jiangood.openadmin.framework.proxy.ProxySettings;
import io.github.jiangood.openadmin.util.BusinessException;
import io.github.jiangood.openadmin.util.dto.Option;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 设置菜单「代理管理」：维护可供主机选择的连接代理，并支持测试与排序。
 * <p>
 * 主机表仍保留代理的五个落库字段作为快照（连接逻辑不变），本服务在代理保存后
 * 把新值同步给所有引用它的主机；删除时若仍有主机引用则拒绝，避免正在使用的主机静默断网。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProxyService extends BaseService<Proxy> {

    private static final int DEFAULT_TEST_TIMEOUT_MILLIS = 15_000;

    private final ProxyRepository proxyRepository;
    private final HostRepository hostRepository;

    /**
     * 列表 / 下拉统一顺序：排序值升序，创建时间兜底（历史数据可能还没有排序值）。
     */
    public List<Proxy> sorted() {
        return proxyRepository.findAll(Sort.by(Sort.Direction.ASC, "sortNo")
                .and(Sort.by(Sort.Direction.ASC, "createTime")));
    }

    public List<Option> options() {
        return sorted().stream()
                .map(p -> new Option(p.getId(), p.getLabel()))
                .collect(Collectors.toList());
    }

    /**
     * 无 ID 新增，有 ID 更新；编辑时密码留空表示沿用原密码。
     */
    @Transactional
    public Proxy saveProxy(Proxy input, List<String> updateFields) {
        Assert.notNull(input, "缺少代理配置");
        normalize(input);

        if (StrUtil.isBlank(input.getId())) {
            if (input.getSortNo() == null) {
                input.setSortNo(nextSortNo());
            }
            Proxy saved = create(input);
            return saved;
        }

        Proxy old = findById(input.getId()).orElse(null);
        Assert.notNull(old, "代理不存在");
        if (StrUtil.isBlank(input.getPassword())) {
            input.setPassword(old.getPassword());
        }
        if (input.getSortNo() == null) {
            input.setSortNo(old.getSortNo());
        }

        // 前端默认提交全部字段；仍按 X-Body-Fields 更新，避免覆盖未提交的列
        List<String> fields = updateFields == null
                ? new ArrayList<>(List.of(Proxy.Fields.name, Proxy.Fields.type, Proxy.Fields.host,
                        Proxy.Fields.port, Proxy.Fields.username, Proxy.Fields.password,
                        Proxy.Fields.remark, Proxy.Fields.sortNo))
                : new ArrayList<>(updateFields);
        if (!fields.contains(Proxy.Fields.password)) {
            fields.add(Proxy.Fields.password);
        }
        if (!fields.contains(Proxy.Fields.sortNo)) {
            fields.add(Proxy.Fields.sortNo);
        }
        Proxy saved = update(input, fields);
        syncToHosts(saved);
        return saved;
    }

    /**
     * 删除代理：仍被主机引用时拒绝，提示具体主机名。
     */
    @Transactional
    public void deleteProxy(String id) {
        Assert.hasText(id, "代理 ID 不能为空");
        List<Host> bound = hostRepository.findAll(Spec.<Host>of().eq(Host.Fields.proxyId, id));
        if (!bound.isEmpty()) {
            String names = bound.stream().limit(3).map(Host::getName).collect(Collectors.joining("、"));
            String suffix = bound.size() > 3 ? " 等 " + bound.size() + " 台主机" : "";
            throw new BusinessException("该代理正被主机 " + names + suffix
                    + " 使用，不能删除，请先在主机里改为其他代理或「不使用代理」");
        }
        deleteById(id);
    }

    /**
     * 列表页上移 / 下移：按当前顺序交换后重新编号，顺带把历史数据的空排序值补齐。
     */
    @Transactional
    public void move(String id, String direction) {
        Assert.hasText(id, "代理 ID 不能为空");
        boolean up = "up".equalsIgnoreCase(direction);
        boolean down = "down".equalsIgnoreCase(direction);
        if (!up && !down) {
            throw new BusinessException("排序方向只能是 up 或 down");
        }

        List<Proxy> list = new ArrayList<>(sorted());
        int index = -1;
        for (int i = 0; i < list.size(); i++) {
            if (id.equals(list.get(i).getId())) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            throw new BusinessException("代理不存在");
        }
        int target = up ? index - 1 : index + 1;
        if (target < 0 || target >= list.size()) {
            return;
        }
        Collections.swap(list, index, target);
        for (int i = 0; i < list.size(); i++) {
            list.get(i).setSortNo(i);
        }
        proxyRepository.saveAll(list);
    }

    /**
     * 测试代理：id 有值时用库里的值补全请求里没填的字段（尤其是密码），
     * 这样界面上密码留空也能测已保存的配置。
     */
    public ProxyTestSupport.Result test(String id, String type, String host, Integer port,
            String username, String password, String target) {
        Proxy saved = StrUtil.isNotBlank(id) ? findById(id).orElse(null) : null;

        String effectiveType = StrUtil.blankToDefault(type,
                saved == null ? Proxy.TYPE_HTTP : saved.getType());
        String effectiveHost = StrUtil.blankToDefault(host, saved == null ? null : saved.getHost());
        Integer effectivePort = port != null ? port : (saved == null ? null : saved.getPort());
        String effectiveUsername = StrUtil.isNotBlank(username)
                ? username : (saved == null ? null : saved.getUsername());
        String effectivePassword = password;
        if (StrUtil.isBlank(effectivePassword) || ProxySettings.PASSWORD_MASK.equals(effectivePassword)) {
            effectivePassword = saved == null ? null : saved.getPassword();
        }

        ProxySettings settings = ProxySettings.of(effectiveType, effectiveHost, effectivePort,
                effectiveUsername, effectivePassword);
        return ProxyTestSupport.test(settings, target, DEFAULT_TEST_TIMEOUT_MILLIS);
    }

    /**
     * 校验并归一化：端口范围、协议白名单、去除首尾空白；名称为空时用地址兜底。
     */
    private void normalize(Proxy input) {
        String type = StrUtil.trim(input.getType());
        if (StrUtil.isBlank(type)) {
            type = Proxy.TYPE_HTTP;
        }
        if (!Proxy.TYPE_HTTP.equals(type) && !Proxy.TYPE_SOCKS5.equals(type)) {
            throw new BusinessException("不支持的代理协议：" + type);
        }
        input.setType(type);

        input.setHost(StrUtil.trimToNull(input.getHost()));
        Assert.hasText(input.getHost(), "请填写代理地址");
        input.setUsername(StrUtil.trimToNull(input.getUsername()));
        input.setRemark(StrUtil.trimToNull(input.getRemark()));

        Assert.notNull(input.getPort(), "请填写代理端口");
        Assert.isTrue(input.getPort() >= 1 && input.getPort() <= 65535, "代理端口需在 1-65535 之间");

        if (StrUtil.isBlank(input.getName())) {
            input.setName(input.getHost() + ":" + input.getPort());
        }
        input.setName(input.getName().trim());
        if (input.getName().length() > 100) {
            throw new BusinessException("代理名称不能超过 100 个字符");
        }
    }

    private int nextSortNo() {
        List<Proxy> list = proxyRepository.findAll(Sort.by(Sort.Direction.DESC, "sortNo"));
        if (list.isEmpty() || list.get(0).getSortNo() == null) {
            return 0;
        }
        return list.get(0).getSortNo() + 1;
    }

    /**
     * 代理修改后同步到引用它的主机：主机保存的是代理字段快照，
     * 不同步会导致「代理管理里改了密码，主机仍拿旧密码连接」。
     */
    private void syncToHosts(Proxy proxy) {
        List<Host> hosts = hostRepository.findAll(
                Spec.<Host>of().eq(Host.Fields.proxyId, proxy.getId()));
        if (hosts.isEmpty()) {
            return;
        }
        for (Host host : hosts) {
            host.setProxyType(proxy.getType());
            host.setProxyHost(proxy.getHost());
            host.setProxyPort(proxy.getPort());
            host.setProxyUsername(proxy.getUsername());
            host.setProxyPassword(proxy.getPassword());
        }
        hostRepository.saveAll(hosts);
        log.info("代理「{}」已更新，同步到 {} 台主机", proxy.getName(), hosts.size());
    }
}
