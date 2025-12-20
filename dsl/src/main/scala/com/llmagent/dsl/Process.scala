package com.llmagent.dsl

import zio.*
import com.llmagent.dsl.Types.*
import com.llmagent.common.{Logging, Config}
import com.llmagent.common.Agent.ToolResult
import com.llmagent.common.observability.ObservableLlmClient

/**
 * Process - A reusable, named pipeline component.
 *
 * Processes are the building blocks of agent pipelines. Each process
 * encapsulates a single transformation or action that can be:
 * - Composed via `>>>`
 * - Named for clear logging
 * - Configured with reflection/retry behavior
 *
 * == Usage ==
 *
 * {{{
 * // Define reusable processes
 * val CleanInput = Process.pure[UserInput, AgentInput]("CleanInput") { input =>
 *   AgentInput(input.rawPrompt.trim, input.metadata)
 * }
 *
 * val GenerateCode = Process.withLlm[AgentInput, String](
 *   name = "GenerateCode",
 *   buildPrompt = (input, _) => s"Generate code for: ${input.taskDescription}",
 *   parseResponse = (_, response, _) => response
 * )
 *
 * // Compose processes
 * val pipeline = CleanInput >>> GenerateCode
 *
 * // Use in agent definition
 * Agent("MyAgent")
 *   .readFrom(queue, decoder)
 *   .process(CleanInput >>> GenerateCode)
 *   .writeTo(outputQueue, encoder)
 *   .build
 * }}}
 */
trait Process[-A, +B]:
  def name: String
  def maxReflections: MaxReflections
  def execute(input: A, ctx: PipelineContext): ZIO[Any, Throwable, B]

  /** Compose with another process */
  def >>>[C](next: Process[B, C]): Process[A, C] =
    Process.compose(this, next)

  /** Convert to a PipelineStep for integration with AgentBuilder */
  def toStep: PipelineStep[A, B] =
    PipelineStep[A, B](
      name,
      (input, ctx) =>
        import MaxReflections.{value as mrValue, hasMore}

        def loop(currentInput: A, reflections: Int): ZIO[Any, Nothing, PipelineResult[B]] =
          execute(currentInput, ctx).foldZIO(
            failure = e =>
              if maxReflections.hasMore(reflections) then
                Logging.info(ctx.conversationId, Logging.Source.Agent, ctx.agentName,
                  s"[$name] Reflection ${reflections + 1}/${maxReflections.mrValue}: ${e.getMessage}")
                loop(currentInput, reflections + 1)
              else
                Logging.logError(ctx.conversationId, Logging.Source.Agent, ctx.agentName,
                  s"[$name] Failed after ${maxReflections.mrValue} reflections: ${e.getMessage}")
                ZIO.succeed(PipelineResult.Failure(
                  s"Max reflections (${maxReflections.mrValue}) exceeded: ${e.getMessage}",
                  ctx
                )),
            success = result =>
              // Log the output of each process
              val outputSummary = result.toString.take(200)
              Logging.info(ctx.conversationId, Logging.Source.Agent, ctx.agentName,
                s"[$name] Output: $outputSummary${if result.toString.length > 200 then "..." else ""}")
              ZIO.succeed(PipelineResult.Success(result, ctx))
          )

        for
          startTime <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
          _ <- ZIO.succeed(
            Logging.info(ctx.conversationId, Logging.Source.Agent, ctx.agentName,
              s"[$name] Starting...")
          )
          result <- loop(input, 0)
          endTime <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
          _ <- ZIO.succeed(
            Logging.info(ctx.conversationId, Logging.Source.Agent, ctx.agentName,
              s"[$name] Completed in ${endTime - startTime}ms")
          )
        yield result
    )

object Process:

  // ════════════════════════════════════════════════════════════════════════════
  // CORE CONSTRUCTORS
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * Create a pure (no effects) transformation process.
   */
  def pure[A, B](processName: String)(f: A => B): Process[A, B] =
    new Process[A, B]:
      val name = processName
      val maxReflections = MaxReflections.none
      def execute(input: A, ctx: PipelineContext): ZIO[Any, Throwable, B] =
        ZIO.attempt(f(input))

  /**
   * Create an effectful process with ZIO.
   */
  def effect[A, B](processName: String, reflections: MaxReflections = MaxReflections.none)(
    f: (A, PipelineContext) => ZIO[Any, Throwable, B]
  ): Process[A, B] =
    new Process[A, B]:
      val name = processName
      val maxReflections = reflections
      def execute(input: A, ctx: PipelineContext): ZIO[Any, Throwable, B] =
        f(input, ctx)

  /**
   * Create an LLM-powered process with automatic logging.
   */
  def withLlm[A, B](
    processName: String,
    buildPrompt: (A, PipelineContext) => String,
    parseResponse: (A, String, PipelineContext) => B,
    model: String = Config.Ollama.defaultModel,
    reflections: MaxReflections = MaxReflections.default
  ): Process[A, B] =
    new Process[A, B]:
      val name = processName
      val maxReflections = reflections

      def execute(input: A, ctx: PipelineContext): ZIO[Any, Throwable, B] =
        ZIO.attempt {
          val prompt = buildPrompt(input, ctx)

          Logging.info(ctx.conversationId, Logging.Source.Agent, ctx.agentName,
            s"[$name] Sending LLM query (${prompt.length} chars)")

          val result = ObservableLlmClient.queryRaw(prompt, ctx.conversationId, model)

          result.result match
            case com.llmagent.common.LlmClient.QueryResult.Success(response) =>
              Logging.logLlmQuery(
                ctx.conversationId,
                ctx.agentName,
                prompt,
                response,
                model,
                result.latencyMs.value
              )
              Logging.info(ctx.conversationId, Logging.Source.Agent, ctx.agentName,
                s"[$name] LLM response (${response.length} chars, ${result.latencyMs.value}ms)")
              parseResponse(input, response, ctx)

            case com.llmagent.common.LlmClient.QueryResult.Failure(error) =>
              Logging.logError(ctx.conversationId, Logging.Source.Agent, ctx.agentName,
                s"[$name] LLM query failed: $error")
              throw new RuntimeException(s"LLM query failed: $error")
        }

  /**
   * Create a tool execution process.
   */
  def withTool[A, TI, TO, B](
    processName: String,
    tool: com.llmagent.common.Agent.Tool[TI, TO],
    prepareInput: (A, PipelineContext) => TI,
    handleOutput: (A, TO, PipelineContext) => B,
    reflections: MaxReflections = MaxReflections.default
  ): Process[A, B] =
    new Process[A, B]:
      val name = processName
      val maxReflections = reflections

      def execute(input: A, ctx: PipelineContext): ZIO[Any, Throwable, B] =
        ZIO.attempt {
          val toolInput = prepareInput(input, ctx)

          Logging.info(ctx.conversationId, Logging.Source.Agent, ctx.agentName,
            s"[$name] Executing tool: ${tool.name}")

          tool.execute(toolInput) match
            case ToolResult.Success(output) =>
              Logging.info(ctx.conversationId, Logging.Source.Agent, ctx.agentName,
                s"[$name] Tool success")
              handleOutput(input, output, ctx)
            case ToolResult.Failure(error) =>
              Logging.logError(ctx.conversationId, Logging.Source.Agent, ctx.agentName,
                s"[$name] Tool failed: $error")
              throw new RuntimeException(error)
        }

  /**
   * Create an identity process (pass-through).
   */
  def identity[A]: Process[A, A] =
    pure[A, A]("identity")(a => a)

  /**
   * Compose two processes sequentially.
   */
  private def compose[A, B, C](first: Process[A, B], second: Process[B, C]): Process[A, C] =
    new Process[A, C]:
      val name = s"${first.name} >>> ${second.name}"
      val maxReflections = MaxReflections.none

      def execute(input: A, ctx: PipelineContext): ZIO[Any, Throwable, C] =
        for
          b <- first.execute(input, ctx)
          c <- second.execute(b, ctx.nextStep)
        yield c

  /**
   * Create a conditional process.
   */
  def when[A](processName: String)(predicate: A => Boolean)(
    thenProcess: Process[A, A]
  ): Process[A, A] =
    new Process[A, A]:
      val name = processName
      val maxReflections = MaxReflections.none

      def execute(input: A, ctx: PipelineContext): ZIO[Any, Throwable, A] =
        if predicate(input) then thenProcess.execute(input, ctx)
        else ZIO.succeed(input)
