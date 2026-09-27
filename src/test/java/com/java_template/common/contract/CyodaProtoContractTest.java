package com.java_template.common.contract;

import com.google.protobuf.Descriptors;
import io.cloudevents.v1.proto.CloudEvent;
import io.cloudevents.v1.proto.Spec;
import io.grpc.MethodDescriptor;
import org.cyoda.cloud.api.grpc.CloudEventsServiceGrpc;
import org.cyoda.cloud.api.grpc.CyodaCloudApi;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CyodaProtoContractTest {

    @Test
    void everyRpcUsesTheCloudEventsLibraryMessage() {
        Descriptors.ServiceDescriptor service = CyodaCloudApi.getDescriptor().findServiceByName("CloudEventsService");

        assertThat(service.getMethods()).hasSize(6);
        service.getMethods().forEach(m -> {
            assertThat(m.getInputType()).isSameAs(CloudEvent.getDescriptor());
            assertThat(m.getOutputType()).isSameAs(CloudEvent.getDescriptor());
        });
        assertThat(CyodaCloudApi.getDescriptor().getDependencies()).contains(Spec.getDescriptor());
    }

    @Test
    void cloudEventClassComesFromTheLibraryJar() {
        MethodDescriptor<CloudEvent, CloudEvent> manage = CloudEventsServiceGrpc.getEntityManageMethod();

        assertThat(manage.getFullMethodName()).isEqualTo("org.cyoda.cloud.api.grpc.CloudEventsService/entityManage");
        assertThat(CloudEvent.class.getProtectionDomain().getCodeSource().getLocation().toString())
                .contains("cloudevents-protobuf");
    }
}
