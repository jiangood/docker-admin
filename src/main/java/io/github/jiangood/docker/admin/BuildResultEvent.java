package io.github.jiangood.docker.admin;

import io.github.jiangood.docker.admin.entity.BuildLog;
import io.github.jiangood.docker.base.BaseEvent;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 构建结束事件：成功与失败都会发布，供邮件通知等旁路逻辑使用。
 * <p>
 * 与 {@link BuildSuccessEvent} 区分：后者仍只用于构建成功后的自动部署等流程。
 */
@Getter
@Setter
@ToString
public class BuildResultEvent extends BaseEvent {

    BuildLog buildLog;

    public BuildResultEvent(Object source) {
        super(source);
    }
}
