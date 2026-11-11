package io.github.jiangood.docker.admin.entity;

import io.github.jiangood.openadmin.framework.dict.DictItem;
import io.github.jiangood.openadmin.framework.dict.DictType;

/**
 * 部署触发方式：说明某次部署是怎么被发起的，用于「部署日志」列表展示。
 * <p>
 * 手动部署来自应用详情的「重新部署」/「部署」页的手动发布；其余为配置变更、改名、迁移等
 * 伴随重新部署的操作，以及镜像构建成功后的自动部署。
 */
@DictType(code = "deployTrigger", label = "部署触发方式")
public enum DeployTrigger {

    @DictItem(label = "手动部署")
    MANUAL,

    @DictItem(label = "新建应用")
    CREATE,

    @DictItem(label = "配置变更")
    CONFIG,

    @DictItem(label = "应用改名")
    RENAME,

    @DictItem(label = "主机迁移")
    MIGRATE,

    @DictItem(label = "构建自动部署")
    BUILD,

    @DictItem(label = "定时更新")
    SCHEDULE,

    /**
     * 遗留值：应用 Webhook 触发升级功能已移除，仅历史部署日志里还存有该值。
     * 删掉枚举常量会导致 Hibernate 读到旧记录时反序列化失败，部署日志列表打不开。
     */
    @DictItem(label = "Webhook 触发")
    WEBHOOK

}
