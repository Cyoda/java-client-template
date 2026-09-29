package com.cyoda.build;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Build-time patches for open defects in cyoda-go's api/openapi.yaml (spec §3.3.3).
 * Each patch asserts the defect is present and fails with FixedUpstreamException once it is not.
 */
public final class OpenApiPatcher {

    private static final String REF = "#/components/schemas/";
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private OpenApiPatcher() {
    }

    public static ObjectNode patch(ObjectNode spec) {
        ObjectNode schemas = object(spec.at("/components/schemas"), "components.schemas");
        discriminatorMappings(schemas);
        arrayConditionValues(schemas);
        return spec;
    }

    // ---- cyoda-go #625 -------------------------------------------------------------

    private static void discriminatorMappings(ObjectNode schemas) {
        Map<String, String> four = new LinkedHashMap<>();
        four.put("simple", "SimpleConditionDto");
        four.put("group", "GroupConditionDto");
        four.put("lifecycle", "LifecycleConditionDto");
        four.put("array", "ArrayConditionDto");
        Map<String, String> five = new LinkedHashMap<>(four);
        five.put("function", "FunctionConditionDto");
        Map<String, String> audit = new LinkedHashMap<>();
        audit.put("EntityChange", "EntityChangeAuditEventDto");
        audit.put("StateMachine", "StateMachineAuditEventDto");
        audit.put("System", "SystemAuditEventDto");

        record Named(String schema, String property, Map<String, String> mapping) {}
        record Inline(String pointer, Map<String, String> mapping) {}
        List<Named> named = List.of(
                new Named("QueryConditionDto", "type", Map.of("function", "FunctionConditionDto")),
                new Named("AbstractConditionDto", "type", four),
                new Named("AuditEventDto", "auditEventType", audit));
        List<Inline> inline = List.of(
                new Inline("/TransitionDefinitionDto/properties/criterion", five),
                new Inline("/WorkflowConfigurationDto/properties/criterion", five),
                new Inline("/GroupConditionDto/allOf/1/properties/conditions/items", four),
                new Inline("/GroupedStatsRequest/properties/condition", four));

        List<String> alreadyMapped = new ArrayList<>();
        for (Named n : named) {
            JsonNode disc = schemas.at("/" + n.schema() + "/discriminator");
            if (!n.property().equals(disc.path("propertyName").asText(null))) {
                throw shapeChanged("discriminatorMappings (#625)", n.schema() + ".discriminator.propertyName");
            }
            if (disc.has("mapping")) {
                alreadyMapped.add(n.schema());
            }
        }
        for (Inline i : inline) {
            JsonNode node = schemas.at(i.pointer());
            Set<String> refs = new LinkedHashSet<>();
            node.path("oneOf").forEach(r -> refs.add(r.path("$ref").asText()));
            Set<String> expected = new LinkedHashSet<>();
            i.mapping().values().forEach(v -> expected.add(REF + v));
            if (!refs.equals(expected)) {
                throw shapeChanged("discriminatorMappings (#625)", i.pointer() + ".oneOf = " + refs);
            }
            if (node.path("discriminator").has("mapping")) {
                alreadyMapped.add(i.pointer());
            }
        }
        if (!alreadyMapped.isEmpty()) {
            throw new FixedUpstreamException("cyoda-go #625 is fixed upstream (mapping present at " + alreadyMapped
                    + "): remove patch discriminatorMappings");
        }

        for (Named n : named) {
            ((ObjectNode) schemas.at("/" + n.schema() + "/discriminator")).set("mapping", mapping(n.mapping()));
        }
        for (Inline i : inline) {
            ObjectNode disc = ((ObjectNode) schemas.at(i.pointer())).putObject("discriminator");
            disc.put("propertyName", "type");
            disc.set("mapping", mapping(i.mapping()));
        }
    }

    private static ObjectNode mapping(Map<String, String> values) {
        ObjectNode m = F.objectNode();
        values.forEach((wire, schema) -> m.put(wire, REF + schema));
        return m;
    }

    // ---- cyoda-go #627 -------------------------------------------------------------

    private static void arrayConditionValues(ObjectNode schemas) {
        ObjectNode array = object(schemas.get("ArrayConditionDto"), "ArrayConditionDto");
        ObjectNode props = object(array.at("/allOf/1/properties"), "ArrayConditionDto.allOf[1].properties");
        boolean defective = props.has("operatorType") && props.has("value") && !props.has("values");
        if (!defective) {
            if (props.has("values") && !props.has("operatorType") && !props.has("value")) {
                throw new FixedUpstreamException("cyoda-go #627 is fixed upstream: remove patch arrayConditionValues");
            }
            throw shapeChanged("arrayConditionValues (#627)", "ArrayConditionDto properties " + props.fieldNames());
        }
        if (!schemas.has("JsonNode")) {
            throw shapeChanged("arrayConditionValues (#627)", "components.schemas.JsonNode is missing");
        }
        props.remove("operatorType");
        props.remove("value");
        ObjectNode values = props.putObject("values");
        values.put("type", "array");
        values.putObject("items").put("$ref", REF + "JsonNode");
        values.put("description", "Positional values, one per array index; a null entry skips that index.");

        ObjectNode owner = array.has("required") ? array : (ObjectNode) array.at("/allOf/1");
        ArrayNode required = F.arrayNode().add("jsonPath").add("type").add("values");
        owner.set("required", required);
    }

    private static ObjectNode object(JsonNode node, String what) {
        if (!(node instanceof ObjectNode o)) {
            throw new IllegalStateException("cyoda-go openapi.yaml changed shape: " + what + " is missing; review OpenApiPatcher");
        }
        return o;
    }

    private static IllegalStateException shapeChanged(String patch, String where) {
        return new IllegalStateException("cyoda-go openapi.yaml changed shape at " + where
                + ": review patch " + patch);
    }
}
