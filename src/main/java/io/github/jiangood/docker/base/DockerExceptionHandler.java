package io.github.jiangood.docker.base;


import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 容器引擎（docker-java）异常统一转换为可读提示。
 * <p>
 * 只处理确实来自 docker-java 的异常，避免把数据库等其他异常误报成「连接容器引擎失败」。
 * 主机不可达等传输层失败会被 docker-java 包装成普通 {@link RuntimeException}，
 * 不经这里，由全局处理器兜底。
 */
@RestControllerAdvice
@Component
public class DockerExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public AjaxResult notFoundException(NotFoundException e) {
        return AjaxResult.err().msg("容器服务或资源不存在");
    }

    @ExceptionHandler(DockerException.class)
    public AjaxResult dockerException(DockerException e) {
        return AjaxResult.err().msg("连接容器引擎失败：" + e.getMessage());
    }

}
