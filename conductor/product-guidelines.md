# Product Guidelines

This document outlines the core principles and conventions for the Agent Pipeline Framework project.

## Prose Style

- **Tone:** Clear, concise, and highly technical. All documentation should be direct and aimed at a developer audience.
- **Formatting:** Use GitHub-flavored Markdown for all documentation. Structure complex information using tables, code blocks, and lists to improve readability.
- **Emphasis:** Use bolding (`**Critical**`) for critical instructions that must be followed to avoid errors.

## Brand Messaging

The core messaging should consistently emphasize the framework's key value propositions:

- **Type-Safety:** Highlight how the use of Scala 3's advanced type system (phantom types, opaque types, etc.) prevents entire classes of bugs at compile time. The motto is: "make invalid states unrepresentable."
- **Composability:** Showcase the power and simplicity of the `>>>` operator for building complex pipelines from simple, reusable processes.
- **Scalability & Resilience:** Emphasize the distributed, asynchronous nature of the agent architecture using RabbitMQ, which allows for horizontal scaling and decoupled services.
- **Observability:** Point out the built-in structured logging and conversation tracing that provides end-to-end visibility into distributed agent workflows.

## Coding Style & Conventions

- **Language:** All new code must be written in Scala 3.
- **Functional & Type-Level:** Adhere to a functional programming style. Leverage the type system to enforce correctness and model the domain accurately.
- **Dependencies:** Keep external dependencies to a minimum. Prefer solutions within the ZIO ecosystem or the standard library.
- **Build Process:** All changes must be validated by running `make`. A change is not considered complete until the `make` command succeeds without any errors.
- **Modularity:** Respect the existing module structure (`common`, `dsl`, `tools`, etc.). Place new code in the appropriate module to maintain a clear separation of concerns.
- **Asynchronous Operations:** Use `ZIO` for all effectful and asynchronous operations.
- **JSON Handling:** Use `zio-json` with type-safe, compile-time derived codecs for all JSON serialization and deserialization.
- **Comments:** Add comments primarily to explain the *why* behind complex logic, especially for advanced type-level programming constructs. Avoid commenting on the *what*.
