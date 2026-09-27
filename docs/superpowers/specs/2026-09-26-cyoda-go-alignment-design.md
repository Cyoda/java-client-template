# Align the Java client template with cyoda-go v0.9.0 — sub-project 1a design

**Date:** 2026-09-26 (revised after independent review)
**Status:** pending written-spec review
**Branch:** `feat/cyoda-go-v0.9-alignment`
**Contract source:** cyoda-go `release/v0.9.0` (tip at implementation start; `df6ad2c7` when this
was written; latest tag `v0.8.4`), `cyoda help`, docs.cyoda.net

## 1. Intent

cyoda-go is the digital twin of Cyoda Cloud: it defines the API and integration contract,
and Cloud mirrors it. The Java client template predates cyoda-go and is stale against it.

**Outcome of the whole alignment:** the template works against a local cyoda-go and against
Cyoda Cloud by configuration alone, its contract artifacts are cyoda-go's, and integration
suites that start cyoda-go themselves prove it end to end.

**Success criteria for 1a**

1. `./gradlew check` builds, runs unit tests, and runs the tier-1 integration suite against
   an in-memory cyoda-go that the suite starts itself. No Docker, no pre-running services.
2. The same runs in CI.
3. Against a local `cyoda` started with `cyoda init && cyoda`,
   `./gradlew runApp --args='--spring.profiles.active=local'` starts, joins as a compute
   member (greet received, logged), and serves `/api/swagger-ui/index.html`. No further
   configuration.

### 1.1 Decisions taken (with the user)

| Topic | Decision |
|---|---|
| Scope | Full alignment in sub-projects (§2). This spec is **1a**. |
| Launching cyoda-go in tests | Subprocess binary on free ports, as cyoda-go's own e2e suite does. No container for cyoda itself. |
| Auth modes | The template supports cyoda-go `mock` (no token) and `jwt`. |
| Test tiers | Tier 1 (1a): everything that does not involve identity, on cyoda-go mock IAM + memory storage. Tier 2 (1b): everything that relies on authentication, on cyoda-go JWT + postgres, with Zitadel. In-memory wherever it makes sense. |
| Test style | Replace Cucumber with plain JUnit 5 integration tests. |
| Contract artifacts | Vendor cyoda-go's files verbatim with a sync script; build-time transforms produce what the Java generators need. |
| Credential model | **Compute → Cyoda is always M2M.** The originating user reaches compute as data (`authtype`/`authid`/`authclaims`), and compute's callbacks join the originating transaction with the tx-token, so cyoda attributes them to the originating user. **BFF → Cyoda forwards the user's own IdP token** (Cyoda federated OIDC provider); M2M only for work with no acting user. OBO / trusted-key subject-token minting is removed. |
| IdP | An external OIDC IdP is presumed; Zitadel is the reference for the target operating model. |
| `authclaims` format | cyoda-go's comma-separated form is canonical. The JSON-object form `{"roles":[…]}` that Cyoda Cloud may still send is also accepted (§4.4). |
| Tier-2 persona tokens (1b) | Zitadel machine-user personas with client credentials, as in ctcc, plus a spike on whether they can carry roles (§10). |

Rationale for the credential model (from the ctcc-management review: `docs/architecture.md`
§2.2, §4, and `docs/reference/cyoda-callout-security-model.md`):
- the IdP stays the only authority for identity and roles;
- no component holds a credential that can mint a token for arbitrary users;
- audits come out right on every path:
  - direct calls → attributed to the forwarded user;
  - joined cascades → attributed to the transaction's origin
    (cyoda-go `docs/cloud-parity/authcontext-attribution.md`);
  - scheduled fires → attributed to the arming principal.

## 2. Roadmap

| # | Sub-project | Contents | Depends on |
|---|---|---|---|
| **1a** | **Contract, runtime alignment, tier-1 harness** (this spec) | §3–§8 | — |
| 1b | BFF identity and tier 2 | Inbound resource-server security, Zitadel assembly, tier-2 suite and CI. Starts with its own brainstorm; known inputs in §10. | 1a |
| 2 | New v0.9.0 capabilities | Entity merge-PATCH; function criteria and scheduled-transition functions; unique keys (blocked on cyoda-go #626); grouped stats. Each with tests. | 1a (1b for identity-dependent tests) |
| 3 | Documentation overhaul | README, `llms.txt`, `llms-full.txt`, `usage-rules.md`, `AI_TESTING_GUIDE.md`, `.augment/rules/*`, `CONTRIBUTING.md` point at `cyoda help` / docs.cyoda.net; local-dev and Zitadel guides | 1a, 1b, 2 |

1a ships on its own. Credential forwarding is implemented and unit-tested in 1a (§4.2), but
no inbound authentication is configured by default. So until 1b, BFF calls go out as M2M,
which is what the template does today: OBO was off by default.

## 3. Contract layer

### 3.1 Layout

cyoda-go's files are vendored **unmodified** under one root. The current `proto/`,
`schema/` and `api/` resource directories are deleted.

```
src/main/resources/cyoda/
  CYODA_VERSION                        # the single pin (format below)
  proto/cyoda/cyoda-cloud-api.proto
  proto/cloudevents/cloudevents.proto
  schema/{common,entity,model,processing,search}/*.json   # event schemas (JSON only)
  openapi/openapi.yaml                 # cyoda-go api/openapi.yaml (OpenAPI 3.1)
```

`CYODA_VERSION`: line 1 is the version (`0.9.0`, or `0.9.0-dev` before the tag); line 2 is
`commit=<full sha>`.

### 3.2 Sync

`scripts/sync-cyoda-contract.sh --from-src <cyoda-go checkout>`:
- copies `proto/cyoda/…`, `proto/cloudevents/…`, `docs/cyoda/schema/**/*.json` (JSON files
  only; the `.go` files in that tree are excluded) and `api/openapi.yaml`;
- writes `CYODA_VERSION` from `git describe` and `git rev-parse HEAD`;
- is idempotent: the resulting `git diff` is the contract change.

Binary mode (`cyoda help … json/proto/yaml`) is not supported: the binary re-serialises
the files (sorted keys, different trailing newlines), so switching modes would show a
spurious contract change. It can be added after the v0.9.0 tag if a normalising step comes
with it.

### 3.3 Code generation

Generated sources come from **build-dir copies** of the vendored files. Each transform is
a small Gradle task that fails the build loudly when its input has an unexpected shape, so
contract drift cannot pass silently.

**3.3.1 Protobuf** (`prepareCyodaProto`)
- cyoda-go's protos declare only `go_package`. The copy inserts, after each `package` line:
  - in `cyoda/cyoda-cloud-api.proto`: `option java_multiple_files = true;`
  - in `cloudevents/cloudevents.proto`: `option java_multiple_files = true;` and
    `option java_package = "io.cloudevents.v1.proto";`
  - The build fails if either file already declares a conflicting Java option.
- Java code is generated **only** for `cyoda-cloud-api.proto`. `cloudevents.proto` is on
  the import path only, and the `io.cloudevents.v1.proto.CloudEvent` class comes from the
  `cloudevents-protobuf` library that `ProtobufFormat` uses. Today the template generates a
  duplicate of that class, and classpath order decides which one loads.
- Output package: `org.cyoda.cloud.api.grpc`, as today.
- `protoc` is aligned with the `protobuf-java` runtime (4.31.x). Today the build pairs
  protoc 3.25.1 with protobuf-java 4.31.1.

**3.3.2 Event schemas** (`prepareEventSchemas`)

A JSON transform (parse, edit, write; not text substitution) over the copied tree:
- Rewrites `"allOf": [ { "$ref": "<p>/BaseEvent.json" } ]` to
  `"extends": { "$ref": "<p>/BaseEvent.json" }`, so that jsonschema2pojo keeps the Java
  inheritance the framework relies on. All 47 `allOf`s have exactly this form; any other
  `allOf` fails the build, naming the file.
- Leaves `existingJavaType` untouched. Every property whose `"type": "any"` cyoda-go
  dropped carries one, so the generated Java types do not change.
- jsonschema2pojo generates into `org.cyoda.cloud.api.event.*`, as today.
- Inline `orderBy` objects added upstream may produce duplicate classes. They are either
  given a `javaType`, or excluded from the generated types the framework imports, whichever
  compiles cleanly. This is checked in the first build (§8).

**3.3.3 OpenAPI** (`prepareOpenApi` + one generator task)
- **Package:** DTOs are generated from `openapi/openapi.yaml` into **one** package,
  `org.cyoda.cloud.api.common.model`. cyoda-go's own event schemas hard-code
  `existingJavaType: org.cyoda.cloud.api.common.model.GroupConditionDto`
  (`docs/cyoda/schema/search/EntitySearchRequest.json:18`,
  `EntitySnapshotSearchRequest.json:18`), so this package is part of the contract.
- **Names:** no `modelNameSuffix`. 87 of the 129 schemas already end in `Dto`; the other
  42 keep their spec names, e.g. `EntityTransactionResponse`, `EntityMetadata`,
  `EntityChangeMeta`.
  - Some of those 42 share a simple name with a jsonschema2pojo event class. They are in
    different packages, so framework code that uses both writes one fully qualified.
- **Discriminator mappings:** four discriminators in the spec have no `mapping` (cyoda-go
  #625): `QueryConditionDto`, `AbstractConditionDto`, `AuditEventDto`,
  `StateMachineEventDto`. Without one, generated code keys subtypes by schema name
  (`"SimpleConditionDto"`) instead of the wire values (`simple`, `group`, `lifecycle`,
  `array`, `function`).
  - `prepareOpenApi` adds the missing mappings in the build copy.
  - It skips any discriminator that already has a mapping, so it becomes a no-op once
    #625 is fixed upstream.
- **Inline `oneOf`s** that openapi-generator turns into uncompilable wrapper types (e.g. the
  processors item of `TransitionDefinitionDto`, the `criterion` fields,
  `GroupConditionDto`'s conditions) are pinned with targeted `schemaMappings` /
  `importMappings` to the real DTOs (`ProcessorDefinitionDto`, `QueryConditionDto`, …).
  The exact list is settled by making the generated code compile (§8).
- `object` / `any` still map to `JsonNode`.
- The five split `openapi-*.yml` files and the `@JsonAlias` post-processing are removed.
- **Framework code** (`EntityService`, `CyodaRepository`, `CyodaInit`, `WorkflowServiceImpl`,
  validators) is adapted to the new names. `GroupOperatorDto` and `OperatorTypeDto` are no
  longer schemas: they are inline enums on their parent DTOs now.
- **Proof:** a unit test deserialises cyoda-go's exported workflow JSON and a condition of
  each type into the generated DTOs, re-serialises them, and asserts the JSON is equal.

**New events after the sync**, used by sub-project 2: `EntityPatchRequest`,
`EntityPatchPayload`, `PatchFormat`, `EntityFunctionCalculationRequest/Response`.
`EntityModelSetUniqueKeys*` has no schema upstream (cyoda-go #626), so nothing is generated
for it.

### 3.4 Version pin

- The build reads `CYODA_VERSION` into a `cyodaVersion` property and passes it to tests as
  a system property.
- The harness (§6.1) runs the binary's `--version` and compares:
  - a released version: `major.minor.patch` must be equal;
  - a `-dev` pin: the binary's `commit` must equal the pinned commit.
- On mismatch the harness fails with both values. `-Dcyoda.allowVersionMismatch=true`
  downgrades that to a warning.
- A binary reporting `dev (commit unknown)` fails, with a hint to build it through
  `scripts/install-cyoda.sh` (§7.5), which stamps the version and commit.

## 4. Runtime alignment

### 4.1 Configuration

`Config.CyodaLight` and `CyodaLightConfigCustomizer` (and its `spring.factories` entry) are
removed; "cyoda-light" was cyoda-go's earlier name.

**`app.config` after 1a:**

| Property | Meaning | Default (`application.yml`, Cloud-shaped) |
|---|---|---|
| `cyoda-host` | Cloud host; the two below are derived from it | — (required for Cloud) |
| `cyoda-api-url` | REST base, including the context path | `https://${cyoda-host}/api` |
| `grpc-address` / `grpc-server-port` | gRPC endpoint | `grpc-${cyoda-host}` / `443` |
| `grpc-tls` | TLS on the gRPC channel (replaces `skip-ssl`) | `true` |
| `ssl-trust-all` / `ssl-trusted-hosts` | Unchanged; development self-signed certificates | `false` / empty |
| `auth-mode` | `client-credentials` \| `none` | `client-credentials` |
| `cyoda-client-id` / `cyoda-client-secret` | M2M credentials | — |
| `execution-mode` | `virtual` \| `platform` threads for processor/criteria work (§4.5) | `virtual` |

**New `application-local.yml`:**
- `cyoda-api-url: http://localhost:8080/api`
- `grpc-address: localhost`, `grpc-server-port: 9090`, `grpc-tls: false`
- `auth-mode: none`

These are cyoda-go's defaults after `cyoda init`.

**Migration** (recorded in `SYNCING_WITH_JAVA_TEMPLATE.md`, §4.8):

| Old key | New |
|---|---|
| `app.config.cyoda-light.*` | removed; set `cyoda-api-url`, `grpc-*` directly, or use the `local` profile |
| `app.config.skip-ssl` | `app.config.grpc-tls` (inverted) |
| `execution.mode` (read by `@ConditionalOnProperty`) and `app.config.execution-mode` | one key, `app.config.execution-mode` |
| top-level `cyoda.api.url` | removed (unused) |
| `app.obo.*`, `app.event.auth-context.*` | removed with OBO (§4.6) |

### 4.2 Outbound credentials: one decision point

**`CyodaCallContext`** is an immutable value that states the credential and the transaction
for **one** call to Cyoda. Its credential is one of three:

| Credential | Meaning |
|---|---|
| `NONE` | no `Authorization` header |
| `M2M` | the M2M service-account token |
| `FORWARD(token)` | the given user token, sent unchanged |

It also carries an optional tx-token.

**`CyodaCallContexts.current()`** is the **only** place that builds one. It is called once,
on entry to each public `EntityService` / `WorkflowService` / `EdgeMessageService` method
and in `CyodaInit`, and applies these rules in order:

1. `auth-mode=none` → `NONE`. If a callout scope is active, its tx-token is still set.
2. A **callout scope** is active (§4.4) → `M2M` plus the scope's tx-token. This holds
   whatever the `SecurityContext` contains: compute never forwards a user credential.
3. The `SecurityContext` holds an authenticated `JwtAuthenticationToken`:
   - a non-blank token → `FORWARD(token)`;
   - a blank token → `CyodaCredentialException`.
4. The `SecurityContext` holds any **other** authenticated, non-anonymous principal →
   `CyodaCredentialException`. The framework cannot forward it, and it never silently
   downgrades to M2M.
5. Otherwise → `M2M`.

**The context is passed explicitly** through every stage of an operation, including
multi-step ones such as snapshot search: create, poll status, then fetch pages. It is never
re-derived from thread-locals on a later stage. That removes today's leak, where a paged
search creates its snapshot as the caller but polls and pages as M2M.

**Transport:**
- **gRPC:** the stub interceptor reads the `CyodaCallContext` from `CallOptions` and sets
  `authorization` and, where allowed (§4.4), `tx-token`.
  - A failure to obtain a credential **cancels the call** with `UNAUTHENTICATED`. Today's
    interceptor proceeds without a header.
  - The member stream (`startStreaming`) is always opened with an explicit `M2M` context
    and never carries a tx-token.
- **REST:** `HttpUtils` no longer takes a caller-supplied token string. It takes a
  `CyodaCallContext` and resolves the header itself, so callers such as
  `WorkflowServiceImpl`, `EdgeMessageServiceImpl` and `CyodaInit` can no longer bypass
  the decision.

**The M2M token:**
- It comes from client credentials against `{cyoda-api-url}/oauth/token` with HTTP Basic,
  cached until 60 s before expiry.
- **A `401` outside a callout scope:** the M2M token is invalidated, fetched again, and the
  call retried once.
- **A `401` inside a callout scope:** it cannot be told apart from a rejected transaction
  pass (cyoda answers both with `UNAUTHORIZED`), so it is **not** retried. The M2M token
  is invalidated, and the call fails with `CyodaCalloutEndedException` (§4.4).
- No `scope` parameter is sent; cyoda ignores it.

Credential forwarding (rule 3) is complete and unit-tested in 1a. What makes it matter in
practice (inbound bearer-token authentication) arrives in 1b.

### 4.3 Inbound (BFF) security

Unchanged in 1a: `SecurityConfig` stays the permit-all default auto-configuration.
Inbound resource-server configuration is 1b (§10).

### 4.4 Compute node

**Credential.** Compute always uses M2M (§4.2 rule 2).
- Opening a callout scope clears the `SecurityContext` for the callout's thread and asserts
  that it is empty.
- Removed: `EventAuthContextHandler`, `DefaultEventUserResolver`, `EventUserResolver`,
  `EventUserIdentity`, `EventUserResolutionException`, and the synthetic-JWT construction.

**Auth context as data.** `CyodaEventContext` gains `authContext()` as a **default** method
(the interface is public), returning `CloudEventAuthContext(type, id, roles)`:
- **`type`** ∈ {`USER`, `SERVICE`, `SYSTEM`}. An absent, unknown or retired value (e.g.
  `service_account`) yields an **empty** context.
- **`roles`** is parsed from `authclaims`:
  - Canonical cyoda-go form: comma-separated, e.g. `ROLE_ADMIN,ROLE_M2M`. Split on `,`,
    trim, drop blanks.
  - Accepted legacy form: a value whose first non-blank character is `{` is read as a JSON
    object, and its `roles` array is used. Cyoda Cloud may still send this form.
  - Anything unparseable yields **no roles** (fail closed) and one `WARN` per distinct
    malformed shape.
  - Unit tests use the literal wire strings `"ROLE_ADMIN,ROLE_M2M"`,
    `"{\"legalEntityId\":\"org-1\",\"roles\":[\"USER\"]}"`, `""` and garbage.
- **`requireRole(String role)`** mirrors cyoda-go's `authctx.Require`: it is `true` only
  when `type` is `USER` or `SERVICE` and the role appears **exactly** (case-sensitive; no
  `ROLE_` prefix is added or removed) among the roles.
- Javadoc states the trust basis: `authclaims` can be relied on only over a
  server-verified TLS channel (`authcontext-attribution.md`, "Trust basis"). With
  `grpc-tls=false` it is forgeable.

**Transaction token.** `CyodaEventContext` gains `txToken()` as a default method, returning
the request's `cyodatxtoken` attribute. A **`CalloutScope`** is opened around each
processor or criterion invocation, holding the tx-token and an **open** flag. The flag is
cleared when the callout's answer is sent.
- **Attached to** `EntityService` calls made while a scope is open:
  - gRPC: `entityManage`, `entityManageCollection`, `entitySearch`,
    `entitySearchCollection`, as metadata `tx-token`;
  - REST: entity and search endpoints, as `X-Tx-Token`.
- **Never attached to** model or workflow administration (`CyodaInit`, `WorkflowService`,
  `entityModelManage`). cyoda refuses those with `400 MODEL_ADMIN_IN_JOINED_TRANSACTION`.
- **Timeout and window parameters** are refused on joined requests, so within a scope the
  framework **rejects** `transactionTimeoutMs`, `timeoutMillis` and `transactionWindow`
  before sending (an `IllegalArgumentException` naming the parameter). The
  `EntityService.save(entities, window, timeoutMs)` overloads are affected.
- **Closed scope:** a call made after the scope's open flag is cleared throws
  `CyodaCalloutEndedException` locally, before any network call.
- **App-spawned threads** lose the scope. `CalloutScope.wrap(Runnable/Callable/Supplier)`
  carries it to threads the application starts itself. Without it, their calls go out
  unjoined as M2M; the Javadoc says so.
- **Explicit detach:** `CalloutScope.unjoined(Supplier)` runs a block with M2M and no
  tx-token. This is cyoda's documented remedy for
  `409 COMMIT_IN_JOINED_TRANSACTION` (writes aimed at a `COMMIT_BEFORE_DISPATCH` processor's
  transaction).
- **Token lifetime:** a tx-token lives only for the callout try's answer limit plus
  `CYODA_CALLOUT_PASS_ALLOWANCE` (default 30 s). Work that outlives the callout cannot join.

**Joined-call refusals.** Over gRPC these arrive as a `success:false` response envelope
carrying `error.code`, not as a gRPC status. Over REST they arrive as HTTP status plus
problem-detail code. Both are mapped to the same exceptions:

| cyoda answer | Exception | Retry? |
|---|---|---|
| `410 CALLOUT_SUPERSEDED`, `410 TRANSACTION_EXPIRED`, `404 TRANSACTION_NOT_FOUND`, `401` (invalid tx pass), `403` (tenant mismatch) | `CyodaCalloutEndedException` (stop working on this request) | no |
| `503 TOO_MANY_JOINED_REQUESTS` | `CyodaRetryableException` | yes, bounded backoff |
| `413 JOINED_RESPONSE_TOO_LARGE` | `CyodaJoinedResponseTooLargeException` (page the read) | no |
| `409 COMMIT_IN_JOINED_TRANSACTION` | `CyodaCommitInJoinedTransactionException` (use `CalloutScope.unjoined`) | no |
| `400 MODEL_ADMIN_IN_JOINED_TRANSACTION` | cannot occur (never attached); `IllegalStateException` if it does | no |

**Attribution notes** (Javadoc on `CalloutScope`):
- Joined writes are attributed to the transaction's origin.
- Under `COMMIT_BEFORE_DISPATCH` with `startNewTxOnDispatch=false`, there is no
  transaction to join. The callout carries no tx-token, callbacks are ordinary M2M
  requests, and they are attributed to the M2M account (`cyoda help workflows`,
  "Attribution handover").
- Whether an async snapshot search sees a joined transaction's uncommitted writes is
  unverified; it is checked in §8.

**Modifying the entity under processing.** The rule "a processor must not modify the
entity under processing via `EntityService`" stays. cyoda v0.9.0 does keep a joined write
to that entity when the processor answers with **no** payload, but the framework always
answers with a payload, which would overwrite that write.

**Protocol fixes:**

| Item | Change |
|---|---|
| Join | `joinedLegalEntityId` is added to the join payload when the M2M token has a `caas_org_id` claim (read without verification; it is our own token). It is omitted otherwise, which cyoda accepts by falling back to the token's tenant (`internal/grpc/streaming.go:56-66`). The field is not in `CalculationMemberJoinEvent.json` (only in the greet schema), and jsonschema2pojo runs with `includeAdditionalProperties=false`, so the join payload is serialised to a Jackson `ObjectNode` and the field added there. |
| Responses | Every processor/criteria response carries a fresh `id`, the `requestId` and `entityId`. Failure sends `success: false` explicitly. `error` is omitted rather than serialised as `null` (serialisation unit test). |
| Criteria | `matches` is always set, on success and failure paths. |
| Keep-alive | The member answers every server keep-alive. `keep-alive-warning-threshold` (ms) changes from 60000 to 20000. It warns when the member has not heard from the **server** for 20 s: an early sign of a broken stream, below cyoda's 30 s eviction for server-side silence. |
| Stream writes | Already single-writer (`ConnectionManager.sendEvent` is `synchronized`); no change. |

### 4.5 Threading

Today `CyodaRepository` runs blocking gRPC calls on the common ForkJoinPool via
`supplyAsync`, and `EntityServiceImpl` blocks on `.join()`. Two problems follow:
- **Deadlock.** A transition started from a processor occupies a pool worker until cyoda
  answers. cyoda answers only after the nested processor's callback completes, and that
  callback needs another worker. Nested or concurrent cascades hang once they exceed the
  pool's parallelism (cores − 1).
- **Lost context** across `thenComposeAsync` and `delayedExecutor` stages (§4.2).

1a changes the threading as follows:
- Blocking stub calls run on a **dedicated virtual-thread-per-task executor**, never on the
  common pool. Stages that poll or delay use the same executor. The `CyodaCallContext`
  travels as an explicit argument.
- Processor and criteria work runs on virtual threads by default
  (`app.config.execution-mode: virtual`), so each cascade level holds no scarce pool
  thread. `platform` remains available, with a documented requirement: at least one free
  processor thread per cascade level.
- Virtual-thread pinning (a `synchronized` block around a blocking call) is audited in the
  framework's call paths (§8).

### 4.6 Removal of OBO

**Deleted:** `OboAwareAuthentication`, `OboTokenService`, `OboKeyRegistrationService`,
`SubjectTokenSigner`, `AesGcmEncryption`, `OboSigningKey`, `OboProperties`,
`OboTokenException`, `AuthContextMode`, `EventAuthContextProperties`,
`EventAuthContextScope`, `EventAuthContextMissingException`, `AuthClaimsParser`
(replaced, §4.4), the OBO bootstrap resources, and all their tests.

`Authentication` becomes the M2M token source used by §4.2.

### 4.7 Workflows and REST paths

- **Example and test workflows:**
  - `"version": "1.5"`;
  - fields cyoda-go does not know are removed;
  - `retryPolicy` is `NONE`, `FIXED` or absent;
  - `responseTimeoutMs` ≤ 60000.
- **Condition `jsonPath`s** carry the required `$.` prefix, in the examples and in any
  framework code that builds conditions.
- **Reference snippets.** `src/test/resources/example/config/workflow/criterion_examples.json`
  and `processor_examples.json` are snippets, not importable workflows. They move to
  `…/example/config/snippets/` and are validated as snippets (each fragment deserialises
  into its DTO), not imported.
- **REST paths.** Every REST call in `common/` has been checked against `openapi.yaml` for
  path and verb; the current ones all exist (model `lock` is `PUT`).

### 4.8 Framework API and downstream impact

Public API changes:
- OpenAPI DTO renames and the single package (§3.3.3);
- `CyodaEventContext.authContext()` / `txToken()` (default methods);
- `CalloutScope`, `CyodaCallContext`, and the new exceptions (§4.4);
- `HttpUtils` signatures (§4.2);
- the config keys (§4.1).

`SYNCING_WITH_JAVA_TEMPLATE.md` gains a "breaking changes in the v0.9.0 alignment" table
covering all of these, plus the workflow version, the `authtype` values, and the new
contract directory layout.

## 5. cyoda-go facts relied on (verified)

These were checked against cyoda-go `release/v0.9.0` code, `cyoda help` and docs.cyoda.net
during design and review:
- tx-token names: `cyodatxtoken`, `tx-token`, `X-Tx-Token`.
- Tx-tokens are issued under mock IAM too (`app/app.go:153-171`).
- Joined callbacks are served one at a time per transaction, and cyoda releases its gate
  across dispatch, so the server side does not deadlock.
- `/oauth/token` exists only in JWT mode, uses HTTP Basic, and ignores `scope`.
- `joinedLegalEntityId` is optional.
- `authtype` ∈ {`user`, `service`, `system`}.
- `authclaims` is comma-separated and absent when there are no roles.
- The mock principal is fixed per process:
  - kind from `CYODA_IAM_MOCK_KIND`;
  - roles from `CYODA_IAM_MOCK_ROLES` (default `ROLE_ADMIN,ROLE_M2M`);
  - tenant `mock-tenant`;
  - user `mock-user-001`.
- Workflow schema 1.1–1.5 is accepted, unknown fields are rejected, and `$.` is required
  on paths.
- A manual transition refused by its criterion answers `400 WORKFLOW_FAILED` with detail
  `transition "<name>" criterion not matched: <reason>`
  (`docs/cloud-parity/criterion-stoppage-reason.md` §2).

## 6. Tier-1 test harness

### 6.1 Components (`src/testFixtures/java/com/java_template/testing/cyoda/`)

These are framework-owned (listed in the sync guide) and consumable by `test`,
`integrationTest`, 1b's tier 2, and downstream apps.

**`CyodaBinary`** finds the binary from `-Dcyoda.bin`, then `$CYODA_BIN`, then `cyoda` on
`PATH`, and enforces the pin (§3.4). It runs only `--version`: an unrecognised argument
would start a server (cyoda-go #623).

**`CyodaServer`** manages one cyoda-go subprocess, started with no arguments:
- **Environment built from scratch.** Only `PATH` is inherited, plus:
  - `HOME` and `XDG_CONFIG_HOME` pointing at a fresh temp directory, which is also the
    working directory. This keeps `~/.config/cyoda/cyoda.env` and `./.env` out.
    `/etc/cyoda/cyoda.env` can still be read, but explicit variables win, and every
    relevant variable is set;
  - no `CYODA_PROFILES`;
  - `CYODA_HTTP_PORT`, `CYODA_GRPC_PORT`, `CYODA_ADMIN_PORT` on three free ports;
  - `CYODA_CONTEXT_PATH=/api`;
  - `CYODA_STORAGE_BACKEND=memory`, `CYODA_IAM_MODE=mock`;
  - `CYODA_SUPPRESS_BANNER=true`, `CYODA_ERROR_RESPONSE_MODE=verbose`,
    `CYODA_LOG_LEVEL=info`;
  - `CYODA_SCHEDULER_SCAN_INTERVAL=50ms`.
- **Defaults kept deliberately:** keep-alive (10 s / 30 s) and `CYODA_DISPATCH_WAIT_TIMEOUT`
  (5 s). With short values, one GC pause or CI stall would evict the member and fail
  callouts.
- **Profiles:** a `Profile` value object carries overrides, e.g. `CYODA_IAM_MOCK_KIND` or
  keep-alive values. Servers are cached per distinct profile.
- **Readiness:** poll `GET /api/health` until `200`, failing fast if the process exits.
  Limit 30 s.
- **Logs:** stdout and stderr go to `build/cyoda-logs/<profile>-<timestamp>.log`. The last
  50 lines are attached to any startup or test failure.
- **Shutdown:** a JUnit root-store `CloseableResource` stops the server after Spring
  contexts close: SIGTERM, 5 s grace, then kill. A JVM shutdown hook is only a backstop.

**`@CyodaIntegrationTest`** is a meta-annotation: `@SpringBootTest` + JUnit extension +
`ApplicationContextInitializer`. The initializer injects:
- `app.config.cyoda-api-url`, `grpc-address`, `grpc-server-port`, `grpc-tls=false`,
  `auth-mode=none`;
- a **context-unique** `grpc-processor-tag`.

**Test workflows are templated.** They reference `${tag}` in `calculationNodesTags`, and
the harness substitutes the context's tag at import. A second cached Spring context (e.g.
one forked by `@MockitoBean`) therefore joins with a different tag and never receives
another context's callouts. `@MockitoBean` in integration tests is also rejected by a
build-time check.

**Helpers:**
- `importWorkflow(model, resource)`: templating + import;
- `awaitComputeMemberJoined()`: waits for the greet;
- `rest()`: a raw REST client;
- unique model names per test class.

**Example application.** The example application lives in `src/test/java/com/example`. The
`integrationTest` source set has `sourceSets.test.output` on its classpath, and
`@CyodaIntegrationTest` boots `Application` plus a test configuration scanning
`com.example.application`.

### 6.2 Tier-1 suite: `integrationTest` (mock IAM, memory; in `check`)

| Class | Proves |
|---|---|
| `ComputeMemberJoinIT` | On a **dedicated** profile with keep-alive 1 s / 3 s, the app joins and receives the greet, and a processor transition started after 10 s of idleness is still served. |
| `ModelAndWorkflowSetupIT` | `CyodaInit` imports sample data, locks the model, imports the v1.5 workflow. The export round-trips through the generated DTOs, equal to what was imported. Every workflow under `src/test/resources/example/config/workflow/` imports without error. |
| `EntityCrudIT` | Via `EntityService` (gRPC): create, bulk create, get, get-all, update loopback, update with transition, delete, delete-all. |
| `SearchIT` | Direct search with `$.` conditions of every condition type; paged async snapshot search across ≥ 3 pages; `pointInTime`. |
| `ProcessorIT` | A processor runs on a transition and its data change persists. A processor failure rolls back the transition and surfaces the error. Response contract: fresh `id`, no `"error": null`. |
| `CriterionIT` | Criterion true → transition taken. False → the manual transition answers `400 WORKFLOW_FAILED` whose detail ends with the criterion's reason. |
| `CascadeAtomicityIT` | A processor creates and transitions a second entity via `EntityService` with the tx-token: both commit with the parent transition, and a later processor failure rolls back both. A model-admin call inside the processor carries no tx-token and succeeds. A timeout parameter inside a scope is rejected locally. |
| `CascadeConcurrencyIT` | Regression for §4.5: 3-level nested cascades, 16 at once, all complete within the callout timeout. |
| `AuthContextPlumbingIT` | A processor sees the mock principal: `authtype=user`, `authid=mock-user-001`, roles `ROLE_ADMIN`,`ROLE_M2M`. `requireRole("ROLE_ADMIN")` is true, and `requireRole("ADMIN")` is false. |

### 6.3 Unit tests

- **Existing tests:** `common/` and `com/example/` unit tests are updated for the renames.
  OBO and event-user-resolver tests are deleted.
- **New unit tests:**
  - `CyodaCallContexts` rules 1–5, including a blank JWT, a non-JWT principal, and a scope
    that overrides a user `SecurityContext`;
  - every outbound call of a paged snapshot search carries the **same** context: same
    credential, same tx-token;
  - the gRPC interceptor cancels on a credential failure;
  - tx-token attachment per RPC and per REST path;
  - mapping of every refusal in the §4.4 table, on both the envelope (gRPC) and status
    (REST) shapes;
  - `authclaims` parsing (both forms, empty, garbage);
  - `requireRole` for `SERVICE`, `SYSTEM`, empty context, empty roles;
  - `CalloutScope.wrap` / `unjoined` / closed-scope behaviour;
  - the `allOf` rewrite and its failure mode;
  - discriminator-mapping injection and its no-op when a mapping exists;
  - DTO round-trip (§3.3.3);
  - response serialisation;
  - `CYODA_VERSION` parsing and the version comparison.

## 7. Build and CI

### 7.1 Source sets and tasks

- `testFixtures` (`java-test-fixtures` plugin).
- `integrationTest`: its classpath includes `sourceSets.test.output`; the task is in
  `check`. It passes `cyoda.bin`, `cyodaVersion` and the log directory.
- Jacoco reports on `test` + `integrationTest`.

### 7.2 Removed

- Cucumber dependencies, the `se.thinkcode.cucumber-runner` plugin, and the `cucumberTest`
  task with its jacoco wiring;
- `GherkinE2eTest`, `src/test/java/e2e/`, `src/test/resources/features/`,
  `application-cucumber.yaml`.

### 7.3 Codegen tasks

- `prepareCyodaProto`, `prepareEventSchemas`, `prepareOpenApi`;
- the aligned `protoc` version (§3.3.1).

### 7.4 Integration-suite build check

A test (or a small Gradle check) fails when an integration-test class declares
`@MockitoBean` / `@MockBean` (§6.1).

### 7.5 Installing cyoda

`scripts/install-cyoda.sh [--from-src <git-ref>] [--dest <dir>]` reads `CYODA_VERSION`:
- **released version:** downloads that release through the project's `install.sh`, with
  `CYODA_VERSION` pinned;
- **`--from-src`:** clones `https://github.com/Cyoda/cyoda-go` at the ref and runs
  `go build -ldflags "-X main.version=<version> -X main.commit=<sha> -X main.buildDate=<date>" ./cmd/cyoda`.
  - Required until v0.9.0 is tagged.
  - The Go module path is `github.com/cyoda-platform/cyoda-go`; the repository is
    `Cyoda/cyoda-go`. Go ≥ 1.26.7 (cyoda-go `go.work`).
- Prints the binary path for `CYODA_BIN`.

### 7.6 CI (`.github/workflows/build.yml`)

For build types `standard` and `test-only`:
1. `actions/setup-go` pinned to cyoda-go's Go version (source build only);
2. `install-cyoda.sh`;
3. `./gradlew check`.

On failure, `build/cyoda-logs/` and test reports are uploaded as artifacts.

## 8. Verify early during implementation

Each item is checked first in its area. A failed assumption changes the plan; the design is
not bent to fit it silently.

1. **Generated OpenAPI DTOs compile** with the `schemaMappings` of §3.3.3, and round-trip
   an exported workflow and every condition type.
2. **jsonschema2pojo output** after the `allOf` rewrite matches the framework's usage,
   including the `orderBy` classes.
3. **Protobuf** generation with import-only `cloudevents.proto` and the library's
   `CloudEvent` class.
4. **Virtual-thread pinning**: no `synchronized` block holds a lock across a blocking
   Cyoda call in the framework's call paths.
5. **Async snapshot search inside a joined scope**: does it see the transaction's
   uncommitted writes? Whatever the answer, the Javadoc documents it.
6. **Cyoda Cloud compatibility** of the `authclaims` JSON form and of `caas_org_id` in
   Cloud M2M tokens. Unverifiable locally; the code tolerates both outcomes.

## 9. cyoda-go findings

This project never changes cyoda-go. Every finding becomes an issue in `Cyoda/cyoda-go`
with milestone `v0.9.0`.

| # | Finding | Kind | Issue |
|---|---|---|---|
| 1 | `client_credentials` tokens carry roles in `scopes`, not `user_roles` as `cyoda help auth`, `auth tokens` and `auth clients` document. The claim present decides user vs service principal, and that rule is undocumented. The same page calls `caas_org_id` a "string UUID". | docs | [#621](https://github.com/Cyoda/cyoda-go/issues/621) |
| 2 | The `BootstrapConfig.ClientSecret` comment says "generated if empty". It never is: in jwt mode it must be set together with the client id, and it is ignored in mock mode. | docs (minor) | [#622](https://github.com/Cyoda/cyoda-go/issues/622) |
| 3 | An unrecognised first argument (e.g. `cyoda version`) starts the server with the user's real config; extra arguments are silently ignored. | bug | [#623](https://github.com/Cyoda/cyoda-go/issues/623) |
| 4 | Four OpenAPI discriminators declare no `mapping`, so generated clients key subtypes by schema name instead of wire values. | bug | [#625](https://github.com/Cyoda/cyoda-go/issues/625) |
| 5 | `EntityModelSetUniqueKeys*` events are accepted over gRPC but have no JSON schema in the published tree. | bug | [#626](https://github.com/Cyoda/cyoda-go/issues/626) |

**Impact on this template:**
- #1: none; the template never reads roles out of its own M2M token.
- #3: none; the harness only calls `--version` and starts the binary with no arguments.
- #4: worked around by `prepareOpenApi` (§3.3.3).
- #5: unique keys wait for sub-project 2.

Findings discovered while implementing are appended here and filed the same way.

## 10. Sub-project 1b: known inputs (to be brainstormed separately)

1b gets its own brainstorm → spec → plan cycle. These inputs are already known:

**Scope**
- **Inbound security:** a resource server validating the IdP's bearer JWT
  (`issuer-uri`), and the fail-closed rule for non-JWT principals already in §4.2.
- **Tier-2 infrastructure:** cyoda-go JWT + postgres on the host; Zitadel + Postgres (on
  `tmpfs`) via Testcontainers `ComposeContainer`; `docker/zitadel/seed.sh`.
- **Tier-2 suites:** `M2mJoinIT`, `AuthContextIT`, `CascadeAttributionIT`,
  `BffForwardingIT`, as outlined during design.
- CI with Docker.

**Persona tokens (decided: option A plus a spike)**
- Personas are Zitadel **machine users** with a project role grant and JWT access tokens.
  Each gets its own token with client credentials (as ctcc's `e2e/personas.go`). This is
  not impersonation: each persona authenticates as itself.
- **Spike (time-boxed, first task of 1b):** does requesting Zitadel's reserved scope
  `urn:zitadel:iam:org:projects:roles`, with the project audience scope, put
  `urn:zitadel:iam:org:project:roles` into a machine user's JWT access token?
  - **Yes:** tier 2 asserts roles end to end.
  - **No:** role assertions stay in unit tests and tier 1, and tier 2 proves forwarding and
    attribution only.

**Known constraints**
- **Issuer:** it must match byte for byte; cyoda compares `iss` bytewise. Use `localhost`
  as Zitadel's external domain, and a free port chosen in Java and passed through compose
  interpolation to both `ports:` and `ZITADEL_EXTERNALPORT`.
- **Readiness:** wait on `<issuer>/.well-known/openid-configuration` and the seed's PAT
  file, with a Zitadel timeout of 120 s or more.
- **OIDC registration:**
  - requires `wellKnownConfigUri`; decide `issuers` and `expectedAudiences`;
  - `Register` warms keys synchronously; `/api/oauth/oidc/providers/reload` refills them
    (v0.9.0);
  - the tenant must be a lowercase UUID, and the bootstrap tenant is set to one.
- **`executedBy`** in change history is the bootstrap client's `caas_user_id`
  (`CYODA_BOOTSTRAP_USER_ID`), not its client id. Set it explicitly and assert on it.
- **`system` principal:** it arises only from scheduled fires, which belong to sub-project
  2. Its tests live there or in unit tests.
- **Seed prerequisites:** `seed.sh` needs `curl` and `jq`. Machine-key bind-mount
  permissions on Linux CI are to be checked.
- **Deployment note** for the docs: production user tokens carry roles only if the IdP
  asserts them in the access token (Zitadel: `accessTokenRoleAssertion` on the app) and the
  provider registration names the matching `rolesClaim`.

## 11. Out of scope for 1a

- Everything in §10 (1b), sub-project 2 and sub-project 3.
- Browser-session BFF (authorization code + PKCE, server-side sessions) and role-based
  authorization in the BFF.
- OBO / token exchange of any kind.
- Changes to cyoda-go.
- Cluster-mode testing.
