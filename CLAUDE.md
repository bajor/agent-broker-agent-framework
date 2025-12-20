# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
make              # Compile all modules (MUST run after every change)
make test         # Run tests
make distributed  # Launch all agents in distributed mode (via run-distributed.sh)
make send-prompt PROMPT=prime  # Send a prompt to the distributed pipeline

# Individual agent runners (DSL-based)
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

This is a multi-agent LLM pipeline system with typed agent-to-agent communication via RabbitMQ. All agents are built using the type-safe DSL.

### Module Dependency Graph

```
common (foundation types, config, logging, RabbitMQ, A2A protocol, ZIO, zio-json)
  ├── tools (LlmTool, PythonExecutorTool)
  ├── submit (submit CLI)
  └── dsl (Agent Pipeline DSL - AgentBuilder, Process, PipelineStep)
      └── examples (PreprocessorMain, CodeGenMain, ExplainerMain, RefinerMain, UserSubmit)
```

### Core Flow (Distributed)

Each agent runs independently as a separate process, communicating via RabbitMQ queues:

```
UserSubmit → [RabbitMQ] → Preprocessor → [RabbitMQ] → CodeGen
                                                          ↓
                            [Console Output] ← Refiner ← Explainer
```

Queue naming: `agent_<agentName>_tasks` (derived from AgentNames)

### Key Abstractions

#### Agent DSL (`dsl/src/main/scala/com/llmagent/dsl/`)

The DSL provides a type-safe, functional way to define agent pipelines:

**Process** (`Process.scala`):
- Composable transformation units that chain via `>>>`
- Constructors: `Process.pure`, `Process.effect`, `Process.withLlm`, `Process.withTool`
- Built-in reflection/retry support via `MaxReflections`

```scala
val pipeline = InitState >>> GenerateCode >>> ExecuteCode >>> Summarize
```

**AgentBuilder** (`AgentBuilder.scala`):
- Phantom-typed builder ensuring compile-time correctness
- `HasInput`/`HasOutput` phantom types enforce `readFrom`/`writeTo` called exactly once
- `.build` only compiles when both are configured

```scala
Agent("MyAgent")
  .readFrom(inputQueue, decoder)    // Transitions No → Yes for input
  .process(myPipeline)
  .writeTo(outputQueue, encoder)    // Transitions No → Yes for output
  .build                            // Only valid when both are Yes
```

**PipelineStep** (`PipelineStep.scala`):
- Kleisli arrow abstraction for composing effectful steps
- Threads `PipelineContext` through the pipeline
- Automatic logging integration

**Types** (`Types.scala`):
- Opaque types: `SourceQueue`, `DestQueue`, `MaxReflections`, `TraceId`
- ADTs: `PipelineResult` (Success/Failure/Rejected), `PromptSource`, `StepLog`
- `PipelineContext` - Immutable context with conversation ID, trace ID, step logs

**AgentRuntime** (`AgentRuntime.scala`):
- RabbitMQ integration for running agents
- Conversation ID propagation via A2A envelope
- Fiber-per-message parallelism

#### Core Types (`common/src/main/scala/com/llmagent/common/`)

**Agent.scala** - Foundation types:
- `AgentId` - Opaque type for agent identification
- `Tool[I, O]` - Trait for tool implementations
- `ToolResult[T]` - ADT for Success/Failure

**A2A Protocol Types**:
- `AgentTypes.scala` - Core message types: `UserInput`, `AgentInput`, `AgentOutput`, `UserOutput`
- `A2AJson.scala` - JSON serialization using zio-json with `A2AEnvelope`
- `AgentNames.scala` - Single source of truth for agent names (preprocessor, codegen, explainer, refiner)

#### Tools (`tools/src/main/scala/com/llmagent/tools/`)

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

The codebase uses advanced Scala 3 type features to make invalid states unrepresentable:

**Opaque Types**:
- `AgentId`, `TaskId`, `ConversationId`, `PromptId`, `SourceQueue`, `DestQueue`
- Validated constructors (e.g., `Port.apply` returns `Option[Port]`)
- Semantic types like `MaxReflections` prevent mixing incompatible values

**Phantom Types**:
- `AgentBuilder[I <: HasInput, O <: HasOutput, In, Out]` tracks builder state
- `BuilderState.Yes`/`No` encode whether `readFrom`/`writeTo` were called
- `.build` requires `I =:= Yes` and `O =:= Yes` evidence

**ADTs for Exhaustive Matching**:
- `GuardrailResult`, `ToolResult`, `PipelineResult`

**Kleisli Composition**:
- `Process` and `PipelineStep` compose via `>>>` operator
- Railway-oriented programming for error handling

## Creating New Agents

1. Define your processes:
```scala
val MyProcess = Process.withLlm[Input, Output](
  "MyProcess",
  buildPrompt = (input, ctx) => s"...",
  parseResponse = (input, response, ctx) => ...
)
```

2. Build the agent:
```scala
val agent = Agent("MyAgent")
  .readFrom(SourceQueue.fromAgentName("upstream"), decoder)
  .process(Process1 >>> Process2 >>> Process3)
  .writeTo(DestQueue.fromAgentName("downstream"), encoder)
  .build
```

3. Run with `AgentRuntime`:
```scala
object MyAgentMain extends ZIOAppDefault:
  override def run = AgentRuntime.run(agent)
```

## Prerequisites

- JDK 11+, sbt 1.9+
- Docker (for RabbitMQ)
- Ollama running locally (default model: configured in `Config.scala`)
- Python 3 (for `PythonExecutorTool`)
