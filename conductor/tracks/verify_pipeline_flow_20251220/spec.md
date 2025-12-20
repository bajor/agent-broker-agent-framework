# Spec: Verify and Document the Core Distributed Agent Pipeline Flow

## Objective

This track aims to verify and document the end-to-end functionality of the core distributed agent pipeline. The primary goal is to ensure that the system behaves as described in the `README.md`, providing a stable baseline for future development and a clear, verifiable example of the system in action.

## Key Deliverables

1.  **Successful Pipeline Execution:** A verifiable, successful run of the distributed agent pipeline using the `make distributed` and `make send-prompt` commands.
2.  **Observability Confirmation:** Confirmation that logs are generated correctly for the executed pipeline run, with a single `conversationId` tying all agent interactions together.
3.  **Documentation:** A new document, `docs/core-pipeline-verification.md`, that details the steps to reproduce the verification, explains the expected output at each stage, and provides guidance on how to interpret the logs.

## Functional Requirements

- The `make distributed` command must start all required agents (Preprocessor, CodeGen, Explainer, Refiner) without errors.
- The `make send-prompt` command must successfully submit a prompt to the `agent_preprocessor_tasks` RabbitMQ queue.
- Each agent in the pipeline must consume a message from its input queue and produce a message to its output queue (or final output, in the case of the Refiner).
- The entire process must be traceable via a consistent `conversationId` in the `conversation_logs/` and `agent_logs/` directories.
- The final output from the `Refiner` agent must be displayed in the console and be consistent with the initial prompt.

## Non-Functional Requirements

- **Reproducibility:** The verification process must be repeatable by any developer with the project prerequisites installed.
- **Clarity:** The final documentation should be clear enough for a new developer to understand the core pipeline flow without needing to read the source code.
