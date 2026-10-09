package io.github.jiangood.docker.admin.util;

import io.github.jiangood.openadmin.framework.auth.LoginTool;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;

/**
 * 容器相关权限校验。
 * <p>
 * 容器组件同时用于「主机详情」与「应用详情」两处上下文：
 * <ul>
 *     <li>主机上下文持有 {@code host:list}</li>
 *     <li>应用上下文持有 {@code app:view}</li>
 * </ul>
 * 而 open-admin 的 {@code @HasPermission} 只接受单个权限码，无法表达「二者其一」，
 * 因此只读类接口改为方法内手工校验；控制台/文件/操作等高风险能力仍用专用权限码注解。
 */
public final class ContainerPermTool {

    public static final String PERM_EXEC = "container:exec";
    public static final String PERM_FILE = "container:file";
    public static final String PERM_OPERATE = "container:operate";

    private ContainerPermTool() {
    }

    /** 是否具备查看容器相关内容的权限（主机 host:list 或应用 app:view）。 */
    public static boolean canView() {
        return has("host:list") || has("app:view");
    }

    public static void assertView() {
        if (!canView()) {
            throw new AccessDeniedException("缺少查看容器权限");
        }
    }

    /** 是否具备指定权限码（含超管通配 {@code *}）。 */
    public static boolean has(String code) {
        List<String> permissions = LoginTool.getPermissions();
        if (permissions == null) {
            return false;
        }
        return permissions.contains("*") || permissions.contains(code);
    }
}
