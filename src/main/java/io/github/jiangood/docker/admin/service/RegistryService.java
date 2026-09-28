package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dao.RegistryRepository;
import io.github.jiangood.docker.admin.entity.Registry;
import io.github.jiangood.docker.config.CfgRegistry;
import io.github.jiangood.docker.config.Config;
import io.github.jiangood.openadmin.framework.data.BaseService;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RegistryService extends BaseService<Registry> {

    private final RegistryRepository registryRepository;

    @Resource
    Config config;

    /**
     * 生效的注册中心：数据库优先，数据库为空时回落到 yml 的 cfg.registry。
     */
    public Registry getEffective() {
        List<Registry> list = registryRepository.findAll(Sort.by(Sort.Direction.DESC, "updateTime"));
        if (!list.isEmpty()) {
            return list.get(0);
        }
        return fromCfg(config.getRegistry());
    }

    /**
     * 保存注册中心（全局唯一，单条记录）。
     */
    @Transactional
    public Registry saveRegistry(Registry input) {
        if (StrUtil.isBlank(input.getId())) {
            // 单例：已有记录则更新，避免出现多条
            Registry exist = getEffective();
            if (exist != null && StrUtil.isNotBlank(exist.getId())) {
                input.setId(exist.getId());
            } else {
                return create(input);
            }
        }

        Registry old = findById(input.getId()).orElse(null);
        Assert.notNull(old, "注册中心不存在");
        if (StrUtil.isBlank(input.getPassword())) {
            input.setPassword(old.getPassword());
        }
        return update(input, null);
    }

    public static Registry fromCfg(CfgRegistry cfg) {
        if (cfg == null) {
            return null;
        }
        Registry r = new Registry();
        r.setUrl(cfg.getUrl());
        r.setNamespace(cfg.getNamespace());
        r.setUsername(cfg.getUsername());
        r.setPassword(cfg.getPassword());
        return r;
    }
}
