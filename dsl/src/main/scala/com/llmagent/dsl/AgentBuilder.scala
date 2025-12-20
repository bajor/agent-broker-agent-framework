package com.llmagent.dsl

import zio.*
import zio.json.*
import com.llmagent.dsl.Types.*
import com.llmagent.common.Agent.{AgentId, Tool, ToolResult}
import com.llmagent.common.observability.Types.ConversationId
import com.llmagent.common.guardrails.Types.{Guardrail, PipelineSafetyConfig, GuardrailResult}

/**
 * Phantom types for compile-time enforcement of the builder protocol.
 *
 * An AgentBuilder must:
 * - Call readFrom exactly once (transitions HasInput from No to Yes)
 * - Call writeTo exactly once (transitions HasOutput from No to Yes)
 * - Only be runnable when both are Yes
 */
sealed trait BuilderState
sealed trait HasInput
sealed trait HasOutput

object BuilderState:
  sealed trait Yes extends HasInput with HasOutput
  sealed trait No extends HasInput with HasOutput

import BuilderState.*

/**
 * Type-safe builder for agent pipelines using phantom types.
 *
 * The type parameters encode the builder state:
 * - I: Input phantom (No = not set, Yes = set)
 * - O: Output phantom (No = not set, Yes = set)
 * - In: The actual input type
 * - Out: The actual output type
 *
 * Usage:
 * {{{
 * Agent("MyAgent")
 *   .readFrom(SourceQueue.fromAgentName("preprocessor"))
 *   .process(PromptSource.Literal("..."), MaxReflections.default)
 *   .useTool(myTool, "model", MaxReflections.default)
 *   .evaluate(evalFn)
 *   .guard(guardrails)
 *   .writeTo(DestQueue.fromAgentName("next"))
 * }}}
 */
final class AgentBuilder[I <: HasInput, O <: HasOutput, In, Out] private (
  val name: String,
  val inputQueue: Option[SourceQueue],
  val outputQueue: Option[DestQueue],
  val steps: PipelineStep[In, Out],
  val inputDecoder: Option[String => Either[String, In]],
  val outputEncoder: Option[Out => String]
):

  // ════════════════════════════════════════════════════════════════════════════
  // SOURCE & DESTINATION
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * Set the source queue for this agent.
   * Requires a decoder to parse messages from the queue.
   */
  def readFrom[A](
    queue: SourceQueue,
    decoder: String => Either[String, A]
  ): AgentBuilder[Yes, O, A, Out] =
    new AgentBuilder(
      name,
      Some(queue),
      outputQueue,
      // Cast is safe because we're changing the input type
      PipelineStep.identity[A].asInstanceOf[PipelineStep[A, Out]],
      Some(decoder),
      outputEncoder
    )

  /**
   * Set the destination queue for this agent.
   * Requires an encoder to serialize messages for the queue.
   */
  def writeTo(
    queue: DestQueue,
    encoder: Out => String
  ): AgentBuilder[I, Yes, In, Out] =
    new AgentBuilder(
      name,
      inputQueue,
      Some(queue),
      steps,
      inputDecoder,
      Some(encoder)
    )

  /**
   * Mark this as a terminal agent (no output queue).
   * The encoder is still needed for logging/final output.
   */
  def terminal(encoder: Out => String): AgentBuilder[I, Yes, In, Out] =
    new AgentBuilder(
      name,
      inputQueue,
      None,
      steps,
      inputDecoder,
      Some(encoder)
    )

  // ════════════════════════════════════════════════════════════════════════════
  // PROCESS - The main way to add pipeline steps
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * Add a Process to the pipeline.
   *
   * This is the primary way to build pipelines. Processes are composable
   * via `>>>` and can be chained:
   *
   * {{{
   * Agent("MyAgent")
   *   .readFrom(queue, decoder)
   *   .process(CleanInput >>> GenerateCode >>> ExecuteCode)
   *   .writeTo(outputQueue, encoder)
   *   .build
   * }}}
   *
   * Or added incrementally:
   *
   * {{{
   * Agent("MyAgent")
   *   .readFrom(queue, decoder)
   *   .process(CleanInput)
   *   .process(GenerateCode)
   *   .process(ExecuteCode)
   *   .writeTo(outputQueue, encoder)
   *   .build
   * }}}
   *
   * All process outputs are automatically logged with the conversation ID.
   */
  def process[B](p: Process[Out, B]): AgentBuilder[I, O, In, B] =
    new AgentBuilder(
      name,
      inputQueue,
      outputQueue,
      steps >>> p.toStep,
      inputDecoder,
      None
    )

  /**
   * Add guardrail validation.
   * Blocked content results in Rejected, not Failure.
   */
  def guard(
    guardrails: List[Guardrail],
    checkContent: (Out, List[Guardrail], PipelineContext) => ZIO[Any, Throwable, GuardrailResult]
  ): AgentBuilder[I, O, In, Out] =
    val step = PipelineStep[Out, Out](
      "guard",
      (out, ctx) =>
        if guardrails.isEmpty then
          ZIO.succeed(PipelineResult.Success(out, ctx))
        else
          checkContent(out, guardrails, ctx).foldZIO(
            failure = e => ZIO.succeed(PipelineResult.Failure(s"Guardrail check failed: ${e.getMessage}", ctx)),
            success = {
              case GuardrailResult.Passed =>
                ZIO.succeed(PipelineResult.Success(out, ctx))
              case GuardrailResult.Blocked(guardrailName, reason) =>
                ZIO.succeed(PipelineResult.Rejected(guardrailName, reason, ctx))
              case GuardrailResult.Error(msg) =>
                ZIO.succeed(PipelineResult.Failure(s"Guardrail error: $msg", ctx))
            }
          )
    ).logged

    new AgentBuilder(
      name,
      inputQueue,
      outputQueue,
      steps >>> step,
      inputDecoder,
      outputEncoder
    )

  // ════════════════════════════════════════════════════════════════════════════
  // BUILD
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * Build the final agent definition.
   * Only callable when both input and output are configured (phantom types enforce this).
   */
  def build(using
    ev1: I =:= Yes,
    ev2: O =:= Yes
  ): AgentDefinition[In, Out] =
    AgentDefinition(
      name = name,
      inputQueue = inputQueue.get, // Safe due to phantom types
      outputQueue = outputQueue,
      pipeline = steps,
      decoder = inputDecoder.get,
      encoder = outputEncoder.get
    )

object AgentBuilder:
  /**
   * Entry point for the DSL.
   * Returns a builder with no input or output configured.
   */
  def apply(name: String): AgentBuilder[No, No, Nothing, Nothing] =
    new AgentBuilder[No, No, Nothing, Nothing](
      name = name,
      inputQueue = None,
      outputQueue = None,
      steps = PipelineStep.identity[Nothing],
      inputDecoder = None,
      outputEncoder = None
    )

/**
 * Convenience object for fluent DSL usage.
 */
object Agent:
  def apply(name: String): AgentBuilder[No, No, Nothing, Nothing] =
    AgentBuilder(name)

/**
 * The final, runnable agent definition.
 * Created by calling .build() on a fully-configured AgentBuilder.
 */
final case class AgentDefinition[In, Out](
  name: String,
  inputQueue: SourceQueue,
  outputQueue: Option[DestQueue],
  pipeline: PipelineStep[In, Out],
  decoder: String => Either[String, In],
  encoder: Out => String
):
  import SourceQueue.value as sqValue
  import DestQueue.value as dqValue

  /** Check if this is a terminal agent (no output queue) */
  def isTerminal: Boolean = outputQueue.isEmpty

  /** Get the queue names for RabbitMQ setup */
  def inputQueueName: String = inputQueue.sqValue
  def outputQueueName: Option[String] = outputQueue.map(_.dqValue)

  /**
   * Execute the pipeline with the given input and context.
   */
  def execute(input: In, context: PipelineContext): ZIO[Any, Nothing, PipelineResult[Out]] =
    pipeline.run(input, context)

  /**
   * Execute from raw message bytes (for RabbitMQ integration).
   */
  def executeFromMessage(
    rawMessage: String,
    traceId: TraceId,
    conversationId: ConversationId
  ): ZIO[Any, Nothing, PipelineResult[Out]] =
    decoder(rawMessage) match
      case Right(input) =>
        val ctx = PipelineContext.initial(name, traceId, conversationId)
        execute(input, ctx)
      case Left(error) =>
        val ctx = PipelineContext.initial(name, traceId, conversationId)
        ZIO.succeed(PipelineResult.Failure(s"Failed to decode input: $error", ctx))

  /**
   * Encode the output for publishing to the output queue.
   */
  def encodeOutput(result: PipelineResult[Out]): Option[String] =
    result match
      case PipelineResult.Success(out, _) => Some(encoder(out))
      case _ => None

  /**
   * Create an envelope from the result for downstream agents.
   */
  def createEnvelope(result: PipelineResult[Out]): PipelineEnvelope[Out] =
    PipelineEnvelope.fromResult(result)
