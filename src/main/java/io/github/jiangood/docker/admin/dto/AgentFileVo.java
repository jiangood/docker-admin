package io.github.jiangood.docker.admin.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 辅助容器挂载目录下的单个条目（相对挂载目录的视图，供 Agent 面板「上传文件」弹窗选择目标路径）。
 */
@Data
public class AgentFileVo implements Serializable {
    private static final long serialVersionUID = 1L;

    String name;
    String type;   // dir / file
}
