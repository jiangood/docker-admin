package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dao.RegistryRepository;
import io.github.jiangood.docker.admin.entity.Registry;
import io.github.jiangood.openadmin.framework.data.BaseService;
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

    /**
     * 生效的注册中心：取数据库中的记录，未配置时返回 null。
     */
    public Registry getEffective() {
        List<Registry> list = registryRepository.findAll(Sort.by(Sort.Direction.DESC, "updateTime"));
        if (!list.isEmpty()) {
            return list.get(0);
        }
        return null;
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
}
