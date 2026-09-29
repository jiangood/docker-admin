package io.github.jiangood.docker.admin.entity;

import io.github.jiangood.openadmin.framework.dict.DictItem;
import io.github.jiangood.openadmin.framework.dict.DictType;

/**
 * 代码源类型。
 * <p>
 * GITLAB/GITEE/GITHUB/GITEA 为已知托管平台，可对接其 API 自动列出仓库；
 * CUSTOM 为原始的自定义方式，仅按地址主机匹配。
 */
@DictType(code = "codeSourceType", label = "代码源类型")
public enum CodeSourceType {

    @DictItem(label = "GitLab")
    GITLAB,

    @DictItem(label = "Gitee")
    GITEE,

    @DictItem(label = "GitHub")
    GITHUB,

    @DictItem(label = "Gitea")
    GITEA,

    @DictItem(label = "自定义")
    CUSTOM;

    /**
     * 是否为可对接 API 的托管平台（自定义除外）。
     */
    public boolean isHosted() {
        return this != CUSTOM;
    }
}
