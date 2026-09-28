# AI Testing Guide for Cyoda Java Client Applications

This guide provides specific commands and prompts for working with AI assistants to test Cyoda Java Client Applications. It includes critical constraints, common issues, and step-by-step integration procedures discovered through extensive E2E testing.

## 🚨 **CRITICAL CONSTRAINTS & REQUIREMENTS**
Before starting the application with ./gradlew runApp, ensure you have imported all the workflows using the MCP tool workflow_mgmt_import_workflows_from_file_tool_cyoda-mcp.
DO NOT start the application before importing the workflows.
It is critical to import workflows before you start the application - otherwise the application will not work correctly
Retry until workflow import is successful.

## ⚠️ **KNOWN LIMITATIONS DISCOVERED IN E2E TESTING**
- **Testing Approach**: Focus on verifying processor and criteria execution via logs rather than entity data changes

### **Entity Name Case Sensitivity** ⚠️
**MOST IMPORTANT**: Entity names for workflow import must **EXACTLY** match the `ENTITY_NAME` constant in your entity class:

```java
// In your entity class (e.g., src/main/java/com/java_template/application/entity/your_entity/version_1/YourEntity.java)
@Data
public class YourEntity implements CyodaEntity {
    public static final String ENTITY_NAME = YourEntity.class.getSimpleName(); // ← "YourEntity"
    public static final Integer ENTITY_VERSION = 1;
    // ... rest of entity implementation
}
```

```bash
# Workflow import - use EXACT case match
workflow_mgmt_import_workflows_from_file_tool_cyoda-mcp(
    entity_name="YourEntity",  # ← Must match ENTITY_NAME exactly
    model_version="1",
    file_path="src/main/resources/workflow/your_entity/version_1/YourEntity.json"
)
```

**Common Mistakes:**
- ❌ Using `entity_name="your_entity"` when `ENTITY_NAME = "YourEntity"`
- ❌ Using `entity_name="MAIL"` when `ENTITY_NAME = "YourEntity"`
- ✅ Using `entity_name="YourEntity"` when `ENTITY_NAME = "YourEntity"`

### **Directory Structure Requirements**
Your Java application must follow this structure:
```
src/main/java/com/java_template/
├── Application.java                    # Main Spring Boot application
├── common/                            # Framework code - DO NOT MODIFY
│   ├── auth/                          # Authentication & token management
│   ├── config/                        # Configuration classes & constants
│   ├── dto/                           # Data transfer objects
│   ├── grpc/                          # gRPC client integration
│   ├── repository/                    # Data access layer
│   ├── serializer/                    # Serialization framework
│   ├── service/                       # EntityService interface & implementation
│   ├── tool/                          # Utility tools
│   ├── util/                          # Utility functions
│   └── workflow/                      # Core interfaces (CyodaEntity, CyodaProcessor, CyodaCriterion)
└── application/                       # Your business logic - CREATE AS NEEDED
    ├── controller/                    # REST endpoints
    │   └── {Entity}Controller.java    # e.g., YourEntityController.java
    ├── entity/
    │   └── {entity_type}/             # e.g., your_entity, order, user
    │       └── version_1/
    │           └── {Entity}.java      # e.g., YourEntity.java, Order.java
    ├── processor/
    │   └── {Entity}{Action}Processor.java # e.g., YourEntityProcessor.java
    └── criterion/
        └── {Entity}{Condition}Criterion.java # e.g., YourEntityIsHappyCriterion.java

src/main/resources/
└── workflow/
    └── {entity_type}/                 # Must match entity directory name
        └── version_1/
            └── {EntityName}.json      # e.g., YourEntity.json, Order.json
```

## 🛠️ MCP Tools Setup

### Installing MCP Tools for AI Assistants

The Cyoda MCP Client provides tools that AI assistants can use directly. Install with:

```bash
# Install globally with pipx (recommended)
pipx install mcp-cyoda

# Or install with pip
pip install mcp-cyoda
```

### AI Assistant Configuration

Add this configuration to your AI assistant's MCP settings:

```json
{
  "mcpServers": {
    "cyoda": {
      "command": "mcp-cyoda",
      "env": {
        "CYODA_CLIENT_ID": "your-client-id-here",
        "CYODA_CLIENT_SECRET": "your-client-secret-here",
        "CYODA_HOST": "client-123.eu.cyoda.net"
      }
    }
  }
}
```

### Available MCP Tools

Once configured, AI assistants have access to these tools:

#### Entity Management Tools
- `entity_get_entity_tool_cyoda-mcp` - Retrieve entities by ID
- `entity_list_entities_tool_cyoda-mcp` - List all entities of a type
- `entity_create_entity_tool_cyoda-mcp` - Create new entities
- `entity_update_entity_tool_cyoda-mcp` - Update existing entities
- `entity_delete_entity_tool_cyoda-mcp` - Delete entities

#### Search Tools
- `search_find_all_cyoda-mcp` - Find all entities of a type
- `search_search_cyoda-mcp` - Advanced search with conditions

#### Workflow Management Tools
- `workflow_mgmt_export_workflows_to_file_tool_cyoda-mcp` - Export workflows
- `workflow_mgmt_import_workflows_from_file_tool_cyoda-mcp` - Import workflows
- `workflow_mgmt_list_workflow_files_tool_cyoda-mcp` - List workflow files
- `workflow_mgmt_validate_workflow_file_tool_cyoda-mcp` - Validate workflows

#### Edge Message Tools
- `edge_message_get_edge_message_tool_cyoda-mcp` - Retrieve messages
- `edge_message_send_edge_message_tool_cyoda-mcp` - Send messages

## 🔧 **WORKFLOW IMPORT TROUBLESHOOTING**

### **Issue: "Entity type not found" or "No workflow found"**
**Root Cause**: Entity name mismatch between class definition and workflow import.

**Solution**:
1. Check your entity class:
   ```java
   @Data
   public class YourEntity implements CyodaEntity {
       public static final String ENTITY_NAME = YourEntity.class.getSimpleName(); // ← "YourEntity"
       public static final Integer ENTITY_VERSION = 1;
   }
   ```

2. Use EXACT same name for workflow import:
   ```bash
   workflow_mgmt_import_workflows_from_file_tool_cyoda-mcp(
       entity_name="YourEntity",  # ← Must match EXACTLY
       model_version="1",
       file_path="src/main/resources/workflow/your_entity/version_1/YourEntity.json"
   )
   ```

### **Issue: "Workflow import succeeds but no workflow execution"**
**Root Cause**: Workflow imported locally but not deployed to Cyoda environment.

**Solution**: Always verify workflow deployment by creating entities via REST API, not just MCP tools.

### **Issue: "Workflow uses 'none' as initial state"**
**Root Cause**: "none" is a reserved keyword in Cyoda workflows.

**Solution**: Use "initial" as the initial state instead of "none" in workflow JSON files.

### **Issue: "Processor/Criteria not found"**
**Root Cause**: Processor/criteria not registered or not implementing required interfaces.

**Solution**: Check your processor/criteria classes:
```java
// Processor example
@Component
public class YourEntityProcessor implements CyodaProcessor {
    @Override
    public OperationSpecification supports() {
        // Return the ModelKey this processor supports
    }

    @Override
    public CyodaEntity process(CyodaEntity entity) {
        // Process the entity
    }
}

// Criterion example
@Component
public class YourEntityIsHappyCriterion implements CyodaCriterion {
    @Override
    public OperationSpecification supports() {
        // Return the ModelKey this criterion supports
    }

    @Override
    public boolean check(CyodaEntity entity) {
        // Check the condition
    }
}
```

## 🤖 AI Assistant Commands

### **Step-by-Step Integration Commands**

#### 1. **Discover Your Application Structure**
```
"Please examine the Java application directory structure and identify:
1. Entity classes in src/main/java/com/java_template/application/entity/ and their ENTITY_NAME constants
2. Available processors in src/main/java/com/java_template/application/processor/
3. Available criteria in src/main/java/com/java_template/application/criterion/
4. Workflow JSON files in src/main/resources/workflow/ and their locations
5. REST API endpoints in src/main/java/com/java_template/application/controller/
Show me the exact entity names and file paths I need for testing."
```

#### 2. **Import Workflows with Correct Entity Names**
```
"Import the workflow for [EntityName] using MCP tools. Make sure to use the exact entity name from the ENTITY_NAME constant in the entity class. The workflow file is at src/main/resources/workflow/[entity_type]/version_1/[EntityName].json"
```

#### 3. **Test Complete E2E Workflow via REST API**
```
"Start the Spring Boot application and test the complete workflow by:
1. Running './gradlew bootRun' to start the application
2. Creating an entity via POST /api/[entity] REST API (not MCP tools)
3. Monitor application logs for criteria and processor execution
4. Verify the entity reaches final workflow state
5. Confirm all gRPC events are processed successfully"
```

#### 4. **Verify Test Suite**
```
"Run the test suite using './gradlew test' and confirm all tests pass. If any fail, analyze the failures and fix the underlying issues."
```

**Integration tests and the cyoda binary.** `./gradlew check` and `./gradlew build` also run the integration
tests (`src/integrationTest`) against the cyoda-go pinned in `src/main/resources/cyoda/CYODA_VERSION`. The build
installs that binary automatically (the `installCyoda` task runs `scripts/install-cyoda.sh` into `.cyoda/bin`,
which survives `./gradlew clean` and is reused while it matches the pin). A `-dev` pin is built from source and
needs Go 1.26.7 or later, `git` and network access; a released pin is downloaded and needs network access. With
no way to install it, pass `-Dcyoda.bin=<path>` / set `CYODA_BIN` to a binary you have, or build with
`./gradlew build -x integrationTest`.

#### 5. **Code Quality Verification**
```
"Run code quality checks on the Java application:
- './gradlew compileJava' (compilation check)
- './gradlew checkstyleMain' (style checking, if configured)
- './gradlew build' (full build including tests)
Fix any critical issues found."
```

## 📋 **DETAILED TESTING WORKFLOWS**

### **Template: Full E2E Workflow Test for Your Application**

**Step 1: Application Discovery and Setup**
```
"Please analyze my Java application directory and help me test the complete Cyoda workflow:

1. Examine the src/main/java/com/java_template/application/ directory structure
2. Identify entity classes and their ENTITY_NAME constants
3. Find workflow JSON files in src/main/resources/workflow/ and their exact paths
4. Locate processor and criteria classes
5. Check REST API endpoints in controller classes

Then start the application using: ./gradlew bootRun"
```

**Step 2: Workflow Import with Correct Entity Names**
```
"Import workflows for my entities using the exact entity names from the ENTITY_NAME constants. For each entity:

1. Use workflow_mgmt_import_workflows_from_file_tool_cyoda-mcp with:
   - entity_name: [EXACT ENTITY_NAME from class]
   - model_version: "1"
   - file_path: "src/main/resources/workflow/[entity_type]/version_1/[EntityName].json"

2. Verify import success before proceeding"
```

**Step 3: Test via REST API (CRITICAL)**
```
"Test the workflow execution by creating entities via the REST API, NOT MCP tools:

1. Use curl or HTTP client to POST to /api/[entity_name]
2. Send proper JSON payload matching the entity fields
3. Monitor application logs for:
   - Criteria execution and results
   - Processor execution and completion
   - gRPC event processing
4. Verify entity reaches final workflow state
5. Check entity state via MCP tools to confirm completion"
```

**Expected Success Pattern:**
- ✅ Spring Boot application starts with all processors/criteria registered as beans
- ✅ Workflows import successfully with correct entity names
- ✅ REST API creates entities successfully
- ✅ Logs show criteria validation execution
- ✅ Logs show processor execution and completion
- ✅ Entities reach final workflow states (workflow state changes in metadata)
- ✅ All gRPC communication completes successfully
- ⚠️ **Note**: Processor business logic executes correctly even if entity data changes don't persist

### **Template: Code Quality Verification**

**Request:**
```
"Run comprehensive code quality checks on the Java application:

1. ./gradlew compileJava (compilation check)
2. ./gradlew compileTestJava (test compilation check)
3. ./gradlew test (run all tests)
4. ./gradlew build (full build including tests and packaging; it installs the pinned cyoda for the integration tests, see "Integration tests and the cyoda binary" above)
5. ./gradlew checkstyleMain (if checkstyle is configured)
6. ./gradlew runApp (run the application)" -- you need this command to run the application

Report results and fix any critical issues found."
```

**Expected Results:**
- ✅ Compilation: All Java files compile without errors
- ✅ Test compilation: All test files compile without errors
- ✅ Tests: All unit and integration tests pass
- ✅ Build: Full build completes successfully
- ✅ Style checks: Code follows established conventions (if configured)

### **Template: MCP Tools Functionality Testing**

**Request:**
```
"Test all MCP tools functionality with my application:

1. List available workflow files:
   workflow_mgmt_list_workflow_files_tool_cyoda-mcp(base_path="application/resources/workflow")

2. Validate workflow files for each entity:
   workflow_mgmt_validate_workflow_file_tool_cyoda-mcp(file_path="src/main/resources/workflow/[entity_type]/version_1/[EntityName].json")

3. Test entity search with conditions:
   search_search_cyoda-mcp(
       entity_model="[your_entity_model]",
       search_conditions={"type": "simple", "jsonPath": "$.[field_name]", "operatorType": "EQUALS", "value": "[test_value]"}
   )

4. Test entity retrieval:
   entity_get_entity_tool_cyoda-mcp(
       entity_model="[your_entity_model]",
       entity_id="[entity_id_from_previous_test]"
   )

5. List all entities:
   entity_list_entities_tool_cyoda-mcp(entity_model="[your_entity_model]")

Verify all tools work correctly and return expected results."
```

**Expected Results:**
- ✅ Workflow files listed correctly from src/main/resources/workflow directory
- ✅ Workflow validation passes for all entity workflows
- ✅ Search returns filtered results based on entity fields
- ✅ Entity retrieval works with proper entity IDs
- ✅ Entity listing shows all created entities
- ✅ No tool execution errors

### Performance and Load Testing

**Request:**
```
"Test the Java Cyoda application performance by creating multiple YourEntity instances rapidly and verify:
1. All entities are processed correctly
2. Each entity triggers appropriate processor based on isHappy field
3. No errors or timeouts occur
4. gRPC communication remains stable
5. Application logs show successful processing for all entities"
```

## 🔍 Verification Checklist

### Application Startup ✅
- [ ] Spring Boot application starts without errors
- [ ] Processors registered as Spring beans: `YourEntityProcessor`, `YourEntityProcessor`
- [ ] Criteria registered as Spring beans: `YourEntityIsHappyCriterion`, `YourEntityCriterion`
- [ ] gRPC connection established
- [ ] Entity discovery finds entity class mappings

### Workflow Import ✅
- [ ] YourEntity workflow imports successfully
- [ ] No import errors in logs
- [ ] Workflows available for entity processing

### Entity Processing ✅
- [ ] YourEntity entity creation succeeds via REST API
- [ ] Criteria validation executes and logs
- [ ] Processor executes and logs
- [ ] Appropriate processor selected based on entity state
- [ ] All gRPC events acknowledged

### Log Verification ✅
Look for these specific log patterns:
```
INFO com.java_template.application.criterion.YourEntityIsHappyCriterion : Checking if your_entity [entity-id] is happy
INFO com.java_template.application.processor.YourEntityProcessor : Processing happy your_entity [entity-id]
INFO com.java_template.application.processor.YourEntityProcessor : Processing gloomy your_entity [entity-id]
```

## 🚨 **COMMON ISSUES AND SOLUTIONS**

### **Issue: "Entity type not found" or "No workflow found"**
**Root Cause**: Entity name case mismatch between class and workflow import.

**AI Command:**
```
"I'm getting 'Entity type not found' errors. Please:
1. Check my entity class ENTITY_NAME constant: find the exact case used
2. Verify I'm using the EXACT same case in workflow import
3. Show me the correct workflow import command with proper entity_name
4. Confirm the workflow file path matches the entity directory structure"
```

### **Issue: "Workflow import succeeds but entities don't execute workflow"**
**Root Cause**: Workflow imported locally but not deployed to Cyoda environment.

**AI Command:**
```
"My workflow import reports success but entities created via MCP tools don't execute the workflow. Please:
1. Test workflow execution by creating entities via REST API instead of MCP tools
2. Use curl POST /api/[entity] with proper JSON payload
3. Monitor application logs for criteria and processor execution
4. Verify entities reach final workflow states"
```

### **Issue: "Processor/Criteria not registered"**
**Root Cause**: Components not registered as Spring beans or not implementing required interfaces.

**AI Command:**
```
"My processors/criteria aren't being registered. Please:
1. Verify processor/criteria classes are annotated with @Component
2. Check they implement CyodaProcessor/CyodaCriterion interfaces correctly
3. Ensure they're in the correct package (com.java_template.application.processor/criterion)
4. Restart the Spring Boot application and check startup logs"
```

### **Issue: "REST API returns 404 or 500 errors"**
**Root Cause**: Controller not registered or entity service issues.

**AI Command:**
```
"My REST API isn't working. Please:
1. Check that the controller is annotated with @RestController and @RequestMapping
2. Verify the controller is in the correct package (com.java_template.application.controller)
3. Confirm entity service is properly configured and injected
4. Test with a simple GET request first
5. Check Spring Boot application logs for startup errors"
```

### **Issue: "Tests failing"**
**Root Cause**: Various application configuration issues.

**AI Command:**
```
"Tests are failing. Please:
1. Run './gradlew test --info' to see detailed failures
2. Check if processors and criteria are registered correctly as Spring beans
3. Verify gRPC connection is established
4. Check test configuration and Spring context setup
5. Fix any configuration issues and re-run tests"
```

### **Issue: "MCP tools not responding"**
**Root Cause**: MCP server configuration or authentication issues.

**AI Command:**
```
"MCP tools aren't working. Please verify:
1. mcp-cyoda package is installed correctly
2. Environment variables are set: CYODA_CLIENT_ID, CYODA_CLIENT_SECRET, CYODA_HOST
3. MCP server configuration is correct in AI assistant settings
4. Test basic functionality with entity_list_entities_tool_cyoda-mcp"
```

### **Issue: "Workflow validation fails"**
**Root Cause**: Invalid JSON structure, missing required fields, or using reserved keywords.

**AI Command:**
```
"My workflow file validation is failing. Please:
1. Use workflow_mgmt_validate_workflow_file_tool_cyoda-mcp to check the file
2. Verify JSON syntax is correct
3. Check that initialState is 'initial' not 'none' (reserved keyword)
4. Ensure all transitions have explicit 'manual': true/false flags
5. Check that processor and criteria names match registered classes
6. Ensure all required workflow fields are present"
```

### **Issue: "Processors execute but entity data doesn't change"**
**Root Cause**: Known limitation - processor changes may not persist to entity data.

**AI Command:**
```
"My processors are executing and logging correctly, but entity data isn't updating. This is a known limitation. Please:
1. Verify processor execution via application logs (this confirms business logic works)
2. Check workflow state changes in entity metadata (current_state field)
3. Focus testing on processor execution confirmation rather than data persistence
4. Consider creating bridge processors to update business status fields based on workflow transitions"
```

## 📊 **SUCCESS METRICS & BENCHMARKS**

### **Performance Benchmarks**
- **Application startup**: < 30 seconds (Spring Boot with all beans)
- **Workflow import**: < 2 seconds per workflow
- **Entity creation via REST API**: < 1 second per entity
- **Workflow execution (criteria + processor)**: < 5 seconds per entity: You can see processors/criteria execution in logs
- **Test suite**: < 60 seconds total
- **End-to-end workflow**: < 10 seconds from creation to completion

### **Quality Standards**
- **Compilation**: No compilation errors (critical)
- **Tests**: All unit and integration tests passing (critical)
- **Build**: Full Gradle build completes successfully (critical)
- **Code style**: Follows established Java conventions
- **E2E workflow**: Complete success with proper logging

### **Workflow Execution Success Criteria**
- ✅ **Application Startup**: All processors and criteria registered as Spring beans
- ✅ **Workflow Import**: Success with correct entity names and "initial" state
- ✅ **Entity Creation**: Via REST API (not just MCP tools)
- ✅ **Criteria Execution**: Logged with results
- ✅ **Processor Execution**: Logged with completion status and business logic
- ✅ **State Progression**: Workflow state changes visible in entity metadata (current_state field)
- ✅ **gRPC Communication**: All events acknowledged
- ⚠️ **Data Persistence**: Focus on processor execution logs rather than entity data changes (known limitation)

## 🎯 **AI TESTING BEST PRACTICES**

### **1. Be Specific with Entity Names**
❌ **Avoid**: "Test the application with entities"
✅ **Use**: "Test workflow execution with [YourEntity] using exact ENTITY_NAME from the class definition"

### **2. Always Test via REST API**
❌ **Avoid**: "Create entities using MCP tools only"
✅ **Use**: "Create entities via POST /api/[entity] REST API to ensure proper workflow execution"

### **3. Request Comprehensive Verification**
Always ask AI to:
- Show complete application logs for criteria and processor execution
- Verify workflow state changes in entity metadata (current_state field)
- Confirm gRPC events are processed and acknowledged
- Check entity states via MCP tools after creation
- **Focus on processor execution logs** rather than entity data changes (known limitation)

### **4. Follow Incremental Testing Order**
Test components in this specific order:
1. **Application startup** - Verify processors/criteria registration as Spring beans
2. **Workflow import** - Use exact entity names from ENTITY_NAME constants with "initial" state
3. **REST API testing** - Create entities via HTTP endpoints
4. **Workflow execution** - Monitor logs for criteria/processor execution
5. **State verification** - Confirm workflow state changes in entity metadata
6. **Business logic verification** - Verify processor execution logs show correct calculations/logic
7. **Test suite** - Run full Gradle test suite

### **5. Comprehensive Error Handling**
When issues occur:
- Ask AI to show complete error messages and stack traces
- Request specific troubleshooting steps based on error type
- Verify fixes by re-running the complete workflow test
- Always test both success and failure scenarios

## 📝 **EXAMPLE AI CONVERSATION TEMPLATE**

**User:** "Please test my Java Cyoda application end-to-end using the application directory"

**AI Response:** "I'll run a complete E2E test of your Java Cyoda client application:

**1. Application Discovery:**
✅ Found entity: YourEntity with ENTITY_NAME = "YourEntity"
✅ Located workflow: src/main/resources/workflow/your_entity/version_1/YourEntity.json
✅ Found processors: YourEntityProcessor, YourEntityProcessor
✅ Found criteria: YourEntityIsHappyCriterion, YourEntityCriterion

**2. Application Startup:**
✅ Spring Boot application started successfully
✅ Processors registered as beans: YourEntityProcessor, YourEntityProcessor
✅ Criteria registered as beans: YourEntityIsHappyCriterion, YourEntityCriterion
✅ gRPC connection established

**3. Workflow Import (Critical - Using Exact Entity Name):**
✅ YourEntity workflow imported using entity_name="YourEntity" (matching ENTITY_NAME constant)
✅ Workflow deployment successful

**4. REST API Testing:**
✅ Happy YourEntity created via POST /api/your_entity: ID 2d1c56ec-3adf-11b2-b83e-e6f027731b46
✅ Gloomy YourEntity created via POST /api/your_entity: ID 3c74b7d8-3adf-11b2-b83e-e6f027731b46

**5. Workflow Execution Verification:**
✅ Happy YourEntity: YourEntityIsHappyCriterion → True → YourEntityProcessor → Success (logged)
✅ Gloomy YourEntity: YourEntityIsHappyCriterion → False → YourEntityCriterion → True → YourEntityProcessor → Success (logged)
✅ Both entities show workflow state progression in metadata
✅ Processor business logic executed correctly (confirmed via logs)

**6. Test Suite:**
✅ All unit and integration tests passed

**Test Summary: Complete Success ✅**
- ✅ Workflow deployment: Working with correct entity names and "initial" state
- ✅ REST API: Working for entity creation
- ✅ Workflow execution: Both happy and gloomy paths working (confirmed via logs)
- ✅ State management: Proper workflow state progression in metadata
- ✅ Processor execution: Business logic confirmed via application logs
- ✅ Test suite: All passing
- ⚠️ Note: Entity data persistence limitation acknowledged but doesn't affect core functionality"

## 📊 **REPORTING RESULTS**

### **Success Report Template**
```
✅ Java Cyoda E2E Test Results - [Your Application]:
- Application: Spring Boot started successfully with all processors/criteria registered as beans
- Workflow Import: [EntityName] workflow imported with correct entity_name and "initial" state
- REST API: Entity creation via POST /api/[entity] working
- Workflow Execution: [X] entities processed with complete workflow execution
- Criteria: All validations executed and logged
- Processors: All processors executed successfully and logged business logic
- State Management: Workflow state progression confirmed in entity metadata
- gRPC Communication: All events processed and acknowledged
- Test Suite: [X]/[X] passing
- Code Quality: Compilation, tests, and build passing
- Known Limitation: Entity data persistence limitation acknowledged but core workflow functionality verified
```

### **Issue Report Template**
```
❌ Cyoda Test Issues Found:
- Issue: [Description]
- Root Cause: [Entity name mismatch/Configuration issue/etc.]
- Component: [Workflow import/Routes API/Processor/etc.]
- Error: [Complete error message]
- Logs: [Relevant log entries]
- Fix Applied: [Specific solution implemented]
- Verification: [How fix was confirmed - re-run test results]
```

Use these templates when communicating test results. Always include specific entity names, file paths, and verification steps for your application.

---

## 🎯 **QUICK REFERENCE CHECKLIST**

### **Pre-Testing Setup**
- [ ] MCP tools installed and configured
- [ ] Environment variables set (CYODA_CLIENT_ID, CYODA_CLIENT_SECRET, CYODA_HOST)
- [ ] Application directory structure verified
- [ ] Entity classes have ENTITY_NAME constants defined

### **Critical Testing Steps**
- [ ] **Entity Name Verification**: Use EXACT case from ENTITY_NAME constant
- [ ] **Workflow Import**: Import using correct entity_name parameter
- [ ] **REST API Testing**: Create entities via POST /api/[entity] (not MCP tools)
- [ ] **Log Monitoring**: Watch for criteria and processor execution
- [ ] **State Verification**: Confirm entities reach final states ("[*]")
- [ ] **Test Suite**: Run and verify all tests pass

### **Success Indicators**
- [ ] Spring Boot application starts with all processors/criteria registered as beans
- [ ] Workflow import succeeds with correct entity names and "initial" state
- [ ] REST API creates entities successfully
- [ ] Application logs show criteria execution and results
- [ ] Application logs show processor execution and business logic completion
- [ ] Entities show workflow state progression in metadata (current_state field)
- [ ] All gRPC events are processed and acknowledged
- [ ] Test suite passes completely
- [ ] Processor business logic verified via logs (data persistence limitation acknowledged)

### **Common Pitfalls to Avoid**
- [ ] ❌ Using lowercase entity names when class uses PascalCase
- [ ] ❌ Using "none" as initial state (use "initial" instead)
- [ ] ❌ Missing explicit "manual": true/false flags on transitions
- [ ] ❌ Testing only with MCP tools instead of REST API
- [ ] ❌ Ignoring application logs during workflow execution
- [ ] ❌ Expecting entity data changes when processors execute (known limitation)
- [ ] ❌ Not verifying workflow state progression in metadata
- [ ] ❌ Skipping test suite verification

**Remember**: The most critical factors are:
1. Using the EXACT entity name from your ENTITY_NAME constant when importing workflows
2. Using "initial" as initial state, not "none"
3. Focusing on processor execution logs rather than entity data persistence
