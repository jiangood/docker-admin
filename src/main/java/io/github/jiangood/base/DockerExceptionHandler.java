package io.github.jiangood.base;


import com.github.dockerjava.api.exception.NotFoundException;
import io.github.jiangood.as.common.dto.AjaxResult;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Component
public class DockerExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public AjaxResult notFoundException(NotFoundException e) {
        return AjaxResult.err().msg("容器服务或资源不存在");
    }

}
