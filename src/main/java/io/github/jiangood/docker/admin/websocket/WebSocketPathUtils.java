package io.github.jiangood.docker.admin.websocket;

import cn.hutool.core.util.StrUtil;

/**
 * WebSocket 路径工具：从带上下文路径的 URI 中提取 {@code marker} 之后的路径段。
 */
final class WebSocketPathUtils {

    private WebSocketPathUtils() {
    }

    /**
     * @param path   完整请求路径，如 {@code /docker-admin/admin/ws/container-log/{hostId}/{containerId}}
     * @param marker 标记段，如 {@code container-log}
     * @return marker 之后的路径段数组；未找到时返回 {@code null}
     */
    static String[] segmentsAfter(String path, String marker) {
        if (StrUtil.isBlank(path)) {
            return null;
        }
        int idx = path.indexOf("/" + marker + "/");
        if (idx < 0) {
            // 允许 marker 位于末尾（无后续段）
            if (path.endsWith("/" + marker)) {
                return new String[0];
            }
            return null;
        }
        String rest = path.substring(idx + marker.length() + 2);
        rest = rest.replaceAll("^/+", "").replaceAll("/+$", "");
        if (rest.isEmpty()) {
            return new String[0];
        }
        return rest.split("/");
    }
}
