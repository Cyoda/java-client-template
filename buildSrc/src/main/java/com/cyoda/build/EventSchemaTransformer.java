package com.cyoda.build;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

/**
 * Makes a cyoda-go event schema (JSON Schema 2020-12) consumable by jsonschema2pojo (spec §3.3.2).
 * Each rule asserts the input shape it expects and fails naming the file otherwise.
 */
public final class EventSchemaTransformer {

    public static final String JSON_NODE = "com.fasterxml.jackson.databind.JsonNode";
    public static final String ORDER_BY_JAVA_TYPE = "org.cyoda.cloud.api.event.search.OrderBy";

    private EventSchemaTransformer() {
    }

    public static ObjectNode transform(ObjectNode schema, String label) {
        rewriteBaseEventAllOf(schema, label);
        rejectAllOf(schema, label, "");
        walk(schema, label, "");
        return schema;
    }

    private static void rewriteBaseEventAllOf(ObjectNode root, String label) {
        JsonNode allOf = root.get("allOf");
        if (allOf == null) {
            return;
        }
        boolean expected = allOf instanceof ArrayNode array
                && array.size() == 1
                && array.get(0).isObject()
                && array.get(0).size() == 1
                && array.get(0).path("$ref").asText("").endsWith("BaseEvent.json");
        if (!expected) {
            throw new IllegalStateException(label + ": unexpected allOf " + allOf
                    + " (only a single $ref to BaseEvent.json is rewritten); update EventSchemaTransformer");
        }
        String ref = allOf.get(0).get("$ref").asText();
        root.remove("allOf");
        ObjectNode ext = JsonNodeFactory.instance.objectNode();
        ext.put("$ref", ref);
        root.set("extends", ext);
    }

    private static void rejectAllOf(JsonNode node, String label, String path) {
        if (node.isObject()) {
            if (node.has("allOf")) {
                throw new IllegalStateException(label + ": unexpected allOf at " + (path.isEmpty() ? "/" : path)
                        + "; update EventSchemaTransformer");
            }
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                rejectAllOf(e.getValue(), label, path + "/" + e.getKey());
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                rejectAllOf(node.get(i), label, path + "/" + i);
            }
        }
    }

    private static void walk(JsonNode node, String label, String path) {
        if (!node.isObject()) {
            if (node.isArray()) {
                for (int i = 0; i < node.size(); i++) {
                    walk(node.get(i), label, path + "/" + i);
                }
            }
            return;
        }
        ObjectNode obj = (ObjectNode) node;
        JsonNode props = obj.get("properties");
        if (props != null && props.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = props.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getKey().equals("orderBy")) {
                    nameOrderBy(e.getValue(), label, path + "/properties/orderBy");
                }
                injectJsonNodeIfTypeless(e.getValue());
                JsonNode items = e.getValue().get("items");
                if (items != null) {
                    injectJsonNodeIfTypeless(items);
                }
            }
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = obj.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            walk(e.getValue(), label, path + "/" + e.getKey());
        }
    }

    private static void injectJsonNodeIfTypeless(JsonNode node) {
        if (node instanceof ObjectNode o
                && "object".equals(o.path("type").asText())
                && !o.has("properties") && !o.has("additionalProperties") && !o.has("patternProperties")
                && !o.has("existingJavaType") && !o.has("javaType") && !o.has("$ref")) {
            o.put("existingJavaType", JSON_NODE);
        }
    }

    private static void nameOrderBy(JsonNode orderBy, String label, String path) {
        JsonNode items = orderBy.get("items");
        boolean expected = "array".equals(orderBy.path("type").asText())
                && items instanceof ObjectNode
                && "object".equals(items.path("type").asText())
                && items.has("properties");
        if (!expected) {
            throw new IllegalStateException(label + ": orderBy at " + path
                    + " is not an array of inline objects; update EventSchemaTransformer");
        }
        ((ObjectNode) items).put("javaType", ORDER_BY_JAVA_TYPE);
    }
}
