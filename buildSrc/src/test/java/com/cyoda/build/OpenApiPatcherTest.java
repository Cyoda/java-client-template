package com.cyoda.build;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenApiPatcherTest {

    private static final ObjectMapper YAML = new YAMLMapper();

    private static final String FIXTURE = """
            components:
              schemas:
                JsonNode: {}
                QueryConditionDto:
                  type: object
                  discriminator: {propertyName: type}
                AbstractConditionDto:
                  type: object
                  discriminator: {propertyName: type}
                AuditEventDto:
                  type: object
                  discriminator: {propertyName: auditEventType}
                TransitionDefinitionDto:
                  properties:
                    criterion:
                      oneOf:
                        - $ref: '#/components/schemas/SimpleConditionDto'
                        - $ref: '#/components/schemas/GroupConditionDto'
                        - $ref: '#/components/schemas/LifecycleConditionDto'
                        - $ref: '#/components/schemas/ArrayConditionDto'
                        - $ref: '#/components/schemas/FunctionConditionDto'
                WorkflowConfigurationDto:
                  properties:
                    criterion:
                      oneOf:
                        - $ref: '#/components/schemas/SimpleConditionDto'
                        - $ref: '#/components/schemas/GroupConditionDto'
                        - $ref: '#/components/schemas/LifecycleConditionDto'
                        - $ref: '#/components/schemas/ArrayConditionDto'
                        - $ref: '#/components/schemas/FunctionConditionDto'
                GroupConditionDto:
                  allOf:
                    - $ref: '#/components/schemas/AbstractConditionDto'
                    - type: object
                      properties:
                        conditions:
                          type: array
                          items:
                            oneOf:
                              - $ref: '#/components/schemas/SimpleConditionDto'
                              - $ref: '#/components/schemas/GroupConditionDto'
                              - $ref: '#/components/schemas/LifecycleConditionDto'
                              - $ref: '#/components/schemas/ArrayConditionDto'
                GroupedStatsRequest:
                  properties:
                    condition:
                      oneOf:
                        - $ref: '#/components/schemas/SimpleConditionDto'
                        - $ref: '#/components/schemas/GroupConditionDto'
                        - $ref: '#/components/schemas/LifecycleConditionDto'
                        - $ref: '#/components/schemas/ArrayConditionDto'
                ArrayConditionDto:
                  allOf:
                    - $ref: '#/components/schemas/AbstractConditionDto'
                    - type: object
                      properties:
                        jsonPath: {type: string}
                        operatorType: {type: string, enum: [EQUALS]}
                        value: {type: array, items: {type: string}}
                  required: [jsonPath, operatorType, type, value]
            """;

    private ObjectNode fixture() throws Exception {
        return (ObjectNode) YAML.readTree(FIXTURE);
    }

    @Test
    void addsDiscriminatorMappingsToNamedSchemasAndInlineUnions() throws Exception {
        ObjectNode spec = OpenApiPatcher.patch(fixture());

        assertThat(spec.at("/components/schemas/QueryConditionDto/discriminator/mapping/function").asText())
                .isEqualTo("#/components/schemas/FunctionConditionDto");
        assertThat(spec.at("/components/schemas/AbstractConditionDto/discriminator/mapping").size()).isEqualTo(4);
        assertThat(spec.at("/components/schemas/AuditEventDto/discriminator/mapping/EntityChange").asText())
                .isEqualTo("#/components/schemas/EntityChangeAuditEventDto");
        JsonNode criterion = spec.at("/components/schemas/TransitionDefinitionDto/properties/criterion/discriminator");
        assertThat(criterion.get("propertyName").asText()).isEqualTo("type");
        assertThat(criterion.get("mapping").size()).isEqualTo(5);
        assertThat(spec.at("/components/schemas/GroupConditionDto/allOf/1/properties/conditions/items/discriminator/mapping/simple").asText())
                .isEqualTo("#/components/schemas/SimpleConditionDto");
        assertThat(spec.at("/components/schemas/GroupedStatsRequest/properties/condition/discriminator/mapping").size()).isEqualTo(4);
    }

    @Test
    void rewritesArrayConditionToValues() throws Exception {
        ObjectNode spec = OpenApiPatcher.patch(fixture());

        JsonNode props = spec.at("/components/schemas/ArrayConditionDto/allOf/1/properties");
        assertThat(props.has("operatorType")).isFalse();
        assertThat(props.has("value")).isFalse();
        assertThat(props.at("/values/type").asText()).isEqualTo("array");
        assertThat(props.at("/values/items/$ref").asText()).isEqualTo("#/components/schemas/JsonNode");
        assertThat(spec.at("/components/schemas/ArrayConditionDto/required").toString())
                .isEqualTo("[\"jsonPath\",\"type\",\"values\"]");
    }

    @Test
    void failsOnceADiscriminatorMappingExistsUpstream() throws Exception {
        ObjectNode spec = fixture();
        ((ObjectNode) spec.at("/components/schemas/AbstractConditionDto/discriminator"))
                .putObject("mapping").put("simple", "#/components/schemas/SimpleConditionDto");

        assertThatThrownBy(() -> OpenApiPatcher.patch(spec))
                .isInstanceOf(FixedUpstreamException.class)
                .hasMessageContaining("cyoda-go #625 is fixed upstream")
                .hasMessageContaining("remove patch discriminatorMappings");
    }

    @Test
    void failsOnceArrayConditionIsFixedUpstream() throws Exception {
        ObjectNode spec = fixture();
        ObjectNode props = (ObjectNode) spec.at("/components/schemas/ArrayConditionDto/allOf/1/properties");
        props.remove("operatorType");
        props.remove("value");
        props.putObject("values").put("type", "array");

        assertThatThrownBy(() -> OpenApiPatcher.patch(spec))
                .isInstanceOf(FixedUpstreamException.class)
                .hasMessageContaining("cyoda-go #627 is fixed upstream")
                .hasMessageContaining("remove patch arrayConditionValues");
    }

    @Test
    void failsWhenAPatchedShapeChangedUnexpectedly() throws Exception {
        ObjectNode spec = fixture();
        ((ObjectNode) spec.at("/components/schemas")).remove("GroupedStatsRequest");

        assertThatThrownBy(() -> OpenApiPatcher.patch(spec))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(FixedUpstreamException.class)
                .hasMessageContaining("GroupedStatsRequest");
    }

    @Test
    void patchesTheVendoredSpec() throws Exception {
        File vendored = new File("../src/main/resources/cyoda/openapi/openapi.yaml");
        ObjectNode spec = (ObjectNode) YAML.readTree(vendored);

        OpenApiPatcher.patch(spec);

        assertThat(spec.at("/components/schemas/AbstractConditionDto/discriminator/mapping").size()).isEqualTo(4);
    }
}
