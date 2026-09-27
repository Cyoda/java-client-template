package com.cyoda.build;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtoOptionInjectorTest {

    private static final String PROTO = """
            syntax = "proto3";

            package io.cloudevents.v1;

            import "google/protobuf/any.proto";

            option go_package = "github.com/cyoda-platform/cyoda-go/api/grpc/cloudevents";

            message CloudEvent { string id = 1; }
            """;

    @Test
    void insertsOptionsDirectlyAfterThePackageLine() {
        String out = ProtoOptionInjector.inject(PROTO, "cloudevents.proto", ProtoOptionInjector.CLOUDEVENTS_OPTIONS);

        assertThat(out).contains("""
                package io.cloudevents.v1;
                option java_multiple_files = true;
                option java_package = "io.cloudevents.v1.proto";
                option java_outer_classname = "Spec";
                """);
        assertThat(out).contains("option go_package");
    }

    @Test
    void keepsAnIdenticalExistingOptionWithoutDuplicatingIt() {
        String proto = PROTO.replace("message", "option java_multiple_files = true;\nmessage");
        String out = ProtoOptionInjector.inject(proto, "cloudevents.proto", ProtoOptionInjector.CLOUDEVENTS_OPTIONS);

        assertThat(out.split("option java_multiple_files", -1)).hasSize(2);
    }

    @Test
    void rejectsAConflictingJavaOption() {
        String proto = PROTO.replace("message", "option java_package = \"other.pkg\";\nmessage");

        assertThatThrownBy(() -> ProtoOptionInjector.inject(proto, "cloudevents.proto", ProtoOptionInjector.CLOUDEVENTS_OPTIONS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cloudevents.proto")
                .hasMessageContaining("java_package");
    }

    @Test
    void rejectsAJavaOptionTheBuildDoesNotManage() {
        String proto = PROTO.replace("message", "option java_generic_services = true;\nmessage");

        assertThatThrownBy(() -> ProtoOptionInjector.inject(proto, "x.proto", ProtoOptionInjector.CYODA_API_OPTIONS))
                .hasMessageContaining("java_generic_services");
    }

    @Test
    void rejectsAFileWithoutExactlyOnePackageLine() {
        assertThatThrownBy(() -> ProtoOptionInjector.inject("syntax = \"proto3\";", "x.proto", Map.of()))
                .hasMessageContaining("no package declaration");
        Map<String, String> none = new LinkedHashMap<>();
        assertThatThrownBy(() -> ProtoOptionInjector.inject("package a;\npackage b;\n", "x.proto", none))
                .hasMessageContaining("more than one package declaration");
    }
}
