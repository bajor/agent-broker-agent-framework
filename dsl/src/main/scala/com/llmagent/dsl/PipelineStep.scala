package com.llmagent.dsl

import zio.*
import com.llmagent.dsl.Types.*
import com.llmagent.common.Agent.{Tool, ToolResult}
import com.llmagent.common.observability.Types.ConversationId
import com.llmagent.common.Logging

/**
 * PipelineStep - The core compositional unit of the agent pipeline.
 *
 * This is essentially a Kleisli arrow: A => ZIO[Any, Nothing, PipelineResult[B]]
 *
 * Key design decisions:
 * - Steps are pure functions wrapped in ZIO effects
 * - Errors are captured in PipelineResult, not ZIO's error channel
 * - Logging is woven in via the LogHook effect wrapper
 * - Steps compose via andThen (>>>) which threads context through
 *
 * The type signature ensures:
 * - Input type A is contravariant (consumed)
 * - Output type B is covariant (produced)
 * - All steps share the same effect type and error handling
 */
final case class PipelineStep[-A, +B](
  name: String,
  run: (A, PipelineContext) => ZIO[Any, Nothing, PipelineResult[B]]
):

  /**
   * Compose this step with another step (Kleisli composition).
   * The output of this step becomes the input of the next.
   * Context is threaded through and logging is automatic.
   */
  def andThen[C](next: PipelineStep[B, C]): PipelineStep[A, C] =
    PipelineStep(s"$name >>> ${next.name}", (a, ctx) =>
      run(a, ctx).flatMap {
        case PipelineResult.Success(b, ctx2) =>
          next.run(b, ctx2.nextStep)
        case PipelineResult.Failure(e, ctx2) =>
          ZIO.succeed(PipelineResult.Failure(e, ctx2))
        case PipelineResult.Rejected(g, r, ctx2) =>
          ZIO.succeed(PipelineResult.Rejected(g, r, ctx2))
      }
    )

  /** Alias for andThen */
  def >>>[C](next: PipelineStep[B, C]): PipelineStep[A, C] = andThen(next)

  /** Map over the output */
  def map[C](f: B => C): PipelineStep[A, C] =
    PipelineStep(name, (a, ctx) => run(a, ctx).map(_.map(f)))

  /** FlatMap - allows dependent step composition */
  def flatMap[C](f: B => PipelineStep[B, C]): PipelineStep[A, C] =
    PipelineStep(s"$name.flatMap", (a, ctx) =>
      run(a, ctx).flatMap {
        case PipelineResult.Success(b, ctx2) =>
          f(b).run(b, ctx2.nextStep)
        case PipelineResult.Failure(e, ctx2) =>
          ZIO.succeed(PipelineResult.Failure(e, ctx2))
        case PipelineResult.Rejected(g, r, ctx2) =>
          ZIO.succeed(PipelineResult.Rejected(g, r, ctx2))
      }
    )

  /** Add logging around this step execution */
  def logged: PipelineStep[A, B] =
    PipelineStep(name, (a, ctx) =>
      for
        startTime <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
        _ <- ZIO.succeed(
          Logging.info(ctx.conversationId, Logging.Source.Agent, ctx.agentName, s"[$name] Starting step ${ctx.stepIndex}")
        )
        result <- run(a, ctx)
        endTime <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
        duration = endTime - startTime
        status = result match
          case PipelineResult.Success(_, _) => StepResultStatus.Success
          case PipelineResult.Failure(e, _) => StepResultStatus.Failure(e)
          case PipelineResult.Rejected(_, r, _) => StepResultStatus.Rejected(r)
        log = StepLog(name, ctx.stepIndex, duration, 0, status)
        _ <- ZIO.succeed(
          Logging.info(ctx.conversationId, Logging.Source.Agent, ctx.agentName,
            s"[$name] Completed in ${duration}ms - ${status.productPrefix}")
        )
      yield result.map(identity) match
        case PipelineResult.Success(v, c) => PipelineResult.Success(v, c.withLog(log))
        case PipelineResult.Failure(e, c) => PipelineResult.Failure(e, c.withLog(log))
        case PipelineResult.Rejected(g, r, c) => PipelineResult.Rejected(g, r, c.withLog(log))
    )

object PipelineStep:

  /**
   * Create an identity step that passes input through unchanged.
   */
  def identity[A]: PipelineStep[A, A] =
    PipelineStep("identity", (a, ctx) => ZIO.succeed(PipelineResult.Success(a, ctx)))

  /**
   * Create a pure transformation step (no effects).
   */
  def pure[A, B](name: String)(f: A => B): PipelineStep[A, B] =
    PipelineStep(name, (a, ctx) =>
      ZIO.succeed(PipelineResult.Success(f(a), ctx))
    )

  /**
   * Create a step from an effectful function.
   */
  def fromZIO[A, B](name: String)(f: (A, PipelineContext) => ZIO[Any, Throwable, B]): PipelineStep[A, B] =
    PipelineStep(name, (a, ctx) =>
      f(a, ctx)
        .map(b => PipelineResult.Success(b, ctx))
        .catchAll(e => ZIO.succeed(PipelineResult.Failure(e.getMessage, ctx)))
    )

  /**
   * Create a step with reflection support (retry on failure).
   * The onFailure function receives the error message and produces a new input.
   */
  def withReflection[A, B](
    name: String,
    maxReflections: MaxReflections
  )(
    execute: (A, PipelineContext) => ZIO[Any, Throwable, B],
    onFailure: (A, String) => A
  ): PipelineStep[A, B] =
    PipelineStep(name, (a, ctx) =>
      def loop(input: A, reflections: Int): ZIO[Any, Nothing, PipelineResult[B]] =
        execute(input, ctx).foldZIO(
          failure = e =>
            if maxReflections.hasMore(reflections) then
              val newInput = onFailure(input, e.getMessage)
              Logging.info(ctx.conversationId, Logging.Source.Agent, ctx.agentName,
                s"[$name] Reflection ${reflections + 1}/${maxReflections.value}: ${e.getMessage}")
              loop(newInput, reflections + 1)
            else
              ZIO.succeed(PipelineResult.Failure(
                s"Max reflections (${maxReflections.value}) exceeded. Last error: ${e.getMessage}",
                ctx.withLog(StepLog(name, ctx.stepIndex, 0, reflections, StepResultStatus.Failure(e.getMessage)))
              )),
          success = b =>
            ZIO.succeed(PipelineResult.Success(b,
              ctx.withLog(StepLog(name, ctx.stepIndex, 0, reflections, StepResultStatus.Success))
            ))
        )
      loop(a, 0)
    )

  /**
   * Create a conditional step that only executes if the predicate is true.
   */
  def when[A](name: String)(predicate: A => Boolean)(step: PipelineStep[A, A]): PipelineStep[A, A] =
    PipelineStep(name, (a, ctx) =>
      if predicate(a) then step.run(a, ctx)
      else ZIO.succeed(PipelineResult.Success(a, ctx))
    )

  /**
   * Create a step that handles upstream failures/rejections gracefully.
   * Downstream agents use this to propagate errors forward without crashing.
   */
  def handleUpstream[A, B](name: String)(
    onPayload: (A, PipelineContext) => ZIO[Any, Nothing, PipelineResult[B]],
    onFailure: (String, String, PipelineContext) => ZIO[Any, Nothing, PipelineResult[B]],
    onRejection: (String, String, String, PipelineContext) => ZIO[Any, Nothing, PipelineResult[B]]
  ): PipelineStep[PipelineEnvelope[A], B] =
    PipelineStep(name, (envelope, ctx) =>
      envelope match
        case PipelineEnvelope.Payload(a) =>
          onPayload(a, ctx)
        case PipelineEnvelope.UpstreamFailure(agent, error) =>
          onFailure(agent, error, ctx)
        case PipelineEnvelope.UpstreamRejection(agent, guardrail, reason) =>
          onRejection(agent, guardrail, reason, ctx)
    )
