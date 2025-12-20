# LLM Agent

Distributed LLM task processing system written in Scala 3.

## Prerequisites

- JDK 11+
- sbt 1.9+
- Docker (for RabbitMQ)
- Ollama running locally

## Build

```bash
make
```

## Setup

```bash
# Start RabbitMQ (runs in background)
make rabbit

# Ensure Ollama is running with your model
ollama run llama3.2
```

## Running

**Terminal 1 - Start the Worker** (processes LLM tasks):
```bash
make worker
```

**Terminal 2 - Start the Submit Service** (submit prompts):
```bash
make submit
```

## Usage

Commands in the submit service:

```
submit <prompt>  - Submit a prompt to the LLM queue
status <id>      - Check result by task ID
list             - List all received results
help             - Show help
quit             - Exit the service
```

Example session:
```
> submit What is Scala?
Task submitted with ID: a1b2c3d4-...
Use 'status <id>' to check the result

> status a1b2c3d4-...
Task a1b2c3d4-... - SUCCESS
Response:
Scala is a general-purpose programming language...

> quit
```

## Architecture

```
Submit Service ──> RabbitMQ (llm_tasks) ──> Worker Service ──> Ollama LLM
                          │                        │
                          └── (llm_results) <──────┘
```

## Project Structure

```
common/                     Shared library module
  src/main/scala/
    com/llmagent/common/
      Config.scala          Type-safe configuration
      Messages.scala        Message types with opaque types
      Logging.scala         Structured logging
      LlmClient.scala       Ollama HTTP client
      RabbitMQ.scala        RabbitMQ client wrapper

worker/                     Worker service module
  src/main/scala/
    com/llmagent/worker/
      Worker.scala          Task processing logic
      Main.scala            Entry point

submit/                     Submit service module
  src/main/scala/
    com/llmagent/submit/
      Submit.scala          CLI and task submission
      ResultStore.scala     Thread-safe result cache
      ResultListener.scala  Background result consumer
      Main.scala            Entry point
```

## Make Commands

- `make` / `make build` - Compile all modules
- `make worker` - Run worker service
- `make submit` - Run submit service
- `make rabbit` - Start RabbitMQ container
- `make rabbit-stop` - Stop RabbitMQ container
- `make test` - Run tests
- `make clean` - Clean build artifacts
