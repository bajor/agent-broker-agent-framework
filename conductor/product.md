# Product Guide: Agent Pipeline Framework

## Initial Concept

This project is a type-safe, distributed multi-agent LLM pipeline system using Scala 3 with ZIO. Its core goal is to enable the creation of modular, scalable, and robust agent-based AI solutions through a powerful, functional, and type-safe DSL.

## Key Features

*   **Type-Safe Agent DSL:** Provides a compile-time safe Domain Specific Language for defining agent pipelines, ensuring correctness and reducing runtime errors.
*   **Composable Processes:** Allows for chaining operations with a `>>>` operator, making pipeline definitions clear, concise, and highly composable.
*   **Distributed Architecture:** Agents communicate asynchronously via RabbitMQ queues, facilitating horizontal scalability and decoupled service interactions.
*   **Automatic Observability:** Integrates structured logging and conversation tracking to provide end-to-end visibility across distributed agent interactions.
*   **Tool Integration:** Offers built-in support for integrating external tools, including LLM calls (via Ollama) and Python script execution, with mechanisms for custom tool development.
*   **Guardrails:** Incorporates safety validation features with configurable guardrail checks to ensure ethical and controlled agent behavior.

## Technology Stack

*   **Primary Language:** Scala 3
*   **Core Framework:** ZIO (for concurrent and asynchronous programming)
*   **Messaging System:** RabbitMQ
*   **LLM Integration:** Ollama
*   **JSON Serialization:** zio-json
*   **Build Tool:** sbt
*   **Scripting:** Python

## Architecture Overview

The system operates as a distributed multi-agent pipeline where each agent is an independent process. Communication between agents (e.g., UserSubmit, Preprocessor, CodeGen, Explainer, Refiner) occurs via RabbitMQ message queues. This microservices-like architecture ensures loose coupling, scalability, and resilience. The core logic for defining agent behavior is encapsulated within a type-safe DSL built on ZIO.
