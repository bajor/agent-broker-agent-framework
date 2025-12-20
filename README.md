# Agent Pipeline Framework

A type-safe, distributed multi-agent LLM pipeline system written in Scala 3 with ZIO.

## Features

- **Type-Safe Agent DSL**: Build agent pipelines with compile-time guarantees using phantom types
- **Composable Processes**: Chain operations with `>>>` for clear, readable pipelines
- **Distributed Architecture**: Agents communicate via RabbitMQ for horizontal scalability
- **Automatic Observability**: Structured logging with conversation tracking across agents
- **Tool Integration**: Built-in support for LLM calls, Python execution, and custom tools
- **Guardrails**: Safety validation with configurable guardrail checks

## Quick Start

### Prerequisites

- JDK 11+
- sbt 1.9+
- Docker (for RabbitMQ)
- Ollama running locally

### Build

```bash
make
```

### Setup

```bash
# Start RabbitMQ (runs in background)
make rabbit

# Ensure Ollama is running with your model
ollama run llama3.2
```

### Running the Distributed Pipeline

**Option 1: All-in-one** (starts all agents + sends prompt)
```bash
make distributed PROMPT="Write a Python function to check if a number is prime"
```

**Option 2: Individual terminals** (for development)

```bash
# Terminal 1-4: Start each agent
make run-preprocessor
make run-codegen
make run-explainer
make run-refiner

# Terminal 5: Send prompts
make send-prompt PROMPT="Calculate the first 10 Fibonacci numbers"
```

## Architecture

```
UserSubmit ─▶ Preprocessor ─▶ CodeGen ─▶ Explainer ─▶ Refiner (terminal)
    │              │             │            │            │
    └──────────────┴─────────────┴────────────┴────────────┘
                         RabbitMQ Queues
```

Each agent runs as an independent ZIO application, consuming from its input queue and publishing to the next agent's queue.

## Agent DSL

The framework provides a type-safe DSL for defining agent pipelines:

```scala
import com.llmagent.dsl.*

// Define reusable processes
val CleanInput = Process.pure[UserInput, AgentInput]("CleanInput") { input =>
  AgentInput(input.prompt.trim)
}

val GenerateCode = Process.withLlm[AgentInput, String](
  "GenerateCode",
  buildPrompt = (input, _) => s"Generate code for: ${input.taskDescription}",
  parseResponse = (_, response, _) => response
)

val ExecuteCode = Process.withTool[String, PythonInput, PythonOutput, Result](
  "ExecuteCode",
  tool = PythonExecutorTool.instance,
  prepareInput = (code, _) => PythonInput.unsafe(code),
  handleOutput = (_, output, _) => Result(output.stdout)
)

// Compose into a pipeline
val pipeline = CleanInput >>> GenerateCode >>> ExecuteCode

// Build the agent with compile-time validation
val agent = Agent("CodeGen")
  .readFrom(SourceQueue.fromAgentName("preprocessor"), decoder)
  .process(pipeline)
  .writeTo(DestQueue.fromAgentName("explainer"), encoder)
  .build  // Only compiles if readFrom AND writeTo are called
```

## Project Structure

```
agent-broker-agent-framework/
├── common/           # Foundation: types, config, RabbitMQ, logging, A2A protocol
├── dsl/              # Agent Pipeline DSL (AgentBuilder, Process, PipelineStep)
├── tools/            # Tool implementations (LlmTool, PythonExecutorTool)
├── pipeline/         # Agent definitions (Preprocessor, Refiner, etc.)
├── runners/          # Distributed agent runners (ZIOAppDefault entry points)
├── examples/         # Example agents using the DSL
├── submit/           # Submit CLI service
└── scripts/          # Utility scripts
```

## Make Commands

| Command | Description |
|---------|-------------|
| `make` | Compile all modules |
| `make test` | Run tests |
| `make distributed` | Launch all agents in distributed mode |
| `make send-prompt PROMPT=...` | Send a prompt to the pipeline |
| `make run-preprocessor` | Run the Preprocessor agent |
| `make run-codegen` | Run the CodeGen agent |
| `make run-explainer` | Run the Explainer agent |
| `make run-refiner` | Run the Refiner agent |
| `make rabbit` | Start RabbitMQ Docker container |
| `make rabbit-remove` | Stop and remove RabbitMQ container |
| `make submit` | Run the submit CLI service |
| `make clean` | Clean all build artifacts |

## Observability

All agent executions are logged to structured JSONL files:

- **Agent logs**: `agent_logs/{conversationId}_{agentName}.jsonl`
- **Conversation logs**: `conversation_logs/{conversationId}.jsonl`

Conversation IDs are automatically propagated through the A2A envelope, enabling end-to-end tracing across the distributed pipeline.

## License

See [LICENSE](LICENSE) for details.