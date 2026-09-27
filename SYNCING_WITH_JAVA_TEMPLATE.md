# Syncing the Java Client Template

The `java-client-template` project provides the shared framework layer (`com.java_template.common`) used by Cyoda client applications. This guide describes how to pull improvements made in downstream projects back into the template so all projects benefit.

## What the template owns

### Framework code (canonical home is the template)

| Path (relative to `apps/backend/`) | Description |
|------|-------------|
| `src/main/java/com/java_template/common/` | Framework code: auth, config, gRPC, serializer, service, workflow, util, tool, observability, controller, dto, repository, exception |
| `src/main/kotlin/org/cyoda/uuid/` | UUID utility functions |
| `src/test/java/com/java_template/common/` | Unit tests for the framework layer |
| `src/test/java/com/example/` | Example entity, processor, criterion, controller — reference implementation that downstream projects use as a starting point |
| `src/test/kotlin/org/cyoda/uuid/` | UUID utility tests |
| `src/test/resources/example/` | Example workflow configs for the reference implementation |
| `src/main/resources/api/` | OpenAPI specs for Cyoda platform APIs (codegen input) |
| `src/main/resources/META-INF/` | Spring Boot auto-configuration registrations |
| `src/main/resources/proto/` | Protobuf definitions for gRPC (CloudEvents, Cyoda Cloud API) |

### Shared config (template provides the structure, apps adapt values)

| Path | Template provides | Apps customize |
|------|-------------------|----------------|
| `src/main/resources/schema/` | `common/`, `entity/`, `model/`, `processing/`, `search/` subdirectories | Apps may add schemas (e.g. `common/condition/`, `common/statemachine/conf/`) for jsonschema2pojo types their application code needs |
| `src/main/resources/logback.xml` | Structure and appenders | `<contextName>` and `{"application":...}` value |
| `src/main/resources/applicationExample.yml` | Full config template with comments | App name in issuer/key-id fields; app-specific sections added or removed |
| `build.gradle` | Plugins, codegen config, shared deps | App-specific deps, main class references, app-specific tasks |
| `src/main/resources/application.yml` | Structure and Cyoda platform config sections | App-specific values, ports, auth config, feature flags |

### Not part of the template (app-specific, never pull)

| Path | Description |
|------|-------------|
| `src/main/java/com/<app_package>/application/` | Business logic: controllers, services, entities, processors, criteria, DTOs |
| `src/main/resources/workflow/` | App entity workflow FSM definitions |
| `src/main/resources/entity/` | App entity JSON schemas |
| `src/main/resources/entity-schemas/` | App entity example data |
| `src/test/java/com/<app_package>/application/` | App-specific unit tests |
| `src/integrationTest/java/com/<app_package>/` | App-specific integration tests (`@CyodaIntegrationTest`) |

---

## Pulling improvements from a downstream project

When a downstream project improves something in the shared framework layer, those improvements should be pulled into the template.

### Step 1: Evaluate what changed

Before pulling, understand what the downstream project changed and why. Not every change belongs in the template.

**Pull into the template:**
- Bug fixes in `common/` classes
- New framework capabilities (new service methods, new utility classes)
- Performance improvements in shared infrastructure
- Test coverage improvements for `common/`
- Schema updates that reflect Cyoda platform API changes
- OpenAPI spec updates
- Protobuf definition updates
- Build plugin or shared dependency version bumps

**Do not pull:**
- App-specific workarounds (e.g. changing `OboKeyRegistrationService` resource paths to avoid mixing with app workflows)
- Changes that only make sense in the context of that app's business logic
- Temporary fixes that should be solved differently in the template
- Extra schemas that only exist because the app's `application/` code needs them

### Step 2: Diff to understand the delta

```bash
SOURCE=~/dev/<downstream-project>/apps/backend
TEMPLATE=apps/backend

# Framework source
diff -rq $TEMPLATE/src/main/java/com/java_template/common/ \
         $SOURCE/src/main/java/com/java_template/common/

# Kotlin utilities
diff -rq $TEMPLATE/src/main/kotlin/ $SOURCE/src/main/kotlin/

# Tests
diff -rq $TEMPLATE/src/test/java/com/java_template/common/ \
         $SOURCE/src/test/java/com/java_template/common/
diff -rq $TEMPLATE/src/test/java/com/example/ \
         $SOURCE/src/test/java/com/example/
diff -rq $TEMPLATE/src/test/kotlin/ $SOURCE/src/test/kotlin/

# Resources
diff -rq $TEMPLATE/src/main/resources/api/ $SOURCE/src/main/resources/api/
diff -rq $TEMPLATE/src/main/resources/META-INF/ $SOURCE/src/main/resources/META-INF/
diff -rq $TEMPLATE/src/main/resources/proto/ $SOURCE/src/main/resources/proto/
diff -rq $TEMPLATE/src/main/resources/schema/ $SOURCE/src/main/resources/schema/

# Build config
diff $TEMPLATE/build.gradle $SOURCE/build.gradle
```

Review each difference. Classify it as "template improvement" or "app-specific divergence."

### Step 3: Copy the framework files

For files classified as template improvements, replace wholesale. **Do not merge — replace.**

```bash
SOURCE=~/dev/<downstream-project>/apps/backend
TEMPLATE=apps/backend

# Framework source code
rm -rf $TEMPLATE/src/main/java/com/java_template/common/
cp -R $SOURCE/src/main/java/com/java_template/common/ \
      $TEMPLATE/src/main/java/com/java_template/common/

# Kotlin utilities
rm -rf $TEMPLATE/src/main/kotlin/org/
cp -R $SOURCE/src/main/kotlin/org/ $TEMPLATE/src/main/kotlin/org/

# Framework tests
rm -rf $TEMPLATE/src/test/java/com/java_template/common/
cp -R $SOURCE/src/test/java/com/java_template/common/ \
      $TEMPLATE/src/test/java/com/java_template/common/

# Example entity tests
rm -rf $TEMPLATE/src/test/java/com/example/
cp -R $SOURCE/src/test/java/com/example/ \
      $TEMPLATE/src/test/java/com/example/

# Kotlin tests
rm -rf $TEMPLATE/src/test/kotlin/org/
cp -R $SOURCE/src/test/kotlin/org/ $TEMPLATE/src/test/kotlin/org/

# Example test resources
rm -rf $TEMPLATE/src/test/resources/example/
cp -R $SOURCE/src/test/resources/example/ \
      $TEMPLATE/src/test/resources/example/
find $TEMPLATE/src/test/resources/example/ -name ".DS_Store" -delete

# OpenAPI specs
rm -rf $TEMPLATE/src/main/resources/api/
cp -R $SOURCE/src/main/resources/api/ $TEMPLATE/src/main/resources/api/

# Spring auto-configuration
rm -rf $TEMPLATE/src/main/resources/META-INF/
cp -R $SOURCE/src/main/resources/META-INF/ $TEMPLATE/src/main/resources/META-INF/

# Protobuf definitions
rm -rf $TEMPLATE/src/main/resources/proto/
cp -R $SOURCE/src/main/resources/proto/ $TEMPLATE/src/main/resources/proto/
```

### Step 4: Handle schema/ carefully

The downstream project may have added app-specific schemas that do not belong in the template. Conversely, the downstream project may have updated shared schemas.

```bash
diff -rq $TEMPLATE/src/main/resources/schema/ $SOURCE/src/main/resources/schema/
```

- **Files in both:** Compare content. If the downstream version is newer/better, take it.
- **Files only in the source:** Evaluate — is this a shared schema improvement or an app-specific addition? Only copy shared improvements.
- **Files only in the template:** Keep them — the downstream project may have deleted schemas it doesn't use but other projects do.

**Known app-specific schemas to skip** (these exist in some downstream projects but should not be in the template):

| Schema | Why it exists downstream |
|--------|------------------------|
| `common/condition/*.json` | App uses jsonschema2pojo-generated condition types instead of OpenAPI Dto types |
| `common/statemachine/conf/*.json` | App uses jsonschema2pojo-generated workflow configuration types |
| `common/ExternalizedFunctionConfig.json` | App-specific jsonschema2pojo type |
| `common/ScheduledTransitionConfig.json` | App-specific jsonschema2pojo type |

### Step 5: Handle build.gradle

Compare and selectively apply changes:

```bash
diff $TEMPLATE/build.gradle $SOURCE/build.gradle
```

**Pull into the template:**
- Plugin version bumps
- New shared dependencies used by `common/`
- Codegen config changes (jsonSchema2Pojo, protobuf, openapi)
- Source set changes
- Shared dependency version bumps

**Do not pull:**
- App-specific `mainClass` references (template uses `com.java_template.Application`)
- App-specific dependencies (e.g. PDFBox, Trino JDBC)
- App-specific Gradle tasks
- Removal of `repositories {}` block (downstream projects may use root `settings.gradle` for this; the template may need it standalone)

### Step 6: Revert app-specific divergences

After copying, check for known app-specific changes that the downstream project made to `common/` files. These should NOT be pulled into the template.

Example from cyoda-test-management-system:

| File | App-specific change | Template should have |
|------|---------------------|---------------------|
| `common/auth/OboKeyRegistrationService.java` | Resource paths changed to `/obo-signing-key/workflow.json` | Original paths: `/workflow/v<version>/<entityname>.json` and `/entity-schemas/examples/<EntityName>/` |

After copying, revert these to the template's original convention:

```bash
git diff $TEMPLATE/src/main/java/com/java_template/common/
# Review each change — revert app-specific modifications
```

### Step 7: Compile and test

```bash
./gradlew :apps:backend:compileJava :apps:backend:compileKotlin
./gradlew :apps:backend:test
```

The template has no application code beyond the `com/example/` reference implementation. If the pulled changes compile and the example tests pass, the framework is self-consistent.

If compilation fails, the downstream project introduced a dependency on their application code — that change should not have been pulled. Identify and revert it.

### Step 8: Verify the example project still works

The `com/example/` test suite is the template's integration test. It exercises:
- Entity creation, retrieval, update
- Processor execution
- Criterion evaluation
- Controller CRUD operations
- Workflow configuration marshalling

All example tests must pass. If a framework change breaks the example, the example needs updating (this is part of the template, so update it here).

---

## Propagating template updates to downstream projects

After updating the template, downstream projects need to pull the changes. Each downstream project should have its own syncing guide (e.g. `SYNCING_WITH_JAVA_TEMPLATE.md`). The general process for a downstream project:

1. Replace shared directories from the template (same copy commands as above, reversed)
2. Handle schema/ carefully — keep app-specific schemas, update shared ones
3. Re-apply any known app-specific divergences
4. Compile — fix any breakage in application code caused by framework API changes
5. Run tests — fix any test failures
6. Align build.gradle shared sections

### Common breakage in downstream projects after a template update

These are the failure patterns we've observed. Document new ones here as they occur.

| Symptom | Cause | Fix |
|---------|-------|-----|
| `no suitable method found for search(ModelSpec, GroupCondition, ...)` | `EntityService` method signatures changed parameter types | Update application code to use new types (e.g. `GroupCondition` → `GroupConditionDto`) |
| `incompatible types: String cannot be converted to UUID` | Schema change altered generated class field types | Update application code and tests to use `UUID` |
| `package X does not exist` | New dependency added in `common/` | Add the dependency to the downstream `build.gradle` |
| `OboSigningKey workflow file not found on classpath` | OBO bootstrap resources missing | Add `workflow/v1/obosigningkey.json` and `entity-schemas/examples/OboSigningKey/obo-signing-key.json` (or the app's equivalent paths) |
| CORS validation failure at startup | `CorsProperties.validateSecurityConstraints()` rejects wildcard + credentials | Ensure `allow-credentials` is `false` when using wildcard CORS origins (typically in Docker/K8s) |

---

## Sync verification checklist

After any sync (pull or propagate), verify:

```bash
# 1. Code generation succeeds
./gradlew :apps:backend:generateProto \
          :apps:backend:generateJsonSchema2Pojo \
          :apps:backend:generateOpenApi

# 2. Compilation succeeds
./gradlew :apps:backend:compileJava :apps:backend:compileKotlin

# 3. Tests pass
./gradlew :apps:backend:test

# 4. No stray files
git status
find apps/backend/src/ -name ".DS_Store" -delete
```

## Breaking changes in the cyoda-go v0.9.0 alignment

The template now implements cyoda-go's contract. Contract files are vendored under
`src/main/resources/cyoda/` (refresh with `scripts/sync-cyoda-contract.sh`). Replace, do not merge.

| Area | Before | After |
|---|---|---|
| **Warning: `application-local.yml` and `.gitignore` (do this first)** | `.gitignore` ignored every `application-*.yml`, and the README told you to create `application-local.yml` with your settings, including Cloud credentials | the template ships `src/main/resources/application-local.yml` (local cyoda-go profile, no credentials) and un-ignores it. If your repo kept Cloud credentials in `application-local.yml`, move them to environment variables (`APP_CONFIG_CYODA_HOST`, `APP_CONFIG_CYODA_CLIENT_ID`, `APP_CONFIG_CYODA_CLIENT_SECRET`) or to a git-ignored `config/application-cloud.yml` (run with `--spring.profiles.active=cloud`) **before** syncing `.gitignore` and `application-local.yml`; otherwise the next `git add` can commit them |
| Contract files | `src/main/resources/{proto,schema,api}` | `src/main/resources/cyoda/{proto,schema,openapi}` + `CYODA_VERSION`; transforms in `buildSrc/` |
| OpenAPI DTO packages | `org.cyoda.cloud.api.{common,workflow,search,audit,iam}.model` | `org.cyoda.cloud.api.common.model` only |
| Condition operators | `OperatorTypeDto`, `GroupOperatorDto`, `.operation(…)` | `SimpleConditionDto.OperatorTypeEnum`, `LifecycleConditionDto.OperatorTypeEnum`, `GroupConditionDto.OperatorEnum`, `.operatorType(…)` |
| Nested conditions | `List<QueryConditionDto>` | `List<GroupConditionDtoAllOfConditions>` |
| `EntityCrudOperations.FieldFilter.operation` | `OperatorTypeDto` | `SimpleConditionDto.OperatorTypeEnum` |
| Simple-name clashes | — | `EntityChangeMeta`, `EntityMetadata`, `EntityTransactionResponse` exist in both `org.cyoda.cloud.api.common.model` and `org.cyoda.cloud.api.event.*`; do not wildcard-import both |
| Generated proto | `CloudEventBatch`, `Cloudevents` generated | not generated; `io.cloudevents.v1.proto.CloudEvent` comes from `cloudevents-protobuf` |
| Event DTO date-times | `java.util.Date` (millisecond precision) | `java.time.OffsetDateTime` for all generated event DTOs, lossless with cyoda-go's RFC3339Nano. Also covers `EntityWithMetadata.getCreationDate()` and the `CrudRepository` pointInTime params. `CyodaJackson` registers `JavaTimeModule` with `WRITE_DATES_AS_TIMESTAMPS` off |
| `EntityService` point in time | `java.util.Date pointInTime` only | every `EntityService` method with a `pointInTime` gains an `OffsetDateTime` overload (lossless; use it with `EntityChangeMeta.getTimeOfChange()`). The `Date` overloads remain as default methods that convert to UTC. A bare `null` literal for `pointInTime` is now ambiguous: cast it (`(OffsetDateTime) null`) or call the overload without `pointInTime`; the same applies to Mockito `isNull()`/`any()` for that argument (`isNull(OffsetDateTime.class)`). A custom `EntityService` implementation must implement the `OffsetDateTime` methods |
| `SearchAndRetrievalParams.pointInTime` | record component `Date`; builder `pointInTime(Date)` | record component `OffsetDateTime` (so `params.pointInTime()` returns `OffsetDateTime`); builder has `pointInTime(OffsetDateTime)` (full precision) and still `pointInTime(Date)` (converted to UTC); `pointInTime(null)` needs a cast |
| `EntityCrudOperations` point in time | REST `OffsetDateTime` converted to `Date` before the call (truncated to ms) | passed through unchanged |
| `EntityWithMetadata` JSON (`metadata.creationDate`, `getCreationDate()`) | `Date` written by Jackson's `StdDateFormat`: `2026-09-27T10:11:12.123+00:00` | `OffsetDateTime` written as ISO-8601 by the app's own mapper, e.g. `2026-09-27T10:11:12.123456789Z` (nanosecond digits, `Z`). A client parsing the old fixed-millisecond pattern must accept ISO-8601. The app's mapper needs `JavaTimeModule` (Spring Boot's default mapper has it) |
| Framework `ObjectMapper` | `CyodaJacksonAutoConfiguration` post-configured Spring's primary `ObjectMapper` (JavaTimeModule, `WRITE_DATES_AS_TIMESTAMPS` off, processor-type handler) | the framework has its own wire mapper, the `CyodaObjectMapper` bean (a copy of the app's primary mapper plus `CyodaJackson.configure`). The app's primary mapper is no longer modified: set `spring.jackson.*` as you want for your own REST API. `CyodaRepository`, `EntityServiceImpl`, `CloudEventBuilder`/`CloudEventParser`, the event strategies, `CyodaContextFactory`, `OperationFactory`, `JsonUtils`, `HttpUtils`, `CyodaInit` and `EdgeMessageServiceImpl` take `CyodaObjectMapper` instead of `ObjectMapper`; in tests pass `CyodaObjectMapper.standalone()`. `JacksonProcessorSerializer`/`JacksonCriterionSerializer` keep their `ObjectMapper` constructor for tests (pass `CyodaObjectMapper.standalone().mapper()` or `CyodaJackson.configure(new ObjectMapper())`) |
| `AbstractEventStrategy` (custom strategies) | constructor `(OperationFactory, ObjectMapper, CyodaContextFactory)` | constructor `(OperationFactory, CyodaObjectMapper, CyodaContextFactory)`, and three new abstract hooks: `requestIdOf(TRequest)` (the callout's `requestId`, not the event `id`), `setEntityIdInErrorResponse(TResponse, TRequest)`, `setRecoveredEntityId(TResponse, String)` |
| Callout response `id` / `requestId` / `entityId` | response `id` echoed the request's `id`; an error response's `requestId` was the request event's `id` | response `id` is a fresh UUID; `requestId` echoes the callout's `requestId` and `entityId` is carried separately, on success and error responses (error responses recover them from unparsable JSON where possible) |
| `Authentication` bean | always present (`@Service`), injected directly | conditional: present only when `app.config.auth-mode` is `client-credentials` (either spelling, e.g. `CLIENT_CREDENTIALS`); with `none` the token source is `NoCyodaAuthentication`. Inject `CyodaTokenSource` instead of `Authentication` |
| `CalculationExecutionStrategy` beans | `processorThreadExecutor`, `criteriaThreadExecutor`, `controlThreadExecutor` (+ `…Virtual`) `@Bean`s selected by `execution.mode` | removed: `GrpcClientAutoConfiguration.eventExecutionRouter()` builds the three executors itself from `app.config.execution-mode` and the `*-thread-pool` sizes; the executor constructors are now `(boolean useVirtualThreads, int threadPoolSize)`. Do not inject them |
| New and changed config | — | `app.config.auth-mode` (`client-credentials` default, or `none`); `app.config.grpc-call-deadline-ms` (default 120000; applies to each unary gRPC call only, never to the server-streaming `entityManageCollection`/`entitySearchCollection` calls); `app.config.keep-alive-warning-threshold` (ms without a server keep-alive before a diagnostic warning, default 20000) |
| `--recreate-models` (`CyodaInit`) | deleted the model directly, so it failed with 409 `MODEL_ALREADY_LOCKED` on any model an earlier run had created (and locked) | unlocks the model first (`PUT model/{name}/{version}/unlock`, tolerating `MODEL_ALREADY_UNLOCKED`), then deletes and recreates it. It still fails with `MODEL_HAS_ENTITIES` while entities of that model exist |
| `build/libs` | only the runnable `app.jar` (plain `jar` disabled) | also `*-plain.jar` (the plain jar is needed by the `testFixtures` wiring) and `*-test-fixtures.jar`. The runnable jar is still `build/libs/app.jar`; do not glob `build/libs/*.jar` |
| `build/cyoda-bin` | — | `scripts/install-cyoda.sh` installs the pinned binary to `build/cyoda-bin/cyoda` by default, and `./gradlew clean` deletes it. After a clean, re-run `scripts/install-cyoda.sh`, or install elsewhere with `--dest <dir>` and point `CYODA_BIN` (or `-Dcyoda.bin`) at it |
| `deleteAll` | chunked (`transactionSize` 1000) | one transaction by default: the template no longer sends `transactionSize` (cyoda-go still honours it when sent, #379); `pageSize` removed from `EntityDeleteAllRequest` |
| Snapshot search paging | a page request with a searchId could silently start a new search, and `totalElements` was the running count (often 0) | a searchId (= snapshotId) always pages the same snapshot, and `totalElements` is the final count. An expired or unknown searchId fails instead of re-searching |
| Config | `app.config.cyoda-light.*`, `skip-ssl`, `execution.mode`, `cyoda.api.url` | removed; use `cyoda-api-url`, `grpc-*`, `grpc-tls`, `app.config.execution-mode` (default `virtual`), `auth-mode` |
| Local cyoda-go | cyoda-light toggle | `--spring.profiles.active=local` (app on 8081, `auth-mode: none`) |
| OBO | `app.obo.*`, `OboAwareAuthentication`, `OboKeyRegistrationService`, … | removed; compute calls Cyoda as M2M |
| Event user resolver | `app.event.auth-context.*`, `EventAuthContextHandler`, … | removed |
| `authtype` values | `user`, `service_account` | `user`, `service`, `system` |
| Workflow JSON | `"version": "1.0"` | `"version": "1.5"`; `$.` on every `jsonPath` |
| `CyodaInit` | imported a workflow without checking for its model | creates the model before importing its workflow; a workflow import for a missing model is rejected by cyoda-go with `MODEL_NOT_FOUND` |
| Tests | Cucumber `GherkinE2eTest` | JUnit `src/integrationTest` with `@CyodaIntegrationTest`; `./gradlew check` needs a cyoda binary (`scripts/install-cyoda.sh`); `./gradlew build -x integrationTest` without one |
