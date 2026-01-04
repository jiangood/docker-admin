package io.github.jiangood.docker.admin;

import io.github.jiangood.base.BaseEvent;
import io.github.jiangood.docker.admin.entity.BuildLog;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class BuildSuccessEvent extends BaseEvent {


    BuildLog buildLog;
    String version;

    public BuildSuccessEvent(Object source) {
        super(source);
    }
}
