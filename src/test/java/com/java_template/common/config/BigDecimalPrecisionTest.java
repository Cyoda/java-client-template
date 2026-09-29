package com.java_template.common.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.java_template.common.dto.EntityWithMetadata;
import com.java_template.common.serializer.jackson.JacksonProcessorSerializer;
import com.java_template.common.workflow.CyodaEntity;
import com.java_template.common.workflow.OperationSpecification;
import org.cyoda.cloud.api.event.common.DataPayload;
import org.cyoda.cloud.api.event.common.EntityMetadata;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationRequest;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code BigDecimal} entity field beyond ~17 significant digits loses precision if the protocol mapper parses
 * the payload's {@code data} tree into {@code DoubleNode}s. This proves the protocol mapper parses decimal
 * literals as {@code DecimalNode} (backed by {@code BigDecimal}), so a high-precision value in an inbound
 * callout's entity data reaches the entity class unchanged, and still serializes back as a plain JSON number.
 */
class BigDecimalPrecisionTest {

    private static final String THIRTY_SIG_DIGITS = "123456789012345678901234567890.123456789";

    private final CyodaObjectMapper mappers = CyodaObjectMapper.of(new ObjectMapper());

    @Test
    void theProtocolMapperParsesADecimalLiteralAsADecimalNode() throws Exception {
        String json = """
                {"id":"test-1","entityId":"11111111-1111-1111-1111-111111111111",
                "payload":{"data":{"amount":%s,"quantity":1.5}}}""".formatted(THIRTY_SIG_DIGITS);

        EntityProcessorCalculationRequest request =
                mappers.protocol().readValue(json, EntityProcessorCalculationRequest.class);

        JsonNode amount = request.getPayload().getData().get("amount");
        assertThat(amount).isInstanceOf(DecimalNode.class);
        assertThat(amount.decimalValue()).isEqualByComparingTo(new BigDecimal(THIRTY_SIG_DIGITS));
    }

    @Test
    void aHighPrecisionEntityFieldReachesTheEntityClassUnchanged() throws Exception {
        String json = """
                {"id":"test-1","entityId":"11111111-1111-1111-1111-111111111111",
                "payload":{"data":{"amount":%s,"quantity":1.5}}}""".formatted(THIRTY_SIG_DIGITS);

        EntityProcessorCalculationRequest request =
                mappers.protocol().readValue(json, EntityProcessorCalculationRequest.class);

        EntityWithMetadata<TestEntity> withMetadata =
                EntityWithMetadata.fromDataPayload(request.getPayload(), TestEntity.class, mappers);

        assertThat(withMetadata.entity().getAmount()).isEqualByComparingTo(new BigDecimal(THIRTY_SIG_DIGITS));
        // A plain double entity field still converts fine through the entity mapper.
        assertThat(withMetadata.entity().getQuantity()).isEqualTo(1.5);
    }

    @Test
    void aHighPrecisionNumberRoundTripsAsAPlainJsonNumber() throws Exception {
        String json = """
                {"id":"test-1","entityId":"11111111-1111-1111-1111-111111111111",
                "payload":{"data":{"amount":%s}}}""".formatted(THIRTY_SIG_DIGITS);
        EntityProcessorCalculationRequest request =
                mappers.protocol().readValue(json, EntityProcessorCalculationRequest.class);

        // The round trip must go through the mapper's own writer, not JsonNode.toString() (which uses
        // Jackson's internal default ObjectMapper, not this one, and would prove nothing about our settings).
        // Pinning the closing brace right after the digits (rather than a bare "not scientific notation"
        // check) rules out any trailing characters, since unrelated fields like "success":true also contain "e".
        String written = mappers.protocol().writeValueAsString(request);

        assertThat(written).contains("\"amount\":" + THIRTY_SIG_DIGITS + "}");
    }

    @Test
    void aTrailingZeroScaleRoundTripsExactly() throws Exception {
        String json = """
                {"id":"test-1","entityId":"11111111-1111-1111-1111-111111111111",
                "payload":{"data":{"amount":10.00}}}""";
        EntityProcessorCalculationRequest request =
                mappers.protocol().readValue(json, EntityProcessorCalculationRequest.class);

        // Jackson 2.19's JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES defaults to true, which would
        // normalize 10.00 to a BigDecimal of scale -1 (unscaledValue 1), written back as "1E+1".
        String written = mappers.protocol().writeValueAsString(request);
        assertThat(written).contains("\"amount\":10.00");

        EntityWithMetadata<TestEntity> withMetadata =
                EntityWithMetadata.fromDataPayload(request.getPayload(), TestEntity.class, mappers);
        assertThat(withMetadata.entity().getAmount()).isEqualTo(new BigDecimal("10.00"));
        assertThat(withMetadata.entity().getAmount().scale()).isEqualTo(2);
    }

    @Test
    void aSmallDecimalSerializesAsAPlainNumberNotScientificNotation() throws Exception {
        String json = """
                {"id":"test-1","entityId":"11111111-1111-1111-1111-111111111111",
                "payload":{"data":{"amount":1e-7}}}""";
        EntityProcessorCalculationRequest request =
                mappers.protocol().readValue(json, EntityProcessorCalculationRequest.class);

        // 1e-7 as a BigDecimal has scale 7 and an adjusted exponent of -7, so BigDecimal.toString() alone
        // would write it back as "1E-7"; JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN forces toPlainString().
        String written = mappers.protocol().writeValueAsString(request);

        assertThat(written).contains("\"amount\":0.0000001").doesNotContainIgnoringCase("e-7");
    }

    /**
     * The write-back direction: entityToJsonNode / valueToTree (used by save, update, saveAll, updateAll and a
     * processor's response payload) goes through entities(), not protocol(). Jackson 2.19's
     * JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES (default true) would collapse the scale here before
     * protocol() ever sees the tree, in a way protocol()'s own fix (it only controls parsing and writing, not
     * how entities() itself builds a tree from a POJO) cannot repair afterwards.
     */
    @Test
    void anOutgoingEntitysTrailingZeroScaleSurvivesTheTreeConversion() throws Exception {
        TestEntity outgoing = new TestEntity();
        outgoing.setAmount(new BigDecimal("10.00"));

        JsonNode tree = mappers.entities().valueToTree(outgoing);

        assertThat(tree.get("amount")).isInstanceOf(DecimalNode.class);
        assertThat(tree.get("amount").decimalValue().scale()).isEqualTo(2);

        String written = mappers.protocol().writeValueAsString(tree);
        assertThat(written).contains("\"amount\":10.00");
    }

    /** The real processor path: entityToJsonNode builds the response payload, then protocol() serializes it. */
    @Test
    void aProcessorsReturnedEntityKeepsItsScaleAndSerializesViaProtocol() throws Exception {
        JacksonProcessorSerializer serializer = new JacksonProcessorSerializer(mappers);
        EntityProcessorCalculationRequest request = new EntityProcessorCalculationRequest()
                .withId("evt").withRequestId("r1").withEntityId(UUID.randomUUID())
                .withPayload(new DataPayload().withType("ENTITY").withData(mappers.protocol().createObjectNode()));
        TestEntity outgoing = new TestEntity();
        outgoing.setAmount(new BigDecimal("10.00"));

        EntityProcessorCalculationResponse response = serializer.responseBuilder(request)
                .withSuccess(outgoing, serializer::entityToJsonNode)
                .build();

        assertThat(response.getPayload().getData().get("amount")).isInstanceOf(DecimalNode.class);
        String written = mappers.protocol().writeValueAsString(response);
        assertThat(written).contains("\"amount\":10.00");
    }

    @SuppressWarnings({"LombokGetterMayBeUsed", "LombokSetterMayBeUsed", "unused"})
    static class TestEntity implements CyodaEntity {
        private BigDecimal amount;
        private double quantity;

        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }

        public double getQuantity() { return quantity; }
        public void setQuantity(double quantity) { this.quantity = quantity; }

        @Override
        public boolean isValid(EntityMetadata metadata) { return true; }

        @Override
        public OperationSpecification getModelKey() {
            ModelSpec modelSpec = new ModelSpec();
            modelSpec.setName("test-entity");
            modelSpec.setVersion(1);
            return new OperationSpecification.Entity(modelSpec, "test-entity");
        }
    }
}
