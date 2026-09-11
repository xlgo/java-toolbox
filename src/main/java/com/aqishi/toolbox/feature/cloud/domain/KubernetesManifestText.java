package com.aqishi.toolbox.feature.cloud.domain;

import com.aqishi.toolbox.util.Json;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * 清单文本格式转换。转换失败时原样返回输入——调用方把它当作
 * 展示层的尽力而为美化，而不是严格校验。
 */
public final class KubernetesManifestText {

    private KubernetesManifestText() {
    }

    /** JSON 转 YAML；输入不是合法 JSON 时原样返回。 */
    public static String jsonToYaml(String json) {
        try {
            Object obj = Json.mapper().readValue(json, Object.class);
            ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
            return yamlMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (Exception error) {
            return json;
        }
    }
}
