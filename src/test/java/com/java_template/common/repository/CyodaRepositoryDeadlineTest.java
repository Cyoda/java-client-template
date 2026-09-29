package com.java_template.common.repository;

import com.java_template.common.config.Config;
import com.java_template.common.config.CyodaObjectMapper;
import com.java_template.common.grpc.client.event_handling.CloudEventBuilder;
import com.java_template.common.grpc.client.event_handling.CloudEventParser;
import io.cloudevents.v1.proto.CloudEvent;
import org.cyoda.cloud.api.common.model.GroupConditionDto;
import org.cyoda.cloud.api.event.common.BaseEvent;
import org.cyoda.cloud.api.event.common.DataPayload;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.cyoda.cloud.api.event.search.EntityResponse;
import org.cyoda.cloud.api.grpc.CloudEventsServiceGrpc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ABOUTME: grpc-call-deadline-ms bounds every unary Cyoda call (spec §4.5) and never the server-streaming
 * entityManageCollection/entitySearchCollection calls, whose duration grows with the result size.
 */
@ExtendWith(MockitoExtension.class)
class CyodaRepositoryDeadlineTest {

    @Mock CloudEventsServiceGrpc.CloudEventsServiceBlockingStub stub;
    @Mock CloudEventsServiceGrpc.CloudEventsServiceBlockingStub deadlineStub;
    @Mock CloudEventBuilder cloudEventBuilder;
    @Mock CloudEventParser cloudEventParser;
    @Mock Config config;

    private CyodaRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        repository = new CyodaRepository(CyodaObjectMapper.standalone(), stub, cloudEventBuilder, cloudEventParser, config);
        lenient().when(config.getGrpcCallDeadlineMs()).thenReturn(1_234L);
        lenient().when(stub.withDeadlineAfter(anyLong(), any())).thenReturn(deadlineStub);
        lenient().when(cloudEventBuilder.buildEvent(any(BaseEvent.class))).thenReturn(CloudEvent.getDefaultInstance());
    }

    @Test
    void aUnaryCallCarriesTheConfiguredDeadline() {
        when(deadlineStub.entitySearch(any())).thenReturn(CloudEvent.getDefaultInstance());
        when(cloudEventParser.parseCloudEvent(any(), eq(EntityResponse.class)))
                .thenReturn(new EntityResponse().withPayload(new DataPayload()));

        repository.findById(UUID.randomUUID()).join();

        verify(stub).withDeadlineAfter(1_234L, TimeUnit.MILLISECONDS);
        verify(deadlineStub).entitySearch(any());
        verify(stub, never()).entitySearch(any());
    }

    @Test
    void aServerStreamingCallHasNoDeadline() {
        when(stub.entitySearchCollection(any())).thenReturn(Collections.emptyIterator());
        GroupConditionDto all = new GroupConditionDto().operator(GroupConditionDto.OperatorEnum.AND).conditions(List.of());

        repository.findAllByCriteria(new ModelSpec().withName("thing").withVersion(1), all,
                SearchAndRetrievalParams.builder().inMemory(true).build()).join();

        verify(stub).entitySearchCollection(any());
        verify(stub, never()).withDeadlineAfter(anyLong(), any());
        verify(deadlineStub, never()).entitySearchCollection(any());
    }
}
