package com.java_template.common.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.java_template.common.dto.EntityWithMetadata;
import com.java_template.common.grpc.client.event_handling.CloudEventParser;
import com.java_template.common.serializer.jackson.JacksonProcessorSerializer;
import com.java_template.common.workflow.CyodaContextFactory;
import com.java_template.common.workflow.CyodaEntity;
import com.java_template.common.workflow.OperationSpecification;
import io.cloudevents.v1.proto.CloudEvent;
import org.cyoda.cloud.api.common.model.ExternalizedProcessorDefinitionDto;
import org.cyoda.cloud.api.common.model.TransitionDefinitionDto;
import org.cyoda.cloud.api.event.common.DataPayload;
import org.cyoda.cloud.api.event.common.EntityChangeMeta;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationRequest;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ABOUTME: The framework's two mappers (spec §4.9). Cyoda protocol messages always use the fixed protocol mapper,
 * whatever the app's spring.jackson.* settings; the app's entities use the app's own primary ObjectMapper, as is,
 * never copied or modified; and an app with several ObjectMappers and no primary one fails at startup.
 */
class CyodaObjectMapperAutoConfigurationTest {

    private static final String NANO_INSTANT = "2026-09-27T10:11:12.123456789Z";
    private static final String JSR310_MODULE_ID = new JavaTimeModule().getTypeId().toString();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, CyodaJacksonAutoConfiguration.class));

    @Configuration(proxyBeanMethods = false)
    static class PlainAppMapper {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoAppMappers {
        @Bean
        ObjectMapper firstMapper() {
            return new ObjectMapper();
        }

        @Bean
        ObjectMapper secondMapper() {
            return new ObjectMapper();
        }
    }

    /** An app entity whose Java field is camelCase, so a naming strategy shows in its JSON. */
    static class Shipment implements CyodaEntity {
        public String trackingNumber;
        public OffsetDateTime shippedAt;

        @Override
        public OperationSpecification getModelKey() {
            return null;
        }
    }

    private static void assertMeetsTheWireContract(ObjectMapper wire) throws Exception {
        EntityChangeMeta meta = new EntityChangeMeta();
        meta.setTimeOfChange(OffsetDateTime.parse(NANO_INSTANT));

        String json = wire.writeValueAsString(meta);

        assertThat(json).contains("\"timeOfChange\":\"" + NANO_INSTANT + "\"");
        assertThat(wire.readValue(json, EntityChangeMeta.class).getTimeOfChange())
                .isEqualTo(OffsetDateTime.parse(NANO_INSTANT));
        TransitionDefinitionDto t = wire.readValue(
                "{\"name\":\"go\",\"next\":\"done\",\"processors\":[{\"name\":\"P\"}]}",
                TransitionDefinitionDto.class);
        assertThat(t.getProcessors()).singleElement().isInstanceOf(ExternalizedProcessorDefinitionDto.class);
        // cyoda-go may add fields to any message at any time
        assertThat(wire.readValue("{\"timeOfChange\":\"" + NANO_INSTANT + "\",\"addedLater\":1}", EntityChangeMeta.class)
                .getTimeOfChange()).isEqualTo(OffsetDateTime.parse(NANO_INSTANT));
    }

    private static CloudEvent callout(String json) {
        return CloudEvent.newBuilder().setId("evt").setTextData(json).build();
    }

    @Test
    void entitiesUseTheAppMapperAsIsAndProtocolMessagesTheFixedMapper() {
        runner.withUserConfiguration(PlainAppMapper.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(CyodaObjectMapper.class);
            assertThat(ctx).hasSingleBean(ObjectMapper.class);
            CyodaObjectMapper mappers = ctx.getBean(CyodaObjectMapper.class);
            ObjectMapper appMapper = ctx.getBean(ObjectMapper.class);

            assertMeetsTheWireContract(mappers.protocol());
            assertThat(mappers.protocol()).isNotSameAs(appMapper);
            assertThat(mappers.entities()).isSameAs(appMapper);
            // the app's mapper is left exactly as the app configured it
            assertThat(appMapper.getRegisteredModuleIds()).doesNotContain(JSR310_MODULE_ID);
            assertThat(appMapper.getDeserializationConfig().getProblemHandlers()).isNull();
            assertThat(appMapper.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)).isTrue();
        });
    }

    @Test
    void theAppsWriteDatesAsTimestampsSettingNeitherReachesTheProtocolNorIsOverridden() {
        runner.withPropertyValues("spring.jackson.serialization.write-dates-as-timestamps=true").run(ctx -> {
            assertThat(ctx).hasSingleBean(ObjectMapper.class);
            ObjectMapper appMapper = ctx.getBean(ObjectMapper.class);
            CyodaObjectMapper mappers = ctx.getBean(CyodaObjectMapper.class);

            assertThat(appMapper.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)).isTrue();
            assertThat(mappers.entities()).isSameAs(appMapper);
            assertThat(mappers.protocol().isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)).isFalse();
            assertMeetsTheWireContract(mappers.protocol());
        });
    }

    @Test
    void anInboundCalloutWithAnUnknownFieldParsesWhenTheAppFailsOnUnknownProperties() {
        runner.withPropertyValues("spring.jackson.deserialization.fail-on-unknown-properties=true").run(ctx -> {
            ObjectMapper appMapper = ctx.getBean(ObjectMapper.class);
            assertThat(appMapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isTrue();
            CyodaObjectMapper mappers = ctx.getBean(CyodaObjectMapper.class);
            String json = """
                    {"id":"e1","requestId":"r1","entityId":"00000000-0000-0000-0000-000000000001",
                     "processorName":"P","fieldCyodaAddsLater":{"x":1},
                     "payload":{"type":"ENTITY","data":{"a":1},"meta":{"state":"s","metaAddedLater":true}}}""";

            EntityProcessorCalculationRequest viaContextFactory = new CyodaContextFactory(mappers)
                    .createCyodaEventContext(callout(json), EntityProcessorCalculationRequest.class).getEvent();
            EntityProcessorCalculationRequest viaParser = new CloudEventParser(mappers)
                    .parseCloudEvent(callout(json), EntityProcessorCalculationRequest.class);

            assertThat(viaContextFactory.getRequestId()).isEqualTo("r1");
            assertThat(viaParser.getRequestId()).isEqualTo("r1");
            assertThat(viaParser.getPayload().getData().get("a").asInt()).isEqualTo(1);
        });
    }

    @Test
    void snakeCaseAppliesToEntitiesButProtocolMessagesKeepTheirWireNames() {
        runner.withPropertyValues("spring.jackson.property-naming-strategy=SNAKE_CASE").run(ctx -> {
            CyodaObjectMapper mappers = ctx.getBean(CyodaObjectMapper.class);
            JacksonProcessorSerializer serializer = new JacksonProcessorSerializer(mappers);
            Shipment shipment = new Shipment();
            shipment.trackingNumber = "T-1";

            JsonNode entityJson = serializer.entityToJsonNode(shipment);

            assertThat(entityJson.has("tracking_number")).isTrue();
            assertThat(entityJson.has("trackingNumber")).isFalse();

            // a callout's payload: entity data in the app's naming, meta and envelope in cyoda's
            ObjectNode data = mappers.protocol().createObjectNode().put("tracking_number", "T-2");
            UUID id = UUID.randomUUID();
            EntityProcessorCalculationRequest request = new CloudEventParser(mappers).parseCloudEvent(callout("""
                    {"id":"e1","requestId":"r1","entityId":"%s","processorName":"P",
                     "payload":{"type":"ENTITY","data":%s,"meta":{"id":"%s","state":"new","creationDate":"%s"}}}"""
                    .formatted(id, data, id, NANO_INSTANT)), EntityProcessorCalculationRequest.class);
            EntityWithMetadata<Shipment> read = serializer.extractEntityWithMetadata(request, Shipment.class);

            assertThat(read.entity().trackingNumber).isEqualTo("T-2");
            assertThat(read.getId()).isEqualTo(id);
            assertThat(read.getState()).isEqualTo("new");
            assertThat(read.getCreationDate()).isEqualTo(OffsetDateTime.parse(NANO_INSTANT));

            String response = mappers.protocol().writeValueAsString(new EntityProcessorCalculationResponse()
                    .withId("x").withRequestId("r1").withEntityId(id)
                    .withPayload(new DataPayload().withType("ENTITY").withData(entityJson)));
            assertThat(response).contains("\"requestId\":\"r1\"").contains("\"entityId\":\"" + id + "\"")
                    .contains("\"tracking_number\":\"T-1\"")
                    .doesNotContain("request_id").doesNotContain("entity_id");
        });
    }

    @Test
    void bootsDefaultMapperWritesEntityDateTimesAsIsoText() {
        runner.run(ctx -> {
            CyodaObjectMapper mappers = ctx.getBean(CyodaObjectMapper.class);
            Shipment shipment = new Shipment();
            shipment.shippedAt = OffsetDateTime.parse(NANO_INSTANT);

            JsonNode tree = mappers.entities().valueToTree(shipment);

            assertThat(tree.get("shippedAt").isTextual()).isTrue();
            assertThat(tree.get("shippedAt").asText()).isEqualTo(NANO_INSTANT);
        });
    }

    @Test
    void severalAppMappersWithNoPrimaryFailAtStartupNamingTheFix() {
        runner.withUserConfiguration(TwoAppMappers.class).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause()
                    .hasMessageContaining("firstMapper")
                    .hasMessageContaining("secondMapper")
                    .hasMessageContaining("@Primary");
        });
    }

    @Test
    void noAppMapperFailsAtStartupNamingTheFix() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CyodaJacksonAutoConfiguration.class))
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).rootCause()
                            .hasMessageContaining("ObjectMapper")
                            .hasMessageContaining("JacksonAutoConfiguration");
                });
    }

    @Test
    void standaloneMappersMeetTheContractAndWriteEntityDateTimesAsBootDoes() throws Exception {
        CyodaObjectMapper standalone = CyodaObjectMapper.standalone();
        Shipment shipment = new Shipment();
        shipment.shippedAt = OffsetDateTime.parse(NANO_INSTANT);

        assertMeetsTheWireContract(standalone.protocol());
        assertThat(standalone.entities().valueToTree(shipment).get("shippedAt").asText()).isEqualTo(NANO_INSTANT);
    }

    @Test
    void ofUsesTheAppMapperForEntitiesWithoutModifyingIt() throws Exception {
        ObjectMapper base = new ObjectMapper();

        CyodaObjectMapper mappers = CyodaObjectMapper.of(base);

        assertThat(mappers.entities()).isSameAs(base);
        assertThat(base.getRegisteredModuleIds()).isEmpty();
        assertThat(base.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)).isTrue();
        assertMeetsTheWireContract(mappers.protocol());
    }
}
