package io.github.jiangood.docker.admin.entity;

import io.github.jiangood.openadmin.framework.dict.DictItem;
import io.github.jiangood.openadmin.framework.dict.DictType;

/**
 * 代码源访问方式。
 * <p>
 * 与平台类型（{@link CodeSourceType}）正交：平台类型决定能否对接平台 API，
 * 访问方式决定用什么凭据访问 git 仓库。
 */
@DictType(code = "codeSourceAuthType", label = "代码源访问方式")
public enum CodeSourceAuthType {

    @DictItem(label = "账号密码")
    PASSWORD,

    @DictItem(label = "访问令牌")
    TOKEN,

    @DictItem(label = "SSH 私钥")
    SSH_KEY;

    /**
     * 是否为走 HTTP(S) 的凭据方式（SSH 私钥除外）。
     */
    public boolean isHttp() {
        return this != SSH_KEY;
    }
}
