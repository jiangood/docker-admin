package io.github.jiangood.docker.admin.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * compose 文件内容。{@code mtime} 用作简单乐观锁：保存时与当前文件比对，避免覆盖别人的修改。
 */
@Data
public class ComposeFileContentVo implements Serializable {
    private static final long serialVersionUID = 1L;

    String path;
    String name;
    String content;
    long size;
    long mtime;
}
