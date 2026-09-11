package com.aqishi.toolbox.feature.cloud.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KubernetesManifestTextTest {

    @Test
    void convertsJsonToYaml() {
        String yaml = KubernetesManifestText.jsonToYaml("{\"kind\":\"Pod\",\"metadata\":{\"name\":\"web\"}}");
        assertTrue(yaml.contains("kind: \"Pod\"") || yaml.contains("kind: Pod"), yaml);
        assertTrue(yaml.contains("name:"), yaml);
    }

    @Test
    void invalidJsonPassesThroughUnchanged() {
        String notJson = "kind: Pod\n  broken: [";
        assertEquals(notJson, KubernetesManifestText.jsonToYaml(notJson));
    }
}
