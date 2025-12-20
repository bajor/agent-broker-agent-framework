package com.llmagent.pipeline

import com.llmagent.common.*
import com.llmagent.common.Agent.{AgentId, AgentDef, AgentResult, ToolAgent, Tool, ToolResult, ToolPhaseContext}
import com.llmagent.common.Types.QueryResult
import com.llmagent.common.guardrails.{GuardrailsRegistry, Types as GuardrailTypes}
import com.llmagent.common.guardrails.Types.{Guardrail, PipelineSafetyConfig, GuardrailResult}
import com.llmagent.common.observability.Types as ObsTypes
import com.llmagent.tools.LlmTool

/** Output when guardrail blocks content */
final case class BlockedOutput(
  reason: String,
  guardrailName: String
)

/** Guarded Refiner - validates output against guardrails before presenting to user */
final class GuardedRefiner(
  override val id: AgentId,
  pipelineName: String,
  conversationId: ObsTypes.ConversationId,
  agentName: String,
  val model: String = Config.Ollama.defaultModel
) extends ToolAgent[AgentOutput, UserOutput]:
  import AgentId.value as agentIdValue
  import ObsTypes.ConversationId.value as convIdValue

  override def tools: List[Tool[?, ?]] = List(LlmTool.instance)

  Logging.info(conversationId, Logging.Source.Agent, agentName, s"Created with model: $model, conversationId: ${conversationId.convIdValue}")

  private var guardrailResult: GuardrailResult = GuardrailResult.Passed
  private var safetyConfig: Option[PipelineSafetyConfig] = None
  private var guardrails: List[Guardrail] = Nil

  override protected def runToolPhase(input: AgentOutput, context: ToolPhaseContext): Unit =
    Logging.info(
      conversationId,
      Logging.Source.Agent,
      agentName,
      s"${id.agentIdValue} checking guardrails for pipeline: $pipelineName"
    )

    // Load pipeline safety config and guardrails
    GuardrailsRegistry.getSafetyConfigByName(pipelineName) match
      case QueryResult.Success(config) =>
        safetyConfig = Some(config)
        Logging.info(conversationId, Logging.Source.Agent, agentName, s"Loaded pipeline safety config: ${config.name}")

        GuardrailsRegistry.getEnabledGuardrails(config.id) match
          case QueryResult.Success(rules) =>
            guardrails = rules
            Logging.info(conversationId, Logging.Source.Agent, agentName, s"Loaded ${rules.size} guardrails")

            if rules.isEmpty then
              guardrailResult = GuardrailResult.Passed
            else
              guardrailResult = checkAllGuardrails(input, config, rules, context)

          case QueryResult.NotFound(msg) =>
            Logging.info(conversationId, Logging.Source.Agent, agentName, s"No guardrails found: $msg")
            guardrailResult = GuardrailResult.Passed

          case QueryResult.Error(msg) =>
            Logging.logError(conversationId, Logging.Source.Agent, agentName, s"Failed to load guardrails: $msg")
            guardrailResult = GuardrailResult.Error(msg)

      case QueryResult.NotFound(_) =>
        Logging.info(conversationId, Logging.Source.Agent, agentName, s"Pipeline safety config not found, skipping guardrails")
        guardrailResult = GuardrailResult.Passed

      case QueryResult.Error(msg) =>
        Logging.logError(conversationId, Logging.Source.Agent, agentName, s"Failed to load pipeline safety config: $msg")
        guardrailResult = GuardrailResult.Error(msg)

  override protected def summarize(input: AgentOutput, context: ToolPhaseContext): AgentResult[UserOutput] =
    guardrailResult match
      case GuardrailResult.Passed =>
        Logging.info(conversationId, Logging.Source.Agent, agentName, s"${id.agentIdValue} passed all guardrails")
        val refined = refineResponse(input.summary)
        val stats = ExecutionStats(
          totalToolCalls = input.toolExecutions,
          totalLatencyMs = input.totalLatencyMs
        )
        AgentResult.Completed(UserOutput(response = refined, stats = stats))

      case GuardrailResult.Blocked(name, reason) =>
        Logging.info(conversationId, Logging.Source.Agent, agentName, s"${id.agentIdValue} blocked by guardrail: $name")
        val blockedResponse = formatBlockedResponse(name, reason)
        val stats = ExecutionStats(
          totalToolCalls = input.toolExecutions,
          totalLatencyMs = input.totalLatencyMs
        )
        AgentResult.Completed(UserOutput(response = blockedResponse, stats = stats))

      case GuardrailResult.Error(msg) =>
        Logging.logError(conversationId, Logging.Source.Agent, agentName, s"Guardrail error: $msg")
        AgentResult.Failed(s"Guardrail check failed: $msg")

  private def checkAllGuardrails(
    input: AgentOutput,
    config: PipelineSafetyConfig,
    rules: List[Guardrail],
    context: ToolPhaseContext
  ): GuardrailResult =
    val combinedPrompt = buildGuardrailCheckPrompt(input, config, rules)

    val llmInput = LlmTool.LlmInput(
      combinedPrompt,
      model,
      conversationId
    )

    val result = executeTool(
      LlmTool.instance,
      llmInput,
      _ => llmInput,
      context
    )

    result match
      case ToolResult.Success(output: LlmTool.LlmOutput) =>
        parseGuardrailResponse(output.response, rules)
      case ToolResult.Failure(error) =>
        GuardrailResult.Error(s"LLM check failed: $error")

  private def buildGuardrailCheckPrompt(
    input: AgentOutput,
    config: PipelineSafetyConfig,
    rules: List[Guardrail]
  ): String =
    val rulesText = rules.zipWithIndex.map { case (rule, idx) =>
      s"""Rule ${idx + 1}: ${rule.name}
Description: ${rule.description}
Check: ${rule.checkPrompt}"""
    }.mkString("\n\n")

    s"""You are a content safety checker for a ${config.name} pipeline.

Pipeline description: ${config.description}
Allowed scope: ${config.allowedScope}

Your task is to check if the following output violates any guardrail rules.

=== GUARDRAIL RULES ===
$rulesText

=== OUTPUT TO CHECK ===
${input.summary}

=== INSTRUCTIONS ===
Analyze the output against each guardrail rule.
If the output violates ANY rule, respond with:
STOP: [rule name] - [brief reason]

If the output passes ALL rules and is appropriate for the pipeline's scope, respond with:
OK

Respond with ONLY "OK" or "STOP: [rule name] - [reason]". Nothing else."""

  private def parseGuardrailResponse(response: String, rules: List[Guardrail]): GuardrailResult =
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
      GuardrailResult.Passed

  private def refineResponse(raw: String): String =
    raw
      .trim
      .replaceAll("\\n{3,}", "\n\n")

  private def formatBlockedResponse(guardrailName: String, reason: String): String =
    s"""I'm sorry, but I cannot provide this response.

The output was blocked by safety guardrail: $guardrailName

Reason: $reason

Please try a different request that falls within the allowed scope of this service."""

object GuardedRefiner:
  def create(
    pipelineName: String,
    conversationId: ObsTypes.ConversationId,
    agentName: String,
    model: String = Config.Ollama.defaultModel
  ): GuardedRefiner =
    new GuardedRefiner(AgentId.generate(), pipelineName, conversationId, agentName, model)
