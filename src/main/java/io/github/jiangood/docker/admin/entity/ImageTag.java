package io.github.jiangood.docker.admin.entity;

import io.github.jiangood.docker.admin.entity.converter.StringListConverter;
import io.github.jiangood.openadmin.framework.data.BaseEntity;
import io.github.jiangood.openadmin.util.annotation.Remark;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 镜像标签：某个镜像（repository）下的一个 tag。
 * <p>
 * 缓存镜像（Dockerfile）中声明的端口与卷，容器的端口/卷映射只允许根据这里声明的值进行配置。
 */
@Remark("镜像标签")
@Entity
@Getter
@Setter
@ToString
@FieldNameConstants
@Table(name = "t_image_tag")
public class ImageTag extends BaseEntity {

    /**
     * 所属镜像的镜像url（不含 tag），关联 {@link Image#getImageUrl()}。
     */
    @Remark("镜像地址")
    @Column(nullable = false, length = 400)
    String imageUrl;

    @Remark("版本")
    @Column(nullable = false)
    String tag;

    /**
     * 完整镜像地址：imageUrl + ":" + tag。
     */
    @Remark("完整地址")
    String fullUrl;

    /**
     * 来源：BUILD（项目构建）/ SYNC（镜像同步）/ IMPORT（导入）/ REMOTE（远程注册中心）。
     */
    @Remark("来源")
    String source;

    /**
     * 镜像摘要（sha256:...），可空。
     */
    String digest;

    /**
     * 镜像大小（字节），可空。
     */
    Long size;

    /**
     * 镜像构建时间：来自镜像配置（image config）的 created 字段，不是同步时间。
     * <p>
     * 远程同步时从 {@code /v2/<name>/blobs/<config.digest>} 读取；本地 inspect 也可填充。
     */
    @Remark("构建时间")
    LocalDateTime buildTime;

    /**
     * 来源构建记录 id（BUILD 来源），可空。
     */
    String buildLogId;

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

}
