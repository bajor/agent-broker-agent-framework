package com.llmagent.runners

import com.llmagent.common.*
import com.llmagent.common.Agent.{AgentId, ToolAgent, Tool, ToolResult, ToolPhaseContext, AgentResult}
import com.llmagent.common.Config as AppConfig
import com.llmagent.common.observability.Types as ObsTypes
import com.llmagent.common.RunnerInfra.{ProcessResult, RunnerConfig}
import com.llmagent.tools.LlmTool
import zio.*

/** Explainer Agent - explains code execution results using LLM
  * Takes AgentOutput from CodeGen, produces AgentOutput with explanation
  */
object ExplainerRunner extends ZIOAppDefault:

  private val config = RunnerConfig(
    agentName = AgentNames.explainer,
    inputQueue = s"agent_${AgentNames.explainer}_tasks",
    outputQueue = Some(s"agent_${AgentNames.refiner}_tasks")
  )

  private lazy val agentId: AgentId = AgentId.unsafe(config.agentName)

  /** The explainer agent - uses LLM to explain code execution results */
  private class ExplainerAgent(
    override val id: AgentId,
    conversationId: ObsTypes.ConversationId,
    model: String
  ) extends ToolAgent[AgentOutput, AgentOutput]:

    override def tools: List[Tool[?, ?]] = List(LlmTool.instance)

    private var explanation: String = ""
    private var explainLatency: Long = 0L

    override protected def runToolPhase(input: AgentOutput, context: ToolPhaseContext): Unit =
      val prompt = s"""You are explaining the result of a Python code execution to the user.

Here is the execution summary:

${input.summary}

Please provide a clear, concise explanation that includes:
1. What the code does (brief description)
2. What the result means in the context of the original task
3. If there was an error, explain what went wrong

Keep your explanation friendly and easy to understand. Focus on the actual result and its meaning."""

      val llmInput = LlmTool.LlmInput(prompt, model, conversationId)

      executeTool(LlmTool.instance, llmInput, feedback =>
        LlmTool.LlmInput(s"Please try again to explain the result.\n\n$prompt", model, conversationId),
        context
      ) match
        case ToolResult.Success(output: LlmTool.LlmOutput) =>
          explanation = output.response
          explainLatency = output.latencyMs
        case ToolResult.Failure(error) =>
          explanation = s"Failed to generate explanation: $error"

    override protected def summarize(input: AgentOutput, context: ToolPhaseContext): AgentResult[AgentOutput] =
      val totalLatency = input.totalLatencyMs + explainLatency

      val summary = s"""${input.summary}

## Explanation
$explanation

## Total Timing
- Previous stages: ${input.totalLatencyMs}ms
- Explanation: ${explainLatency}ms
- Total: ${totalLatency}ms"""

      AgentResult.Completed(AgentOutput(
        summary = summary,
        toolExecutions = input.toolExecutions + context.size,
        totalLatencyMs = totalLatency
      ))

  private def handler(
    envelope: A2AJson.A2AEnvelope,
    convId: ObsTypes.ConversationId
  ): ZIO[Any, Throwable, ProcessResult] =
    ZIO.attempt {
      A2AJson.decodeAgentOutputFromEnvelope(envelope) match
        case Some(agentOutput) =>
          val agent = new ExplainerAgent(agentId, convId, AppConfig.Ollama.defaultModel)

          agent.process(agentOutput) match
            case AgentResult.Completed(output) =>
              val outputEnvelope = A2AJson.createEnvelope(
                from = agentId,
                to = AgentNames.refiner,
                traceId = envelope.traceId,
                conversationId = convId,
                payload = output,
                encoder = A2AJson.encodeAgentOutput,
                payloadType = A2AJson.PayloadTypes.AgentOutput
              )
              ProcessResult.Success(Some(outputEnvelope))

            case AgentResult.Failed(error) =>
              ProcessResult.Failure(s"Explanation failed: $error")

        case None =>
          ProcessResult.Failure("Failed to decode AgentOutput from payload")
    }

  override def run: ZIO[Any, Throwable, Nothing] =
    RunnerInfra.runAgent(config, handler)
