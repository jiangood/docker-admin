package io.github.jiangood.docker.admin.entity.converter;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.entity.App;
import io.github.jiangood.openadmin.util.JsonTool;
import jakarta.persistence.AttributeConverter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AppConfigConverter implements AttributeConverter<App.AppConfig, String> {


    @Override
    public String convertToDatabaseColumn(App.AppConfig obj) {
        if (obj == null) {
            return null;
        }

        return JsonTool.toJsonQuietly(obj);

    }

    @Override
    public App.AppConfig convertToEntityAttribute(String dbData) {
        if (StrUtil.isBlank(dbData)) {
            return null;
        }
        try {
            return JsonTool.jsonToBean(dbData, App.AppConfig.class);

        } catch (Exception e) {
            log.warn("解析应用配置失败: {}", e.getMessage());
        }
        return null;
    }
}
