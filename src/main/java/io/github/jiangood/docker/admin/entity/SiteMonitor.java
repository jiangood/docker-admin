package io.github.jiangood.docker.admin.entity;

import io.github.jiangood.openadmin.framework.data.BaseEntity;
import io.github.jiangood.openadmin.util.annotation.Remark;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldNameConstants;

import java.time.LocalDateTime;

/**
 * 网站监控项：定时对目标 URL 发起 HTTP(S) 探测，判定其是否可达。
 * <p>
 * 判定不只看状态码：很多站点挂在 nginx 等反向代理后面，代理层可能用 200 状态码
 * 返回错误占位页（「502 Bad Gateway」、业务维护文案等），所以还有双向关键词——
 * 正向 {@link #keyword} 出现才算正常，反向 {@link #errorKeyword} 出现即判不可达。
 * <p>
 * 实体上同时存最近一次检测的快照（成功与否、状态码、耗时、失败原因），
 * 供列表页直接展示，不必每次去翻历史记录表。
 */
@Remark("网站监控")
@Entity
@Getter
@Setter
@FieldNameConstants
@Table(name = "t_site_monitor")
public class SiteMonitor extends BaseEntity {

    /**
     * 请求方法：GET（读响应体做关键词判定）。
     */
    public static final String METHOD_GET = "GET";
    /**
     * 请求方法：HEAD（只看状态码，没有响应体）。
     */
    public static final String METHOD_HEAD = "HEAD";

    /**
     * 连续失败多少次后才发告警邮件：偶发网络抖动不值得吵人，告警一次后恢复时再通知。
     */
    public static final int ALERT_THRESHOLD = 3;

    /**
     * 监控项名称。
     */
    String name;

    /**
     * 目标地址，http/https 均可；nginx 反代后面的站点填对外公开的地址。
     */
    String url;

    /**
     * 请求方法，见 {@link #METHOD_GET} / {@link #METHOD_HEAD}；默认 GET。
     */
    String method;

    /**
     * 正向关键词（可空）：非空时响应体必须包含它才算正常，
     * 用于识别「状态码 200 但内容是登录跳转/被拦截页」这类情况。
     */
    String keyword;

    /**
     * 反向关键词（可空）：响应体包含它即判定不可达，优先级最高；
     * 适用 nginx 等代理用 200 返回错误占位页的场景，如「502 Bad Gateway」「已到期」。
     */
    String errorKeyword;

    /**
     * 检查间隔（分钟）。
     */
    Integer intervalMinutes;

    /**
     * 单次请求超时（秒）。
     */
    Integer timeoutSeconds;

    /**
     * 是否启用：停用后定时任务不再探测。
     */
    Boolean enabled;

    /**
     * 是否邮件告警：连续失败满 {@link #ALERT_THRESHOLD} 次发告警邮件，恢复后发恢复通知。
     * 收件人为创建人的邮箱。
     */
    Boolean notifyEmail;

    /**
     * 告警邮件实际收件人（创建人邮箱），仅用于列表展示与提示，不持久化。
     * 为空表示创建人未配置邮箱，此时即使开启邮件告警也发不出去。
     */
    @Remark("告警收件邮箱")
    @Transient
    String notifyEmailTo;

    // ---------------- 以下为最近一次检测的快照（非用户配置） ----------------

    /**
     * 最近一次检测成功与否；null 表示尚未检测过。
     */
    Boolean lastSuccess;

    /**
     * 最近一次检测的 HTTP 状态码；请求都没发出去（连接超时等）时为 null。
     */
    Integer lastStatusCode;

    /**
     * 最近一次检测耗时（毫秒）。
     */
    Long lastLatencyMs;

    /**
     * 最近一次检测时间。
     */
    LocalDateTime lastCheckTime;

    /**
     * 最近一次检测的失败原因，成功时为空；截断避免把整页 html 灌进来。
     */
    @Column(length = 1000)
    String lastError;

    /**
     * 连续失败次数：成功即清零，驱动告警阈值。
     */
    Integer failCount;

    /**
     * 是否已经发过告警邮件：发过之后不再重复，恢复时发恢复通知并复位。
     */
    Boolean alerted;

}
