package io.github.jiangood.docker.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 镜像归档批量清理请求：只处理选中的记录，运行中的任务会在后端跳过。
 */
@Data
public class ImageDownloadCleanRequest {

    @NotEmpty(message = "请选择要清理的记录")
    @Size(max = 200, message = "单次最多清理 200 条记录")
    List<@NotBlank(message = "记录标识不能为空") String> logIds;

}
