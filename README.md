# Java Client Template

A **Gradle project** using **Spring Boot** with **Cyoda integration** for building scalable web clients with workflow-driven backend interactions.


## 🛠️ Getting Started

> ☕ **Java 21 Required**
> Make sure Java 21 is installed and set as the active version.

### 1. Clone the Project

```bash
git clone https://github.com/Cyoda-platform/java-client-template.git
cd java-client-template
```

### 2. ⚙️ Configure the Application

Configuration is managed via Spring Boot YAML files. `application.yml` holds Cloud-shaped defaults.

**Local cyoda-go (the `cyoda-local` profile).** `src/main/resources/application-cyoda-local.yml` ships
with the template and targets a local cyoda-go on its defaults (REST `http://localhost:8080/api`, gRPC
`localhost:9090`, no TLS, `auth-mode: none`). It moves the app to port **8081** so it does not clash
with cyoda-go on 8080, and binds it to `127.0.0.1` only. It is tracked in git: do not put credentials in it.

```bash
./gradlew runApp --args='--spring.profiles.active=cyoda-local'
```

**Cyoda Cloud.** Never commit credentials. Either use environment variables:

```bash
export APP_CONFIG_CYODA_HOST=your-cyoda-host
export APP_CONFIG_CYODA_CLIENT_ID=your-client-id
export APP_CONFIG_CYODA_CLIENT_SECRET=your-client-secret
./gradlew runApp
```

or a git-ignored `application-cloud.yml` (every `application-*.yml` except the shipped
`application-cyoda-local.yml` is git-ignored, including an `application-local.yml` you may already have). Put it in `config/` at the project root rather than in
`src/main/resources`, so it is not packaged into the jar; Spring Boot reads `./config/` from the
working directory:

```yaml
# config/application-cloud.yml
app:
  config:
    cyoda-host: your-cyoda-host
    cyoda-client-id: your-client-id
    cyoda-client-secret: your-client-secret
```

```bash
./gradlew runApp --args='--spring.profiles.active=cloud'
```

### 3. 🧰 Run Workflow Import Tool

#### Option 1: Run via Gradle (recommended for local development)
```bash
./gradlew runApp -PmainClass=com.java_template.common.tool.WorkflowImportTool --args='--spring.profiles.active=cyoda-local'
```

#### Option 2: Build and Run JAR (recommended for CI or scripting)
```bash
./gradlew bootJarWorkflowImport
java -jar build/libs/java-client-template-1.0-SNAPSHOT-workflow-import.jar --spring.profiles.active=cyoda-local
```

### 4. ▶️ Run the Application

#### Option 1: Run via Gradle
```bash
./gradlew runApp --args='--spring.profiles.active=cyoda-local'
```

#### Option 2: Run Manually After Build
```bash
./gradlew bootJar
java -jar build/libs/app.jar --spring.profiles.active=cyoda-local
```

> Access the app (cyoda-local profile): [http://localhost:8081/api/swagger-ui/index.html](http://localhost:8081/api/swagger-ui/index.html)
>
> **Note**: The app runs on port 8080 by default (`src/main/resources/application.yml`) and on 127.0.0.1:8081 with the `cyoda-local` profile, under the `/api` context path. You can change the port by setting the `server.port` property.

### 5. 🧪 Run the Tests

```bash
./gradlew test                        # unit tests
./gradlew check                       # unit tests + integration tests against the pinned cyoda-go
./gradlew build -x integrationTest    # build without the integration tests
```

`./gradlew check` and `./gradlew build` run the integration tests (`src/integrationTest`), which start the
cyoda-go version pinned in `src/main/resources/cyoda/CYODA_VERSION` as a subprocess. **The build installs it
automatically**: the `installCyoda` task runs `scripts/install-cyoda.sh` into `.cyoda/bin` (git-ignored). The
binary survives `./gradlew clean` and is reused while it matches the pin, so it is installed once per pin.
Installing needs:

- for a `-dev` pin (built from source): Go 1.26.7 or later, `git`, and network access to github.com;
- for a released pin (downloaded): network access to the GitHub release; the archive must match the SHA-256
  committed next to the pin (`CYODA_SHA256SUMS`).

To use a cyoda binary you installed yourself, pass `-Dcyoda.bin=<path>` or set `CYODA_BIN=<path>`; the build
then installs nothing. A `cyoda` on `PATH` that already matches the pin is kept too, as long as `.cyoda/bin`
is empty (the tests then use it). To keep a binary that deliberately does not match the pin — at `.cyoda/bin`
or on `PATH` — instead of it being overwritten, pass `-Dcyoda.allowVersionMismatch=true`. Windows cannot run
the install script: use `-Dcyoda.bin` / `CYODA_BIN` there, or `-x integrationTest`.

**Dependency verification.** Gradle checks every downloaded dependency against `gradle/verification-metadata.xml`.
After adding or bumping a dependency, regenerate the checksums with one command (details in `CONTRIBUTING.md`):

```bash
GRADLE_USER_HOME="$(mktemp -d)" ./gradlew --no-daemon --write-verification-metadata sha256 build integrationTest jacocoTestReport bootJarWorkflowImport printOtelAgentPath resolveProtocNatives
```

---

## 🏗️ Project Structure

This template follows a clear separation between **framework code** (that you don't modify) and **application code** (where you implement your business logic).

### `src/main/java/com/java_template/common/` - Framework Code (DO NOT MODIFY)

**Core Framework Components:**
- `auth/` – Authentication & token management for Cyoda integration
- `config/` – Configuration classes using Spring Boot's configuration management
- `dto/` – Data transfer objects including `EntityWithMetadata<T>` wrapper
- `grpc/` – gRPC client integration with Cyoda platform
- `repository/` – Data access layer for Cyoda REST API operations
- `service/` – `EntityService` interface and implementation for all Cyoda operations
- `serializer/` – Serialization framework with fluent APIs (`ProcessorSerializer`, `CriterionSerializer`)
- `tool/` – Utility tools like `WorkflowImportTool` for importing workflow configurations
- `util/` – Various utility functions and helpers
- `workflow/` – Core interfaces: `CyodaEntity`, `CyodaProcessor`, `CyodaCriterion`

> ⚠️ **IMPORTANT**: There is no need to modify anything in the `common/` directory. This is the framework code that provides all Cyoda integration.

### `src/main/java/com/java_template/application/` - Your Business Logic (CREATE AS NEEDED)

**Your Implementation Areas:**
- `controller/` – REST endpoints and HTTP API controllers
- `entity/` – Domain entities implementing `CyodaEntity` interface
- `processor/` – Workflow processors implementing `CyodaProcessor` interface
- `criterion/` – Workflow criteria implementing `CyodaCriterion` interface

## 🔑 Core Concepts

### What is a CyodaEntity?
Domain objects that represent your business data. Must implement `CyodaEntity` interface and be placed in `application/entity/` directory.

### What is a CyodaProcessor?
Workflow components that handle business logic and entity transformations. **Critical limitation**: Cannot update the current entity being processed via EntityService.

### What is a CyodaCriterion?
Pure functions that evaluate conditions without side effects. Must not modify entities or have side effects.

### EntityWithMetadata<T> Pattern
Unified wrapper that includes both entity data and technical metadata (UUID, state, etc.). Used consistently across controllers, processors, and criteria.

## 🔄 Workflow Configuration

Workflows are defined using **finite-state machine (FSM)** JSON files placed in:
```
src/main/resources/workflow/$entity_name/version_$version/$entity_name.json
```

### Workflow Schema Reference
The workflow configuration schema is defined by `WorkflowConfigurationDto` in:
```
src/main/resources/cyoda/openapi/openapi.yaml
```
This schema defines the structure for workflow definitions, including states, transitions, processors, and criteria.

### Key Concepts
- **States and Transitions**: Define the workflow flow
- **Processors**: Handle business logic during transitions
- **Criteria**: Evaluate conditions to determine transition paths
- **Automatic Discovery**: Components are found via Spring `@Component` annotation

## 📚 Documentation and Examples

### Code Examples
- **`src/test/java/com/example/application/`** - Complete implementation examples for all components
  - `controller/` - REST controller patterns
  - `entity/` - Entity class implementations
  - `processor/` - Workflow processor examples  
  - `criterion/` - Workflow criteria examples

### Configuration Examples  
- **`src/test/resources/example/config/`** - Configuration templates and examples
  - `workflow/` - Workflow JSON configuration templates
  - `snippets/` - Processor and criterion configuration snippets

### Documentation Files
- **`README.md`** - Complete project documentation (this file)
- **`CONTRIBUTING.md`** - Contributors guide and validation workflow
- **`usage-rules.md`** - Developer and AI agent guidelines
- **`llms.txt`** / **`llms-full.txt`** - AI-friendly documentation references

## 📝 Quick Reference

### Key Concepts
- **Framework Code** (`common/`) - Never modify, provides all Cyoda integration
- **Application Code** (`application/`) - Your business logic implementation area
- **EntityWithMetadata<T>** - Unified wrapper pattern for all entity operations
- **EntityService** - Single interface for all Cyoda data operations

### Implementation Checklist
- ✅ Entities implement `CyodaEntity` with `getModelKey()` and `isValid()`
- ✅ Processors implement `CyodaProcessor` with `process()` and `supports()`
- ✅ Criteria implement `CyodaCriterion` with `check()` and `supports()`
- ✅ Use `@Component` annotation for Spring discovery
- ✅ Place workflow JSON files in `src/main/resources/workflow/$entity_name/version_$version/`
- ✅ Always reference `src/test/java/com/example/application/` for implementation patterns

### Critical Limitations
- ❌ Never modify anything in `common/` directory
- ❌ Processors cannot update the current entity being processed
- ❌ Criteria must be pure functions without side effects
- ❌ No Java reflection usage allowed

> 📚 **See `src/test/java/com/example/application/` and `src/test/resources/example/config/` for complete implementation examples, patterns, and configuration templates**

## 🚀 Getting Started

1. **Review Examples**: Start by exploring `src/test/java/com/example/application/` for implementation patterns
2. **Create Entities**: Implement `CyodaEntity` in `application/entity/`
3. **Add Processors**: Implement `CyodaProcessor` in `application/processor/`
4. **Add Criteria**: Implement `CyodaCriterion` in `application/criterion/`
5. **Configure Workflows**: Create JSON files in `src/main/resources/workflow/`
6. **Build Controllers**: Create REST endpoints in `application/controller/`

## 🔧 Development Workflow

1. Review `src/test/java/com/example/application/` for patterns before implementing new features
2. Follow established architectural patterns for processors, criteria, and serializers
3. Use `usage-rules.md` for detailed implementation guidelines
4. Run `./gradlew build` to generate required classes before development (it also installs the pinned cyoda
   for the integration tests; see "5. Run the Tests" above)

**For Contributors:**

- See `CONTRIBUTING.md` for detailed guidelines

## Package Management

Always use appropriate package managers for dependency management:

1. **Use package managers** for all dependency operations instead of manually editing configuration files
2. **Exception**: Only edit package files directly for complex configurations that cannot be accomplished through package manager commands
3. **Generated Classes**: Ensure `build/generated-sources/js2p/org/cyoda/cloud/api/event` classes are available via `./gradlew build`
4. **Communication**: Use generated classes for all Cyoda integration
