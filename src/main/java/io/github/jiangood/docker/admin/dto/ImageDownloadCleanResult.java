package io.github.jiangood.docker.admin.dto;

import lombok.Data;

/**
 * 批量清理结果：运行中、正在下载、已清理或删除失败的记录计入 skipped。
 */
@Data
public class ImageDownloadCleanResult {

    private int cleaned;

    private int skipped;

}
