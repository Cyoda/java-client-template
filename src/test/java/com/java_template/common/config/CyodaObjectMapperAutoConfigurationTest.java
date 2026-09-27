package com.java_template.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.cyoda.cloud.api.common.model.ExternalizedProcessorDefinitionDto;
import org.cyoda.cloud.api.common.model.TransitionDefinitionDto;
import org.cyoda.cloud.api.event.common.EntityChangeMeta;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ABOUTME: The framework's wire mapper (CyodaObjectMapper) always meets the cyoda-go contract, whatever the
 * app does with its own primary ObjectMapper, and the app's mapper is left exactly as the app configured it.
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
    }

    @Test
    void wireMapperMeetsTheContractWhenTheAppDefinesAPlainObjectMapper() {
        runner.withUserConfiguration(PlainAppMapper.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(CyodaObjectMapper.class);
            assertThat(ctx).hasSingleBean(ObjectMapper.class);

            assertMeetsTheWireContract(ctx.getBean(CyodaObjectMapper.class).mapper());

            ObjectMapper appMapper = ctx.getBean(ObjectMapper.class);
            assertThat(ctx.getBean(CyodaObjectMapper.class).mapper()).isNotSameAs(appMapper);
            assertThat(appMapper.getRegisteredModuleIds()).doesNotContain(JSR310_MODULE_ID);
            assertThat(appMapper.getDeserializationConfig().getProblemHandlers()).isNull();
            assertThat(appMapper.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)).isTrue();
        });
    }

    @Test
    void appPrimaryMapperKeepsItsOwnWriteDatesAsTimestampsSetting() {
        runner.withPropertyValues("spring.jackson.serialization.write-dates-as-timestamps=true").run(ctx -> {
            assertThat(ctx).hasSingleBean(ObjectMapper.class);
            assertThat(ctx.getBean(ObjectMapper.class).isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)).isTrue();

            ObjectMapper wire = ctx.getBean(CyodaObjectMapper.class).mapper();
            assertThat(wire.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)).isFalse();
            assertMeetsTheWireContract(wire);
        });
    }

    @Test
    void standaloneWireMapperMeetsTheContract() throws Exception {
        assertMeetsTheWireContract(CyodaObjectMapper.standalone().mapper());
    }

    @Test
    void fromDoesNotModifyItsBase() {
        ObjectMapper base = new ObjectMapper();

        CyodaObjectMapper.from(base);

        assertThat(base.getRegisteredModuleIds()).isEmpty();
        assertThat(base.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)).isTrue();
    }
}
