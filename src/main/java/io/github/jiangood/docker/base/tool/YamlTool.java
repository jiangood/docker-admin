package io.github.jiangood.docker.base.tool;

import cn.hutool.core.util.StrUtil;
import org.apache.commons.lang3.StringUtils;
import org.yaml.snakeyaml.Yaml;

import java.util.*;

public class YamlTool {

    /**
     * yml文件流转成单层map
     * 转Properties 改变了顺序
     *
     * @param yamlContent
     * @return
     */
    public static Map<String, Object> yamlToFlattenedMap(String yamlContent) {
        if(StrUtil.isBlank(yamlContent)){
            return Collections.emptyMap();
        }
        Yaml yaml = createYaml();
        Map<String, Object> map=new HashMap<>();
        Iterable<Object> ite = yaml.loadAll(yamlContent);
        for (Object object : ite) {
            if (object != null) {
                map = asMap(object);
                map=getFlattenedMap(map);
            }
        }
        return map;
    }

    private static Yaml createYaml() {
        return new Yaml();
    }

    private static Map<String, Object> getFlattenedMap(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        buildFlattenedMap(result, source, null);
        return result;
    }
    private static void buildFlattenedMap(Map<String, Object> result, Map<String, Object> source, String path) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            if (!StringUtils.isBlank(path)) {
                if (key.startsWith("[")) {
                    key = path + key;
                } else {
                    key = path + '.' + key;
                }
            }
            Object value = entry.getValue();
            if (value instanceof String) {
                result.put(key, value);
            } else if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) value;
                buildFlattenedMap(result, map, key);
            } else if (value instanceof Collection) {
                @SuppressWarnings("unchecked")
                Collection<Object> collection = (Collection<Object>) value;
                int count = 0;
                for (Object object : collection) {
                    buildFlattenedMap(result, Collections.singletonMap("[" + (count++) + "]", object), key);
                }
            } else {
                result.put(key, (value != null ? value.toString() : ""));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object object) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!(object instanceof Map)) {
            result.put("document", object);
            return result;
        }

        Map<Object, Object> map = (Map<Object, Object>) object;
        for (Map.Entry<Object, Object> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map) {
                value = asMap(value);
            }
            Object key = entry.getKey();
            if (key instanceof CharSequence) {
                result.put(key.toString(), value);
            } else {
                result.put("[" + key.toString() + "]", value);
            }
        }
        return result;
    }



    public static void main(String[] args) {



    }
}
