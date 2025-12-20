package com.llmagent

/**
 * Agent Pipeline DSL - A type-safe, functional DSL for building agent pipelines.
 *
 * == Overview ==
 *
 * This DSL provides a composable, type-safe way to define agent pipelines that:
 * - Read from RabbitMQ queues
 * - Process messages through configurable steps
 * - Execute tools with reflection support
 * - Apply guardrails for safety validation
 * - Write to output queues or act as terminal agents
 *
 * == Quick Start ==
 *
 * {{{
 * import com.llmagent.dsl.*
 * import com.llmagent.dsl.Types.*
 * import com.llmagent.dsl.Steps.*
 *
 * val myAgent = Agent("MyAgent")
 *   .readFrom(
 *     SourceQueue.fromAgentName("upstream"),
 *     myDecoder
 *   )
 *   .process(PromptSource.Literal("System prompt..."), MaxReflections.default)(
 *     (prompt, input, ctx) => processWithLlm(prompt, input),
 *     (input, error) => input // retry with same input
 *   )
 *   .writeTo(
 *     DestQueue.fromAgentName("downstream"),
 *     myEncoder
 *   )
 *   .build
 *
 * // Run the agent
 * Runtime.run(myAgent)
 * }}}
 *
 * == Key Concepts ==
 *
 * '''PipelineStep''': The core compositional unit. Steps are Kleisli arrows
 * that compose via `>>>` and thread `PipelineContext` through the pipeline.
 *
 * '''AgentBuilder''': A type-safe builder using phantom types to ensure
 * `readFrom` and `writeTo` are called exactly once.
 *
 * '''PipelineContext''': Immutable context containing conversation ID, trace ID,
 * and step logs. Propagated through all steps and to downstream agents.
 *
 * '''PipelineResult''': ADT representing Success, Failure, or Rejection.
 * Failures and rejections are propagated to downstream agents.
 *
 * == Logging ==
 *
 * The DSL integrates with the existing logging system:
 * - Agent logs: `agent_logs/{conversationId}_{agentName}.jsonl`
 * - Conversation logs: `conversation_logs/{conversationId}.jsonl`
 * - LLM query logs include prompt, response, model, and duration
 *
 * Conversation IDs are automatically propagated through the A2A envelope.
 */
package object dsl:

  // Re-export main types for convenience
  export Types.SourceQueue
  export Types.DestQueue
  export Types.MaxReflections
  export Types.TraceId
  export Types.PipelineContext
  export Types.PipelineResult
  export Types.PipelineEnvelope
  export Types.PromptSource
  export Types.StepLog
  export Types.StepResultStatus

  // Type aliases for cleaner imports
  // Note: Objects like Agent, AgentBuilder, Runtime, Steps, Process, PipelineStep
  // are automatically available via `import com.llmagent.dsl.*` since they're
  // defined in the same package. No need to re-export them here.
