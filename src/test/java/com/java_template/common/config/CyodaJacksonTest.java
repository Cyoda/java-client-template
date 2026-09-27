package com.java_template.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.cyoda.cloud.api.common.model.ExternalizedProcessorDefinitionDto;
import org.cyoda.cloud.api.common.model.ProcessorDefinitionDto;
import org.cyoda.cloud.api.common.model.TransitionDefinitionDto;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CyodaJacksonTest {

    private final ObjectMapper om = CyodaJackson.configure(new ObjectMapper());

    @Test
    void aProcessorWithoutTypeIsExternalized() throws Exception {
        TransitionDefinitionDto t = om.readValue("""
                {"name":"go","next":"done","processors":[{"name":"P","config":{"calculationNodesTags":"x"}}]}""",
                TransitionDefinitionDto.class);

        assertThat(t.getProcessors()).singleElement().isInstanceOf(ExternalizedProcessorDefinitionDto.class);
    }

    @Test
    void aProcessorWithBlankTypeIsExternalized() throws Exception {
        TransitionDefinitionDto t = om.readValue("""
                {"name":"go","next":"done","processors":[{"type":"","name":"P"}]}""",
                TransitionDefinitionDto.class);

        assertThat(t.getProcessors().getFirst().getName()).isEqualTo("P");
        assertThat(t.getProcessors()).singleElement()
                .isInstanceOf(ExternalizedProcessorDefinitionDto.class)
                .extracting(ProcessorDefinitionDto::getType)
                .isEqualTo(ProcessorDefinitionDto.TypeEnum.EXTERNALIZED);
    }

    @Test
    void anExplicitExternalizedTypeStillWorks() throws Exception {
        TransitionDefinitionDto t = om.readValue("""
                {"name":"go","next":"done","processors":[{"type":"externalized","name":"P"}]}""",
                TransitionDefinitionDto.class);

        assertThat(t.getProcessors()).hasSize(1);
    }
}
