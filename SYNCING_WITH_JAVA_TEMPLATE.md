# Syncing the Java Client Template

The `java-client-template` project provides the shared framework layer (`com.java_template.common`), the cyoda-go contract it is generated from, and the test harness used by Cyoda client applications. This guide describes how to pull improvements made in downstream projects back into the template, and how downstream projects take template updates, so all projects benefit.

## What the template owns

### Framework (canonical home is the template: replace, do not merge)

| Path (relative to `apps/backend/`) | Description |
|------|-------------|
| `src/main/java/com/java_template/common/` | Framework code: auth, config, gRPC, serializer, service, workflow, util, tool, observability, controller, dto, repository, exception |
| `src/main/kotlin/org/cyoda/uuid/` | UUID utility functions |
| `src/main/resources/META-INF/` | Spring Boot auto-configuration registrations |
| `src/main/resources/cyoda/` | The cyoda-go contract: `CYODA_VERSION` (the pin), `CYODA_SHA256SUMS` (for a released pin), `proto/`, `schema/`, `openapi/openapi.yaml`. Vendored unmodified by `scripts/sync-cyoda-contract.sh`; never edit by hand |
| `src/main/resources/application-cyoda-local.yml` | The `cyoda-local` profile for a local cyoda-go (no credentials) |
| `buildSrc/` | Build logic: contract transforms for code generation, and the `installCyoda` task; `buildSrc/gradle/verification-metadata.xml` holds the checksums of its nested test build |
| `scripts/` | `sync-cyoda-contract.sh` (refresh the contract) and `install-cyoda.sh` (install the pinned cyoda) |
| `src/testFixtures/` | The cyoda test harness: `@CyodaIntegrationTest`, `CyodaServer`, `CyodaBinary`, `CyodaRest`, `CyodaModelSetup`, … |
| `src/test/java/com/java_template/common/` | Unit tests for the framework layer |
| `src/test/java/com/java_template/testing/` | Unit tests for the harness and the docs (`DocsLinksTest`) |
| `src/test/java/com/example/`, `src/test/resources/example/` | Example entity, processor, criterion, controller and workflow configs: the reference implementation downstream projects start from |
| `src/test/kotlin/org/cyoda/uuid/` | UUID utility tests |
| `src/integrationTest/java/com/java_template/it/`, `src/integrationTest/resources/it-workflows/`, `src/integrationTest/resources/entity-schemas/examples/ExampleEntity/` | The template's tier-1 integration suite (framework behaviour against a real cyoda-go) |
| `.dockerignore` | Keeps the image build context lean |

### Shared config (template provides the structure, apps adapt values)

| Path | Template provides | Apps customize |
|------|-------------------|----------------|
| `build.gradle` | Plugins, codegen config, shared deps, the `integrationTest` source set and task (with its `installCyoda` dependency), `buildLogicTest`, `testFixtures` wiring | App-specific deps, main class references, app-specific tasks |
| `gradle/verification-metadata.xml` | SHA-256 checksums for every dependency the template's build resolves | Regenerate it after merging `build.gradle` (see Step 5); never hand-edit checksums |
| `src/main/resources/application.yml` | Structure and Cyoda platform config sections | App-specific values, ports, auth config, feature flags |
| `.gitignore` | Ignores every `application-*.yml` except `application-cyoda-local.yml`, and `.cyoda/` | App-specific entries |

**App-specific JSON schemas.** jsonschema2pojo reads only the vendored `src/main/resources/cyoda/schema/` tree (through `prepareEventSchemas`) and generates it into `org.cyoda.cloud.api.event`. App schemas are not generated: do not add them under `cyoda/schema/`, which `sync-cyoda-contract.sh` replaces wholesale. An app that wants generated types adds its own jsonschema2pojo configuration and source directory in its own `build.gradle`, or writes the classes by hand.

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
- Bug fixes in `common/` classes, the harness (`src/testFixtures/`) or the build logic (`buildSrc/`, `scripts/`)
- New framework capabilities (new service methods, new utility classes)
- Performance improvements in shared infrastructure
- Test coverage improvements for `common/` and the harness
- Build plugin or shared dependency version bumps

**Do not pull:**
- App-specific workarounds in `common/` (e.g. a resource path changed to suit one app's layout)
- Changes that only make sense in the context of that app's business logic
- Temporary fixes that should be solved differently in the template
- Edits to the vendored contract under `src/main/resources/cyoda/`: the contract changes only through `scripts/sync-cyoda-contract.sh` (Step 4)

### Step 2: Diff to understand the delta

```bash
SOURCE=~/dev/<downstream-project>/apps/backend
TEMPLATE=apps/backend

# Framework source
diff -rq $TEMPLATE/src/main/java/com/java_template/common/ \
         $SOURCE/src/main/java/com/java_template/common/
diff -rq $TEMPLATE/src/main/kotlin/ $SOURCE/src/main/kotlin/

# Resources
diff -rq $TEMPLATE/src/main/resources/META-INF/ $SOURCE/src/main/resources/META-INF/
diff -rq $TEMPLATE/src/main/resources/cyoda/ $SOURCE/src/main/resources/cyoda/
diff $TEMPLATE/src/main/resources/application-cyoda-local.yml $SOURCE/src/main/resources/application-cyoda-local.yml

# Build logic and scripts
diff -rq -x build -x .gradle $TEMPLATE/buildSrc/ $SOURCE/buildSrc/
diff -rq $TEMPLATE/scripts/ $SOURCE/scripts/

# Harness and tests
diff -rq $TEMPLATE/src/testFixtures/ $SOURCE/src/testFixtures/
diff -rq $TEMPLATE/src/test/java/com/java_template/ \
         $SOURCE/src/test/java/com/java_template/
diff -rq $TEMPLATE/src/test/java/com/example/ $SOURCE/src/test/java/com/example/
diff -rq $TEMPLATE/src/test/resources/example/ $SOURCE/src/test/resources/example/
diff -rq $TEMPLATE/src/test/kotlin/ $SOURCE/src/test/kotlin/
diff -rq $TEMPLATE/src/integrationTest/java/com/java_template/it/ \
         $SOURCE/src/integrationTest/java/com/java_template/it/
diff -rq $TEMPLATE/src/integrationTest/resources/it-workflows/ \
         $SOURCE/src/integrationTest/resources/it-workflows/

# Build config
diff $TEMPLATE/build.gradle $SOURCE/build.gradle
diff $TEMPLATE/.dockerignore $SOURCE/.dockerignore
```

Review each difference. Classify it as "template improvement" or "app-specific divergence."

### Step 3: Copy the framework files

For files classified as template improvements, replace wholesale. **Do not merge — replace.**

```bash
SOURCE=~/dev/<downstream-project>/apps/backend
TEMPLATE=apps/backend

replace() { # <relative dir>
  rm -rf "$TEMPLATE/$1"
  mkdir -p "$(dirname "$TEMPLATE/$1")"
  cp -R "$SOURCE/$1" "$TEMPLATE/$1"
}

# Framework source, Kotlin utilities and Spring auto-configuration
replace src/main/java/com/java_template/common/
replace src/main/kotlin/org/
replace src/main/resources/META-INF/

# Build logic (without its build output) and scripts
rm -rf $TEMPLATE/buildSrc/src/
cp -R $SOURCE/buildSrc/src/ $TEMPLATE/buildSrc/src/
cp $SOURCE/buildSrc/build.gradle $TEMPLATE/buildSrc/build.gradle
mkdir -p $TEMPLATE/buildSrc/gradle
cp $SOURCE/buildSrc/gradle/verification-metadata.xml $TEMPLATE/buildSrc/gradle/verification-metadata.xml
replace scripts/

# Harness, framework tests, the reference implementation and the tier-1 suite
replace src/testFixtures/
replace src/test/java/com/java_template/
replace src/test/java/com/example/
replace src/test/resources/example/
replace src/test/kotlin/org/
replace src/integrationTest/java/com/java_template/it/
replace src/integrationTest/resources/it-workflows/

# Single files
cp $SOURCE/src/main/resources/application-cyoda-local.yml $TEMPLATE/src/main/resources/
cp $SOURCE/.dockerignore $TEMPLATE/.dockerignore

find $TEMPLATE/src/ -name ".DS_Store" -delete
```

### Step 4: The cyoda-go contract

`src/main/resources/cyoda/` is cyoda-go's contract, vendored unmodified, never merged. If the downstream project is on a newer pin (`CYODA_VERSION`), re-run the sync in the template instead of copying files:

```bash
scripts/sync-cyoda-contract.sh --from-src <cyoda-go checkout at that commit> --version <x.y.z[-dev]>
```

For a released version this also records `CYODA_SHA256SUMS`, which `scripts/install-cyoda.sh` checks the downloaded binary against. The resulting `git diff` is the contract change; codegen and the tests then show what it breaks.

### Step 5: Handle build.gradle

Compare and selectively apply changes:

```bash
diff $TEMPLATE/build.gradle $SOURCE/build.gradle
```

**Pull into the template:**
- Plugin version bumps
- New shared dependencies used by `common/`
- Codegen config changes (jsonSchema2Pojo, protobuf, openapi)
- Source set changes, and the `integrationTest`, `installCyoda`, `buildLogicTest` and `testFixtures` wiring
- Shared dependency version bumps

**Do not pull:**
- App-specific `mainClass` references (template uses `com.java_template.Application`)
- App-specific dependencies (e.g. PDFBox, Trino JDBC)
- App-specific Gradle tasks
- Removal of `repositories {}` block (downstream projects may use root `settings.gradle` for this; the template may need it standalone)

After any dependency change, regenerate the checksums (see "Dependency verification" in `CONTRIBUTING.md`) and review the diff of `gradle/verification-metadata.xml`.

### Step 6: Revert app-specific divergences

After copying, check for app-specific changes the downstream project made to framework files (Step 1's "Do not pull" list). Revert them to the template's convention:

```bash
git diff $TEMPLATE/src/main/java/com/java_template/common/
# Review each change — revert app-specific modifications
```

### Step 7: Compile and test

```bash
./gradlew :apps:backend:compileJava :apps:backend:compileKotlin
./gradlew :apps:backend:test
./gradlew :apps:backend:integrationTest   # installs the pinned cyoda into .cyoda/bin if needed
```

The template has no application code beyond the `com/example/` reference implementation. If the pulled changes compile and the example and integration tests pass, the framework is self-consistent.

If compilation fails, the downstream project introduced a dependency on their application code — that change should not have been pulled. Identify and revert it.

### Step 8: Verify the example project still works

The `com/example/` unit tests and the tier-1 integration suite (`src/integrationTest/`, against a real cyoda-go) exercise:
- Entity creation, retrieval, update, search
- Processor execution and criterion evaluation
- Controller CRUD operations
- Model and workflow setup, and workflow configuration marshalling

All of them must pass. If a framework change breaks the example, the example needs updating (this is part of the template, so update it here).

---

## Propagating template updates to downstream projects

After updating the template, downstream projects need to pull the changes. Each downstream project should have its own syncing guide (e.g. `SYNCING_WITH_JAVA_TEMPLATE.md`). The general process for a downstream project:

1. Replace the framework paths from the template (same copy commands as above, reversed)
2. Take the template's `src/main/resources/cyoda/` as a whole (or re-run `scripts/sync-cyoda-contract.sh` at the template's pin)
3. Re-apply any known app-specific divergences
4. Compile — fix any breakage in application code caused by framework API changes
5. Run the unit and integration tests — fix any failures
6. Align build.gradle shared sections, then regenerate `gradle/verification-metadata.xml`

### Common breakage in downstream projects after a template update

These are the failure patterns we've observed. Document new ones here as they occur.

| Symptom | Cause | Fix |
|---------|-------|-----|
| `no suitable method found for search(ModelSpec, GroupCondition, ...)` | `EntityService` method signatures changed parameter types | Update application code to use new types (e.g. `GroupCondition` → `GroupConditionDto`) |
| `incompatible types: String cannot be converted to UUID` | Schema change altered generated class field types | Update application code and tests to use `UUID` |
| `package X does not exist` | New dependency added in `common/` | Add the dependency to the downstream `build.gradle` |
| `Dependency verification failed` | A dependency (new, or a different version) has no checksum in `gradle/verification-metadata.xml` | Regenerate the metadata (`CONTRIBUTING.md`, "Dependency verification") and review the diff |
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

# 3. Unit and integration tests pass
./gradlew :apps:backend:test :apps:backend:integrationTest

# 4. No stray files
git status
find apps/backend/src/ -name ".DS_Store" -delete
```

## Breaking changes in the cyoda-go v0.9.0 alignment

The template now implements cyoda-go's contract. Contract files are vendored under
`src/main/resources/cyoda/` (refresh with `scripts/sync-cyoda-contract.sh`). Replace, do not merge.

| Area | Before | After |
|---|---|---|
| Local profile file | `.gitignore` ignored every `application-*.yml`; the README told you to create `application-local.yml` with your own settings | the template ships `src/main/resources/application-cyoda-local.yml` (profile `cyoda-local`: a local cyoda-go with mock IAM, app on `127.0.0.1:8081`, no credentials), the only `application-*.yml` that `.gitignore` un-ignores. An existing `application-local.yml` is untouched and still ignored, so settings in it stay out of git. But if it holds credentials and sits in `src/main/resources`, move them out (to `config/application-cloud.yml` at the project root, or environment variables): `bootJar` packages everything under `src/main/resources` into the jar, git-ignored or not. The image build (`.dockerignore`) excludes every `application-*.yml` under `src/main/resources/` except `application-cyoda-local.yml`, and `.env` files |
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
| `EntityCrudOperations` point in time | REST `OffsetDateTime` converted to `Date` before the call (truncated to ms) | passed through unchanged — a test stubbing the `Date` overload (e.g. `any(Date.class)`) no longer matches these calls: the mock returns `null` and the test fails at runtime (e.g. a 404) without any compile error; stub the `OffsetDateTime` overload |
| `EntityWithMetadata` JSON (`metadata.creationDate`, `getCreationDate()`) | `Date` written by Jackson's `StdDateFormat`: `2026-09-27T10:11:12.123+00:00` | `OffsetDateTime` written as ISO-8601 by the app's own mapper, e.g. `2026-09-27T10:11:12.123456789Z` (nanosecond digits, `Z`). A client parsing the old fixed-millisecond pattern must accept ISO-8601. The app's mapper needs `JavaTimeModule` (Spring Boot's default mapper has it) |
| Framework `ObjectMapper` | the framework injected Spring's primary `ObjectMapper` and used it as-is for Cyoda payloads and event/OpenAPI DTOs (no Cyoda-specific configuration of its own) | the framework has its own wire mapper, the `CyodaObjectMapper` bean (a copy of the app's primary mapper plus `CyodaJackson.configure`). The app's primary mapper is no longer modified: set `spring.jackson.*` as you want for your own REST API. `CyodaRepository`, `EntityServiceImpl`, `CloudEventBuilder`/`CloudEventParser`, the event strategies, `CyodaContextFactory`, `OperationFactory`, `JsonUtils`, `HttpUtils`, `CyodaInit` and `EdgeMessageServiceImpl` take `CyodaObjectMapper` instead of `ObjectMapper`; in tests pass `CyodaObjectMapper.standalone()`. `JacksonProcessorSerializer`/`JacksonCriterionSerializer` keep their `ObjectMapper` constructor for tests (pass `CyodaObjectMapper.standalone().mapper()` or `CyodaJackson.configure(new ObjectMapper())`) |
| `AbstractEventStrategy` (custom strategies) | constructor `(OperationFactory, ObjectMapper, CyodaContextFactory, EventAuthContextHandler)`; abstract hooks `getRequestClass`, `createOperationSpecification`, `executeOperation`, `createErrorResponse`, `setRequestIdInErrorResponse` | constructor `(OperationFactory, CyodaObjectMapper, CyodaContextFactory)`, and three new abstract hooks: `requestIdOf(TRequest)` (the callout's `requestId`, not the event `id`), `setEntityIdInErrorResponse(TResponse, TRequest)`, `setRecoveredEntityId(TResponse, String)` |
| Callout response `id` / `requestId` / `entityId` | response `id` echoed the request's `id`; an error response's `requestId` was the request event's `id` | response `id` is a fresh UUID; `requestId` echoes the callout's `requestId` and `entityId` is carried separately, on success and error responses (error responses recover them from unparsable JSON where possible) |
| `Authentication` bean | always present (`@Service`), injected directly | conditional: present only when `app.config.auth-mode` is `client-credentials` (either spelling, e.g. `CLIENT_CREDENTIALS`); with `none` the token source is `NoCyodaAuthentication`. Inject `CyodaTokenSource` instead of `Authentication` |
| REST error bodies (`EntityCrudOperations`, example controller) | the ProblemDetail `detail` included the exception's message (internal hosts, URLs, Cyoda error text) | a generic `detail` plus a `correlationId` (also a ProblemDetail property); the exception is logged at ERROR with that id (`ErrorResponses`). The status stays 400, except 500 for a call whose credential cannot be determined or obtained (`CyodaCredentialException`) or that Cyoda rejects as unauthenticated (gRPC `UNAUTHENTICATED`, REST 401): a server-side fault, not the client's. The gRPC `UNAUTHENTICATED` description for a credential that cannot be obtained is the generic "failed to obtain a Cyoda token"; the cause stays attached for server-side logs |
| `app.config.ssl-trusted-hosts` | listing any host made the REST and token `HttpClient` trust every certificate from every host | certificate checks are skipped only for a listed host (matched on the name the connection uses, port ignored); every other host is validated normally. `ssl-trust-all` still trusts everything |
| Helm chart (`helm/`) | `values.yaml` set `CYODA_HOST`, `CYODA_CLIENT_ID`, `CYODA_CLIENT_SECRET`, `GRPC_*`, `SSL_*`, … as plain env values, none of which bind to `app.config.*`, and the client secret was a plain value | the env names are `APP_CONFIG_*` (relaxed binding, e.g. `APP_CONFIG_GRPC_SERVER_PORT`); the unbound `*_AI_API`, `ENTITY_VERSION` and `EXTERNAL_CALCULATIONS_THREAD_POOL` are gone. The client secret comes from an existing Secret through `secretKeyRef` (`cyodaClientSecret.secretName`, required; `cyodaClientSecret.key`, default `client-secret`) as `APP_CONFIG_CYODA_CLIENT_SECRET`. `global.registry.host` and `image.tag` are required (defaults exist for every other `global.*`/`host.*` value, so the chart renders standalone). Ingress is off by default; when enabled it requires `host.name` and `ingress.tls`. Pod and container run as uid/gid 10001 (the Dockerfile's user), non-root, read-only root filesystem with an emptyDir `/tmp`, no privilege escalation, all capabilities dropped. Image pulls use an existing Secret (`global.imagePullSecret.existingSecretName`); building `regcred` from plaintext `global.registry.username/password` (`global.imagePullSecret.enabled`) is legacy and off by default. The unused top-level `imagePullSecret`/`imagePullSecrets` values are gone |
| gRPC admin endpoints (`GrpcAdminController`) | `POST /admin/grpc/reconnect?force=…` and `GET /admin/grpc/status` always on, unauthenticated under the default permit-all `SecurityConfig` | off unless `app.admin.grpc-endpoints.enabled=true`; enable only where your `SecurityFilterChain` protects `/admin/**`. The `force` parameter is gone: the reconnection strategy ignored a resurrect outside IDLE anyway, so "forcing" did nothing. `reconnect` works only in IDLE and answers 400 otherwise |
| Health probes | Helm probed `/actuator/health/...` (outside the `/api` context path) | Helm probes `/api/actuator/health/{liveness,readiness}`; `management.endpoint.health.probes.enabled: true`. If your `SecurityFilterChain` requires authentication, permit `/actuator/health/**`, or the probes get 401 and the pods never become ready |
| M2M token invalidation (`CyodaTokenSource.invalidate`) | `Authentication.invalidateTokens()` cleared only its own cached field, so Spring's `OAuth2AuthorizedClientService` handed the same (rejected, unexpired) token back on the "refetch" | `invalidate` also removes the authorized client from the client service, so the next fetch really gets a new token. `CyodaTokenSource` gains a default `invalidate(String rejectedToken)`: it drops the cached token only if it is still the one Cyoda refused, so a token another thread fetched since is kept. `HttpUtils` and `CyodaGrpcCalls` pass the exact token they sent. A custom `CyodaTokenSource` that caches tokens should override it; the no-arg `invalidate()` still drops whatever is cached |
| `CalculationExecutionStrategy` beans | `processorThreadExecutor`, `criteriaThreadExecutor`, `controlThreadExecutor` (+ `…Virtual`) `@Bean`s selected by `execution.mode` | removed: `GrpcClientAutoConfiguration.eventExecutionRouter()` builds the three executors itself from `app.config.execution-mode` and the `*-thread-pool` sizes; the executor constructors are now `(boolean useVirtualThreads, int threadPoolSize)`. Do not inject them |
| New and changed config | — | `app.config.auth-mode` (`client-credentials` default, or `none`); `app.config.grpc-call-deadline-ms` (default 120000, must be > 0; applies to each unary gRPC call only, never to the server-streaming `entityManageCollection`/`entitySearchCollection` calls); `app.config.allow-insecure-transport` (default `false`: with `client-credentials`, startup fails when the token URI derived from `cyoda-api-url` is not `https` or `grpc-tls=false`, unless the host is loopback; `true` allows plaintext on a trusted network; `auth-mode=none` logs a warning instead); `app.config.keep-alive-warning-threshold` (ms without a server keep-alive before a diagnostic warning, default 20000) |
| `--recreate-models` (`CyodaInit`) | deleted the model directly, so it failed with 409 `MODEL_ALREADY_LOCKED` on any model an earlier run had created (and locked) | unlocks the model first (`PUT model/{name}/{version}/unlock`, tolerating `MODEL_ALREADY_UNLOCKED`), then deletes and recreates it. It still fails with `MODEL_HAS_ENTITIES` while entities of that model exist |
| `build/libs` | only the runnable `app.jar` (plain `jar` disabled) | also `*-plain.jar` (the plain jar is needed by the `testFixtures` wiring) and `*-test-fixtures.jar`. The runnable jar is still `build/libs/app.jar`; do not glob `build/libs/*.jar` |
| `.cyoda/bin` | — | `integrationTest` depends on the new `installCyoda` task (`buildSrc` `InstallCyodaTask`), which runs `scripts/install-cyoda.sh` to install the pinned binary to `.cyoda/bin/cyoda` in the project root (git-ignored; add `.cyoda/` to your own `.gitignore`). It installs nothing when `-Dcyoda.bin` or `CYODA_BIN` is set, or when `.cyoda/bin/cyoda --version` already matches `CYODA_VERSION`, or, when `.cyoda/bin/cyoda` is absent, when a `cyoda` on `PATH` already matches it (the same binary `CyodaBinary.locate()` would then use). `-Dcyoda.allowVersionMismatch=true` also skips the install over a mismatching binary at `.cyoda/bin` or on `PATH`, so one placed there deliberately is never overwritten. The binary survives `./gradlew clean`, and the integration tests find it there automatically, after `-Dcyoda.bin` and `CYODA_BIN` and before `PATH`. Installing a `-dev` pin builds from source (Go ≥ 1.26.7, `git`, network); a released pin is downloaded (network). On Windows, or without those, pass `-Dcyoda.bin`/`CYODA_BIN` or build with `-x integrationTest`. A binary installed elsewhere (`--dest <dir>`) is named with `CYODA_BIN` or `-Dcyoda.bin` |
| `CYODA_SHA256SUMS` | — | for a released pin, `scripts/sync-cyoda-contract.sh` records the release archives' SHA-256 in `CYODA_SHA256SUMS`, next to `CYODA_VERSION` (from the release's `SHA256SUMS`, or `--sha256sums <file>`), and `scripts/install-cyoda.sh` refuses an archive that does not match it, or a release pin without it. A `-dev` pin (source build) needs none. Sync the file together with `CYODA_VERSION` |
| Dependency versions | Spring Boot 3.5.3; `jackson-databind` pinned at 2.19.1 | Spring Boot 3.5.16 (latest 3.5 patch); `jackson-databind` and `spring-boot-starter-web` versions come from Boot's BOM (Jackson 2.21.4); patch bumps for httpclient5 (5.5.2), springdoc (2.8.17), JUnit (5.13.4), jsonschema2pojo (1.2.2) and, in `buildSrc`, Jackson (2.19.4) and AssertJ (3.27.7) |
| Dependency verification | — | Gradle verifies every downloaded artifact against `gradle/verification-metadata.xml` (and `buildSrc/gradle/verification-metadata.xml` for the nested `buildSrc` test run). After merging `build.gradle`, regenerate both with the command in `CONTRIBUTING.md` ("Dependency verification") and review the diff; a dependency without a checksum fails the build with "Dependency verification failed" |
| `deleteAll` | chunked (`transactionSize` 1000) | one transaction by default: the template no longer sends `transactionSize` (cyoda-go still honours it when sent, #379); `pageSize` removed from `EntityDeleteAllRequest` |
| `EntityService.findByBusinessIdOrNull` / `findByCompositeKeyOrNull` | returned `null` on any exception, as well as when nothing matched | return `null` only when nothing matches. Every failure propagates as the exception the call raised (unwrapped from `CompletionException`), including `CyodaCalloutEndedException` and `CyodaRetryableException`. `EntityCrudOperations.create`/`createWithCompositeKey` therefore answer an error instead of going on to create when the duplicate check fails; catch around your own `*OrNull` calls if you relied on the old behaviour |
| `EntityService` / `WorkflowService` exceptions | a failed call threw `CompletionException` (from `.join()`), with the real exception as its cause | the typed Cyoda exception is thrown directly (spec §4.4): `CyodaCalloutEndedException`, `CyodaRetryableException`, `CyodaCommitInJoinedTransactionException`, `CyodaJoinedResponseTooLargeException`, `CyodaAccessDeniedException`, otherwise `CyodaOperationException`/`CyodaHttpException` (or the repository's own `RuntimeException`, e.g. a gRPC `StatusRuntimeException`). Code that catches `CompletionException` around these calls, or unwraps `getCause()`, must catch the typed exception or `RuntimeException` instead. `CyodaExceptionUtil` works with both. `WorkflowService.exportWorkflows` still throws `WorkflowExportException`, whose cause is now the typed exception rather than a `CompletionException`. `EdgeMessageService` follows the same rule (see its own row) |
| `EdgeMessageService` errors | a failed call threw `CompletionException` (from `.join()`) or `IllegalStateException` wrapping the real exception; the documented `null` (getMessageById/getMessageContent) and `false` (deleteMessage) for a missing message were never returned, because `HttpUtils` threw on the 404 first | the typed Cyoda exception is thrown directly, unwrapped (`CyodaCalloutEndedException`, `CyodaRetryableException`, `CyodaAccessDeniedException`, otherwise `CyodaHttpException` with the HTTP status and cyoda error code). A 404 (`CyodaHttpException.status() == 404`) now gives the documented `null`/`false`; `TRANSACTION_NOT_FOUND` (also a 404) is a `CyodaCalloutEndedException` and propagates. `createMessage` has no not-found result: every failure propagates. Code that caught `IllegalStateException` or `CompletionException` around these calls must catch the typed exception or `RuntimeException` |
| Errors (REST/gRPC mapping) | REST failures raised `ResponseStatusException`; gRPC failures were surfaced as `CyodaOperationException("CLIENT_ERROR", …)` regardless of the real cause | `CyodaErrors.fromHttp`/`fromGrpcEnvelope` map both to the same typed exceptions, read from the real error code (REST: the problem detail's `properties.errorCode`; gRPC: the envelope message's prefix up to the first colon): `CyodaHttpException` (REST, carries the HTTP status), `CyodaOperationException` (gRPC, no HTTP status), `CyodaCalloutEndedException`, `CyodaRetryableException`, `CyodaJoinedResponseTooLargeException`, `CyodaCommitInJoinedTransactionException`, `CyodaAccessDeniedException` |
| `CrudRepository` | methods carried no call context | every method takes a `CyodaCallContext ctx` first — build it once per operation with `CyodaCallContexts.current()`, never re-derive it per call; `pointInTime` parameters are `OffsetDateTime` |
| `HttpUtils` | constructor `(JsonUtils, CyodaObjectMapper, Config)`; `send*Request(String token, …)` | constructor `(JsonUtils, CyodaObjectMapper, Config, CyodaTokenSource)`; every `send*Request(CyodaCallContext ctx, …)` sets `Authorization` from `ctx.credential()` and attaches `X-Tx-Token` only when `ctx.isJoined()` **and** the path is tx-routed (`entity`/`search`/`message` — `HttpUtils.isTxRouted`). Only a 401 is retried, only for an M2M credential, and only once, after invalidating the cached token; a 401 on a joined call is never retried — it is mapped to `CyodaCalloutEndedException` instead. REST never retries `TOO_MANY_JOINED_REQUESTS` or `TRANSACTION_NODE_UNAVAILABLE` (those surface as `CyodaRetryableException`) |
| gRPC interceptor | `ClientAuthorizationInterceptor` | `CyodaCallInterceptor(CyodaTokenSource)`. A call with no `CyodaCallInterceptor.CONTEXT` in `CallOptions` is cancelled before it reaches the transport, with `FAILED_PRECONDITION`. A credential that cannot be resolved — an M2M token fetch failure, or a blank `Forward` (forwarded-JWT) token — is cancelled with `UNAUTHENTICATED`, never sent unauthenticated. `CyodaGrpcCalls.call` retries `UNAUTHENTICATED` once for an M2M credential (after invalidating the token; a `Forward` credential is never retried on `UNAUTHENTICATED`), and retries a joined-retryable code (`TOO_MANY_JOINED_REQUESTS`, `TRANSACTION_NODE_UNAVAILABLE`) up to 3 times with backoff. `app.config.grpc-call-deadline-ms` applies only to unary calls (the `CyodaRepository` stub), never to the server-streaming `entityManageCollection`/`entitySearchCollection` calls |
| Compute context | — | `CyodaEventContext.authContext()` returns the callout's `CloudEventAuthContext` (type/id/roles from `authtype`/`authid`/`authclaims`) as data, never a credential — `authclaims` is comma-separated; `txToken()` returns the callout's `cyodatxtoken` CloudEvent attribute, or `null` when the dispatch carries none. `CloudEventAuthContext(type, id, roles)`; `requireRole(role)` is true only for `USER`/`SERVICE` holding exactly that role and fails closed otherwise (`SYSTEM`, an empty context, or no match all return `false`). `CalloutScope.wrap`/`wrapSupplier` carry the current callout scope onto another thread so its calls still join the transaction; `unjoined` detaches the current thread from the transaction (M2M, no tx-token) only while inside an open callout scope, and is a no-op — `body` runs unchanged, under whatever credential would otherwise apply — outside one (it never downgrades a credential, spec §4.2). A scope ends when the callout is answered (`CalloutScope.end()`/`close()`); work wrapped onto another thread that runs after that point fails with `CyodaCalloutEndedException` |
| Callbacks from processors | separate requests, as the processor's own credential | joined to the callout's transaction: inside an open `CalloutScope`, `CyodaCallContexts.current()` returns M2M with the scope's tx-token, so `EntityService`/`CrudRepository` calls commit atomically with the transition. Inside that scope: a caller-supplied `pointInTime` is refused with `IllegalArgumentException` before any call is sent; transaction-control parameters (`transactionWindow`, `transactionTimeoutMs`) are refused the same way; a search runs direct (not an async snapshot) capped at `CyodaRepository.DIRECT_SEARCH_LIMIT` (10 000) — only page 0 can be read, and a full page at the cap fails with `IllegalStateException`, since whether more rows exist can't be told. `EntityService.searchAsStream`/`streamAll` ignore `pageSize` (and `inMemory`) there: each runs ONE direct search at the 10 000 limit and streams every match (up to 9 999), read into memory at once; a result that fills the limit fails with `IllegalStateException` when the stream is created, before anything is streamed. (Before this, a stream inside a scope read page 0 at `pageSize`, default 100, and failed on page 1.) Outside a scope streams page lazily at `pageSize` as before |
| BFF credential | OBO exchange (or `OboTokenException`) | `CyodaCallContexts.current()`: an authenticated `JwtAuthenticationToken` forwards the user's token unchanged (a blank token value is refused with `CyodaCredentialException`); any other authenticated, non-anonymous principal is refused with `CyodaCredentialException` — the framework never silently downgrades to M2M. Work handed off to another thread (e.g. an unwrapped `CompletableFuture.supplyAsync`) does not carry Spring Security's thread-local `SecurityContext`, so `current()` there sees no authentication and the call goes out M2M instead of forwarding the user's token; propagate the context yourself (e.g. a `DelegatingSecurityContextExecutor`) to keep forwarding it |
| Threads | fixed pools of 20 (also selectable in "virtual" mode) | `app.config.execution-mode: virtual` (the default) gives each processor and criterion task its own virtual thread (`Executors.newThreadPerTaskExecutor`; `processor-thread-pool`/`criteria-thread-pool` are ignored). Control events (greet, keep-alive, ack) keep a small fixed pool of `control-thread-pool` virtual threads. `platform` mode keeps a fixed pool of the configured size per executor, as before. `EntityService`/`CrudRepository` always run their gRPC calls on their own `Executors.newVirtualThreadPerTaskExecutor()`, independent of `execution-mode` |
| Snapshot search paging | a page request with a searchId could silently start a new search, and `totalElements` was the running count (often 0) | a searchId (= snapshotId) always pages the same snapshot, and `totalElements` is the final count. An expired or unknown searchId fails instead of re-searching |
| Config | `app.config.cyoda-light.*`, `skip-ssl`, `execution.mode`, `cyoda.api.url` | removed; use `cyoda-api-url`, `grpc-*`, `grpc-tls`, `app.config.execution-mode` (default `virtual`), `auth-mode` |
| Local cyoda-go | cyoda-light toggle | `--spring.profiles.active=cyoda-local` (app on `127.0.0.1:8081`, `auth-mode: none`) |
| OBO | `app.obo.*`, `OboAwareAuthentication`, `OboKeyRegistrationService`, … | removed; compute calls Cyoda as M2M |
| Event user resolver | `app.event.auth-context.*`, `EventAuthContextHandler`, … | removed |
| `authtype` values | `user`, `service_account` | `user`, `service`, `system` |
| Workflow JSON | `"version": "1.0"` | `"version": "1.5"`; `$.` on every `jsonPath` |
| `CyodaInit` | imported a workflow without checking for its model | creates the model before importing its workflow; a workflow import for a missing model is rejected by cyoda-go with `MODEL_NOT_FOUND` |
| Tests | Cucumber `GherkinE2eTest` | JUnit `src/integrationTest` with `@CyodaIntegrationTest`; `./gradlew check` needs the pinned cyoda binary, which the build installs to `.cyoda/bin` itself (see the `.cyoda/bin` row); `./gradlew build -x integrationTest` without one |
