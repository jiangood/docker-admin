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
     * 要写入的 Dockerfile 内容，覆盖仓库根目录下的同名文件。
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
