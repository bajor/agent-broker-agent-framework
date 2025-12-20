package com.llmagent.dsl

import java.util.UUID
import zio.json.*
import com.llmagent.common.observability.Types.ConversationId
import com.llmagent.common.Agent.{AgentId, ToolResult}

/**
 * Core domain types for the Agent DSL.
 *
 * This module defines the foundational ADTs that make invalid states unrepresentable:
 * - Queue identifiers with compile-time safety
 * - Pipeline context that threads through all steps
 * - Step results that explicitly model success/failure/rejection
 * - Reflection configuration for retry loops
 */
object Types:

  // ════════════════════════════════════════════════════════════════════════════
  // QUEUE TYPES - Type-safe queue identifiers
  // ════════════════════════════════════════════════════════════════════════════

  /** Source queue - where the agent reads messages from */
  opaque type SourceQueue = String

  object SourceQueue:
    def apply(name: String): Option[SourceQueue] =
      if name.nonEmpty && name.matches("^[a-zA-Z0-9_-]+$") then Some(name)
      else None

    def unsafe(name: String): SourceQueue = name

    def fromAgentName(agentName: String): SourceQueue =
      s"agent_${agentName}_tasks"

    extension (q: SourceQueue)
      def value: String = q

  /** Destination queue - where the agent writes messages to */
  opaque type DestQueue = String

  object DestQueue:
    def apply(name: String): Option[DestQueue] =
      if name.nonEmpty && name.matches("^[a-zA-Z0-9_-]+$") then Some(name)
      else None

    def unsafe(name: String): DestQueue = name

    def fromAgentName(agentName: String): DestQueue =
      s"agent_${agentName}_tasks"

    extension (q: DestQueue)
      def value: String = q

  // ════════════════════════════════════════════════════════════════════════════
  // REFLECTION CONFIG - Controls retry/reflection behavior
  // ════════════════════════════════════════════════════════════════════════════

  /** Maximum number of reflections (self-critique loops) for a step */
  opaque type MaxReflections = Int

  object MaxReflections:
    val default: MaxReflections = 3
    val none: MaxReflections = 0

    def apply(n: Int): Option[MaxReflections] =
      if n >= 0 && n <= 10 then Some(n) else None

    def unsafe(n: Int): MaxReflections = n

    extension (m: MaxReflections)
      def value: Int = m
      def hasMore(current: Int): Boolean = current < m

  // ════════════════════════════════════════════════════════════════════════════
  // PIPELINE CONTEXT - Threading state through the pipeline
  // ════════════════════════════════════════════════════════════════════════════

  /** Unique trace ID for distributed tracing across agents */
  opaque type TraceId = String

  object TraceId:
    def generate(): TraceId = UUID.randomUUID().toString
    def unsafe(s: String): TraceId = s

    extension (t: TraceId)
      def value: String = t

  /**
   * Immutable context that flows through every pipeline step.
   * Contains all the metadata needed for logging, tracing, and error handling.
   */
  final case class PipelineContext(
    agentName: String,
    traceId: TraceId,
    conversationId: ConversationId,
    stepIndex: Int,
    stepLogs: Vector[StepLog]
  ):
    def nextStep: PipelineContext = copy(stepIndex = stepIndex + 1)

    def withLog(log: StepLog): PipelineContext =
      copy(stepLogs = stepLogs :+ log)

  object PipelineContext:
    def initial(agentName: String, traceId: TraceId, conversationId: ConversationId): PipelineContext =
      PipelineContext(agentName, traceId, conversationId, 0, Vector.empty)

  /** Log entry for a single step execution */
  final case class StepLog(
    stepName: String,
    stepIndex: Int,
    durationMs: Long,
    reflectionsUsed: Int,
    result: StepResultStatus
  )

  enum StepResultStatus:
    case Success
    case Failure(error: String)
    case Rejected(reason: String)

  // ════════════════════════════════════════════════════════════════════════════
  // PIPELINE RESULT - Explicit success/failure/rejection modeling
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * The result of a pipeline execution.
   *
   * - Success: Completed successfully with output
   * - Failure: Failed after exhausting retries (downstream sees failure message)
   * - Rejected: Guardrails blocked the content (downstream sees rejection message)
   *
   * All variants can be serialized and sent to downstream agents.
   */
  enum PipelineResult[+A]:
    case Success(value: A, ctx: PipelineContext)
    case Failure(error: String, ctx: PipelineContext)
    case Rejected(guardrailName: String, reason: String, ctx: PipelineContext)

    def isSuccess: Boolean = this match
      case Success(_, _) => true
      case _ => false

    def map[B](f: A => B): PipelineResult[B] = this match
      case Success(a, c) => Success(f(a), c)
      case Failure(e, c) => Failure(e, c)
      case Rejected(g, r, c) => Rejected(g, r, c)

    def flatMap[B](f: A => PipelineResult[B]): PipelineResult[B] = this match
      case Success(a, c) => f(a) match
        case Success(b, c2) => Success(b, c2)
        case Failure(e, c2) => Failure(e, c2)
        case Rejected(g, r, c2) => Rejected(g, r, c2)
      case Failure(e, c) => Failure(e, c)
      case Rejected(g, r, c) => Rejected(g, r, c)

    /** Unified accessor for the pipeline context across all variants */
    def context: PipelineContext = this match
      case Success(_, c) => c
      case Failure(_, c) => c
      case Rejected(_, _, c) => c

  object PipelineResult:
    def success[A](value: A, context: PipelineContext): PipelineResult[A] =
      Success(value, context)

    def failure[A](error: String, context: PipelineContext): PipelineResult[A] =
      Failure(error, context)

    def rejected[A](guardrailName: String, reason: String, context: PipelineContext): PipelineResult[A] =
      Rejected(guardrailName, reason, context)

  // ════════════════════════════════════════════════════════════════════════════
  // ENVELOPE - Message wrapper for downstream propagation
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * Wrapper that allows downstream agents to receive success, failure, or rejection.
   * Downstream agents can pattern match on this and propagate failures forward.
   */
  enum PipelineEnvelope[+A]:
    case Payload(value: A)
    case UpstreamFailure(agentName: String, error: String)
    case UpstreamRejection(agentName: String, guardrailName: String, reason: String)

    def isPayload: Boolean = this match
      case Payload(_) => true
      case _ => false

  object PipelineEnvelope:
    def fromResult[A](result: PipelineResult[A]): PipelineEnvelope[A] = result match
      case PipelineResult.Success(v, _) => Payload(v)
      case PipelineResult.Failure(e, ctx) => UpstreamFailure(ctx.agentName, e)
      case PipelineResult.Rejected(g, r, ctx) => UpstreamRejection(ctx.agentName, g, r)

  // ════════════════════════════════════════════════════════════════════════════
  // PROMPT SOURCE - Where prompts come from
  // ════════════════════════════════════════════════════════════════════════════

  /** Source for system prompts - can be literal, from database, or dynamic */
  enum PromptSource:
    case Literal(prompt: String)
    case FromRegistry(promptName: String)
    case Dynamic(f: PipelineContext => String)

    def resolve(ctx: PipelineContext): String = this match
      case Literal(p) => p
      case FromRegistry(name) =>
        // Will be resolved via PromptRegistry at runtime
        s"{{registry:$name}}"
      case Dynamic(f) => f(ctx)
