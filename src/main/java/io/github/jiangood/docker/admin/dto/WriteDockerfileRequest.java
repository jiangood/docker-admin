package io.github.jiangood.docker.admin.dto;

import lombok.Data;

/**
 * 写入仓库请求：把编辑后的 Dockerfile 提交并推送到 Git 仓库的默认分支。
 */
@Data
public class WriteDockerfileRequest {

    /**
     * Git 仓库地址，凭据按地址主机自动匹配代码源。
     */
    String gitUrl;

    /**
     * 构建上下文：相对代码仓库根目录的目录，留空表示仓库根目录。
     * Dockerfile 写入该目录，而不是仓库根目录。
     */
    String context;

    /**
     * 要写入的 Dockerfile 内容，覆盖构建上下文目录下的同名文件。
     */
    String dockerfileText;

    /**
     * 提交说明，留空使用默认说明。
     */
    String commitMessage;

    /**
     * 目标分支，留空使用仓库默认分支。
     */
    String branch;
}
