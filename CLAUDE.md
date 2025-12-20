# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
make              # Compile all modules (MUST run after every change)
make test         # Run tests
make distributed  # Launch all agents in distributed mode (via run-distributed.sh)
make send-prompt PROMPT=prime  # Send a prompt to the distributed pipeline

# Individual agent runners
make run-preprocessor  # Run the Preprocessor agent
make run-codegen       # Run the CodeGen agent
make run-explainer     # Run the Explainer agent
make run-refiner       # Run the Refiner agent

# RabbitMQ
make rabbit        # Start RabbitMQ Docker container
make rabbit-remove # Stop and remove RabbitMQ container

make submit       # Run the submit CLI service
make clean        # Clean all build artifacts
```

**Critical**: Always run `make` after code changes and verify it completes with no errors before considering any change complete.

## Architecture Overview

This is a multi-agent LLM pipeline system with typed agent-to-agent communication via RabbitMQ.

### Module Dependency Graph

```
common (foundation types, config, logging, RabbitMQ, A2A protocol, ZIO, zio-json)
  ├── tools (LlmTool, PythonExecutorTool)
  ├── submit (submit CLI)
  └── pipeline (Preprocessor, Refiner, GuardedRefiner)
      └── runners (PreprocessorRunner, CodeGenRunner, ExplainerRunner, RefinerRunner)
```

### Core Flow (Distributed)

Each agent runs independently as a separate process, communicating via RabbitMQ queues:

```
UserSubmit → [RabbitMQ] → PreprocessorRunner → [RabbitMQ] → CodeGenRunner
                                                                   ↓
                              [Console Output] ← RefinerRunner ← ExplainerRunner
```

Queue naming: `agent_<agentName>_tasks` (derived from AgentNames)

### Key Abstractions

**Agent** (`common/src/main/scala/com/llmagent/common/Agent.scala`):
- `AgentDef[I, O]` - Base agent with typed input/output
- `ToolAgent[I, O]` - Agent with tool support, two-phase execution (tool phase + summarize phase)
- `ToolPhaseContext` - Tracks tool executions with reflection support (max 3 retries on failure)

**A2A Protocol Types** (`common/src/main/scala/com/llmagent/common/`):
- `AgentTypes.scala` - Core message types: `UserInput`, `AgentInput`, `AgentOutput`, `UserOutput`, `ExecutionStats`
- `A2AJson.scala` - JSON serialization using zio-json with `A2AEnvelope` for RabbitMQ messages
- `AgentNames.scala` - Single source of truth for agent names (preprocessor, codegen, explainer, refiner)
- `RunnerInfra.scala` - ZIO-based infrastructure for distributed runners with fiber-per-message parallelism

**Runners** (`runners/src/main/scala/com/llmagent/runners/`):
- Each runner extends `ZIOAppDefault` with its own `main`
- Uses `RunnerInfra.runAgent()` for RabbitMQ message handling
- Spawns a fiber per message for parallel processing

**Tools** (`tools/src/main/scala/com/llmagent/tools/`):
- `Tool[I, O]` trait with `ToolResult.Success` / `ToolResult.Failure`
- `LlmTool` - Calls Ollama with observability logging
- `PythonExecutorTool` - Executes Python code in subprocess with timeout

### JSON Serialization

Uses **zio-json** for type-safe, compile-time derived JSON codecs:
- All A2A message types use `derives JsonEncoder, JsonDecoder`
- `@jsonField("snake_case")` annotations for JSON field naming
- `A2AEnvelope.payload` is `zio.json.ast.Json` for flexible nested payloads

### Database Schemas

**prompts.db** - Prompt versioning with A/B testing:
- `prompts` table: id, name, description
- `prompt_versions` table: content (template with `{{input}}`), enabled flag for A/B selection

**guardrails.db** - Safety validation:
- `pipelines` table: name, description, allowed_scope
- `guardrails` table: check_prompt (LLM validates if output violates rules)

### Type-Level Design Patterns

The codebase uses opaque types extensively to make invalid states unrepresentable:
- `TaskId`, `AgentId`, `ConversationId`, `PromptId`, etc.
- Validated constructors (e.g., `Port.apply` returns `Option[Port]`)
- ADTs for exhaustive matching (`ResultStatus`, `GuardrailResult`, `ToolResult`)
- Semantic types like `Retries` and `ReflectionCount` prevent mixing different counters

## Prerequisites

- JDK 11+, sbt 1.9+
- Docker (for RabbitMQ)
- Ollama running locally (default model: configured in `Config.scala`)
- Python 3 (for `PythonExecutorTool`)
