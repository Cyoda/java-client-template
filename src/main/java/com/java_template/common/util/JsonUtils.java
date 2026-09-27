package com.java_template.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_template.common.config.CyodaObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * ABOUTME: Utility component providing JSON manipulation and conversion operations
 * using Jackson ObjectMapper for entity and payload processing.
 */
@Component
public class JsonUtils {
    private final ObjectMapper objectMapper;

    public JsonUtils(CyodaObjectMapper wireMapper) {
        this.objectMapper = wireMapper.mapper();
    }

    public String mapToJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new RuntimeException("Error converting Map to JSON", e);
        }
    }

    public Map<String, Object> jsonToMap(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new RuntimeException("Error converting JSON to Map", e);
        }
    }

    public String toJson(Object data) {
        try {
            if (data instanceof String) {
                return (String) data;
            }
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            throw new RuntimeException("Error converting to JSON", e);
        }
    }

    public String getJsonString(Object responseJson) {
        if (responseJson instanceof String) {
            return (String) responseJson;
        } else {
            return toJson(responseJson);
        }
    }

    public JsonNode getJsonNode(Object object) {
        return objectMapper.valueToTree(object);
    }
}
