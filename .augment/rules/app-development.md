---
type: "always_apply"
description: "Guidelines for building services with Cyoda Cloud"
---

# Application Development Rules

> **SCOPE**: Apply these rules when developing application code in this project.

## Project Layout

### Package Structure
```
com/java_template/common/     # Framework code - DO NOT MODIFY
├── auth/                     # Authentication & token management
├── config/                   # Configuration classes
├── dto/                      # Data transfer objects
├── grpc/                     # gRPC client integration
├── repository/               # Data access layer (Cyoda REST API)
├── serializer/               # Serialization framework
├── service/                  # EntityService, WorkflowService, EdgeMessageService
├── tool/                     # Utility tools
├── util/                     # Utility functions
└── workflow/                 # Core interfaces (CyodaEntity, CyodaProcessor, CyodaCriterion)

<your-package>/application/    # Your business logic - CREATE AS NEEDED
├── controller/               # REST endpoints
├── entity/                   # Domain entities
├── processor/                # Workflow processors
├── criterion/                # Workflow criteria
└── service/                  # Application-specific services (extensions go here)
```

### Resource Directories
- `src/main/resources/cyoda/schema/` - gRPC JSON schema definitions (Cyoda-provided)
- `src/main/resources/cyoda/openapi/openapi.yaml` - HTTP OpenAPI specification (Cyoda-provided)

## Contribution Rules

### Framework Code (`com/java_template/common/`)
- **DO NOT MODIFY** the `com/java_template/common/` package directly
- Until a stable library is provided, updates to the common package require dropping in the latest code
- **Risk of conflicts**: Any modifications to `com/java_template/common/` may conflict with future updates

### Schema and API Definitions
- **DO NOT MODIFY** files in `src/main/resources/cyoda/schema/` or `src/main/resources/cyoda/openapi/openapi.yaml`
- These files are provided by the Cyoda platform
- They will be provided as a library dependency in the future

### Extending Functionality
- **If functionality is missing**: Build extensions outside the `com/java_template` package
- Place custom services, utilities, and extensions in your application package
- Follow existing patterns from the framework code when creating extensions

