package io.github.jiangood.docker.admin.service.compose;

import io.github.jiangood.openadmin.util.BusinessException;

import java.util.List;

/**
 * Compose Tab 上允许执行的动作。命令参数在服务端固定，前端只能选动作，不开放自由命令。
 */
public enum ComposeAction {

    /** 拉取项目里用到的镜像 */
    PULL("pull", List.of("pull")),

    /** 启动 / 更新（改了 compose 文件后执行它） */
    UP("up", List.of("up", "-d")),

    /** 停止并删除容器与网络（不删命名卷） */
    DOWN("down", List.of("down"));

    private final String code;
    private final List<String> args;

    ComposeAction(String code, List<String> args) {
        this.code = code;
        this.args = args;
    }

    public String getCode() {
        return code;
    }

    public List<String> getArgs() {
        return args;
    }

    public static ComposeAction of(String code) {
        for (ComposeAction action : values()) {
            if (action.code.equalsIgnoreCase(code)) {
                return action;
            }
        }
        throw new BusinessException("不支持的操作：" + code);
    }
}
