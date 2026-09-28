---
type: "always_apply"
description: "Rules for integrating with Cyoda"
---

# Cyoda Integration Rules

> **SCOPE**: Apply these rules when working with Cyoda platform integration.

## Schema and API Definitions

### gRPC Schema Definitions
- **Location**: `src/main/resources/cyoda/schema/`
- **Contents**: JSON schema files for gRPC-related objects organized by domain:
  - `common/` - Base events, metadata, data formats, state machine definitions
  - `entity/` - Entity CRUD operations (create, update, delete, transition)
  - `model/` - Entity model management (import, export, delete)
  - `processing/` - Processor and criteria calculation requests/responses
  - `search/` - Entity search, snapshots, and statistics queries

### HTTP API OpenAPI Schema
- **Location**: `src/main/resources/cyoda/openapi/openapi.yaml`
- **Contents**: the single OpenAPI specification covering entity search, workflow management, IAM and audit logging endpoints

## Communication Protocol Preferences

### Prefer gRPC Over HTTP
- **Services, Criteria, and Processors** should preferentially use gRPC for Cyoda communication
- **Fall back to HTTP** only if a feature is not yet available in gRPC
- gRPC provides better performance and type safety for real-time operations

## Cyoda Service Layer

### Primary Services
Use the services defined in `com/java_template/common/service/` to access Cyoda:
- `EntityService` / `EntityServiceImpl` - Entity CRUD and search operations
- `WorkflowService` / `WorkflowServiceImpl` - Workflow management
- `EdgeMessageService` / `EdgeMessageServiceImpl` - Edge messaging
