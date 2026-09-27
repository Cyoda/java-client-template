# cyoda-go v0.9.0 Alignment (Sub-project 1a) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Java client template implement cyoda-go v0.9.0's contract (vendored proto/event-schema/OpenAPI files, M2M compute with transaction-joined callbacks, no OBO) and prove it with a tier-1 integration suite that launches an in-memory cyoda-go subprocess.

**Architecture:** cyoda-go's contract files are vendored verbatim under `src/main/resources/cyoda/`. Small, unit-tested transforms in `buildSrc` produce generator-ready copies in `build/`. Runtime changes happen in two stages:
- **PR 1:** removes OBO and makes configuration and auth mode explicit.
- **PR 2:** routes every Cyoda call through one explicit per-call context (credential + tx-token), and opens a `CalloutScope` around each processor or criterion.

A `testFixtures` harness starts cyoda-go on free ports and wires Spring tests to it.

**Tech Stack:** Java 21, Spring Boot 3.5.3, Gradle 8.7, gRPC 1.73.0, protobuf-java 4.31.1, jsonschema2pojo 1.2.1, openapi-generator 7.12.0, JUnit 5, AssertJ, Jackson 2.19, cyoda-go (Go ≥ 1.26.7 for source builds).

**Spec:** `docs/superpowers/specs/2026-09-26-cyoda-go-alignment-design.md`. Read it first; section references (§n) below point into it.

**Delivery:** one plan, two PRs.
- **Part A (Tasks 1–17)** is PR 1.
- **Part B (Tasks 18–27)** is PR 2.

Open PR 1 after Task 17, and PR 2 after Task 27.

## Spec clarifications taken by this plan

These resolve details the spec left to implementation. Reviewers should hold tasks to them.

1. **Processor `type` default (§3.3.3, §8.5).** The plan goes straight to the documented fallback:
   - a Jackson `DeserializationProblemHandler`, registered by `CyodaJackson.configure(ObjectMapper)`;
   - it resolves a missing or blank processor `type` to `ExternalizedProcessorDefinitionDto`.

   No openapi-generator template fork is made.
2. **`ModelAndWorkflowSetupIT` isolation (§6.1).** `CyodaInit` derives model names from entity classes, so they cannot be made unique per test.
   - The test runs `CyodaInit` with `recreateModels=true` against the example application's entities.
   - No other integration test touches those models.
3. **Reads inside a callout scope (§4.4).** Inside an open `CalloutScope`, `EntityService.create`/`update`/`updateByBusinessId` reload the entity with `getById(id)` **without** `pointInTime`. The joined transaction's latest view is the correct one, and a point-in-time read inside an open transaction is not part of the contract.
4. **`CrudRepository` signatures (§4.8).** Every `CrudRepository` method gains a leading `CyodaCallContext ctx` parameter. `EntityServiceImpl` builds the context once per public call and passes it down.
5. **`SearchIT` condition types (§6.2).** Function conditions are excluded:
   - they cannot be expressed through `EntityService.search`, since the nested-condition union has four members;
   - cyoda-go rejects them in search (`docs/cloud-parity/function-condition-search-rejection.md`).

   `SearchIT` covers simple, group, lifecycle and array.
6. **Harness shutdown (§6.1).** Servers stop at the end of the test engine run, before Spring's cached contexts close. A cached context's member may log one connection loss in that window. This is documented in `CyodaServerExtension` and accepted; the framework's logging is not changed for it.

## Global Constraints

- Java toolchain 21; Spring Boot `3.5.3`; Gradle wrapper `8.7`.
- `protobuf-java` `4.31.1`, `protoc` `4.31.1`, `grpc-*` `1.73.0`, `protoc-gen-grpc-java` `1.73.0`.
- jsonschema2pojo `1.2.1`; openapi-generator `7.12.0`.
- Contract files live only in `src/main/resources/cyoda/`. They are never edited by hand; `scripts/sync-cyoda-contract.sh` refreshes them.
- Generated packages:
  - OpenAPI DTOs: `org.cyoda.cloud.api.common.model`;
  - event classes: `org.cyoda.cloud.api.event.*`;
  - gRPC: `org.cyoda.cloud.api.grpc`;
  - CloudEvent: the `cloudevents-protobuf` library's `io.cloudevents.v1.proto.CloudEvent`.
- Patches for open cyoda-go defects (#625, #627) fail the build with `cyoda-go #<n> is fixed upstream: remove patch <name>` once the defect is gone.
- The template implements cyoda-go's contract only. No code tolerates Cyoda Cloud differences.
- Never modify cyoda-go. File issues in `Cyoda/cyoda-go` instead.
- `authclaims` is comma-separated only. Anything else yields no roles.
- tx-token names:
  - CloudEvent attribute `cyodatxtoken`;
  - gRPC metadata `tx-token`;
  - HTTP header `X-Tx-Token`.
- Configuration values:

  | Key | Value |
  |---|---|
  | `app.config.keep-alive-warning-threshold` | `20000` |
  | `app.config.grpc-call-deadline-ms` | `120000` |
  | `app.config.execution-mode` | default `virtual` |
  | `app.config.auth-mode` | `client-credentials` (default) or `none` |
  | local profile `server.port` | `8081` |

- Joined-call retries apply to `TOO_MANY_JOINED_REQUESTS` and `TRANSACTION_NODE_UNAVAILABLE` only: at most 3 retries, with backoff 50, 100 and 200 ms.
- Direct-search limit inside a callout scope: `10000`.
- The cyoda binary is only ever run with `--version` or with no arguments. An unrecognised argument starts a server (cyoda-go #623).
- The tier-1 cyoda server environment is built from scratch. It has explicit values for every relevant `CYODA_*` variable, and `HOME`/`XDG_CONFIG_HOME` point at a temp directory.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

These are the inputs most likely to bite a user that no feature test covers directly. Each one has a pinning test in its owning task.

1. **No cyoda binary, or a binary at the wrong version, on a developer machine.** `./gradlew check` must fail fast with a message that names the pinned version and gives the exact `scripts/install-cyoda.sh` command. It must not hang or show a Spring stack trace. Pinned in Task 10.
2. **Downstream workflow JSON whose processors omit `type` or send `"type": ""`.** It must deserialise as an externalized processor, as cyoda-go does. Pinned in Task 5.
3. **`auth-mode=client-credentials` with a blank client id or secret.** Startup must fail with one message naming both properties. It must not fail later on the first gRPC call. Pinned in Task 7.
4. **A processor that calls `EntityService` from a thread it started itself (no `CalloutScope.wrap`).** The call must go out as M2M with no tx-token, and must not throw or leak another request's token. Pinned in Task 19.
5. **A paged read inside a callout scope that asks for more than 10 000 entities, or for page > 0.** It must throw `IllegalStateException` with a clear message. It must not silently return a detached snapshot that misses the cascade's writes. Pinned in Task 21.

## File Structure

**Build**
- `buildSrc/build.gradle`: build logic module (Jackson, Jackson YAML, JUnit).
- `buildSrc/src/main/java/com/cyoda/build/ProtoOptionInjector.java`: inserts Java options after the `package` line; rejects conflicting options.
- `buildSrc/src/main/java/com/cyoda/build/PrepareCyodaProtoTask.java`: copies the protos into `build/cyoda-proto/{main,include}` and injects options.
- `buildSrc/src/main/java/com/cyoda/build/EventSchemaTransformer.java`: `allOf`→`extends`, typeless objects→`JsonNode`, shared `OrderBy` type.
- `buildSrc/src/main/java/com/cyoda/build/PrepareEventSchemasTask.java`: copies the event-schema tree into `build/cyoda-schema`, transformed.
- `buildSrc/src/main/java/com/cyoda/build/OpenApiPatcher.java`: the #625 and #627 patches, fail-once-fixed.
- `buildSrc/src/main/java/com/cyoda/build/FixedUpstreamException.java`
- `buildSrc/src/main/java/com/cyoda/build/PrepareOpenApiTask.java`
- `build.gradle`: codegen wiring, `testFixtures`, `integrationTest`, Cucumber removal.
- `scripts/sync-cyoda-contract.sh`, `scripts/install-cyoda.sh`
- `.github/workflows/build.yml`

**Contract (vendored):** `src/main/resources/cyoda/{CYODA_VERSION,proto/,schema/,openapi/openapi.yaml}`

**Framework (`src/main/java/com/java_template/common/`)**
- `config/Config.java`: new keys; cyoda-light removed.
- `config/CyodaJackson.java`: the processor-type problem handler.
- `config/CyodaJacksonAutoConfiguration.java`: applies `CyodaJackson` to Spring's `ObjectMapper`.
- `config/GrpcClientAutoConfiguration.java`
- `auth/CyodaTokenSource.java`: M2M token abstraction.
- `auth/Authentication.java`: the M2M token source.
- `auth/NoCyodaAuthentication.java`
- `auth/CloudEventAuthContext.java`, `auth/CloudEventAuthContextExtractor.java`
- `call/CyodaCallContext.java`, `call/CyodaCallContexts.java`, `call/CalloutScope.java`, `call/CyodaCallInterceptor.java` (PR 2)
- `exception/CyodaErrors.java` and the new exception classes (PR 2)
- `repository/CrudRepository.java`, `repository/CyodaRepository.java`
- `service/EntityServiceImpl.java`, `service/WorkflowService*.java`, `service/EdgeMessageServiceImpl.java`
- `util/HttpUtils.java`, `tool/CyodaInit.java`
- `grpc/client/event_handling/AbstractEventStrategy.java` and the two strategies
- `serializer/ResponseBuilder.java`
- `workflow/CyodaEventContext.java`, `workflow/CyodaContextFactory.java`
- `grpc/client/*ThreadExecutor.java`

**Harness (`src/testFixtures/java/com/java_template/testing/cyoda/`)**
- `CyodaVersion`, `CyodaBinary`, `Profile`, `CyodaProfiles`, `FreePorts`, `CyodaServer`, `CyodaTestEnvironment`
- `CyodaServerExtension`, `CyodaIntegrationTest`, `CyodaContextCustomizerFactory`, `CyodaRest`, `WorkflowTemplating`, `CyodaModelSetup`, `CyodaAwait`
- `src/testFixtures/resources/META-INF/spring.factories`

**Integration tests (`src/integrationTest/java/com/java_template/it/`)**
- `support/`: `ItThing`, `ItRecordingProcessor`, `ItFailingProcessor`, `ItFlagCriterion`, `ItCascadeProcessor`, `ItAuthProbeProcessor`, `ExampleAppScan`
- `*IT.java`, one per spec §6.2 row
- `src/integrationTest/resources/it-workflows/thing-workflow.json`

---

# Part A: PR 1 (contract, configuration, OBO removal, harness)

### Task 1: Vendor the cyoda-go contract with a sync script

**Files:**
- Create: `scripts/sync-cyoda-contract.sh`
- Create (generated by the script): `src/main/resources/cyoda/CYODA_VERSION`, `src/main/resources/cyoda/proto/**`, `src/main/resources/cyoda/schema/**`, `src/main/resources/cyoda/openapi/openapi.yaml`

**Interfaces:**
- Produces:
  - the directory layout of spec §3.1;
  - `CYODA_VERSION`, whose line 1 is `<x.y.z[-dev]>` and line 2 is `commit=<40-hex sha>`.

- [ ] **Step 1: Write the script**

```bash
#!/usr/bin/env bash
# Vendors cyoda-go's contract files into src/main/resources/cyoda/, unmodified.
# Spec: docs/superpowers/specs/2026-09-26-cyoda-go-alignment-design.md §3.2
set -euo pipefail

usage() {
  echo "usage: $0 --from-src <cyoda-go checkout> --version <x.y.z[-dev]>" >&2
  exit 2
}

SRC=""
VERSION=""
while [ $# -gt 0 ]; do
  case "$1" in
    --from-src) SRC="${2:-}"; shift 2 ;;
    --version)  VERSION="${2:-}"; shift 2 ;;
    *) usage ;;
  esac
done
[ -n "$SRC" ] && [ -n "$VERSION" ] || usage
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-dev)?$ ]] || { echo "invalid --version '$VERSION'" >&2; exit 2; }
for f in api/openapi.yaml proto/cyoda/cyoda-cloud-api.proto proto/cloudevents/cloudevents.proto docs/cyoda/schema; do
  [ -e "$SRC/$f" ] || { echo "$SRC is not a cyoda-go checkout (missing $f)" >&2; exit 1; }
done

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/src/main/resources/cyoda"
COMMIT="$(git -C "$SRC" rev-parse HEAD)"

rm -rf "$DEST/proto" "$DEST/schema" "$DEST/openapi"
mkdir -p "$DEST/proto/cyoda" "$DEST/proto/cloudevents" "$DEST/schema" "$DEST/openapi"
cp "$SRC/proto/cyoda/cyoda-cloud-api.proto" "$DEST/proto/cyoda/"
cp "$SRC/proto/cloudevents/cloudevents.proto" "$DEST/proto/cloudevents/"
(
  cd "$SRC/docs/cyoda/schema"
  find . -name '*.json' -print0 | while IFS= read -r -d '' f; do
    mkdir -p "$DEST/schema/$(dirname "$f")"
    cp "$f" "$DEST/schema/$f"
  done
)
cp "$SRC/api/openapi.yaml" "$DEST/openapi/openapi.yaml"
printf '%s\ncommit=%s\n' "$VERSION" "$COMMIT" > "$DEST/CYODA_VERSION"
echo "synced cyoda-go $VERSION ($COMMIT) into $DEST"
```

- [ ] **Step 2: Make it executable and run it against the local checkout**

```bash
chmod +x scripts/sync-cyoda-contract.sh
scripts/sync-cyoda-contract.sh --from-src /Users/paul/go-projects/cyoda-light/cyoda-go --version 0.9.0-dev
```

Expected: `synced cyoda-go 0.9.0-dev (<sha>) into …/src/main/resources/cyoda`.

- [ ] **Step 3: Verify the layout, the absence of `.go` files, and idempotence**

```bash
test -f src/main/resources/cyoda/proto/cyoda/cyoda-cloud-api.proto
test -f src/main/resources/cyoda/proto/cloudevents/cloudevents.proto
test -f src/main/resources/cyoda/openapi/openapi.yaml
test -f src/main/resources/cyoda/schema/common/BaseEvent.json
test -f src/main/resources/cyoda/schema/common/statemachine/WorkflowInfo.json
test -z "$(find src/main/resources/cyoda -name '*.go')"
sed -n 2p src/main/resources/cyoda/CYODA_VERSION | grep -E '^commit=[0-9a-f]{40}$'
git add -A src/main/resources/cyoda && scripts/sync-cyoda-contract.sh --from-src /Users/paul/go-projects/cyoda-light/cyoda-go --version 0.9.0-dev && git diff --exit-code src/main/resources/cyoda
```

Expected: every command exits 0. The final `git diff --exit-code` prints nothing.

- [ ] **Step 4: Check that the script rejects bad input**

```bash
scripts/sync-cyoda-contract.sh --from-src /tmp --version 0.9.0; echo "exit=$?"
scripts/sync-cyoda-contract.sh --from-src /Users/paul/go-projects/cyoda-light/cyoda-go --version v0.9; echo "exit=$?"
```

Expected:
- the first prints `/tmp is not a cyoda-go checkout (missing api/openapi.yaml)` and `exit=1`;
- the second prints `invalid --version 'v0.9'` and `exit=2`.

- [ ] **Step 5: Commit**

```bash
git add scripts/sync-cyoda-contract.sh src/main/resources/cyoda
git commit -m "build: vendor cyoda-go contract files with a sync script

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: buildSrc module and protobuf generation from the vendored protos

**Files:**
- Create: `buildSrc/build.gradle`
- Create: `buildSrc/src/main/java/com/cyoda/build/ProtoOptionInjector.java`
- Create: `buildSrc/src/main/java/com/cyoda/build/PrepareCyodaProtoTask.java`
- Test: `buildSrc/src/test/java/com/cyoda/build/ProtoOptionInjectorTest.java`
- Modify: `build.gradle` (the `sourceSets.main.proto` block, the `protobuf { }` block, and the dependencies)
- Delete: `src/main/resources/proto/`
- Test: `src/test/java/com/java_template/common/contract/CyodaProtoContractTest.java`

**Interfaces:**
- Produces:
  - `ProtoOptionInjector.inject(String proto, String label, Map<String,String> options): String`
  - Gradle task `prepareCyodaProto`, with outputs `build/cyoda-proto/main/cyoda/cyoda-cloud-api.proto` and `build/cyoda-proto/include/cloudevents/cloudevents.proto`
  - generated classes `org.cyoda.cloud.api.grpc.CloudEventsServiceGrpc` and `org.cyoda.cloud.api.grpc.CyodaCloudApi`

- [ ] **Step 1: Create `buildSrc/build.gradle`**

```groovy
plugins {
    id 'java'
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation gradleApi()
    implementation 'com.fasterxml.jackson.core:jackson-databind:2.19.1'
    implementation 'com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.19.1'
    testImplementation platform('org.junit:junit-bom:5.13.1')
    testImplementation 'org.junit.jupiter:junit-jupiter'
    testImplementation 'org.assertj:assertj-core:3.27.3'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

test {
    useJUnitPlatform()
}
```

- [ ] **Step 2: Write the failing test**

```java
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
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./gradlew -p buildSrc test --tests com.cyoda.build.ProtoOptionInjectorTest`
Expected: compilation FAILS with `cannot find symbol: class ProtoOptionInjector`.

- [ ] **Step 4: Implement `ProtoOptionInjector`**

```java
package com.cyoda.build;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inserts Java generator options into a vendored cyoda-go .proto file (which declares only
 * go_package). Fails loudly when the file already declares a Java option this build does
 * not manage or declares one with a different value, so contract drift is never silent.
 */
public final class ProtoOptionInjector {

    public static final Map<String, String> CYODA_API_OPTIONS = Map.of("java_multiple_files", "true");

    /** Matches the cloudevents-protobuf library, whose CloudEvent comes from spec.proto (outer class Spec). */
    public static final Map<String, String> CLOUDEVENTS_OPTIONS = Map.of(
            "java_multiple_files", "true",
            "java_package", "\"io.cloudevents.v1.proto\"",
            "java_outer_classname", "\"Spec\"");

    /** Emission order for injected options; any other managed option follows alphabetically. */
    private static final List<String> CANONICAL_ORDER = List.of("java_multiple_files", "java_package", "java_outer_classname");

    private static final Pattern PACKAGE_LINE = Pattern.compile("(?m)^package\\s+[A-Za-z0-9_.]+\\s*;[ \\t]*$");
    private static final Pattern JAVA_OPTION = Pattern.compile("(?m)^\\s*option\\s+(java_[a-z_]+)\\s*=\\s*([^;]+);");

    private ProtoOptionInjector() {
    }

    public static String inject(String proto, String label, Map<String, String> options) {
        Map<String, String> existing = new HashMap<>();
        Matcher option = JAVA_OPTION.matcher(proto);
        while (option.find()) {
            existing.put(option.group(1), option.group(2).trim());
        }
        existing.forEach((name, value) -> {
            String wanted = options.get(name);
            if (wanted == null || !wanted.equals(value)) {
                throw new IllegalStateException(label + " declares option " + name + " = " + value
                        + ", which conflicts with the Java options this build injects; update ProtoOptionInjector");
            }
        });

        Matcher pkg = PACKAGE_LINE.matcher(proto);
        if (!pkg.find()) {
            throw new IllegalStateException(label + ": no package declaration");
        }
        int insertAt = pkg.end();
        if (pkg.find()) {
            throw new IllegalStateException(label + ": more than one package declaration");
        }

        List<String> order = new ArrayList<>(CANONICAL_ORDER);
        new TreeSet<>(options.keySet()).stream().filter(k -> !order.contains(k)).forEach(order::add);
        StringBuilder inserted = new StringBuilder();
        for (String name : order) {
            String value = options.get(name);
            if (value != null && !existing.containsKey(name)) {
                inserted.append("\noption ").append(name).append(" = ").append(value).append(';');
            }
        }
        return proto.substring(0, insertAt) + inserted + proto.substring(insertAt);
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew -p buildSrc test --tests com.cyoda.build.ProtoOptionInjectorTest`
Expected: PASS (5 tests).

- [ ] **Step 6: Implement `PrepareCyodaProtoTask`**

```java
package com.cyoda.build;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Copies the vendored protos into build/cyoda-proto/{main,include} with Java options injected (spec §3.3.1). */
public abstract class PrepareCyodaProtoTask extends DefaultTask {

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getSourceDir();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @TaskAction
    public void prepare() throws IOException {
        Path src = getSourceDir().get().getAsFile().toPath();
        Path out = getOutputDir().get().getAsFile().toPath();
        getProject().delete(out.toFile());

        Path api = src.resolve("cyoda/cyoda-cloud-api.proto");
        Path ce = src.resolve("cloudevents/cloudevents.proto");
        write(out.resolve("main/cyoda/cyoda-cloud-api.proto"),
                ProtoOptionInjector.inject(Files.readString(api), api.toString(), ProtoOptionInjector.CYODA_API_OPTIONS));
        write(out.resolve("include/cloudevents/cloudevents.proto"),
                ProtoOptionInjector.inject(Files.readString(ce), ce.toString(), ProtoOptionInjector.CLOUDEVENTS_OPTIONS));
    }

    private static void write(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }
}
```

- [ ] **Step 7: Write the failing app-level contract test**

```java
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
```

- [ ] **Step 8: Wire the build and delete the old protos**

In `build.gradle`:

1. **`sourceSets.main`:** replace `proto { srcDir 'src/main/resources/proto' }` with:

```groovy
        proto {
            srcDir layout.buildDirectory.dir('cyoda-proto/main')
        }
```

2. **Protobuf block:** replace the whole `protobuf { … }` block with:

```groovy
tasks.register('prepareCyodaProto', com.cyoda.build.PrepareCyodaProtoTask) {
    sourceDir = file('src/main/resources/cyoda/proto')
    outputDir = layout.buildDirectory.dir('cyoda-proto')
}

protobuf {
    protoc {
        artifact = 'com.google.protobuf:protoc:4.31.1'
    }
    plugins {
        grpc {
            artifact = 'io.grpc:protoc-gen-grpc-java:1.73.0'
        }
    }
    generateProtoTasks {
        all().each { task ->
            task.dependsOn 'prepareCyodaProto'
            task.plugins { grpc {} }
        }
    }
}

tasks.matching { it.name == 'extractIncludeProto' }.configureEach { dependsOn 'prepareCyodaProto' }
```

3. **Dependencies:** add inside `dependencies { }`:

```groovy
    // cloudevents.proto is import-only; io.cloudevents.v1.proto.CloudEvent comes from cloudevents-protobuf (spec §3.3.1)
    compileProtoPath files(layout.buildDirectory.dir('cyoda-proto/include')) {
        builtBy 'prepareCyodaProto'
    }
```

Then delete the old protos:

```bash
git rm -r src/main/resources/proto
```

- [ ] **Step 9: Build and run the contract test**

Run: `./gradlew clean generateProto compileJava test --tests com.java_template.common.contract.CyodaProtoContractTest`
Expected: BUILD SUCCESSFUL; 2 tests pass. `build/generated/source/proto/main/java/io/cloudevents` must NOT exist. Check it with:

```bash
test ! -d build/generated/source/proto/main/java/io/cloudevents && echo "no duplicate CloudEvent"
```

- [ ] **Step 10: Run the full unit suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. Nothing in the framework referenced `CloudEventBatch` or the `Cloudevents` outer class. If a compile error names one of them, replace the reference with `io.cloudevents.v1.proto.CloudEvent`.

- [ ] **Step 11: Commit**

```bash
git add buildSrc build.gradle src/test/java/com/java_template/common/contract/CyodaProtoContractTest.java
git commit -m "build: generate gRPC stubs from vendored cyoda-go protos

Java options are injected into a build copy; cloudevents.proto is import-only and
CloudEvent comes from cloudevents-protobuf. protoc aligned with protobuf-java 4.31.1.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Event-schema transform and jsonschema2pojo from the vendored tree

**Files:**
- Create: `buildSrc/src/main/java/com/cyoda/build/EventSchemaTransformer.java`
- Create: `buildSrc/src/main/java/com/cyoda/build/PrepareEventSchemasTask.java`
- Test: `buildSrc/src/test/java/com/cyoda/build/EventSchemaTransformerTest.java`
- Modify: `build.gradle` (the `jsonSchema2Pojo` block)
- Delete: `src/main/resources/schema/`
- Test: `src/test/java/com/java_template/common/contract/GeneratedEventTypesTest.java`

**Interfaces:**
- Produces:
  - `EventSchemaTransformer.transform(ObjectNode schema, String label): ObjectNode` (mutates and returns its argument);
  - Gradle task `prepareEventSchemas`, output `build/cyoda-schema/**`;
  - generated `org.cyoda.cloud.api.event.search.OrderBy` (a single class).

- [ ] **Step 1: Write the failing transformer tests**

```java
package com.cyoda.build;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventSchemaTransformerTest {

    private final ObjectMapper om = new ObjectMapper();

    private ObjectNode json(String s) throws Exception {
        return (ObjectNode) om.readTree(s);
    }

    @Test
    void rewritesTheSingleBaseEventAllOfToExtends() throws Exception {
        ObjectNode schema = json("""
                {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
                 "allOf":[{"$ref":"../common/BaseEvent.json"}],
                 "properties":{"matches":{"type":"boolean"}}}""");

        EventSchemaTransformer.transform(schema, "EntityCriteriaCalculationResponse.json");

        assertThat(schema.has("allOf")).isFalse();
        assertThat(schema.at("/extends/$ref").asText()).isEqualTo("../common/BaseEvent.json");
    }

    @Test
    void rejectsAnyOtherAllOf() throws Exception {
        ObjectNode twoRefs = json("""
                {"allOf":[{"$ref":"../common/BaseEvent.json"},{"$ref":"X.json"}]}""");
        ObjectNode nested = json("""
                {"properties":{"p":{"allOf":[{"$ref":"X.json"}]}}}""");

        assertThatThrownBy(() -> EventSchemaTransformer.transform(twoRefs, "A.json"))
                .hasMessageContaining("A.json").hasMessageContaining("allOf");
        assertThatThrownBy(() -> EventSchemaTransformer.transform(nested, "B.json"))
                .hasMessageContaining("B.json").hasMessageContaining("/properties/p");
    }

    @Test
    void typelessObjectPropertiesBecomeJsonNode() throws Exception {
        ObjectNode schema = json("""
                {"type":"object","properties":{
                   "result":{"type":"object","description":"Function calculation result."},
                   "typed":{"type":"object","properties":{"a":{"type":"string"}}},
                   "map":{"type":"object","additionalProperties":{"type":"string"}},
                   "keep":{"type":"object","existingJavaType":"java.util.Map<String,Object>"},
                   "list":{"type":"array","items":{"type":"object"}}}}""");

        EventSchemaTransformer.transform(schema, "R.json");

        assertThat(schema.at("/properties/result/existingJavaType").asText()).isEqualTo(EventSchemaTransformer.JSON_NODE);
        assertThat(schema.at("/properties/typed").has("existingJavaType")).isFalse();
        assertThat(schema.at("/properties/map").has("existingJavaType")).isFalse();
        assertThat(schema.at("/properties/keep/existingJavaType").asText()).isEqualTo("java.util.Map<String,Object>");
        assertThat(schema.at("/properties/list/items/existingJavaType").asText()).isEqualTo(EventSchemaTransformer.JSON_NODE);
    }

    @Test
    void orderByItemsShareOneJavaType() throws Exception {
        ObjectNode schema = json("""
                {"type":"object","properties":{"orderBy":{"type":"array","items":
                  {"type":"object","properties":{"path":{"type":"string"}}}}}}""");

        EventSchemaTransformer.transform(schema, "S.json");

        assertThat(schema.at("/properties/orderBy/items/javaType").asText()).isEqualTo(EventSchemaTransformer.ORDER_BY_JAVA_TYPE);
    }

    @Test
    void anOrderByOfAnotherShapeFails() throws Exception {
        ObjectNode schema = json("""
                {"type":"object","properties":{"orderBy":{"type":"string"}}}""");

        assertThatThrownBy(() -> EventSchemaTransformer.transform(schema, "S.json"))
                .hasMessageContaining("S.json").hasMessageContaining("orderBy");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew -p buildSrc test --tests com.cyoda.build.EventSchemaTransformerTest`
Expected: compilation FAILS: `cannot find symbol: class EventSchemaTransformer`.

- [ ] **Step 3: Implement `EventSchemaTransformer`**

```java
package com.cyoda.build;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

/**
 * Makes a cyoda-go event schema (JSON Schema 2020-12) consumable by jsonschema2pojo (spec §3.3.2).
 * Each rule asserts the input shape it expects and fails naming the file otherwise.
 */
public final class EventSchemaTransformer {

    public static final String JSON_NODE = "com.fasterxml.jackson.databind.JsonNode";
    public static final String ORDER_BY_JAVA_TYPE = "org.cyoda.cloud.api.event.search.OrderBy";

    private EventSchemaTransformer() {
    }

    public static ObjectNode transform(ObjectNode schema, String label) {
        rewriteBaseEventAllOf(schema, label);
        rejectAllOf(schema, label, "");
        walk(schema, label, "");
        return schema;
    }

    private static void rewriteBaseEventAllOf(ObjectNode root, String label) {
        JsonNode allOf = root.get("allOf");
        if (allOf == null) {
            return;
        }
        boolean expected = allOf instanceof ArrayNode array
                && array.size() == 1
                && array.get(0).isObject()
                && array.get(0).size() == 1
                && array.get(0).path("$ref").asText("").endsWith("BaseEvent.json");
        if (!expected) {
            throw new IllegalStateException(label + ": unexpected allOf " + allOf
                    + " (only a single $ref to BaseEvent.json is rewritten); update EventSchemaTransformer");
        }
        String ref = allOf.get(0).get("$ref").asText();
        root.remove("allOf");
        ObjectNode ext = JsonNodeFactory.instance.objectNode();
        ext.put("$ref", ref);
        root.set("extends", ext);
    }

    private static void rejectAllOf(JsonNode node, String label, String path) {
        if (node.isObject()) {
            if (node.has("allOf")) {
                throw new IllegalStateException(label + ": unexpected allOf at " + (path.isEmpty() ? "/" : path)
                        + "; update EventSchemaTransformer");
            }
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                rejectAllOf(e.getValue(), label, path + "/" + e.getKey());
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                rejectAllOf(node.get(i), label, path + "/" + i);
            }
        }
    }

    private static void walk(JsonNode node, String label, String path) {
        if (!node.isObject()) {
            if (node.isArray()) {
                for (int i = 0; i < node.size(); i++) {
                    walk(node.get(i), label, path + "/" + i);
                }
            }
            return;
        }
        ObjectNode obj = (ObjectNode) node;
        JsonNode props = obj.get("properties");
        if (props != null && props.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = props.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getKey().equals("orderBy")) {
                    nameOrderBy(e.getValue(), label, path + "/properties/orderBy");
                }
                injectJsonNodeIfTypeless(e.getValue());
                JsonNode items = e.getValue().get("items");
                if (items != null) {
                    injectJsonNodeIfTypeless(items);
                }
            }
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = obj.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            walk(e.getValue(), label, path + "/" + e.getKey());
        }
    }

    private static void injectJsonNodeIfTypeless(JsonNode node) {
        if (node instanceof ObjectNode o
                && "object".equals(o.path("type").asText())
                && !o.has("properties") && !o.has("additionalProperties") && !o.has("patternProperties")
                && !o.has("existingJavaType") && !o.has("javaType") && !o.has("$ref")) {
            o.put("existingJavaType", JSON_NODE);
        }
    }

    private static void nameOrderBy(JsonNode orderBy, String label, String path) {
        JsonNode items = orderBy.get("items");
        boolean expected = "array".equals(orderBy.path("type").asText())
                && items instanceof ObjectNode
                && "object".equals(items.path("type").asText())
                && items.has("properties");
        if (!expected) {
            throw new IllegalStateException(label + ": orderBy at " + path
                    + " is not an array of inline objects; update EventSchemaTransformer");
        }
        ((ObjectNode) items).put("javaType", ORDER_BY_JAVA_TYPE);
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew -p buildSrc test --tests com.cyoda.build.EventSchemaTransformerTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Implement `PrepareEventSchemasTask`**

It also asserts that every `orderBy` item schema is identical, because jsonschema2pojo generates a shared `javaType` only once.

```java
package com.cyoda.build;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Copies the vendored event-schema tree into build/cyoda-schema, transformed for jsonschema2pojo (spec §3.3.2). */
public abstract class PrepareEventSchemasTask extends DefaultTask {

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getSourceDir();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @TaskAction
    public void prepare() throws IOException {
        ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Path src = getSourceDir().get().getAsFile().toPath();
        Path out = getOutputDir().get().getAsFile().toPath();
        getProject().delete(out.toFile());

        List<JsonNode> orderByItems = new ArrayList<>();
        try (Stream<Path> files = Files.walk(src)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                String label = src.relativize(file).toString();
                ObjectNode schema = (ObjectNode) om.readTree(file.toFile());
                EventSchemaTransformer.transform(schema, label);
                schema.findParents("orderBy").forEach(parent -> {
                    JsonNode items = parent.get("orderBy").get("items");
                    if (items != null && items.has("javaType")) {
                        orderByItems.add(items);
                    }
                });
                Path target = out.resolve(label);
                Files.createDirectories(target.getParent());
                om.writeValue(target.toFile(), schema);
            }
        }
        for (JsonNode items : orderByItems) {
            if (!items.equals(orderByItems.getFirst())) {
                throw new IllegalStateException("orderBy item schemas differ between event schemas; "
                        + "a shared javaType would drop one of them. Update EventSchemaTransformer.");
            }
        }
    }
}
```

- [ ] **Step 6: Write the failing app-level test**

```java
package com.java_template.common.contract;

import com.fasterxml.jackson.databind.JsonNode;
import org.cyoda.cloud.api.event.common.BaseEvent;
import org.cyoda.cloud.api.event.entity.EntityPatchRequest;
import org.cyoda.cloud.api.event.processing.EntityCriteriaCalculationResponse;
import org.cyoda.cloud.api.event.processing.EntityFunctionCalculationResponse;
import org.cyoda.cloud.api.event.search.EntitySearchRequest;
import org.cyoda.cloud.api.event.search.EntitySnapshotSearchRequest;
import org.cyoda.cloud.api.event.search.OrderBy;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratedEventTypesTest {

    @Test
    void responsesStillExtendBaseEvent() {
        assertThat(BaseEvent.class).isAssignableFrom(EntityCriteriaCalculationResponse.class);
        assertThat(BaseEvent.class).isAssignableFrom(EntityPatchRequest.class);
    }

    @Test
    void functionResultIsAJsonNode() throws Exception {
        assertThat(EntityFunctionCalculationResponse.class.getMethod("getResult").getReturnType()).isEqualTo(JsonNode.class);
    }

    @Test
    void orderByIsOneSharedClass() throws Exception {
        for (Class<?> c : new Class<?>[]{EntitySearchRequest.class, EntitySnapshotSearchRequest.class}) {
            ParameterizedType t = (ParameterizedType) c.getMethod("getOrderBy").getGenericReturnType();
            assertThat(t.getActualTypeArguments()[0]).isEqualTo(OrderBy.class);
        }
    }
}
```

- [ ] **Step 7: Wire the build and delete the old schemas**

In `build.gradle`, replace the `jsonSchema2Pojo { … }` block with:

```groovy
tasks.register('prepareEventSchemas', com.cyoda.build.PrepareEventSchemasTask) {
    sourceDir = file('src/main/resources/cyoda/schema')
    outputDir = layout.buildDirectory.dir('cyoda-schema')
}

jsonSchema2Pojo {
    source = files(layout.buildDirectory.dir('cyoda-schema'))
    targetDirectory = file("$buildDir/generated-sources/js2p")
    targetPackage = 'org.cyoda.cloud.api.event'
    generateBuilders = true
    usePrimitives = false
    includeAdditionalProperties = false
    includeHashcodeAndEquals = true
    includeToString = true
    annotationStyle = 'jackson2'
    sourceType = 'jsonschema'
    removeOldOutput = true
    includeConstructors = false
    includeRequiredPropertiesConstructor = false
    includeAllPropertiesConstructor = true
}

tasks.named('generateJsonSchema2Pojo') { dependsOn 'prepareEventSchemas' }
```

Then delete the old schemas:

```bash
git rm -r src/main/resources/schema
```

- [ ] **Step 8: Build and run the tests**

Run: `./gradlew clean compileJava test --tests 'com.java_template.common.contract.*'`
Expected: BUILD SUCCESSFUL; `GeneratedEventTypesTest` has 3 passing tests.

If `compileJava` reports errors in `src/main/java/com/java_template/common/**`, they come from event classes that changed shape upstream. Fix each one at its call site against the newly generated class in `build/generated-sources/js2p`:
- Use the new getter or field name.
- Do not re-add a removed field. For example, `EntityDeleteAllRequest` no longer has `transactionSize`/`pageSize`, so drop those setter calls.

- [ ] **Step 9: Run the full unit suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Commit**

```bash
git add buildSrc build.gradle src/main/java src/test/java/com/java_template/common/contract/GeneratedEventTypesTest.java
git commit -m "build: generate event classes from cyoda-go's event-schema tree

allOf->extends keeps the BaseEvent hierarchy; typeless objects become JsonNode;
orderBy items share one OrderBy class.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: OpenAPI patches for cyoda-go #625 and #627 (fail once fixed)

**Files:**
- Create: `buildSrc/src/main/java/com/cyoda/build/FixedUpstreamException.java`
- Create: `buildSrc/src/main/java/com/cyoda/build/OpenApiPatcher.java`
- Create: `buildSrc/src/main/java/com/cyoda/build/PrepareOpenApiTask.java`
- Test: `buildSrc/src/test/java/com/cyoda/build/OpenApiPatcherTest.java`

**Interfaces:**
- Produces:
  - `OpenApiPatcher.patch(ObjectNode spec): ObjectNode`
  - `FixedUpstreamException extends IllegalStateException`
  - Gradle task type `PrepareOpenApiTask`, with `sourceFile` and `outputFile` properties

- [ ] **Step 1: Write the failing tests**

The minimal spec fixture mirrors the real shapes, and one test runs against the vendored file.

```java
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
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew -p buildSrc test --tests com.cyoda.build.OpenApiPatcherTest`
Expected: compilation FAILS: `cannot find symbol: class OpenApiPatcher`.

- [ ] **Step 3: Implement `FixedUpstreamException` and `OpenApiPatcher`**

```java
package com.cyoda.build;

/** A patch found the upstream defect it works around already fixed: the patch must be removed. */
public class FixedUpstreamException extends IllegalStateException {
    public FixedUpstreamException(String message) {
        super(message);
    }
}
```

```java
package com.cyoda.build;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Build-time patches for open defects in cyoda-go's api/openapi.yaml (spec §3.3.3).
 * Each patch asserts the defect is present and fails with FixedUpstreamException once it is not.
 */
public final class OpenApiPatcher {

    private static final String REF = "#/components/schemas/";
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private OpenApiPatcher() {
    }

    public static ObjectNode patch(ObjectNode spec) {
        ObjectNode schemas = object(spec.at("/components/schemas"), "components.schemas");
        discriminatorMappings(schemas);
        arrayConditionValues(schemas);
        return spec;
    }

    // ---- cyoda-go #625 -------------------------------------------------------------

    private static void discriminatorMappings(ObjectNode schemas) {
        Map<String, String> four = new LinkedHashMap<>();
        four.put("simple", "SimpleConditionDto");
        four.put("group", "GroupConditionDto");
        four.put("lifecycle", "LifecycleConditionDto");
        four.put("array", "ArrayConditionDto");
        Map<String, String> five = new LinkedHashMap<>(four);
        five.put("function", "FunctionConditionDto");
        Map<String, String> audit = new LinkedHashMap<>();
        audit.put("EntityChange", "EntityChangeAuditEventDto");
        audit.put("StateMachine", "StateMachineAuditEventDto");
        audit.put("System", "SystemAuditEventDto");

        record Named(String schema, String property, Map<String, String> mapping) {}
        record Inline(String pointer, Map<String, String> mapping) {}
        List<Named> named = List.of(
                new Named("QueryConditionDto", "type", Map.of("function", "FunctionConditionDto")),
                new Named("AbstractConditionDto", "type", four),
                new Named("AuditEventDto", "auditEventType", audit));
        List<Inline> inline = List.of(
                new Inline("/TransitionDefinitionDto/properties/criterion", five),
                new Inline("/WorkflowConfigurationDto/properties/criterion", five),
                new Inline("/GroupConditionDto/allOf/1/properties/conditions/items", four),
                new Inline("/GroupedStatsRequest/properties/condition", four));

        List<String> alreadyMapped = new ArrayList<>();
        for (Named n : named) {
            JsonNode disc = schemas.at("/" + n.schema() + "/discriminator");
            if (!n.property().equals(disc.path("propertyName").asText(null))) {
                throw shapeChanged("discriminatorMappings (#625)", n.schema() + ".discriminator.propertyName");
            }
            if (disc.has("mapping")) {
                alreadyMapped.add(n.schema());
            }
        }
        for (Inline i : inline) {
            JsonNode node = schemas.at(i.pointer());
            Set<String> refs = new LinkedHashSet<>();
            node.path("oneOf").forEach(r -> refs.add(r.path("$ref").asText()));
            Set<String> expected = new LinkedHashSet<>();
            i.mapping().values().forEach(v -> expected.add(REF + v));
            if (!refs.equals(expected)) {
                throw shapeChanged("discriminatorMappings (#625)", i.pointer() + ".oneOf = " + refs);
            }
            if (node.path("discriminator").has("mapping")) {
                alreadyMapped.add(i.pointer());
            }
        }
        if (!alreadyMapped.isEmpty()) {
            throw new FixedUpstreamException("cyoda-go #625 is fixed upstream (mapping present at " + alreadyMapped
                    + "): remove patch discriminatorMappings");
        }

        for (Named n : named) {
            ((ObjectNode) schemas.at("/" + n.schema() + "/discriminator")).set("mapping", mapping(n.mapping()));
        }
        for (Inline i : inline) {
            ObjectNode disc = ((ObjectNode) schemas.at(i.pointer())).putObject("discriminator");
            disc.put("propertyName", "type");
            disc.set("mapping", mapping(i.mapping()));
        }
    }

    private static ObjectNode mapping(Map<String, String> values) {
        ObjectNode m = F.objectNode();
        values.forEach((wire, schema) -> m.put(wire, REF + schema));
        return m;
    }

    // ---- cyoda-go #627 -------------------------------------------------------------

    private static void arrayConditionValues(ObjectNode schemas) {
        ObjectNode array = object(schemas.get("ArrayConditionDto"), "ArrayConditionDto");
        ObjectNode props = object(array.at("/allOf/1/properties"), "ArrayConditionDto.allOf[1].properties");
        boolean defective = props.has("operatorType") && props.has("value") && !props.has("values");
        if (!defective) {
            if (props.has("values") && !props.has("operatorType") && !props.has("value")) {
                throw new FixedUpstreamException("cyoda-go #627 is fixed upstream: remove patch arrayConditionValues");
            }
            throw shapeChanged("arrayConditionValues (#627)", "ArrayConditionDto properties " + props.fieldNames());
        }
        if (!schemas.has("JsonNode")) {
            throw shapeChanged("arrayConditionValues (#627)", "components.schemas.JsonNode is missing");
        }
        props.remove("operatorType");
        props.remove("value");
        ObjectNode values = props.putObject("values");
        values.put("type", "array");
        values.putObject("items").put("$ref", REF + "JsonNode");
        values.put("description", "Positional values, one per array index; a null entry skips that index.");

        ObjectNode owner = array.has("required") ? array : (ObjectNode) array.at("/allOf/1");
        ArrayNode required = F.arrayNode().add("jsonPath").add("type").add("values");
        owner.set("required", required);
    }

    private static ObjectNode object(JsonNode node, String what) {
        if (!(node instanceof ObjectNode o)) {
            throw new IllegalStateException("cyoda-go openapi.yaml changed shape: " + what + " is missing; review OpenApiPatcher");
        }
        return o;
    }

    private static IllegalStateException shapeChanged(String patch, String where) {
        return new IllegalStateException("cyoda-go openapi.yaml changed shape at " + where
                + ": review patch " + patch);
    }
}
```

- [ ] **Step 4: Implement `PrepareOpenApiTask`**

```java
package com.cyoda.build;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.file.Files;

/** Writes the patched copy of the vendored openapi.yaml that openapi-generator reads (spec §3.3.3). */
public abstract class PrepareOpenApiTask extends DefaultTask {

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getSourceFile();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void prepare() throws IOException {
        YAMLMapper yaml = new YAMLMapper();
        ObjectNode spec = (ObjectNode) yaml.readTree(getSourceFile().get().getAsFile());
        OpenApiPatcher.patch(spec);
        var out = getOutputFile().get().getAsFile().toPath();
        Files.createDirectories(out.getParent());
        yaml.writeValue(out.toFile(), spec);
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew -p buildSrc test`
Expected: PASS (all buildSrc tests, including `patchesTheVendoredSpec`).

- [ ] **Step 6: Commit**

```bash
git add buildSrc
git commit -m "build: OpenAPI patches for cyoda-go #625 and #627 that fail once fixed

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Generate DTOs from cyoda-go's single OpenAPI spec and adapt the framework

**Files:**
- Modify: `build.gradle` (replace the `openapiFiles` loop and `generateOpenApi` aggregate)
- Delete: `src/main/resources/api/`
- Create: `src/main/java/com/java_template/common/config/CyodaJackson.java`
- Create: `src/main/java/com/java_template/common/config/CyodaJacksonAutoConfiguration.java`
- Modify: `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Modify: `src/main/java/com/java_template/common/service/EntityServiceImpl.java` (condition building, lines ~88–100 and ~136–156)
- Modify: `src/main/java/com/java_template/common/repository/CyodaRepository.java:252-254`
- Modify: `src/main/java/com/java_template/common/controller/EntityCrudOperations.java` (lines ~373–455 and `FieldFilter` at ~639–653)
- Modify: `src/test/java/com/example/tests/WorkflowConfigurationMarshallingTest.java`, `src/test/java/com/java_template/common/service/EntityServiceImplTest.java`, `src/test/java/com/example/application/**` (imports and enum names)
- Create: `src/test/resources/contract/workflow-roundtrip.json`
- Test: `src/test/java/com/java_template/common/contract/WorkflowDtoRoundTripTest.java`
- Test: `src/test/java/com/java_template/common/config/CyodaJacksonTest.java`

**Interfaces:**
- Consumes: Gradle task type `PrepareOpenApiTask` (Task 4).
- Produces:
  - DTOs in `org.cyoda.cloud.api.common.model`: condition classes `SimpleConditionDto`, `GroupConditionDto`, `LifecycleConditionDto`, `ArrayConditionDto` (all extend `AbstractConditionDto`) and `FunctionConditionDto` (extends `QueryConditionDto`);
  - interfaces `GroupConditionDtoAllOfConditions` (nested conditions) and `TransitionDefinitionDtoCriterion` / `WorkflowConfigurationDtoCriterion` (criteria);
  - enums `SimpleConditionDto.OperatorTypeEnum`, `LifecycleConditionDto.OperatorTypeEnum`, `GroupConditionDto.OperatorEnum`;
  - `CyodaJackson.configure(ObjectMapper): ObjectMapper`.

- [ ] **Step 1: Replace the OpenAPI generation in `build.gradle`**

Delete the `def openapiFiles = [...]` list, the `openapiFiles.each { … }` loop, and the `tasks.register('generateOpenApi') { … }` block including its `doLast` `@JsonAlias` fix. Insert:

```groovy
tasks.register('prepareOpenApi', com.cyoda.build.PrepareOpenApiTask) {
    sourceFile = file('src/main/resources/cyoda/openapi/openapi.yaml')
    outputFile = layout.buildDirectory.file('cyoda-openapi/openapi.yaml')
}

tasks.register('generateOpenApi', org.openapitools.generator.gradle.plugin.tasks.GenerateTask) {
    description = 'Generates Java DTOs from cyoda-go api/openapi.yaml (patched copy)'
    group = 'openapi tools'
    dependsOn 'prepareOpenApi'
    generatorName = 'spring'
    inputSpec = layout.buildDirectory.file('cyoda-openapi/openapi.yaml').get().asFile.path
    outputDir = "$buildDir/generated-sources/openapi"
    modelPackage = 'org.cyoda.cloud.api.common.model'
    apiPackage = 'org.cyoda.cloud.api.common.api'
    generateApiTests = false
    generateModelTests = false
    generateApiDocumentation = false
    generateModelDocumentation = false
    configOptions = [
        dateLibrary: 'java8',
        useJakartaEe: 'true',
        openApiNullable: 'false',
        serializationLibrary: 'jackson',
        interfaceOnly: 'true',
        useSpringBoot3: 'true',
        useBeanValidation: 'true',
        containerDefaultToNull: 'true',
    ]
    globalProperties = [models: '', apis: 'false', supportingFiles: '']
    typeMappings = [object: 'JsonNode', any: 'JsonNode', AnyType: 'JsonNode']
    importMappings = [JsonNode: 'com.fasterxml.jackson.databind.JsonNode']
    // The only inline oneOf wrapper that does not compile (spec §3.3.3)
    schemaMappings = [TransitionDefinitionDto_processors_inner: 'ExternalizedProcessorDefinitionDto']
}
```

Then delete the old specs:

```bash
git rm -r src/main/resources/api
```

- [ ] **Step 2: Generate and inspect the output**

Run: `./gradlew generateOpenApi`
Expected: BUILD SUCCESSFUL. Then:

```bash
G=build/generated-sources/openapi/src/main/java/org/cyoda/cloud/api/common/model
grep -h 'public class SimpleConditionDto' $G/SimpleConditionDto.java
grep -h 'name = "simple"' $G/AbstractConditionDto.java
grep -h 'List<ExternalizedProcessorDefinitionDto> processors' $G/TransitionDefinitionDto.java
grep -h 'private List<JsonNode> values' $G/ArrayConditionDto.java
```

Expected: each `grep` prints one line.

- [ ] **Step 3: Write the failing Jackson test (Review Focus #2)**

```java
package com.java_template.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.cyoda.cloud.api.common.model.ExternalizedProcessorDefinitionDto;
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
    }

    @Test
    void anExplicitExternalizedTypeStillWorks() throws Exception {
        TransitionDefinitionDto t = om.readValue("""
                {"name":"go","next":"done","processors":[{"type":"externalized","name":"P"}]}""",
                TransitionDefinitionDto.class);

        assertThat(t.getProcessors()).hasSize(1);
    }
}
```

- [ ] **Step 4: Run it to verify it fails**

Run: `./gradlew test --tests com.java_template.common.config.CyodaJacksonTest`
Expected: FAIL with a compile error `cannot find symbol: CyodaJackson`. Framework compile errors about `OperatorTypeDto`/`GroupOperatorDto` also appear; Step 6 fixes them.

- [ ] **Step 5: Implement `CyodaJackson` and its auto-configuration**

```java
package com.java_template.common.config;

import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.databind.jsontype.TypeIdResolver;
import org.cyoda.cloud.api.common.model.ExternalizedProcessorDefinitionDto;
import org.cyoda.cloud.api.common.model.ProcessorDefinitionDto;

/**
 * ABOUTME: Jackson settings the cyoda-go contract needs on every ObjectMapper that reads workflow DTOs.
 * A processor whose "type" is missing or blank is externalized, as cyoda-go treats it (spec §3.3.3).
 */
public final class CyodaJackson {

    private CyodaJackson() {
    }

    public static ObjectMapper configure(ObjectMapper mapper) {
        mapper.addHandler(new ProcessorTypeDefault());
        return mapper;
    }

    static final class ProcessorTypeDefault extends DeserializationProblemHandler {

        @Override
        public JavaType handleUnknownTypeId(DeserializationContext ctxt, JavaType baseType, String subTypeId,
                                            TypeIdResolver idResolver, String failureMsg) {
            return blank(subTypeId) && isProcessor(baseType) ? externalized(ctxt) : null;
        }

        @Override
        public JavaType handleMissingTypeId(DeserializationContext ctxt, JavaType baseType,
                                            TypeIdResolver idResolver, String failureMsg) {
            return isProcessor(baseType) ? externalized(ctxt) : null;
        }

        private static boolean blank(String id) {
            return id == null || id.isBlank();
        }

        private static boolean isProcessor(JavaType baseType) {
            return ProcessorDefinitionDto.class.isAssignableFrom(baseType.getRawClass());
        }

        private static JavaType externalized(DeserializationContext ctxt) {
            return ctxt.constructType(ExternalizedProcessorDefinitionDto.class);
        }
    }
}
```

```java
package com.java_template.common.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.Bean;

/** ABOUTME: Applies {@link CyodaJackson} to Spring Boot's auto-configured ObjectMapper. */
@AutoConfiguration(before = JacksonAutoConfiguration.class)
public class CyodaJacksonAutoConfiguration {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer cyodaJacksonCustomizer() {
        return builder -> builder.postConfigurer(CyodaJackson::configure);
    }
}
```

Append this line to `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```
com.java_template.common.config.CyodaJacksonAutoConfiguration
```

- [ ] **Step 6: Adapt framework code to the generated names**

Apply these replacements. The compiler lists every remaining site: run `./gradlew compileJava compileTestJava` and fix until it is clean.

| Old | New |
|---|---|
| `import org.cyoda.cloud.api.common.model.GroupOperatorDto;` | (delete) |
| `GroupOperatorDto.AND` / `.OR` / `.NOT` | `GroupConditionDto.OperatorEnum.AND` / `.OR` / `.NOT` |
| `OperatorTypeDto.X` used with `SimpleConditionDto` | `SimpleConditionDto.OperatorTypeEnum.X` |
| `OperatorTypeDto.X` used with `LifecycleConditionDto` | `LifecycleConditionDto.OperatorTypeEnum.X` |
| `.operation(…)` on a condition | `.operatorType(…)` |
| `List<QueryConditionDto>` holding nested conditions | `List<GroupConditionDtoAllOfConditions>` |
| `.value(someString)` on `SimpleConditionDto` | `.value(com.fasterxml.jackson.databind.node.TextNode.valueOf(someString))` |
| `org.cyoda.cloud.api.workflow.model.X` / `search.model.X` / `audit.model.X` / `iam.model.X` | `org.cyoda.cloud.api.common.model.X` |

For example, `EntityServiceImpl` business-id lookup becomes:

```java
        SimpleConditionDto simpleCondition = new SimpleConditionDto()
                .jsonPath("$." + businessIdField)
                .operatorType(SimpleConditionDto.OperatorTypeEnum.EQUALS)
                .value(TextNode.valueOf(businessId));

        GroupConditionDto condition = new GroupConditionDto()
                .operator(GroupConditionDto.OperatorEnum.AND)
                .conditions(List.of(simpleCondition));
```

`CyodaRepository.findAll` match-all becomes:

```java
        GroupConditionDto matchAllCondition = new GroupConditionDto()
                .operator(GroupConditionDto.OperatorEnum.AND)
                .conditions(List.of());
```

`EntityCrudOperations.FieldFilter` becomes:

```java
    public record FieldFilter(String fieldName, SimpleConditionDto.OperatorTypeEnum operation, String value) {
        public static FieldFilter equals(String fieldName, String value) {
            return new FieldFilter(fieldName, SimpleConditionDto.OperatorTypeEnum.EQUALS, value);
        }

        public static FieldFilter contains(String fieldName, String value) {
            return new FieldFilter(fieldName, SimpleConditionDto.OperatorTypeEnum.CONTAINS, value);
        }

        public static FieldFilter greaterThan(String fieldName, String value) {
            return new FieldFilter(fieldName, SimpleConditionDto.OperatorTypeEnum.GREATER_THAN, value);
        }

        public static FieldFilter lessThan(String fieldName, String value) {
            return new FieldFilter(fieldName, SimpleConditionDto.OperatorTypeEnum.LESS_THAN, value);
        }
    }
```

In `EntityCrudOperations`, the nested-condition list declared as `List<QueryConditionDto> conditions` becomes `List<GroupConditionDtoAllOfConditions> conditions`. The lifecycle condition becomes:

```java
                LifecycleConditionDto stateCondition = new LifecycleConditionDto()
                        .field("state")
                        .operatorType(LifecycleConditionDto.OperatorTypeEnum.EQUALS)
                        .value(TextNode.valueOf(state));
```

- [ ] **Step 7: Write the round-trip fixture**

Create `src/test/resources/contract/workflow-roundtrip.json`:

```json
{
  "version": "1.5",
  "name": "roundtrip",
  "desc": "Every condition type and a processor, with defaults present",
  "initialState": "none",
  "active": true,
  "criterion": {
    "type": "group",
    "operator": "AND",
    "conditions": [
      {"type": "simple", "jsonPath": "$.amount", "operatorType": "GREATER_THAN", "value": 10},
      {"type": "lifecycle", "field": "state", "operatorType": "EQUALS", "value": "new"},
      {"type": "array", "jsonPath": "$.tags[*]", "values": ["a", null, "c"]},
      {"type": "group", "operator": "OR", "conditions": [
        {"type": "simple", "jsonPath": "$.name", "operatorType": "EQUALS", "value": "x"}
      ]}
    ]
  },
  "states": {
    "none": {
      "transitions": [
        {"name": "to_new", "next": "new", "manual": false, "disabled": false}
      ]
    },
    "new": {
      "transitions": [
        {
          "name": "approve",
          "next": "approved",
          "manual": true,
          "disabled": false,
          "criterion": {
            "type": "function",
            "function": {
              "name": "ItFlagCriterion",
              "config": {"attachEntity": true, "calculationNodesTags": "t", "responseTimeoutMs": 10000, "retryPolicy": "NONE"}
            }
          },
          "processors": [
            {
              "type": "externalized",
              "name": "ItRecordingProcessor",
              "executionMode": "SYNC",
              "config": {"attachEntity": true, "calculationNodesTags": "t", "responseTimeoutMs": 10000, "retryPolicy": "NONE"}
            }
          ]
        }
      ]
    },
    "approved": {"transitions": []}
  }
}
```

- [ ] **Step 8: Write the round-trip test**

```java
package com.java_template.common.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_template.common.config.CyodaJackson;
import org.cyoda.cloud.api.common.model.ArrayConditionDto;
import org.cyoda.cloud.api.common.model.FunctionConditionDto;
import org.cyoda.cloud.api.common.model.GroupConditionDto;
import org.cyoda.cloud.api.common.model.WorkflowConfigurationDto;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowDtoRoundTripTest {

    private final ObjectMapper om = CyodaJackson.configure(new ObjectMapper())
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    /** Numbers compare by value (IntNode 10 == LongNode 10); everything else by equals. */
    private static final Comparator<JsonNode> NUMERIC_AWARE = (a, b) -> {
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue());
        }
        return a.equals(b) ? 0 : 1;
    };

    @Test
    void everyConditionTypeAndProcessorRoundTrips() throws Exception {
        JsonNode input;
        try (InputStream in = getClass().getResourceAsStream("/contract/workflow-roundtrip.json")) {
            input = om.readTree(in);
        }

        WorkflowConfigurationDto dto = om.treeToValue(input, WorkflowConfigurationDto.class);
        JsonNode reserialized = om.readTree(om.writeValueAsString(dto));

        assertThat(dto.getCriterion()).isInstanceOf(GroupConditionDto.class);
        assertThat(((GroupConditionDto) dto.getCriterion()).getConditions())
                .anySatisfy(c -> assertThat(c).isInstanceOf(ArrayConditionDto.class));
        assertThat(dto.getStates().get("new").getTransitions().getFirst().getCriterion())
                .isInstanceOf(FunctionConditionDto.class);
        assertThat(reserialized.equals(NUMERIC_AWARE, input))
                .as("round trip of\n%s\nproduced\n%s", input.toPrettyString(), reserialized.toPrettyString())
                .isTrue();
    }
}
```

- [ ] **Step 9: Run the new tests**

Run: `./gradlew test --tests com.java_template.common.config.CyodaJacksonTest --tests com.java_template.common.contract.WorkflowDtoRoundTripTest`
Expected: PASS (4 tests).

If the round trip fails **only** because the reserialized JSON contains a field with a schema default that the input lacks, add that field with its default value to `workflow-roundtrip.json`. The spec requires defaults to be present in the input. Any other difference is a real defect: fix the patch or mapping, never the test.

- [ ] **Step 10: Run the full unit suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 11: Commit**

```bash
git add build.gradle src/main src/test
git commit -m "build: generate DTOs from cyoda-go api/openapi.yaml into one package

Conditions use the generated oneOf interfaces; a processor without type is
externalized; the framework adapts to OperatorTypeEnum/OperatorEnum names.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 6: Remove OBO and the event-user resolver

**Files:**
- Delete from `src/main/java/com/java_template/common/auth/`:
  - OBO: `OboAwareAuthentication.java`, `OboTokenService.java`, `OboKeyRegistrationService.java`, `SubjectTokenSigner.java`, `AesGcmEncryption.java`, `OboSigningKey.java`, `OboProperties.java`, `OboTokenException.java`;
  - event-user resolver: `EventAuthContextHandler.java`, `DefaultEventUserResolver.java`, `EventUserResolver.java`, `EventUserIdentity.java`, `EventUserResolutionException.java`, `AuthContextMode.java`, `EventAuthContextProperties.java`, `EventAuthContextScope.java`, `EventAuthContextMissingException.java`;
  - `AuthClaimsParser.java`.
- Delete from `src/test/java/com/java_template/common/auth/`: `AesGcmEncryptionTest.java`, `AuthClaimsParserTest.java`, `DefaultEventUserResolverTest.java`, `EventAuthContextHandlerTest.java`, `OboAwareAuthenticationTest.java`, `OboKeyRegistrationServiceTest.java`, `OboPropertiesTest.java`, `OboTokenExceptionTest.java`, `OboTokenServiceTest.java`, `SubjectTokenSignerTest.java`.
- Modify:
  - `src/main/java/com/java_template/common/grpc/client/event_handling/AbstractEventStrategy.java`
  - `ProcessorEventStrategy.java`, `CriteriaEventStrategy.java`
  - `src/main/java/com/java_template/common/grpc/client/ClientAuthorizationInterceptor.java`
  - `src/main/java/com/java_template/common/repository/CyodaRepository.java:372-425` (`sendAndGet`/`sendAndGetCollection`)
- Test: `src/test/java/com/java_template/common/grpc/client/ClientAuthorizationInterceptorTest.java` (rewrite)

**Interfaces:**
- Produces:
  - `AbstractEventStrategy(OperationFactory, ObjectMapper, CyodaContextFactory)` (the `EventAuthContextHandler` parameter is gone), with the same change on `ProcessorEventStrategy`/`CriteriaEventStrategy`;
  - `ClientAuthorizationInterceptor(Authentication)`, which **cancels** the call with `UNAUTHENTICATED` when no token can be obtained.

- [ ] **Step 1: Rewrite the interceptor test (failing)**

```java
package com.java_template.common.grpc.client;

import com.java_template.common.auth.Authentication;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import org.cyoda.cloud.api.grpc.CloudEventsServiceGrpc;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

class ClientAuthorizationInterceptorTest {

    private static final Metadata.Key<String> AUTH = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    @SuppressWarnings({"unchecked", "rawtypes"})
    private final MethodDescriptor<Object, Object> method = (MethodDescriptor) CloudEventsServiceGrpc.getEntityManageMethod();

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void addsTheM2mBearerToken() {
        Authentication auth = mock(Authentication.class);
        when(auth.getAccessToken()).thenReturn(new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, "tok", Instant.now(), Instant.now().plusSeconds(600)));
        ClientCall<Object, Object> delegate = mock(ClientCall.class);
        Channel channel = mock(Channel.class);
        when(channel.newCall(any(), any())).thenReturn((ClientCall) delegate);

        ClientCall<Object, Object> call = new ClientAuthorizationInterceptor(auth).interceptCall(method, CallOptions.DEFAULT, channel);
        Metadata headers = new Metadata();
        call.start(mock(ClientCall.Listener.class), headers);

        assertThat(headers.get(AUTH)).isEqualTo("Bearer tok");
        verify(delegate).start(any(), same(headers));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void cancelsTheCallWhenNoTokenCanBeObtained() {
        Authentication auth = mock(Authentication.class);
        when(auth.getAccessToken()).thenThrow(new IllegalStateException("token endpoint down"));
        ClientCall<Object, Object> delegate = mock(ClientCall.class);
        Channel channel = mock(Channel.class);
        when(channel.newCall(any(), any())).thenReturn((ClientCall) delegate);
        ClientCall.Listener<Object> listener = mock(ClientCall.Listener.class);

        new ClientAuthorizationInterceptor(auth).interceptCall(method, CallOptions.DEFAULT, channel)
                .start(listener, new Metadata());

        ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
        verify(listener).onClose(status.capture(), any());
        assertThat(status.getValue().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
        verify(delegate, never()).start(any(), any());
    }
}
```

- [ ] **Step 2: Delete the OBO and event-user classes and their tests**

Run from the repository root:

```bash
git rm src/main/java/com/java_template/common/auth/{OboAwareAuthentication,OboTokenService,OboKeyRegistrationService,SubjectTokenSigner,AesGcmEncryption,OboSigningKey,OboProperties,OboTokenException,EventAuthContextHandler,DefaultEventUserResolver,EventUserResolver,EventUserIdentity,EventUserResolutionException,AuthContextMode,EventAuthContextProperties,EventAuthContextScope,EventAuthContextMissingException,AuthClaimsParser}.java
git rm src/test/java/com/java_template/common/auth/{AesGcmEncryptionTest,AuthClaimsParserTest,DefaultEventUserResolverTest,EventAuthContextHandlerTest,OboAwareAuthenticationTest,OboKeyRegistrationServiceTest,OboPropertiesTest,OboTokenExceptionTest,OboTokenServiceTest,SubjectTokenSignerTest}.java
```

- [ ] **Step 3: Run the interceptor test to verify it fails**

Run: `./gradlew test --tests com.java_template.common.grpc.client.ClientAuthorizationInterceptorTest`
Expected: compilation FAILS in `AbstractEventStrategy`/`ClientAuthorizationInterceptor`, which reference deleted classes.

- [ ] **Step 4: Rewrite `ClientAuthorizationInterceptor`**

```java
package com.java_template.common.grpc.client;

import com.java_template.common.auth.Authentication;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ABOUTME: gRPC client interceptor that sends the M2M bearer token on every Cyoda call.
 * A call is cancelled with UNAUTHENTICATED when no token can be obtained; it never proceeds unauthenticated.
 */
public class ClientAuthorizationInterceptor implements ClientInterceptor {
    private static final Logger LOG = LoggerFactory.getLogger(ClientAuthorizationInterceptor.class);
    private static final Metadata.Key<String> AUTHORIZATION = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final Authentication authentication;

    public ClientAuthorizationInterceptor(Authentication authentication) {
        this.authentication = authentication;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                final String token;
                try {
                    token = authentication.getAccessToken().getTokenValue();
                } catch (RuntimeException e) {
                    LOG.error("Cannot obtain M2M token for {} — cancelling the call", method.getFullMethodName(), e);
                    responseListener.onClose(
                            Status.UNAUTHENTICATED.withDescription("M2M token unavailable: " + e.getMessage()).withCause(e),
                            new Metadata());
                    return;
                }
                headers.put(AUTHORIZATION, "Bearer " + token);
                super.start(responseListener, headers);
            }
        };
    }
}
```

- [ ] **Step 5: Remove the auth handler from `AbstractEventStrategy` and the two strategies**

In `AbstractEventStrategy`:
- delete the `authContextHandler` field, its constructor parameter, and the imports of the deleted classes;
- the constructor becomes `protected AbstractEventStrategy(OperationFactory operationFactory, ObjectMapper objectMapper, CyodaContextFactory eventContextFactory)`;
- in `handleEvent`, replace

```java
            try (EventAuthContextScope ignored = authContextHandler.establish(cloudEvent)) {
                return executeOperation(operation, request, context);
            }

        } catch (EventAuthContextMissingException e) {
            logger.error("Auth context required but absent for {} event: {}", cloudEventType, e.getMessage());
            return returnErrorResponseFor(request, e);
        } catch (EventUserResolutionException e) {
            logger.error("Cannot resolve user from auth context for {} event: {}", cloudEventType, e.getMessage());
            return returnErrorResponseFor(request, e);
        } catch (Exception e) {
```

with

```java
            return executeOperation(operation, request, context);

        } catch (Exception e) {
```

In `ProcessorEventStrategy` and `CriteriaEventStrategy`, drop the `EventAuthContextHandler authContextHandler` constructor parameter and pass three arguments to `super(...)`.

- [ ] **Step 6: Remove the SecurityContext capture from `CyodaRepository`**

Replace `sendAndGet` and `sendAndGetCollection` (lines ~372–425) with:

```java
    private <RESPONSE_PAYLOAD_TYPE extends BaseEvent> CompletableFuture<RESPONSE_PAYLOAD_TYPE> sendAndGet(
            final Function<CloudEvent, CloudEvent> apiCall,
            final BaseEvent baseEvent,
            final Class<RESPONSE_PAYLOAD_TYPE> responsePayloadType
    ) {
        try {
            final CloudEvent requestEvent = cloudEventBuilder.buildEvent(baseEvent);
            return CompletableFuture.supplyAsync(() -> {
                        logger.debug("Sending event: {}", requestEvent);
                        CloudEvent cloudEvent = requestAndGetOrThrow(apiCall, requestEvent);
                        logger.debug("Received event: {}", cloudEvent);
                        return cloudEvent;
                    })
                    .thenApply(response -> cloudEventParser.parseCloudEvent(response, responsePayloadType))
                    .thenApply(this::validateResponse);
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }
    }

    private <RESPONSE_PAYLOAD_TYPE extends BaseEvent> CompletableFuture<Stream<RESPONSE_PAYLOAD_TYPE>> sendAndGetCollection(
            final Function<CloudEvent, Iterator<CloudEvent>> apiCall,
            final BaseEvent baseEvent,
            final Class<RESPONSE_PAYLOAD_TYPE> responsePayloadClass
    ) {
        try {
            final var requestEvent = cloudEventBuilder.buildEvent(baseEvent);
            return CompletableFuture.supplyAsync(() -> requestAndGetOrThrow(apiCall, requestEvent))
                    .thenApply(response -> processCollection(Streams.stream(response), responsePayloadClass));
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }
    }
```

Then delete the now-unused `SecurityContext`/`SecurityContextHolder` imports.

- [ ] **Step 7: Verify no references remain and the tests pass**

```bash
grep -rn -E 'Obo|EventAuthContext|EventUser|AuthClaimsParser|SubjectTokenSigner|AesGcm' src/main src/test || echo clean
./gradlew test
```

Expected: `clean`, then BUILD SUCCESSFUL. The two interceptor tests pass.

- [ ] **Step 8: Commit**

```bash
git add -A src/main src/test
git commit -m "refactor: remove OBO and the event-user resolver

Compute calls Cyoda as M2M only (spec §4.6). The gRPC interceptor now cancels a
call it cannot authenticate instead of sending it without a header.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Explicit configuration: auth mode, gRPC TLS, deadlines, local profile

**Files:**
- Modify: `src/main/java/com/java_template/common/config/Config.java`
- Delete: `src/main/java/com/java_template/common/config/CyodaLightConfigCustomizer.java`, `src/test/java/com/java_template/common/config/CyodaLightConfigCustomizerTest.java`, `src/main/resources/META-INF/spring.factories`
- Create: `src/main/java/com/java_template/common/auth/CyodaTokenSource.java`
- Create: `src/main/java/com/java_template/common/auth/NoCyodaAuthentication.java`
- Modify: `src/main/java/com/java_template/common/auth/Authentication.java`
- Modify: `ClientAuthorizationInterceptor.java`, `GrpcClientAutoConfiguration.java`, `tool/CyodaInit.java`, `service/WorkflowServiceImpl.java`, `service/EdgeMessageServiceImpl.java`, `repository/CyodaRepository.java` (the per-call deadline)
- Modify: `src/main/resources/application.yml`
- Create: `src/main/resources/application-local.yml`
- Test: `src/test/java/com/java_template/common/config/ConfigBindingTest.java`
- Test: `src/test/java/com/java_template/common/auth/AuthModeWiringTest.java`
- Modify: `src/test/java/com/java_template/common/grpc/client/ClientAuthorizationInterceptorTest.java`

**Interfaces:**
- Produces:
  - `Config.AuthMode { CLIENT_CREDENTIALS, NONE }`, and on `Config`: `getAuthMode()`, `isGrpcTls()`, `getGrpcCallDeadlineMs(): long`, `getExecutionMode()` (default `"virtual"`), `getKeepAliveWarningThreshold()` (default `20000`);
  - `interface CyodaTokenSource { Optional<String> bearerToken(); void invalidate(); }`, implemented by `Authentication` (bean when `auth-mode=client-credentials`, which is also the default) and `NoCyodaAuthentication` (bean when `auth-mode=none`);
  - `ClientAuthorizationInterceptor(CyodaTokenSource)`.

- [ ] **Step 1: Write the failing configuration tests (Review Focus #3)**

```java
package com.java_template.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigBindingTest {

    @Configuration
    @EnableConfigurationProperties(Config.class)
    static class Bind {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Bind.class);

    @Test
    void defaultsAreCloudShaped() {
        runner.run(ctx -> {
            Config c = ctx.getBean(Config.class);
            assertThat(c.isGrpcTls()).isTrue();
            assertThat(c.getAuthMode()).isEqualTo(Config.AuthMode.CLIENT_CREDENTIALS);
            assertThat(c.getExecutionMode()).isEqualTo("virtual");
            assertThat(c.getGrpcCallDeadlineMs()).isEqualTo(120_000L);
            assertThat(c.getKeepAliveWarningThreshold()).isEqualTo(20_000L);
        });
    }

    @Test
    void relaxedBindingOfAuthModeNone() {
        runner.withPropertyValues("app.config.auth-mode=none", "app.config.grpc-tls=false")
                .run(ctx -> {
                    Config c = ctx.getBean(Config.class);
                    assertThat(c.getAuthMode()).isEqualTo(Config.AuthMode.NONE);
                    assertThat(c.isGrpcTls()).isFalse();
                });
    }

    @Test
    void localProfileTargetsADefaultLocalCyoda() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("local", new ClassPathResource("application-local.yml"));
        PropertySource<?> local = sources.getFirst();

        assertThat(local.getProperty("server.port")).isEqualTo(8081);
        assertThat(local.getProperty("app.config.cyoda-api-url")).isEqualTo("http://localhost:8080/api");
        assertThat(local.getProperty("app.config.grpc-address")).isEqualTo("localhost");
        assertThat(local.getProperty("app.config.grpc-server-port")).isEqualTo(9090);
        assertThat(local.getProperty("app.config.grpc-tls")).isEqualTo(false);
        assertThat(local.getProperty("app.config.auth-mode")).isEqualTo("none");
    }
}
```

```java
package com.java_template.common.auth;

import com.java_template.common.config.Config;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class AuthModeWiringTest {

    @Configuration
    @EnableConfigurationProperties(Config.class)
    @Import({Authentication.class, NoCyodaAuthentication.class})
    static class Wiring {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Wiring.class);

    @Test
    void noneModeCreatesNoClientRegistrationAndSendsNoToken() {
        runner.withPropertyValues("app.config.auth-mode=none").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(Authentication.class);
            assertThat(ctx.getBean(CyodaTokenSource.class).bearerToken()).isEmpty();
        });
    }

    @Test
    void clientCredentialsModeFailsFastOnMissingCredentials() {
        runner.withPropertyValues("app.config.cyoda-api-url=http://localhost:1/api", "app.config.cyoda-client-id=")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().rootCause()
                        .hasMessageContaining("app.config.cyoda-client-id")
                        .hasMessageContaining("app.config.cyoda-client-secret"));
    }

    @Test
    void clientCredentialsModeWithCredentialsProvidesTheM2mSource() {
        runner.withPropertyValues("app.config.cyoda-api-url=http://localhost:1/api",
                        "app.config.cyoda-client-id=id", "app.config.cyoda-client-secret=secret")
                .run(ctx -> assertThat(ctx.getBean(CyodaTokenSource.class)).isInstanceOf(Authentication.class));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests com.java_template.common.config.ConfigBindingTest --tests com.java_template.common.auth.AuthModeWiringTest`
Expected: compilation FAILS: `cannot find symbol: AuthMode`, `CyodaTokenSource`, `NoCyodaAuthentication`, `isGrpcTls`.

- [ ] **Step 3: Update `Config` and delete the cyoda-light customizer**

1. **Fields.** In `Config`, remove the `skipSsl` and `cyodaLight` fields, their accessors, and the nested `CyodaLight` class. Change the `executionMode` initialiser to `"virtual"` and `keepAliveWarningThreshold` to `20000`. Add:

```java
    public enum AuthMode { CLIENT_CREDENTIALS, NONE }

    private boolean grpcTls = true;
    private AuthMode authMode = AuthMode.CLIENT_CREDENTIALS;
    private long grpcCallDeadlineMs = 120_000L;

    public boolean isGrpcTls() {
        return grpcTls;
    }

    public void setGrpcTls(boolean grpcTls) {
        this.grpcTls = grpcTls;
    }

    public AuthMode getAuthMode() {
        return authMode;
    }

    public void setAuthMode(AuthMode authMode) {
        this.authMode = authMode;
    }

    public long getGrpcCallDeadlineMs() {
        return grpcCallDeadlineMs;
    }

    public void setGrpcCallDeadlineMs(long grpcCallDeadlineMs) {
        this.grpcCallDeadlineMs = grpcCallDeadlineMs;
    }
```

2. **Delete the cyoda-light customizer:**

```bash
git rm src/main/java/com/java_template/common/config/CyodaLightConfigCustomizer.java \
       src/test/java/com/java_template/common/config/CyodaLightConfigCustomizerTest.java \
       src/main/resources/META-INF/spring.factories
```

- [ ] **Step 4: Add `CyodaTokenSource` and `NoCyodaAuthentication`; make `Authentication` conditional and validated**

```java
package com.java_template.common.auth;

import java.util.Optional;

/** ABOUTME: Source of the bearer token for outbound Cyoda calls; empty when app.config.auth-mode=none. */
public interface CyodaTokenSource {

    Optional<String> bearerToken();

    /** Drops a cached token so the next call fetches a new one. */
    void invalidate();
}
```

```java
package com.java_template.common.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** ABOUTME: Token source for cyoda-go mock IAM (app.config.auth-mode=none): no Authorization header is sent. */
@Component
@ConditionalOnProperty(name = "app.config.auth-mode", havingValue = "none")
public class NoCyodaAuthentication implements CyodaTokenSource {

    @Override
    public Optional<String> bearerToken() {
        return Optional.empty();
    }

    @Override
    public void invalidate() {
        // nothing cached
    }
}
```

In `Authentication`:
- change the class declaration to

```java
@Service
@ConditionalOnProperty(name = "app.config.auth-mode", havingValue = "client-credentials", matchIfMissing = true)
public class Authentication implements CyodaTokenSource {
```

- make these the first statements of the constructor, after `this.config = config;`:

```java
        if (isBlank(config.getCyodaClientId()) || isBlank(config.getCyodaClientSecret())) {
            throw new IllegalStateException("app.config.cyoda-client-id and app.config.cyoda-client-secret must be set "
                    + "when app.config.auth-mode=client-credentials (use auth-mode=none for cyoda-go mock IAM)");
        }
```

- add

```java
    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @Override
    public Optional<String> bearerToken() {
        return Optional.of(getAccessToken().getTokenValue());
    }

    @Override
    public void invalidate() {
        invalidateTokens();
    }
```

(Imports: `org.springframework.boot.autoconfigure.condition.ConditionalOnProperty`, `java.util.Optional`.)

- [ ] **Step 5: Switch every user to `CyodaTokenSource`**

- **`ClientAuthorizationInterceptor`:** the field and constructor take `CyodaTokenSource tokenSource`. In `start(...)`, replace everything from `final String token;` through `super.start(...)` with:

```java
                final java.util.Optional<String> token;
                try {
                    token = tokenSource.bearerToken();
                } catch (RuntimeException e) {
                    LOG.error("Cannot obtain M2M token for {} — cancelling the call", method.getFullMethodName(), e);
                    responseListener.onClose(
                            Status.UNAUTHENTICATED.withDescription("M2M token unavailable: " + e.getMessage()).withCause(e),
                            new Metadata());
                    return;
                }
                token.ifPresent(t -> headers.put(AUTHORIZATION, "Bearer " + t));
                super.start(responseListener, headers);
```

- **`GrpcClientAutoConfiguration`:**
  - the three stub bean methods take `CyodaTokenSource tokenSource` instead of `Authentication authentication`, and build `new ClientAuthorizationInterceptor(tokenSource)`;
  - in `managedChannel(...)`, replace `config.isSkipSsl()` with `!config.isGrpcTls()`;
  - delete the six `@ConditionalOnProperty(name = "execution.mode", …)` `CalculationExecutionStrategy` bean methods. Nothing injects them, and `eventExecutionRouter()` builds its own executors from `config.getExecutionMode()`.
- **`CyodaInit`, `WorkflowServiceImpl`, `EdgeMessageServiceImpl`:** replace the `Authentication authentication` field and constructor parameter with `CyodaTokenSource tokenSource`, and every `authentication.getAccessToken().getTokenValue()` with `tokenSource.bearerToken().orElse(null)`. `HttpUtils` already omits the `Authorization` header for a null token.
- **`ClientAuthorizationInterceptorTest`:** mock `CyodaTokenSource` instead of `Authentication`:
  - first test: `when(source.bearerToken()).thenReturn(java.util.Optional.of("tok"))`;
  - second test: `when(source.bearerToken()).thenThrow(new IllegalStateException("token endpoint down"))`;
  - add a third test:

```java
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void sendsNoHeaderInAuthModeNone() {
        CyodaTokenSource source = mock(CyodaTokenSource.class);
        when(source.bearerToken()).thenReturn(java.util.Optional.empty());
        ClientCall<Object, Object> delegate = mock(ClientCall.class);
        Channel channel = mock(Channel.class);
        when(channel.newCall(any(), any())).thenReturn((ClientCall) delegate);
        Metadata headers = new Metadata();

        new ClientAuthorizationInterceptor(source).interceptCall(method, CallOptions.DEFAULT, channel)
                .start(mock(ClientCall.Listener.class), headers);

        assertThat(headers.get(AUTH)).isNull();
        verify(delegate).start(any(), same(headers));
    }
```

- [ ] **Step 6: Apply the per-call deadline in `CyodaRepository`**

Add:

```java
    private CloudEventsServiceGrpc.CloudEventsServiceBlockingStub blocking() {
        return cloudEventsServiceBlockingStub.withDeadlineAfter(config.getGrpcCallDeadlineMs(), TimeUnit.MILLISECONDS);
    }
```

Then replace every method reference `cloudEventsServiceBlockingStub::entityManage`, `::entityManageCollection`, `::entitySearch` and `::entitySearchCollection` with the matching lambda, e.g. `req -> blocking().entityManage(req)`. Verify:

```bash
grep -n 'cloudEventsServiceBlockingStub::' src/main/java/com/java_template/common/repository/CyodaRepository.java || echo none
```

Expected: `none`.

- [ ] **Step 7: Update `application.yml` and add `application-local.yml`**

In `src/main/resources/application.yml`, replace everything from the `  config:` line under `app:` to the end of the file (this also deletes the unused top-level `cyoda.api.url`) with:

```yaml
  config:
    # Cyoda connection. Cloud-shaped defaults; see application-local.yml for a local cyoda-go.
    cyoda-host:
    cyoda-api-url: https://${app.config.cyoda-host}/api
    # client-credentials: M2M token from {cyoda-api-url}/oauth/token (cyoda-go jwt mode, Cyoda Cloud)
    # none: no Authorization header (cyoda-go mock IAM)
    auth-mode: client-credentials
    cyoda-client-id:
    cyoda-client-secret:

    # gRPC
    grpc-address: grpc-${app.config.cyoda-host}
    grpc-server-port: 443
    grpc-tls: true
    grpc-call-deadline-ms: 120000
    grpc-processor-tag: cyoda_application
    grpc-communication-data-format: JSON
    grpc-max-inbound-message-size: 16777216
    grpc-max-inbound-metadata-size: 16384
    grpc-flow-control-window: 67108864
    grpc-keep-alive-time-seconds: 30
    grpc-keep-alive-timeout-seconds: 10
    grpc-idle-timeout-seconds: 300

    # Processor/criteria threads: virtual (one virtual thread per task) or platform (fixed pools below)
    execution-mode: virtual
    processor-thread-pool: 20
    criteria-thread-pool: 20
    control-thread-pool: 3

    # Connection
    handshake-timeout-ms: 5000
    initial-reconnect-delay-ms: 200
    max-reconnect-delay-ms: 10000
    failed-reconnects-limit: 10

    # Monitoring (ms without a server keep-alive before a warning; diagnostic only)
    sent-events-cache-max-size: 100
    monitoring-scheduler-initial-delay-seconds: 1
    monitoring-scheduler-delay-seconds: 3
    keep-alive-warning-threshold: 20000

    # Development only: trust self-signed certificates
    ssl-trust-all: false
    ssl-trusted-hosts:

    # Activates NoopProcessor and AlwaysTrueCriterion/AlwaysFalseCriterion for workflow testing
    include-default-operations: false
```

Create `src/main/resources/application-local.yml`:

```yaml
# Local cyoda-go with its defaults after `cyoda init && cyoda` (mock IAM, REST 8080, gRPC 9090).
# Run with: ./gradlew runApp --args='--spring.profiles.active=local'
server:
  port: 8081

app:
  config:
    cyoda-api-url: http://localhost:8080/api
    grpc-address: localhost
    grpc-server-port: 9090
    grpc-tls: false
    auth-mode: none
```

- [ ] **Step 8: Run the tests and check for leftovers**

```bash
./gradlew test
grep -rn -E 'isSkipSsl|getCyodaLight|execution\.mode|cyoda-light|cyoda\.api\.url' src || echo clean
```

Expected: BUILD SUCCESSFUL, including `ConfigBindingTest` (3 tests) and `AuthModeWiringTest` (3 tests). Then `clean`.

- [ ] **Step 9: Commit**

```bash
git add -A src/main src/test
git commit -m "feat(config): explicit auth-mode, gRPC TLS and call deadline; local profile

auth-mode=none talks to cyoda-go mock IAM without building an OAuth client;
client-credentials fails fast on missing credentials. cyoda-light removed.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Compute-member protocol fixes

**Files:**
- Modify: `src/main/java/com/java_template/common/serializer/ResponseBuilder.java` (`build()` in both builders)
- Modify: `src/main/java/com/java_template/common/grpc/client/event_handling/AbstractEventStrategy.java`, `ProcessorEventStrategy.java`, `CriteriaEventStrategy.java`
- Modify: `src/main/java/com/java_template/common/auth/CloudEventAuthContext.java`
- Modify: `src/main/java/com/java_template/common/workflow/CyodaContextFactory.java`
- Test: `src/test/java/com/java_template/application/serializer/ResponseBuilderTest.java` (add a test)
- Test: `src/test/java/com/java_template/common/grpc/client/AbstractEventStrategyTest.java` (add tests)
- Test: `src/test/java/com/java_template/common/auth/CloudEventAuthContextExtractorTest.java` (update values)
- Test: `src/test/java/com/java_template/common/contract/ResponseSerializationTest.java`

**Interfaces:**
- Produces:
  - abstract hooks on `AbstractEventStrategy`: `requestIdOf(TRequest): String`, `setEntityIdInErrorResponse(TResponse, TRequest)`, `setRecoveredEntityId(TResponse, String)`;
  - `AbstractEventStrategy.recoverEntityIdFromCloudEvent(CloudEvent): Optional<String>` (public static);
  - `CloudEventAuthContext.AUTH_TYPES = Set.of("user","service","system")`.

- [ ] **Step 1: Write the failing tests**

Add to `ResponseBuilderTest`:

```java
    @Test
    void responsesCarryAFreshIdAndTheRequestAndEntityIds() {
        EntityCriteriaCalculationRequest criteriaRequest = new EntityCriteriaCalculationRequest()
                .withId("req-event-1").withRequestId("r-1").withEntityId(java.util.UUID.randomUUID().toString());

        EntityCriteriaCalculationResponse criteria = ResponseBuilder.forCriterion(criteriaRequest).withMatch().build();

        assertThat(criteria.getId()).isNotBlank().isNotEqualTo("req-event-1");
        assertThat(criteria.getRequestId()).isEqualTo("r-1");
        assertThat(criteria.getEntityId()).isEqualTo(criteriaRequest.getEntityId());
        assertThat(criteria.getMatches()).isTrue();
    }
```

Create `ResponseSerializationTest`:

```java
package com.java_template.common.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cyoda.cloud.api.event.common.Error;
import org.cyoda.cloud.api.event.processing.EntityCriteriaCalculationResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseSerializationTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void aSuccessfulAnswerOmitsErrorInsteadOfSendingNull() throws Exception {
        EntityCriteriaCalculationResponse r = new EntityCriteriaCalculationResponse();
        r.setId("id-1");
        r.setRequestId("r-1");
        r.setEntityId("e-1");
        r.setSuccess(true);
        r.setMatches(true);

        JsonNode json = om.readTree(om.writeValueAsString(r));

        assertThat(json.has("error")).isFalse();
        assertThat(json.get("success").asBoolean()).isTrue();
    }

    @Test
    void aFailureSendsSuccessFalseExplicitly() throws Exception {
        EntityCriteriaCalculationResponse r = new EntityCriteriaCalculationResponse();
        r.setId("id-1");
        r.setRequestId("r-1");
        r.setEntityId("e-1");
        r.setSuccess(false);
        r.setMatches(false);
        Error e = new Error();
        e.setCode("GENERAL_ERROR");
        e.setMessage("boom");
        r.setError(e);

        JsonNode json = om.readTree(om.writeValueAsString(r));

        assertThat(json.get("success").isBoolean()).isTrue();
        assertThat(json.get("success").asBoolean()).isFalse();
        assertThat(json.get("matches").asBoolean()).isFalse();
    }
}
```

Add to `AbstractEventStrategyTest` (imports: `com.fasterxml.jackson.databind.ObjectMapper`, `com.fasterxml.jackson.databind.node.ObjectNode`, `com.java_template.common.grpc.client.event_handling.ProcessorEventStrategy`, `com.java_template.common.workflow.CyodaContextFactory`, `com.java_template.common.workflow.OperationFactory`, `org.cyoda.cloud.api.event.processing.EntityProcessorCalculationResponse`, `java.util.UUID`, `static org.mockito.ArgumentMatchers.any`, `static org.mockito.Mockito.lenient`, `static org.mockito.Mockito.mock`, `static org.junit.jupiter.api.Assertions.assertNotEquals`, `static org.junit.jupiter.api.Assertions.assertNotNull`):

```java
    @Test
    void anErrorResponseCarriesAFreshIdTheRequestIdAndTheEntityId() throws Exception {
        ObjectMapper om = new ObjectMapper();
        OperationFactory factory = mock(OperationFactory.class);
        lenient().when(factory.getProcessorForModel(any())).thenThrow(new IllegalStateException("boom"));
        ProcessorEventStrategy strategy = new ProcessorEventStrategy(factory, om, new CyodaContextFactory(om));

        String entityId = UUID.randomUUID().toString();
        ObjectNode request = om.createObjectNode();
        request.put("id", "evt-1");
        request.put("requestId", "r-1");
        request.put("entityId", entityId);
        request.put("processorId", "p-1");
        request.put("processorName", "SomeProcessor");
        ObjectNode payload = request.putObject("payload");
        payload.put("type", "ENTITY");
        payload.putObject("meta").putObject("modelKey").put("name", "m").put("version", 1);
        payload.putObject("data");
        io.cloudevents.v1.proto.CloudEvent ce = io.cloudevents.v1.proto.CloudEvent.newBuilder()
                .setId("ce-1").setSource("test").setSpecVersion("1.0")
                .setType("EntityProcessorCalculationRequest")
                .setTextData(om.writeValueAsString(request)).build();

        EntityProcessorCalculationResponse response = strategy.handleEvent(ce);

        assertEquals(Boolean.FALSE, response.getSuccess());
        assertNotNull(response.getId());
        assertNotEquals("evt-1", response.getId());
        assertEquals("r-1", response.getRequestId());
        assertEquals(entityId, response.getEntityId());
    }

    @Test
    void entityIdIsRecoveredFromCorruptedJson() {
        when(cloudEvent.getTextData()).thenReturn(
                "{\"requestId\":\"r-1\",\"entityId\":\"8824c480-c166-11ee-bf9f-ae468cd3ed16\", broken");

        assertEquals(java.util.Optional.of("8824c480-c166-11ee-bf9f-ae468cd3ed16"),
                AbstractEventStrategy.recoverEntityIdFromCloudEvent(cloudEvent));
    }
```

In `CloudEventAuthContextExtractorTest`, replace every `"service_account"` with `"service"` and remove `"unauthenticated"`/`"unknown"` from the loop's array. Add:

```java
    @Test
    void knowsExactlyTheThreeCyodaGoAuthTypes() {
        assertThat(CloudEventAuthContext.AUTH_TYPES).containsExactlyInAnyOrder("user", "service", "system");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests '*ResponseBuilderTest' --tests '*ResponseSerializationTest' --tests '*AbstractEventStrategyTest' --tests '*CloudEventAuthContextExtractorTest'`
Expected:
- `ResponseBuilderTest` FAILS: the id equals the request's id;
- `recoverEntityIdFromCloudEvent`/`AUTH_TYPES` do not compile.

- [ ] **Step 3: Implement the fixes**

In both `ResponseBuilder.*.build()` methods, replace `response.setId(request.getId());` with:

```java
            response.setId(java.util.UUID.randomUUID().toString());
```

In `AbstractEventStrategy.returnErrorResponseFor(TRequest request, Exception e)`, replace the first four lines of the body with:

```java
        TResponse errorResponse = createErrorResponse();
        errorResponse.setId(java.util.UUID.randomUUID().toString());
        errorResponse.setSuccess(false);
        setRequestIdInErrorResponse(errorResponse, requestIdOf(request));
        setEntityIdInErrorResponse(errorResponse, request);
```

The old code passed `request.getId()`, the CloudEvent payload id, as the `requestId`; the answer must echo the callout's `requestId`.

In `returnErrorResponseFor(CloudEvent cloudEvent, JsonProcessingException e)`, right after `TResponse errorResponse = createErrorResponse();`, add:

```java
        errorResponse.setId(java.util.UUID.randomUUID().toString());
        recoverEntityIdFromCloudEvent(cloudEvent).ifPresent(entityId -> setRecoveredEntityId(errorResponse, entityId));
```

Add to `AbstractEventStrategy`:

```java
    /** The originating callout's requestId, which the answer must echo (not the CloudEvent payload id). */
    protected abstract String requestIdOf(TRequest request);

    protected abstract void setEntityIdInErrorResponse(TResponse errorResponse, TRequest request);

    protected abstract void setRecoveredEntityId(TResponse errorResponse, String entityId);

    public static Optional<String> recoverEntityIdFromCloudEvent(CloudEvent cloudEvent) {
        if (cloudEvent == null || cloudEvent.getTextData() == null || cloudEvent.getTextData().isBlank()) {
            return Optional.empty();
        }
        Matcher m = Pattern.compile(
                "[\"']?entityId[\"']?\\s*:\\s*[\"']?([a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12})")
                .matcher(cloudEvent.getTextData());
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }
```

In `ProcessorEventStrategy`:

```java
    @Override
    protected String requestIdOf(EntityProcessorCalculationRequest request) {
        return request.getRequestId();
    }

    @Override
    protected void setEntityIdInErrorResponse(EntityProcessorCalculationResponse errorResponse,
                                              EntityProcessorCalculationRequest request) {
        errorResponse.setEntityId(request.getEntityId());
    }

    @Override
    protected void setRecoveredEntityId(EntityProcessorCalculationResponse errorResponse, String entityId) {
        errorResponse.setEntityId(entityId);
    }
```

In `CriteriaEventStrategy`:

```java
    @Override
    protected String requestIdOf(EntityCriteriaCalculationRequest request) {
        return request.getRequestId();
    }

    @Override
    protected void setEntityIdInErrorResponse(EntityCriteriaCalculationResponse errorResponse,
                                              EntityCriteriaCalculationRequest request) {
        errorResponse.setEntityId(request.getEntityId());
    }

    @Override
    protected void setRecoveredEntityId(EntityCriteriaCalculationResponse errorResponse, String entityId) {
        errorResponse.setEntityId(entityId);
    }
```

In `CloudEventAuthContext` add:

```java
    /** The only authtype values cyoda-go sends (service_account is retired). */
    public static final java.util.Set<String> AUTH_TYPES = java.util.Set.of("user", "service", "system");
```

In `CyodaContextFactory`:
- add `private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CyodaContextFactory.class);`;
- at the start of `createCyodaEventContext`, add:

```java
        var authType = cloudEvent.getAttributesMap().get("authtype");
        if (authType != null && !CloudEventAuthContext.AUTH_TYPES.contains(authType.getCeString())) {
            log.warn("CloudEvent {} carries unknown authtype '{}' (expected one of {})",
                    cloudEvent.getId(), authType.getCeString(), CloudEventAuthContext.AUTH_TYPES);
        }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A src/main src/test
git commit -m "fix(grpc): answers carry a fresh id, the callout requestId and the entityId

Also: authtype values user/service/system.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Example workflows at schema 1.5; snippets validated

**Files:**
- Modify: `src/test/resources/example/config/workflow/template_workflow.json`
- Move: `src/test/resources/example/config/workflow/{criterion_examples,processor_examples}.json` → `src/test/resources/example/config/snippets/`
- Modify: the moved snippet files (wire names)
- Modify: `src/test/java/com/example/tests/WorkflowConfigurationMarshallingTest.java`
- Test: `src/test/java/com/example/tests/SnippetValidationTest.java`

**Interfaces:**
- Consumes: `CyodaJackson.configure` (Task 5), and the generated `WorkflowConfigurationDto`, `TransitionDefinitionDtoCriterion` and `ExternalizedProcessorDefinitionDto`.

- [ ] **Step 1: Write the failing tests**

```java
package com.example.tests;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_template.common.config.CyodaJackson;
import org.cyoda.cloud.api.common.model.ExternalizedProcessorDefinitionDto;
import org.cyoda.cloud.api.common.model.TransitionDefinitionDtoCriterion;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SnippetValidationTest {

    private final ObjectMapper om = CyodaJackson.configure(new ObjectMapper())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private JsonNode read(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/example/config/snippets/" + name)) {
            return om.readTree(in);
        }
    }

    @Test
    void everyCriterionExampleIsAValidCriterion() throws Exception {
        JsonNode snippets = read("criterion_examples.json");
        List<String> checked = new ArrayList<>();
        snippets.fields().forEachRemaining(e -> {
            JsonNode example = e.getValue().get("example");
            if (example != null) {
                try {
                    om.treeToValue(example, TransitionDefinitionDtoCriterion.class);
                } catch (Exception ex) {
                    throw new AssertionError("criterion snippet '" + e.getKey() + "' is invalid: " + ex.getMessage(), ex);
                }
                checked.add(e.getKey());
            }
        });
        assertThat(checked).contains("simple_criterion", "group_criterion", "function_criterion");
    }

    @Test
    void everyProcessorExampleIsAValidProcessor() throws Exception {
        JsonNode snippets = read("processor_examples.json");
        List<JsonNode> processors = new ArrayList<>();
        snippets.get("processor_configuration").fields().forEachRemaining(e -> {
            if (e.getValue().isObject() && e.getValue().has("name")) {
                processors.add(e.getValue());
            }
        });
        snippets.at("/processor_chain_example/processors").forEach(processors::add);

        assertThat(processors).isNotEmpty();
        for (JsonNode p : processors) {
            om.treeToValue(p, ExternalizedProcessorDefinitionDto.class);
        }
    }
}
```

In `WorkflowConfigurationMarshallingTest`:
- change the import to `org.cyoda.cloud.api.common.model.WorkflowConfigurationDto`;
- build its mapper with `CyodaJackson.configure(new ObjectMapper())`;
- inside its loop over files in `example/config/workflow/`, after the `readValue`, add:

```java
            assertThat(dto.getVersion()).as(workflowFile.getName()).isEqualTo("1.5");
```

(Use the file's existing variable names for the deserialized DTO and the file.)

- [ ] **Step 2: Move the snippets and run the tests to verify they fail**

```bash
mkdir -p src/test/resources/example/config/snippets
git mv src/test/resources/example/config/workflow/criterion_examples.json src/test/resources/example/config/snippets/
git mv src/test/resources/example/config/workflow/processor_examples.json src/test/resources/example/config/snippets/
./gradlew test --tests com.example.tests.SnippetValidationTest --tests com.example.tests.WorkflowConfigurationMarshallingTest
```

Expected: FAIL.
- The criterion snippet `simple_criterion` is invalid (`Unrecognized field "operation"`).
- The marshalling test sees version `"1.0"`.

- [ ] **Step 3: Update the JSON to the v1.5 contract**

- **`template_workflow.json`:**
  - set `"version": "1.5"`;
  - make sure every `jsonPath` starts with `$.`;
  - check that every processor and function `config` has a `retryPolicy` of `NONE`/`FIXED`, `responseTimeoutMs` ≤ 60000, and a non-empty `calculationNodesTags`.
- **`criterion_examples.json`:**
  - in every condition inside an `example`, rename `"operation"` to `"operatorType"`;
  - make sure `jsonPath` values start with `$.`;
  - write any array condition with `values`;
  - leave the descriptive keys (`supported_operations`, `supported_operators`, `jsonPath_reference`) as documentation. The test does not read them.
- **`processor_examples.json`:** remove any processor field the test reports as unrecognized, and keep `executionMode` within `SYNC`/`ASYNC_SAME_TX`/`ASYNC_NEW_TX`/`COMMIT_BEFORE_DISPATCH`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A src/test
git commit -m "test: example workflows at schema 1.5; reference snippets validated against the DTOs

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 10: Harness foundation: version pin and binary lookup (testFixtures)

**Files:**
- Modify: `build.gradle` (the `java-test-fixtures` plugin and test-fixture dependencies)
- Create: `src/testFixtures/java/com/java_template/testing/cyoda/CyodaVersion.java`
- Create: `src/testFixtures/java/com/java_template/testing/cyoda/CyodaBinary.java`
- Test: `src/test/java/com/java_template/testing/cyoda/CyodaVersionTest.java`
- Test: `src/test/java/com/java_template/testing/cyoda/CyodaBinaryTest.java`

**Interfaces:**
- Produces:
  - `record CyodaVersion(String version, String commit)`, with:
    - `static CyodaVersion readPin(Path pinFile)`
    - `static CyodaVersion parseBinaryOutput(String output)`
    - `boolean isDevPin()`
    - `static Optional<String> incompatibility(CyodaVersion pin, CyodaVersion binary)`
    - `static final String INSTALL_HINT`
  - `final class CyodaBinary`, with:
    - `static Path locate(String sysProp, String envVar, String pathEnv)`
    - `static Path locate()`
    - `static CyodaVersion version(Path binary)`
    - `static Path resolvePinned(Path pinFile, boolean allowMismatch, Consumer<String> warn)`

- [ ] **Step 1: Add the testFixtures plugin and dependencies**

In `build.gradle`:
- add `id 'java-test-fixtures'` to the `plugins { }` block;
- add to `dependencies { }`:

```groovy
    testFixturesImplementation 'org.springframework.boot:spring-boot-starter-test'
    testFixturesImplementation 'com.fasterxml.jackson.core:jackson-databind'
    testFixturesImplementation platform('org.junit:junit-bom:5.13.1')
    testFixturesImplementation 'org.junit.jupiter:junit-jupiter'
    testImplementation testFixtures(project)
```

- [ ] **Step 2: Write the failing tests (Review Focus #1)**

```java
package com.java_template.testing.cyoda;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CyodaVersionTest {

    private static final String SHA = "df6ad2c7a1b2c3d4e5f60718293a4b5c6d7e8f90";

    @Test
    void readsThePinFile(@TempDir Path dir) throws Exception {
        Path pin = Files.writeString(dir.resolve("CYODA_VERSION"), "0.9.0-dev\ncommit=" + SHA + "\n");

        CyodaVersion v = CyodaVersion.readPin(pin);

        assertThat(v.version()).isEqualTo("0.9.0-dev");
        assertThat(v.commit()).isEqualTo(SHA);
        assertThat(v.isDevPin()).isTrue();
    }

    @Test
    void rejectsAMalformedPinFile(@TempDir Path dir) throws Exception {
        Path pin = Files.writeString(dir.resolve("CYODA_VERSION"), "0.9.0\n");

        assertThatThrownBy(() -> CyodaVersion.readPin(pin)).hasMessageContaining("commit=<sha>");
    }

    @Test
    void parsesTheBinaryVersionLine() {
        CyodaVersion v = CyodaVersion.parseBinaryOutput(
                "cyoda version 0.8.4 (commit e86115417899938dd1ea2b38c1441fee43d0bb6a, built 2026-09-09T22:13:55Z)\n");

        assertThat(v).isEqualTo(new CyodaVersion("0.8.4", "e86115417899938dd1ea2b38c1441fee43d0bb6a"));
    }

    @Test
    void releasedPinsCompareVersions() {
        CyodaVersion pin = new CyodaVersion("0.9.0", SHA);

        assertThat(CyodaVersion.incompatibility(pin, new CyodaVersion("0.9.0", "abc1234"))).isEmpty();
        assertThat(CyodaVersion.incompatibility(pin, new CyodaVersion("0.8.4", "abc1234")))
                .get().asString().contains("0.8.4").contains("0.9.0").contains("scripts/install-cyoda.sh");
    }

    @Test
    void devPinsCompareCommits() {
        CyodaVersion pin = new CyodaVersion("0.9.0-dev", SHA);

        assertThat(CyodaVersion.incompatibility(pin, new CyodaVersion("0.9.0-dev", SHA))).isEmpty();
        assertThat(CyodaVersion.incompatibility(pin, new CyodaVersion("0.9.0-dev", "df6ad2c"))).isEmpty();
        assertThat(CyodaVersion.incompatibility(pin, new CyodaVersion("0.9.0-dev", "0123456789"))).isPresent();
    }

    @Test
    void anUnstampedDevBinaryIsRefused() {
        CyodaVersion pin = new CyodaVersion("0.9.0-dev", SHA);

        assertThat(CyodaVersion.incompatibility(pin, new CyodaVersion("dev", "unknown")))
                .get().asString().contains("dev (commit unknown)").contains("scripts/install-cyoda.sh");
    }
}
```

```java
package com.java_template.testing.cyoda;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CyodaBinaryTest {

    private static Path fakeCyoda(Path dir, String versionLine) throws Exception {
        Path bin = dir.resolve("cyoda");
        Files.writeString(bin, "#!/bin/sh\necho '" + versionLine + "'\n");
        Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwxr-xr-x"));
        return bin;
    }

    @Test
    void systemPropertyWinsOverEnvAndPath(@TempDir Path a, @TempDir Path b) throws Exception {
        Path prop = fakeCyoda(a, "x");
        Path env = fakeCyoda(b, "y");

        assertThat(CyodaBinary.locate(prop.toString(), env.toString(), b.toString())).isEqualTo(prop);
        assertThat(CyodaBinary.locate(null, env.toString(), a.toString())).isEqualTo(env);
        assertThat(CyodaBinary.locate(null, null, "/nonexistent:" + a)).isEqualTo(a.resolve("cyoda"));
    }

    @Test
    void aMissingBinaryFailsWithTheInstallCommand() {
        assertThatThrownBy(() -> CyodaBinary.locate(null, null, "/nonexistent"))
                .hasMessageContaining("No cyoda binary found")
                .hasMessageContaining("scripts/install-cyoda.sh");
    }

    @Test
    void resolvePinnedRunsOnlyVersionAndEnforcesThePin(@TempDir Path dir) throws Exception {
        String sha = "df6ad2c7a1b2c3d4e5f60718293a4b5c6d7e8f90";
        Path pin = Files.writeString(dir.resolve("CYODA_VERSION"), "0.9.0-dev\ncommit=" + sha + "\n");
        Path bin = fakeCyoda(dir, "cyoda version 0.9.0-dev (commit 0000000000, built now)");
        System.setProperty("cyoda.bin", bin.toString());
        try {
            assertThatThrownBy(() -> CyodaBinary.resolvePinned(pin, false, m -> {}))
                    .hasMessageContaining("0000000000").hasMessageContaining(sha);

            List<String> warnings = new ArrayList<>();
            assertThat(CyodaBinary.resolvePinned(pin, true, warnings::add)).isEqualTo(bin);
            assertThat(warnings).hasSize(1);
        } finally {
            System.clearProperty("cyoda.bin");
        }
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.java_template.testing.cyoda.*'`
Expected: compilation FAILS: `cannot find symbol: CyodaVersion`, `CyodaBinary`.

- [ ] **Step 4: Implement `CyodaVersion` and `CyodaBinary`**

```java
package com.java_template.testing.cyoda;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The cyoda-go version pinned in CYODA_VERSION, or reported by `cyoda --version` (spec §3.4). */
public record CyodaVersion(String version, String commit) {

    public static final String INSTALL_HINT = "Install the pinned cyoda with: scripts/install-cyoda.sh "
            + "(or scripts/install-cyoda.sh --src-dir <cyoda-go checkout>), then export CYODA_BIN=<printed path> "
            + "or pass -Dcyoda.bin=<path>.";

    private static final Pattern BINARY_LINE = Pattern.compile("cyoda version (\\S+) \\(commit (\\S+), built [^)]*\\)");

    public static CyodaVersion readPin(Path pinFile) {
        try {
            List<String> lines = Files.readAllLines(pinFile).stream().map(String::trim).filter(l -> !l.isEmpty()).toList();
            if (lines.size() != 2 || !lines.get(1).startsWith("commit=")) {
                throw new IllegalStateException(pinFile + " must contain two lines: <version> and commit=<sha>");
            }
            return new CyodaVersion(lines.get(0), lines.get(1).substring("commit=".length()));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read cyoda pin " + pinFile, e);
        }
    }

    public static CyodaVersion parseBinaryOutput(String output) {
        Matcher m = BINARY_LINE.matcher(output);
        if (!m.find()) {
            throw new IllegalStateException("unrecognised `cyoda --version` output: " + output.strip());
        }
        return new CyodaVersion(m.group(1), m.group(2));
    }

    public boolean isDevPin() {
        return version.endsWith("-dev");
    }

    /** Why {@code binary} does not satisfy {@code pin}, or empty when it does. */
    public static Optional<String> incompatibility(CyodaVersion pin, CyodaVersion binary) {
        if ("dev".equals(binary.version) && "unknown".equals(binary.commit)) {
            return Optional.of("cyoda binary reports 'dev (commit unknown)': it was built without version stamping. " + INSTALL_HINT);
        }
        if (pin.isDevPin()) {
            return sameCommit(pin.commit, binary.commit) ? Optional.empty()
                    : Optional.of("cyoda binary is at commit " + binary.commit + " but the pin is " + pin.version
                    + " at commit " + pin.commit + ". " + INSTALL_HINT);
        }
        String reported = binary.version.startsWith("v") ? binary.version.substring(1) : binary.version;
        return reported.equals(pin.version) ? Optional.empty()
                : Optional.of("cyoda binary is version " + binary.version + " but the pin is " + pin.version + ". " + INSTALL_HINT);
    }

    static boolean sameCommit(String a, String b) {
        int n = Math.min(a.length(), b.length());
        return n >= 7 && a.substring(0, n).equalsIgnoreCase(b.substring(0, n));
    }
}
```

```java
package com.java_template.testing.cyoda;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Finds the cyoda binary (-Dcyoda.bin, then $CYODA_BIN, then PATH) and checks it against the pin.
 * The binary is only ever run with --version here: an unrecognised argument would start a server (cyoda-go #623).
 */
public final class CyodaBinary {

    private CyodaBinary() {
    }

    public static Path locate() {
        return locate(System.getProperty("cyoda.bin"), System.getenv("CYODA_BIN"), System.getenv("PATH"));
    }

    public static Path locate(String sysProp, String envVar, String pathEnv) {
        if (sysProp != null && !sysProp.isBlank()) {
            return requireExecutable(Path.of(sysProp), "-Dcyoda.bin");
        }
        if (envVar != null && !envVar.isBlank()) {
            return requireExecutable(Path.of(envVar), "CYODA_BIN");
        }
        if (pathEnv != null) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                Path candidate = Path.of(dir, "cyoda");
                if (Files.isExecutable(candidate)) {
                    return candidate;
                }
            }
        }
        throw new IllegalStateException("No cyoda binary found (-Dcyoda.bin, CYODA_BIN, PATH). " + CyodaVersion.INSTALL_HINT);
    }

    public static CyodaVersion version(Path binary) {
        try {
            Process p = new ProcessBuilder(binary.toString(), "--version").redirectErrorStream(true).start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException(binary + " --version did not finish within 10 s");
            }
            return CyodaVersion.parseBinaryOutput(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("cannot run " + binary + " --version", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted running " + binary + " --version", e);
        }
    }

    public static Path resolvePinned(Path pinFile, boolean allowMismatch, Consumer<String> warn) {
        Path binary = locate();
        CyodaVersion pin = CyodaVersion.readPin(pinFile);
        CyodaVersion actual = version(binary);
        CyodaVersion.incompatibility(pin, actual).ifPresent(problem -> {
            if (!allowMismatch) {
                throw new IllegalStateException(problem + " (binary: " + binary + "; override with -Dcyoda.allowVersionMismatch=true)");
            }
            warn.accept(problem);
        });
        return binary;
    }

    private static Path requireExecutable(Path p, String source) {
        if (!Files.isExecutable(p)) {
            throw new IllegalStateException(source + " points at " + p + ", which is not an executable file. " + CyodaVersion.INSTALL_HINT);
        }
        return p;
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.java_template.testing.cyoda.*'`
Expected: PASS (9 tests).

- [ ] **Step 6: Commit**

```bash
git add build.gradle src/testFixtures src/test/java/com/java_template/testing
git commit -m "test(harness): cyoda version pin and binary lookup

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: `scripts/install-cyoda.sh`

**Files:**
- Create: `scripts/install-cyoda.sh`

**Interfaces:**
- Consumes: `src/main/resources/cyoda/CYODA_VERSION` (Task 1).
- Produces: a cyoda binary at `build/cyoda-bin/cyoda` (default `--dest`). The script prints its path on stdout.

- [ ] **Step 1: Write the script**

```bash
#!/usr/bin/env bash
# Installs the cyoda binary pinned in src/main/resources/cyoda/CYODA_VERSION (spec §7.4).
#   scripts/install-cyoda.sh                       released pin: download; -dev pin: build pinned commit from GitHub
#   scripts/install-cyoda.sh --from-src [<ref>]    build from GitHub at <ref> (default: the pinned commit)
#   scripts/install-cyoda.sh --src-dir <checkout>  build an existing local checkout (its HEAD must be the pinned commit)
#   --dest <dir>                                   output directory (default: build/cyoda-bin)
# Prints the binary path on stdout (use it as CYODA_BIN). Never runs `cyoda init`.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PIN="$ROOT/src/main/resources/cyoda/CYODA_VERSION"
VERSION="$(sed -n 1p "$PIN")"
COMMIT="$(sed -n 2p "$PIN" | sed 's/^commit=//')"
DEST="$ROOT/build/cyoda-bin"
MODE=""
REF=""
SRC_DIR=""

fail() { echo "install-cyoda: $*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --from-src)
      MODE=github
      if [ $# -gt 1 ] && [[ "$2" != --* ]]; then REF="$2"; shift; fi
      shift ;;
    --src-dir) MODE=local; SRC_DIR="${2:-}"; shift 2 ;;
    --dest) DEST="${2:-}"; shift 2 ;;
    *) fail "unknown argument $1" ;;
  esac
done
if [ -z "$MODE" ]; then
  if [[ "$VERSION" == *-dev ]]; then MODE=github; else MODE=release; fi
fi
mkdir -p "$DEST"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

build_checkout() { # <checkout dir>
  command -v go >/dev/null || fail "Go >= 1.26.7 is required to build cyoda from source"
  local sha date
  sha="$(git -C "$1" rev-parse HEAD)"
  date="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  ( cd "$1" && CGO_ENABLED=0 go build \
      -ldflags "-X main.version=$VERSION -X main.commit=$sha -X main.buildDate=$date" \
      -o "$DEST/cyoda" ./cmd/cyoda )
}

case "$MODE" in
  release)
    os="$(uname -s | tr '[:upper:]' '[:lower:]')"
    arch="$(uname -m)"
    case "$arch" in x86_64) arch=amd64 ;; aarch64|arm64) arch=arm64 ;; esac
    asset="cyoda_${VERSION}_${os}_${arch}.tar.gz"
    base="https://github.com/Cyoda/cyoda-go/releases/download/v${VERSION}"
    curl -fsSL "$base/$asset" -o "$WORK/$asset" || fail "cannot download $base/$asset"
    curl -fsSL "$base/SHA256SUMS" -o "$WORK/SHA256SUMS" || fail "cannot download $base/SHA256SUMS"
    ( cd "$WORK" && grep " $asset\$" SHA256SUMS | shasum -a 256 -c - >/dev/null ) || fail "checksum mismatch for $asset"
    tar -xzf "$WORK/$asset" -C "$WORK" cyoda
    mv "$WORK/cyoda" "$DEST/cyoda"
    ;;
  github)
    [ -n "$REF" ] || REF="$COMMIT"
    git clone --quiet https://github.com/Cyoda/cyoda-go.git "$WORK/cyoda-go"
    git -C "$WORK/cyoda-go" checkout --quiet "$REF"
    build_checkout "$WORK/cyoda-go"
    ;;
  local)
    [ -d "$SRC_DIR/cmd/cyoda" ] || fail "$SRC_DIR is not a cyoda-go checkout"
    head="$(git -C "$SRC_DIR" rev-parse HEAD)"
    [ "$head" = "$COMMIT" ] || fail "$SRC_DIR is at $head but the pin is $COMMIT (sync the pin or check out the pinned commit)"
    build_checkout "$SRC_DIR"
    ;;
esac

chmod +x "$DEST/cyoda"
"$DEST/cyoda" --version >&2
echo "$DEST/cyoda"
```

- [ ] **Step 2: Build the pinned binary from the local checkout and verify it**

```bash
chmod +x scripts/install-cyoda.sh
scripts/install-cyoda.sh --src-dir /Users/paul/go-projects/cyoda-light/cyoda-go
build/cyoda-bin/cyoda --version
```

Expected:
- the last line of the script's output is `…/build/cyoda-bin/cyoda`;
- `--version` prints `cyoda version 0.9.0-dev (commit <pinned sha>, built …)`.

If the local checkout's HEAD moved past the pin, the script fails with `… is at <sha> but the pin is <sha>`. In that case, re-run Task 1's sync (`--version 0.9.0-dev`) and commit the refreshed contract as its own commit first.

- [ ] **Step 3: Verify argument handling**

```bash
scripts/install-cyoda.sh --bogus; echo "exit=$?"
scripts/install-cyoda.sh --src-dir /tmp; echo "exit=$?"
```

Expected:
- `install-cyoda: unknown argument --bogus` and `exit=1`;
- `install-cyoda: /tmp is not a cyoda-go checkout` and `exit=1`.

- [ ] **Step 4: Commit**

```bash
git add scripts/install-cyoda.sh
git commit -m "build: install-cyoda.sh builds or downloads the pinned cyoda without running cyoda init

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: `CyodaServer` subprocess harness and the `integrationTest` source set

**Files:**
- Create: `src/testFixtures/java/com/java_template/testing/cyoda/Profile.java`
- Create: `src/testFixtures/java/com/java_template/testing/cyoda/CyodaProfiles.java`
- Create: `src/testFixtures/java/com/java_template/testing/cyoda/FreePorts.java`
- Create: `src/testFixtures/java/com/java_template/testing/cyoda/CyodaServer.java`
- Create: `src/testFixtures/java/com/java_template/testing/cyoda/CyodaTestEnvironment.java`
- Create: `src/testFixtures/java/com/java_template/testing/cyoda/CyodaServerExtension.java`
- Modify: `build.gradle` (`integrationTest` source set and task, `check` dependency)
- Test: `src/test/java/com/java_template/testing/cyoda/CyodaServerEnvironmentTest.java`
- Test: `src/integrationTest/java/com/java_template/it/CyodaServerIT.java`

**Interfaces:**
- Consumes: `CyodaBinary.resolvePinned` (Task 10).
- Produces:
  - `record Profile(String name, Map<String,String> overrides)`, with `static Profile mockMemory()`, `Profile named(String)` and `Profile with(String key, String value)`;
  - `CyodaProfiles.DEFAULT = "default"`, `CyodaProfiles.KEEPALIVE_SHORT = "keepalive-short"`, `static Profile byName(String)`;
  - `CyodaServer implements AutoCloseable`, with:
    - `static CyodaServer start(Path binary, Profile profile, Path logDir)`
    - `static Map<String,String> environment(Profile, int http, int grpc, int admin, Path home, String path)`
    - `String apiUrl()`, `String grpcHost()`, `int grpcPort()`, `String logTail(int lines)`, `void close()`
  - `CyodaTestEnvironment.server(Profile): CyodaServer` (cached per profile name) and `static void closeAll()`;
  - `CyodaServerExtension implements BeforeAllCallback`.

- [ ] **Step 1: Write the failing environment unit test**

```java
package com.java_template.testing.cyoda;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CyodaServerEnvironmentTest {

    @Test
    void theEnvironmentIsBuiltFromScratchAndIsolatedFromUserConfig() {
        Path home = Path.of("/tmp/cyoda-home-x");

        Map<String, String> env = CyodaServer.environment(Profile.mockMemory(), 18080, 19090, 19091, home, "/usr/bin:/bin");

        assertThat(env).containsEntry("PATH", "/usr/bin:/bin")
                .containsEntry("HOME", home.toString())
                .containsEntry("XDG_CONFIG_HOME", home.resolve(".config").toString())
                .containsEntry("CYODA_HTTP_PORT", "18080")
                .containsEntry("CYODA_GRPC_PORT", "19090")
                .containsEntry("CYODA_ADMIN_PORT", "19091")
                .containsEntry("CYODA_CONTEXT_PATH", "/api")
                .containsEntry("CYODA_STORAGE_BACKEND", "memory")
                .containsEntry("CYODA_IAM_MODE", "mock")
                .containsEntry("CYODA_IAM_MOCK_KIND", "user")
                .containsEntry("CYODA_IAM_MOCK_ROLES", "ROLE_ADMIN,ROLE_M2M")
                .containsEntry("CYODA_KEEPALIVE_INTERVAL", "10")
                .containsEntry("CYODA_KEEPALIVE_TIMEOUT", "30")
                .containsEntry("CYODA_DISPATCH_WAIT_TIMEOUT", "5s")
                .containsEntry("CYODA_SCHEDULER_SCAN_INTERVAL", "50ms")
                .containsEntry("CYODA_SUPPRESS_BANNER", "true")
                .containsEntry("CYODA_ERROR_RESPONSE_MODE", "verbose")
                .doesNotContainKey("CYODA_PROFILES");
    }

    @Test
    void profileOverridesWin() {
        Map<String, String> env = CyodaServer.environment(CyodaProfiles.byName(CyodaProfiles.KEEPALIVE_SHORT),
                1, 2, 3, Path.of("/tmp/h"), "/bin");

        assertThat(env).containsEntry("CYODA_KEEPALIVE_INTERVAL", "1").containsEntry("CYODA_KEEPALIVE_TIMEOUT", "3");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests com.java_template.testing.cyoda.CyodaServerEnvironmentTest`
Expected: compilation FAILS: `cannot find symbol: CyodaServer`, `Profile`, `CyodaProfiles`.

- [ ] **Step 3: Implement `Profile`, `CyodaProfiles`, `FreePorts` and `CyodaServer`**

```java
package com.java_template.testing.cyoda;

import java.util.LinkedHashMap;
import java.util.Map;

/** A named set of CYODA_* overrides on top of the tier-1 defaults (mock IAM, memory storage). */
public record Profile(String name, Map<String, String> overrides) {

    public static Profile mockMemory() {
        return new Profile(CyodaProfiles.DEFAULT, Map.of());
    }

    public Profile named(String newName) {
        return new Profile(newName, overrides);
    }

    public Profile with(String key, String value) {
        Map<String, String> m = new LinkedHashMap<>(overrides);
        m.put(key, value);
        return new Profile(name, Map.copyOf(m));
    }
}
```

```java
package com.java_template.testing.cyoda;

/** The profiles tier-1 tests may request by name via @CyodaIntegrationTest(profile = …). */
public final class CyodaProfiles {

    public static final String DEFAULT = "default";
    /** Keep-alive 1 s / eviction 3 s — only for ComputeMemberJoinIT (spec §6.2). */
    public static final String KEEPALIVE_SHORT = "keepalive-short";

    private CyodaProfiles() {
    }

    public static Profile byName(String name) {
        return switch (name) {
            case DEFAULT -> Profile.mockMemory();
            case KEEPALIVE_SHORT -> Profile.mockMemory().named(KEEPALIVE_SHORT)
                    .with("CYODA_KEEPALIVE_INTERVAL", "1")
                    .with("CYODA_KEEPALIVE_TIMEOUT", "3");
            default -> throw new IllegalArgumentException("unknown cyoda test profile '" + name + "'");
        };
    }
}
```

```java
package com.java_template.testing.cyoda;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;

final class FreePorts {

    private FreePorts() {
    }

    static int[] allocate(int count) {
        ServerSocket[] sockets = new ServerSocket[count];
        try {
            int[] ports = new int[count];
            for (int i = 0; i < count; i++) {
                sockets[i] = new ServerSocket(0);
                ports[i] = sockets[i].getLocalPort();
            }
            return ports;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot allocate free ports", e);
        } finally {
            for (ServerSocket s : sockets) {
                if (s != null) {
                    try {
                        s.close();
                    } catch (IOException ignored) {
                        // best effort
                    }
                }
            }
        }
    }
}
```

```java
package com.java_template.testing.cyoda;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * One cyoda-go subprocess on free ports with memory storage and mock IAM (spec §6.1).
 * The environment is built from scratch: HOME/XDG_CONFIG_HOME point at a temp dir so the
 * user's ~/.config/cyoda/cyoda.env and any ./.env cannot change storage or auth.
 */
public final class CyodaServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CyodaServer.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final Process process;
    private final Profile profile;
    private final int httpPort;
    private final int grpcPort;
    private final Path workDir;
    private final Path logFile;

    private CyodaServer(Process process, Profile profile, int httpPort, int grpcPort, Path workDir, Path logFile) {
        this.process = process;
        this.profile = profile;
        this.httpPort = httpPort;
        this.grpcPort = grpcPort;
        this.workDir = workDir;
        this.logFile = logFile;
    }

    public static CyodaServer start(Path binary, Profile profile, Path logDir) {
        try {
            int[] ports = FreePorts.allocate(3);
            Path work = Files.createTempDirectory("cyoda-" + profile.name() + "-");
            Files.createDirectories(logDir);
            Path logFile = logDir.resolve(profile.name() + "-" + TS.format(LocalDateTime.now()) + ".log");
            ProcessBuilder pb = new ProcessBuilder(binary.toString())
                    .directory(work.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile());
            pb.environment().clear();
            pb.environment().putAll(environment(profile, ports[0], ports[1], ports[2], work, System.getenv("PATH")));
            CyodaServer server = new CyodaServer(pb.start(), profile, ports[0], ports[1], work, logFile);
            server.awaitHealthy(Duration.ofSeconds(30));
            log.info("cyoda '{}' up: http={} grpc={} log={}", profile.name(), server.apiUrl(), ports[1], logFile);
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot start cyoda " + binary, e);
        }
    }

    static Map<String, String> environment(Profile profile, int http, int grpc, int admin, Path home, String path) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PATH", path == null ? "/usr/bin:/bin" : path);
        env.put("HOME", home.toString());
        env.put("XDG_CONFIG_HOME", home.resolve(".config").toString());
        env.put("CYODA_HTTP_PORT", Integer.toString(http));
        env.put("CYODA_GRPC_PORT", Integer.toString(grpc));
        env.put("CYODA_ADMIN_PORT", Integer.toString(admin));
        env.put("CYODA_ADMIN_BIND_ADDRESS", "127.0.0.1");
        env.put("CYODA_CONTEXT_PATH", "/api");
        env.put("CYODA_STORAGE_BACKEND", "memory");
        env.put("CYODA_IAM_MODE", "mock");
        env.put("CYODA_IAM_MOCK_KIND", "user");
        env.put("CYODA_IAM_MOCK_ROLES", "ROLE_ADMIN,ROLE_M2M");
        env.put("CYODA_KEEPALIVE_INTERVAL", "10");
        env.put("CYODA_KEEPALIVE_TIMEOUT", "30");
        env.put("CYODA_DISPATCH_WAIT_TIMEOUT", "5s");
        env.put("CYODA_SCHEDULER_SCAN_INTERVAL", "50ms");
        env.put("CYODA_SUPPRESS_BANNER", "true");
        env.put("CYODA_ERROR_RESPONSE_MODE", "verbose");
        env.put("CYODA_LOG_LEVEL", "info");
        env.putAll(profile.overrides());
        return env;
    }

    public String apiUrl() {
        return "http://127.0.0.1:" + httpPort + "/api";
    }

    public String grpcHost() {
        return "127.0.0.1";
    }

    public int grpcPort() {
        return grpcPort;
    }

    public Profile profile() {
        return profile;
    }

    public String logTail(int lines) {
        try {
            List<String> all = Files.readAllLines(logFile);
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        } catch (IOException e) {
            return "(cannot read " + logFile + ": " + e.getMessage() + ")";
        }
    }

    private void awaitHealthy(Duration limit) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        HttpRequest health = HttpRequest.newBuilder(URI.create(apiUrl() + "/health")).timeout(Duration.ofSeconds(2)).build();
        long deadline = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException("cyoda exited with code " + process.exitValue() + " during startup:\n" + logTail(50));
            }
            try {
                if (http.send(health, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                    return;
                }
            } catch (IOException ignored) {
                // not listening yet
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            sleep(100);
        }
        close();
        throw new IllegalStateException("cyoda did not become healthy within " + limit.toSeconds() + " s:\n" + logTail(50));
    }

    @Override
    public void close() {
        if (process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        try (Stream<Path> files = Files.walk(workDir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // temp dir cleanup is best effort
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

- [ ] **Step 4: Implement `CyodaTestEnvironment` and `CyodaServerExtension`**

```java
package com.java_template.testing.cyoda;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One lazily started cyoda server per profile per test JVM, shared by every test class.
 * System properties (set by the integrationTest Gradle task): cyoda.pinFile, cyoda.logDir,
 * cyoda.bin, cyoda.allowVersionMismatch.
 */
public final class CyodaTestEnvironment {

    private static final Logger log = LoggerFactory.getLogger(CyodaTestEnvironment.class);
    private static final Map<String, CyodaServer> SERVERS = new ConcurrentHashMap<>();

    private CyodaTestEnvironment() {
    }

    public static synchronized CyodaServer server(Profile profile) {
        return SERVERS.computeIfAbsent(profile.name(), n -> start(profile));
    }

    public static CyodaRest rest(Profile profile) {
        return new CyodaRest(server(profile).apiUrl());
    }

    public static synchronized void closeAll() {
        SERVERS.values().forEach(CyodaServer::close);
        SERVERS.clear();
    }

    private static CyodaServer start(Profile profile) {
        Path pin = Path.of(System.getProperty("cyoda.pinFile", "src/main/resources/cyoda/CYODA_VERSION"));
        Path logDir = Path.of(System.getProperty("cyoda.logDir", "build/cyoda-logs"));
        Path binary = CyodaBinary.resolvePinned(pin, Boolean.getBoolean("cyoda.allowVersionMismatch"), log::warn);
        CyodaServer server = CyodaServer.start(binary, profile, logDir);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "cyoda-" + profile.name() + "-shutdown"));
        return server;
    }
}
```

```java
package com.java_template.testing.cyoda;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;

/**
 * Starts the profile's cyoda server before the test class and stops every server when the
 * test engine finishes. Spring's cached test contexts close later (JVM shutdown), so their
 * member streams may log one connection loss after the server is gone; that is expected.
 */
public class CyodaServerExtension implements BeforeAllCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        String profile = AnnotationSupport.findAnnotation(context.getRequiredTestClass(), CyodaIntegrationTest.class)
                .map(CyodaIntegrationTest::profile)
                .orElse(CyodaProfiles.DEFAULT);
        CyodaTestEnvironment.server(CyodaProfiles.byName(profile));
        context.getRoot().getStore(ExtensionContext.Namespace.GLOBAL)
                .getOrComputeIfAbsent("cyoda-servers", k -> new Closer(), Closer.class);
    }

    static final class Closer implements ExtensionContext.Store.CloseableResource, AutoCloseable {
        @Override
        public void close() {
            CyodaTestEnvironment.closeAll();
        }
    }
}
```

`CyodaIntegrationTest` and `CyodaRest` are referenced above and created in Task 13. Add minimal compilable versions now so this task builds:

```java
package com.java_template.testing.cyoda;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.*;

/** Placeholder completed in Task 13 (adds @SpringBootTest and the context customizer). */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@ExtendWith(CyodaServerExtension.class)
public @interface CyodaIntegrationTest {
    String profile() default CyodaProfiles.DEFAULT;
}
```

```java
package com.java_template.testing.cyoda;

/** Completed in Task 13. */
public final class CyodaRest {
    private final String apiUrl;

    public CyodaRest(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String apiUrl() {
        return apiUrl;
    }
}
```

- [ ] **Step 5: Add the `integrationTest` source set and task to `build.gradle`**

```groovy
sourceSets {
    integrationTest {
        java.srcDir 'src/integrationTest/java'
        resources.srcDir 'src/integrationTest/resources'
        compileClasspath += sourceSets.main.output + sourceSets.test.output
        runtimeClasspath += sourceSets.main.output + sourceSets.test.output
    }
}

configurations {
    integrationTestImplementation.extendsFrom testImplementation
    integrationTestRuntimeOnly.extendsFrom testRuntimeOnly
}

dependencies {
    integrationTestImplementation testFixtures(project)
}

def cyodaPin = file('src/main/resources/cyoda/CYODA_VERSION')

tasks.register('integrationTest', Test) {
    description = 'Tier-1 integration tests against a cyoda-go subprocess (mock IAM, memory storage)'
    group = 'verification'
    testClassesDirs = sourceSets.integrationTest.output.classesDirs
    classpath = sourceSets.integrationTest.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter tasks.named('test')
    systemProperty 'user.dir', project.projectDir.absolutePath
    systemProperty 'cyoda.pinFile', cyodaPin.absolutePath
    systemProperty 'cyoda.logDir', layout.buildDirectory.dir('cyoda-logs').get().asFile.absolutePath
    ['cyoda.bin', 'cyoda.allowVersionMismatch'].each { key ->
        if (System.getProperty(key) != null) {
            systemProperty key, System.getProperty(key)
        }
    }
    inputs.file cyodaPin
    testLogging {
        events 'failed'
        exceptionFormat 'full'
    }
}

tasks.named('check') { dependsOn 'integrationTest' }
```

(If `build.gradle` already has a top-level `sourceSets { }` block, add the `integrationTest { … }` entry inside it instead of declaring a second block.)

- [ ] **Step 6: Write the smoke integration test**

```java
package com.java_template.it;

import com.java_template.testing.cyoda.CyodaServer;
import com.java_template.testing.cyoda.CyodaTestEnvironment;
import com.java_template.testing.cyoda.Profile;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class CyodaServerIT {

    @Test
    void thePinnedCyodaStartsInMemoryAndIsHealthy() throws Exception {
        CyodaServer server = CyodaTestEnvironment.server(Profile.mockMemory());

        HttpResponse<String> health = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(server.apiUrl() + "/health")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("UP");
        assertThat(server.logTail(200)).doesNotContain("sqlite");
    }
}
```

- [ ] **Step 7: Run the unit test and the smoke IT**

```bash
./gradlew test --tests com.java_template.testing.cyoda.CyodaServerEnvironmentTest
./gradlew integrationTest -Dcyoda.bin=build/cyoda-bin/cyoda --tests com.java_template.it.CyodaServerIT
```

Expected: both PASS. `build/cyoda-logs/default-*.log` exists and shows the memory backend.

- [ ] **Step 8: Check the failure path by hand (Review Focus #1)**

```bash
./gradlew integrationTest -Dcyoda.bin=/nonexistent --tests com.java_template.it.CyodaServerIT; echo "exit=$?"
```

Expected: FAIL within seconds. The report shows `-Dcyoda.bin points at /nonexistent, which is not an executable file. Install the pinned cyoda with: scripts/install-cyoda.sh …`. No Spring stack trace.

- [ ] **Step 9: Commit**

```bash
git add build.gradle src/testFixtures src/test/java/com/java_template/testing src/integrationTest
git commit -m "test(harness): cyoda-go subprocess on free ports with an isolated environment; integrationTest task

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 13: Spring wiring for integration tests, test fixtures, and `ComputeMemberJoinIT`

**Files:**
- Modify: `src/testFixtures/java/com/java_template/testing/cyoda/CyodaIntegrationTest.java` (complete it)
- Create: `src/testFixtures/java/com/java_template/testing/cyoda/CyodaContextCustomizerFactory.java`
- Create: `src/testFixtures/resources/META-INF/spring.factories`
- Modify: `src/testFixtures/java/com/java_template/testing/cyoda/CyodaRest.java` (complete it)
- Create: `src/testFixtures/java/com/java_template/testing/cyoda/WorkflowTemplating.java`
- Create: `src/testFixtures/java/com/java_template/testing/cyoda/CyodaModelSetup.java`
- Create: `src/testFixtures/java/com/java_template/testing/cyoda/CyodaAwait.java`
- Create: `src/integrationTest/java/com/java_template/it/support/ItThing.java`, `ItRecordingProcessor.java`, `ItFailingProcessor.java`, `ItFlagCriterion.java`, `ExampleAppScan.java`
- Create: `src/integrationTest/resources/it-workflows/thing-workflow.json`
- Test: `src/test/java/com/java_template/testing/cyoda/WorkflowTemplatingTest.java`
- Test: `src/test/java/com/java_template/testing/cyoda/IntegrationTestHygieneTest.java`
- Test: `src/integrationTest/java/com/java_template/it/ComputeMemberJoinIT.java`

**Interfaces:**
- Consumes: `CyodaTestEnvironment`, `CyodaProfiles`, `CyodaServer` (Task 12); `ConnectionStateTracker`/`ObserverState.READY` (framework).
- Produces:
  - `@CyodaIntegrationTest(profile = …)`, which boots `com.java_template.Application` against the profile's server with `auth-mode=none` and a context-unique `app.config.grpc-processor-tag`;
  - `CyodaRest`: `get(String path)`, `post(String path, Object body)`, `put(String path, Object body)`, `delete(String path)`, each returning `CyodaRest.Response(int status, JsonNode body, String raw)`; plus `Response.requireSuccess()`;
  - `WorkflowTemplating.load(String classpathResource, String tag): ObjectNode` and `WorkflowTemplating.applyTag(JsonNode workflow, String tag): ObjectNode`;
  - `CyodaModelSetup.createModel(CyodaRest rest, String model, int version, JsonNode sampleData, JsonNode workflow)`;
  - `CyodaAwait.memberReady(ConnectionStateTracker tracker, Duration limit)`;
  - `ItThing` (CyodaEntity; `static ItThing of(String model, String name, int amount)`, `ItThing in(String model)`, `static JsonNode sampleData()`);
  - processor and criterion names `ItRecordingProcessor`, `ItFailingProcessor`, `ItFlagCriterion`;
  - workflow transitions `process`, `fail`, `approve`, and states `new`, `processed`, `failed`, `approved`.

- [ ] **Step 1: Write the failing unit tests**

```java
package com.java_template.testing.cyoda;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowTemplatingTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void replacesEveryProcessorAndFunctionTag() throws Exception {
        ObjectNode wf = (ObjectNode) om.readTree("""
                {"name":"w","states":{"a":{"transitions":[
                  {"name":"t","next":"b",
                   "processors":[{"name":"P","config":{"calculationNodesTags":"${tag}"}}],
                   "criterion":{"type":"function","function":{"name":"C","config":{"calculationNodesTags":"old"}}}}]}}}""");

        ObjectNode out = WorkflowTemplating.applyTag(wf, "it-1234");

        assertThat(out.at("/states/a/transitions/0/processors/0/config/calculationNodesTags").asText()).isEqualTo("it-1234");
        assertThat(out.at("/states/a/transitions/0/criterion/function/config/calculationNodesTags").asText()).isEqualTo("it-1234");
        assertThat(wf.at("/states/a/transitions/0/processors/0/config/calculationNodesTags").asText()).isEqualTo("${tag}");
    }

    @Test
    void refusesAMissingOrEmptyTag() throws Exception {
        ObjectNode missing = (ObjectNode) om.readTree("""
                {"name":"w","states":{"a":{"transitions":[{"name":"t","next":"b","processors":[{"name":"P"}]}]}}}""");
        ObjectNode empty = (ObjectNode) om.readTree("""
                {"name":"w","states":{"a":{"transitions":[{"name":"t","next":"b",
                  "processors":[{"name":"P","config":{"calculationNodesTags":""}}]}]}}}""");

        assertThatThrownBy(() -> WorkflowTemplating.applyTag(missing, "t"))
                .hasMessageContaining("'P'").hasMessageContaining("calculationNodesTags");
        assertThatThrownBy(() -> WorkflowTemplating.applyTag(empty, "t"))
                .hasMessageContaining("matches every member");
    }
}
```

```java
package com.java_template.testing.cyoda;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** A forked Spring context would stay joined with the same tag and steal callouts (spec §6.1). */
class IntegrationTestHygieneTest {

    @Test
    void integrationTestsDeclareNoMockBeans() throws Exception {
        Path root = Path.of("src/integrationTest/java");
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> files = Files.walk(root)) {
            List<Path> offenders = files.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                try {
                    String src = Files.readString(p);
                    return src.contains("@MockitoBean") || src.contains("@MockBean");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
            assertThat(offenders).as("integration tests must not fork the Spring context with mock beans").isEmpty();
        }
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew test --tests com.java_template.testing.cyoda.WorkflowTemplatingTest --tests com.java_template.testing.cyoda.IntegrationTestHygieneTest`
Expected: `WorkflowTemplatingTest` FAILS to compile (`WorkflowTemplating` missing). `IntegrationTestHygieneTest` passes.

- [ ] **Step 3: Implement the fixtures**

```java
package com.java_template.testing.cyoda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Sets every processor's and function criterion's calculationNodesTags to the Spring context's
 * unique tag. A missing or empty tag is refused: an empty list matches every member of the
 * tenant, so another context's member could receive the callout (spec §6.1).
 */
public final class WorkflowTemplating {

    private static final ObjectMapper OM = new ObjectMapper();

    private WorkflowTemplating() {
    }

    public static ObjectNode load(String classpathResource, String tag) {
        try (InputStream in = WorkflowTemplating.class.getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalArgumentException("no classpath resource " + classpathResource);
            }
            return applyTag(OM.readTree(in), tag);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static ObjectNode applyTag(JsonNode workflow, String tag) {
        ObjectNode copy = workflow.deepCopy();
        String wf = copy.path("name").asText("?");
        copy.path("states").forEach(state -> state.path("transitions").forEach(t -> {
            t.path("processors").forEach(p -> retag(p, wf, "processor '" + p.path("name").asText() + "'", tag));
            JsonNode criterion = t.path("criterion");
            if ("function".equals(criterion.path("type").asText())) {
                JsonNode fn = criterion.path("function");
                retag(fn, wf, "criterion function '" + fn.path("name").asText() + "'", tag);
            }
        }));
        return copy;
    }

    private static void retag(JsonNode owner, String wf, String what, String tag) {
        JsonNode config = owner.get("config");
        JsonNode tags = config == null ? null : config.get("calculationNodesTags");
        if (tags == null) {
            throw new IllegalArgumentException("workflow '" + wf + "': " + what + " has no config.calculationNodesTags");
        }
        if (tags.asText().isBlank()) {
            throw new IllegalArgumentException("workflow '" + wf + "': " + what
                    + " has empty calculationNodesTags, which matches every member of the tenant");
        }
        ((ObjectNode) config).put("calculationNodesTags", tag);
    }
}
```

```java
package com.java_template.testing.cyoda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Minimal REST client for tier-1 assertions against cyoda-go (mock IAM: no Authorization header). */
public final class CyodaRest {

    public record Response(int status, JsonNode body, String raw) {
        public Response requireSuccess() {
            if (status < 200 || status >= 300) {
                throw new AssertionError("cyoda answered " + status + ": " + raw);
            }
            return this;
        }
    }

    private static final ObjectMapper OM = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String apiUrl;

    public CyodaRest(String apiUrl) {
        this.apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
    }

    public String apiUrl() {
        return apiUrl;
    }

    public Response get(String path) {
        return send("GET", path, null);
    }

    public Response post(String path, Object body) {
        return send("POST", path, body);
    }

    public Response put(String path, Object body) {
        return send("PUT", path, body);
    }

    public Response delete(String path) {
        return send("DELETE", path, null);
    }

    private Response send(String method, String path, Object body) {
        try {
            HttpRequest.BodyPublisher publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(OM.writeValueAsString(body));
            HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl + "/" + path))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .method(method, publisher)
                    .build();
            HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json = null;
            try {
                json = r.body() == null || r.body().isBlank() ? null : OM.readTree(r.body());
            } catch (IOException notJson) {
                // keep raw only
            }
            return new Response(r.statusCode(), json, r.body());
        } catch (IOException e) {
            throw new UncheckedIOException(method + " " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
```

```java
package com.java_template.testing.cyoda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Creates a model from sample data, imports its workflow and locks it (entities need a LOCKED model). */
public final class CyodaModelSetup {

    private static final ObjectMapper OM = new ObjectMapper();

    private CyodaModelSetup() {
    }

    public static void createModel(CyodaRest rest, String model, int version, JsonNode sampleData, JsonNode workflow) {
        rest.post("model/import/JSON/SAMPLE_DATA/" + model + "/" + version, sampleData).requireSuccess();
        importWorkflow(rest, model, version, workflow);
        rest.put("model/" + model + "/" + version + "/lock", null).requireSuccess();
    }

    public static void importWorkflow(CyodaRest rest, String model, int version, JsonNode workflow) {
        ObjectNode body = OM.createObjectNode();
        body.put("importMode", "REPLACE");
        body.putArray("workflows").add(workflow);
        rest.post("model/" + model + "/" + version + "/workflow/import", body).requireSuccess();
    }
}
```

```java
package com.java_template.testing.cyoda;

import com.java_template.common.grpc.client.monitoring.ConnectionStateTracker;
import com.java_template.common.grpc.client.monitoring.ObserverState;

import java.time.Duration;

public final class CyodaAwait {

    private CyodaAwait() {
    }

    /** Waits for the greet (ObserverState.READY) so the first transition cannot hit NO_COMPUTE_MEMBER_FOR_TAG. */
    public static void memberReady(ConnectionStateTracker tracker, Duration limit) {
        long deadline = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < deadline) {
            if (tracker.getLastObserverState() == ObserverState.READY) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("compute member not READY within " + limit + " (state: " + tracker.getLastObserverState() + ")");
    }
}
```

Complete `CyodaIntegrationTest`:

```java
package com.java_template.testing.cyoda;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.annotation.*;

/**
 * Boots the application (found by @SpringBootConfiguration search) against a cyoda-go
 * subprocess for the given profile, with auth-mode=none and a context-unique processor tag.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@ExtendWith(CyodaServerExtension.class)
@SpringBootTest
public @interface CyodaIntegrationTest {
    String profile() default CyodaProfiles.DEFAULT;
}
```

```java
package com.java_template.testing.cyoda;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.TestContextAnnotationUtils;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Points the Spring context at the profile's cyoda server (registered in META-INF/spring.factories). */
public class CyodaContextCustomizerFactory implements ContextCustomizerFactory {

    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass, List<ContextConfigurationAttributes> attrs) {
        CyodaIntegrationTest ann = TestContextAnnotationUtils.findMergedAnnotation(testClass, CyodaIntegrationTest.class);
        return ann == null ? null : new Customizer(ann.profile());
    }

    static final class Customizer implements ContextCustomizer {
        private final String profile;

        Customizer(String profile) {
            this.profile = profile;
        }

        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration merged) {
            CyodaServer server = CyodaTestEnvironment.server(CyodaProfiles.byName(profile));
            String tag = "it-" + UUID.randomUUID().toString().substring(0, 8);
            TestPropertyValues.of(
                    "app.config.cyoda-api-url=" + server.apiUrl(),
                    "app.config.grpc-address=" + server.grpcHost(),
                    "app.config.grpc-server-port=" + server.grpcPort(),
                    "app.config.grpc-tls=false",
                    "app.config.auth-mode=none",
                    "app.config.grpc-processor-tag=" + tag,
                    "server.port=0"
            ).applyTo(context);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Customizer c && c.profile.equals(profile);
        }

        @Override
        public int hashCode() {
            return Objects.hash(profile);
        }
    }
}
```

`src/testFixtures/resources/META-INF/spring.factories`:

```
org.springframework.test.context.ContextCustomizerFactory=\
com.java_template.testing.cyoda.CyodaContextCustomizerFactory
```

- [ ] **Step 4: Create the integration-test domain fixtures**

`src/integrationTest/java/com/java_template/it/support/ItThing.java`:

```java
package com.java_template.it.support;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_template.common.workflow.CyodaEntity;
import com.java_template.common.workflow.OperationSpecification;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.cyoda.cloud.api.event.common.ModelSpec;

import java.util.List;

/** Test entity whose model name is chosen per test class, so tests never share a model. */
@Data
@NoArgsConstructor
public class ItThing implements CyodaEntity {

    @JsonIgnore
    private String model;
    private String name;
    private Integer amount;
    private List<String> tags;
    private String note;
    private String ref;

    public static ItThing of(String model, String name, int amount) {
        ItThing t = new ItThing();
        t.model = model;
        t.name = name;
        t.amount = amount;
        t.tags = List.of("t");
        t.note = "";
        t.ref = "";
        return t;
    }

    public ItThing in(String modelName) {
        this.model = modelName;
        return this;
    }

    @Override
    public OperationSpecification getModelKey() {
        return new OperationSpecification.Entity(new ModelSpec().withName(model).withVersion(1), "ItThing");
    }

    public static JsonNode sampleData() {
        try {
            return new ObjectMapper().readTree("""
                    {"name":"sample","amount":1,"tags":["a","b"],"note":"n","ref":"r"}""");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
```

`ItRecordingProcessor.java`:

```java
package com.java_template.it.support;

import com.java_template.common.serializer.ProcessorSerializer;
import com.java_template.common.serializer.SerializerFactory;
import com.java_template.common.workflow.CyodaEventContext;
import com.java_template.common.workflow.CyodaProcessor;
import com.java_template.common.workflow.OperationSpecification;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationRequest;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationResponse;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ItRecordingProcessor implements CyodaProcessor {

    private final ProcessorSerializer serializer;
    private final Set<UUID> processed = ConcurrentHashMap.newKeySet();

    public ItRecordingProcessor(SerializerFactory serializerFactory) {
        this.serializer = serializerFactory.getDefaultProcessorSerializer();
    }

    @Override
    public EntityProcessorCalculationResponse process(CyodaEventContext<EntityProcessorCalculationRequest> context) {
        return serializer.withRequest(context.getEvent())
                .toEntityWithMetadata(ItThing.class)
                .map(ctx -> {
                    ctx.entityResponse().entity().setNote("processed");
                    processed.add(ctx.entityResponse().getId());
                    return ctx.entityResponse();
                })
                .complete();
    }

    @Override
    public boolean supports(OperationSpecification spec) {
        return "ItRecordingProcessor".equals(spec.operationName());
    }

    public boolean hasProcessed(UUID entityId) {
        return processed.contains(entityId);
    }
}
```

`ItFailingProcessor.java`:

```java
package com.java_template.it.support;

import com.java_template.common.serializer.ProcessorSerializer;
import com.java_template.common.serializer.SerializerFactory;
import com.java_template.common.workflow.CyodaEventContext;
import com.java_template.common.workflow.CyodaProcessor;
import com.java_template.common.workflow.OperationSpecification;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationRequest;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationResponse;
import org.springframework.stereotype.Component;

@Component
public class ItFailingProcessor implements CyodaProcessor {

    private final ProcessorSerializer serializer;

    public ItFailingProcessor(SerializerFactory serializerFactory) {
        this.serializer = serializerFactory.getDefaultProcessorSerializer();
    }

    @Override
    public EntityProcessorCalculationResponse process(CyodaEventContext<EntityProcessorCalculationRequest> context) {
        return serializer.withRequest(context.getEvent())
                .toEntityWithMetadata(ItThing.class)
                .map(ctx -> {
                    throw new IllegalStateException("it-failure");
                })
                .complete();
    }

    @Override
    public boolean supports(OperationSpecification spec) {
        return "ItFailingProcessor".equals(spec.operationName());
    }
}
```

`ItFlagCriterion.java`:

```java
package com.java_template.it.support;

import com.java_template.common.serializer.CriterionSerializer;
import com.java_template.common.serializer.EvaluationOutcome;
import com.java_template.common.serializer.SerializerFactory;
import com.java_template.common.workflow.CyodaCriterion;
import com.java_template.common.workflow.CyodaEventContext;
import com.java_template.common.workflow.OperationSpecification;
import org.cyoda.cloud.api.event.processing.EntityCriteriaCalculationRequest;
import org.cyoda.cloud.api.event.processing.EntityCriteriaCalculationResponse;
import org.springframework.stereotype.Component;

/** Matches when amount >= 10; otherwise refuses with reason "amount below 10". */
@Component
public class ItFlagCriterion implements CyodaCriterion {

    private final CriterionSerializer serializer;

    public ItFlagCriterion(SerializerFactory serializerFactory) {
        this.serializer = serializerFactory.getDefaultCriteriaSerializer();
    }

    @Override
    public EntityCriteriaCalculationResponse check(CyodaEventContext<EntityCriteriaCalculationRequest> context) {
        return serializer.withRequest(context.getEvent())
                .evaluateEntity(ItThing.class, ctx -> ctx.entityWithMetadata().entity().getAmount() >= 10
                        ? EvaluationOutcome.success()
                        : EvaluationOutcome.fail("amount below 10"))
                .complete();
    }

    @Override
    public boolean supports(OperationSpecification spec) {
        return "ItFlagCriterion".equals(spec.operationName());
    }
}
```

`ExampleAppScan.java` (brings the example application's beans into the integration-test context):

```java
package com.java_template.it.support;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan("com.example.application")
public class ExampleAppScan {
}
```

`src/integrationTest/resources/it-workflows/thing-workflow.json`:

```json
{
  "version": "1.5",
  "name": "it-thing",
  "desc": "Tier-1 integration workflow",
  "initialState": "none",
  "active": true,
  "states": {
    "none": {
      "transitions": [{"name": "to_new", "next": "new", "manual": false}]
    },
    "new": {
      "transitions": [
        {
          "name": "process", "next": "processed", "manual": true,
          "processors": [{"type": "externalized", "name": "ItRecordingProcessor", "executionMode": "SYNC",
            "config": {"attachEntity": true, "calculationNodesTags": "${tag}", "responseTimeoutMs": 10000, "retryPolicy": "NONE"}}]
        },
        {
          "name": "fail", "next": "failed", "manual": true,
          "processors": [{"type": "externalized", "name": "ItFailingProcessor", "executionMode": "SYNC",
            "config": {"attachEntity": true, "calculationNodesTags": "${tag}", "responseTimeoutMs": 10000, "retryPolicy": "NONE"}}]
        },
        {
          "name": "approve", "next": "approved", "manual": true,
          "criterion": {"type": "function", "function": {"name": "ItFlagCriterion",
            "config": {"attachEntity": true, "calculationNodesTags": "${tag}", "responseTimeoutMs": 10000, "retryPolicy": "NONE"}}}
        }
      ]
    },
    "processed": {"transitions": []},
    "failed": {"transitions": []},
    "approved": {"transitions": []}
  }
}
```

- [ ] **Step 5: Write `ComputeMemberJoinIT`**

```java
package com.java_template.it;

import com.java_template.common.config.Config;
import com.java_template.common.dto.EntityWithMetadata;
import com.java_template.common.grpc.client.monitoring.ConnectionStateTracker;
import com.java_template.common.service.EntityService;
import com.java_template.it.support.ItRecordingProcessor;
import com.java_template.it.support.ItThing;
import com.java_template.testing.cyoda.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@CyodaIntegrationTest(profile = CyodaProfiles.KEEPALIVE_SHORT)
class ComputeMemberJoinIT {

    @Autowired ConnectionStateTracker tracker;
    @Autowired EntityService entityService;
    @Autowired Config config;
    @Autowired ItRecordingProcessor recorder;

    @Test
    void staysJoinedAcrossIdlePeriodsLongerThanTheEvictionWindow() throws Exception {
        CyodaAwait.memberReady(tracker, Duration.ofSeconds(20));
        String model = "join_it_" + UUID.randomUUID().toString().substring(0, 8);
        CyodaModelSetup.createModel(CyodaTestEnvironment.rest(CyodaProfiles.byName(CyodaProfiles.KEEPALIVE_SHORT)),
                model, 1, ItThing.sampleData(), WorkflowTemplating.load("/it-workflows/thing-workflow.json", config.getGrpcProcessorTag()));

        Thread.sleep(10_000); // more than 3× the 3 s eviction window of this profile

        EntityWithMetadata<ItThing> created = entityService.create(ItThing.of(model, "a", 1));
        EntityWithMetadata<ItThing> processed = entityService.update(created.getId(), created.entity().in(model), "process");

        assertThat(processed.getState()).isEqualTo("processed");
        assertThat(processed.entity().getNote()).isEqualTo("processed");
        assertThat(recorder.hasProcessed(created.getId())).isTrue();
    }
}
```

- [ ] **Step 6: Run the unit tests and the IT**

```bash
./gradlew test --tests 'com.java_template.testing.cyoda.*'
./gradlew integrationTest -Dcyoda.bin=build/cyoda-bin/cyoda --tests com.java_template.it.ComputeMemberJoinIT
```

Expected: PASS. If the IT fails, attach `build/cyoda-logs/keepalive-short-*.log` to your analysis before changing anything. A `NO_COMPUTE_MEMBER_FOR_TAG` there means the member was evicted, which is exactly what this test exists to catch.

- [ ] **Step 7: Commit**

```bash
git add src/testFixtures src/test/java/com/java_template/testing src/integrationTest
git commit -m "test(harness): @CyodaIntegrationTest wiring, tag templating, fixtures and ComputeMemberJoinIT

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 14: `EntityCrudIT` and `SearchIT`

**Files:**
- Test: `src/integrationTest/java/com/java_template/it/EntityCrudIT.java`
- Test: `src/integrationTest/java/com/java_template/it/SearchIT.java`

**Interfaces:**
- Consumes: the Task 13 fixtures, `EntityService`, `SearchAndRetrievalParams`, and the generated condition DTOs (Task 5).

Spec correction: §6.2 lists "function" among `SearchIT`'s condition types. A function condition cannot be expressed in `EntityService.search` (the nested-condition union has four members), and cyoda-go rejects it in search (`docs/cloud-parity/function-condition-search-rejection.md`). `SearchIT` covers simple, group, lifecycle and array.

- [ ] **Step 1: Write `EntityCrudIT`**

```java
package com.java_template.it;

import com.java_template.common.config.Config;
import com.java_template.common.dto.EntityWithMetadata;
import com.java_template.common.grpc.client.monitoring.ConnectionStateTracker;
import com.java_template.common.service.EntityService;
import com.java_template.it.support.ItThing;
import com.java_template.testing.cyoda.*;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@CyodaIntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EntityCrudIT {

    @Autowired EntityService entityService;
    @Autowired Config config;
    @Autowired ConnectionStateTracker tracker;

    private String model;
    private ModelSpec spec;

    @BeforeAll
    void createModel() {
        CyodaAwait.memberReady(tracker, Duration.ofSeconds(20));
        model = "crud_it_" + UUID.randomUUID().toString().substring(0, 8);
        spec = new ModelSpec().withName(model).withVersion(1);
        CyodaModelSetup.createModel(CyodaTestEnvironment.rest(Profile.mockMemory()), model, 1, ItThing.sampleData(),
                WorkflowTemplating.load("/it-workflows/thing-workflow.json", config.getGrpcProcessorTag()));
    }

    @Test
    void createThenGet() {
        EntityWithMetadata<ItThing> created = entityService.create(ItThing.of(model, "one", 3));

        EntityWithMetadata<ItThing> read = entityService.getById(created.getId(), spec, ItThing.class);

        assertThat(read.entity().getName()).isEqualTo("one");
        assertThat(read.entity().getAmount()).isEqualTo(3);
        assertThat(read.getState()).isEqualTo("new");
    }

    @Test
    void bulkCreateAndGetAll() {
        String bulkModel = model;
        List<EntityWithMetadata<ItThing>> saved = entityService.save(List.of(
                ItThing.of(bulkModel, "b1", 1), ItThing.of(bulkModel, "b2", 2), ItThing.of(bulkModel, "b3", 3)));

        assertThat(saved).hasSize(3).allSatisfy(e -> assertThat(e.getId()).isNotNull());
        assertThat(entityService.findAll(spec, ItThing.class).data())
                .extracting(e -> e.entity().getName()).contains("b1", "b2", "b3");
    }

    @Test
    void loopbackUpdatePersistsData() {
        EntityWithMetadata<ItThing> created = entityService.create(ItThing.of(model, "before", 1));
        ItThing changed = created.entity().in(model);
        changed.setName("after");

        EntityWithMetadata<ItThing> updated = entityService.update(created.getId(), changed, null);

        assertThat(updated.entity().getName()).isEqualTo("after");
        assertThat(updated.getState()).isEqualTo("new");
    }

    @Test
    void updateWithTransitionRunsTheProcessor() {
        EntityWithMetadata<ItThing> created = entityService.create(ItThing.of(model, "p", 1));

        EntityWithMetadata<ItThing> processed = entityService.update(created.getId(), created.entity().in(model), "process");

        assertThat(processed.getState()).isEqualTo("processed");
        assertThat(processed.entity().getNote()).isEqualTo("processed");
    }

    @Test
    void deleteById() {
        EntityWithMetadata<ItThing> created = entityService.create(ItThing.of(model, "gone", 1));

        entityService.deleteById(created.getId());

        assertThatThrownBy(() -> entityService.getById(created.getId(), spec, ItThing.class))
                .hasStackTraceContaining("ENTITY_NOT_FOUND");
    }

    @Test
    void deleteAllReturnsTheCount() {
        String other = model + "_del";
        CyodaModelSetup.createModel(CyodaTestEnvironment.rest(Profile.mockMemory()), other, 1, ItThing.sampleData(),
                WorkflowTemplating.load("/it-workflows/thing-workflow.json", config.getGrpcProcessorTag()));
        entityService.save(List.of(ItThing.of(other, "x", 1), ItThing.of(other, "y", 2)));

        Integer deleted = entityService.deleteAll(new ModelSpec().withName(other).withVersion(1));

        assertThat(deleted).isEqualTo(2);
    }
}
```

- [ ] **Step 2: Write `SearchIT`**

```java
package com.java_template.it;

import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import com.java_template.common.config.Config;
import com.java_template.common.dto.EntityWithMetadata;
import com.java_template.common.dto.PageResult;
import com.java_template.common.grpc.client.monitoring.ConnectionStateTracker;
import com.java_template.common.repository.SearchAndRetrievalParams;
import com.java_template.common.service.EntityService;
import com.java_template.it.support.ItThing;
import com.java_template.testing.cyoda.*;
import org.cyoda.cloud.api.common.model.*;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@CyodaIntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchIT {

    @Autowired EntityService entityService;
    @Autowired Config config;
    @Autowired ConnectionStateTracker tracker;

    private ModelSpec spec;
    private String model;

    @BeforeAll
    void seed() {
        CyodaAwait.memberReady(tracker, Duration.ofSeconds(20));
        model = "search_it_" + UUID.randomUUID().toString().substring(0, 8);
        spec = new ModelSpec().withName(model).withVersion(1);
        CyodaModelSetup.createModel(CyodaTestEnvironment.rest(Profile.mockMemory()), model, 1, ItThing.sampleData(),
                WorkflowTemplating.load("/it-workflows/thing-workflow.json", config.getGrpcProcessorTag()));
        List<ItThing> things = new ArrayList<>();
        IntStream.rangeClosed(1, 5).forEach(i -> {
            ItThing t = ItThing.of(model, "n" + i, i);
            t.setTags(List.of(i % 2 == 0 ? "even" : "odd", "x"));
            things.add(t);
        });
        entityService.save(things);
    }

    private SearchAndRetrievalParams direct() {
        return SearchAndRetrievalParams.builder().inMemory(true).pageSize(1000).build();
    }

    private List<Integer> amounts(PageResult<EntityWithMetadata<ItThing>> page) {
        return page.data().stream().map(e -> e.entity().getAmount()).sorted().toList();
    }

    private static GroupConditionDto and(GroupConditionDtoAllOfConditions... conditions) {
        return new GroupConditionDto().operator(GroupConditionDto.OperatorEnum.AND).conditions(List.of(conditions));
    }

    @Test
    void simpleCondition() {
        var gt3 = new SimpleConditionDto().jsonPath("$.amount")
                .operatorType(SimpleConditionDto.OperatorTypeEnum.GREATER_THAN).value(IntNode.valueOf(3));

        assertThat(amounts(entityService.search(spec, and(gt3), ItThing.class, direct()))).containsExactly(4, 5);
    }

    @Test
    void nestedGroupWithOr() {
        var one = new SimpleConditionDto().jsonPath("$.amount")
                .operatorType(SimpleConditionDto.OperatorTypeEnum.EQUALS).value(IntNode.valueOf(1));
        var five = new SimpleConditionDto().jsonPath("$.amount")
                .operatorType(SimpleConditionDto.OperatorTypeEnum.EQUALS).value(IntNode.valueOf(5));
        var or = new GroupConditionDto().operator(GroupConditionDto.OperatorEnum.OR).conditions(List.of(one, five));

        assertThat(amounts(entityService.search(spec, and(or), ItThing.class, direct()))).containsExactly(1, 5);
    }

    @Test
    void lifecycleCondition() {
        var isNew = new LifecycleConditionDto().field("state")
                .operatorType(LifecycleConditionDto.OperatorTypeEnum.EQUALS).value(TextNode.valueOf("new"));

        assertThat(entityService.search(spec, and(isNew), ItThing.class, direct()).data()).hasSize(5);
    }

    @Test
    void arrayConditionWithPositionalValues() {
        var evenFirst = new ArrayConditionDto().jsonPath("$.tags[*]")
                .values(List.of(TextNode.valueOf("even"), JsonNodeFactory.instance.nullNode()));

        assertThat(amounts(entityService.search(spec, and(evenFirst), ItThing.class, direct()))).containsExactly(2, 4);
    }

    @Test
    void pagedSnapshotSearchAcrossThreePages() {
        String paged = model + "_paged";
        CyodaModelSetup.createModel(CyodaTestEnvironment.rest(Profile.mockMemory()), paged, 1, ItThing.sampleData(),
                WorkflowTemplating.load("/it-workflows/thing-workflow.json", config.getGrpcProcessorTag()));
        entityService.save(IntStream.range(0, 250).mapToObj(i -> ItThing.of(paged, "p" + i, i)).toList());
        ModelSpec pagedSpec = new ModelSpec().withName(paged).withVersion(1);

        PageResult<EntityWithMetadata<ItThing>> first = entityService.search(pagedSpec, and(), ItThing.class,
                SearchAndRetrievalParams.builder().pageSize(100).pageNumber(0).build());
        PageResult<EntityWithMetadata<ItThing>> second = entityService.search(pagedSpec, and(), ItThing.class,
                SearchAndRetrievalParams.builder().pageSize(100).pageNumber(1).searchId(first.searchId()).build());
        PageResult<EntityWithMetadata<ItThing>> third = entityService.search(pagedSpec, and(), ItThing.class,
                SearchAndRetrievalParams.builder().pageSize(100).pageNumber(2).searchId(first.searchId()).build());

        assertThat(first.totalElements()).isEqualTo(250);
        assertThat(first.data()).hasSize(100);
        assertThat(second.data()).hasSize(100);
        assertThat(third.data()).hasSize(50);
    }

    @Test
    void pointInTimeSeesThePastValue() throws Exception {
        EntityWithMetadata<ItThing> created = entityService.create(ItThing.of(model, "pit", 7));
        Thread.sleep(50);
        Date before = new Date();
        Thread.sleep(50);
        ItThing changed = created.entity().in(model);
        changed.setName("pit-changed");
        entityService.update(created.getId(), changed, null);

        EntityWithMetadata<ItThing> past = entityService.getById(created.getId(), spec, ItThing.class, before);

        assertThat(past.entity().getName()).isEqualTo("pit");
    }
}
```

`ArrayConditionDto.values(List<JsonNode>)` is the setter generated after the #627 patch (Task 5, Step 2 checked `private List<JsonNode> values`).

- [ ] **Step 3: Run the ITs**

Run: `./gradlew integrationTest -Dcyoda.bin=build/cyoda-bin/cyoda --tests com.java_template.it.EntityCrudIT --tests com.java_template.it.SearchIT`
Expected: PASS (6 + 6 tests).

A failure means either the framework disagrees with cyoda-go (fix the framework) or cyoda-go has a defect. In the second case, stop and report it; the fix is an issue in `Cyoda/cyoda-go` per the Global Constraints, never a test workaround.

- [ ] **Step 4: Commit**

```bash
git add src/integrationTest
git commit -m "test(it): entity CRUD and search against in-memory cyoda-go

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 15: `ProcessorIT` and `CriterionIT`

**Files:**
- Test: `src/integrationTest/java/com/java_template/it/ProcessorIT.java`
- Test: `src/integrationTest/java/com/java_template/it/CriterionIT.java`

**Interfaces:**
- Consumes: the Task 13 fixtures (the `process`, `fail` and `approve` transitions; `ItFlagCriterion`'s reason `amount below 10`).

- [ ] **Step 1: Write the tests**

```java
package com.java_template.it;

import com.java_template.common.config.Config;
import com.java_template.common.dto.EntityWithMetadata;
import com.java_template.common.grpc.client.monitoring.ConnectionStateTracker;
import com.java_template.common.service.EntityService;
import com.java_template.it.support.ItThing;
import com.java_template.testing.cyoda.*;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@CyodaIntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProcessorIT {

    @Autowired EntityService entityService;
    @Autowired Config config;
    @Autowired ConnectionStateTracker tracker;

    private String model;
    private ModelSpec spec;

    @BeforeAll
    void createModel() {
        CyodaAwait.memberReady(tracker, Duration.ofSeconds(20));
        model = "proc_it_" + UUID.randomUUID().toString().substring(0, 8);
        spec = new ModelSpec().withName(model).withVersion(1);
        CyodaModelSetup.createModel(CyodaTestEnvironment.rest(Profile.mockMemory()), model, 1, ItThing.sampleData(),
                WorkflowTemplating.load("/it-workflows/thing-workflow.json", config.getGrpcProcessorTag()));
    }

    @Test
    void theProcessorsChangePersistsWithTheTransition() {
        EntityWithMetadata<ItThing> created = entityService.create(ItThing.of(model, "p", 1));

        entityService.update(created.getId(), created.entity().in(model), "process");

        EntityWithMetadata<ItThing> read = entityService.getById(created.getId(), spec, ItThing.class);
        assertThat(read.getState()).isEqualTo("processed");
        assertThat(read.entity().getNote()).isEqualTo("processed");
    }

    @Test
    void aFailingProcessorRollsTheTransitionBackAndSurfacesTheError() {
        EntityWithMetadata<ItThing> created = entityService.create(ItThing.of(model, "f", 1));

        assertThatThrownBy(() -> entityService.update(created.getId(), created.entity().in(model), "fail"))
                .hasStackTraceContaining("it-failure");

        EntityWithMetadata<ItThing> read = entityService.getById(created.getId(), spec, ItThing.class);
        assertThat(read.getState()).isEqualTo("new");
        assertThat(read.entity().getNote()).isEmpty();
    }
}
```

```java
package com.java_template.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_template.common.config.Config;
import com.java_template.common.dto.EntityWithMetadata;
import com.java_template.common.grpc.client.monitoring.ConnectionStateTracker;
import com.java_template.common.service.EntityService;
import com.java_template.it.support.ItThing;
import com.java_template.testing.cyoda.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@CyodaIntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CriterionIT {

    @Autowired EntityService entityService;
    @Autowired Config config;
    @Autowired ConnectionStateTracker tracker;
    @Autowired ObjectMapper objectMapper;

    private String model;
    private CyodaRest rest;

    @BeforeAll
    void createModel() {
        CyodaAwait.memberReady(tracker, Duration.ofSeconds(20));
        model = "crit_it_" + UUID.randomUUID().toString().substring(0, 8);
        rest = CyodaTestEnvironment.rest(Profile.mockMemory());
        CyodaModelSetup.createModel(rest, model, 1, ItThing.sampleData(),
                WorkflowTemplating.load("/it-workflows/thing-workflow.json", config.getGrpcProcessorTag()));
    }

    @Test
    void aMatchingCriterionLetsTheTransitionThrough() {
        EntityWithMetadata<ItThing> created = entityService.create(ItThing.of(model, "big", 20));

        EntityWithMetadata<ItThing> approved = entityService.update(created.getId(), created.entity().in(model), "approve");

        assertThat(approved.getState()).isEqualTo("approved");
    }

    @Test
    void aRefusingCriterionAnswers400WithItsReasonOverRest() {
        EntityWithMetadata<ItThing> created = entityService.create(ItThing.of(model, "small", 5));

        CyodaRest.Response r = rest.put("entity/JSON/" + created.getId() + "/approve",
                objectMapper.valueToTree(created.entity()));

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.raw()).contains("WORKFLOW_FAILED").contains("amount below 10");
    }

    @Test
    void aRefusingCriterionSurfacesCodeAndReasonThroughEntityService() {
        EntityWithMetadata<ItThing> created = entityService.create(ItThing.of(model, "small2", 5));

        assertThatThrownBy(() -> entityService.update(created.getId(), created.entity().in(model), "approve"))
                .hasStackTraceContaining("WORKFLOW_FAILED")
                .hasStackTraceContaining("amount below 10");
    }
}
```

- [ ] **Step 2: Run the ITs**

Run: `./gradlew integrationTest -Dcyoda.bin=build/cyoda-bin/cyoda --tests com.java_template.it.ProcessorIT --tests com.java_template.it.CriterionIT`
Expected: PASS (2 + 3 tests).

- [ ] **Step 3: Commit**

```bash
git add src/integrationTest
git commit -m "test(it): processors and criteria against in-memory cyoda-go

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 16: `ModelAndWorkflowSetupIT`

**Files:**
- Create: `src/integrationTest/resources/entity-schemas/examples/ExampleEntity/example-entity.json`
- Test: `src/integrationTest/java/com/java_template/it/ModelAndWorkflowSetupIT.java`

**Interfaces:**
- Consumes:
  - `CyodaInit.initCyoda(CyodaInitConfig)`, where `CyodaInitConfig` has `setRecreateModels(boolean)` and `setWorkflowDir(String)`;
  - `CyodaJackson`;
  - the generated `WorkflowConfigurationDto`;
  - the Task 13 fixtures.

- [ ] **Step 1: Add example data for `ExampleEntity`**

`src/integrationTest/resources/entity-schemas/examples/ExampleEntity/example-entity.json`:

```json
{
  "exampleId": "EX-1",
  "name": "Example",
  "amount": 10.5,
  "quantity": 2,
  "description": "sample",
  "items": [{"itemId": "i1", "itemName": "Item", "price": 1.5, "qty": 1, "category": "c", "itemTotal": 1.5}],
  "contact": {"name": "n", "email": "e@example.com", "phone": "1", "address": {"line1": "l", "city": "c", "postcode": "p", "country": "x"}},
  "createdAt": "2026-01-01T00:00:00",
  "updatedAt": "2026-01-01T00:00:00"
}
```

If `ExampleEntity.ExampleAddress` has different field names, copy them from `src/test/java/com/example/application/entity/example_entity/version_1/ExampleEntity.java`. The sample must use the entity's real fields.

- [ ] **Step 2: Write the test**

```java
package com.java_template.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.java_template.common.config.Config;
import com.java_template.common.config.CyodaJackson;
import com.java_template.common.tool.CyodaInit;
import com.java_template.common.tool.CyodaInitConfig;
import com.java_template.testing.cyoda.*;
import org.cyoda.cloud.api.common.model.WorkflowConfigurationDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The only test that touches the example application's models (ExampleEntity, OtherEntity);
 * CyodaInit derives model names from entity classes, so isolation is by ownership, not by name.
 */
@CyodaIntegrationTest
@TestPropertySource(properties = "app.config.entity-base-package=com.example.application")
class ModelAndWorkflowSetupIT {

    @Autowired CyodaInit cyodaInit;
    @Autowired Config config;

    private final ObjectMapper om = CyodaJackson.configure(new ObjectMapper());

    @Test
    void cyodaInitImportsSampleDataLocksTheModelAndImportsTheWorkflow(@TempDir Path workflowDir) throws Exception {
        ObjectNode workflow = WorkflowTemplating.load("/example/config/workflow/template_workflow.json", config.getGrpcProcessorTag());
        Path file = workflowDir.resolve("ExampleEntity/version_1/ExampleEntity.json");
        Files.createDirectories(file.getParent());
        om.writeValue(file.toFile(), workflow);
        CyodaInitConfig init = CyodaInitConfig.withRecreateModels(true);
        init.setWorkflowDir(workflowDir.toString());

        cyodaInit.initCyoda(init);

        CyodaRest rest = CyodaTestEnvironment.rest(Profile.mockMemory());
        JsonNode models = rest.get("model/").requireSuccess().body();
        assertThat(models.toString()).contains("ExampleEntity").contains("LOCKED");

        JsonNode exported = rest.get("model/ExampleEntity/1/workflow/export").requireSuccess().body();
        WorkflowConfigurationDto imported = om.treeToValue(workflow, WorkflowConfigurationDto.class);
        WorkflowConfigurationDto roundTripped = om.treeToValue(exported.get("workflows").get(0), WorkflowConfigurationDto.class);
        assertThat(roundTripped).isEqualTo(imported);
    }

    @Test
    void everyExampleWorkflowImports() throws Exception {
        CyodaRest rest = CyodaTestEnvironment.rest(Profile.mockMemory());
        Path dir = Path.of("src/test/resources/example/config/workflow");
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                String model = "wf_it_" + UUID.randomUUID().toString().substring(0, 8);
                rest.post("model/import/JSON/SAMPLE_DATA/" + model + "/1", om.readTree("{\"sampleFieldA\":\"a\"}")).requireSuccess();

                CyodaModelSetup.importWorkflow(rest, model, 1,
                        WorkflowTemplating.applyTag(om.readTree(f.toFile()), config.getGrpcProcessorTag()));
            }
        }
    }
}
```

Two cases need adjusting as you implement:
- **Exported workflow shape.** If the export's JSON wraps workflows differently from `{"workflows":[…]}`, read the shape from `cyoda help models` / `cyoda help workflows` and adjust the `exported.get(…)` path. The equality assertion itself is the contract and stays unchanged.
- **Sample data vs. workflow paths.** If `everyExampleWorkflowImports` fails because the sample data lacks a field the workflow's conditions reference, add that field to the sample.

- [ ] **Step 3: Run the IT**

Run: `./gradlew integrationTest -Dcyoda.bin=build/cyoda-bin/cyoda --tests com.java_template.it.ModelAndWorkflowSetupIT`
Expected: PASS (2 tests).

- [ ] **Step 4: Commit**

```bash
git add src/integrationTest
git commit -m "test(it): CyodaInit model/workflow setup round-trips through the generated DTOs

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 17: Remove Cucumber, CI, sync guide, and local-run verification (PR 1 wrap-up)

**Files:**
- Modify: `build.gradle` (remove Cucumber; Jacoco on `test` + `integrationTest`; drop `test { exclude … }`)
- Delete: `src/test/java/e2e/`, `src/test/resources/features/`, `src/test/resources/application-cucumber.yaml`, `src/test/resources/workflows/workflow_with_processor.json`
- Modify: `.github/workflows/build.yml`
- Modify: `SYNCING_WITH_JAVA_TEMPLATE.md`

- [ ] **Step 1: Remove Cucumber from the build**

In `build.gradle`:
- delete `id 'se.thinkcode.cucumber-runner' version '0.0.11'`;
- delete the `task cucumberTest(type: Test) { … }` block;
- delete the three `io.cucumber:*` dependencies;
- delete `exclude '**/GherkinE2eTest.class'` from `test { }`;
- replace the `jacocoTestReport { … }` block with:

```groovy
jacocoTestReport {
    dependsOn tasks.named('test'), tasks.named('integrationTest')
    executionData.from = files(
            layout.buildDirectory.file('jacoco/test.exec'),
            layout.buildDirectory.file('jacoco/integrationTest.exec'))
    reports.xml.required = true
}
```

Then delete the Cucumber sources and resources:

```bash
git rm -r src/test/java/e2e src/test/resources/features src/test/resources/application-cucumber.yaml src/test/resources/workflows/workflow_with_processor.json
```

- [ ] **Step 2: Update CI**

In `.github/workflows/build.yml`:

1. **Triggers.** Replace the `on:` block with:

```yaml
on:
  push:
  pull_request:
  workflow_dispatch:
    inputs:
      branch:
        description: 'Branch to build'
        required: true
        default: 'main'
        type: string
      build_type:
        description: 'Type of build to perform'
        required: false
        default: 'standard'
        type: choice
        options:
          - compile-only
          - test-only
          - workflow-validation
          - standard
          - workflow-import
          - both
      tracker_id:
        description: 'Unique tracker ID for identifying this run'
        required: false
        type: string
```

2. **Job env and checkout.** Under `jobs.build`, add:

```yaml
    env:
      BUILD_TYPE: ${{ inputs.build_type || 'standard' }}
      BRANCH: ${{ inputs.branch || github.ref_name }}
```

   Change the checkout `ref:` to `${{ env.BRANCH }}`. Replace every `inputs.build_type` in `if:` conditions and in the summary with `env.BUILD_TYPE`, and every `inputs.branch` with `env.BRANCH`.

3. **Compile-only.** Change `./gradlew clean compileJava compileTestJava --continue --console=plain -i` to:

```yaml
          ./gradlew clean compileJava compileTestJava compileTestFixturesJava compileIntegrationTestJava --continue --console=plain -i
```

4. **Tests.** Replace the `Run tests` step with:

```yaml
      - name: Set up Go (source build of the pinned cyoda)
        if: ${{ env.BUILD_TYPE == 'standard' || env.BUILD_TYPE == 'test-only' }}
        uses: actions/setup-go@v5
        with:
          go-version: '1.26.7'

      - name: Install pinned cyoda
        if: ${{ env.BUILD_TYPE == 'standard' || env.BUILD_TYPE == 'test-only' }}
        run: echo "CYODA_BIN=$(scripts/install-cyoda.sh)" >> "$GITHUB_ENV"

      - name: Run tests (unit + tier-1 integration)
        if: ${{ env.BUILD_TYPE == 'standard' || env.BUILD_TYPE == 'test-only' }}
        run: ./gradlew check

      - name: Upload cyoda logs
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: cyoda-logs-${{ github.run_id }}
          path: build/cyoda-logs/
          retention-days: 7
```

5. **Reports.** In the two "Upload test …" steps, add `build/test-results/integrationTest/` and `build/reports/tests/integrationTest/` to `path:`.

- [ ] **Step 3: Record the PR 1 breaking changes in the sync guide**

Append to `SYNCING_WITH_JAVA_TEMPLATE.md`:

````markdown
## Breaking changes in the cyoda-go v0.9.0 alignment

The template now implements cyoda-go's contract. Contract files are vendored under
`src/main/resources/cyoda/` (refresh with `scripts/sync-cyoda-contract.sh`). Replace, do not merge.

| Area | Before | After |
|---|---|---|
| Contract files | `src/main/resources/{proto,schema,api}` | `src/main/resources/cyoda/{proto,schema,openapi}` + `CYODA_VERSION`; transforms in `buildSrc/` |
| OpenAPI DTO packages | `org.cyoda.cloud.api.{common,workflow,search,audit,iam}.model` | `org.cyoda.cloud.api.common.model` only |
| Condition operators | `OperatorTypeDto`, `GroupOperatorDto`, `.operation(…)` | `SimpleConditionDto.OperatorTypeEnum`, `LifecycleConditionDto.OperatorTypeEnum`, `GroupConditionDto.OperatorEnum`, `.operatorType(…)` |
| Nested conditions | `List<QueryConditionDto>` | `List<GroupConditionDtoAllOfConditions>` |
| `EntityCrudOperations.FieldFilter.operation` | `OperatorTypeDto` | `SimpleConditionDto.OperatorTypeEnum` |
| Simple-name clashes | — | `EntityChangeMeta`, `EntityMetadata`, `EntityTransactionResponse` exist in both `org.cyoda.cloud.api.common.model` and `org.cyoda.cloud.api.event.*`; do not wildcard-import both |
| Generated proto | `CloudEventBatch`, `Cloudevents` generated | not generated; `io.cloudevents.v1.proto.CloudEvent` comes from `cloudevents-protobuf` |
| `deleteAll` | chunked (`transactionSize` 1000) | one transaction (`EntityDeleteAllRequest` lost `transactionSize`/`pageSize`) |
| Config | `app.config.cyoda-light.*`, `skip-ssl`, `execution.mode`, `cyoda.api.url` | removed; use `cyoda-api-url`, `grpc-*`, `grpc-tls`, `app.config.execution-mode` (default `virtual`), `auth-mode` |
| Local cyoda-go | cyoda-light toggle | `--spring.profiles.active=local` (app on 8081, `auth-mode: none`) |
| OBO | `app.obo.*`, `OboAwareAuthentication`, `OboKeyRegistrationService`, … | removed; compute calls Cyoda as M2M |
| Event user resolver | `app.event.auth-context.*`, `EventAuthContextHandler`, … | removed |
| `authtype` values | `user`, `service_account` | `user`, `service`, `system` |
| Workflow JSON | `"version": "1.0"` | `"version": "1.5"`; `$.` on every `jsonPath` |
| Tests | Cucumber `GherkinE2eTest` | JUnit `src/integrationTest` with `@CyodaIntegrationTest`; `./gradlew check` needs a cyoda binary (`scripts/install-cyoda.sh`); `./gradlew build -x integrationTest` without one |
````

- [ ] **Step 4: Full verification**

```bash
./gradlew clean check -Dcyoda.bin=build/cyoda-bin/cyoda
./gradlew -p buildSrc test
```

Expected: BUILD SUCCESSFUL for both. Record the test counts from `build/test-results/{test,integrationTest}`.

- [ ] **Step 5: Local-run verification (spec success criterion 3)**

Run this by hand. Stop and ask the user if port 8080, 9090 or 9091 is already in use; never stop their processes.

```bash
lsof -nP -iTCP:8080 -iTCP:9090 -iTCP:9091 -sTCP:LISTEN
```

If the output is empty:
1. In terminal 1, start cyoda with defaults in an isolated home:

```bash
env -i PATH="$PATH" HOME="$(mktemp -d)" CYODA_STORAGE_BACKEND=memory build/cyoda-bin/cyoda
```

2. In terminal 2, start the app:

```bash
./gradlew runApp --args='--spring.profiles.active=local'
```

3. In terminal 3:

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/swagger-ui/index.html
```

Expected:
- the app log shows `Stream successfully established`;
- `curl` prints `200`.

Stop the app and cyoda afterwards.

- [ ] **Step 6: Commit and open PR 1**

```bash
git add -A build.gradle src .github SYNCING_WITH_JAVA_TEMPLATE.md
git commit -m "chore: remove Cucumber; CI runs the tier-1 suite; sync guide lists breaking changes

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

Use superpowers:finishing-a-development-branch to open PR 1 from this commit. The PR body ends with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.

---

# Part B: PR 2 (credentials, callout scope, threading)

Start Part B from the PR 1 branch tip. If PR 1 has merged, start from `main`; otherwise continue on the same branch and open PR 2 against PR 1's branch.

### Task 18: Cyoda error codes on both doors, mapped to typed exceptions

**Files:**
- Create in `src/main/java/com/java_template/common/exception/`: `CyodaErrors.java`, `CyodaHttpException.java`, `CyodaCalloutEndedException.java`, `CyodaRetryableException.java`, `CyodaJoinedResponseTooLargeException.java`, `CyodaCommitInJoinedTransactionException.java`, `CyodaAccessDeniedException.java`, `CyodaCredentialException.java`
- Modify: `src/main/java/com/java_template/common/repository/CyodaRepository.java` (`validateResponse`)
- Modify: `src/main/java/com/java_template/common/util/HttpUtils.java` (error branch of `sendRequest`)
- Test: `src/test/java/com/java_template/common/exception/CyodaErrorsTest.java`
- Modify: `src/test/java/com/java_template/common/repository/CyodaRepositoryValidateResponseTest.java`

**Interfaces:**
- Produces:
  - `CyodaErrors.codeFromMessage(String): String` (nullable);
  - `CyodaErrors.fromGrpcEnvelope(Error error, boolean joined): RuntimeException`;
  - `CyodaErrors.fromHttp(int status, String body, boolean joined): RuntimeException`;
  - `CyodaErrors.JOINED_RETRYABLE = Set.of("TOO_MANY_JOINED_REQUESTS","TRANSACTION_NODE_UNAVAILABLE")`;
  - exception types, all extending `CyodaOperationException(String code, String message, Boolean retryable)`:
    - `CyodaHttpException(int status, String code, String message, boolean retryable)` with `int status()`;
    - `CyodaCalloutEndedException`, `CyodaRetryableException`, `CyodaJoinedResponseTooLargeException`, `CyodaCommitInJoinedTransactionException`, `CyodaAccessDeniedException`;
    - `CyodaCredentialException` (extends `IllegalStateException`);
  - `CyodaRepository.validateResponse(T response, boolean joined)`.

- [ ] **Step 1: Write the failing tests**

The tests use the literal wire shapes documented in `cyoda help errors`.

```java
package com.java_template.common.exception;

import org.cyoda.cloud.api.event.common.Error;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CyodaErrorsTest {

    private static Error envelope(String message, Boolean retryable) {
        Error e = new Error();
        e.setCode("CLIENT_ERROR");
        e.setMessage(message);
        e.setRetryable(retryable);
        return e;
    }

    private static String problem(int status, String code, String detail, Boolean retryable) {
        return "{\"type\":\"about:blank\",\"title\":\"x\",\"status\":" + status + ",\"detail\":\"" + detail
                + "\",\"properties\":{\"errorCode\":\"" + code + "\"" + (retryable == null ? "" : ",\"retryable\":" + retryable) + "}}";
    }

    @Test
    void grpcCodeIsTheMessagePrefixNotTheCoarseEnvelopeCode() {
        assertThat(CyodaErrors.codeFromMessage("ENTITY_NOT_FOUND: entity id=abc not found")).isEqualTo("ENTITY_NOT_FOUND");
        assertThat(CyodaErrors.codeFromMessage("no prefix here")).isNull();

        RuntimeException e = CyodaErrors.fromGrpcEnvelope(envelope("ENTITY_NOT_FOUND: entity id=abc not found", null), false);

        assertThat(e).isInstanceOf(CyodaOperationException.class);
        assertThat(((CyodaOperationException) e).getErrorCode()).isEqualTo("ENTITY_NOT_FOUND");
    }

    @Test
    void theSameStatusIsToldApartByCode() {
        assertThat(CyodaErrors.fromHttp(410, problem(410, "CALLOUT_SUPERSEDED", "CALLOUT_SUPERSEDED: moved", null), true))
                .isInstanceOf(CyodaCalloutEndedException.class);
        assertThat(CyodaErrors.fromHttp(410, problem(410, "TRANSACTION_EXPIRED", "TRANSACTION_EXPIRED: old", null), true))
                .isInstanceOf(CyodaCalloutEndedException.class);
        assertThat(CyodaErrors.fromGrpcEnvelope(envelope("TRANSACTION_NOT_FOUND: closed", null), true))
                .isInstanceOf(CyodaCalloutEndedException.class);
    }

    @Test
    void unauthorizedEndsTheCalloutOnlyWhenJoined() {
        assertThat(CyodaErrors.fromGrpcEnvelope(envelope("UNAUTHORIZED: invalid transaction token", null), true))
                .isInstanceOf(CyodaCalloutEndedException.class);
        assertThat(CyodaErrors.fromHttp(401, problem(401, "UNAUTHORIZED", "UNAUTHORIZED: bad token", null), false))
                .isInstanceOf(CyodaHttpException.class)
                .isNotInstanceOf(CyodaCalloutEndedException.class);
    }

    @Test
    void joinedRetryablesAndTheRest() {
        assertThat(CyodaErrors.fromGrpcEnvelope(envelope("TOO_MANY_JOINED_REQUESTS: queue full", true), true))
                .isInstanceOf(CyodaRetryableException.class);
        assertThat(CyodaErrors.fromHttp(503, problem(503, "TRANSACTION_NODE_UNAVAILABLE", "x", true), true))
                .isInstanceOf(CyodaRetryableException.class);
        assertThat(CyodaErrors.fromHttp(409, problem(409, "CONFLICT", "CONFLICT: retry", true), true))
                .isInstanceOf(CyodaRetryableException.class);
        assertThat(CyodaErrors.fromHttp(409, problem(409, "CONFLICT", "CONFLICT: no", null), false))
                .isInstanceOf(CyodaHttpException.class);
        assertThat(CyodaErrors.fromHttp(413, problem(413, "JOINED_RESPONSE_TOO_LARGE", "x", null), true))
                .isInstanceOf(CyodaJoinedResponseTooLargeException.class);
        assertThat(CyodaErrors.fromGrpcEnvelope(envelope("COMMIT_IN_JOINED_TRANSACTION: use unjoined", null), true))
                .isInstanceOf(CyodaCommitInJoinedTransactionException.class);
        assertThat(CyodaErrors.fromHttp(403, problem(403, "FORBIDDEN", "FORBIDDEN: role", null), false))
                .isInstanceOf(CyodaAccessDeniedException.class);
        assertThat(CyodaErrors.fromGrpcEnvelope(envelope("MODEL_ADMIN_IN_JOINED_TRANSACTION: no", null), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("framework bug");
    }

    @Test
    void httpStatusIsKeptAndNonProblemBodiesStillMap() {
        RuntimeException e = CyodaErrors.fromHttp(502, "<html>bad gateway</html>", false);

        assertThat(e).isInstanceOf(CyodaHttpException.class);
        assertThat(((CyodaHttpException) e).status()).isEqualTo(502);
        assertThat(((CyodaHttpException) e).getErrorCode()).isEqualTo("HTTP_502");
    }
}
```

In `CyodaRepositoryValidateResponseTest`:
- change every `repo.validateResponse(x)` call to `repo.validateResponse(x, false)`;
- change assertions on the error code of a `CLIENT_ERROR` envelope to expect the message-prefix code (e.g. `ENTITY_NOT_FOUND`) wherever the test's message has a `CODE:` prefix.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests com.java_template.common.exception.CyodaErrorsTest`
Expected: compilation FAILS: `cannot find symbol: CyodaErrors`.

- [ ] **Step 3: Implement the exception types**

```java
package com.java_template.common.exception;

/** A Cyoda REST failure: HTTP status plus the problem detail's properties.errorCode. */
public class CyodaHttpException extends CyodaOperationException {
    private final int status;

    public CyodaHttpException(int status, String code, String message, boolean retryable) {
        super(code, message, retryable);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
```

```java
package com.java_template.common.exception;

/**
 * The callout this request joined has ended or moved (CALLOUT_SUPERSEDED, TRANSACTION_EXPIRED,
 * TRANSACTION_NOT_FOUND, or an invalid pass). Stop working on this request; never retry.
 */
public class CyodaCalloutEndedException extends CyodaOperationException {
    public CyodaCalloutEndedException(String code, String message) {
        super(code, message, false);
    }
}
```

```java
package com.java_template.common.exception;

/** A transient refusal Cyoda marks retryable (e.g. TOO_MANY_JOINED_REQUESTS). */
public class CyodaRetryableException extends CyodaOperationException {
    public CyodaRetryableException(String code, String message) {
        super(code, message, true);
    }
}
```

```java
package com.java_template.common.exception;

/** A joined read answer exceeded CYODA_CALLOUT_JOINED_RESPONSE_MAX_BYTES; page the read. */
public class CyodaJoinedResponseTooLargeException extends CyodaOperationException {
    public CyodaJoinedResponseTooLargeException(String message) {
        super("JOINED_RESPONSE_TOO_LARGE", message, false);
    }
}
```

```java
package com.java_template.common.exception;

/** A joined write reached a COMMIT_BEFORE_DISPATCH processor's transaction; make it with CalloutScope.unjoined. */
public class CyodaCommitInJoinedTransactionException extends CyodaOperationException {
    public CyodaCommitInJoinedTransactionException(String message) {
        super("COMMIT_IN_JOINED_TRANSACTION", message, false);
    }
}
```

```java
package com.java_template.common.exception;

/** 403 FORBIDDEN: the caller lacks a role, or the pass belongs to another tenant. */
public class CyodaAccessDeniedException extends CyodaOperationException {
    public CyodaAccessDeniedException(String message) {
        super("FORBIDDEN", message, false);
    }
}
```

```java
package com.java_template.common.exception;

/** The credential for a Cyoda call cannot be determined; the framework never silently downgrades to M2M. */
public class CyodaCredentialException extends IllegalStateException {
    public CyodaCredentialException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: Implement `CyodaErrors`**

```java
package com.java_template.common.exception;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cyoda.cloud.api.event.common.Error;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ABOUTME: Maps cyoda-go failures to typed exceptions (spec §4.4).
 * gRPC: the envelope's code is coarse (CLIENT_ERROR/SERVER_ERROR); the real code is the message
 * prefix up to the first colon. REST: the problem detail's properties.errorCode.
 */
public final class CyodaErrors {

    public static final Set<String> JOINED_RETRYABLE = Set.of("TOO_MANY_JOINED_REQUESTS", "TRANSACTION_NODE_UNAVAILABLE");

    private static final Pattern CODE_PREFIX = Pattern.compile("^([A-Z][A-Z0-9_]+):");
    private static final ObjectMapper OM = new ObjectMapper();

    private CyodaErrors() {
    }

    public static String codeFromMessage(String message) {
        if (message == null) {
            return null;
        }
        Matcher m = CODE_PREFIX.matcher(message);
        return m.find() ? m.group(1) : null;
    }

    public static RuntimeException fromGrpcEnvelope(Error error, boolean joined) {
        String message = error == null || error.getMessage() == null ? "Operation failed with no error details" : error.getMessage();
        String code = codeFromMessage(message);
        if (code == null) {
            code = error != null && error.getCode() != null ? error.getCode() : "UNKNOWN";
        }
        boolean retryable = error != null && Boolean.TRUE.equals(error.getRetryable());
        return map(code, null, retryable, message, joined);
    }

    public static RuntimeException fromHttp(int status, String body, boolean joined) {
        String detail = body;
        String code = null;
        boolean retryable = false;
        try {
            JsonNode json = OM.readTree(body);
            if (json != null && json.isObject()) {
                detail = firstText(json, "detail", "message", "errorMessage", "title");
                code = json.path("properties").path("errorCode").asText(null);
                retryable = json.path("properties").path("retryable").asBoolean(false);
            }
        } catch (Exception notJson) {
            // keep the raw body as the detail
        }
        if (code == null) {
            code = codeFromMessage(detail);
        }
        if (code == null) {
            code = "HTTP_" + status;
        }
        return map(code, status, retryable, detail == null ? "" : detail, joined);
    }

    static RuntimeException map(String code, Integer status, boolean retryable, String message, boolean joined) {
        return switch (code) {
            case "CALLOUT_SUPERSEDED", "TRANSACTION_EXPIRED", "TRANSACTION_NOT_FOUND" -> new CyodaCalloutEndedException(code, message);
            case "UNAUTHORIZED" -> joined ? new CyodaCalloutEndedException(code, message) : generic(code, status, false, message);
            case "TOO_MANY_JOINED_REQUESTS", "TRANSACTION_NODE_UNAVAILABLE" -> new CyodaRetryableException(code, message);
            case "CONFLICT" -> retryable ? new CyodaRetryableException(code, message) : generic(code, status, false, message);
            case "JOINED_RESPONSE_TOO_LARGE" -> new CyodaJoinedResponseTooLargeException(message);
            case "COMMIT_IN_JOINED_TRANSACTION" -> new CyodaCommitInJoinedTransactionException(message);
            case "FORBIDDEN" -> new CyodaAccessDeniedException(message);
            case "MODEL_ADMIN_IN_JOINED_TRANSACTION" ->
                    new IllegalStateException("framework bug: a tx-token was sent on a model/workflow admin call: " + message);
            default -> generic(code, status, retryable, message);
        };
    }

    private static CyodaOperationException generic(String code, Integer status, boolean retryable, String message) {
        return status == null ? new CyodaOperationException(code, message, retryable)
                : new CyodaHttpException(status, code, message, retryable);
    }

    private static String firstText(JsonNode json, String... fields) {
        for (String f : fields) {
            if (json.hasNonNull(f)) {
                return json.get(f).asText();
            }
        }
        return json.toString();
    }
}
```

- [ ] **Step 5: Use it in `CyodaRepository.validateResponse` and `HttpUtils`**

`CyodaRepository.validateResponse` becomes:

```java
    <T extends BaseEvent> T validateResponse(T response, boolean joined) {
        if (response.getWarnings() != null && !response.getWarnings().isEmpty()) {
            response.getWarnings().forEach(w -> logger.warn("Cyoda warning: {}", w));
        }
        if (Boolean.FALSE.equals(response.getSuccess())) {
            throw CyodaErrors.fromGrpcEnvelope(response.getError(), joined);
        }
        return response;
    }
```

Update its callers to pass `false` for now: `this::validateResponse` becomes `r -> validateResponse(r, false)`. Task 21 passes the real joined flag.

In `HttpUtils.sendRequest`, replace the two `throw new ResponseStatusException(…)` branches (4xx and 5xx) with:

```java
                    } else if (statusCode >= 400) {
                        throw CyodaErrors.fromHttp(statusCode, responseBody, false);
                    }
```

Then delete the `extractErrorMessage` method and the `ResponseStatusException`/`HttpStatus` imports.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add -A src/main src/test
git commit -m "feat(errors): read cyoda error codes from both doors and map them to typed exceptions

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 19: `CyodaCallContext`, `CalloutScope` and the credential decision

**Files:**
- Create: `src/main/java/com/java_template/common/call/CyodaCallContext.java`
- Create: `src/main/java/com/java_template/common/call/CalloutScope.java`
- Create: `src/main/java/com/java_template/common/call/CyodaCallContexts.java`
- Test: `src/test/java/com/java_template/common/call/CyodaCallContextsTest.java`
- Test: `src/test/java/com/java_template/common/call/CalloutScopeTest.java`

**Interfaces:**
- Consumes: `Config.getAuthMode()` (Task 7) and `CyodaCredentialException` (Task 18).
- Produces:
  - `record CyodaCallContext(Credential credential, String txToken)`, where:
    - `sealed interface Credential permits None, M2m, Forward`, with records `None()`, `M2m()`, `Forward(String token)`;
    - factories `none()`, `m2m()`, `forward(String)`;
    - `withTxToken(String)`, `isJoined()`, `fingerprint()`;
  - `final class CalloutScope implements AutoCloseable`, with:
    - `static CalloutScope open(String txToken)` and `static Optional<CalloutScope> current()`;
    - `String txToken()`, `boolean isOpen()`, `void end()`, `void close()`;
    - `static Runnable wrap(Runnable)`, `static <T> Callable<T> wrap(Callable<T>)`, `static <T> Supplier<T> wrapSupplier(Supplier<T>)`, `static <T> T unjoined(Supplier<T>)`;
  - `@Component CyodaCallContexts`, with `CyodaCallContext current()` and `CyodaCallContext forMemberStream()`.

- [ ] **Step 1: Write the failing tests (Review Focus #4 included)**

```java
package com.java_template.common.call;

import com.java_template.common.config.Config;
import com.java_template.common.exception.CyodaCalloutEndedException;
import com.java_template.common.exception.CyodaCredentialException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CyodaCallContextsTest {

    private final Config clientCredentials = new Config();
    private final CyodaCallContexts contexts = new CyodaCallContexts(clientCredentials);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        CalloutScope.current().ifPresent(CalloutScope::close);
    }

    private static JwtAuthenticationToken jwt(String value) {
        Jwt token = Jwt.withTokenValue(value).header("alg", "RS256").subject("u1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        return new JwtAuthenticationToken(token);
    }

    @Test
    void rule1_authModeNoneSendsNothingButKeepsTheScopeToken() {
        Config none = new Config();
        none.setAuthMode(Config.AuthMode.NONE);
        CyodaCallContexts noneContexts = new CyodaCallContexts(none);

        assertThat(noneContexts.current()).isEqualTo(CyodaCallContext.none());
        try (CalloutScope ignored = CalloutScope.open("tx-1")) {
            assertThat(noneContexts.current()).isEqualTo(CyodaCallContext.none().withTxToken("tx-1"));
        }
    }

    @Test
    void rule2_anOpenScopeMeansM2mPlusTokenWhateverTheSecurityContextHolds() {
        SecurityContextHolder.getContext().setAuthentication(jwt("user-token"));
        try (CalloutScope ignored = CalloutScope.open("tx-2")) {
            assertThat(contexts.current()).isEqualTo(CyodaCallContext.m2m().withTxToken("tx-2"));
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }
    }

    @Test
    void rule3_aUserJwtIsForwardedAndABlankOneIsAnError() {
        SecurityContextHolder.getContext().setAuthentication(jwt("user-token"));
        assertThat(contexts.current()).isEqualTo(CyodaCallContext.forward("user-token"));

        SecurityContextHolder.getContext().setAuthentication(jwt(" "));
        assertThatThrownBy(contexts::current).isInstanceOf(CyodaCredentialException.class);
    }

    @Test
    void rule4_anotherAuthenticatedPrincipalIsAnErrorNotADowngrade() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("bob", "pw", AuthorityUtils.createAuthorityList("ROLE_USER")));

        assertThatThrownBy(contexts::current)
                .isInstanceOf(CyodaCredentialException.class)
                .hasMessageContaining("UsernamePasswordAuthenticationToken");
    }

    @Test
    void rule5_noPrincipalOrAnonymousIsM2m() {
        assertThat(contexts.current()).isEqualTo(CyodaCallContext.m2m());
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("k", "anon", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        assertThat(contexts.current()).isEqualTo(CyodaCallContext.m2m());
    }

    @Test
    void memberStreamIsM2mOrNoneAndNeverJoined() {
        try (CalloutScope ignored = CalloutScope.open("tx-3")) {
            assertThat(contexts.forMemberStream()).isEqualTo(CyodaCallContext.m2m());
        }
        Config none = new Config();
        none.setAuthMode(Config.AuthMode.NONE);
        assertThat(new CyodaCallContexts(none).forMemberStream()).isEqualTo(CyodaCallContext.none());
    }

    @Test
    void aClosedScopeFailsLocally() {
        CalloutScope scope = CalloutScope.open("tx-4");
        scope.end();

        assertThatThrownBy(contexts::current).isInstanceOf(CyodaCalloutEndedException.class);
        scope.close();
    }

    @Test
    void aThreadTheAppStartedWithoutWrapGoesOutUnjoinedAsM2m() throws Exception {
        try (CalloutScope ignored = CalloutScope.open("tx-5")) {
            CyodaCallContext fromPlainThread = CompletableFuture.supplyAsync(contexts::current, r -> new Thread(r).start()).get();
            CyodaCallContext fromWrapped = CompletableFuture.supplyAsync(CalloutScope.wrapSupplier(contexts::current),
                    r -> new Thread(r).start()).get();

            assertThat(fromPlainThread).isEqualTo(CyodaCallContext.m2m());
            assertThat(fromWrapped).isEqualTo(CyodaCallContext.m2m().withTxToken("tx-5"));
        }
    }

    @Test
    void unjoinedRunsAsM2mWithoutTheToken() {
        try (CalloutScope ignored = CalloutScope.open("tx-6")) {
            assertThat(CalloutScope.unjoined(contexts::current)).isEqualTo(CyodaCallContext.m2m());
            assertThat(contexts.current().txToken()).isEqualTo("tx-6");
        }
    }
}
```

```java
package com.java_template.common.call;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CalloutScopeTest {

    @Test
    void closeRestoresThePreviousScopeAndClearsTheThread() {
        try (CalloutScope outer = CalloutScope.open("outer")) {
            try (CalloutScope inner = CalloutScope.open("inner")) {
                assertThat(CalloutScope.current()).contains(inner);
            }
            assertThat(CalloutScope.current()).contains(outer);
        }
        assertThat(CalloutScope.current()).isEmpty();
    }

    @Test
    void fingerprintsNeverContainTheTokens() {
        CyodaCallContext ctx = CyodaCallContext.forward("secret-user-token").withTxToken("secret-tx");

        assertThat(ctx.fingerprint()).doesNotContain("secret").startsWith("fwd:");
        assertThat(ctx.toString()).doesNotContain("secret");
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.java_template.common.call.*'`
Expected: compilation FAILS: `package com.java_template.common.call does not exist`.

- [ ] **Step 3: Implement `CyodaCallContext`**

```java
package com.java_template.common.call;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * ABOUTME: The credential and transaction for exactly one Cyoda call (spec §4.2). Built only by
 * {@link CyodaCallContexts} and passed explicitly through every stage of an operation.
 */
public record CyodaCallContext(Credential credential, String txToken) {

    public sealed interface Credential permits None, M2m, Forward {
    }

    /** No Authorization header (cyoda-go mock IAM). */
    public record None() implements Credential {
    }

    /** The M2M service-account token. */
    public record M2m() implements Credential {
    }

    /** A user's own IdP token, sent unchanged. */
    public record Forward(String token) implements Credential {
        @Override
        public String toString() {
            return "Forward[token=<redacted>]";
        }
    }

    public static CyodaCallContext none() {
        return new CyodaCallContext(new None(), null);
    }

    public static CyodaCallContext m2m() {
        return new CyodaCallContext(new M2m(), null);
    }

    public static CyodaCallContext forward(String token) {
        return new CyodaCallContext(new Forward(token), null);
    }

    public CyodaCallContext withTxToken(String token) {
        return new CyodaCallContext(credential, token);
    }

    public boolean isJoined() {
        return txToken != null;
    }

    /** Stable, secret-free identity of this context, for cache keys. */
    public String fingerprint() {
        String cred = switch (credential) {
            case None n -> "none";
            case M2m m -> "m2m";
            case Forward f -> "fwd:" + sha256(f.token());
        };
        return txToken == null ? cred : cred + "|tx:" + sha256(txToken);
    }

    @Override
    public String toString() {
        return "CyodaCallContext[" + credential + (txToken == null ? "" : ", tx=<redacted>") + "]";
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 4: Implement `CalloutScope`**

```java
package com.java_template.common.call;

import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * ABOUTME: The processor/criterion callout a thread is working on (spec §4.4). While open, EntityService
 * calls go out as M2M and join the callout's transaction with its tx-token; attribution follows the
 * transaction's origin. Threads the application starts itself lose the scope unless wrapped with
 * {@link #wrap}; their calls then go out unjoined as M2M. Under COMMIT_BEFORE_DISPATCH with
 * startNewTxOnDispatch=false the callout carries no token, so calls are plain M2M requests attributed
 * to the M2M account. A token lives for the try's answer limit plus CYODA_CALLOUT_PASS_ALLOWANCE (30 s).
 */
public final class CalloutScope implements AutoCloseable {

    private static final ThreadLocal<CalloutScope> CURRENT = new ThreadLocal<>();
    /** Marker scope used by {@link #unjoined}: M2M, no token, never ends. */
    private static final CalloutScope DETACHED = new CalloutScope(null, null);

    private final String txToken;
    private final CalloutScope previous;
    private final AtomicBoolean open = new AtomicBoolean(true);

    private CalloutScope(String txToken, CalloutScope previous) {
        this.txToken = txToken;
        this.previous = previous;
    }

    /** Opens a scope on this thread and clears its SecurityContext: compute never forwards a user credential. */
    public static CalloutScope open(String txToken) {
        SecurityContextHolder.clearContext();
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            throw new IllegalStateException("SecurityContext still holds an authentication inside a callout scope");
        }
        CalloutScope scope = new CalloutScope(txToken, CURRENT.get());
        CURRENT.set(scope);
        return scope;
    }

    public static Optional<CalloutScope> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public String txToken() {
        return txToken;
    }

    public boolean isOpen() {
        return open.get();
    }

    /** The callout's answer is being sent: later calls through this scope fail locally. */
    public void end() {
        if (this != DETACHED) {
            open.set(false);
        }
    }

    @Override
    public void close() {
        end();
        if (CURRENT.get() == this) {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static Runnable wrap(Runnable task) {
        CalloutScope scope = CURRENT.get();
        return scope == null ? task : () -> runIn(scope, () -> {
            task.run();
            return null;
        });
    }

    public static <T> Callable<T> wrap(Callable<T> task) {
        CalloutScope scope = CURRENT.get();
        return scope == null ? task : () -> {
            CalloutScope before = CURRENT.get();
            CURRENT.set(scope);
            try {
                return task.call();
            } finally {
                restore(before);
            }
        };
    }

    public static <T> Supplier<T> wrapSupplier(Supplier<T> task) {
        CalloutScope scope = CURRENT.get();
        return scope == null ? task : () -> runIn(scope, task);
    }

    /** Runs {@code body} as M2M without the tx-token (the remedy for COMMIT_IN_JOINED_TRANSACTION). */
    public static <T> T unjoined(Supplier<T> body) {
        return runIn(DETACHED, body);
    }

    private static <T> T runIn(CalloutScope scope, Supplier<T> body) {
        CalloutScope before = CURRENT.get();
        CURRENT.set(scope);
        try {
            return body.get();
        } finally {
            restore(before);
        }
    }

    private static void restore(CalloutScope before) {
        if (before == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(before);
        }
    }
}
```

- [ ] **Step 5: Implement `CyodaCallContexts`**

```java
package com.java_template.common.call;

import com.java_template.common.config.Config;
import com.java_template.common.exception.CyodaCalloutEndedException;
import com.java_template.common.exception.CyodaCredentialException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** ABOUTME: The only place a {@link CyodaCallContext} is built (spec §4.2 rules 1–5). */
@Component
public class CyodaCallContexts {

    private final Config config;

    public CyodaCallContexts(Config config) {
        this.config = config;
    }

    public CyodaCallContext current() {
        CalloutScope scope = CalloutScope.current().orElse(null);
        if (scope != null && !scope.isOpen()) {
            throw new CyodaCalloutEndedException("CALLOUT_SCOPE_CLOSED",
                    "this callout has already answered; its transaction can no longer be joined");
        }
        if (config.getAuthMode() == Config.AuthMode.NONE) {                         // rule 1
            return scope == null ? CyodaCallContext.none() : CyodaCallContext.none().withTxToken(scope.txToken());
        }
        if (scope != null) {                                                         // rule 2
            return CyodaCallContext.m2m().withTxToken(scope.txToken());
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {                            // rule 3
            String value = jwt.getToken().getTokenValue();
            if (value == null || value.isBlank()) {
                throw new CyodaCredentialException("the authenticated JWT has a blank token value; refusing to call Cyoda");
            }
            return CyodaCallContext.forward(value);
        }
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) { // rule 4
            throw new CyodaCredentialException("principal of type " + auth.getClass().getSimpleName()
                    + " cannot be forwarded to Cyoda; only a bearer JWT can (spec §4.2)");
        }
        return CyodaCallContext.m2m();                                               // rule 5
    }

    public CyodaCallContext forMemberStream() {
        return config.getAuthMode() == Config.AuthMode.NONE ? CyodaCallContext.none() : CyodaCallContext.m2m();
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.java_template.common.call.*'`
Expected: PASS (11 tests).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/java_template/common/call src/test/java/com/java_template/common/call
git commit -m "feat(call): per-call credential/tx context and CalloutScope (spec §4.2, §4.4)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 20: gRPC interceptor driven by the call context; M2M token without pinning

**Files:**
- Create: `src/main/java/com/java_template/common/call/CyodaCallInterceptor.java`
- Create: `src/main/java/com/java_template/common/call/CyodaGrpcCalls.java`
- Delete: `src/main/java/com/java_template/common/grpc/client/ClientAuthorizationInterceptor.java`, `src/test/java/com/java_template/common/grpc/client/ClientAuthorizationInterceptorTest.java`
- Modify: `src/main/java/com/java_template/common/config/GrpcClientAutoConfiguration.java`
- Modify: `src/main/java/com/java_template/common/grpc/client/connection/ConnectionManager.java` (`connect()`)
- Modify: `src/main/java/com/java_template/common/auth/Authentication.java` (`getAccessToken`, `invalidateTokens`)
- Test: `src/test/java/com/java_template/common/call/CyodaCallInterceptorTest.java`
- Test: `src/test/java/com/java_template/common/call/CyodaGrpcCallsTest.java`

**Interfaces:**
- Consumes: `CyodaCallContext`, `CyodaCallContexts` (Task 19), and `CyodaTokenSource` (Task 7).
- Produces:
  - `CyodaCallInterceptor(CyodaTokenSource)`, with `CyodaCallInterceptor.CONTEXT` (a `CallOptions.Key<CyodaCallContext>`) and `TX_ROUTED = Set.of("entityManage","entityManageCollection","entitySearch","entitySearchCollection")`;
  - `CyodaGrpcCalls.call(CyodaCallContext ctx, CyodaTokenSource tokens, Supplier<T> call): T`, which retries once on `UNAUTHENTICATED` for M2M and up to 3 times on `CyodaErrors.JOINED_RETRYABLE` codes.

- [ ] **Step 1: Write the failing tests**

```java
package com.java_template.common.call;

import com.java_template.common.auth.CyodaTokenSource;
import io.grpc.*;
import org.cyoda.cloud.api.grpc.CloudEventsServiceGrpc;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CyodaCallInterceptorTest {

    private static final Metadata.Key<String> AUTH = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> TX = Metadata.Key.of("tx-token", Metadata.ASCII_STRING_MARSHALLER);

    private final CyodaTokenSource tokens = mock(CyodaTokenSource.class);

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Metadata start(MethodDescriptor<?, ?> method, CallOptions options, ClientCall.Listener<Object> listener) {
        ClientCall<Object, Object> delegate = mock(ClientCall.class);
        Channel channel = mock(Channel.class);
        when(channel.newCall(any(), any())).thenReturn((ClientCall) delegate);
        Metadata headers = new Metadata();
        new CyodaCallInterceptor(tokens).interceptCall((MethodDescriptor) method, options, channel).start(listener, headers);
        return headers;
    }

    @Test
    @SuppressWarnings("unchecked")
    void m2mJoinedEntityCallCarriesBearerAndTxToken() {
        when(tokens.bearerToken()).thenReturn(Optional.of("m2m"));
        CallOptions opts = CallOptions.DEFAULT.withOption(CyodaCallInterceptor.CONTEXT, CyodaCallContext.m2m().withTxToken("tx"));

        Metadata h = start(CloudEventsServiceGrpc.getEntityManageMethod(), opts, mock(ClientCall.Listener.class));

        assertThat(h.get(AUTH)).isEqualTo("Bearer m2m");
        assertThat(h.get(TX)).isEqualTo("tx");
    }

    @Test
    @SuppressWarnings("unchecked")
    void modelAdminNeverCarriesTheTxToken() {
        when(tokens.bearerToken()).thenReturn(Optional.of("m2m"));
        CallOptions opts = CallOptions.DEFAULT.withOption(CyodaCallInterceptor.CONTEXT, CyodaCallContext.m2m().withTxToken("tx"));

        Metadata h = start(CloudEventsServiceGrpc.getEntityModelManageMethod(), opts, mock(ClientCall.Listener.class));

        assertThat(h.get(TX)).isNull();
        assertThat(h.get(AUTH)).isEqualTo("Bearer m2m");
    }

    @Test
    @SuppressWarnings("unchecked")
    void forwardSendsTheUserTokenAndNoneSendsNothing() {
        CallOptions fwd = CallOptions.DEFAULT.withOption(CyodaCallInterceptor.CONTEXT, CyodaCallContext.forward("user"));
        CallOptions none = CallOptions.DEFAULT.withOption(CyodaCallInterceptor.CONTEXT, CyodaCallContext.none());

        assertThat(start(CloudEventsServiceGrpc.getEntitySearchMethod(), fwd, mock(ClientCall.Listener.class)).get(AUTH))
                .isEqualTo("Bearer user");
        assertThat(start(CloudEventsServiceGrpc.getEntitySearchMethod(), none, mock(ClientCall.Listener.class)).get(AUTH))
                .isNull();
        verifyNoInteractions(tokens);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aCallWithoutContextIsCancelled() {
        ClientCall.Listener<Object> listener = mock(ClientCall.Listener.class);

        start(CloudEventsServiceGrpc.getEntityManageMethod(), CallOptions.DEFAULT, listener);

        ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
        verify(listener).onClose(status.capture(), any());
        assertThat(status.getValue().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aCredentialFailureCancelsWithUnauthenticated() {
        when(tokens.bearerToken()).thenThrow(new IllegalStateException("down"));
        ClientCall.Listener<Object> listener = mock(ClientCall.Listener.class);
        CallOptions opts = CallOptions.DEFAULT.withOption(CyodaCallInterceptor.CONTEXT, CyodaCallContext.m2m());

        start(CloudEventsServiceGrpc.getEntityManageMethod(), opts, listener);

        ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
        verify(listener).onClose(status.capture(), any());
        assertThat(status.getValue().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    }
}
```

```java
package com.java_template.common.call;

import com.java_template.common.auth.CyodaTokenSource;
import com.java_template.common.exception.CyodaRetryableException;
import io.grpc.Status;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class CyodaGrpcCallsTest {

    private final CyodaTokenSource tokens = mock(CyodaTokenSource.class);

    @Test
    void m2mRetriesOnceAfterUnauthenticated() {
        AtomicInteger calls = new AtomicInteger();

        String r = CyodaGrpcCalls.call(CyodaCallContext.m2m(), tokens, () -> {
            if (calls.incrementAndGet() == 1) {
                throw Status.UNAUTHENTICATED.asRuntimeException();
            }
            return "ok";
        });

        assertThat(r).isEqualTo("ok");
        assertThat(calls).hasValue(2);
        verify(tokens).invalidate();
    }

    @Test
    void aForwardedTokenIsNeverRetried() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> CyodaGrpcCalls.call(CyodaCallContext.forward("u"), tokens, () -> {
            calls.incrementAndGet();
            throw Status.UNAUTHENTICATED.asRuntimeException();
        }));
        assertThat(calls).hasValue(1);
        verifyNoInteractions(tokens);
    }

    @Test
    void joinedQueueFullIsRetriedAtMostThreeTimes() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> CyodaGrpcCalls.call(CyodaCallContext.m2m().withTxToken("t"), tokens, () -> {
            calls.incrementAndGet();
            throw new CyodaRetryableException("TOO_MANY_JOINED_REQUESTS", "full");
        })).isInstanceOf(CyodaRetryableException.class);
        assertThat(calls).hasValue(4);
    }

    @Test
    void aRetryableConflictIsNotRetriedLocally() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> CyodaGrpcCalls.call(CyodaCallContext.m2m(), tokens, () -> {
            calls.incrementAndGet();
            throw new CyodaRetryableException("CONFLICT", "c");
        }));
        assertThat(calls).hasValue(1);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests com.java_template.common.call.CyodaCallInterceptorTest --tests com.java_template.common.call.CyodaGrpcCallsTest`
Expected: compilation FAILS: `cannot find symbol: CyodaCallInterceptor`, `CyodaGrpcCalls`.

- [ ] **Step 3: Implement `CyodaCallInterceptor` and `CyodaGrpcCalls`**

```java
package com.java_template.common.call;

import com.java_template.common.auth.CyodaTokenSource;
import io.grpc.*;

import java.util.Set;

/**
 * ABOUTME: Sets authorization and (for tx-routed RPCs only) tx-token from the call's {@link CyodaCallContext}.
 * A call without a context, or whose credential cannot be obtained, is cancelled — never sent unauthenticated.
 */
public final class CyodaCallInterceptor implements ClientInterceptor {

    public static final CallOptions.Key<CyodaCallContext> CONTEXT = CallOptions.Key.create("cyoda-call-context");
    /** The only RPCs cyoda routes into a joined transaction (entityModelManage refuses a token). */
    public static final Set<String> TX_ROUTED = Set.of("entityManage", "entityManageCollection", "entitySearch", "entitySearchCollection");

    private static final Metadata.Key<String> AUTHORIZATION = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> TX_TOKEN = Metadata.Key.of("tx-token", Metadata.ASCII_STRING_MARSHALLER);

    private final CyodaTokenSource tokenSource;

    public CyodaCallInterceptor(CyodaTokenSource tokenSource) {
        this.tokenSource = tokenSource;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method, CallOptions options, Channel next) {
        CyodaCallContext ctx = options.getOption(CONTEXT);
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, options)) {
            @Override
            public void start(Listener<RespT> listener, Metadata headers) {
                if (ctx == null) {
                    listener.onClose(Status.FAILED_PRECONDITION.withDescription(
                            "no CyodaCallContext on " + method.getFullMethodName() + "; use the framework's services"), new Metadata());
                    return;
                }
                try {
                    switch (ctx.credential()) {
                        case CyodaCallContext.None n -> { }
                        case CyodaCallContext.M2m m -> headers.put(AUTHORIZATION, "Bearer " + tokenSource.bearerToken()
                                .orElseThrow(() -> new IllegalStateException("no M2M token source is configured")));
                        case CyodaCallContext.Forward f -> headers.put(AUTHORIZATION, "Bearer " + f.token());
                    }
                } catch (RuntimeException e) {
                    listener.onClose(Status.UNAUTHENTICATED.withDescription("credential unavailable: " + e.getMessage()).withCause(e),
                            new Metadata());
                    return;
                }
                if (ctx.isJoined() && TX_ROUTED.contains(method.getBareMethodName())) {
                    headers.put(TX_TOKEN, ctx.txToken());
                }
                super.start(listener, headers);
            }
        };
    }
}
```

```java
package com.java_template.common.call;

import com.java_template.common.auth.CyodaTokenSource;
import com.java_template.common.exception.CyodaErrors;
import com.java_template.common.exception.CyodaRetryableException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import java.util.function.Supplier;

/** ABOUTME: Retry rules for one Cyoda call (spec §4.2, §4.4). */
public final class CyodaGrpcCalls {

    private static final long[] BACKOFF_MS = {50, 100, 200};

    private CyodaGrpcCalls() {
    }

    public static <T> T call(CyodaCallContext ctx, CyodaTokenSource tokens, Supplier<T> call) {
        for (int attempt = 0; ; attempt++) {
            try {
                return withM2mRetry(ctx, tokens, call);
            } catch (CyodaRetryableException e) {
                if (!CyodaErrors.JOINED_RETRYABLE.contains(e.getErrorCode()) || attempt >= BACKOFF_MS.length) {
                    throw e;
                }
                sleep(BACKOFF_MS[attempt]);
            }
        }
    }

    private static <T> T withM2mRetry(CyodaCallContext ctx, CyodaTokenSource tokens, Supplier<T> call) {
        try {
            return call.get();
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.UNAUTHENTICATED && ctx.credential() instanceof CyodaCallContext.M2m) {
                tokens.invalidate();
                return call.get();
            }
            throw e;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 4: Wire the interceptor, the member stream and the token lock**

- **Old interceptor:** delete `ClientAuthorizationInterceptor.java` and its test (`git rm`).
- **`GrpcClientAutoConfiguration`:** each stub bean uses `.withInterceptors(new CyodaCallInterceptor(tokenSource))`.
- **`ConnectionManager`:** add a `CyodaCallContexts callContexts` constructor parameter. In `connect()`, replace `cloudEventsServiceStub.startStreaming(ourObserver)` with:

```java
            final var newObserver = cloudEventsServiceStub
                    .withOption(CyodaCallInterceptor.CONTEXT, callContexts.forMemberStream())
                    .startStreaming(ourObserver);
```

- **Stub call sites:** make sure every framework stub call sets a context. The next command must list only `CyodaRepository` (fixed in Task 21) and `ConnectionManager` (fixed above):

```bash
grep -rn -E 'cloudEventsService(Blocking|Future)?Stub\b' src/main/java | grep -v -E 'GrpcClientAutoConfiguration|import '
```

- **`Authentication`:** replace the `ConcurrentHashMap` cache with a volatile field guarded by a `ReentrantLock`, so the token HTTP call never runs inside a `synchronized` bin lock. A bin lock pins a virtual thread on JDK 21.

```java
    private final java.util.concurrent.locks.ReentrantLock fetchLock = new java.util.concurrent.locks.ReentrantLock();
    private volatile CachedToken cached;

    public OAuth2AccessToken getAccessToken() {
        CachedToken current = cached;
        if (current != null && current.isValid()) {
            return current.oAuth2AccessToken();
        }
        fetchLock.lock();
        try {
            current = cached;
            if (current != null && current.isValid()) {
                return current.oAuth2AccessToken();
            }
            logger.info("Fetching new OAuth2 access token");
            OAuth2AuthorizedClient client = authorizedClientManager.authorize(
                    OAuth2AuthorizeRequest.withClientRegistrationId("cyoda").principal("cyoda-client").build());
            if (client == null || client.getAccessToken() == null) {
                throw new IllegalStateException("Failed to obtain access token");
            }
            cached = new CachedToken(client.getAccessToken());
            return client.getAccessToken();
        } finally {
            fetchLock.unlock();
        }
    }

    public void invalidateTokens() {
        cached = null;
        logger.info("Manually invalidated cached token");
    }
```

  Delete the `tokenCache`/`CACHE_KEY` fields.

- [ ] **Step 5: Run the tests**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

Until Task 21 is done, `CyodaRepository` calls carry no context, so do not run `integrationTest` between Tasks 20 and 21. Its calls would be cancelled with `FAILED_PRECONDITION`, which is the intended guard. Commit Tasks 20 and 21 separately, but verify ITs only after Task 21.

- [ ] **Step 6: Commit**

```bash
git add -A src/main src/test
git commit -m "feat(grpc): interceptor driven by CyodaCallContext; tx-token only on tx-routed RPCs; M2M token fetch outside bin locks

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 21: Repository and service carry the context explicitly; virtual-thread executor; scope rules

**Files:**
- Modify: `src/main/java/com/java_template/common/repository/CrudRepository.java` (every method gains a leading `CyodaCallContext ctx`)
- Modify: `src/main/java/com/java_template/common/repository/CyodaRepository.java`
- Modify: `src/main/java/com/java_template/common/service/EntityServiceImpl.java`
- Modify: `src/test/java/com/java_template/common/service/EntityServiceImplTest.java`, `src/test/java/com/java_template/common/repository/CyodaRepositoryValidateResponseTest.java`
- Test: `src/test/java/com/java_template/common/repository/RecordingCyodaServer.java` (test helper)
- Test: `src/test/java/com/java_template/common/repository/CyodaRepositoryContextTest.java`

**Interfaces:**
- Consumes: `CyodaCallContext`, `CyodaCallContexts`, `CalloutScope` (Task 19); `CyodaCallInterceptor.CONTEXT` and `CyodaGrpcCalls.call` (Task 20).
- Produces:
  - `CrudRepository`: e.g. `CompletableFuture<DataPayload> findById(CyodaCallContext ctx, UUID id, Date pointInTime)` (the same for every method);
  - `CyodaRepository(ObjectMapper, CloudEventsServiceBlockingStub, CloudEventBuilder, CloudEventParser, Config, CyodaTokenSource)`;
  - `CyodaRepository.DIRECT_SEARCH_LIMIT = 10_000`.

- [ ] **Step 1: Write the recording gRPC test helper**

```java
package com.java_template.common.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_template.common.config.Config;
import com.java_template.common.grpc.client.event_handling.CloudEventBuilder;
import io.cloudevents.core.provider.EventFormatProvider;
import io.cloudevents.protobuf.ProtobufFormat;
import io.cloudevents.v1.proto.CloudEvent;
import io.grpc.*;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.cyoda.cloud.api.event.common.BaseEvent;
import org.cyoda.cloud.api.grpc.CloudEventsServiceGrpc;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** In-process CloudEventsService that records every call's headers and answers from functions. */
final class RecordingCyodaServer extends CloudEventsServiceGrpc.CloudEventsServiceImplBase implements AutoCloseable {

    record Seen(String method, String type, String authorization, String txToken) {}

    static final Metadata.Key<String> AUTH = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    static final Metadata.Key<String> TX = Metadata.Key.of("tx-token", Metadata.ASCII_STRING_MARSHALLER);

    final List<Seen> seen = new CopyOnWriteArrayList<>();
    final CloudEventBuilder builder;
    Function<CloudEvent, BaseEvent> unary = ce -> { throw new IllegalStateException("no unary answer for " + ce.getType()); };
    Function<CloudEvent, List<BaseEvent>> collection = ce -> List.of();

    private final String name = "cyoda-" + UUID.randomUUID();
    private final Server server;
    final ManagedChannel channel;

    RecordingCyodaServer(ObjectMapper om) throws Exception {
        builder = new CloudEventBuilder(om, EventFormatProvider.getInstance().resolveFormat(ProtobufFormat.PROTO_CONTENT_TYPE), new Config());
        ServerInterceptor capture = new ServerInterceptor() {
            @Override
            public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
                return Contexts.interceptCall(Context.current().withValue(HEADERS, headers), call, headers, next);
            }
        };
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(ServerInterceptors.intercept(this, capture)).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    private void record(String method, CloudEvent ce) {
        Metadata h = HEADERS.get();
        seen.add(new Seen(method, ce.getType(), h.get(AUTH), h.get(TX)));
    }

    @Override
    public void entitySearch(CloudEvent request, StreamObserver<CloudEvent> out) {
        record("entitySearch", request);
        answer(out, List.of(unary.apply(request)));
    }

    @Override
    public void entitySearchCollection(CloudEvent request, StreamObserver<CloudEvent> out) {
        record("entitySearchCollection", request);
        answer(out, collection.apply(request));
    }

    @Override
    public void entityManage(CloudEvent request, StreamObserver<CloudEvent> out) {
        record("entityManage", request);
        answer(out, List.of(unary.apply(request)));
    }

    private void answer(StreamObserver<CloudEvent> out, List<BaseEvent> events) {
        try {
            for (BaseEvent e : events) {
                out.onNext(builder.buildEvent(e));
            }
            out.onCompleted();
        } catch (Exception e) {
            out.onError(Status.INTERNAL.withCause(e).asRuntimeException());
        }
    }

    @Override
    public void close() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    /** Request headers, attached to the gRPC Context by the capturing interceptor. */
    private static final Context.Key<Metadata> HEADERS = Context.key("recorded-headers");
}
```

- [ ] **Step 2: Write the failing repository tests (Review Focus #5 included)**

```java
package com.java_template.common.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_template.common.auth.CyodaTokenSource;
import com.java_template.common.call.CyodaCallContext;
import com.java_template.common.call.CyodaCallInterceptor;
import com.java_template.common.config.Config;
import com.java_template.common.grpc.client.event_handling.CloudEventBuilder;
import com.java_template.common.grpc.client.event_handling.CloudEventParser;
import org.cyoda.cloud.api.common.model.GroupConditionDto;
import org.cyoda.cloud.api.event.common.DataPayload;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.cyoda.cloud.api.event.search.*;
import org.cyoda.cloud.api.grpc.CloudEventsServiceGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CyodaRepositoryContextTest {

    private final ObjectMapper om = new ObjectMapper();
    private RecordingCyodaServer server;
    private CyodaRepository repo;
    private final ModelSpec spec = new ModelSpec().withName("m").withVersion(1);
    private final GroupConditionDto all = new GroupConditionDto().operator(GroupConditionDto.OperatorEnum.AND).conditions(List.of());

    @BeforeEach
    void setUp() throws Exception {
        server = new RecordingCyodaServer(om);
        CyodaTokenSource tokens = mock(CyodaTokenSource.class);
        when(tokens.bearerToken()).thenReturn(Optional.of("m2m-token"));
        var stub = CloudEventsServiceGrpc.newBlockingStub(server.channel).withInterceptors(new CyodaCallInterceptor(tokens));
        Config config = new Config();
        repo = new CyodaRepository(om, stub, server.builder, new CloudEventParser(om), config, tokens);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private static EntityResponse entity(int i) {
        return (EntityResponse) new EntityResponse().withId(UUID.randomUUID().toString()).withSuccess(true)
                .withPayload(new DataPayload().withType("ENTITY").withData(new ObjectMapper().createObjectNode().put("i", i)));
    }

    @Test
    void everyCallOfAPagedSnapshotSearchCarriesTheSameContext() {
        UUID snapshot = UUID.randomUUID();
        server.unary = ce -> switch (ce.getType()) {
            case "EntitySnapshotSearchRequest" -> new EntitySnapshotSearchResponse().withId("r1").withSuccess(true)
                    .withStatus(new SearchSnapshotStatus().withSnapshotId(snapshot).withEntitiesCount(2L)
                            .withStatus(SearchSnapshotStatus.Status.RUNNING));
            case "SnapshotGetStatusRequest" -> new EntitySnapshotSearchResponse().withId("r2").withSuccess(true)
                    .withStatus(new SearchSnapshotStatus().withSnapshotId(snapshot).withEntitiesCount(2L)
                            .withStatus(SearchSnapshotStatus.Status.SUCCESSFUL));
            default -> throw new IllegalStateException(ce.getType());
        };
        server.collection = ce -> List.of(entity(1), entity(2));
        CyodaCallContext ctx = CyodaCallContext.forward("user-token");

        repo.findAllByCriteria(ctx, spec, all, SearchAndRetrievalParams.builder().pageSize(10).pollIntervalMs(10).build()).join();

        assertThat(server.seen).extracting(RecordingCyodaServer.Seen::type)
                .containsExactly("EntitySnapshotSearchRequest", "SnapshotGetStatusRequest", "SnapshotGetRequest");
        assertThat(server.seen).allSatisfy(s -> {
            assertThat(s.authorization()).isEqualTo("Bearer user-token");
            assertThat(s.txToken()).isNull();
        });
    }

    @Test
    void insideAScopeSearchIsDirectAndJoined() {
        server.collection = ce -> List.of(entity(1));
        CyodaCallContext joined = CyodaCallContext.m2m().withTxToken("tx-1");

        var page = repo.findAllByCriteria(joined, spec, all, SearchAndRetrievalParams.defaults()).join();

        assertThat(page.data()).hasSize(1);
        assertThat(server.seen).singleElement().satisfies(s -> {
            assertThat(s.type()).isEqualTo("EntitySearchRequest");
            assertThat(s.txToken()).isEqualTo("tx-1");
            assertThat(s.authorization()).isEqualTo("Bearer m2m-token");
        });
    }

    @Test
    void insideAScopePagingBeyondTheDirectLimitIsRefused() {
        CyodaCallContext joined = CyodaCallContext.m2m().withTxToken("tx-1");

        assertThatThrownBy(() -> repo.findAllByCriteria(joined, spec, all,
                SearchAndRetrievalParams.builder().pageNumber(1).build()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("direct search");
        assertThatThrownBy(() -> repo.findAllByCriteria(joined, spec, all,
                SearchAndRetrievalParams.builder().pageSize(10_001).build()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("10000");
        assertThat(server.seen).isEmpty();
    }

    @Test
    void insideAScopeTransactionControlParametersAreRefusedLocally() {
        CyodaCallContext joined = CyodaCallContext.m2m().withTxToken("tx-1");

        assertThatThrownBy(() -> repo.saveAll(joined, spec, List.of(om.createObjectNode()), 10, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("transactionWindow");
        assertThatThrownBy(() -> repo.updateAll(joined, List.of(om.createObjectNode().put("id", UUID.randomUUID().toString())), null, null, 1000L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("transactionTimeoutMs");
        assertThat(server.seen).isEmpty();
    }
}
```

`EntityResponse.withId(...)` returns `BaseEvent` in the generated builder chain, hence the cast in `entity(i)`. If the generated `with*` methods return the subtype, remove the cast.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew test --tests com.java_template.common.repository.CyodaRepositoryContextTest`
Expected: compilation FAILS: the `CyodaRepository` constructor and methods do not take a context.

- [ ] **Step 4: Refactor `CrudRepository` and `CyodaRepository`**

**4a. Signatures.** Every `CrudRepository` method gains a first parameter `@NotNull CyodaCallContext ctx`; update `CyodaRepository`'s `@Override`s to match. Add the constructor parameter `CyodaTokenSource tokenSource` and a field:

```java
    public static final int DIRECT_SEARCH_LIMIT = 10_000;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @PreDestroy
    void shutdownExecutor() {
        executor.close();
    }
```

**4b. Call helpers.** Replace `blocking()`, `sendAndGet` and `sendAndGetCollection` with context-carrying versions:

```java
    private CloudEventsServiceGrpc.CloudEventsServiceBlockingStub stub(CyodaCallContext ctx) {
        return cloudEventsServiceBlockingStub
                .withOption(CyodaCallInterceptor.CONTEXT, ctx)
                .withDeadlineAfter(config.getGrpcCallDeadlineMs(), TimeUnit.MILLISECONDS);
    }

    private <R extends BaseEvent> CompletableFuture<R> sendAndGet(
            CyodaCallContext ctx,
            BiFunction<CloudEventsServiceGrpc.CloudEventsServiceBlockingStub, CloudEvent, CloudEvent> apiCall,
            BaseEvent baseEvent,
            Class<R> responseType
    ) {
        final CloudEvent requestEvent = build(baseEvent);
        return CompletableFuture.supplyAsync(() -> CyodaGrpcCalls.call(ctx, tokenSource, () -> {
            CloudEvent response = apiCall.apply(stub(ctx), requestEvent);
            return validateResponse(cloudEventParser.parseCloudEvent(response, responseType), ctx.isJoined());
        }), executor);
    }

    private <R extends BaseEvent> CompletableFuture<Stream<R>> sendAndGetCollection(
            CyodaCallContext ctx,
            BiFunction<CloudEventsServiceGrpc.CloudEventsServiceBlockingStub, CloudEvent, Iterator<CloudEvent>> apiCall,
            BaseEvent baseEvent,
            Class<R> responseType
    ) {
        final CloudEvent requestEvent = build(baseEvent);
        return CompletableFuture.supplyAsync(() -> CyodaGrpcCalls.call(ctx, tokenSource, () -> {
            List<R> all = new ArrayList<>();
            apiCall.apply(stub(ctx), requestEvent).forEachRemaining(ce -> {
                if (ce != null) {
                    all.add(validateResponse(cloudEventParser.parseCloudEvent(ce, responseType), ctx.isJoined()));
                }
            });
            return all.stream();
        }), executor);
    }

    private CloudEvent build(BaseEvent event) {
        try {
            return cloudEventBuilder.buildEvent(event);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException(e);
        }
    }
```

Every call site becomes `sendAndGet(ctx, (s, req) -> s.entityManage(req), …)`, and the same for `entityManageCollection`, `entitySearch` and `entitySearchCollection`. Delete `requestAndGetOrThrow` and `processCollection`. The collection is materialised inside the executor task, so validation errors surface in the call that caused them.

**4c. Search inside a scope.** At the top of `findAllByCriteria` and `findAll`:

```java
        if (ctx.isJoined()) {
            requireDirectSearchable(params);
            return findAllByConditionInMemory(ctx, modelSpec, params.pageSize(), condition, params.pointInTime());
        }
```

with

```java
    /** Async snapshot search runs detached from the transaction, so inside a scope only a direct search sees the cascade's writes. */
    private static void requireDirectSearchable(SearchAndRetrievalParams params) {
        if (params.pageNumber() > 0 || params.searchId() != null) {
            throw new IllegalStateException("inside a callout scope search runs as a direct search (async snapshots do not "
                    + "see the joined transaction's writes): only page 0 can be read");
        }
        if (params.pageSize() > DIRECT_SEARCH_LIMIT) {
            throw new IllegalStateException("inside a callout scope a direct search returns at most " + DIRECT_SEARCH_LIMIT
                    + " entities; requested " + params.pageSize());
        }
    }
```

In `findAll`, pass the match-all `GroupConditionDto` as `condition`.

**4d. Transaction-control parameters inside a scope.** At the top of the `saveAll(ctx, …, transactionWindow, transactionTimeoutMs)` and `updateAll(ctx, …, transactionWindow, transactionTimeoutMs)` overloads:

```java
        rejectTransactionControlWhenJoined(ctx, transactionWindow, transactionTimeoutMs);
```

with

```java
    private static void rejectTransactionControlWhenJoined(CyodaCallContext ctx, Integer transactionWindow, Long transactionTimeoutMs) {
        if (!ctx.isJoined()) {
            return;
        }
        if (transactionWindow != null) {
            throw new IllegalArgumentException("transactionWindow is refused on a request joined to a callout's transaction");
        }
        if (transactionTimeoutMs != null) {
            throw new IllegalArgumentException("transactionTimeoutMs is refused on a request joined to a callout's transaction");
        }
    }
```

**4e. Snapshot cache.** It loses its loader, and its key gains the context fingerprint; nothing is cached inside a scope.
- Change the field type to `Cache<SearchCacheKey, CompletableFuture<SearchSnapshotStatus>>` and replace `.build(key -> …)` with `.build()`.
- Change the record to `private record SearchCacheKey(String contextFingerprint, ModelSpec modelSpec, GroupConditionDto condition, Date pointInTime, UUID searchId) {}`.
- Build the key with `ctx.fingerprint()`.
- Use `snapshotCache.getIfPresent(cacheKey)`.
- Wrap the `snapshotCache.put(...)` in `if (!ctx.isJoined())`.

**4f. Async stages.** Every async stage uses `executor`:
- `snapshot.thenComposeAsync(fn)` becomes `snapshot.thenComposeAsync(fn, executor)`;
- the poll delay becomes `CompletableFuture.runAsync(() -> {}, CompletableFuture.delayedExecutor(intervalMillis, TimeUnit.MILLISECONDS, executor))`;
- `ctx` is a parameter of `findAllByCondition`, `createSnapshotSearch`, `getSnapShotIdCompletableFuture`, `waitForSearchCompletion`, `pollSnapshotStatus`, `getSnapshotStatus` and `getSearchResult`, and is passed to every `sendAndGet*`.

- [ ] **Step 5: Refactor `EntityServiceImpl` to build the context once per public call**

Inject `CyodaCallContexts callContexts` (constructor). For **every** public method:
1. The first statement is `CyodaCallContext ctx = callContexts.current();`.
2. The body moves into a `private` method with the same name and `ctx` as its first parameter.
3. Every `repository.x(...)` call passes `ctx` first.
4. Calls to other public `EntityService` methods inside a body call the private ctx-taking variant instead, so one operation uses one context.

The public methods are:
- `getById` ×2, `findByBusinessId` ×2, `findByBusinessIdOrNull`, `findByCompositeKey`, `findByCompositeKeyOrNull`;
- `findAll`, `streamAll`, `search`, `searchAsStream`;
- `getEntityCount` ×2, `getEntityStatsByState` ×3;
- `create`, `update`, `updateByBusinessId`, `deleteById`, `deleteByBusinessId`;
- `save` ×2, `updateAll` ×2, `deleteAll`;
- `getEntityChangesMetadata` ×2.

Inside a scope, reload without `pointInTime` (Spec clarification 3). In the private `create`, `update` and `updateByBusinessId`, immediately after the repository write, insert:

```java
        if (ctx.isJoined()) {
            // Inside the joined transaction its latest view is the right one (spec clarification 3).
            return getById(ctx, entityId, modelSpec, entityClass, null);
        }
```

For example, `create` becomes:

```java
    @Override
    public <T extends CyodaEntity> EntityWithMetadata<T> create(@NotNull final T entity) {
        return create(callContexts.current(), entity);
    }

    private <T extends CyodaEntity> EntityWithMetadata<T> create(CyodaCallContext ctx, T entity) {
        ModelSpec modelSpec = entity.getModelKey().modelKey();
        EntityTransactionResponse response = repository.save(ctx, modelSpec, objectMapper.valueToTree(entity)).join();
        UUID entityId = response.getTransactionInfo().getEntityIds().getFirst();
        UUID transactionId = response.getTransactionInfo().getTransactionId();
        @SuppressWarnings("unchecked")
        Class<T> entityClass = (Class<T>) entity.getClass();
        if (ctx.isJoined()) {
            return getById(ctx, entityId, modelSpec, entityClass, null);
        }
        EntityChangeMeta changeMeta = getEntityChangesMetadata(ctx, entityId, null).stream()
                .filter(meta -> transactionId.equals(meta.getTransactionId()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Transaction metadata not found for transaction: " + transactionId));
        return getById(ctx, entityId, modelSpec, entityClass, changeMeta.getTimeOfChange());
    }
```

Update `EntityServiceImplTest`:
- its mocked `CrudRepository` expectations gain an `any(CyodaCallContext.class)` first argument;
- construct the service with a `CyodaCallContexts` built on a default `Config`.

Update `CyodaRepositoryValidateResponseTest` for the new constructor, passing a mocked `CyodaTokenSource` and any blocking stub (e.g. `CloudEventsServiceGrpc.newBlockingStub(InProcessChannelBuilder.forName("unused").build())`).

- [ ] **Step 6: Run the unit tests, then the tier-1 integration suite**

```bash
./gradlew test
./gradlew integrationTest -Dcyoda.bin=build/cyoda-bin/cyoda
```

Expected: both BUILD SUCCESSFUL. Every PR 1 IT still passes; their calls now carry `NONE` contexts under `auth-mode=none`.

- [ ] **Step 7: Commit**

```bash
git add -A src/main src/test
git commit -m "feat(repository): one explicit call context per operation on a virtual-thread executor

Paged snapshot searches no longer lose the caller's credential; inside a callout scope
search is direct, transaction-control parameters are refused, and reads skip pointInTime.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 22: REST calls take the context: headers, tx-token and 401 rules

**Files:**
- Modify: `src/main/java/com/java_template/common/util/HttpUtils.java`
- Modify: `src/main/java/com/java_template/common/service/WorkflowService.java`, `WorkflowServiceImpl.java` (add `importWorkflows`), `EdgeMessageServiceImpl.java`, `src/main/java/com/java_template/common/tool/CyodaInit.java`
- Test: `src/test/java/com/java_template/common/util/HttpUtilsContextTest.java`

**Interfaces:**
- Consumes: `CyodaCallContext`, `CyodaCallContexts` (Task 19), `CyodaTokenSource` (Task 7), `CyodaErrors` (Task 18).
- Produces:
  - `HttpUtils(JsonUtils, ObjectMapper, Config, CyodaTokenSource)`;
  - every public `send*Request` takes `CyodaCallContext ctx` as its first parameter instead of `String token`;
  - `HttpUtils.isTxRouted(String path): boolean`, true for paths starting `entity`, `search` or `message`;
  - `WorkflowService.importWorkflows(ModelSpec modelSpec, JsonNode workflows, String importMode): JsonNode`.

- [ ] **Step 1: Write the failing tests**

```java
package com.java_template.common.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_template.common.auth.CyodaTokenSource;
import com.java_template.common.call.CyodaCallContext;
import com.java_template.common.config.Config;
import com.java_template.common.exception.CyodaCalloutEndedException;
import com.java_template.common.exception.CyodaHttpException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class HttpUtilsContextTest {

    private HttpServer server;
    private final List<Map<String, String>> seen = new CopyOnWriteArrayList<>();
    private final AtomicInteger unauthorizedFirst = new AtomicInteger();
    private final CyodaTokenSource tokens = mock(CyodaTokenSource.class);
    private HttpUtils http;
    private String base;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", ex -> {
            seen.add(Map.of(
                    "path", ex.getRequestURI().getPath(),
                    "auth", String.valueOf(ex.getRequestHeaders().getFirst("Authorization")),
                    "tx", String.valueOf(ex.getRequestHeaders().getFirst("X-Tx-Token"))));
            boolean deny = unauthorizedFirst.getAndDecrement() > 0;
            byte[] body = (deny
                    ? "{\"status\":401,\"detail\":\"UNAUTHORIZED: token\",\"properties\":{\"errorCode\":\"UNAUTHORIZED\"}}"
                    : "{\"ok\":true}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(deny ? 401 : 200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
        when(tokens.bearerToken()).thenReturn(Optional.of("m2m"));
        ObjectMapper om = new ObjectMapper();
        http = new HttpUtils(new JsonUtils(om), om, new Config(), tokens);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void entityPathsCarryTheTxTokenAndModelPathsNever() {
        CyodaCallContext joined = CyodaCallContext.m2m().withTxToken("tx-9");

        http.sendGetRequest(joined, base, "entity/abc").join();
        http.sendPostRequest(joined, base, "model/m/1/workflow/import", Map.of()).join();

        assertThat(seen.get(0)).containsEntry("auth", "Bearer m2m").containsEntry("tx", "tx-9");
        assertThat(seen.get(1)).containsEntry("auth", "Bearer m2m").containsEntry("tx", "null");
    }

    @Test
    void forwardAndNone() {
        http.sendGetRequest(CyodaCallContext.forward("user"), base, "entity/x").join();
        http.sendGetRequest(CyodaCallContext.none(), base, "entity/x").join();

        assertThat(seen.get(0)).containsEntry("auth", "Bearer user");
        assertThat(seen.get(1)).containsEntry("auth", "null");
    }

    @Test
    void m2mOutsideAScopeRefreshesAndRetriesOnce() {
        unauthorizedFirst.set(1);

        http.sendGetRequest(CyodaCallContext.m2m(), base, "entity/x").join();

        assertThat(seen).hasSize(2);
        verify(tokens).invalidate();
    }

    @Test
    void a401InsideAScopeEndsTheCalloutWithoutRetry() {
        unauthorizedFirst.set(1);

        assertThatThrownBy(() -> http.sendGetRequest(CyodaCallContext.m2m().withTxToken("t"), base, "entity/x").join())
                .hasCauseInstanceOf(CyodaCalloutEndedException.class);
        assertThat(seen).hasSize(1);
        verify(tokens).invalidate();
    }

    @Test
    void aForwardedTokenIsNeverRetried() {
        unauthorizedFirst.set(1);

        assertThatThrownBy(() -> http.sendGetRequest(CyodaCallContext.forward("u"), base, "entity/x").join())
                .hasCauseInstanceOf(CyodaHttpException.class);
        assertThat(seen).hasSize(1);
        verify(tokens, never()).invalidate();
    }
}
```

(Construct `JsonUtils` the way its constructor requires. If it is not `JsonUtils(ObjectMapper)`, use the actual constructor.)

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests com.java_template.common.util.HttpUtilsContextTest`
Expected: compilation FAILS: `HttpUtils` takes a token string.

- [ ] **Step 3: Implement**

In `HttpUtils`:
- add the `CyodaTokenSource tokenSource` constructor parameter and field;
- change every public `send*Request(String token, …)` to `send*Request(CyodaCallContext ctx, …)`;
- replace `createRequestBuilder(url, token, method)`, `createRequest` and `sendRequest` with:

```java
    public static boolean isTxRouted(String path) {
        String p = path == null ? "" : (path.startsWith("/") ? path.substring(1) : path);
        return p.startsWith("entity") || p.startsWith("search") || p.startsWith("message");
    }

    private HttpRequest createRequest(CyodaCallContext ctx, String url, String path, String method, Object data) {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url)).header("Content-Type", "application/json");
        switch (ctx.credential()) {
            case CyodaCallContext.None n -> { }
            case CyodaCallContext.M2m m -> builder.header("Authorization", "Bearer " + tokenSource.bearerToken()
                    .orElseThrow(() -> new CyodaCredentialException("no M2M token source is configured")));
            case CyodaCallContext.Forward f -> builder.header("Authorization", "Bearer " + f.token());
        }
        if (ctx.isJoined() && isTxRouted(path)) {
            builder.header("X-Tx-Token", ctx.txToken());
        }
        HttpRequest.BodyPublisher body = data == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(jsonUtils.toJson(data), StandardCharsets.UTF_8);
        return builder.method(method, body).build();
    }

    private CompletableFuture<ObjectNode> sendRequest(CyodaCallContext ctx, String url, String path, String method,
                                                       Object data, ResponseBodyParser parser) {
        return send(ctx, url, path, method, data).thenCompose(response -> {
            boolean m2m = ctx.credential() instanceof CyodaCallContext.M2m;
            if (response.statusCode() == 401 && m2m) {
                tokenSource.invalidate();
                if (!ctx.isJoined()) {
                    return send(ctx, url, path, method, data);
                }
            }
            return CompletableFuture.completedFuture(response);
        }).thenApply(response -> {
            int status = response.statusCode();
            if (status >= 400) {
                throw CyodaErrors.fromHttp(status, response.body(), ctx.isJoined());
            }
            if (status >= 300) {
                logger.info("[{}] {} {} redirect: {}", status, method, url, response.body());
            }
            return parser.parse(response.body(), response.headers().firstValue("Content-Type").orElse(null), status);
        });
    }

    private CompletableFuture<HttpResponse<String>> send(CyodaCallContext ctx, String url, String path, String method, Object data) {
        return client.sendAsync(createRequest(ctx, url, path, method, data), HttpResponse.BodyHandlers.ofString());
    }
```

Each public method passes `path` through, e.g.:

```java
    public CompletableFuture<ObjectNode> sendGetRequest(CyodaCallContext ctx, String apiUrl, String path) {
        return sendRequest(ctx, buildUrlWithParams(apiUrl, path, null), path, "GET", null, defaultParser);
    }
```

Callers:
- **`WorkflowServiceImpl`, `EdgeMessageServiceImpl`, `CyodaInit`:** inject `CyodaCallContexts callContexts` instead of `CyodaTokenSource`. Each public method starts with `CyodaCallContext ctx = callContexts.current();` and passes `ctx` to `HttpUtils`. `CyodaInit.initCyoda` builds it once and threads it through its private methods in place of `String token`.
- **`WorkflowService`:** add:

```java
    /** Imports workflows for a model: POST model/{name}/{version}/workflow/import. Never joins a transaction. */
    JsonNode importWorkflows(ModelSpec modelSpec, JsonNode workflows, String importMode);
```

and implement it in `WorkflowServiceImpl`:

```java
    @Override
    public JsonNode importWorkflows(ModelSpec modelSpec, JsonNode workflows, String importMode) {
        CyodaCallContext ctx = callContexts.current();
        ObjectNode body = objectMapper.createObjectNode();
        body.put("importMode", importMode);
        body.set("workflows", workflows);
        String path = String.format("model/%s/%d/workflow/import", modelSpec.getName(), modelSpec.getVersion());
        return httpUtils.sendPostRequest(ctx, config.getCyodaApiUrl(), path, body).join();
    }
```

(Inject `ObjectMapper` if `WorkflowServiceImpl` does not have it yet.)

- [ ] **Step 4: Run the tests**

```bash
./gradlew test
./gradlew integrationTest -Dcyoda.bin=build/cyoda-bin/cyoda
```

Expected: both BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A src/main src/test
git commit -m "feat(http): REST calls take the call context; X-Tx-Token only on entity/search/message paths

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 23: Open a `CalloutScope` around every processor and criterion

**Files:**
- Modify: `src/main/java/com/java_template/common/grpc/client/event_handling/AbstractEventStrategy.java` (`handleEvent`)
- Modify: `src/main/java/com/java_template/common/workflow/CyodaEventContext.java` (`txToken()` default method)
- Test: `src/test/java/com/java_template/common/grpc/client/CalloutScopeLifecycleTest.java`

**Interfaces:**
- Consumes: `CalloutScope` (Task 19).
- Produces: `default String CyodaEventContext.txToken()` (nullable). While a processor or criterion runs, `CalloutScope.current()` holds its token.

- [ ] **Step 1: Write the failing test**

```java
package com.java_template.common.grpc.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.java_template.common.call.CalloutScope;
import com.java_template.common.grpc.client.event_handling.ProcessorEventStrategy;
import com.java_template.common.workflow.CyodaContextFactory;
import com.java_template.common.workflow.CyodaProcessor;
import com.java_template.common.workflow.OperationFactory;
import io.cloudevents.v1.proto.CloudEvent;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationResponse;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CalloutScopeLifecycleTest {

    @Test
    void theScopeHoldsTheTokenDuringTheCalloutAndIsGoneAfterwards() throws Exception {
        ObjectMapper om = new ObjectMapper();
        AtomicReference<String> seenToken = new AtomicReference<>();
        AtomicReference<Object> seenAuth = new AtomicReference<>("unset");
        AtomicReference<String> contextToken = new AtomicReference<>();
        CyodaProcessor processor = mock(CyodaProcessor.class);
        when(processor.process(any())).thenAnswer(inv -> {
            seenToken.set(CalloutScope.current().map(CalloutScope::txToken).orElse(null));
            seenAuth.set(SecurityContextHolder.getContext().getAuthentication());
            contextToken.set(((com.java_template.common.workflow.CyodaEventContext<?>) inv.getArgument(0)).txToken());
            return new EntityProcessorCalculationResponse();
        });
        OperationFactory factory = mock(OperationFactory.class);
        when(factory.getProcessorForModel(any())).thenReturn(processor);
        ProcessorEventStrategy strategy = new ProcessorEventStrategy(factory, om, new CyodaContextFactory(om));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("leftover", "x", List.of()));

        ObjectNode request = om.createObjectNode();
        request.put("id", "evt-1").put("requestId", "r-1").put("entityId", UUID.randomUUID().toString())
                .put("processorId", "p").put("processorName", "P");
        ObjectNode payload = request.putObject("payload");
        payload.put("type", "ENTITY");
        payload.putObject("meta").putObject("modelKey").put("name", "m").put("version", 1);
        payload.putObject("data");
        CloudEvent ce = CloudEvent.newBuilder().setId("ce").setSource("s").setSpecVersion("1.0")
                .setType("EntityProcessorCalculationRequest").setTextData(om.writeValueAsString(request))
                .putAttributes("cyodatxtoken", CloudEvent.CloudEventAttributeValue.newBuilder().setCeString("tx-abc").build())
                .build();

        strategy.handleEvent(ce);

        assertThat(seenToken.get()).isEqualTo("tx-abc");
        assertThat(contextToken.get()).isEqualTo("tx-abc");
        assertThat(seenAuth.get()).isNull();
        assertThat(CalloutScope.current()).isEmpty();
    }
}
```

The processor is only reached if `OperationSpecification.create` accepts this minimal request. If it throws, add the fields it reports (e.g. transition and state names) to `request`. The assertions stay unchanged.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests com.java_template.common.grpc.client.CalloutScopeLifecycleTest`
Expected: FAIL; `txToken()` does not compile, and `seenToken` would be null.

- [ ] **Step 3: Implement**

In `CyodaEventContext`, replace the `// TODO: add gRPC access object to Cyoda` line with:

```java
    /** The callout's transaction token (CloudEvent attribute cyodatxtoken), or null when the dispatch carries none. */
    default String txToken() {
        var attr = getCloudEvent().getAttributesMap().get("cyodatxtoken");
        return attr == null ? null : attr.getCeString();
    }
```

In `AbstractEventStrategy.handleEvent`, replace `return executeOperation(operation, request, context);` with:

```java
            try (CalloutScope ignored = CalloutScope.open(context.txToken())) {
                return executeOperation(operation, request, context);
            }
```

(Import `com.java_template.common.call.CalloutScope`.)

- [ ] **Step 4: Run the tests**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A src/main src/test
git commit -m "feat(compute): processors and criteria run inside a CalloutScope carrying cyodatxtoken

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 24: Auth context as data: comma-separated `authclaims` and `requireRole`

**Files:**
- Modify: `src/main/java/com/java_template/common/auth/CloudEventAuthContext.java`
- Modify: `src/main/java/com/java_template/common/auth/CloudEventAuthContextExtractor.java`
- Modify: `src/main/java/com/java_template/common/workflow/CyodaEventContext.java` (`authContext()` default method)
- Modify: `src/test/java/com/java_template/common/auth/CloudEventAuthContextExtractorTest.java` (rewrite)

**Interfaces:**
- Produces:
  - `record CloudEventAuthContext(Type type, String id, List<String> roles)`, with:
    - `enum Type { USER, SERVICE, SYSTEM }`;
    - `static CloudEventAuthContext empty()`, `boolean isEmpty()`, `boolean requireRole(String role)`;
    - `AUTH_TYPES` (kept from Task 8);
  - `static CloudEventAuthContext CloudEventAuthContextExtractor.from(CloudEvent)`;
  - `default CloudEventAuthContext CyodaEventContext.authContext()`.

- [ ] **Step 1: Rewrite the test (failing)**

```java
package com.java_template.common.auth;

import io.cloudevents.v1.proto.CloudEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CloudEventAuthContextExtractorTest {

    private static CloudEvent.CloudEventAttributeValue str(String v) {
        return CloudEvent.CloudEventAttributeValue.newBuilder().setCeString(v).build();
    }

    private static CloudEvent event(String type, String id, String claims) {
        CloudEvent.Builder b = CloudEvent.newBuilder().setId("e").setSource("s").setSpecVersion("1.0").setType("t");
        if (type != null) b.putAttributes("authtype", str(type));
        if (id != null) b.putAttributes("authid", str(id));
        if (claims != null) b.putAttributes("authclaims", str(claims));
        return b.build();
    }

    @Test
    void parsesTheCommaSeparatedWireForm() {
        CloudEventAuthContext ctx = CloudEventAuthContextExtractor.from(event("user", "mock-user-001", "ROLE_ADMIN,ROLE_M2M"));

        assertThat(ctx.type()).isEqualTo(CloudEventAuthContext.Type.USER);
        assertThat(ctx.id()).isEqualTo("mock-user-001");
        assertThat(ctx.roles()).containsExactly("ROLE_ADMIN", "ROLE_M2M");
    }

    @Test
    void trimsAndDropsBlanks() {
        assertThat(CloudEventAuthContextExtractor.from(event("service", "c", " a , ,b ")).roles()).containsExactly("a", "b");
        assertThat(CloudEventAuthContextExtractor.from(event("service", "c", "")).roles()).isEmpty();
    }

    @Test
    void aJsonObjectIsNotRoles() {
        CloudEventAuthContext ctx = CloudEventAuthContextExtractor.from(
                event("user", "u", "{\"legalEntityId\":\"org-1\",\"roles\":[\"USER\"]}"));

        assertThat(ctx.roles()).isEmpty();
        assertThat(ctx.requireRole("USER")).isFalse();
    }

    @Test
    void unknownRetiredOrAbsentAuthTypeYieldsAnEmptyContext() {
        assertThat(CloudEventAuthContextExtractor.from(event("service_account", "x", "ROLE_M2M")).isEmpty()).isTrue();
        assertThat(CloudEventAuthContextExtractor.from(event(null, null, null)).isEmpty()).isTrue();
    }

    @Test
    void requireRoleIsFailClosedAndExact() {
        CloudEventAuthContext user = CloudEventAuthContextExtractor.from(event("user", "u", "ROLE_ADMIN"));
        CloudEventAuthContext service = CloudEventAuthContextExtractor.from(event("service", "c", "ROLE_M2M"));
        CloudEventAuthContext system = CloudEventAuthContextExtractor.from(event("system", null, "ROLE_ADMIN"));

        assertThat(user.requireRole("ROLE_ADMIN")).isTrue();
        assertThat(user.requireRole("ADMIN")).isFalse();
        assertThat(user.requireRole("role_admin")).isFalse();
        assertThat(service.requireRole("ROLE_M2M")).isTrue();
        assertThat(system.requireRole("ROLE_ADMIN")).isFalse();
        assertThat(CloudEventAuthContext.empty().requireRole("ROLE_ADMIN")).isFalse();
        assertThat(CloudEventAuthContextExtractor.from(event("user", "u", null)).requireRole("ROLE_ADMIN")).isFalse();
    }

    @Test
    void knowsExactlyTheThreeCyodaGoAuthTypes() {
        assertThat(CloudEventAuthContext.AUTH_TYPES).containsExactlyInAnyOrder("user", "service", "system");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests com.java_template.common.auth.CloudEventAuthContextExtractorTest`
Expected: compilation FAILS: `Type`, `from`, `requireRole` missing.

- [ ] **Step 3: Implement**

```java
package com.java_template.common.auth;

import java.util.List;
import java.util.Set;

/**
 * ABOUTME: The principal whose action triggered a callout, as data (spec §4.4). Roles come from the
 * comma-separated authclaims attribute. Trust basis: authclaims can be relied on only over a
 * server-verified TLS channel; with grpc-tls=false it is forgeable (cyoda-go authcontext-attribution.md).
 */
public record CloudEventAuthContext(Type type, String id, List<String> roles) {

    public enum Type { USER, SERVICE, SYSTEM }

    /** The only authtype values cyoda-go sends (service_account is retired). */
    public static final Set<String> AUTH_TYPES = Set.of("user", "service", "system");

    private static final CloudEventAuthContext EMPTY = new CloudEventAuthContext(null, null, List.of());

    public static CloudEventAuthContext empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return type == null;
    }

    /** Mirrors cyoda-go authctx.Require: true only for USER or SERVICE holding exactly this role. */
    public boolean requireRole(String role) {
        return (type == Type.USER || type == Type.SERVICE) && roles.contains(role);
    }
}
```

```java
package com.java_template.common.auth;

import io.cloudevents.v1.proto.CloudEvent;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** ABOUTME: Reads authtype/authid/authclaims from a callout CloudEvent (authclaims is comma-separated only). */
@Component
public class CloudEventAuthContextExtractor {

    public static CloudEventAuthContext from(CloudEvent cloudEvent) {
        var attrs = cloudEvent.getAttributesMap();
        String type = attrs.containsKey("authtype") ? attrs.get("authtype").getCeString() : null;
        if (type == null || !CloudEventAuthContext.AUTH_TYPES.contains(type)) {
            return CloudEventAuthContext.empty();
        }
        String id = attrs.containsKey("authid") ? attrs.get("authid").getCeString() : null;
        String claims = attrs.containsKey("authclaims") ? attrs.get("authclaims").getCeString() : "";
        return new CloudEventAuthContext(CloudEventAuthContext.Type.valueOf(type.toUpperCase()), id, parseRoles(claims));
    }

    static List<String> parseRoles(String claims) {
        if (claims == null || claims.isBlank() || claims.trim().startsWith("{")) {
            return List.of();
        }
        return Arrays.stream(claims.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** Kept for existing callers; prefer {@link #from(CloudEvent)} or CyodaEventContext.authContext(). */
    public Optional<CloudEventAuthContext> extract(CloudEvent cloudEvent) {
        CloudEventAuthContext ctx = from(cloudEvent);
        return ctx.isEmpty() ? Optional.empty() : Optional.of(ctx);
    }
}
```

In `CyodaEventContext` add:

```java
    /** The originating principal as data (never a credential); empty when absent or unknown. */
    default com.java_template.common.auth.CloudEventAuthContext authContext() {
        return com.java_template.common.auth.CloudEventAuthContextExtractor.from(getCloudEvent());
    }
```

In `CyodaContextFactory`, keep the unknown-`authtype` warning from Task 8.

- [ ] **Step 4: Run the tests**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A src/main src/test
git commit -m "feat(compute): authContext() with comma-separated authclaims and fail-closed requireRole

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 25: One virtual thread per processor/criterion task; pinning audit

**Files:**
- Modify: `src/main/java/com/java_template/common/grpc/client/ProcessorThreadExecutor.java`, `CriteriaThreadExecutor.java`
- Modify: `src/main/java/com/java_template/common/grpc/client/DefaultEventExecutionRouter.java` (implements `AutoCloseable`)
- Test: `src/test/java/com/java_template/common/grpc/client/ProcessorThreadExecutorTest.java`

**Interfaces:**
- Produces:
  - in virtual mode, `ProcessorThreadExecutor`/`CriteriaThreadExecutor` start one virtual thread per task; the pool size is ignored in that mode;
  - `DefaultEventExecutionRouter.close()` shuts down all three executors, and Spring calls it on context close.

- [ ] **Step 1: Write the failing test**

```java
package com.java_template.common.grpc.client;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessorThreadExecutorTest {

    @Test
    void virtualModeIsNotBoundedByThePoolSize() throws Exception {
        ProcessorThreadExecutor executor = new ProcessorThreadExecutor(true, 20);
        int tasks = 100;
        CountDownLatch allStarted = new CountDownLatch(tasks);
        CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < tasks; i++) {
            executor.run(() -> {
                allStarted.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        boolean started = allStarted.await(5, TimeUnit.SECONDS);
        release.countDown();
        executor.shutdown();

        assertThat(started).as("100 blocked tasks all started, so nested cascades cannot starve").isTrue();
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests com.java_template.common.grpc.client.ProcessorThreadExecutorTest`
Expected: FAIL; only 20 tasks start (`newFixedThreadPool(20, virtualFactory)`).

- [ ] **Step 3: Implement**

In `ProcessorThreadExecutor` (and the same in `CriteriaThreadExecutor`, with the name prefix `criteria-calculation-`), replace the virtual branch with:

```java
        if (useVirtualThreads) {
            // One virtual thread per task: a callout blocked on a nested cascade holds no scarce thread (spec §4.5).
            this.executorService = Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("processor-calculation-", 0).factory());
            log.info("Initialized ProcessorThreadExecutor with one virtual thread per task (pool size ignored)");
        } else {
```

Make `DefaultEventExecutionRouter` implement `AutoCloseable`:

```java
    @Override
    public void close() {
        for (CalculationExecutionStrategy e : new CalculationExecutionStrategy[]{processorExecutor, criteriaExecutor, controlExecutor}) {
            if (e instanceof ProcessorThreadExecutor p) p.shutdown();
            if (e instanceof CriteriaThreadExecutor c) c.shutdown();
            if (e instanceof ControlThreadExecutor k) k.shutdown();
        }
    }
```

- [ ] **Step 4: Pinning audit**

```bash
grep -rn -E 'synchronized|ConcurrentHashMap\.compute|\.computeIfAbsent\(' src/main/java/com/java_template/common | grep -v '^.*://'
```

For each hit, check whether the locked region makes a blocking Cyoda call (gRPC stub, `HttpUtils`, token fetch):
- `ConnectionManager.sendEvent` (`synchronized`) only calls `StreamObserver.onNext`, which is non-blocking, so it stays.
- The `Authentication` token fetch was moved out in Task 20.

Record the findings in the commit message. If a hit does block, replace the `synchronized` with a `ReentrantLock` in this task.

- [ ] **Step 5: Run the tests**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add -A src/main src/test
git commit -m "feat(threads): one virtual thread per processor/criterion task; executors closed with the context

Pinning audit: ConnectionManager.sendEvent (non-blocking onNext) is the only synchronized
region on a Cyoda call path; the token fetch moved out of the bin lock in the previous commit.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 26: Cascade and auth-context integration tests

**Files:**
- Create: `src/integrationTest/java/com/java_template/it/support/ItCascadeProcessor.java`
- Create: `src/integrationTest/java/com/java_template/it/support/ItAuthProbeProcessor.java`
- Modify: `src/integrationTest/resources/it-workflows/thing-workflow.json` (add `cascade` and `probe`)
- Test: `src/integrationTest/java/com/java_template/it/CascadeAtomicityIT.java`
- Test: `src/integrationTest/java/com/java_template/it/CascadeConcurrencyIT.java`
- Test: `src/integrationTest/java/com/java_template/it/AuthContextPlumbingIT.java`

**Interfaces:**
- Consumes: `EntityService`, `WorkflowService.importWorkflows` (Task 22), `CyodaEventContext.authContext()` (Task 24), and the Task 13 fixtures.
- Produces: transitions `cascade` (`new` → `cascaded`) and `probe` (`new` → `probed`).

- [ ] **Step 1: Extend the workflow**

In `thing-workflow.json`, add to `states.new.transitions`:

```json
        {
          "name": "cascade", "next": "cascaded", "manual": true,
          "processors": [{"type": "externalized", "name": "ItCascadeProcessor", "executionMode": "SYNC",
            "config": {"attachEntity": true, "calculationNodesTags": "${tag}", "responseTimeoutMs": 60000, "retryPolicy": "NONE"}}]
        },
        {
          "name": "probe", "next": "probed", "manual": true,
          "processors": [{"type": "externalized", "name": "ItAuthProbeProcessor", "executionMode": "SYNC",
            "config": {"attachEntity": true, "calculationNodesTags": "${tag}", "responseTimeoutMs": 10000, "retryPolicy": "NONE"}}]
        }
```

and add the states `"cascaded": {"transitions": []}` and `"probed": {"transitions": []}`.

- [ ] **Step 2: Write the processors**

```java
package com.java_template.it.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.java_template.common.dto.EntityWithMetadata;
import com.java_template.common.repository.SearchAndRetrievalParams;
import com.java_template.common.serializer.ProcessorSerializer;
import com.java_template.common.serializer.SerializerFactory;
import com.java_template.common.service.EntityService;
import com.java_template.common.service.WorkflowService;
import com.java_template.common.workflow.CyodaEventContext;
import com.java_template.common.workflow.CyodaProcessor;
import com.java_template.common.workflow.OperationSpecification;
import org.cyoda.cloud.api.common.model.GroupConditionDto;
import org.cyoda.cloud.api.common.model.SimpleConditionDto;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationRequest;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * amount = remaining cascade depth: creates a child (amount-1, ref = parent id) and fires "cascade" on it.
 * note switches: "fail-after-cascade" throws after the child write; "admin-inside" imports a workflow
 * for model "<model>_admin"; "timeout-param" tries a save with transaction-control parameters.
 */
@Component
public class ItCascadeProcessor implements CyodaProcessor {

    public final Map<UUID, Integer> childrenSeenBySearch = new ConcurrentHashMap<>();
    public final Set<UUID> timeoutParamRejected = ConcurrentHashMap.newKeySet();

    private final ProcessorSerializer serializer;
    private final EntityService entityService;
    private final WorkflowService workflowService;
    private final ObjectMapper om = new ObjectMapper();

    public ItCascadeProcessor(SerializerFactory f, EntityService entityService, WorkflowService workflowService) {
        this.serializer = f.getDefaultProcessorSerializer();
        this.entityService = entityService;
        this.workflowService = workflowService;
    }

    @Override
    public EntityProcessorCalculationResponse process(CyodaEventContext<EntityProcessorCalculationRequest> context) {
        return serializer.withRequest(context.getEvent()).toEntityWithMetadata(ItThing.class).map(c -> {
            EntityWithMetadata<ItThing> parent = c.entityResponse();
            String model = parent.getModelKey().getName();
            ModelSpec spec = new ModelSpec().withName(model).withVersion(1);
            ItThing thing = parent.entity().in(model);
            String note = thing.getNote();

            if (thing.getAmount() > 0) {
                ItThing child = ItThing.of(model, thing.getName() + ">", thing.getAmount() - 1);
                child.setRef(parent.getId().toString());
                EntityWithMetadata<ItThing> created = entityService.create(child);
                entityService.update(created.getId(), created.entity().in(model), "cascade");
                var byRef = new GroupConditionDto().operator(GroupConditionDto.OperatorEnum.AND).conditions(List.of(
                        new SimpleConditionDto().jsonPath("$.ref")
                                .operatorType(SimpleConditionDto.OperatorTypeEnum.EQUALS)
                                .value(TextNode.valueOf(parent.getId().toString()))));
                childrenSeenBySearch.put(parent.getId(),
                        entityService.search(spec, byRef, ItThing.class, SearchAndRetrievalParams.defaults()).data().size());
            }
            if ("admin-inside".equals(note)) {
                try {
                    var wf = om.readTree(getClass().getResourceAsStream("/it-workflows/thing-workflow.json"));
                    workflowService.importWorkflows(new ModelSpec().withName(model + "_admin").withVersion(1),
                            om.createArrayNode().add(com.java_template.testing.cyoda.WorkflowTemplating.applyTag(wf, "unused-tag")), "REPLACE");
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            }
            if ("timeout-param".equals(note)) {
                try {
                    entityService.save(List.of(ItThing.of(model, "never", 0)), 10, 1000L);
                } catch (IllegalArgumentException expected) {
                    timeoutParamRejected.add(parent.getId());
                }
            }
            if ("fail-after-cascade".equals(note)) {
                throw new IllegalStateException("fail-after-cascade");
            }
            thing.setNote("cascaded");
            return parent;
        }).complete();
    }

    @Override
    public boolean supports(OperationSpecification spec) {
        return "ItCascadeProcessor".equals(spec.operationName());
    }
}
```

```java
package com.java_template.it.support;

import com.java_template.common.auth.CloudEventAuthContext;
import com.java_template.common.serializer.ProcessorSerializer;
import com.java_template.common.serializer.SerializerFactory;
import com.java_template.common.workflow.CyodaEventContext;
import com.java_template.common.workflow.CyodaProcessor;
import com.java_template.common.workflow.OperationSpecification;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationRequest;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationResponse;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ItAuthProbeProcessor implements CyodaProcessor {

    public final Map<UUID, CloudEventAuthContext> seen = new ConcurrentHashMap<>();
    private final ProcessorSerializer serializer;

    public ItAuthProbeProcessor(SerializerFactory f) {
        this.serializer = f.getDefaultProcessorSerializer();
    }

    @Override
    public EntityProcessorCalculationResponse process(CyodaEventContext<EntityProcessorCalculationRequest> context) {
        CloudEventAuthContext auth = context.authContext();
        return serializer.withRequest(context.getEvent()).toEntityWithMetadata(ItThing.class).map(c -> {
            seen.put(c.entityResponse().getId(), auth);
            return c.entityResponse();
        }).complete();
    }

    @Override
    public boolean supports(OperationSpecification spec) {
        return "ItAuthProbeProcessor".equals(spec.operationName());
    }
}
```

- [ ] **Step 3: Write the ITs**

```java
package com.java_template.it;

import com.fasterxml.jackson.databind.node.TextNode;
import com.java_template.common.config.Config;
import com.java_template.common.dto.EntityWithMetadata;
import com.java_template.common.grpc.client.monitoring.ConnectionStateTracker;
import com.java_template.common.repository.SearchAndRetrievalParams;
import com.java_template.common.service.EntityService;
import com.java_template.it.support.ItCascadeProcessor;
import com.java_template.it.support.ItThing;
import com.java_template.testing.cyoda.*;
import org.cyoda.cloud.api.common.model.GroupConditionDto;
import org.cyoda.cloud.api.common.model.SimpleConditionDto;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@CyodaIntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CascadeAtomicityIT {

    @Autowired EntityService entityService;
    @Autowired Config config;
    @Autowired ConnectionStateTracker tracker;
    @Autowired ItCascadeProcessor cascade;

    private String model;
    private ModelSpec spec;
    private CyodaRest rest;

    @BeforeAll
    void createModel() {
        CyodaAwait.memberReady(tracker, Duration.ofSeconds(20));
        model = "cascade_it_" + UUID.randomUUID().toString().substring(0, 8);
        spec = new ModelSpec().withName(model).withVersion(1);
        rest = CyodaTestEnvironment.rest(Profile.mockMemory());
        CyodaModelSetup.createModel(rest, model, 1, ItThing.sampleData(),
                WorkflowTemplating.load("/it-workflows/thing-workflow.json", config.getGrpcProcessorTag()));
    }

    private List<EntityWithMetadata<ItThing>> childrenOf(UUID parentId) {
        var byRef = new GroupConditionDto().operator(GroupConditionDto.OperatorEnum.AND).conditions(List.of(
                new SimpleConditionDto().jsonPath("$.ref").operatorType(SimpleConditionDto.OperatorTypeEnum.EQUALS)
                        .value(TextNode.valueOf(parentId.toString()))));
        return entityService.search(spec, byRef, ItThing.class, SearchAndRetrievalParams.builder().inMemory(true).build()).data();
    }

    @Test
    void theJoinedChildCommitsWithTheParentAndIsVisibleToSearchInsideTheScope() {
        EntityWithMetadata<ItThing> parent = entityService.create(ItThing.of(model, "p", 1));

        EntityWithMetadata<ItThing> done = entityService.update(parent.getId(), parent.entity().in(model), "cascade");

        assertThat(done.getState()).isEqualTo("cascaded");
        assertThat(childrenOf(parent.getId())).singleElement().satisfies(c -> assertThat(c.getState()).isEqualTo("cascaded"));
        assertThat(cascade.childrenSeenBySearch.get(parent.getId())).isEqualTo(1);
    }

    @Test
    void aFailureAfterTheCascadeRollsBackParentAndChild() {
        ItThing p = ItThing.of(model, "f", 1);
        p.setNote("fail-after-cascade");
        EntityWithMetadata<ItThing> parent = entityService.create(p);

        assertThatThrownBy(() -> entityService.update(parent.getId(), parent.entity().in(model), "cascade"))
                .hasStackTraceContaining("fail-after-cascade");

        assertThat(childrenOf(parent.getId())).isEmpty();
        assertThat(entityService.getById(parent.getId(), spec, ItThing.class).getState()).isEqualTo("new");
    }

    @Test
    void aModelAdminCallInsideTheProcessorCarriesNoTokenAndSucceeds() {
        rest.post("model/import/JSON/SAMPLE_DATA/" + model + "_admin/1", ItThing.sampleData()).requireSuccess();
        ItThing p = ItThing.of(model, "a", 0);
        p.setNote("admin-inside");
        EntityWithMetadata<ItThing> parent = entityService.create(p);

        EntityWithMetadata<ItThing> done = entityService.update(parent.getId(), parent.entity().in(model), "cascade");

        assertThat(done.getState()).isEqualTo("cascaded");
        assertThat(rest.get("model/" + model + "_admin/1/workflow/export").requireSuccess().raw()).contains("it-thing");
    }

    @Test
    void transactionControlParametersAreRejectedLocallyInsideTheScope() {
        ItThing p = ItThing.of(model, "t", 0);
        p.setNote("timeout-param");
        EntityWithMetadata<ItThing> parent = entityService.create(p);

        entityService.update(parent.getId(), parent.entity().in(model), "cascade");

        assertThat(cascade.timeoutParamRejected).contains(parent.getId());
    }
}
```

```java
package com.java_template.it;

import com.java_template.common.config.Config;
import com.java_template.common.dto.EntityWithMetadata;
import com.java_template.common.grpc.client.monitoring.ConnectionStateTracker;
import com.java_template.common.service.EntityService;
import com.java_template.it.support.ItThing;
import com.java_template.testing.cyoda.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Regression for spec §4.5: nested + concurrent cascades must not exhaust a thread pool. */
@CyodaIntegrationTest
class CascadeConcurrencyIT {

    @Autowired EntityService entityService;
    @Autowired Config config;
    @Autowired ConnectionStateTracker tracker;

    @Test
    void sixteenConcurrentThreeLevelCascadesAllComplete() throws Exception {
        CyodaAwait.memberReady(tracker, Duration.ofSeconds(20));
        String model = "conc_it_" + UUID.randomUUID().toString().substring(0, 8);
        CyodaModelSetup.createModel(CyodaTestEnvironment.rest(Profile.mockMemory()), model, 1, ItThing.sampleData(),
                WorkflowTemplating.load("/it-workflows/thing-workflow.json", config.getGrpcProcessorTag()));
        List<EntityWithMetadata<ItThing>> parents = entityService.save(
                java.util.stream.IntStream.range(0, 16).mapToObj(i -> ItThing.of(model, "c" + i, 3)).toList());

        try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<EntityWithMetadata<ItThing>>> runs = parents.stream()
                    .map(p -> CompletableFuture.supplyAsync(
                            () -> entityService.update(p.getId(), p.entity().in(model), "cascade"), callers))
                    .toList();

            CompletableFuture.allOf(runs.toArray(CompletableFuture[]::new)).get(60, TimeUnit.SECONDS);

            assertThat(runs).allSatisfy(r -> assertThat(r.join().getState()).isEqualTo("cascaded"));
        }
    }
}
```

```java
package com.java_template.it;

import com.java_template.common.auth.CloudEventAuthContext;
import com.java_template.common.config.Config;
import com.java_template.common.dto.EntityWithMetadata;
import com.java_template.common.grpc.client.monitoring.ConnectionStateTracker;
import com.java_template.common.service.EntityService;
import com.java_template.it.support.ItAuthProbeProcessor;
import com.java_template.it.support.ItThing;
import com.java_template.testing.cyoda.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@CyodaIntegrationTest
class AuthContextPlumbingIT {

    @Autowired EntityService entityService;
    @Autowired Config config;
    @Autowired ConnectionStateTracker tracker;
    @Autowired ItAuthProbeProcessor probe;

    @Test
    void theProcessorSeesTheMockPrincipalAsData() {
        CyodaAwait.memberReady(tracker, Duration.ofSeconds(20));
        String model = "auth_it_" + UUID.randomUUID().toString().substring(0, 8);
        CyodaModelSetup.createModel(CyodaTestEnvironment.rest(Profile.mockMemory()), model, 1, ItThing.sampleData(),
                WorkflowTemplating.load("/it-workflows/thing-workflow.json", config.getGrpcProcessorTag()));
        EntityWithMetadata<ItThing> created = entityService.create(ItThing.of(model, "a", 1));

        entityService.update(created.getId(), created.entity().in(model), "probe");

        CloudEventAuthContext auth = probe.seen.get(created.getId());
        assertThat(auth.type()).isEqualTo(CloudEventAuthContext.Type.USER);
        assertThat(auth.id()).isEqualTo("mock-user-001");
        assertThat(auth.roles()).containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_M2M");
        assertThat(auth.requireRole("ROLE_ADMIN")).isTrue();
        assertThat(auth.requireRole("ADMIN")).isFalse();
    }
}
```

`ItCascadeProcessor` imports `WorkflowTemplating` from `testFixtures`, which is on the `integrationTest` classpath.

- [ ] **Step 4: Run the full tier-1 suite**

Run: `./gradlew integrationTest -Dcyoda.bin=build/cyoda-bin/cyoda`
Expected: BUILD SUCCESSFUL. `CascadeConcurrencyIT` completes well inside 60 s.

If `CascadeAtomicityIT.theJoinedChildCommits…` fails because the child is invisible or unattributed, capture the cyoda log and the failing assertion before changing code:
- the Global Constraints forbid working around cyoda-go;
- a cyoda-go defect gets an issue in `Cyoda/cyoda-go`;
- a framework defect gets fixed here.

- [ ] **Step 5: Commit**

```bash
git add src/integrationTest
git commit -m "test(it): joined cascades commit and roll back together; 16×3 concurrent cascades; auth context as data

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 27: PR 2 wrap-up: sync-guide rows, full verification, PR

**Files:**
- Modify: `SYNCING_WITH_JAVA_TEMPLATE.md`

- [ ] **Step 1: Add the PR 2 rows to the breaking-changes table**

Append these rows to the table added in Task 17:

```markdown
| `CrudRepository` | methods without context | every method takes `CyodaCallContext ctx` first; build it with `CyodaCallContexts.current()` |
| `HttpUtils` | `send*Request(String token, …)` | `send*Request(CyodaCallContext ctx, …)`; constructor takes `CyodaTokenSource` |
| gRPC interceptor | `ClientAuthorizationInterceptor` | `CyodaCallInterceptor`; a stub call without `CyodaCallInterceptor.CONTEXT` is cancelled (`FAILED_PRECONDITION`) |
| Errors | `ResponseStatusException`; gRPC failures as `CyodaOperationException("CLIENT_ERROR", …)` | `CyodaHttpException` (status + `errorCode`); real codes from the message prefix; typed `CyodaCalloutEndedException`, `CyodaRetryableException`, `CyodaJoinedResponseTooLargeException`, `CyodaCommitInJoinedTransactionException`, `CyodaAccessDeniedException` |
| Compute context | — | `CyodaEventContext.authContext()`, `txToken()`; `CloudEventAuthContext(type, id, roles)` with `requireRole`; `CalloutScope.wrap`/`unjoined` |
| Callbacks from processors | separate requests as the processor's credential | joined to the callout's transaction (M2M + tx-token): atomic with the transition, attributed to its origin; inside a scope search is direct (≤ 10 000, page 0) and transaction-control parameters are refused |
| BFF credential | OBO exchange (or `OboTokenException`) | an app's own JWT `SecurityFilterChain` now forwards the user's IdP token unchanged; any other authenticated principal is a `CyodaCredentialException` |
| Threads | fixed pools of 20 (also in "virtual" mode) | `execution-mode: virtual` = one virtual thread per processor/criterion task |
```

- [ ] **Step 2: Full verification (superpowers:verification-before-completion)**

```bash
./gradlew clean check -Dcyoda.bin=build/cyoda-bin/cyoda
./gradlew -p buildSrc test
grep -rn -E 'TODO|TBD|FIXME' src/main/java/com/java_template/common/call src/main/java/com/java_template/common/exception || echo "no markers"
```

Expected: BUILD SUCCESSFUL for both, then `no markers`. Record the unit and integration test counts.

- [ ] **Step 3: Commit and open PR 2**

```bash
git add SYNCING_WITH_JAVA_TEMPLATE.md
git commit -m "docs(sync): PR 2 breaking changes (call context, errors, callout scope, threads)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

Use superpowers:finishing-a-development-branch to open PR 2. The PR body ends with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
