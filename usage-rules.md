# Usage Rules - Java Client Template

This file provides comprehensive guidelines for developers and AI agents working with the Java Client Template project. All patterns shown are current best practices.

## 📚 **Code Examples Reference**

**All code examples and implementation patterns are located in the `src/test/java/com/example/application/` directory:**
- `src/test/java/com/example/application/controller/` - REST controller patterns and best practices
- `src/test/java/com/example/application/entity/` - Entity class implementations with CyodaEntity interface
- `src/test/java/com/example/application/processor/` - Workflow processor examples with serialization patterns
  - `src/test/java/com/example/application/criterion/` - Workflow criteria examples with evaluation logic

**Configuration examples are in the `src/test/resources/example/config/` directory:**
- `src/test/resources/example/config/workflow/` - Workflow JSON configuration templates and examples

**Always reference these examples** when implementing new components to ensure consistency with established patterns.

## 🚀 **Core Principles**

- **Performance-Optimized**: Use EntityService methods with clear performance guidance
- **Type-Safe**: Work with EntityWithMetadata<T> throughout the application
- **Unified Interface**: Consistent patterns between processors and controllers
- **Clean Architecture**: Minimal dependencies, clear separation of concerns
- **No Deprecated Patterns**: Only current, recommended approaches

## 📦 **EntityService - Performance-Optimized API**

### ✅ **EntityService Method Patterns**
See `src/test/java/com/example/application/controller/` for complete EntityService usage examples in REST controllers.

## 🏗️ **Entities**

- Always implement `CyodaEntity` interface for domain entities
- Place entity classes in `application/entity/` directory for automatic discovery
- Implement `getModelKey()` to return `OperationSpecification.Entity` with proper ModelSpec
- Override `isValid()` method to provide entity-specific validation logic
- Use static `ENTITY_NAME` constant for consistent entity naming
- Work with EntityWithMetadata<T> wrapper that includes entity + metadata

## ⚙️ **Processors (CyodaProcessor)**

### ✅ **Processor Implementation Patterns**
See `src/test/java/com/example/application/processor/` for complete processor implementation examples including:
- Constructor patterns with SerializerFactory injection
- EntityService integration for interacting with other entities
- Fluent API usage with ProcessorSerializer
- EntityWithMetadata processing patterns
- Error handling and validation approaches

### 🚨 **Critical Processor Limitations**
- ✅ **CAN**: Read current entity data using `entityWithMetadata.entity()`
- ✅ **CAN**: Access current entity metadata: `entityWithMetadata.metadata().getId()` and `entityWithMetadata.metadata().getState() ` to read technical ID and current state
- ✅ **CAN**: Get, update, delete OTHER entities using EntityService
- ❌ **CANNOT**: Use entityService to update the current entity being processed
- ❌ **CANNOT**: Change the current entity's workflow state
- ❌ **CANNOT**: Use Java reflection
- ❌ **CANNOT**: Modify code in `src/main/java/com/java_template/common` directory

### ❌ **Forbidden Processor Patterns**
- **NEVER** extract from payload manually using ObjectMapper
- **NEVER** try use entityService to update the current entity in processor
- **NEVER** use Java reflection
## 🔍 **Criteria (CyodaCriterion)**

### ✅ **Criteria Implementation Patterns**
See `src/test/java/com/example/application/criterion/` for complete criteria implementation examples including:
- Constructor patterns with SerializerFactory injection
- CriterionSerializer fluent API usage
- EvaluationOutcome chaining for validation logic
- ReasonAttachmentStrategy for validation feedback
- EntityWithMetadata access patterns

### 🎯 **Criteria Guidelines**
- Criteria MUST be pure functions - no side effects or payload modifications
- Return boolean evaluation results only - no entity modifications
- Use `EvaluationOutcome.and()` chaining for validation logic
- Use `withReasonAttachment(ReasonAttachmentStrategy.toWarnings())` for validation feedback

## 🔄 **Serializers**

### ✅ **Serializer Usage Patterns**
See `src/test/java/com/example/application/processor/` and `src/test/java/com/example/application/criterion/` for complete serializer usage examples including:
- SerializerFactory injection patterns
- ProcessorSerializer fluent API usage
- CriterionSerializer fluent API usage
- Validation and error handling patterns
- EntityWithMetadata processing chains

### 🎯 **Serializer Guidelines**
- Use `SerializerFactory` to get appropriate serializer instances
- Prefer `ProcessorSerializer` for processors and `CriterionSerializer` for criteria
- Jackson serializers are the default implementation
- Always validate requests before processing in serializer implementations

## 🎯 **Controllers**

### ✅ **Controller Implementation Patterns**
See `src/test/java/com/example/application/controller/` for complete controller implementation examples including:
- REST endpoint patterns with proper annotations
- EntityService integration and method selection
- EntityWithMetadata response patterns
- Error handling and HTTP status codes
- ModelSpec creation and usage

### ❌ **Forbidden Controller Patterns**
- **NEVER** accept `Map<String, Object>` as request body
- **NEVER** return generic `Object` responses
- **NEVER** bypass EntityService for direct repository access

See `src/test/java/com/example/application/controller/` for correct patterns and anti-patterns documentation.

### 🎯 **Controller Guidelines**
- CAN return `ResponseEntity<EntityWithMetadata<T>>` for single entities
- CAN return `ResponseEntity<List<EntityWithMetadata<T>>>` for multiple entities
- Use appropriate EntityService methods based on performance needs
- Handle errors with proper HTTP status codes (400, 404, 500)

## 📋 **EntityWithMetadata<T> Structure**

### ✅ **Working with EntityWithMetadata**
EntityWithMetadata<T> is the unified wrapper for all entity operations providing:
- **Entity Access**: `entityWithMetadata.entity()` method for business data
- **Metadata Access**: `entityWithMetadata.metadata()` method for technical information
- **Technical UUID**: Use `entityWithMetadata.metadata().getId()` for subsequent operations
- **Workflow State**: Access current state via `entityWithMetadata.metadata().getState()`
- **Audit Information**: Creation date, last update, transition history

### 📄 **JSON Structure**
EntityWithMetadata follows a clean JSON structure:
```
{
  "entity": { /* business data */ },
  "metadata": { /* technical metadata */ }
}
```

See `src/test/java/com/example/application/controller/` and `src/test/java/com/example/application/processor/` for complete EntityWithMetadata usage patterns.

## 🔄 **Workflow Configuration**

### ✅ **Workflow Best Practices**
- Place workflow JSON files in `src/main/resources/workflow/$entity_name/version_$version/` directory
- Use finite-state machine (FSM) model for workflow definitions
- Avoid cyclic FSM states in workflow configuration
- Component operation names must match `supports()` method implementations
- Use `WorkflowImportTool` for importing workflow configurations
- Workflow methods should be separate processor classes, not switch-based dispatch
- Check workflow documentation for valid transitions when updating entities

### 🎯 **Workflow Transitions**
- **With Transition**: Use `entityService.update(entityId, entity, "TRANSITION_NAME")` to trigger workflow transitions
- **Without Transition**: Use `entityService.update(entityId, entity, null)` to update without state change
- **Processor Limitation**: Processors can only update OTHER entities, not the current entity being processed

See `src/test/resources/example/config/workflow/` for workflow transition examples and patterns.

## ⚙️ **Configuration**

- Use injected `Config` bean instead of hardcoded values
- Configuration is managed via Spring Boot YAML files (see the "Configure the Application" section of `README.md`)
- Entity versioning: Use integer constants like `public static final Integer ENTITY_VERSION = 1;`
- Configure gRPC settings via `application.yml` or profile-specific YAML files
- Use `config.getCyodaHost()` and related methods for Cyoda integration
- SSL and authentication settings are configured via Spring Boot properties

## 🏗️ **Architecture Guidelines**

- Follow separation of concerns: entities, processors, criteria, services, controllers
- Use dependency injection via Spring constructor injection
- Implement interfaces rather than concrete classes for better testability
- Use sealed classes for type-safe operation specifications
- Prefer composition over inheritance in workflow components
- Use factory patterns for component selection and instantiation
- Work with EntityWithMetadata<T> for unified interface consistency

## 🌐 **gRPC Integration**

- Use `CyodaCalculationMemberClient` for gRPC communication with Cyoda
- Configure gRPC settings via `config.getGrpcAddress()` and `config.getGrpcServerPort()`
- Use `config.getGrpcProcessorTag()` for processor identification
- Handle gRPC streaming with proper error handling and connection management
- Use protobuf message types for type-safe gRPC communication
- Configure SSL/TLS settings via Spring Boot configuration properties

## ⚠️ **Error Handling**

### ✅ **Error Handling Patterns**
- Use `ErrorInfo` class for structured error information
- Prefer `StandardErrorCodes` enum over hardcoded error strings
- Use `ErrorInfo.fromException()` for exception handling
- Use `ResponseBuilder` for consistent error response formatting
- Use `EvaluationOutcome.Fail` for criteria validation failures

See `src/test/java/com/example/application/processor/` and `src/test/java/com/example/application/criterion/` for complete error handling examples.

### 🎯 **Error Handling Guidelines**
- Use `ErrorInfo` class for structured error information
- Prefer `StandardErrorCodes` enum over hardcoded error strings
- Use `EvaluationOutcome.Fail` for criteria validation failures
- Log errors with appropriate context (entity ID, operation type, stack trace)
- Use `ResponseBuilder` for consistent error response formatting
- Never log and rethrow exceptions - choose one approach consistently

## ✅ **Validation**

- Use `@Valid` annotation for request body validation in controllers
- Implement `isValid()` method in entity classes for business validation
- Use `EvaluationOutcome` chaining for complex validation logic
- Prefer `validate()` method in EntityResponseProcessingChain for processor validation
- Use `ReasonAttachmentStrategy` to attach validation reasons to responses
- Validate requests in serializer implementations before processing
- Use `Condition` and `SearchConditionRequest` for query validation

## 🚫 **Critical Restrictions**

- **CANNOT** use Java reflection
- **CANNOT** modify code in `src/main/java/com/java_template/common` directory
- **CANNOT** update current entity in processors with entityService - only OTHER entities
- **MUST** run compilation checks frequently
- **MUST** use performance-optimized EntityService methods
- **MUST** work with EntityWithMetadata<T> for consistency
