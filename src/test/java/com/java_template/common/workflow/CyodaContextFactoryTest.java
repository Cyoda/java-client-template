package com.java_template.common.workflow;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.java_template.common.config.CyodaObjectMapper;
import io.cloudevents.v1.proto.CloudEvent;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** ABOUTME: An unknown CloudEvent authtype is warned about once per distinct value, then logged at DEBUG. */
class CyodaContextFactoryTest {

    private final CyodaContextFactory factory = new CyodaContextFactory(CyodaObjectMapper.standalone());
    private final Logger logger = (Logger) LoggerFactory.getLogger(CyodaContextFactory.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void capture() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void release() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
    }

    private static CloudEvent eventWithAuthType(String authType) {
        return CloudEvent.newBuilder()
                .setId("evt-" + authType)
                .setTextData("{\"id\":\"e\",\"requestId\":\"r\",\"entityId\":\"00000000-0000-0000-0000-000000000001\"}")
                .putAttributes("authtype", CloudEvent.CloudEventAttributeValue.newBuilder().setCeString(authType).build())
                .build();
    }

    private List<ILoggingEvent> unknownAuthTypeLogs(Level level) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == level && e.getFormattedMessage().contains("unknown authtype"))
                .toList();
    }

    @Test
    void warnsOncePerDistinctUnknownAuthTypeThenLogsAtDebug() throws Exception {
        String first = "weird-" + System.nanoTime();
        String second = "other-" + System.nanoTime();

        factory.createCyodaEventContext(eventWithAuthType(first), EntityProcessorCalculationRequest.class);
        factory.createCyodaEventContext(eventWithAuthType(first), EntityProcessorCalculationRequest.class);
        factory.createCyodaEventContext(eventWithAuthType(first), EntityProcessorCalculationRequest.class);
        factory.createCyodaEventContext(eventWithAuthType(second), EntityProcessorCalculationRequest.class);

        assertThat(unknownAuthTypeLogs(Level.WARN)).hasSize(2)
                .anySatisfy(e -> assertThat(e.getFormattedMessage()).contains(first))
                .anySatisfy(e -> assertThat(e.getFormattedMessage()).contains(second));
        assertThat(unknownAuthTypeLogs(Level.DEBUG)).hasSize(2)
                .allSatisfy(e -> assertThat(e.getFormattedMessage()).contains(first));
    }

    @Test
    void aKnownAuthTypeIsNotLogged() throws Exception {
        factory.createCyodaEventContext(eventWithAuthType("service"), EntityProcessorCalculationRequest.class);

        assertThat(unknownAuthTypeLogs(Level.WARN)).isEmpty();
        assertThat(unknownAuthTypeLogs(Level.DEBUG)).isEmpty();
    }
}
