package io.github.jiangood.docker.admin.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 主机 Compose 管理能力探测结果（详情页 Compose Tab 打开时先查一次）。
 */
@Data
public class ComposeCapabilityVo implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 是否可以做 Compose 管理（宿主 OS、辅助镜像、daemon 连接都满足时为 true） */
    boolean supported;

    /** 不可用的原因，直接展示给用户 */
    String reason;

    /** 可用但需要提醒的事项（如 Docker Desktop 下相对路径挂载不生效） */
    String warning;

    /** 辅助容器里 compose 的版本号，探测失败时为空 */
    String composeVersion;

    /** 辅助容器访问 daemon 的方式：socket / tcp */
    String daemonMode;

    /** 辅助镜像地址 */
    String helperImage;

    /** 主机上是否已有辅助镜像 */
    boolean imageReady;

    /** 是否可以通过「准备运行环境」拉取辅助镜像（只有"缺镜像"这一种情况才为 true） */
    boolean canPrepare;

    /** 辅助镜像大小（字节），未就绪时为空 */
    Long imageSize;

    /** docker info 的 OSType（linux / windows） */
    String osType;

    /** docker info 的 OperatingSystem */
    String os;
}
