# Align the Java client template with cyoda-go v0.9.0: sub-project 1a design

**Date:** 2026-09-26 (third revision, after two independent reviews)
**Status:** pending written-spec review
**Branch:** `feat/cyoda-go-v0.9-alignment`
**Contract source:** cyoda-go `release/v0.9.0` (its tip when implementation starts; `df6ad2c7`
when this was written; latest tag `v0.8.4`), plus `cyoda help` and docs.cyoda.net

## 1. Intent

cyoda-go is the digital twin of Cyoda Cloud. **cyoda-go defines the API and integration
contract, and Cyoda Cloud follows it.** The Java client template predates cyoda-go and is
stale against it.

### 1.1 Outcome

When the whole alignment is done:
- the template implements cyoda-go's contract;
- its contract artifacts are cyoda-go's own files;
- integration suites that start cyoda-go themselves prove it works end to end.

Where Cyoda Cloud differs from cyoda-go today, that difference is Cloud's to close. The
template has no workarounds for it (§9.2).

### 1.2 Success criteria for 1a

1. `./gradlew check` builds, runs the unit tests, and runs the tier-1 integration suite
   against an in-memory cyoda-go that the suite starts itself. There is no Docker and
   nothing needs to be running beforehand.
2. CI runs the same on every push and pull request.
3. Running a local cyoda (`cyoda init && cyoda`) and then
   `./gradlew runApp --args='--spring.profiles.active=cyoda-local'` works without further
   configuration. The app:
   - starts on port 8081;
   - joins as a compute member (the greet is received and logged);
   - serves `http://localhost:8081/api/swagger-ui/index.html`.

### 1.3 Decisions taken with the user

| Topic | Decision |
|---|---|
| Scope | Full alignment in sub-projects (§2). This spec is **1a**, delivered as two PRs (§2.1). |
| Cloud | The template implements cyoda-go's contract only. Cloud gaps are recorded as Cloud obligations, never worked around. |
| Launching cyoda-go in tests | Subprocess binary on free ports, as cyoda-go's own e2e suite does. No container for cyoda itself. |
| Auth modes | The template supports cyoda-go `mock` (no token) and `jwt`. |
| Test tiers | **Tier 1 (1a):** everything that does not involve identity, on cyoda-go mock IAM with memory storage. **Tier 2 (1b):** everything that relies on authentication, on cyoda-go JWT with postgres, plus Zitadel. Use in-memory storage wherever it makes sense. |
| Test style | Replace Cucumber with plain JUnit 5 integration tests. |
| Contract artifacts | Vendor cyoda-go's files verbatim with a sync script. Build-time transforms produce what the Java generators need. Patches for open cyoda-go defects fail the build once the defect is fixed (§3.3). |
| Credential model | See the three rules below this table. |
| IdP | An external OIDC IdP is presumed. Zitadel is the reference for the target operating model. |
| `authclaims` | cyoda-go's comma-separated form only. Anything else yields no roles. |
| Tier-2 persona tokens (1b) | Zitadel machine-user personas using client credentials, as in ctcc, plus a spike on whether they can carry roles (§10). |

**Credential model:**
- **Compute → Cyoda is always M2M.** The originating user reaches compute as data
  (`authtype` / `authid` / `authclaims`).
- **Compute's callbacks join the originating transaction** using the tx-token, so cyoda
  attributes them to the originating user.
- **BFF → Cyoda forwards the user's own IdP token**, through Cyoda's federated OIDC
  provider. M2M is used only for work with no acting user.
- OBO / trusted-key subject-token minting is removed.

**Why this credential model.** It comes from the ctcc-management review
(`docs/architecture.md` §2.2 and §4; `docs/reference/cyoda-callout-security-model.md`):
- the IdP stays the only authority for identity and roles;
- no component holds a credential that can mint a token for arbitrary users;
- audits are correct on every path:
  - **direct calls** → the forwarded user;
  - **joined cascades** → the transaction's origin (cyoda-go
    `docs/cloud-parity/authcontext-attribution.md`);
  - **scheduled fires** → the arming principal.

## 2. Roadmap

| # | Sub-project | Contents | Depends on |
|---|---|---|---|
| **1a** | **Contract, runtime alignment, tier-1 harness** (this spec) | §3 to §8 | — |
| 1b | BFF identity and tier 2 | Inbound resource-server security, Zitadel assembly, tier-2 suite and CI. It starts with its own brainstorm; known inputs are in §10. | 1a |
| 2 | New v0.9.0 capabilities | Entity merge-PATCH; function criteria and scheduled-transition functions; unique keys (blocked on cyoda-go #626); grouped stats. Each with tests. | 1a (and 1b for identity-dependent tests) |
| 3 | Documentation overhaul | README, `llms.txt`, `llms-full.txt`, `usage-rules.md`, `AI_TESTING_GUIDE.md`, `.augment/rules/*` and `CONTRIBUTING.md` point at `cyoda help` / docs.cyoda.net. Local-dev and Zitadel guides. | 1a, 1b, 2 |

### 2.1 Delivery of 1a: two PRs, one plan

**PR 1: contract, configuration, OBO removal, harness.** It covers:
- §3 (all of it);
- §4.1;
- §4.6 (OBO removal, including the event-user resolver);
- §4.7;
- the protocol fixes in §4.4 that need no callout scope (responses, criteria `matches`,
  `authtype` values, keep-alive);
- §6;
- §7;
- the tier-1 tests `ComputeMemberJoinIT`, `ModelAndWorkflowSetupIT`, `EntityCrudIT`,
  `SearchIT`, `ProcessorIT` and `CriterionIT`;
- Cucumber removal and CI.

After PR 1, every Cyoda call goes out as M2M, or with no header under `auth-mode=none`. The
one exception fails closed: while the calling thread's `SecurityContext` holds an
authenticated, non-anonymous user, the M2M token is refused (`CyodaCredentialException`;
on gRPC the call fails with `UNAUTHENTICATED`), so a user's request never runs with the
service account's rights. `CyodaRepository` runs each gRPC call with the caller's
`SecurityContext`, so the interceptor sees it on the pool thread.

**PR 2: credentials, callout scope, threading.** It covers:
- §4.2, the decision point;
- §4.4, `CalloutScope`, the tx-token and refusal mapping, and the auth context as data;
- §4.5, threading;
- the tier-1 tests `CascadeAtomicityIT`, `CascadeConcurrencyIT` and
  `AuthContextPlumbingIT`;
- their unit tests.

PR 2 builds on PR 1's harness. PR 1 does not depend on PR 2.

**What changes for BFF calls.** In the template's default configuration (permit-all
inbound), BFF calls go out as M2M, just as today, because OBO was off by default. An app
that installed its own JWT `SecurityFilterChain` behaves differently after PR 2: its user's
raw IdP token is forwarded to Cyoda (§4.2 rule 3); after PR 1 alone, that call is refused.
Before, the template attempted OBO and,
with OBO unconfigured, threw `OboTokenException`. This is listed as a breaking change (§4.8).

## 3. Contract layer

### 3.1 Layout

cyoda-go's files are vendored **unmodified** under one root:

```
src/main/resources/cyoda/
  CYODA_VERSION
  CYODA_SHA256SUMS          # released pins only: the release archives' SHA-256 (§3.2, §7.4)
  proto/cyoda/cyoda-cloud-api.proto
  proto/cloudevents/cloudevents.proto
  schema/**/*.json          # the whole event-schema tree, incl. common/statemachine/
  openapi/openapi.yaml      # cyoda-go api/openapi.yaml (OpenAPI 3.1)
```

The template's current `proto/`, `schema/` and `api/` resource directories are deleted.

`CYODA_VERSION` holds two lines:
1. the version, e.g. `0.9.0`, or `0.9.0-dev` before the tag;
2. `commit=<full sha>`.

### 3.2 Sync

The command is
`scripts/sync-cyoda-contract.sh --from-src <cyoda-go checkout> --version <x.y.z[-dev]> [--sha256sums <file>]`.

- It copies the files listed in §3.1 from `proto/`, `docs/cyoda/schema/` (JSON files only;
  the tree's `.go` files are excluded) and `api/openapi.yaml`.
- It writes `CYODA_VERSION`: the version comes from `--version`, and the commit from
  `git rev-parse HEAD` of the checkout. (`git describe` on the release branch yields
  `cyoda-0.8.4-…`, so the version cannot be derived.)
- For a released version it writes `CYODA_SHA256SUMS`: the `cyoda_<version>_<os>_<arch>.tar.gz`
  lines of the release's `SHA256SUMS`, downloaded from the GitHub release or read from
  `--sha256sums <file>`. It fails, before changing anything, if there are none. For a `-dev`
  version it removes the file.
- It is idempotent. The resulting `git diff` is the contract change.

There is no binary mode. The binary re-serialises the files, so switching modes would show
spurious diffs.

### 3.3 Code generation

Sources are generated from **build-dir copies** of the vendored files. Each transform is a
small Gradle task that parses and edits its input structurally, never with text
substitution.

Every transform **asserts the input shape it expects** and fails the build naming the file
otherwise, so contract drift cannot pass silently.

**Patches for open cyoda-go defects** follow the "fail once fixed" rule:
- each patch first asserts that the defect is still present;
- when it is not, the build fails with
  `cyoda-go #<n> is fixed upstream: remove patch <name>`.

**3.3.1 Protobuf** (task `prepareCyodaProto`)

- **Option injection.** After each `package` line, the copy gets:
  - `cyoda/cyoda-cloud-api.proto`: `option java_multiple_files = true;`
  - `cloudevents/cloudevents.proto`: `option java_multiple_files = true;`,
    `option java_package = "io.cloudevents.v1.proto";` and
    `option java_outer_classname = "Spec";`

  These match the `cloudevents-protobuf` library, whose `CloudEvent` comes from its
  `spec.proto`. The build fails if either file already declares a conflicting Java option.
  A trial build proved this compiles, and the runtime descriptor resolves all six RPCs to
  the library's `CloudEvent`.
- **Import-only CloudEvents.** Only `cyoda/` is a proto source directory. The copy's
  `cloudevents/` directory is added with `compileProtoPath files(<copy dir>)`, so it is on
  the import path only. `io.cloudevents.v1.proto.CloudEvent` comes from the
  `cloudevents-protobuf` library. Today the template generates its own duplicate class, and
  classpath order decides which one loads.
- **Versions.** `protoc` 4.31.x aligns with `protobuf-java` 4.31.1 (today the build pairs it
  with protoc 3.25.1), and `protoc-gen-grpc-java` aligns with `grpc` 1.73.0.
- **Classes that disappear:** the generated `CloudEventBatch` and `Cloudevents` (§4.8).
- **Output package:** `org.cyoda.cloud.api.grpc`, as today.

**3.3.2 Event schemas** (task `prepareEventSchemas`; JSON transform)

- **Inheritance.** `"allOf": [ { "$ref": "<p>/BaseEvent.json" } ]` becomes
  `"extends": { "$ref": "<p>/BaseEvent.json" }`, so jsonschema2pojo keeps the Java
  inheritance the framework relies on.
  - All 47 `allOf`s have exactly this form.
  - Any other `allOf` fails the build.
- **Typeless objects.** A property of `"type": "object"` that has no `properties` and no
  `existingJavaType` gets `existingJavaType: com.fasterxml.jackson.databind.JsonNode`.
  - Otherwise jsonschema2pojo generates an empty class, and with
    `includeAdditionalProperties=false` that silently drops content.
  - Example: `EntityFunctionCalculationResponse.result`.
- **Inline `orderBy`.** Its object definitions get one shared `javaType`
  (`org.cyoda.cloud.api.event.search.OrderBy`). Without it, class names (`OrderBy`,
  `OrderBy__1`) depend on processing order.
- **Untouched:** `existingJavaType` already present on the ten "any value" properties.
- **Output package:** jsonschema2pojo generates into `org.cyoda.cloud.api.event.*`, as
  today.

**3.3.3 OpenAPI** (task `prepareOpenApi`, then one generator task)

- **Package.** All DTOs are generated into one package, `org.cyoda.cloud.api.common.model`.
  cyoda-go's own event schemas hard-code
  `existingJavaType: org.cyoda.cloud.api.common.model.GroupConditionDto`
  (`docs/cyoda/schema/search/EntitySearchRequest.json:18` and
  `EntitySnapshotSearchRequest.json:18`).
- **Names.** No `modelNameSuffix`. 87 of the 129 schemas already end in `Dto`; the other 42
  keep their spec names.
- **Conditions are the generated interfaces.** Simple, group, lifecycle and array
  conditions extend `AbstractConditionDto`. Only function conditions extend
  `QueryConditionDto`. The criterion and condition fields are inline `oneOf`s, which
  openapi-generator turns into interfaces, and they are kept as such:
  `TransitionDefinitionDto.criterion`, `WorkflowConfigurationDto.criterion`,
  `GroupConditionDto.conditions.items`, `GroupedStatsRequest.condition`. Framework code
  that holds a mixed list of conditions uses those interfaces, not `QueryConditionDto`.
- **Patch: discriminator mappings** (cyoda-go #625).
  - A `mapping` is added to `QueryConditionDto`, `AbstractConditionDto` and `AuditEventDto`.
  - A mapping is also added to each of the four inline condition unions:
    - conditions: `simple`, `group`, `lifecycle`, `array`, `function`;
    - audit: `EntityChange`, `StateMachine`, `System`.
  - `StateMachineEventDto` gets no mapping: its subtypes declare no wire values, and no
    response references it.
- **Patch: array condition** (cyoda-go #627). `ArrayConditionDto` is rewritten to
  `{type, jsonPath, values: [any]}` with no `operatorType` and no `value`, which is what the
  server parses.
- **Processor `type`.** A processor without `type`, or with `"type": ""`, must deserialise
  as externalized. cyoda-go defaults it, and the template's workflows omit it. The
  generated `ProcessorDefinitionDto` hierarchy gets `defaultImpl =
  ExternalizedProcessorDefinitionDto` through a generator template override, not a
  spec patch.
- **Schema mapping.** One `schemaMappings` entry,
  `TransitionDefinitionDto_processors_inner=ExternalizedProcessorDefinitionDto`, removes the
  only wrapper type that does not compile.
- **Other types.** `object` / `any` map to `JsonNode`.
- **Removed:** the five split `openapi-*.yml` files and the `@JsonAlias` post-processing.
- **Framework code adapted to the new names:**
  - `EntityService`, `EntityServiceImpl`;
  - `CrudRepository`, `CyodaRepository`;
  - `EntityCrudOperations` (including `FieldFilter`);
  - `CyodaInit`, `WorkflowServiceImpl`;
  - the validators.

  `GroupOperatorDto` and `OperatorTypeDto` are no longer schemas: their values are inline
  enums on each condition DTO, and `.operation()` becomes `.operatorType()`.
- **Round-trip proof** (unit test):
  - Input: cyoda-go's exported workflow JSON, with schema defaults present, plus one
    condition of each type.
  - Each is deserialised into the generated DTOs and re-serialised with a `NON_NULL`
    mapper, generated with `containerDefaultToNull=true`.
  - The re-parsed JSON trees must be equal, with numbers compared by value.

**New events after the sync** (used by sub-project 2): `EntityPatchRequest`,
`EntityPatchPayload`, `PatchFormat`, `EntityFunctionCalculationRequest/Response`.
`EntityModelSetUniqueKeys*` has no schema upstream (cyoda-go #626), so nothing is generated
for it.

### 3.4 Version pin

- The build reads `CYODA_VERSION` into a `cyodaVersion` property and passes it to the tests.
- The harness (§6.1) runs `--version` on the binary and compares:
  - for a released version, `major.minor.patch` must be equal;
  - for a `-dev` pin, the binary's `commit` must equal the pinned commit.
- On a mismatch it fails and shows both values. `-Dcyoda.allowVersionMismatch=true` turns
  the failure into a warning.
- A binary that reports `dev (commit unknown)` fails, with a hint to use
  `scripts/install-cyoda.sh` (§7.4).

## 4. Runtime alignment

### 4.1 Configuration

`Config.CyodaLight` and `CyodaLightConfigCustomizer` (and its `spring.factories` entry) are
removed. "cyoda-light" was cyoda-go's earlier name.

**`app.config` after 1a:**

| Property | Meaning | Default (`application.yml`) |
|---|---|---|
| `cyoda-host` | Host from which the next two properties are derived | — |
| `cyoda-api-url` | REST base URL, including the context path | `https://${cyoda-host}/api` |
| `grpc-address` / `grpc-server-port` | gRPC endpoint | `grpc-${cyoda-host}` / `443` |
| `grpc-tls` | TLS on the gRPC channel (replaces `skip-ssl`) | `true` |
| `grpc-call-deadline-ms` | Deadline on every unary Cyoda call (previously none; `withWaitForReady()` could wait forever). A value ≤ 0 fails startup. | `120000` |
| `ssl-trust-all` / `ssl-trusted-hosts` | Trust every certificate / skip certificate checks for the listed hosts only (REST and the token request validate every other host normally) | `false` / empty |
| `auth-mode` | `client-credentials` or `none` | `client-credentials` |
| `cyoda-client-id` / `cyoda-client-secret` | M2M credentials, required when `auth-mode=client-credentials` | — |
| `allow-insecure-transport` | Allows plaintext to Cyoda with `auth-mode=client-credentials` (below) | `false` |
| `execution-mode` | `virtual` or `platform` threads for processor and criteria work (§4.5) | `virtual` (was `platform`) |

**`auth-mode=none`:**
- No OAuth2 `ClientRegistration` and no M2M token source is created. Today the
  `Authentication` constructor always builds one, and Spring rejects an empty client id at
  startup.
- `auth-mode=client-credentials` with a missing client id or secret fails startup with a
  message naming both properties.
- `auth-mode=none` logs one warning at startup: calls carry no credentials.

**Transport check (`auth-mode=client-credentials`).** The client secret goes to
`{cyoda-api-url}/oauth/token` and the M2M token rides every call, so startup fails, naming
the setting, when the token URI is not `https` or `grpc-tls=false`. A loopback host
(`localhost`, `127.0.0.0/8`, `::1`, by its literal form) or
`app.config.allow-insecure-transport=true` lifts the check.

**New `application-cyoda-local.yml` (profile `cyoda-local`):**
- `server.port: 8081`, because cyoda-go's HTTP default is 8080;
- `server.address: 127.0.0.1`: with mock IAM the app runs without credentials, so it is not
  reachable from other hosts;
- `cyoda-api-url: http://localhost:8080/api`;
- `grpc-address: localhost`, `grpc-server-port: 9090`, `grpc-tls: false`;
- `auth-mode: none`.

**Migration.** These rows are recorded in `SYNCING_WITH_JAVA_TEMPLATE.md` (§4.8):

| Old key | New |
|---|---|
| `app.config.cyoda-light.*` | Removed. Set `cyoda-api-url` and `grpc-*`, or use the `cyoda-local` profile. |
| `app.config.skip-ssl` | `app.config.grpc-tls` (inverted) |
| `execution.mode` (read by `@ConditionalOnProperty`) and `app.config.execution-mode` | One key, `app.config.execution-mode`, whose default changes to `virtual` |
| top-level `cyoda.api.url` | Removed (unused) |
| `app.obo.*`, `app.event.auth-context.*` | Removed with OBO (§4.6) |

### 4.2 Outbound credentials: one decision point (PR 2)

**`CyodaCallContext`** is an immutable value that states the credential and the transaction
for one Cyoda call.

| Part | Values |
|---|---|
| Credential | `NONE` (no `Authorization` header); `M2M` (the service-account token); `FORWARD(token)` (a user token, sent unchanged) |
| Transaction | an optional tx-token |

**`CyodaCallContexts`** is the only class that builds contexts. It has two methods.

**`current()`** is called once on entry to each public method of `EntityService`,
`WorkflowService`, `EdgeMessageService` and `CyodaInit`. It applies these rules in order:

1. `auth-mode=none` → `NONE`. If a callout scope is active, its tx-token is still set.
2. A callout scope is active (§4.4) → `M2M` plus the scope's tx-token, whatever the
   `SecurityContext` holds.
3. The `SecurityContext` holds an authenticated `JwtAuthenticationToken`:
   - with a non-blank token → `FORWARD(token)`;
   - with a blank token → `CyodaCredentialException`.
4. Any other authenticated, non-anonymous principal → `CyodaCredentialException`. The
   framework never silently downgrades to M2M.
5. Otherwise → `M2M`.

**`forMemberStream()`** returns `NONE` under `auth-mode=none` and `M2M` otherwise. It never
carries a tx-token, and it is used only to open `startStreaming`.

**Passing the context along:**
- The context is passed **explicitly** through every stage of an operation, including
  multi-step ones such as snapshot search (create, poll status, fetch pages). It is never
  re-derived from thread-locals.
- The snapshot-search cache (Caffeine, `CyodaRepository`) loses its loader. Its key gains a
  **context fingerprint**: the credential kind, plus a SHA-256 of a forwarded token. No
  caching takes place inside a callout scope.

**gRPC:**
- The stub interceptor reads the context from `CallOptions`.
- It sets `authorization` and, where allowed (§4.4), `tx-token`.
- A call without a context is cancelled with `FAILED_PRECONDITION`. The stubs remain beans,
  but they are documented as framework-internal.
- A failure to obtain a credential cancels the call with `UNAUTHENTICATED`. Today the
  interceptor proceeds without a header.

**REST:** `HttpUtils` takes a `CyodaCallContext` instead of a caller-supplied token and
resolves the header itself.

**The M2M token:**
- It comes from client credentials against `{cyoda-api-url}/oauth/token` with HTTP Basic.
  No `scope` is sent.
- It is cached until 60 s before it expires.
- The fetch runs outside any `ConcurrentHashMap.compute` bin lock (a `ReentrantLock` guards
  it), so it cannot pin a virtual thread (§4.5).

**Rejected-token retry (M2M only).** A `FORWARD` token is never retried: the failure
surfaces to the caller.
- **gRPC:** a rejected bearer token arrives as gRPC status `UNAUTHENTICATED`. A rejected
  transaction pass arrives as a `success:false` envelope instead (§4.4), so the two are
  distinct. On `UNAUTHENTICATED` the token is invalidated, fetched again, and the call
  retried once, inside a callout scope too.
- **REST:** both arrive as `401 UNAUTHORIZED`.
  - Outside a scope: invalidate, fetch again, retry once.
  - Inside a scope: the token is invalidated, the call is not retried, and it fails with
    `CyodaCalloutEndedException`.

### 4.3 Inbound (BFF) security

Unchanged in 1a. `SecurityConfig` stays the permit-all default auto-configuration. Inbound
resource-server configuration is 1b's (§10).

### 4.4 Compute node

**Credential (PR 2).** Compute always uses M2M (§4.2, rule 2). Opening a callout scope
clears the thread's `SecurityContext` and asserts that it is empty.

**Auth context as data (PR 2).**
- `CyodaEventContext` gains a default method `authContext()` that returns the reshaped
  `CloudEventAuthContext(type, id, roles)`. It was
  `(authType, authId, authClaimsJson)`.
- `CloudEventAuthContextExtractor` stays and produces the new shape.
- **`type`** is one of `USER`, `SERVICE`, `SYSTEM`. An absent, unknown or retired value
  (e.g. `service_account`) yields an **empty** context.
- **`roles`** come from `authclaims`, which is comma-separated
  (`internal/grpc/cloudevent.go:84-88`). The value is split on `,`, each part trimmed, and
  blanks dropped. Nothing else is parsed: a JSON object is not roles, and it yields none.
- **`requireRole(String role)`** mirrors cyoda-go's `authctx.Require`. It returns `true`
  only when `type` is `USER` or `SERVICE` and `role` appears exactly among the roles.
- **Trust.** The Javadoc states that `authclaims` can be trusted only over a
  server-verified TLS channel.
- **Unit tests** use the literal wire strings `"ROLE_ADMIN,ROLE_M2M"`, `""`, a JSON object
  and garbage.

**Transaction token (PR 2).** `CyodaEventContext` gains a default method `txToken()` that
returns the `cyodatxtoken` attribute. A **`CalloutScope`**, holding the tx-token and an
**open** flag, is opened around each processor or criterion invocation. The flag is
cleared when the answer is sent.

- **Attached** (while the scope is open, to calls that carry a context from `current()`):
  - gRPC `entityManage`, `entityManageCollection`, `entitySearch` and
    `entitySearchCollection`, as metadata `tx-token`;
  - REST entity, search and edge-message endpoints, as `X-Tx-Token`.
- **Never attached** to model or workflow administration (`CyodaInit`, `WorkflowService`,
  `entityModelManage`). cyoda refuses those with `MODEL_ADMIN_IN_JOINED_TRANSACTION`.
- **Parameters refused on joined requests.** Inside a scope, the framework rejects a
  non-null `transactionTimeoutMs`, `timeoutMillis`, `transactionWindow` or
  `transactionSize` before sending, with an `IllegalArgumentException` that names the
  parameter (`docs/cloud-parity/transaction-control-params.md`).
- **Search inside a scope uses direct search.** Async snapshot search runs detached from
  the transaction (`internal/domain/search/service.go:1051-1053`; `tx-aware-search.md`), so
  it would not see the cascade's own uncommitted writes. Direct search has a limit of
  10,000; a paged read beyond that inside a scope throws `IllegalStateException`.
- **After the scope closes,** a call throws `CyodaCalloutEndedException` locally, before
  any network call.
- **App-spawned threads** lose the scope. `CalloutScope.wrap(Runnable/Callable/Supplier)`
  carries it over. The Javadoc states that, without it, calls go out unjoined as M2M.
- **`CalloutScope.unjoined(Supplier)`** runs a block as M2M with no tx-token. It is cyoda's
  documented remedy for `COMMIT_IN_JOINED_TRANSACTION`.
- **Token lifetime.** A tx-token lives for the try's answer limit plus
  `CYODA_CALLOUT_PASS_ALLOWANCE` (default 30 s).

**Refusal mapping (PR 2).**
- **Over gRPC,** a failure is a `success:false` envelope. Its `code` is coarse
  (`CLIENT_ERROR` or `SERVER_ERROR`). The cyoda error code is the prefix of `message` up
  to the first colon, and `retryable` is present only when `true`
  (`internal/grpc/errors.go:42-48`; `cyoda help errors`).
- **Over REST,** the error code is the problem detail's `properties.errorCode`. Today
  `HttpUtils` keeps only the message, and `CyodaRepository.validateResponse` passes
  `CLIENT_ERROR` on as the code. Both must preserve the real code.
- The mapping keys on the **code**. The HTTP status alone cannot distinguish, for example,
  `410 CALLOUT_SUPERSEDED` from `410 TRANSACTION_EXPIRED`.

| cyoda code (status) | Exception | Retry |
|---|---|---|
| `CALLOUT_SUPERSEDED` (410), `TRANSACTION_EXPIRED` (410), `TRANSACTION_NOT_FOUND` (404), `UNAUTHORIZED` on a joined request (see §4.2) | `CyodaCalloutEndedException`: stop working on this request | no |
| `TOO_MANY_JOINED_REQUESTS` (503) | `CyodaRetryableException` | the framework retries up to 3 times with 50, 100 and 200 ms backoff, then throws |
| `TRANSACTION_NODE_UNAVAILABLE` (503, cluster) | `CyodaRetryableException` | as above |
| `CONFLICT` (409, retryable) | `CyodaRetryableException` | not retried locally; the processor fails and cyoda's callout retry policy applies |
| `JOINED_RESPONSE_TOO_LARGE` (413) | `CyodaJoinedResponseTooLargeException`: page the read | no |
| `COMMIT_IN_JOINED_TRANSACTION` (409) | `CyodaCommitInJoinedTransactionException`: use `CalloutScope.unjoined` | no |
| `FORBIDDEN` (403): tenant or role | `CyodaAccessDeniedException` | no |
| `MODEL_ADMIN_IN_JOINED_TRANSACTION` (400) | cannot occur, because the token is never attached; if it does, `IllegalStateException` | no |

**Attribution (the Javadoc on `CalloutScope`):**
- Joined writes are attributed to the transaction's origin.
- Under `COMMIT_BEFORE_DISPATCH` with `startNewTxOnDispatch=false`, the callout carries no
  tx-token, so callbacks are ordinary M2M requests attributed to the M2M account
  (`cyoda help workflows`, "Attribution handover").

**Modifying the entity under processing.** The rule "a processor must not modify the entity
under processing via `EntityService`" stays. cyoda keeps a joined write to that entity only
when the processor answers with no payload, and the framework always answers with a payload.

**Protocol fixes (PR 1):**

| Item | Change |
|---|---|
| Join | No `joinedLegalEntityId` is sent. cyoda uses the token's tenant when it is absent (`internal/grpc/streaming.go:56-66`). Sending it only adds a `PermissionDenied` risk. |
| `authtype` values | `user`, `service` and `system` are recognised; `service_account` is not. Until PR 2 replaces the event-auth handling, the values are only logged. |
| Responses | Every processor or criterion response carries a fresh `id`, the `requestId` and the `entityId`. A failure sends `success: false` explicitly. `error` is omitted rather than serialised as `null`; a serialisation unit test covers this. |
| Criteria | `matches` is always set. |
| Keep-alive | cyoda evicts a member after 30 s without inbound activity from the member, or after a stalled write (`internal/grpc/streaming.go:191-198`). The member already answers every server keep-alive (`KeepAliveEventHandlingStrategy`). `keep-alive-warning-threshold` (ms) changes from 60000 to 20000, so a silent **server** is reported sooner; this is a diagnostic and has no bearing on eviction. |
| Stream writes | Already single-writer (`ConnectionManager.sendEvent` is `synchronized`). |

### 4.5 Threading (PR 2)

Today `CyodaRepository` runs blocking gRPC calls on the common ForkJoinPool (`supplyAsync`),
and `EntityServiceImpl` blocks on `.join()`. The processor and criteria executors are fixed
pools of 20 in both modes. The `virtual` mode is
`newFixedThreadPool(20, virtualFactory)`.

This causes two problems:
- **Deadlock.** Each nested or concurrent cascade holds one pool thread while it waits for
  a callback that needs another thread. Past the pool size, the cascades hang.
- **Lost context.** Context is lost across async stages (§4.2).

1a changes the threading as follows:
- Blocking stub calls, including poll and delay stages, run on a dedicated
  `Executors.newVirtualThreadPerTaskExecutor()`, and the context travels as an argument.
- In `virtual` mode, the processor and criteria executors are
  `newVirtualThreadPerTaskExecutor()`. `processor-thread-pool` and `criteria-thread-pool`
  are ignored in that mode (documented).
- `platform` mode keeps the fixed pools and a documented requirement: at least one free
  processor thread per cascade level.
- **Pinning audit.** On JDK 21, a `synchronized` block that holds a lock across a blocking
  call pins the virtual thread. The only known case, the token fetch inside
  `ConcurrentHashMap.compute`, is fixed in §4.2. The rest of the framework's call paths are
  audited (§8).

### 4.6 Removal of OBO and the event-user resolver (PR 1)

Deleted:
- `OboAwareAuthentication`, `OboTokenService`, `OboKeyRegistrationService`,
  `SubjectTokenSigner`, `AesGcmEncryption`, `OboSigningKey`, `OboProperties`,
  `OboTokenException`;
- `EventAuthContextHandler`, `DefaultEventUserResolver`, `EventUserResolver`,
  `EventUserIdentity`, `EventUserResolutionException`;
- `AuthContextMode`, `EventAuthContextProperties`, `EventAuthContextScope`,
  `EventAuthContextMissingException`;
- `AuthClaimsParser`;
- the OBO bootstrap resources and all their tests.

`Authentication` becomes the M2M token source. In PR 1, every call uses it, or sends no
header under `auth-mode=none`. The `CloudEventAuthContext` reshape and the extractor
changes land in PR 2.

### 4.7 Workflows and REST paths (PR 1)

- **Example and test workflows:**
  - `"version": "1.5"`;
  - no fields that cyoda-go does not recognise;
  - `retryPolicy` is `NONE`, `FIXED` or absent;
  - `responseTimeoutMs` ≤ 60000;
  - non-empty `calculationNodesTags`.
- **Condition `jsonPath`s** carry the required `$.` prefix, in examples and in framework
  code that builds conditions.
- **Snippets.** `criterion_examples.json` and `processor_examples.json` are snippets, not
  workflows. They move to `src/test/resources/example/config/snippets/`, where each
  fragment is validated by deserialising it into its DTO.
- **REST paths.** Every REST call in `common/` was checked against `openapi.yaml`, and all
  of them exist.

### 4.8 Downstream impact

`SYNCING_WITH_JAVA_TEMPLATE.md` gains a "breaking changes in the v0.9.0 alignment" table
covering:

- **Contract files:** the new `src/main/resources/cyoda/` layout.
- **Configuration:** every key in §4.1's migration table, including the `execution-mode`
  default change to `virtual`.
- **Generated OpenAPI DTOs:**
  - one package, `org.cyoda.cloud.api.common.model`; `workflow.model` and `search.model`
    are gone;
  - operator enums are per-class, and `.operation()` becomes `.operatorType()`;
  - condition collections use the generated interfaces;
  - `EntityCrudOperations.FieldFilter` changes;
  - simple-name clashes: `EntityChangeMeta`, `EntityMetadata` and
    `EntityTransactionResponse` exist in both `org.cyoda.cloud.api.common.model` and
    `org.cyoda.cloud.api.event.*`, so two wildcard imports no longer compile.
- **Generated proto:** `CloudEventBatch` and `Cloudevents` are no longer generated.
- **`deleteAll`:** `EntityDeleteAllRequest` lost `transactionSize` (default 1000) and
  `pageSize`, so `deleteAll` is one transaction (`internal/grpc/entity.go:471-499`).
- **Signatures:** `CrudRepository` and `HttpUtils` signatures change (§4.2).
- **Compute:**
  - `CyodaEventContext.authContext()` / `txToken()`;
  - `CloudEventAuthContext` changes shape;
  - `authtype` values change.
- **New types:** `CalloutScope`, `CyodaCallContext` and the new exceptions.
- **OBO and the event-user resolver are removed.** Apps with their own JWT
  `SecurityFilterChain` now forward the user's IdP token (§2.1).
- **Workflows:** version 1.5.
- **Build:** `check` now runs the integration suite and needs a cyoda binary (§7).

## 5. cyoda-go facts relied on (verified)

These were checked against cyoda-go `release/v0.9.0`, `cyoda help` and docs.cyoda.net
during design and two reviews.

**Transaction tokens**
- Names: `cyodatxtoken`, `tx-token`, `X-Tx-Token`.
- Tokens are issued under mock IAM as well (`app/app.go:153-171`).
- A token's lifetime is the answer limit plus `CYODA_CALLOUT_PASS_ALLOWANCE`.
- Joined callbacks are served one at a time per transaction. cyoda releases its gate across
  dispatch.
- Refusals: codes, statuses and order as in the CHANGELOG. They arrive as envelopes on
  gRPC and as problem details on REST.
- `MODEL_ADMIN_IN_JOINED_TRANSACTION` applies on both doors, to mutating calls only;
  exports pass.
- Async search is detached from the transaction.

**Errors**
- gRPC envelopes carry a coarse `code`, the real code as the message prefix, and
  `retryable`.
- REST problem details carry `properties.errorCode`.
- A rejected bearer token is gRPC status `UNAUTHENTICATED`.

**Authentication**
- `/oauth/token` is JWT-mode only, uses HTTP Basic and ignores `scope`.
- `joinedLegalEntityId` is optional.
- `authtype` ∈ {`user`, `service`, `system`}. `authclaims` is comma-separated and absent
  when the principal has no roles.
- The mock principal: kind from `CYODA_IAM_MOCK_KIND`, roles from `CYODA_IAM_MOCK_ROLES`
  (default `ROLE_ADMIN,ROLE_M2M`), tenant `mock-tenant`, user `mock-user-001`.

**Workflows**
- Schema versions 1.1 to 1.5 are accepted, and unknown fields are rejected.
- `$.` is required on JSON paths.
- An empty `calculationNodesTags` matches every member of the tenant.
- A manual transition refused by its criterion answers `400 WORKFLOW_FAILED` with detail
  `transition "<name>" criterion not matched: <reason>`. Over gRPC the message carries the
  `WORKFLOW_FAILED:` prefix.

**Server**
- Keep-alive defaults 10 s / 30 s; env var names and defaults; `/api/health`.
- Env files are read in this order: `/etc/cyoda/cyoda.env`, then
  `$XDG_CONFIG_HOME` or `~/.config/cyoda/cyoda.env`, then `./.env`, then profiles.
- Version ldflags: `main.version`, `main.commit`, `main.buildDate`. Go 1.26.7.

## 6. Tier-1 test harness (PR 1)

### 6.1 Components

The components live in `src/testFixtures/java/com/java_template/testing/cyoda/`. They are
framework-owned, listed in the sync guide, and usable by `test`, `integrationTest`, 1b's
tier 2 and downstream apps.

**`CyodaBinary`**
- Finds the binary from `-Dcyoda.bin`, then `$CYODA_BIN`, then `<project>/.cyoda/bin/cyoda`
  (the default install location, §7.4), then `cyoda` on `PATH`. The project directory is
  `-Dcyoda.projectDir`, else `user.dir`.
- Enforces the pin (§3.4), with `CyodaVersion` parsing the pin file and the `--version`
  output.
- Only ever runs `--version`: an unrecognised argument would start a server
  (cyoda-go #623).
- If no binary is found, it fails with the `install-cyoda.sh` command to run.

**`CyodaServer`** runs one cyoda-go subprocess, started with no arguments.

**The environment is built from scratch.** Only `PATH` is inherited. The rest is set
explicitly:
- **Isolation:** `HOME` and `XDG_CONFIG_HOME` point at a fresh temp directory, which is
  also the working directory. `CYODA_PROFILES` is unset.
- **Ports:** `CYODA_HTTP_PORT`, `CYODA_GRPC_PORT` and `CYODA_ADMIN_PORT` are three free
  ports. `CYODA_CONTEXT_PATH=/api`.
- **Server:** `CYODA_STORAGE_BACKEND=memory`, `CYODA_IAM_MODE=mock`,
  `CYODA_IAM_MOCK_KIND=user`, `CYODA_IAM_MOCK_ROLES=ROLE_ADMIN,ROLE_M2M`.
- **Timing:** `CYODA_KEEPALIVE_INTERVAL=10`, `CYODA_KEEPALIVE_TIMEOUT=30`,
  `CYODA_DISPATCH_WAIT_TIMEOUT=5s` (explicitly set to cyoda's defaults, so a
  `/etc/cyoda/cyoda.env` cannot change them), `CYODA_SCHEDULER_SCAN_INTERVAL=50ms`.
- **Output:** `CYODA_SUPPRESS_BANNER=true`, `CYODA_ERROR_RESPONSE_MODE=verbose`,
  `CYODA_LOG_LEVEL=info`.

**Profiles.** A `Profile` value object carries overrides, and servers are cached per
distinct profile.

**Readiness.** The harness polls `GET /api/health` until it answers `200`, and fails fast
if the process exits first. The limit is 30 s.

**Logs.** Output goes to `build/cyoda-logs/<profile>-<timestamp>.log`. The last 50 lines are
attached to any startup or test failure: a startup failure's message includes them, and
`CyodaServerExtension` adds them to a failing test or lifecycle method as a suppressed
exception.

**Shutdown.** A JUnit root-store `CloseableResource` stops the server at the end of the test
engine run: SIGTERM, 5 s grace, then kill. This happens **before** Spring's cached test
contexts close in their JVM shutdown hook. Those contexts' member streams then see the
server go away. That is accepted, and a connection loss during JVM shutdown is logged at
`DEBUG`, not `ERROR`.

**`@CyodaIntegrationTest`** is a meta-annotation that combines `@SpringBootTest`, the JUnit
extension and an `ApplicationContextInitializer`. The initializer injects:
- `app.config.cyoda-api-url`, `grpc-address`, `grpc-server-port`;
- `grpc-tls=false`, `auth-mode=none`;
- `server.port=0`;
- a **context-unique** `grpc-processor-tag`.

**Tag isolation**
- Test workflows reference `${tag}` in `calculationNodesTags`. The harness substitutes the
  context's tag when importing, and **refuses** a workflow whose tags are missing or empty.
  An empty list would match every member of `mock-tenant`.
- A second cached Spring context therefore never receives another context's callouts.
- A build-time check (§7.1) rejects `@MockitoBean` / `@MockBean` in integration tests.

**Helpers**
- `importWorkflow(model, resource)`: tag templating plus import.
- `awaitComputeMemberJoined()`.
- `rest()`: a raw REST client.
- Unique model names per test class.
- `CyodaInit` isolation. `ModelAndWorkflowSetupIT` runs `CyodaInit` against a resource set
  generated per test: unique model name, templated tags. No two tests share a model.

**The example application.** It lives in `src/test/java/com/example`. The `integrationTest`
source set has `sourceSets.test.output` on its classpath. `@CyodaIntegrationTest` boots
`Application` plus a test configuration that scans `com.example.application`.

### 6.2 Tier-1 suite: `integrationTest` (mock IAM, memory storage; part of `check`)

| Class | PR | Proves |
|---|---|---|
| `ComputeMemberJoinIT` | 1 | On a dedicated profile with keep-alive 1 s / 3 s, the app joins and receives the greet. A processor transition started after 10 s of idleness is still served. |
| `ModelAndWorkflowSetupIT` | 1 | `CyodaInit` imports sample data, locks the model and imports the v1.5 workflow. The export round-trips through the generated DTOs, equal to what was imported. Every workflow under `example/config/workflow/` imports. |
| `EntityCrudIT` | 1 | Via `EntityService` (gRPC): create, bulk create, get, get-all, update (loopback), update with transition, delete, delete-all. |
| `SearchIT` | 1 | Direct search with `$.` conditions of every type: simple, group, lifecycle, array (`values` form), function. Paged async snapshot search across at least 3 pages. `pointInTime`. |
| `ProcessorIT` | 1 | A processor runs on a transition and its data change persists. A processor failure rolls back the transition and surfaces the error. The response contract holds: fresh `id`, no `"error": null`. |
| `CriterionIT` | 1 | Criterion true: the transition is taken. Criterion false:<br>• over REST, the manual transition answers `400 WORKFLOW_FAILED`, and the detail ends with the criterion's reason;<br>• through `EntityService` (gRPC), the exception carries code `WORKFLOW_FAILED` and the reason. |
| `CascadeAtomicityIT` | 2 | A processor creates and transitions a second entity via `EntityService` with the tx-token. Both commit with the parent transition, and a later processor failure rolls back both. A **workflow import** inside the processor carries no tx-token and succeeds. A timeout parameter inside a scope is rejected locally. A search inside the scope sees the cascade's own write. |
| `CascadeConcurrencyIT` | 2 | Regression test for §4.5: 16 concurrent 3-level nested cascades all complete within the callout timeout. |
| `AuthContextPlumbingIT` | 2 | A processor sees `authtype=user`, `authid=mock-user-001` and roles `ROLE_ADMIN`, `ROLE_M2M`. `requireRole("ROLE_ADMIN")` is true; `requireRole("ADMIN")` is false. |

### 6.3 Unit tests

- Existing `common/` and `com/example/` unit tests are updated. OBO and event-user tests are
  deleted.

**PR 1 adds tests for:**
- the proto option injection and its conflict check;
- the `allOf` rewrite, typeless-object injection and `orderBy` naming, including their
  failure modes;
- each OpenAPI patch, including its "fixed upstream" failure;
- the DTO round-trip (§3.3.3);
- the processor `type` default;
- response serialisation;
- `CYODA_VERSION` parsing and version comparison;
- `auth-mode=none` startup without client credentials.

**PR 2 adds tests for:**
- **Credential decision:**
  - `CyodaCallContexts` rules 1–5, including a blank JWT, a non-JWT principal, and a scope
    over a user `SecurityContext`;
  - `forMemberStream()`.
- **Interceptor:**
  - cancels a call with no context;
  - cancels on a credential failure.
- **Context propagation:**
  - a paged snapshot search sends the same context on every call;
  - the snapshot-cache key includes the context fingerprint.
- **Transaction token:**
  - attachment per RPC and per REST path;
  - refusal of joined-request parameters.
- **Errors and retry:**
  - each refusal code mapped from both wire shapes: gRPC message prefix plus `retryable`,
    and REST `properties.errorCode`;
  - the M2M retry rules on both doors.
- **Auth context:**
  - `authclaims` parsing: comma form, empty, JSON, garbage;
  - `requireRole` for `SERVICE`, `SYSTEM`, an empty context and empty roles.
- **Callout scope:** `wrap`, `unjoined`, and behaviour once closed.
- **Maintainer scripts:** a value-taking flag given without its value fails with a message;
  `install-cyoda.sh` without Go says Go is needed and names `CYODA_BIN`.
- **Binary lookup and install:** `CyodaBinary`'s lookup order, including `.cyoda/bin`
  auto-detection in a temp project; `CyodaInstallDecision` (`buildSrc`): an explicit binary
  skips, a matching binary is kept whether at `.cyoda/bin` or, failing that, on `PATH`, a
  missing or mismatching one is installed unless `-Dcyoda.allowVersionMismatch=true` keeps
  it, and Windows refuses with the way around it.

## 7. Build and CI

### 7.1 Source sets and tasks

- **`testFixtures`** (`java-test-fixtures` plugin).
- **`integrationTest`:**
  - its classpath includes `sourceSets.test.output`;
  - the task is part of `check`;
  - it passes `cyoda.pinFile`, `cyoda.logDir`, `cyoda.projectDir` (the project directory,
    where the harness looks for `.cyoda/bin/cyoda`) and, when set, `cyoda.bin` and
    `cyoda.allowVersionMismatch`.
  - It depends on `installCyoda` (§7.4), so `./gradlew build` and `check` install the
    pinned cyoda themselves.
  - If no binary can be found after that, it fails with the install command.
  - Where cyoda cannot be installed or run, `./gradlew build -x integrationTest` builds
    without it (documented).
- **`buildLogicTest`** runs `buildSrc`'s own unit tests and is part of `check`.
- **Integration-test hygiene:** the unit test `IntegrationTestHygieneTest` fails when an
  integration-test class declares `@MockitoBean` or `@MockBean`.
- **Jacoco** reports on `test` + `integrationTest`.

### 7.2 Removed

- The Cucumber dependencies, the `se.thinkcode.cucumber-runner` plugin, and the
  `cucumberTest` task with its Jacoco wiring.
- `GherkinE2eTest`, `src/test/java/e2e/`, `src/test/resources/features/` and
  `application-cucumber.yaml`.

### 7.3 Codegen tasks

- `prepareCyodaProto`, `prepareEventSchemas` and `prepareOpenApi`, run before generation.
- The aligned `protoc` and `protoc-gen-grpc-java` versions (§3.3.1).

### 7.4 Installing cyoda

`scripts/install-cyoda.sh [--from-src [<git-ref>] | --src-dir <checkout>] [--dest <dir>] [--archive <file>]`
reads `CYODA_VERSION` and installs the binary to `<dest>/cyoda`. The default `<dest>` is
`.cyoda/bin` in the project root: it is git-ignored, survives `./gradlew clean`, and the
harness finds it there with no configuration (§6.1). A binary installed elsewhere is named
with `CYODA_BIN` or `-Dcyoda.bin`.

- **For a released version:** it downloads the release archive for this OS and architecture
  from `github.com/Cyoda/cyoda-go/releases/v<version>` (or takes the local `--archive`) and
  verifies it against the release's `SHA256SUMS` and against the SHA-256 committed in
  `CYODA_SHA256SUMS`. A missing `CYODA_SHA256SUMS`, or no line for this platform, fails the
  install. A `-dev` pin is a source build and needs no checksum file.
  - It does not use the project's `install.sh`, which runs `cyoda init` and writes the
    user's `~/.config/cyoda/cyoda.env`.
- **With `--from-src`:** it clones `https://github.com/Cyoda/cyoda-go` at `<git-ref>`
  (default: the pinned commit) and runs
  `CGO_ENABLED=0 go build -ldflags "-X main.version=<version> -X main.commit=<sha> -X main.buildDate=<date>" ./cmd/cyoda`
  in the checkout.
  - The build is in workspace mode, because the checkout's `go.work` pins the plugin
    modules.
  - It requires Go ≥ 1.26.7.
  - The Go module path is `github.com/cyoda-platform/cyoda-go`; the repository is
    `Cyoda/cyoda-go`.
- It runs `--version` on the result and prints the binary path on stdout, for `CYODA_BIN`.
  It never runs `cyoda init`.
- **Failures name their cause and the way around it** (`CYODA_BIN` / `-Dcyoda.bin`): no
  Go for a source build (and, for a `-dev` pin, that it has no release to download), a
  failed clone or download (network access, a published release), or a failed `go build`.

**The `installCyoda` Gradle task** (`buildSrc` `InstallCyodaTask`, a dependency of
`integrationTest`) runs the script with no arguments, so the build installs the pinned
cyoda into `.cyoda/bin` itself. `CyodaInstallDecision` decides, at execution time:
- `-Dcyoda.bin` or `CYODA_BIN` set: nothing is installed or run.
- `.cyoda/bin/cyoda` executable (`Files.isExecutable`, the same check `CyodaBinary.locate()`
  makes) and its `--version` matches the pin, by the harness's rule (§3.4): nothing is
  installed, and the task reports `UP-TO-DATE`. The binary is only ever run with `--version`.
- `.cyoda/bin/cyoda` missing or not executable, but a `cyoda` on `PATH` matches the pin:
  nothing is installed either, because `CyodaBinary.locate()` would use that PATH binary
  anyway (`.cyoda/bin` is checked first, but only when it is really usable there).
- `-Dcyoda.allowVersionMismatch=true`: a binary already at `.cyoda/bin` or, failing that, on
  `PATH` is kept even when it does not match the pin, so a binary placed there deliberately
  is never overwritten (`Action.MISMATCH_ALLOWED`, logged at `WARN`, unlike the quiet `INFO`
  a real match gets). With no binary anywhere, this has no effect and the pin is still
  installed.
- Otherwise it is installed, or reinstalled over a binary that does not match.
- On Windows, which cannot run the script, an install that is needed fails with a message
  naming `scripts/install-cyoda.sh`, `-Dcyoda.bin` and `CYODA_BIN`.
- A failed install fails the task after the script's own message, and adds the
  `-Dcyoda.bin` / `CYODA_BIN` / `-x integrationTest` ways around it.

Installing needs Go ≥ 1.26.7, `git` and network access for a `-dev` pin (a source build),
or network access for a released pin (a download).

### 7.5 CI (`.github/workflows/build.yml`)

- **Triggers:** `push` to `main` and `develop`, `pull_request` and `workflow_dispatch`.
- **Hardening:**
  - the workflow token is read-only (`permissions: contents: read`), and `actions/checkout`
    does not persist it (`persist-credentials: false`);
  - every action is pinned by full commit SHA, with its version in a comment;
  - the Gradle wrapper jar is checked by `gradle/actions/wrapper-validation`, and
    `gradle-wrapper.properties` pins the distribution's `distributionSha256Sum`;
  - branch names and dispatch inputs reach `run:` scripts only through `env:`, never as
    expressions spliced into the script.
- **Standard job** (and `test-only`):
  1. `actions/setup-go` at Go 1.26.7, for the source build of the `-dev` pin;
  2. `install-cyoda.sh`, whose output becomes `CYODA_BIN`;
  3. `./gradlew check`, in which `installCyoda` installs nothing, because `CYODA_BIN` is set.
- **`compile-only`** compiles main, test, `testFixtures` and `integrationTest` sources.
- **On failure,** `build/cyoda-logs/` is uploaded. Test results and reports for `test` and
  `integrationTest` are always uploaded. The standard jar artifact is `build/libs/app.jar`.

## 8. Verify early during implementation

Each item is checked first in its area. If an assumption fails, the plan changes; the design
is not quietly bent.

1. **Generated OpenAPI DTOs:** they compile with the §3.3.3 configuration and pass the
   round-trip test. A trial build showed that one schema mapping suffices; this confirms it
   in the real build.
2. **jsonschema2pojo output:** it matches framework usage after the three §3.3.2 transforms.
3. **Protobuf:** the import-only setup compiles, and the runtime descriptor resolves to the
   library's `CloudEvent`.
4. **Pinning audit:** no `synchronized` block holds a lock across a blocking Cyoda call in
   the framework's call paths.
5. **The `ProcessorDefinitionDto` `defaultImpl` template override** works with
   openapi-generator 7.12.0. If it does not, the fallback is a `@JsonTypeInfo` mix-in
   registered on the framework's `ObjectMapper`.

## 9. Findings and obligations outside this template

### 9.1 cyoda-go findings

This project never changes cyoda-go. Every finding becomes an issue in `Cyoda/cyoda-go`
with milestone `v0.9.0`.

| # | Finding | Kind | Issue |
|---|---|---|---|
| 1 | M2M tokens carry roles in `scopes`, not the documented `user_roles`. The claim decides whether the principal is a user or a service, and that rule is undocumented. `caas_org_id` is called a "string UUID". | docs | [#621](https://github.com/Cyoda/cyoda-go/issues/621) |
| 2 | The bootstrap client secret is never generated, but its field comment says it is. | docs (minor) | [#622](https://github.com/Cyoda/cyoda-go/issues/622) |
| 3 | An unrecognised argument starts the server. | bug | [#623](https://github.com/Cyoda/cyoda-go/issues/623) |
| 4 | Discriminators have no `mapping`. This covers the named schemas and the inline condition unions (see the issue's comment). `StateMachineEventDto` has no wire values at all. | bug | [#625](https://github.com/Cyoda/cyoda-go/issues/625) |
| 5 | `EntityModelSetUniqueKeys*` events have no JSON schema. | bug | [#626](https://github.com/Cyoda/cyoda-go/issues/626) |
| 6 | `ArrayConditionDto` declares `operatorType` + `value`; the server parses `values`. | bug | [#627](https://github.com/Cyoda/cyoda-go/issues/627) |

**Impact on the template:**
- #1 and #3: none.
- #4 and #6: patched with "fail once fixed" (§3.3.3).
- #5: unique keys wait for sub-project 2.

Findings discovered while implementing are appended here and filed the same way.

### 9.2 Cyoda Cloud obligations (recorded, not worked around)

The template implements cyoda-go's contract. These are the known places where Cloud must
follow cyoda-go before the template behaves the same against Cloud. They are taken from
cyoda-go's `docs/cloud-parity/`.

- **Joined callbacks.** Cloud's callbacks are separate transactions today
  (`callout-failover.md` §8). Until Cloud joins them, a processor's writes on Cloud are
  neither atomic with the transition nor attributed to the originating user.
- **`authclaims` format.** It must be the comma-separated form
  (`authcontext-attribution.md` §4).
- **`authtype` values.** They must be `user`, `service` or `system`; `service_account` is
  retired.
- **The OpenAPI contract**, including the corrected discriminators (#625) and the array
  condition (#627), once fixed.

## 10. Sub-project 1b: known inputs (to be brainstormed separately)

1b gets its own brainstorm → spec → plan cycle. What is already known:

**Scope**
- An inbound resource server that validates the IdP's bearer JWT (`issuer-uri`). §4.2
  already defines the fail-closed rules.
- Tier-2 infrastructure:
  - cyoda-go running JWT + postgres on the host;
  - Zitadel + Postgres (on `tmpfs`) via Testcontainers `ComposeContainer`;
  - `docker/zitadel/seed.sh`.
- Tier-2 suites: `M2mJoinIT`, `AuthContextIT`, `CascadeAttributionIT` and
  `BffForwardingIT`.
- CI with Docker.

**Persona tokens (decided: option A plus a spike)**
- Personas are Zitadel **machine users**. Each has a project role grant and JWT access
  tokens, and gets its own token through client credentials, as in ctcc's `e2e/personas.go`.
  This is not impersonation.
- **Spike** (time-boxed; 1b's first task): does requesting Zitadel's reserved scope
  `urn:zitadel:iam:org:projects:roles`, together with the project audience scope, put
  `urn:zitadel:iam:org:project:roles` into a machine user's JWT access token?
  - **Yes:** tier 2 asserts roles end to end.
  - **No:** role assertions stay in unit tests and tier 1.

**Known constraints**
- **Issuer:** cyoda compares `iss` byte for byte. Zitadel's external domain is therefore
  `localhost`, and a free port chosen in Java is passed through compose interpolation to
  both `ports:` and `ZITADEL_EXTERNALPORT`.
- **Readiness:** the harness waits for `<issuer>/.well-known/openid-configuration` and the
  seed's PAT file, with a Zitadel timeout of at least 120 s.
- **OIDC registration:**
  - it needs `wellKnownConfigUri`;
  - `issuers` and `expectedAudiences` are still to be decided;
  - `Register` warms keys synchronously;
  - `/api/oauth/oidc/providers/reload` refills them;
  - the tenant is a lowercase UUID, and the bootstrap tenant is set to one.
- **`executedBy`:** it is the bootstrap client's `caas_user_id` (`CYODA_BOOTSTRAP_USER_ID`).
  Set it explicitly and assert it.
- **The `system` principal:** it arises only from scheduled fires, which belong to
  sub-project 2.
- **`seed.sh` prerequisites:** `curl` and `jq`. Linux CI bind-mount permissions for the
  machine key are still to be checked.
- **Deployment note:** production user tokens carry roles only if the IdP asserts them in
  the access token (in Zitadel, `accessTokenRoleAssertion`) and the provider registration
  names the matching `rolesClaim`.

## 11. Out of scope for 1a

- Everything in §10 (1b), sub-project 2 and sub-project 3.
- A browser-session BFF, and role-based authorization in the BFF.
- OBO / token exchange of any kind.
- Workarounds for Cyoda Cloud gaps (§9.2).
- Changes to cyoda-go.
- Cluster-mode testing.
