package io.github.jiangood.docker.base;

import cn.hutool.core.collection.CollUtil;
import io.github.jiangood.openadmin.framework.auth.LoginTool;
import io.github.jiangood.openadmin.modules.system.entity.SysOrg;
import io.github.jiangood.openadmin.util.BusinessException;

import java.util.List;

/**
 * 组织机构数据权限校验。
 * <p>
 * 规则与列表查询保持一致：管理员放行；未归属组织的数据所有人可见；
 * 其余要求当前登录用户拥有该组织的权限（{@code ORG_} 权限）。
 */
public final class OrgAccessTool {

    private OrgAccessTool() {
    }

    /**
     * 判断当前用户是否可以访问该组织下的数据。
     */
    public static boolean canAccess(SysOrg org) {
        if (LoginTool.isAdmin()) {
            return true;
        }
        if (org == null || org.getId() == null) {
            // 未归属组织的数据对所有人可见，与列表查询的 or(isNull, in) 语义一致
            return true;
        }
        List<String> permissions = LoginTool.getOrgPermissions();
        return CollUtil.isNotEmpty(permissions) && permissions.contains(org.getId());
    }

    /**
     * 无权限时抛出业务异常，由框架统一转为错误响应。
     */
    public static void assertAccess(SysOrg org) {
        if (!canAccess(org)) {
            throw new BusinessException("无权访问该数据");
        }
    }
}
