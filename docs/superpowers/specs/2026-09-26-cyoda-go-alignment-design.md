# Align the Java client template with cyoda-go v0.9.0 — design

**Date:** 2026-09-26
**Status:** approved in conversation, pending written-spec review
**Branch:** `feat/cyoda-go-v0.9-alignment`
**Contract source:** cyoda-go `release/v0.9.0` @ `ec73ef63` (latest tag `v0.8.4`), `cyoda help`, docs.cyoda.net

## 1. Intent

cyoda-go is the digital twin of Cyoda Cloud: it defines the API and integration contract,
and Cloud mirrors it. The Java client template predates cyoda-go and is stale against it.

**Outcome:** the template works against a local cyoda-go and against Cyoda Cloud by
configuration alone, its contract artifacts are cyoda-go's, and an integration suite that
starts cyoda-go itself proves it end to end.

**Success criteria**

1. `./gradlew check` builds, runs unit tests, and runs the tier-1 integration suite against
   an in-memory cyoda-go it starts itself — no Docker, no pre-running services.
2. `./gradlew authIntegrationTest` brings up Zitadel + Postgres, runs cyoda-go (JWT,
   postgres) and proves the authentication and attribution model with real IdP tokens.
3. Both run in CI.
4. `cyoda init && cyoda` followed by `./gradlew runApp --args='--spring.profiles.active=local'`
   gives a working app with no further configuration.

### 1.1 Decisions taken (with the user)

| Topic | Decision |
|---|---|
| Scope | Full alignment, decomposed into three sub-projects (§2). This spec is sub-project 1. |
| Launching cyoda-go in tests | Subprocess binary on free ports (as cyoda-go's own e2e suite does). No Testcontainers for cyoda itself. |
| Auth modes | Template supports cyoda-go `mock` (no token) and `jwt`. |
| Test tiers | Tier 1: tests that do not involve identity run on cyoda-go mock IAM + memory storage. Tier 2: tests that rely on authentication run on cyoda-go JWT + postgres, with Zitadel. Use in-memory wherever it makes sense. |
| Test style | Replace Cucumber with plain JUnit 5 integration tests. |
| Contract artifacts | Vendor cyoda-go's files verbatim + sync script; build-time `allOf`→`extends` rewrite for jsonschema2pojo; DTOs from the single authoritative `api/openapi.yaml`. |
| Credential model | **Compute → Cyoda is always M2M.** The originating user reaches compute as data (`authtype/authid/authclaims`), and compute's callbacks join the originating transaction via the tx-token so cyoda attributes them to the originating user. **BFF → Cyoda forwards the user's own IdP token** (Cyoda federated OIDC provider); M2M only for system work with no acting user. OBO / trusted-key subject-token minting is removed. |
| IdP | An external OIDC IdP is presumed; Zitadel is the reference for the target operating model. |

Rationale for the credential model (from the ctcc-management review — `docs/architecture.md`
§2.2, §4.4 and `docs/reference/cyoda-callout-security-model.md`): the IdP remains the only
authority for identity and roles; no component holds a credential able to mint a token for
arbitrary users (OBO's trusted signing key is exactly that); audits are correct on every
path — direct calls attribute to the forwarded user, joined cascades to the transaction's
origin (cyoda-go `docs/cloud-parity/authcontext-attribution.md`), scheduled fires to the
arming principal.

## 2. Roadmap

| # | Sub-project | Contents | Depends on |
|---|---|---|---|
| **1** | **Contract, runtime alignment, test harness** (this spec) | §3–§6 | — |
| 2 | New v0.9.0 capabilities | Entity merge-PATCH (`EntityPatchRequest`, `PATCH /entity/JSON/{id}`), function criteria and scheduled-transition functions (`EntityFunctionCalculationRequest/Response`), unique keys (`EntityModelSetUniqueKeys*`), grouped stats; each with tier-1/tier-2 tests | 1 |
| 3 | Documentation overhaul | README, `llms.txt`, `llms-full.txt`, `usage-rules.md`, `AI_TESTING_GUIDE.md`, `.augment/rules/*`, `CONTRIBUTING.md` point at `cyoda help` / docs.cyoda.net as the source of truth; local-dev and Zitadel assembly guides | 1, 2 |

Transaction-token callbacks moved from sub-project 2 into sub-project 1: correct audit
attribution of compute's writes depends on them.

## 3. Contract layer

### 3.1 Layout

cyoda-go's files are vendored **unmodified** under one root; the current `proto/`,
`schema/` and `api/` resource directories are deleted.

```
src/main/resources/cyoda/
  CYODA_VERSION                        # "0.9.0" + source commit — the single pin
  proto/cyoda/cyoda-cloud-api.proto
  proto/cloudevents/cloudevents.proto
  schema/{common,entity,model,processing,search}/*.json   # event schemas
  openapi/openapi.yaml                 # cyoda-go api/openapi.yaml (OpenAPI 3.1)
```

`CYODA_VERSION` format: first line the version (`0.9.0`, or `0.9.0-dev` before the tag),
second line `commit=<sha>`.

### 3.2 Sync

`scripts/sync-cyoda-contract.sh`:

- `--from-src <cyoda-go checkout>` copies `proto/cyoda/…`, `proto/cloudevents/…`,
  `docs/cyoda/schema/**/*.json` and `api/openapi.yaml`; records `git describe` + commit.
- `--from-bin <cyoda>` writes the same tree from `cyoda help grpc proto`,
  `cyoda help cloudevents json` and `cyoda help openapi yaml` (proto output is split on its
  separator comments; the cloudevents JSON is split into one file per schema by `$id`).
- Idempotent; the resulting `git diff` is the contract change.

### 3.3 Code generation

Generated package names are unchanged where they exist today, so application imports do
not move except for OpenAPI DTOs (§3.3.3).

**3.3.1 Protobuf.** cyoda-go's protos declare only `go_package`. Code is generated from a
build-dir copy (`prepareCyodaProto`) that inserts, after each `package` line:

- `cyoda/cyoda-cloud-api.proto`: `option java_multiple_files = true;`
- `cloudevents/cloudevents.proto`: `option java_multiple_files = true;` and
  `option java_package = "io.cloudevents.v1.proto";` — the package of the
  `cloudevents-protobuf` library's `CloudEvent`, which `ProtobufFormat` requires.

The `cloudevents/cloudevents.proto` import path is kept (proto root = the copy's `proto/`).
Output packages: `org.cyoda.cloud.api.grpc` and `io.cloudevents.v1.proto`, as today. The
step fails the build if either file already declares a conflicting Java option.

**3.3.2 Event schemas.** `prepareEventSchemas` copies the schema tree to
`build/cyoda-schema/` and rewrites exactly
`"allOf": [ { "$ref": "<path>/BaseEvent.json" } ]` → `"extends": { "$ref": "<path>/BaseEvent.json" }`.
Any other `allOf` fails the build naming the file, so contract drift cannot pass silently.
jsonschema2pojo generates from the copy into `org.cyoda.cloud.api.event.*` as today;
`EntityCriteriaCalculationResponse extends BaseEvent` still holds. Dropped `"type": "any"`
is inert: those properties carry `existingJavaType`. The generated sources are checked in
the first build; if a property's type changed, the framework is adapted.

**3.3.3 OpenAPI.** One generator task over `openapi/openapi.yaml`, model package
`org.cyoda.cloud.api.model`, **no** `modelNameSuffix` (cyoda-go's names already end in
`Dto`), `object`/`any` → `JsonNode` as today. The five split `openapi-*.yml` files, the
per-spec schema mappings and the `@JsonAlias` post-processing are removed. Framework code
(`EntityService`, `CyodaRepository`, `CyodaInit`, `WorkflowServiceImpl`, validators) is
adapted to the new names/package. Where a schema no longer exists, the framework uses
cyoda-go's equivalent.

New events available after the sync (used by sub-project 2): `EntityPatchRequest`,
`EntityPatchPayload`, `PatchFormat`, `EntityFunctionCalculationRequest/Response`,
`EntityModelSetUniqueKeys*`.

### 3.4 Version pin

The build reads `CYODA_VERSION` into a `cyodaVersion` property and passes it to tests as a
system property. The harness (§5.1) compares it to the launched binary's `--version`
(major.minor.patch): mismatch fails with a message naming both; `-Dcyoda.allowVersionMismatch=true`
downgrades to a warning; a `dev` build warns.

## 4. Runtime alignment

### 4.1 Connection configuration

`Config.CyodaLight` and `CyodaLightConfigCustomizer` (and its `spring.factories` entry)
are removed — "cyoda-light" was cyoda-go's earlier name. `app.config` carries:

| Property | Meaning | Default (`application.yml`, Cloud-shaped) |
|---|---|---|
| `cyoda-host` | Cloud host, used to derive the two below | — (required for Cloud) |
| `cyoda-api-url` | REST base incl. context path | `https://${cyoda-host}/api` |
| `grpc-address` / `grpc-server-port` | gRPC endpoint | `grpc-${cyoda-host}` / `443` |
| `grpc-tls` | TLS on the gRPC channel (replaces `skip-ssl`) | `true` |
| `auth-mode` | `client-credentials` \| `none` | `client-credentials` |
| `cyoda-client-id` / `cyoda-client-secret` | M2M credentials | — |

New `application-local.yml`: `cyoda-api-url: http://localhost:8080/api`,
`grpc-address: localhost`, `grpc-server-port: 9090`, `grpc-tls: false`, `auth-mode: none`
— cyoda-go's defaults after `cyoda init`.

### 4.2 Outbound credentials — one decision point

A single `CyodaCredentialResolver` decides the `Authorization` value for every outbound
Cyoda call, gRPC (interceptor) and REST (`HttpUtils` and all `RestClient` users):

1. `auth-mode=none` → no header.
2. A **forwarded user credential** is present on the current execution context →
   `Bearer <that token>`, unchanged. A present-but-blank credential throws
   `CyodaCredentialException` — never a silent downgrade to M2M.
3. Otherwise → the M2M token (client-credentials against `{cyoda-api-url}/oauth/token`,
   HTTP Basic, cached until 60 s before expiry; invalidated and re-fetched once on a 401).
   The `scope=ROLE_M2M` form parameter is removed if cyoda-go rejects it, kept otherwise.

The forwarded credential is the inbound bearer token of the current HTTP request (§4.3).
It is **never** set on compute threads (§4.4). It propagates across the framework's async
boundaries the way `CyodaRepository` already propagates the `SecurityContext`.

### 4.3 Inbound (BFF) security

`SecurityConfig` stays a replaceable auto-configuration default:

- `spring.security.oauth2.resourceserver.jwt.issuer-uri` set → requests under the context
  path require a valid bearer JWT from that issuer (health, OpenAPI and Swagger UI
  excepted); the authenticated `Jwt`'s token value is the forwarded credential for Cyoda
  calls made while handling the request.
- Not set → permit-all, as today, with a startup `WARN`; calls go out as M2M (or no header
  under `auth-mode=none`).

The browser-session BFF pattern (authorization code + PKCE, server-side session, no token
in the browser — ctcc §4.3) is out of scope.

### 4.4 Compute node

**Credential.** Compute always uses M2M (§4.2 step 3). `EventAuthContextHandler`,
`DefaultEventUserResolver`, `EventUserResolver`, `EventUserIdentity`,
`EventUserResolutionException` and the synthetic-JWT construction are removed.

**Auth context as data.** `CloudEventAuthContextExtractor` stays. Its result is exposed on
`CyodaEventContext` as `authContext()` returning `CloudEventAuthContext(authType, authId,
roles)` with `authType` ∈ {`USER`, `SERVICE`, `SYSTEM`} (the retired `service_account` is
not recognised; an unknown or absent value yields an empty context). A fail-closed helper
`CloudEventAuthContext.requireRole(String role)` mirrors cyoda-go's `authctx.Require`:
`true` only when `authType` is `USER` or `SERVICE` and the role is present in the claims;
`false` for absent context, empty claims or `SYSTEM`.

**Transaction token.** The `cyodatxtoken` CloudEvent attribute of a processor/criteria
request is captured into a `CalloutScope` bound to the executing thread for the duration of
the callout and exposed as `CyodaEventContext.txToken()`. While a scope is active:

- every gRPC call made through the framework adds metadata `tx-token: <token>`;
- every REST call adds `X-Tx-Token: <token>`;
- the scope propagates across the framework's async boundaries and is cleared when the
  callout completes.

`EntityService` calls made from a processor therefore join the triggering transaction:
they see its uncommitted writes, commit or roll back with it, and are attributed to its
origin. The documented rule that a processor must not modify the entity under processing
via `EntityService` stays. Refusals `410 CALLOUT_SUPERSEDED`, `410 TRANSACTION_EXPIRED`,
`404 TRANSACTION_NOT_FOUND` surface as `CyodaCalloutEndedException` and are not retried.

**Protocol fixes.**

| Item | Change |
|---|---|
| Join | `CalculationMemberJoinEvent` carries `joinedLegalEntityId` = the M2M token's `caas_org_id` claim (read without verification; it is our own token). Omitted under `auth-mode=none`. |
| Responses | Every processor/criteria response carries a fresh `id`, the `requestId` and `entityId`. Failure sends `success: false` explicitly. `error` is omitted rather than serialised as `null` (verified by a serialisation unit test). |
| Criteria | `matches` always set, on success and failure paths. |
| Keep-alive | The member answers every server keep-alive. The `keep-alive-warning-threshold` default changes from 60 s to 20 s, so the warning fires before cyoda-go's 30 s eviction (`CYODA_KEEPALIVE_TIMEOUT`). |
| Stream writes | Confirmed single-writer (gRPC forbids concurrent sends); fixed if not. |

### 4.5 Removal of OBO

Deleted: `OboAwareAuthentication`, `OboTokenService`, `OboKeyRegistrationService`,
`SubjectTokenSigner`, `AesGcmEncryption`, `OboSigningKey`, `OboProperties`,
`OboTokenException`, `AuthContextMode`, `EventAuthContextProperties`,
`EventAuthContextScope`, `EventAuthContextMissingException`, the OBO bootstrap resources
and all their tests. `Authentication` becomes the M2M token source behind
`CyodaCredentialResolver`.

### 4.6 Workflows and REST paths

- Example and test workflows move to `"version": "1.5"`; fields cyoda-go does not know are
  removed; `retryPolicy` is `NONE`/`FIXED`/absent; `responseTimeoutMs` ≤ 60000.
- Condition `jsonPath`s carry the required `$.` prefix — in examples and in any framework
  code that builds conditions.
- Every REST call in `common/` is checked against `openapi.yaml` for path and verb (e.g.
  model `lock` is `PUT`, `withAdminRole` is boolean, `/account/m2m*` is gone).

### 4.7 Framework API and downstream impact

Public signatures of `EntityService`, `CyodaProcessor`, `CyodaCriterion` and
`CyodaEventContext` change only by: OpenAPI DTO renames/package (§3.3.3), and the new
`authContext()` / `txToken()` accessors. `SYNCING_WITH_JAVA_TEMPLATE.md` gains a
"breaking changes in the v0.9.0 alignment" table (config keys, removed OBO, DTO package,
workflow version, `authtype` values, new directory layout).

## 5. Test harness

### 5.1 Components (`src/testFixtures/java/com/java_template/testing/cyoda/`)

Framework-owned (listed in the sync guide), consumable by `test`, `integrationTest`,
`authIntegrationTest` and downstream apps.

**`CyodaBinary`** — resolves the binary from `-Dcyoda.bin`, then `$CYODA_BIN`, then `cyoda`
on `PATH`; runs `--version`; enforces the pin (§3.4).

**`CyodaServer`** — one cyoda-go subprocess.

- Three free ports (HTTP, gRPC, admin) — never collides with a developer's running instance.
- Temp working directory and an explicit environment, so `~/.config/cyoda/cyoda.env` and
  any `./.env` cannot change storage or auth (explicit env always wins).
- Common env: `CYODA_SUPPRESS_BANNER=true`, `CYODA_ERROR_RESPONSE_MODE=verbose`,
  `CYODA_LOG_LEVEL=info`, `CYODA_DISPATCH_WAIT_TIMEOUT=200ms`,
  `CYODA_SCHEDULER_SCAN_INTERVAL=50ms`, `CYODA_KEEPALIVE_INTERVAL=1`,
  `CYODA_KEEPALIVE_TIMEOUT=3`.
- **Mock profile (tier 1):** `CYODA_STORAGE_BACKEND=memory`, `CYODA_IAM_MODE=mock`,
  `CYODA_IAM_MOCK_KIND` configurable (default `user`).
- **JWT/postgres profile (tier 2):** `CYODA_STORAGE_BACKEND=postgres`,
  `CYODA_POSTGRES_URL=<compose postgres>/cyoda`, `CYODA_IAM_MODE=jwt`, a generated RSA key
  written to a temp file as `CYODA_JWT_SIGNING_KEY_FILE`, random
  `CYODA_BOOTSTRAP_CLIENT_ID/SECRET`, a random lowercase-UUID `CYODA_BOOTSTRAP_TENANT_ID`
  (OIDC federation requires it), `CYODA_BOOTSTRAP_ROLES=ROLE_ADMIN,ROLE_M2M`,
  `CYODA_OIDC_REQUIRE_HTTPS=false`, `CYODA_OIDC_ALLOW_PRIVATE_NETWORKS=true`.
- Readiness: poll `GET /api/health` until `200`; fail fast if the process exits; 30 s limit.
- stdout/stderr → `build/cyoda-logs/<profile>-<timestamp>.log`; the last 50 lines are
  attached to any startup or test failure.
- Shutdown: SIGTERM, 5 s grace, then kill; a JVM shutdown hook backs it up.

**`CyodaTestEnvironment`** — one lazily started server per profile per test JVM, shared by
every test class of that tier.

**`@CyodaIntegrationTest`** (tier 1) / **`@CyodaAuthIntegrationTest`** (tier 2) —
meta-annotations combining `@SpringBootTest`, the JUnit extension and an
`ApplicationContextInitializer` that injects `app.config.cyoda-api-url`, `grpc-address`,
`grpc-server-port`, `grpc-tls=false`, `auth-mode`, client credentials and (tier 2)
`spring.security.oauth2.resourceserver.jwt.issuer-uri`. Identical properties let Spring
reuse one application context per tier. Tests isolate by unique model names per class;
nothing restarts between tests.

**Helpers:** `awaitComputeMemberJoined()` (waits for the greet before the first transition,
avoiding `NO_COMPUTE_MEMBER_FOR_TAG`), `rest()` (raw REST client with the tier's
credential, for assertions such as `GET /entity/{id}/changes`), and in tier 2
`personaToken(Persona)`.

**`ZitadelEnvironment`** (tier 2) — starts `docker/zitadel/compose.yaml` via Testcontainers
`ComposeContainer`, runs `docker/zitadel/seed.sh`, reads the env file it writes (issuer,
project id, persona credentials, roles claim), then starts `CyodaServer` (JWT/postgres),
registers Zitadel as the tenant's OIDC provider through `POST /api/oauth/oidc/providers`
(`rolesClaim: urn:zitadel:iam:org:project:roles`), calls `/reload`, and probes with a real
persona token until it is accepted (bounded; fails with the cyoda log tail otherwise).

### 5.2 Zitadel assembly (`docker/zitadel/`)

- `compose.yaml`: `postgres:17-alpine` with its data directory on `tmpfs` and an init
  script creating databases `zitadel` and `cyoda`; `ghcr.io/zitadel/zitadel` (version and
  digest pinned, as in ctcc) in `start-from-init` with a first-instance machine key written
  to a mounted directory. No cyoda service: cyoda runs on the host through `CyodaServer`,
  so the stack works before a v0.9.0 image exists.
- Issuer host follows ctcc's `*.localtest.me` convention so a token's `iss` is the same
  from the host and from containers.
- `seed.sh` (idempotent, ported from ctcc `infra/zitadel/seed.sh`): project with role
  assertion, roles, persona users with passwords, a machine user for persona token minting,
  writes `docker/zitadel/.generated/zitadel.env`. It is usable by hand for local
  development of the target operating model.

## 6. Test suites, build and CI

### 6.1 Tier 1 — `integrationTest` (mock IAM, memory storage; in `check`)

| Class | Proves |
|---|---|
| `ComputeMemberJoinIT` | The app joins and receives the greet; with 1 s/3 s keep-alive settings the member is still joined after 10 s. |
| `ModelAndWorkflowSetupIT` | `CyodaInit` imports sample data, locks the model and imports the v1.5 workflow; export round-trips; every JSON under `src/test/resources/example/config/workflow/` imports without error. |
| `EntityCrudIT` | Via `EntityService` (gRPC): create, bulk create, get, get-all, update loopback, update with transition, delete, delete-all. |
| `SearchIT` | Direct search with `$.` conditions; paged async snapshot search; `pointInTime`. |
| `ProcessorIT` | A processor runs on a transition and its data change persists; a processor failure rolls back the transition and surfaces the error; response contract (fresh `id`, no `"error": null`). |
| `CriterionIT` | Criterion true → transition taken; false → not taken, reason carried back. |
| `CascadeAtomicityIT` | A processor creates and transitions a second entity via `EntityService` with the tx-token: both commit with the parent transition; a later processor failure rolls back both. |
| `AuthContextPlumbingIT` | A processor sees the mock principal's `authtype`/`authid`/`authclaims`; `requireRole` evaluates them fail-closed. |

### 6.2 Tier 2 — `authIntegrationTest` (JWT, postgres, Zitadel; not in `check`)

| Class | Proves |
|---|---|
| `M2mJoinIT` | Client-credentials token, gRPC bearer join, `joinedLegalEntityId` = tenant, greet. |
| `AuthContextIT` | Real persona tokens produce `authtype=user`, `authid=oidc:<provider>:<sub>`, roles from Zitadel; `requireRole` fail-closed for missing role, empty claims, `system`. |
| `CascadeAttributionIT` | A persona triggers a transition through the example controller; the processor's joined cascade write shows `user` = the persona, `attributedKind=user`, `executedBy` = the M2M account in `GET /entity/{id}/changes`. |
| `BffForwardingIT` | Persona bearer → controller → Cyoda: change attributed to the persona; no token → 401; blank forwarded credential → error, no M2M call; a background call with no acting user → attributed to the M2M account. |

### 6.3 Unit tests

Existing `common/` and `com/example/` unit tests are updated for the renames; OBO and
event-user-resolver tests are deleted; new unit tests cover `CyodaCredentialResolver`,
`CalloutScope` propagation, `requireRole`, the `allOf` rewrite, response serialisation and
`CYODA_VERSION` parsing.

### 6.4 Build

- Source sets/tasks: `testFixtures` (`java-test-fixtures` plugin), `integrationTest`
  (task in `check`), `authIntegrationTest` (task not in `check`). Both integration tasks
  pass `cyoda.bin`, `cyodaVersion` and `build/cyoda-logs`.
- Removed: Cucumber dependencies, `se.thinkcode.cucumber-runner` plugin, `cucumberTest`
  task and its jacoco wiring, `GherkinE2eTest`, `src/test/java/e2e/`,
  `src/test/resources/features/`, `application-cucumber.yaml`. Jacoco reports on `test` +
  `integrationTest`.
- Added test dependency: Testcontainers (compose module) for tier 2.

### 6.5 Installing cyoda

`scripts/install-cyoda.sh [--from-src <git-ref>] [--dest <dir>]` reads `CYODA_VERSION`:
released version → downloads that release (the project's `install.sh` route with
`CYODA_VERSION` pinned); `--from-src` → clones `https://github.com/Cyoda/cyoda-go` at the
ref and runs `go build ./cmd/cyoda` (required until v0.9.0 is tagged; the Go module path is
`github.com/cyoda-platform/cyoda-go`, the repository is `Cyoda/cyoda-go`). Prints the
binary path for `CYODA_BIN`.

### 6.6 CI (`.github/workflows/build.yml`)

For build types `standard` and `test-only`: setup-go (source build only) →
`install-cyoda.sh` → `./gradlew check` → `./gradlew authIntegrationTest` (runners have
Docker). On failure, `build/cyoda-logs/` and test reports are uploaded as artifacts.

## 7. Verify during implementation

Each is checked early; a failed assumption changes the plan, not silently the design.

1. The tx-token is issued and honoured under mock IAM (tier-1 `CascadeAtomicityIT`).
2. cyoda-go accepts or ignores the `scope` form field on `client_credentials`.
3. `joinedLegalEntityId` omitted under mock mode is accepted.
4. jsonschema2pojo output after the `allOf` rewrite and `"type": "any"` removal matches
   what the framework uses.
5. openapi-generator 7.x handles cyoda-go's OpenAPI 3.1 spec; if not, bump the generator
   version.
6. Joined callbacks are served one at a time per transaction (CHANGELOG, Unreleased);
   `CyodaRepository`'s parallel calls inside a processor must not deadlock or time out.
7. v0.9.0's OIDC `/reload` refills the JWKS cache (ctcc saw it empty the cache without
   refilling on a 0.8.3 build).
8. `*.localtest.me` resolves to 127.0.0.1 through public DNS; tier 2 needs network DNS.
   If that proves fragile (offline, CI resolver), fall back to `localhost` as Zitadel's
   external domain, since cyoda runs on the host.

## 8. cyoda-go findings

This project never changes cyoda-go. Every finding becomes an issue in `Cyoda/cyoda-go`
with milestone `v0.9.0`. Issue numbers are recorded here once filed.

| # | Finding | Kind | Issue |
|---|---|---|---|
| 1 | `client_credentials` tokens carry roles in `scopes`, not `user_roles` as `cyoda help auth`, `auth tokens` and `auth clients` document. The code is deliberate: the presence of `user_roles` vs `scopes` decides whether the principal is a user or a service. The docs are wrong and never state that rule. The same page calls `caas_org_id` a "string UUID"; a tenant id follows the tenant grammar, and only OIDC federation requires a UUID. | docs | pending |
| 2 | The `BootstrapConfig.ClientSecret` field comment (`app/config.go:334`) says "optional, generated if empty". The secret is never generated: in jwt mode it must be set together with the client id (both or neither), and in mock mode it is ignored. User-facing docs are correct. | docs (minor) | pending |
| 3 | An unrecognised first argument (e.g. `cyoda version`, `cyoda serve`) starts the server with the user's real config instead of failing. Extra arguments and flags are silently ignored, although `cyoda help cli` shows `[<flags>]` in the synopsis. | bug | pending |

**Impact on this project:**
- #1: the template never reads roles out of its own M2M token (§4.2), so nothing depends on the claim name.
- #3: `CyodaBinary` calls only `--version`; `CyodaServer` starts the binary with no arguments.

Findings discovered while implementing are appended here and filed the same way.

## 9. Out of scope

- Sub-projects 2 and 3 (§2).
- Browser-session BFF (authorization code + PKCE, server-side sessions).
- OBO / token exchange of any kind.
- A cyoda container in the Zitadel assembly (added when a v0.9.0 image exists, if wanted).
- Changes to cyoda-go.
- Cluster-mode testing.
