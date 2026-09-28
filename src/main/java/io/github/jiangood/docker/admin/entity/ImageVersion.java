package io.github.jiangood.docker.admin.entity;

import io.github.jiangood.docker.admin.entity.converter.StringListConverter;
import io.github.jiangood.openadmin.framework.data.BaseEntity;
import io.github.jiangood.openadmin.util.annotation.Remark;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 镜像版本：缓存镜像（Dockerfile）中声明的端口与卷。
 * 容器的端口/卷映射只允许根据这里声明的值进行配置。
 */
@Remark("镜像版本")
@Entity
@Getter
@Setter
@ToString
@FieldNameConstants
@Table(name = "t_image_version")
public class ImageVersion extends BaseEntity {

    @Remark("镜像地址")
    @Column(nullable = false)
    String imageUrl;

    @Remark("版本")
    @Column(nullable = false)
    String tag;

    /**
     * 镜像声明的端口，形如 80/tcp、53/udp。
     */
    @Lob
    @Convert(converter = StringListConverter.class)
    List<String> exposedPorts = new ArrayList<>();

    /**
     * 镜像声明的卷路径，如 /data。
     */
    @Lob
    @Convert(converter = StringListConverter.class)
    List<String> volumes = new ArrayList<>();

    @Remark("已读取声明")
    Boolean inspected;

    LocalDateTime inspectTime;

    /**
     * 镜像地址:版本。
     */
    @Transient
    public String getFullName() {
        return imageUrl + ":" + tag;
    }
}
