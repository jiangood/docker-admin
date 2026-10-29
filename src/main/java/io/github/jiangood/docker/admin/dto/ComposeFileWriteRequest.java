package io.github.jiangood.docker.admin.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 保存 compose 文件的请求体。走 JSON（文件可能上千行），不走表单。
 */
@Data
public class ComposeFileWriteRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    String hostId;

    /** 宿主机上的文件绝对路径 */
    String path;

    String content;

    /** 打开编辑器时拿到的 mtime，用于冲突检测；为空则跳过检测 */
    Long mtime;
}
