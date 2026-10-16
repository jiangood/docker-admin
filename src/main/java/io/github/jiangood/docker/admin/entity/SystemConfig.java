package io.github.jiangood.docker.admin.entity;

import io.github.jiangood.openadmin.framework.data.BaseEntity;
import io.github.jiangood.openadmin.util.annotation.Remark;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldNameConstants;

/**
 * 系统级配置，保存全局默认资源的 ID 等轻量配置。
 */
@Remark("系统配置")
@Entity
@Getter
@Setter
@FieldNameConstants
@Table(name = "t_system_config")
public class SystemConfig extends BaseEntity {

    @Remark("配置键")
    @Column(name = "config_key", nullable = false, unique = true, length = 100)
    String configKey;

    @Remark("配置值")
    @Column(name = "config_value", length = 500)
    String configValue;

    @Remark("说明")
    @Column(length = 300)
    String description;
}
