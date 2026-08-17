package io.github.jiangood.docker.sdk.registry;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class TagVo {
    String tagName;

    LocalDateTime time;

    String  url;
}
