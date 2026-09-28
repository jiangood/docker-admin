package io.github.jiangood.docker.admin.entity.converter;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.openadmin.util.JsonTool;
import jakarta.persistence.AttributeConverter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 字符串列表 <-> JSON 字符串。用于镜像版本声明的端口与卷路径。
 */
@Slf4j
public class StringListConverter implements AttributeConverter<List<String>, String> {

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        if (attribute == null) {
            return null;
        }
        return JsonTool.toJsonQuietly(attribute);
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (StrUtil.isBlank(dbData)) {
            return new ArrayList<>();
        }
        try {
            List<String> list = JsonTool.jsonToListQuietly(dbData);
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            log.warn("解析列表失败: {}", e.getMessage());
        }
        return new ArrayList<>();
    }
}
