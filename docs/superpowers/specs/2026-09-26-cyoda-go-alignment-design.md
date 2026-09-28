# Align the Java client template with cyoda-go v0.9.0: sub-project 1a design

**Date:** 2026-09-26
**Status:** implemented
**Branches:** `feat/cyoda-go-v0.9-alignment` (PR 1, #58) and `feat/cyoda-go-v0.9-pr2` (PR 2, #59)
**Contract source:** cyoda-go `release/v0.9.0` at commit `df6ad2c7` (pinned as `0.9.0-dev`
in `CYODA_VERSION`), plus `cyoda help` and docs.cyoda.net

## 1. Intent

cyoda-go is the digital twin of Cyoda Cloud. **cyoda-go defines the API and integration
contract, and Cyoda Cloud follows it.** The Java client template implements that contract.

### 1.1 Outcome

When the whole alignment is done:
- the template implements cyoda-go's contract;
- its contract artifacts are cyoda-go's own files;
- integration suites that start cyoda-go themselves prove it works end to end.

Where Cyoda Cloud differs from cyoda-go, that difference is Cloud's to close. The template
has no workarounds for it (§9.2).

### 1.2 Success criteria for 1a

1. `./gradlew check` builds, runs the unit tests, and runs the tier-1 integration suite
   against an in-memory cyoda-go that the suite starts itself. It needs the pinned cyoda
   binary (§7.4), but no Docker and nothing running beforehand.
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
| Test style | Plain JUnit 5 integration tests; no Cucumber. |
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
- There is no OBO or trusted-key subject-token minting.

**Why this credential model.** It comes from the ctcc-management architecture
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

### 2.1 Delivery of 1a: two PRs

**PR 1 (#58): contract, configuration, OBO removal, harness.** It covers:
- §3 (all of it);
- §4.1;
- §4.6 (no OBO, no event-user resolver);
- §4.7;
- the protocol items in §4.4 that need no callout scope (responses, criteria `matches`,
  `authtype` values, keep-alive);
- the date-time types, snapshot-search paging and the protocol and entity mappers in §4.9;
- §6;
- §7;
- the tier-1 tests `CyodaServerIT`, `ComputeMemberJoinIT`, `ModelAndWorkflowSetupIT`,
  `EntityCrudIT`, `SearchIT`, `ProcessorIT` and `CriterionIT`;
- CI.

Within PR 1 alone, every Cyoda call goes out as M2M, or with no header under
`auth-mode=none`, and a call made while an authenticated user is on the calling thread is
refused (`CyodaCredentialException`) rather than sent as M2M.

**PR 2 (#59, stacked on PR 1): credentials, callout scope, threading.** It covers:
- §4.2, the decision point, and the retry rules;
- §4.4, `CalloutScope`, the tx-token, reads inside a callout, refusal mapping, and the auth
  context as data;
- §4.5, threading and deadlines;
- error surfacing in §4.9;
- the tier-1 tests `CascadeAtomicityIT`, `CascadeConcurrencyIT` and
  `AuthContextPlumbingIT`;
- their unit tests.

**BFF calls.** In the template's default configuration (permit-all inbound), BFF calls go
out as M2M. An app that installs its own JWT `SecurityFilterChain` has its user's raw IdP
token forwarded to Cyoda (§4.2 rule 3). For such apps this is a breaking change (§4.8).

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

There are no other `proto/`, `schema/` or `api/` resource directories.

`CYODA_VERSION` holds two lines:
1. the version, e.g. `0.9.0`, or `0.9.0-dev` before the tag;
2. `commit=<full sha>`.

### 3.2 Sync

The command is
`scripts/sync-cyoda-contract.sh --from-src <cyoda-go checkout> --version <x.y.z[-dev]> [--sha256sums <file>]`.

- It checks that the checkout has the expected files, then copies the files listed in §3.1
  from `proto/`, `docs/cyoda/schema/` (JSON files only; the tree's `.go` files are
  excluded) and `api/openapi.yaml`.
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
small, unit-tested task in `buildSrc` that parses and edits its input structurally, never
with text substitution.

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
- **Import-only CloudEvents.** Only `cyoda/` is a proto source directory. The copy's
  `cloudevents/` directory is added with `compileProtoPath`, so it is on the import path
  only. `io.cloudevents.v1.proto.CloudEvent` comes from the `cloudevents-protobuf` library,
  so exactly one `CloudEvent` class is on the classpath, and all six RPCs resolve to it.
- **Packaging.** The build-modified copy of `cyoda-cloud-api.proto` is excluded from the
  jar's resources; only the vendored original ships.
- **Versions.** `protoc` 4.31.1 aligns with `protobuf-java` 4.31.1, and
  `protoc-gen-grpc-java` 1.73.0 aligns with `grpc` 1.73.0.
- **Not generated:** `CloudEventBatch` and `Cloudevents` (§4.8).
- **Output package:** `org.cyoda.cloud.api.grpc`.

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
- **Date-times.** jsonschema2pojo's `dateTimeType` is `java.time.OffsetDateTime`. cyoda-go
  writes date-times in RFC3339 with nanoseconds and compares point-in-time reads at that
  precision (cyoda-go #349), so a millisecond `Date` would read as-at an instant before the
  change it names. `OffsetDateTime` is lossless and matches the OpenAPI DTOs.
- **Output package:** `org.cyoda.cloud.api.event.*`.

**3.3.3 OpenAPI** (task `prepareOpenApi`, then the `generateOpenApi` task)

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
  `GroupConditionDto.conditions.items` (`GroupConditionDtoAllOfConditions`),
  `GroupedStatsRequest.condition`. Framework code that holds a mixed list of conditions uses
  those interfaces, not `QueryConditionDto`.
- **Operators** are inline enums on each condition DTO (for example
  `SimpleConditionDto.OperatorTypeEnum`, `GroupConditionDto.OperatorEnum`), set with
  `.operatorType(…)`. There are no separate operator schemas.
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
- **Processor `type`.** A processor without `type`, or with `"type": ""`, deserialises as
  externalized, as cyoda-go defaults it; the template's workflows omit it. A Jackson
  `DeserializationProblemHandler`, registered by `CyodaJackson.configure` on the framework's
  protocol mapper (§4.9), resolves a missing or blank `type` to
  `ExternalizedProcessorDefinitionDto`. There is no generator template override and no spec
  patch for it.
- **Schema mapping.** One `schemaMappings` entry,
  `TransitionDefinitionDto_processors_inner=ExternalizedProcessorDefinitionDto`, removes the
  only wrapper type that does not compile.
- **Other types.** `object` / `any` map to `JsonNode`.
- **One spec file.** DTOs come from cyoda-go's single `openapi.yaml`. There are no split
  `openapi-*.yml` files and no `@JsonAlias` post-processing.
- **Round-trip proof** (unit test `WorkflowDtoRoundTripTest`):
  - Input: cyoda-go's exported workflow JSON, with schema defaults present, plus one
    condition of each type.
  - Each is deserialised into the generated DTOs and re-serialised with a `NON_NULL`
    mapper, generated with `containerDefaultToNull=true`.
  - The re-parsed JSON trees must be equal, with numbers compared by value.

**Events for sub-project 2.** The contract includes `EntityPatchRequest`,
`EntityPatchPayload`, `PatchFormat` and `EntityFunctionCalculationRequest/Response`.
`EntityModelSetUniqueKeys*` has no schema upstream (cyoda-go #626), so nothing is generated
for it.

### 3.4 Version pin

- The `integrationTest` task passes the path of `CYODA_VERSION` to the tests as the system
  property `cyoda.pinFile`; the harness (§6.1) reads the pin from it.
- The harness runs `--version` on the binary and compares:
  - for a released version, `major.minor.patch` must be equal (a leading `v` in the
    binary's output is ignored);
  - for a `-dev` pin, the binary's `commit` must equal the pinned commit (compared on the
    common prefix, at least 7 characters).
- A binary that reports `dev (commit unknown)` does not match any pin; the message says to
  use `scripts/install-cyoda.sh` (§7.4).
- On a mismatch the harness fails and shows both values. `-Dcyoda.allowVersionMismatch=true`
  turns the failure into a warning.

## 4. Runtime alignment

### 4.1 Configuration

The template has no `Config.CyodaLight`, no `CyodaLightConfigCustomizer` and no
"cyoda-light" settings. "cyoda-light" was cyoda-go's earlier name.

**`app.config`:**

| Property | Meaning | Default (`application.yml`) |
|---|---|---|
| `cyoda-host` | Host from which the next two properties are derived | — |
| `cyoda-api-url` | REST base URL, including the context path. Also the only origin a Cyoda credential or tx-token is sent to over REST (§4.2) | `https://${cyoda-host}/api` |
| `grpc-address` / `grpc-server-port` | gRPC endpoint | `grpc-${cyoda-host}` / `443` |
| `grpc-tls` | TLS on the gRPC channel | `true` |
| `grpc-call-deadline-ms` | Deadline on each unary gRPC call, and on server-streaming calls inside a callout. Outside a callout, the longest a server-streaming call waits for the connection before it starts (§4.5). Also the timeout of each REST request to Cyoda and the read timeout of the M2M token request. A value ≤ 0 fails startup | `120000` |
| `ssl-trust-all` / `ssl-trusted-hosts` | Trust every certificate / skip certificate checks for the listed hosts only (REST and the token request validate every other host normally). For development | `false` / empty |
| `auth-mode` | `client-credentials` or `none` | `client-credentials` |
| `cyoda-client-id` / `cyoda-client-secret` | M2M credentials, required when `auth-mode=client-credentials` | — |
| `allow-insecure-transport` | Allows plaintext to Cyoda with `auth-mode=client-credentials` (below) | `false` |
| `execution-mode` | `virtual` or `platform` threads for processor, criteria and control work (§4.5) | `virtual` |
| `processor-thread-pool` / `criteria-thread-pool` / `control-thread-pool` | Pool sizes. In `virtual` mode the processor and criteria sizes are ignored (§4.5) | `20` / `20` / `3` |
| `keep-alive-warning-threshold` | ms without a server keep-alive before a diagnostic warning (§4.4) | `20000` |

**`auth-mode`:**
- `client-credentials`: `Authentication` is the M2M token source (`CyodaTokenSource`). A
  missing client id or secret fails startup with one message naming both properties.
- `none`: no OAuth2 `ClientRegistration` and no M2M token source are created.
  `NoCyodaAuthentication` is the token source, and no `Authorization` header is sent. One
  warning at startup says calls carry no credentials.

**Transport check (`auth-mode=client-credentials`).** The client secret goes to
`{cyoda-api-url}/oauth/token` and the M2M token rides every call, so startup fails, naming
the setting, when the token URI is not `https` or `grpc-tls=false`. A loopback host
(`localhost`, `127.0.0.0/8`, `::1`, by its literal form) or
`app.config.allow-insecure-transport=true` lifts the check.

**`application-cyoda-local.yml`** (profile `cyoda-local`, no credentials):
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
| `execution.mode` (read by `@ConditionalOnProperty`) and `app.config.execution-mode` | One key, `app.config.execution-mode`, default `virtual` |
| top-level `cyoda.api.url` | Removed (unused) |
| `app.obo.*`, `app.event.auth-context.*` | Removed with OBO (§4.6) |

### 4.2 Outbound credentials: one decision point (PR 2)

**`CyodaCallContext`** is an immutable value that states the credential and the transaction
for one Cyoda call.

| Part | Values |
|---|---|
| Credential | `None` (no `Authorization` header); `M2m` (the service-account token); `Forward(token)` (a user token, sent unchanged) |
| Transaction | an optional tx-token |

Its `toString()` never prints a token. Its `fingerprint()` is a secret-free identity for
cache keys: the credential kind, a SHA-256 of a forwarded token, and a SHA-256 of a
tx-token.

**`CyodaCallContexts`** is the only class that builds contexts. It has two methods.

**`current()`** is called once on entry to each public method of `EntityService`,
`WorkflowService`, `EdgeMessageService` and `CyodaInit`. If the thread's callout scope has
ended, it throws `CyodaCalloutEndedException` before any network call. Otherwise it applies
these rules in order:

1. `auth-mode=none` → `None`. If a callout scope is active, its tx-token is still set.
2. A callout scope is active (§4.4) → `M2m` plus the scope's tx-token, whatever the
   `SecurityContext` holds.
3. The `SecurityContext` holds a `JwtAuthenticationToken` (the resource server's, always
   authenticated):
   - with a non-blank token → `Forward(token)`;
   - with a blank token → `CyodaCredentialException`.
4. Any other authenticated, non-anonymous principal → `CyodaCredentialException`. The
   framework never silently downgrades to M2M.
5. Otherwise → `M2m`.

`current()` reads Spring Security's thread-local `SecurityContext`. Work that a BFF request
hands to another thread without propagating that context (for example an unwrapped
`CompletableFuture.supplyAsync`) sees no authentication and goes out as M2M. An app that
needs the user's token forwarded from such a thread propagates the context itself, for
example with a `DelegatingSecurityContextExecutor`.

**`forMemberStream()`** returns `None` under `auth-mode=none` and `M2m` otherwise. It never
carries a tx-token, and it is used only to open `startStreaming`.

**Passing the context along:**
- The context is passed **explicitly** through every stage of an operation, including
  multi-step ones such as snapshot search (create, poll status, fetch pages) and the later
  pages of a stream. It is never re-derived from thread-locals. Every `CrudRepository`
  method takes it as its first parameter.
- The snapshot-search cache (Caffeine, `CyodaRepository`) has no loader. Its key includes
  the context fingerprint, so one caller's snapshot is never served to a caller with a
  different credential or transaction. It holds only final (`SUCCESSFUL`) statuses, is
  bounded at 1000 entries, and expires each entry at its snapshot's `expirationDate`. No
  caching takes place inside a callout scope.

**gRPC:**
- The stub interceptor (`CyodaCallInterceptor`) reads the context from `CallOptions`.
- It sets `authorization` and, where allowed (§4.4), `tx-token`.
- A call without a context is cancelled with `FAILED_PRECONDITION` before it reaches the
  transport. The stubs remain beans, but they are documented as framework-internal.
- A failure to obtain a credential (an M2M token fetch failure, or a blank forwarded token)
  cancels the call with `UNAUTHENTICATED`. No call is ever sent unauthenticated. The status
  description is the generic "failed to obtain a Cyoda token", because it can reach a REST
  client; the cause stays attached for the server log.
- gRPC credentials and tx-tokens travel only on the channel to the configured
  `grpc-address`.

**REST:**
- `HttpUtils` takes a `CyodaCallContext` on every request and resolves the header itself. A
  blank forwarded token is refused with `CyodaCredentialException` before any I/O.
- A request that carries a credential (`M2m` or `Forward`), or that is joined (it carries a
  tx-token, even with credential `None`), goes only to the origin (scheme, host and port) of
  `cyoda-api-url`. Any other origin is refused with `IllegalArgumentException` before the
  token is fetched and before any network I/O. Only an unjoined request with credential
  `None` may go elsewhere.

**Logging.** No token is ever logged: not the M2M token, a forwarded token, nor a tx-token.
A CloudEvent is never logged whole, because its attributes carry the tx-token and the
caller's `authid` / `authclaims`; log lines name it by type and id (`CloudEvents.describe`,
which adds only source, `requestId` and `entityId`).

**The M2M token:**
- It comes from client credentials against `{cyoda-api-url}/oauth/token` with HTTP Basic.
  No `scope` is sent.
- It is cached until 60 s before it expires.
- The fetch runs under a `ReentrantLock`, not a `synchronized` block or a
  `ConcurrentHashMap.compute` bin lock, so it cannot pin a virtual thread (§4.5). A thread
  waiting for another thread's fetch can be interrupted; it then fails with
  `CyodaCredentialException`.
- The token request has a 10 s connect timeout and a read timeout of
  `grpc-call-deadline-ms`.
- **Invalidation** takes the token Cyoda rejected. Under the lock, it drops that token from
  the framework's own cache and from Spring's authorized-client service, each only if it
  still holds that token. The next fetch therefore really gets a new token, and a token
  another thread fetched in the meantime is kept.

**Retries.**

| Door | Failure | Rule |
|---|---|---|
| gRPC | status `UNAUTHENTICATED` (a rejected bearer token) | For an `M2m` credential only: invalidate the token that was sent, fetch again, retry once. This applies inside a callout scope too, because a rejected transaction pass arrives as a `success:false` envelope (§4.4), not as `UNAUTHENTICATED`. |
| gRPC | `TOO_MANY_JOINED_REQUESTS`, `TRANSACTION_NODE_UNAVAILABLE` | Retry up to 3 times, with 50, 100 and 200 ms backoff, then throw `CyodaRetryableException`. cyoda-go refuses both on gRPC before anything is applied, so a retry of a collection write is safe. |
| REST | `401` outside a joined call | For an `M2m` credential only: invalidate the token that was sent, fetch again, retry once. |
| REST | `401` on a joined call | Never retried. The token is invalidated, and the call fails with `CyodaCalloutEndedException`, keyed on the status even when the body carries no `errorCode`. |
| REST | `TOO_MANY_JOINED_REQUESTS` | Retry up to 3 times with the same backoff. cyoda-go refuses it before it reads the request body or runs the handler, so nothing was applied. The delay runs on a fresh virtual thread, not the common pool. |
| REST | `TRANSACTION_NODE_UNAVAILABLE` | Never retried: the REST reverse proxy can answer it after the owning node applied the write. It surfaces as `CyodaRetryableException`. |

A `Forward` credential is never retried on `UNAUTHENTICATED` or `401`: the failure surfaces
to the caller.

### 4.3 Inbound (BFF) security

`SecurityConfig` is the permit-all default auto-configuration. Inbound resource-server
configuration is 1b's (§10).

The gRPC admin endpoints (`GrpcAdminController`: `POST /admin/grpc/reconnect`,
`GET /admin/grpc/status`) have no authentication of their own, so they exist only when
`app.admin.grpc-endpoints.enabled=true` (default `false`); an app enables them only where
its own `SecurityFilterChain` protects `/admin/**`. `reconnect` takes no `force` parameter:
it works in `IDLE` only and answers `400` otherwise.

### 4.4 Compute node

**Credential (PR 2).** Compute always uses M2M (§4.2, rule 2). Opening a callout scope
clears the thread's `SecurityContext` and asserts that it is empty; closing the scope puts
the previous `SecurityContext` back.

**Auth context as data (PR 2).**
- `CyodaEventContext` has a default method `authContext()` that returns
  `CloudEventAuthContext(type, id, roles)`.
- `CloudEventAuthContextExtractor` produces it from the CloudEvent attributes.
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

**Transaction token and `CalloutScope` (PR 2).** `CyodaEventContext` has a default method
`txToken()` that returns the `cyodatxtoken` attribute, or `null` when the dispatch carries
none. A **`CalloutScope`**, holding the tx-token and an **open** flag, is opened around each
processor or criterion invocation. The scope ends when the invocation returns, before its
answer is sent. A dispatch without a token opens a scope with no token: its calls go out as
plain M2M, not joined.

- **Attached** (while the scope is open, to calls that carry a context from `current()`):
  - gRPC `entityManage`, `entityManageCollection`, `entitySearch` and
    `entitySearchCollection`, as metadata `tx-token`;
  - REST entity, search and edge-message paths (`entity…`, `search…`, `message…`), as
    `X-Tx-Token`.
- **Never attached** to model or workflow administration (`CyodaInit`, `WorkflowService`,
  `entityModelManage`, REST `model/…` paths). cyoda refuses those with
  `MODEL_ADMIN_IN_JOINED_TRANSACTION`.
- **Parameters refused on joined requests.** The framework's API exposes two
  transaction-control parameters, `transactionWindow` and `transactionTimeoutMs`. Inside a
  scope, a non-null value of either is refused before sending, with an
  `IllegalArgumentException` that names the parameter
  (`docs/cloud-parity/transaction-control-params.md`). The framework never sends
  `timeoutMillis` or `transactionSize`.
- **After the scope ends,** a call through it throws `CyodaCalloutEndedException` locally,
  before any network call.
- **App-spawned threads** lose the scope. `CalloutScope.wrap(Runnable)`,
  `CalloutScope.wrap(Callable)` and `CalloutScope.wrapSupplier(Supplier)` carry it over.
  Without them, calls from such a thread go out unjoined as M2M.
- **`CalloutScope.unjoined(Supplier)`** detaches a block from the callout's transaction:
  inside a callout scope, the block runs as M2M with no tx-token. It is cyoda's documented
  remedy for `COMMIT_IN_JOINED_TRANSACTION`. Outside a callout scope there is no
  transaction to detach from, so the block runs unchanged, with whatever credential
  `current()` would otherwise produce. `unjoined` never changes a credential.
- **Token lifetime.** A tx-token lives for the try's answer limit plus
  `CYODA_CALLOUT_PASS_ALLOWANCE` (default 30 s).

**Reads inside a callout (PR 2).**
- **Point in time.** A caller-supplied `pointInTime` on a joined read (`getById`,
  `findByBusinessId`, `search` / `findAll` and the streams, `getEntityCount`,
  `getEntityStatsByState`, `getEntityChangesMetadata`) is passed through to cyoda
  unchanged, alongside the tx-token. cyoda-go defines it as a historical read of committed
  state: it returns what was committed as at that time, and does not see the callout's own
  uncommitted writes. Reading without `pointInTime` is the only way to see them.
- **Reload after a write.** Inside a scope, `create`, `update`, `updateByBusinessId`,
  `save` and `updateAll` reload the written entity without `pointInTime`, because the
  reload must see the transaction's own write.
- **Search is a direct search.** Async snapshot search runs detached from the transaction
  (`internal/domain/search/service.go:1051-1053`; `tx-aware-search.md`), so it would not
  see the cascade's own uncommitted writes. Inside a scope, `findAll`, `search`,
  `findByBusinessId`, `findByCompositeKey` and the streams run as one direct search, capped
  at 10 000 entities (`CyodaRepository.DIRECT_SEARCH_LIMIT`); `inMemory` makes no
  difference there.
  - Only page 0 can be read. A request with `pageNumber > 0`, a `searchId`, or a
    `pageSize` above 10 000 throws `IllegalStateException` before anything is sent.
  - The search asks for one entity more than the page. If it arrives, the page reports a
    next page, and reading that page throws `IllegalStateException` rather than silently
    stopping.
  - A page of exactly 10 000 leaves no room for that probe. If it comes back full, it
    throws `IllegalStateException`, since whether more entities exist cannot be told.
- **Streams.** `streamAll` and `searchAsStream` ignore `pageSize` inside a scope. Each runs
  one direct search at the 10 000 cap, reads the whole result into memory, and streams
  every match, up to 9 999. A result that fills the cap throws `IllegalStateException` when
  the stream is created, before anything is streamed. Outside a scope, streams page lazily
  at `pageSize`.
- **Answer size.** cyoda caps a joined answer at `CYODA_CALLOUT_JOINED_RESPONSE_MAX_BYTES`
  (10 MiB by default) and refuses a larger one with `JOINED_RESPONSE_TOO_LARGE`, which the
  framework raises as `CyodaJoinedResponseTooLargeException`. With entities larger than
  about 1 KB this ceiling is reached well before 9 999 matches. The remedy is to narrow the
  condition, since a read inside a callout cannot be paged.

**Refusal mapping (PR 2).**
- **Over gRPC,** a failure is a `success:false` envelope. Its `code` is coarse
  (`CLIENT_ERROR` or `SERVER_ERROR`). The cyoda error code is the prefix of `message` up
  to the first colon, and `retryable` is present only when `true`
  (`internal/grpc/errors.go:42-48`; `cyoda help errors`).
- **Over REST,** the error code is the problem detail's `properties.errorCode`.
- The mapping (`CyodaErrors`) keys on the **code**. The HTTP status alone cannot
  distinguish, for example, `410 CALLOUT_SUPERSEDED` from `410 TRANSACTION_EXPIRED`. The one
  exception is a `401` on a joined REST request, which is keyed on the status (§4.2).

| cyoda code (status) | Exception | Retry |
|---|---|---|
| `CALLOUT_SUPERSEDED` (410), `TRANSACTION_EXPIRED` (410), `TRANSACTION_NOT_FOUND` (404), `UNAUTHORIZED` or `401` on a joined request | `CyodaCalloutEndedException`: stop working on this request | no |
| `TOO_MANY_JOINED_REQUESTS` (503) | `CyodaRetryableException` | up to 3 times with 50, 100 and 200 ms backoff, on gRPC and REST, then throws |
| `TRANSACTION_NODE_UNAVAILABLE` (503, cluster) | `CyodaRetryableException` | on gRPC as above; never on REST |
| `CONFLICT` (409, retryable) | `CyodaRetryableException` | not retried locally; the processor fails and cyoda's callout retry policy applies |
| `JOINED_RESPONSE_TOO_LARGE` (413) | `CyodaJoinedResponseTooLargeException`: narrow the condition | no |
| `COMMIT_IN_JOINED_TRANSACTION` (409) | `CyodaCommitInJoinedTransactionException`: use `CalloutScope.unjoined` | no |
| `FORBIDDEN` (403): tenant or role | `CyodaAccessDeniedException` | no |
| `MODEL_ADMIN_IN_JOINED_TRANSACTION` (400) | cannot occur, because the token is never attached; if it does, `IllegalStateException` | no |
| any other code | `CyodaHttpException` (REST: carries the HTTP status and the code) or `CyodaOperationException` (gRPC: carries the code) | no |

**Attribution (the Javadoc on `CalloutScope`):**
- Joined writes are attributed to the transaction's origin.
- Under `COMMIT_BEFORE_DISPATCH` with `startNewTxOnDispatch=false`, the callout carries no
  tx-token, so callbacks are ordinary M2M requests attributed to the M2M account
  (`cyoda help workflows`, "Attribution handover").

**Modifying the entity under processing.** A processor must not modify the entity under
processing via `EntityService`. cyoda keeps a joined write to that entity only when the
processor answers with no payload, and the framework always answers with a payload.

**Protocol (PR 1):**

| Item | Behaviour |
|---|---|
| Join | No `joinedLegalEntityId` is sent. cyoda uses the token's tenant when it is absent (`internal/grpc/streaming.go:56-66`). Sending it would only add a `PermissionDenied` risk. |
| `authtype` values | `user`, `service` and `system` are recognised; `service_account` is not. An unrecognised value yields an empty auth context and a warning. |
| Responses | Every processor or criterion response carries a fresh `id`, the callout's `requestId` and the `entityId`, on success and on error. A failure sends `success: false` explicitly. `error` is omitted rather than serialised as `null`. A serialisation unit test covers this. |
| Criteria | `matches` is always set. |
| Keep-alive | cyoda evicts a member after 30 s without inbound activity from the member, or after a stalled write (`internal/grpc/streaming.go:191-198`). The member answers every server keep-alive (`KeepAliveEventHandlingStrategy`). `keep-alive-warning-threshold` is 20000 ms, so a silent **server** is reported promptly; this is a diagnostic and has no bearing on eviction. |
| Stream writes | Single-writer: `ConnectionManager.sendEvent` is `synchronized`, and `onNext` does not block. |

### 4.5 Threading and deadlines (PR 2)

**Why.** A callout that calls back into Cyoda holds its thread while it waits for the
callback, and a nested cascade's callback needs another thread. With a bounded pool,
nested or concurrent cascades beyond the pool size deadlock until their callouts time out.

**Executors:**
- **Processor and criterion tasks.** In `virtual` mode (the default), each task gets its own
  virtual thread (`Executors.newThreadPerTaskExecutor` with a virtual factory).
  `processor-thread-pool` and `criteria-thread-pool` are ignored in that mode.
- **Control events** (greet, keep-alive, ack) keep a small fixed pool of
  `control-thread-pool` threads: virtual threads in `virtual` mode, platform threads in
  `platform` mode.
- **`platform` mode** keeps fixed pools of the configured sizes, with a documented sizing
  rule: a nested cascade holds one processor thread per level while it waits for the level
  below, so `processor-thread-pool` must leave at least one free thread per cascade level,
  for every cascade running at once.
- **Cyoda calls.** Whatever the `execution-mode`, the repository behind `EntityService`
  runs its blocking stub calls, including the poll and delay stages of a snapshot search,
  on its own `Executors.newVirtualThreadPerTaskExecutor()`. The context travels as an
  argument. On shutdown, in-flight calls get 5 s to finish and are then interrupted; a
  snapshot poll abandoned by shutdown fails rather than hanging its caller.

**Deadlines:**
- `grpc-call-deadline-ms` applies to every unary gRPC call. Each attempt of a retried call
  gets a fresh deadline.
- The server-streaming calls (`entityManageCollection`, `entitySearchCollection`) carry the
  same deadline only when joined; the tx-token's lifetime bounds them anyway.
- Unjoined server-streaming calls (search, snapshot result pages, count, stats, changes
  metadata, `saveAll`/`updateAll`/`deleteAll`) have no deadline, because their duration
  grows with the result size. Instead, before such a call starts, `CyodaRepository` waits for
  the channel to be `READY` (`ChannelReadiness`, using `ManagedChannel.getState(true)` and
  `notifyWhenStateChanged`) for at most `grpc-call-deadline-ms`.
  - If the channel is not `READY` in time, the call fails without being sent, with
    `CyodaRetryableException` code `UNAVAILABLE` and the message
    `Cyoda was unreachable for <N> ms: …`. If the channel is already shut down, the message
    is instead `Cyoda is unreachable: the gRPC channel to it is shut down`. `CyodaGrpcCalls`
    does not retry either case.
  - Once the call has started, nothing bounds its total duration, so a long but healthy
    stream is never cut off.
  - The stubs keep `withWaitForReady()`, so a brief reconnect between the check and the
    start still works; the readiness wait is the bound.
  - The wait blocks on a latch, on the repository's virtual-thread executor.
  - If the channel is already `TRANSIENT_FAILURE` as the wait begins, it calls
    `ManagedChannel.resetConnectBackoff()`, so a call made right after Cyoda recovers retries
    immediately instead of sitting out gRPC's own reconnect backoff. This is rate-limited to
    at most once every 500 ms, so a burst of calls during an outage does not itself defeat
    gRPC's backoff by resetting it on every single one.
- Each REST request to Cyoda has a timeout of `grpc-call-deadline-ms`, and the REST and
  token HTTP clients have a 10 s connect timeout.

**Pinning.** On JDK 21, a `synchronized` block that holds a lock across a blocking call
pins the virtual thread. No framework call path does this: the M2M token fetch uses a
`ReentrantLock` (§4.2), and the only `synchronized` method on a call path,
`ConnectionManager.sendEvent`, does not block.

### 4.6 No OBO and no event-user resolver (PR 1)

The template has none of the following. A downstream copy deletes them:
- `OboAwareAuthentication`, `OboTokenService`, `OboKeyRegistrationService`,
  `SubjectTokenSigner`, `AesGcmEncryption`, `OboSigningKey`, `OboProperties`,
  `OboTokenException`;
- `EventAuthContextHandler`, `DefaultEventUserResolver`, `EventUserResolver`,
  `EventUserIdentity`, `EventUserResolutionException`;
- `AuthContextMode`, `EventAuthContextProperties`, `EventAuthContextScope`,
  `EventAuthContextMissingException`;
- `AuthClaimsParser`;
- the OBO bootstrap resources and their tests.

`Authentication` is the M2M token source (§4.1, §4.2).

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
  workflows. They live in `src/test/resources/example/config/snippets/`, where each
  fragment is validated by deserialising it into its DTO. The `function_criterion` snippet
  has no pre-check criterion (cyoda-go #628).
- **REST paths.** Every REST path the framework calls exists in `openapi.yaml`.
- **`CyodaInit`** creates a model before importing its workflow; cyoda-go rejects a
  workflow import for a missing model. With `--recreate-models`, it unlocks a model
  (tolerating `MODEL_ALREADY_UNLOCKED`) before deleting and recreating it, because cyoda-go
  refuses to delete a locked model. While a model still has entities, cyoda-go refuses the
  unlock with `MODEL_HAS_ENTITIES`; `CyodaInit` then fails with a message naming
  `--recreate-models`, keeps cyoda's `CyodaHttpException` as the cause, and the model stays
  locked.

### 4.8 Downstream impact

`SYNCING_WITH_JAVA_TEMPLATE.md` has a "Breaking changes in the cyoda-go v0.9.0 alignment"
table. It covers:

- **Contract files:** the `src/main/resources/cyoda/` layout.
- **Local profile file:** the template ships `application-cyoda-local.yml` (profile
  `cyoda-local`), without credentials, and `.gitignore` ignores every other
  `application-*.yml`. Credentials an app keeps in a file under `src/main/resources` move
  out, because `bootJar` packages that directory whether git ignores a file or not.
- **Configuration:** every key in §4.1's migration table, including the `execution-mode`
  default of `virtual`.
- **Generated OpenAPI DTOs:**
  - one package, `org.cyoda.cloud.api.common.model`; `workflow.model` and `search.model`
    are gone;
  - operator enums are per-class, set with `.operatorType()`;
  - condition collections use the generated interfaces;
  - `EntityCrudOperations.FieldFilter` uses `SimpleConditionDto.OperatorTypeEnum`;
  - simple-name clashes: `EntityChangeMeta`, `EntityMetadata` and
    `EntityTransactionResponse` exist in both `org.cyoda.cloud.api.common.model` and
    `org.cyoda.cloud.api.event.*`, so two wildcard imports do not compile.
- **Generated proto:** `CloudEventBatch` and `Cloudevents` are not generated.
- **Date-times and point in time:** event DTO date-times, `EntityWithMetadata.getCreationDate()`
  and `CrudRepository` `pointInTime` parameters are `OffsetDateTime`; `EntityService` and
  `SearchAndRetrievalParams` take both `Date` and `OffsetDateTime` (§4.9).
- **Mappers:** framework classes take `CyodaObjectMapper`. Protocol messages use its fixed
  protocol mapper; entities use the app's primary mapper, unmodified, so the stored entity
  JSON follows the app's Jackson settings. Several `ObjectMapper` beans need one marked
  `@Primary` (§4.9).
- **`deleteAll`:** the template does not send `transactionSize`, so `deleteAll` is one
  transaction by default (cyoda-go still honours `transactionSize` when sent, #379).
  `EntityDeleteAllRequest` has no `pageSize`.
- **Signatures:** `CrudRepository` (a leading `CyodaCallContext`), `HttpUtils` (a
  `CyodaCallContext` instead of a token), and the constructors of `EntityServiceImpl`,
  `CyodaRepository`, `WorkflowServiceImpl`, `EdgeMessageServiceImpl`, `CyodaInit`,
  `ConnectionManager` and `AbstractEventStrategy`. `WorkflowService` has
  `importWorkflows`. `Authentication` exists only under `auth-mode=client-credentials`;
  inject `CyodaTokenSource`.
- **Errors:** typed exceptions thrown directly, `*OrNull` semantics, `EdgeMessageService`
  not-found results (§4.9).
- **Compute:**
  - `CyodaEventContext.authContext()` / `txToken()`;
  - `CloudEventAuthContext(type, id, roles)`;
  - `authtype` values;
  - callout responses carry a fresh `id`, the callout's `requestId` and the `entityId`.
- **New types:** `CalloutScope`, `CyodaCallContext`, `CyodaCallContexts` and the new
  exceptions.
- **OBO and the event-user resolver do not exist.** Apps with their own JWT
  `SecurityFilterChain` forward the user's IdP token (§2.1).
- **Threads:** processor and criterion tasks on virtual threads by default (§4.5).
- **Snapshot search paging** (§4.9).
- **Workflows:** version 1.5.
- **Build:** `check` runs the integration suite and needs a cyoda binary (§7); the runnable
  jar is `build/libs/app.jar`.

### 4.9 Service API behaviour

**Errors.** `EntityService`, `WorkflowService` and `EdgeMessageService` throw the typed
Cyoda exceptions (§4.4) directly, never wrapped in a `CompletionException`
(`Futures.joinUnwrapped`). The rethrown exception carries a suppressed
`Futures.CallerStack` whose trace shows the calling thread's frames. Local argument
refusals are `IllegalArgumentException` or `IllegalStateException`.
- `findByBusinessIdOrNull` and `findByCompositeKeyOrNull` return `null` only when nothing
  matches. Every error propagates.
- `EdgeMessageService.getMessageById` / `getMessageContent` return `null`, and
  `deleteMessage` returns `false`, only for a `404` (`CyodaHttpException`).
  `TRANSACTION_NOT_FOUND`, also a `404`, is a `CyodaCalloutEndedException` and propagates.
  `createMessage` has no not-found result.
- `WorkflowService.importWorkflows` never joins a transaction; a refusal throws the typed
  exception.
- `WorkflowService.exportWorkflows` throws `WorkflowExportException` on any failure, with
  the typed exception as its cause. A missing model is a `WorkflowExportException` with
  status `404`.

**REST error bodies.** `EntityCrudOperations` and the example controller never put an
exception's message (which can carry internal hosts, URLs or Cyoda error text) in a
response. `ErrorResponses` answers with a ProblemDetail whose `detail` is a fixed, generic
description plus a correlation id (also its `correlationId` property), and logs the
exception at `ERROR` under that id. The status is the caller's (`400`), except `500` for a
credential that cannot be determined or obtained (`CyodaCredentialException`) or that Cyoda
rejects (gRPC `UNAUTHENTICATED`, REST `401`): the inbound request was already accepted, so
that is the service's own fault, not the client's.

**Point in time.**
- Event DTO date-times are `OffsetDateTime` (§3.3.2), and so is
  `EntityWithMetadata.getCreationDate()`.
- Every `EntityService` method with a `pointInTime` has an `OffsetDateTime` overload that
  keeps full precision, and a `java.util.Date` overload that converts to UTC
  (`PointInTime.toOffsetDateTime`). `SearchAndRetrievalParams.pointInTime` is an
  `OffsetDateTime`; its builder accepts both. A bare `null` literal is ambiguous between
  the overloads and needs a cast.
- `CrudRepository` takes `OffsetDateTime` only. `EntityCrudOperations` passes the REST
  `OffsetDateTime` through unchanged.
- Outside a callout, `create`, `update`, `updateByBusinessId`, `save` and `updateAll` reload
  the written entity as at its change's `timeOfChange`, untouched, so the reload shows
  exactly the transaction just committed. Inside a callout they reload without
  `pointInTime` (§4.4).

**Snapshot search paging.** Outside a callout, `search` and `findAll` without `inMemory`
run an async snapshot search.
- The first page creates the snapshot and waits for its final status, polling every
  `pollIntervalMs` up to `awaitLimitMs`.
- The returned `searchId` is the snapshot id. A page request with a `searchId` always reads
  that same snapshot (its status via `SnapshotGetStatusRequest`); it never starts a new
  search.
- `totalElements` is the snapshot's final count.
- An expired or unknown `searchId`, or a snapshot that ends in a status other than
  `SUCCESSFUL`, fails; it never falls back to a new search.

**Protocol and entity mappers.** `CyodaObjectMapper` holds the framework's two mappers,
one per role. It is a Spring bean built by `CyodaJacksonAutoConfiguration` after Spring
Boot's `JacksonAutoConfiguration`.
- **Protocol mapper (`protocol()`)** is used for every Cyoda protocol message:
  - CloudEvent payload JSON;
  - the event DTOs (`org.cyoda.cloud.api.event.*`) and the OpenAPI models
    (`org.cyoda.cloud.api.common.model`);
  - workflow JSON and REST bodies sent to Cyoda;
  - `DataPayload` envelopes, including reading and writing the entity JSON tree in their
    `data` field, and the Cyoda metadata in `meta`.

  It is fixed: `CyodaJackson.configure(new ObjectMapper())` with
  `FAIL_ON_UNKNOWN_PROPERTIES` off, so a field cyoda-go adds never breaks parsing. No
  `spring.jackson.*` setting and no app bean reaches it. `CyodaJackson.configure` registers
  `JavaTimeModule`, turns `WRITE_DATES_AS_TIMESTAMPS` off (cyoda-go expects RFC3339 text),
  adds the processor-type handler (§3.3.3), and makes every tree it parses — entity data,
  REST responses, workflow JSON — hold decimals losslessly:
  - `DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS`, so a decimal literal parses as
    `DecimalNode`, not `DoubleNode`: a `BigDecimal` entity field beyond ~17 significant
    digits survives an inbound callout unchanged. A consequence outside entity data too:
    `JsonUtils.jsonToMap` now yields `BigDecimal` where it used to yield `Double`, and code
    that inspects a raw `JsonNode` from this mapper sees `isBigDecimal()`, not `isDouble()`.
  - `JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES` off. Jackson 2.19 defaults this to
    `true`, which normalizes a `DecimalNode`'s `BigDecimal` by stripping trailing zeroes —
    collapsing its scale to a negative exponent (`10.00` becomes `1E+1`) and then writing it
    back that way. Off, a `DecimalNode` keeps the exact scale it was parsed with.
  - `JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN` enabled. `BigDecimal.toString()` still
    switches to scientific notation once its adjusted exponent passes a threshold regardless
    of the setting above (e.g. `1e-7`); this makes the generator always use
    `toPlainString()` instead, so every decimal serializes as a plain JSON number.
- **Entity mapper (`entities()`)** is used for every conversion between an app entity class
  and a JSON tree: writing an entity for create, update and save, reading one from a
  payload's `data`, the serializers' `entityToJsonNode`/`extractEntity`, and the values of
  search conditions built from entity fields. It is a copy of the app's primary
  `ObjectMapper`, taken once at startup with `ObjectMapper.copy()`: every app setting
  (naming, modules, date format) carries over, **except** that
  `JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES` is always disabled on the copy. The
  app's own bean is never modified.
  - Why: the entity-to-tree conversion (`valueToTree` / `entityToJsonNode`) is an
    intermediate step the app never sees or configures, not a choice the app made. Left at
    Jackson 2.19's default, it would collapse a `BigDecimal` entity field's scale there
    (`10.00` becomes a `DecimalNode` of scale `-1`, written back as `1E+1`) even though the
    app's own JSON output — serializing a `BigDecimal` field directly, not through this tree
    — already keeps it. So: entity conversion follows the app's Jackson settings, except
    that decimal scale is always kept.
  - The entity JSON stored in Cyoda therefore follows the app's Jackson settings. With
    `SNAKE_CASE` naming, the entity is stored in snake_case, and JSON paths in conditions and
    workflows must use those names.
  - Spring Boot's default mapper writes entity date-times as ISO-8601 text (it registers
    `JavaTimeModule` and turns `WRITE_DATES_AS_TIMESTAMPS` off). The framework forces
    nothing the app did not choose: an app that turns timestamps on stores numbers.
  - The entity JSON always reaches the protocol message as a JSON tree (`valueToTree`),
    never as text, so the protocol mapper writes it verbatim — including a `BigDecimal`
    field's scale, end to end in both directions (an inbound callout's entity data, via
    `protocol()`'s own decimal settings above, and an outgoing entity, via this copy).
- **An ambiguous app mapper fails at startup.** With several `ObjectMapper` beans and none
  primary, the bean fails with a message naming them and the fix (mark one `@Primary`).
  With no `ObjectMapper` bean at all, it fails saying to keep `JacksonAutoConfiguration` or
  define one. There is no fallback to a bare mapper.
- `CyodaObjectMapper` is a holder, not an `ObjectMapper` bean, because a second
  `ObjectMapper` bean would make Spring Boot's `JacksonAutoConfiguration` back off.
- `CyodaObjectMapper.of(appMapper)` builds the pair by hand. Tests and tools without Spring
  use `CyodaObjectMapper.standalone()`, whose entity mapper is the one Spring Boot builds
  when the app sets no `spring.jackson.*` property.

## 5. cyoda-go facts relied on

These hold for cyoda-go `release/v0.9.0`, `cyoda help` and docs.cyoda.net.

**Transaction tokens**
- Names: `cyodatxtoken`, `tx-token`, `X-Tx-Token`.
- Tokens are issued under mock IAM as well (`app/app.go:153-171`).
- A token's lifetime is the answer limit plus `CYODA_CALLOUT_PASS_ALLOWANCE`.
- Joined callbacks are served one at a time per transaction. cyoda releases its gate across
  dispatch.
- Refusals: codes, statuses and order as in the CHANGELOG. They arrive as envelopes on
  gRPC and as problem details on REST.
- `TOO_MANY_JOINED_REQUESTS` is refused before anything is applied, on both doors: on gRPC
  before the request message is received or the handler runs, on REST before the body is
  read or the handler runs (`internal/domain/txjoin/txjoin.go`,
  `internal/httpmw/txjoin_mw.go`; `cyoda help errors TOO_MANY_JOINED_REQUESTS`: "The
  refused callback changes nothing").
- `TRANSACTION_NODE_UNAVAILABLE` on gRPC is emitted only when the token's owner is dead or
  unknown, before the call is forwarded (`internal/grpc/txroute_interceptor.go`). On REST,
  the reverse proxy may answer it after the owning node applied the write.
- A joined answer is capped at `CYODA_CALLOUT_JOINED_RESPONSE_MAX_BYTES` (10 MiB by
  default), refused with `JOINED_RESPONSE_TOO_LARGE`.
- A `pointInTime` read inside a joined transaction is a historical read of committed state
  and ignores the transaction's buffer (`cyoda help crud`).
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

**Entities and search**
- Date-times are RFC3339 with nanoseconds, and point-in-time reads compare at that
  precision (#349).
- Search rejects function conditions (`docs/cloud-parity/function-condition-search-rejection.md`).
- `EntityDeleteAllRequest` honours `transactionSize` when sent (#379).

**Models and workflows**
- Schema versions 1.1 to 1.5 are accepted, and unknown fields are rejected.
- `$.` is required on JSON paths.
- An empty `calculationNodesTags` matches every member of the tenant.
- A manual transition refused by its criterion answers `400 WORKFLOW_FAILED` with detail
  `transition "<name>" criterion not matched: <reason>`. Over gRPC the message carries the
  `WORKFLOW_FAILED:` prefix.
- A workflow import for a missing model is rejected (`MODEL_NOT_FOUND`).
- A locked model cannot be deleted (`MODEL_ALREADY_LOCKED`), and a model with entities
  cannot be unlocked (`MODEL_HAS_ENTITIES`).
- The change level is stored whether or not the model is locked.

**Server**
- Keep-alive defaults 10 s / 30 s; env var names and defaults; `/api/health`.
- Env files are read in this order: `/etc/cyoda/cyoda.env`, then
  `$XDG_CONFIG_HOME` or `~/.config/cyoda/cyoda.env`, then `./.env`, then profiles.
- Version ldflags: `main.version`, `main.commit`, `main.buildDate`. Go 1.26.7.
- An unrecognised command-line argument starts the server (#623).

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
- **Isolation:** `HOME` points at a fresh temp directory, which is also the working
  directory, and `XDG_CONFIG_HOME` at its `.config`. `CYODA_PROFILES` is not set.
- **Ports:** `CYODA_HTTP_PORT`, `CYODA_GRPC_PORT` and `CYODA_ADMIN_PORT` are three free
  ports; `CYODA_ADMIN_BIND_ADDRESS=127.0.0.1`; `CYODA_CONTEXT_PATH=/api`.
- **Server:** `CYODA_STORAGE_BACKEND=memory`, `CYODA_IAM_MODE=mock`,
  `CYODA_IAM_MOCK_KIND=user`, `CYODA_IAM_MOCK_ROLES=ROLE_ADMIN,ROLE_M2M`.
- **Timing:** `CYODA_KEEPALIVE_INTERVAL=10`, `CYODA_KEEPALIVE_TIMEOUT=30`,
  `CYODA_DISPATCH_WAIT_TIMEOUT=5s` (cyoda's defaults, set explicitly so a
  `/etc/cyoda/cyoda.env` cannot change them), `CYODA_SCHEDULER_SCAN_INTERVAL=50ms`.
- **Output:** `CYODA_SUPPRESS_BANNER=true`, `CYODA_ERROR_RESPONSE_MODE=verbose`,
  `CYODA_LOG_LEVEL=info`.

**Profiles.** A `Profile` value object carries overrides on top of those defaults.
`CyodaProfiles` names the ones tests may request: `default`, and `keepalive-short`
(keep-alive 1 s / 3 s). `CyodaTestEnvironment` starts one server per profile per test JVM,
lazily, and shares it across test classes.

**Readiness.** The harness polls `GET /api/health` until it answers `200`, and fails fast
if the process exits first. The limit is 30 s.

**Logs.** Output goes to `build/cyoda-logs/<profile>-<timestamp>.log`. The last 50 lines are
attached to any startup or test failure: a startup failure's message includes them, and
`CyodaServerExtension` adds them to a failing test or lifecycle method as a suppressed
exception.

**Shutdown.** A JUnit root-store `CloseableResource` stops every server at the end of the
test engine run: SIGTERM, 5 s grace, then kill, and the temp directory is deleted. A JVM
shutdown hook does the same as a backstop. Servers stop **before** Spring's cached test
contexts close in their own JVM shutdown hook, so a cached context's member stream may log
one connection loss after the server is gone. That is expected, and the framework's
logging is not changed for it.

**`@CyodaIntegrationTest`** is a meta-annotation that combines `@SpringBootTest` and the
JUnit extension `CyodaServerExtension`. Its `profile` attribute selects the server. A
`ContextCustomizerFactory` (registered in the test fixtures' `META-INF/spring.factories`)
injects into each test context:
- `app.config.cyoda-api-url`, `grpc-address`, `grpc-server-port`;
- `grpc-tls=false`, `auth-mode=none`;
- `server.port=0`;
- a **context-unique** `grpc-processor-tag`.

**Tag isolation**
- Test workflows carry a `${tag}` placeholder in `calculationNodesTags`.
  `WorkflowTemplating` sets every processor's and function criterion's tags to the
  context's tag when loading a workflow, and **refuses** a workflow whose tags are missing
  or empty. An empty list would match every member of `mock-tenant`.
- A second cached Spring context therefore never receives another context's callouts.
- The unit test `IntegrationTestHygieneTest` (§7.1) rejects `@MockitoBean` / `@MockBean` in
  integration tests, since a forked context would join with the same tag.

**Helpers**
- `CyodaTestEnvironment.server(profile)` and `rest(profile)`; `CyodaRest`, a raw REST
  client.
- `WorkflowTemplating.load(resource, tag)` and `applyTag(workflow, tag)`.
- `CyodaModelSetup.createModel(…)` (sample data, `STRUCTURAL` change level, workflow
  import, lock) and `importWorkflow(…)`.
- `CyodaAwait.memberReady(tracker, limit)`: waits for the greet.
- Each test class uses model names with a random suffix, so no two classes share a model.
- `ModelAndWorkflowSetupIT` runs `CyodaInit` with `--recreate-models` against the example
  application's `ExampleEntity` model, whose name `CyodaInit` derives from the entity class.
  No other integration test touches that model.

**The example application.** It lives in `src/test/java/com/example`. The `integrationTest`
source set has `sourceSets.test.output` on its classpath. `@SpringBootTest` finds
`Application`, and a test configuration in the integration-test sources (`ExampleAppScan`)
scans `com.example.application`.

### 6.2 Tier-1 suite: `integrationTest` (mock IAM, memory storage; part of `check`)

| Class | PR | Proves |
|---|---|---|
| `CyodaServerIT` | 1 | The pinned cyoda starts with memory storage and answers `/api/health`. |
| `ComputeMemberJoinIT` | 1 | On the `keepalive-short` profile (1 s / 3 s), the app joins and receives the greet. A processor transition started after 10 s of idleness is still served. |
| `ModelAndWorkflowSetupIT` | 1 | `CyodaInit` imports sample data, locks the model and imports the v1.5 workflow; the export round-trips through the generated DTOs, equal to what was imported. A second `--recreate-models` run recreates the locked model, and so does a run on an unlocked one. On a model with entities, `--recreate-models` fails with `MODEL_HAS_ENTITIES`, keeps the typed cause, and leaves the model locked. Every workflow under `example/config/workflow/` imports. |
| `EntityCrudIT` | 1 | Via `EntityService` (gRPC): create, bulk create, get, get-all, update (loopback), update with transition, delete, delete-all. |
| `SearchIT` | 1 | Direct search with `$.` conditions: simple, nested group with `OR`, lifecycle, array (`values` form). Function conditions are not covered, because `EntityService.search` cannot express them and cyoda-go rejects them in search. Paged async snapshot search over 250 entities: three pages of one snapshot, with `totalElements`, `totalPages` and `hasNext`, partitioning the set. A `pointInTime` read sees the past value. |
| `ProcessorIT` | 1 | A processor runs on a transition and its data change persists. A processor failure rolls back the transition and surfaces the error. |
| `CriterionIT` | 1 | Criterion true: the transition is taken. Criterion false, with the entity left unchanged:<br>• over REST, the manual transition answers `400 WORKFLOW_FAILED`, and the detail contains the criterion's reason;<br>• through `EntityService` (gRPC), the exception carries code `WORKFLOW_FAILED` and the reason. |
| `CascadeAtomicityIT` | 2 | A processor creates and transitions a second entity via `EntityService` with the tx-token. Both commit with the parent transition, in the same transaction as the parent's cascade change, and a search inside the scope sees the cascade's own write. A later processor failure rolls back both. A **workflow import** inside the processor carries no tx-token and succeeds. Both transaction-control parameters are rejected locally inside the scope. |
| `CascadeConcurrencyIT` | 2 | Regression test for §4.5: 16 concurrent 3-level nested cascades all complete within 60 s, and all 64 entities commit. A latch at the leaves holds all 64 callouts at once, which a bounded pool of 20 cannot serve. |
| `AuthContextPlumbingIT` | 2 | A processor sees `authtype=user`, `authid=mock-user-001` and roles `ROLE_ADMIN`, `ROLE_M2M`. `requireRole("ROLE_ADMIN")` is true; `requireRole("ADMIN")` is false. |

### 6.3 Unit tests

- The `common/` and `com/example/` unit tests cover the aligned framework. There are no OBO
  or event-user tests.

**PR 1 tests:**
- the proto option injection and its conflict check, and that every RPC uses the library's
  `CloudEvent`;
- the `allOf` rewrite, typeless-object injection and `orderBy` naming, including their
  failure modes;
- each OpenAPI patch, including its "fixed upstream" failure;
- the DTO round-trip (§3.3.3);
- the processor `type` default;
- response serialisation (fresh `id`, `requestId`, `entityId`, no `"error": null`);
- date-time precision;
- `CYODA_VERSION` parsing and version comparison;
- the harness environment and startup-failure cleanup;
- `auth-mode=none` startup without client credentials;
- the mappers' auto-configuration: protocol parsing under `fail-on-unknown-properties`, `SNAKE_CASE` reaching entities but not protocol DTOs, and the startup failure for several non-primary `ObjectMapper`s.

**PR 2 tests:**
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
  - the retry rules on both doors (§4.2), including invalidation of only the rejected token;
  - unwrapping at the service boundary (`Futures`).
- **REST origin rule** and request timeouts.
- **Deadlines:** unary, and streaming joined and unjoined; on an in-process channel, an
  unjoined streaming call fails after about the bound with no server or after the server
  goes away, succeeds with it up, and a slow stream longer than the bound completes. A call
  made right after a `TRANSIENT_FAILURE` (the server was down, now back) succeeds within the
  bound instead of sitting out gRPC's reconnect backoff.
- **Reads inside a callout:** the direct-search limits and scoped streams.
- **Auth context:**
  - `authclaims` parsing: comma form, empty, JSON, garbage;
  - `requireRole` for `SERVICE`, `SYSTEM`, an empty context and empty roles.
- **Callout scope:** `wrap`, `unjoined`, and behaviour once ended.
- **Executors:** one virtual thread per processor task in `virtual` mode.
- **Maintainer scripts:** a value-taking flag given without its value fails with a message;
  `install-cyoda.sh` without Go says Go is needed and names `CYODA_BIN`.
- **Binary lookup and install:** `CyodaBinary`'s lookup order, including `.cyoda/bin`
  auto-detection in a temp project; `CyodaInstallDecision` (`buildSrc`): an explicit binary
  skips, a matching binary is kept whether at `.cyoda/bin` or, failing that, on `PATH`, a
  missing or mismatching one is installed unless `-Dcyoda.allowVersionMismatch=true` keeps
  it, and Windows refuses with the way around it.

## 7. Build and CI

### 7.1 Source sets and tasks

- **`testFixtures`** (`java-test-fixtures` plugin). The plain `jar` is enabled, because the
  test-fixtures wiring resolves it; the runnable jar is `build/libs/app.jar`.
- **`integrationTest`:**
  - its classpath includes `sourceSets.main.output` and `sourceSets.test.output`;
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
- **Dependency verification:** `gradle/verification-metadata.xml` holds the SHA-256 of every
  artifact the build and CI resolve (plugins, `buildSrc`, codegen tools, test, `testFixtures`
  and `integrationTest` classpaths, the OTel agent, and protoc/gRPC-plugin binaries for every
  platform via `resolveProtocNatives`). `buildLogicTest`'s nested `buildSrc` build uses
  `buildSrc/gradle/verification-metadata.xml`, written by the same
  `--write-verification-metadata` run (CONTRIBUTING.md has the command).

### 7.2 No Cucumber

The build has no Cucumber dependencies, no `se.thinkcode.cucumber-runner` plugin and no
`cucumberTest` task. There is no `GherkinE2eTest`, `src/test/java/e2e/`,
`src/test/resources/features/` or `application-cucumber.yaml`.

### 7.3 Codegen tasks

- `prepareCyodaProto`, `prepareEventSchemas` and `prepareOpenApi` run before generation.
- `protoc` and `protoc-gen-grpc-java` are aligned with the runtime libraries (§3.3.1).

### 7.4 Installing cyoda

`scripts/install-cyoda.sh [--from-src [<git-ref>] | --src-dir <checkout>] [--dest <dir>] [--archive <file>]`
reads `CYODA_VERSION` and installs the binary to `<dest>/cyoda`. The default `<dest>` is
`.cyoda/bin` in the project root: it is git-ignored, survives `./gradlew clean`, and the
harness finds it there with no configuration (§6.1). A binary installed elsewhere is named
with `CYODA_BIN` or `-Dcyoda.bin`.

- **With no mode flag:** a released pin is downloaded; a `-dev` pin is built from GitHub at
  the pinned commit, as with `--from-src`.
- **Released version:** it downloads `cyoda_<version>_<os>_<arch>.tar.gz` for this OS and
  architecture from `github.com/Cyoda/cyoda-go/releases/download/v<version>` (or takes the
  local `--archive`) and verifies it against the release's `SHA256SUMS` and against the
  SHA-256 committed in `CYODA_SHA256SUMS` (with `sha256sum`, or `shasum` when that is
  missing). A missing `CYODA_SHA256SUMS`, or no line for this platform, fails the install.
  A `-dev` pin is a source build and needs no checksum file.
  - It does not use the project's `install.sh`, which runs `cyoda init` and writes the
    user's `~/.config/cyoda/cyoda.env`.
- **`--from-src`:** it clones `https://github.com/Cyoda/cyoda-go` at `<git-ref>` (default:
  the pinned commit) and builds it.
- **`--src-dir`:** it builds an existing local checkout, whose `HEAD` must be the pinned
  commit.
- **A source build** runs
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
- **`helm` job:** `azure/setup-helm` (pinned) runs `helm lint` and `helm template` on `helm/`
  with `.github/helm/ci-values.yaml`, the minimal required values.
- **On failure,** `build/cyoda-logs/` is uploaded. Test results and reports for `test` and
  `integrationTest` are always uploaded. The standard jar artifact is `build/libs/app.jar`.

### 7.6 Container image and Helm chart

**Image (`Dockerfile`, `.dockerignore`).**
- The runtime stage runs as an unprivileged system user, uid/gid 10001 with no login shell,
  set as a numeric `USER` so Kubernetes `runAsNonRoot` can verify it. The app writes only
  to `/tmp`.
- `.dockerignore` keeps the build context to what `./gradlew bootJar` reads: no `.cyoda/`,
  `build/`, `.git/`, `.gradle/`, `docs/`, `helm/` or IDE and agent metadata. Because
  `bootJar` packages all of `src/main/resources`, it also excludes every
  `src/main/resources/application-*` file except `application-cyoda-local.yml`, and every
  `.env` file.

**Helm chart (`helm/`).**
- Environment variables are `APP_CONFIG_*` names, which Spring binds to `app.config.*`
  (`HelmValuesBindingTest` checks every one names a real `Config` property). The client
  secret comes from an existing Secret through `secretKeyRef` (`cyodaClientSecret.secretName`,
  required; `cyodaClientSecret.key`, default `client-secret`), never a plain value.
- The chart renders standalone: `global.registry.host` and `image.tag` are required, and
  every other `global.*` / `host.*` value has a default.
- Pod and container run non-root as uid/gid 10001, with a read-only root filesystem, an
  `emptyDir` `/tmp` bounded by `tmpSizeLimit` (default `512Mi`), no privilege escalation,
  all capabilities dropped and the `RuntimeDefault` seccomp profile.
- Ingress is off by default; enabled, it requires `host.name` and `ingress.tls`.
- Image pulls use an existing Secret (`global.imagePullSecret.existingSecretName`). Building
  a pull secret from plaintext `global.registry.username` / `password` is off by default.
- The probes call `/api/actuator/health/liveness` and `/readiness` (under the `/api`
  context path), with `management.endpoint.health.probes.enabled: true`. An app whose
  `SecurityFilterChain` requires authentication permits `/actuator/health/**`.

## 8. Verified build and runtime facts

1. **Generated OpenAPI DTOs** compile with the §3.3.3 configuration, and one schema mapping
   suffices. They pass the round-trip test.
2. **jsonschema2pojo output** matches framework usage after the three §3.3.2 transforms.
3. **Protobuf:** the import-only setup compiles, and the runtime descriptor resolves all
   six RPCs to the library's `CloudEvent` (`CyodaProtoContractTest`).
4. **Pinning:** no `synchronized` block holds a lock across a blocking Cyoda call in the
   framework's call paths (§4.5).
5. **Processor `type` default:** a Jackson `DeserializationProblemHandler` on the protocol
   mapper, with no openapi-generator template fork (§3.3.3).

## 9. Findings and obligations outside this template

### 9.1 cyoda-go findings

This project never changes cyoda-go. Each finding is an issue in `Cyoda/cyoda-go` with
milestone `v0.9.0`.

| # | Finding | Kind | Issue |
|---|---|---|---|
| 1 | M2M tokens carry roles in `scopes`, not the documented `user_roles`. The claim decides whether the principal is a user or a service, and that rule is undocumented. `caas_org_id` is called a "string UUID". | docs | [#621](https://github.com/Cyoda/cyoda-go/issues/621) |
| 2 | The bootstrap client secret is never generated, but its field comment says it is. | docs (minor) | [#622](https://github.com/Cyoda/cyoda-go/issues/622) |
| 3 | An unrecognised argument starts the server. | bug | [#623](https://github.com/Cyoda/cyoda-go/issues/623) |
| 4 | Discriminators have no `mapping`. This covers the named schemas and the inline condition unions (see the issue's comment). `StateMachineEventDto` has no wire values at all. | bug | [#625](https://github.com/Cyoda/cyoda-go/issues/625) |
| 5 | `EntityModelSetUniqueKeys*` events have no JSON schema. | bug | [#626](https://github.com/Cyoda/cyoda-go/issues/626) |
| 6 | `ArrayConditionDto` declares `operatorType` + `value`; the server parses `values`. | bug | [#627](https://github.com/Cyoda/cyoda-go/issues/627) |
| 7 | `ExternalizedFunctionDto.criterion` is typed `QueryConditionDto`, so its pre-check cannot be expressed, and it is never evaluated. | bug | [#628](https://github.com/Cyoda/cyoda-go/issues/628) |
| 8 | `TransitionDefinitionDto.manual` is required in the OpenAPI spec but optional (default `false`) on import. | bug | [#629](https://github.com/Cyoda/cyoda-go/issues/629) |

**Impact on the template:**
- #1, #3 and #629: none.
- #4 and #6: patched with "fail once fixed" (§3.3.3).
- #5: unique keys wait for sub-project 2.
- #628: the `function_criterion` snippet has no pre-check criterion (§4.7).

### 9.2 Cyoda Cloud obligations (recorded, not worked around)

The template implements cyoda-go's contract. These are the known places where Cloud must
follow cyoda-go before the template behaves the same against Cloud. They are taken from
cyoda-go's `docs/cloud-parity/`.

- **Joined callbacks.** Cloud's callbacks are separate transactions
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
