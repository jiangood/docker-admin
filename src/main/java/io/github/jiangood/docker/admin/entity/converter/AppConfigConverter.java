package io.github.jiangood.docker.admin.entity.converter;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.entity.App;
import io.github.jiangood.docker.base.tool.YamlTool;
import io.github.jiangood.openadmin.util.JsonTool;
import jakarta.persistence.AttributeConverter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Map;

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
            App.AppConfig cfg = JsonTool.jsonToBean(dbData, App.AppConfig.class);
            migrateLegacyEnvironment(cfg);
            return cfg;

        } catch (Exception e) {
            log.warn("解析应用配置失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 旧版环境变量为 YAML 字符串（environmentYAML），读取时一次性迁移到结构化 envs 并清空旧字段。
     */
    @SuppressWarnings("deprecation")
    private void migrateLegacyEnvironment(App.AppConfig cfg) {
        if (cfg == null || StrUtil.isBlank(cfg.getEnvironmentYAML())) {
            return;
        }
        if (cfg.getEnvs() == null) {
            cfg.setEnvs(new ArrayList<>());
        }
        if (cfg.getEnvs().isEmpty()) {
            Map<String, Object> dict = YamlTool.yamlToFlattenedMap(cfg.getEnvironmentYAML());
            for (Map.Entry<String, Object> e : dict.entrySet()) {
                App.EnvVar env = new App.EnvVar();
                env.setName(e.getKey());
                env.setValue(e.getValue() == null ? null : e.getValue().toString());
                cfg.getEnvs().add(env);
            }
        }
        cfg.setEnvironmentYAML(null);
    }
}
