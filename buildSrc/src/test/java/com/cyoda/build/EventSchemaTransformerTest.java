package com.cyoda.build;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventSchemaTransformerTest {

    private final ObjectMapper om = new ObjectMapper();

    private ObjectNode json(String s) throws Exception {
        return (ObjectNode) om.readTree(s);
    }

    @Test
    void rewritesTheSingleBaseEventAllOfToExtends() throws Exception {
        ObjectNode schema = json("""
                {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
                 "allOf":[{"$ref":"../common/BaseEvent.json"}],
                 "properties":{"matches":{"type":"boolean"}}}""");

        EventSchemaTransformer.transform(schema, "EntityCriteriaCalculationResponse.json");

        assertThat(schema.has("allOf")).isFalse();
        assertThat(schema.at("/extends/$ref").asText()).isEqualTo("../common/BaseEvent.json");
    }

    @Test
    void rejectsAnyOtherAllOf() throws Exception {
        ObjectNode twoRefs = json("""
                {"allOf":[{"$ref":"../common/BaseEvent.json"},{"$ref":"X.json"}]}""");
        ObjectNode nested = json("""
                {"properties":{"p":{"allOf":[{"$ref":"X.json"}]}}}""");

        assertThatThrownBy(() -> EventSchemaTransformer.transform(twoRefs, "A.json"))
                .hasMessageContaining("A.json").hasMessageContaining("allOf");
        assertThatThrownBy(() -> EventSchemaTransformer.transform(nested, "B.json"))
                .hasMessageContaining("B.json").hasMessageContaining("/properties/p");
    }

    @Test
    void typelessObjectPropertiesBecomeJsonNode() throws Exception {
        ObjectNode schema = json("""
                {"type":"object","properties":{
                   "result":{"type":"object","description":"Function calculation result."},
                   "typed":{"type":"object","properties":{"a":{"type":"string"}}},
                   "map":{"type":"object","additionalProperties":{"type":"string"}},
                   "keep":{"type":"object","existingJavaType":"java.util.Map<String,Object>"},
                   "list":{"type":"array","items":{"type":"object"}}}}""");

        EventSchemaTransformer.transform(schema, "R.json");

        assertThat(schema.at("/properties/result/existingJavaType").asText()).isEqualTo(EventSchemaTransformer.JSON_NODE);
        assertThat(schema.at("/properties/typed").has("existingJavaType")).isFalse();
        assertThat(schema.at("/properties/map").has("existingJavaType")).isFalse();
        assertThat(schema.at("/properties/keep/existingJavaType").asText()).isEqualTo("java.util.Map<String,Object>");
        assertThat(schema.at("/properties/list/items/existingJavaType").asText()).isEqualTo(EventSchemaTransformer.JSON_NODE);
    }

    @Test
    void orderByItemsShareOneJavaType() throws Exception {
        ObjectNode schema = json("""
                {"type":"object","properties":{"orderBy":{"type":"array","items":
                  {"type":"object","properties":{"path":{"type":"string"}}}}}}""");

        EventSchemaTransformer.transform(schema, "S.json");

        assertThat(schema.at("/properties/orderBy/items/javaType").asText()).isEqualTo(EventSchemaTransformer.ORDER_BY_JAVA_TYPE);
    }

    @Test
    void anOrderByOfAnotherShapeFails() throws Exception {
        ObjectNode schema = json("""
                {"type":"object","properties":{"orderBy":{"type":"string"}}}""");

        assertThatThrownBy(() -> EventSchemaTransformer.transform(schema, "S.json"))
                .hasMessageContaining("S.json").hasMessageContaining("orderBy");
    }
}
