package com.java_template.common.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.java_template.common.dto.EntityWithMetadata;
import com.java_template.common.workflow.CyodaEntity;
import com.java_template.common.workflow.OperationSpecification;
import org.cyoda.cloud.api.event.common.EntityMetadata;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

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
    void aHighPrecisionNumberSerializesBackAsAPlainJsonNumber() {
        DecimalNode node = DecimalNode.valueOf(new BigDecimal(THIRTY_SIG_DIGITS));

        String written = mappers.protocol().valueToTree(node).toString();

        assertThat(written).doesNotContain("\"").doesNotContainIgnoringCase("e");
        assertThat(new BigDecimal(written)).isEqualByComparingTo(new BigDecimal(THIRTY_SIG_DIGITS));
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
