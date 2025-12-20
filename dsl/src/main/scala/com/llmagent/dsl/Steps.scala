package com.llmagent.dsl

import zio.*
import com.llmagent.dsl.Types.*
import com.llmagent.common.{Logging, Config}
import com.llmagent.common.Agent.{Tool, ToolResult}
import com.llmagent.common.observability.Types as ObsTypes
import com.llmagent.common.observability.{ObservableLlmClient, ConversationLogger}
import com.llmagent.common.guardrails.Types.{Guardrail, GuardrailResult}
import com.llmagent.tools.LlmTool

/**
 * Pre-built pipeline steps for common agent operations.
 *
 * These steps integrate with the existing logging infrastructure:
 * - LLM queries are logged via ObservableLlmClient (conversation_logs/)
 * - Step execution is logged via Logging (agent_logs/)
 * - All logs include the conversation ID for correlation
 */
object Steps:

  // ════════════════════════════════════════════════════════════════════════════
  // LLM STEPS - Query LLM with full logging
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * Create a step that queries the LLM.
   *
   * Logging behavior:
   * - Query/response logged to conversation_logs/{conversationId}.jsonl via ObservableLlmClient
   * - Step execution logged to agent_logs/{conversationId}_{agentName}.jsonl
   *
   * @param name Step name for logging
   * @param buildPrompt Function to build the prompt from input
   * @param parseResponse Function to parse LLM response into output
   * @param model LLM model to use
   * @param maxReflections Maximum self-critique iterations
   */
  def llmQuery[A, B](
    name: String,
    buildPrompt: (A, PipelineContext) => String,
    parseResponse: (A, String, PipelineContext) => B,
    model: String = Config.Ollama.defaultModel,
    maxReflections: MaxReflections = MaxReflections.default
  ): PipelineStep[A, B] =
    PipelineStep.withReflection[A, B](
      name,
      maxReflections
    )(
      execute = (input, ctx) =>
        ZIO.attempt {
          val prompt = buildPrompt(input, ctx)

          // Log the prompt being sent
          Logging.info(ctx.conversationId, Logging.Source.Agent, ctx.agentName,
            s"[$name] Sending LLM query (${prompt.length} chars)")

          // Query LLM via the observable client (logs to conversation_logs/)
          val result = ObservableLlmClient.queryRaw(
            prompt = prompt,
            conversationId = ctx.conversationId,
            model = model
          )

          // Also log to agent_logs for this specific agent
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
                s"[$name] LLM response received (${response.length} chars, ${result.latencyMs.value}ms)")
              parseResponse(input, response, ctx)

            case com.llmagent.common.LlmClient.QueryResult.Failure(error) =>
              Logging.logError(ctx.conversationId, Logging.Source.Agent, ctx.agentName,
                s"[$name] LLM query failed: $error")
              throw new RuntimeException(s"LLM query failed: $error")
        },
      onFailure = (input, error) =>
        // For reflection, we just return the same input - the prompt builder can check for errors
        input
    ).logged

  /**
   * Create a step that uses the LlmTool directly.
   * This preserves the exact behavior of the existing ToolAgent pattern.
   */
  def llmToolStep[A, B](
    name: String,
    prepareInput: (A, PipelineContext) => LlmTool.LlmInput,
    handleOutput: (A, LlmTool.LlmOutput) => B,
    maxReflections: MaxReflections = MaxReflections.default,
    onFailure: (A, String) => A = (a: A, _: String) => a
  ): PipelineStep[A, B] =
    PipelineStep.withReflection[A, B](
      name,
      maxReflections
    )(
      execute = (input, ctx) =>
        ZIO.attempt {
          val llmInput = prepareInput(input, ctx)

          Logging.info(ctx.conversationId, Logging.Source.Agent, ctx.agentName,
            s"[$name] Executing LlmTool (model: ${llmInput.model})")

          LlmTool.instance.execute(llmInput) match
            case ToolResult.Success(output) =>
              Logging.logLlmQuery(
                ctx.conversationId,
                ctx.agentName,
                llmInput.prompt,
                output.response,
                llmInput.model,
                output.latencyMs
              )
              Logging.info(ctx.conversationId, Logging.Source.Agent, ctx.agentName,
                s"[$name] LlmTool success (${output.latencyMs}ms)")
              handleOutput(input, output)

            case ToolResult.Failure(error) =>
              Logging.logError(ctx.conversationId, Logging.Source.Agent, ctx.agentName,
                s"[$name] LlmTool failed: $error")
              throw new RuntimeException(error)
        },
      onFailure = onFailure
    ).logged

  // ════════════════════════════════════════════════════════════════════════════
  // TOOL STEPS - Execute arbitrary tools with logging
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * Create a step that executes a tool.
   *
   * @param tool The tool to execute
   * @param prepareInput Function to prepare tool input from step input
   * @param handleOutput Function to transform tool output to step output
   * @param maxReflections Maximum retry iterations
   * @param onFailure Function to modify input on failure (for reflection)
   */
  def toolStep[A, TI, TO, B](
    tool: Tool[TI, TO],
    prepareInput: (A, PipelineContext) => TI,
    handleOutput: (A, TO) => B,
    maxReflections: MaxReflections = MaxReflections.default,
    onFailure: (A, String) => A = (a: A, _: String) => a
  ): PipelineStep[A, B] =
    PipelineStep.withReflection[A, B](
      s"tool:${tool.name}",
      maxReflections
    )(
      execute = (input, ctx) =>
        ZIO.attempt {
          val toolInput = prepareInput(input, ctx)

          Logging.info(ctx.conversationId, Logging.Source.Agent, ctx.agentName,
            s"[tool:${tool.name}] Executing...")

          tool.execute(toolInput) match
            case ToolResult.Success(output) =>
              Logging.info(ctx.conversationId, Logging.Source.Agent, ctx.agentName,
                s"[tool:${tool.name}] Success")
              handleOutput(input, output)

            case ToolResult.Failure(error) =>
              Logging.logError(ctx.conversationId, Logging.Source.Agent, ctx.agentName,
                s"[tool:${tool.name}] Failed: $error")
              throw new RuntimeException(error)
        },
      onFailure = onFailure
    ).logged

  // ════════════════════════════════════════════════════════════════════════════
  // GUARDRAIL STEPS - Safety validation with logging
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * Create a guardrail step that validates content against safety rules.
   * Uses LLM to check if content violates guardrails.
   *
   * Returns Rejected (not Failure) if guardrails block the content.
   */
  def guardrailStep[A](
    guardrails: List[Guardrail],
    extractContent: A => String,
    model: String = Config.Ollama.defaultModel
  ): PipelineStep[A, A] =
    PipelineStep[A, A](
      "guardrails",
      (input, ctx) =>
        if guardrails.isEmpty then
          Logging.info(ctx.conversationId, Logging.Source.Agent, ctx.agentName,
            "[guardrails] No guardrails configured, passing through")
          ZIO.succeed(PipelineResult.Success(input, ctx))
        else
          ZIO.attempt {
            Logging.info(ctx.conversationId, Logging.Source.Agent, ctx.agentName,
              s"[guardrails] Checking ${guardrails.size} guardrails...")

            val content = extractContent(input)
            val prompt = buildGuardrailPrompt(guardrails, content)

            val result = ObservableLlmClient.queryRaw(prompt, ctx.conversationId, model)

            // Log the guardrail check
            Logging.logLlmQuery(
              ctx.conversationId,
              ctx.agentName,
              prompt,
              result.result match
                case com.llmagent.common.LlmClient.QueryResult.Success(r) => r
                case com.llmagent.common.LlmClient.QueryResult.Failure(e) => s"ERROR: $e"
              ,
              model,
              result.latencyMs.value
            )

            result.result match
              case com.llmagent.common.LlmClient.QueryResult.Success(response) =>
                parseGuardrailResponse(response, guardrails)
              case com.llmagent.common.LlmClient.QueryResult.Failure(error) =>
                GuardrailResult.Error(error)
          }.map {
            case GuardrailResult.Passed =>
              Logging.info(ctx.conversationId, Logging.Source.Agent, ctx.agentName,
                "[guardrails] All guardrails passed")
              PipelineResult.Success(input, ctx)
            case GuardrailResult.Blocked(name, reason) =>
              Logging.info(ctx.conversationId, Logging.Source.Agent, ctx.agentName,
                s"[guardrails] Blocked by '$name': $reason")
              PipelineResult.Rejected(name, reason, ctx)
            case GuardrailResult.Error(msg) =>
              Logging.logError(ctx.conversationId, Logging.Source.Agent, ctx.agentName,
                s"[guardrails] Error: $msg")
              PipelineResult.Failure(s"Guardrail check error: $msg", ctx)
          }.catchAll { e =>
            ZIO.succeed(PipelineResult.Failure(s"Guardrail check failed: ${e.getMessage}", ctx))
          }
    ).logged

  private def buildGuardrailPrompt(guardrails: List[Guardrail], content: String): String =
    val rulesText = guardrails.zipWithIndex.map { case (rule, idx) =>
      s"""Rule ${idx + 1}: ${rule.name}
Description: ${rule.description}
Check: ${rule.checkPrompt}"""
    }.mkString("\n\n")

    s"""You are a content safety checker.

Your task is to check if the following content violates any guardrail rules.

=== GUARDRAIL RULES ===
$rulesText

=== CONTENT TO CHECK ===
$content

=== INSTRUCTIONS ===
Analyze the content against each guardrail rule.
If the content violates ANY rule, respond with:
STOP: [rule name] - [brief reason]

If the content passes ALL rules, respond with:
OK

Respond with ONLY "OK" or "STOP: [rule name] - [reason]". Nothing else."""

  private def parseGuardrailResponse(response: String, guardrails: List[Guardrail]): GuardrailResult =
    val trimmed = response.trim.toUpperCase
    if trimmed.startsWith("OK") then
      GuardrailResult.Passed
    else if trimmed.startsWith("STOP") then
      val rest = response.trim.stripPrefix("STOP:").stripPrefix("STOP").trim
      val parts = rest.split(" - ", 2)
      val ruleName = if parts.nonEmpty then parts(0).trim else "Unknown"
      val reason = if parts.length > 1 then parts(1).trim else "Guardrail violation"
      GuardrailResult.Blocked(ruleName, reason)
    else
      // Default to passed if response is unclear
      GuardrailResult.Passed

  // ════════════════════════════════════════════════════════════════════════════
  // TRANSFORMATION STEPS
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * Create a pure transformation step with logging.
   */
  def transform[A, B](name: String)(f: A => B): PipelineStep[A, B] =
    PipelineStep[A, B](
      name,
      (input, ctx) =>
        ZIO.attempt(f(input)).fold(
          e => {
            Logging.logError(ctx.conversationId, Logging.Source.Agent, ctx.agentName,
              s"[$name] Transform failed: ${e.getMessage}")
            PipelineResult.Failure(e.getMessage, ctx)
          },
          result => {
            Logging.info(ctx.conversationId, Logging.Source.Agent, ctx.agentName,
              s"[$name] Transform completed")
            PipelineResult.Success(result, ctx)
          }
        )
    ).logged

  /**
   * Create an identity step (pass-through with logging).
   */
  def passThrough[A](name: String): PipelineStep[A, A] =
    PipelineStep[A, A](
      name,
      (input, ctx) =>
        ZIO.succeed {
          Logging.info(ctx.conversationId, Logging.Source.Agent, ctx.agentName,
            s"[$name] Pass-through")
          PipelineResult.Success(input, ctx)
        }
    )

  // ════════════════════════════════════════════════════════════════════════════
  // UPSTREAM FAILURE HANDLING
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * Create a step that handles upstream failures gracefully.
   * Downstream agents use this to receive and propagate errors without crashing.
   */
  def handleUpstreamFailures[A, B](
    onPayload: (A, PipelineContext) => ZIO[Any, Throwable, B],
    onFailure: (String, String, PipelineContext) => B,
    onRejection: (String, String, String, PipelineContext) => B
  ): PipelineStep[PipelineEnvelope[A], B] =
    PipelineStep[PipelineEnvelope[A], B](
      "handleUpstream",
      (envelope, ctx) =>
        envelope match
          case PipelineEnvelope.Payload(a) =>
            Logging.info(ctx.conversationId, Logging.Source.Agent, ctx.agentName,
              "[handleUpstream] Processing payload")
            onPayload(a, ctx).fold(
              e => PipelineResult.Failure(e.getMessage, ctx),
              b => PipelineResult.Success(b, ctx)
            )

          case PipelineEnvelope.UpstreamFailure(agent, error) =>
            Logging.info(ctx.conversationId, Logging.Source.Agent, ctx.agentName,
              s"[handleUpstream] Propagating failure from $agent: $error")
            ZIO.succeed(PipelineResult.Success(onFailure(agent, error, ctx), ctx))

          case PipelineEnvelope.UpstreamRejection(agent, guardrail, reason) =>
            Logging.info(ctx.conversationId, Logging.Source.Agent, ctx.agentName,
              s"[handleUpstream] Propagating rejection from $agent: $guardrail - $reason")
            ZIO.succeed(PipelineResult.Success(onRejection(agent, guardrail, reason, ctx), ctx))
    ).logged
