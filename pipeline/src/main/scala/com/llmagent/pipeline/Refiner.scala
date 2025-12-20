package com.llmagent.pipeline

import com.llmagent.common.*
import com.llmagent.common.Agent.{AgentId, AgentDef, AgentResult}
import com.llmagent.common.observability.Types as ObsTypes
import com.llmagent.common.observability.{ConversationLogger, Types as ObsTypes2}
import java.time.Instant

/** Refiner - takes final agent output and refines for user */
final class Refiner(
  override val id: AgentId,
  conversationId: ObsTypes.ConversationId
) extends AgentDef[AgentOutput, UserOutput]:
  import AgentId.value as agentIdValue
  import ObsTypes.ConversationId.value as convIdValue

  private val agentName = "Refiner"

  // Constructor logging - writes to agent_logs/{conversationId}_Refiner.jsonl
  Logging.info(
    conversationId,
    Logging.Source.Agent,
    agentName,
    s"$agentName ${id.agentIdValue} created, conversationId: ${conversationId.convIdValue}"
  )

  override def process(input: AgentOutput): AgentResult[UserOutput] = {
    val startTime = System.currentTimeMillis()

    Logging.info(
      conversationId,
      Logging.Source.Agent,
      agentName,
      s"$agentName ${id.agentIdValue} starting refinement phase..."
    )

    Logging.info(
      conversationId,
      Logging.Source.Agent,
      agentName,
      s"$agentName ${id.agentIdValue} received input: toolExecutions=${input.toolExecutions}, latency=${input.totalLatencyMs}ms, summary=${input.summary.take(100).replace("\n", " ")}..."
    )

    val refined = refineResponse(input.summary)
    val stats = ExecutionStats(
      totalToolCalls = input.toolExecutions,
      totalLatencyMs = input.totalLatencyMs
    )

    Logging.info(
      conversationId,
      Logging.Source.Agent,
      agentName,
      s"$agentName ${id.agentIdValue} refined response: ${refined.length} chars (removed ${input.summary.length - refined.length} chars)"
    )

    val output = UserOutput(
      response = refined,
      stats = stats
    )

    val latencyMs = System.currentTimeMillis() - startTime

    Logging.info(
      conversationId,
      Logging.Source.Agent,
      agentName,
      s"$agentName ${id.agentIdValue} completed. Output: response=${refined.length} chars, toolCalls=${stats.totalToolCalls}, latency=${stats.totalLatencyMs}ms"
    )

    // Log to conversation logs so Refiner appears in conversation trail
    val activityLog = ObsTypes2.AgentActivityLog(
      id = ObsTypes.ExchangeId.generate(),
      conversationId = conversationId,
      agentId = id.agentIdValue,
      agentType = agentName,
      phase = "refinement",
      inputSummary = s"toolExecutions=${input.toolExecutions}, latency=${input.totalLatencyMs}ms, summaryLength=${input.summary.length}",
      outputSummary = s"responseLength=${refined.length}, toolCalls=${stats.totalToolCalls}, totalLatency=${stats.totalLatencyMs}ms",
      latencyMs = Types.LatencyMs(latencyMs),
      error = None,
      timestamp = Instant.now()
    )
    ConversationLogger.logAgentActivity(activityLog)

    AgentResult.Completed(output)
  }

  private def refineResponse(raw: String): String = {
    raw
      .trim
      .replaceAll("\\n{3,}", "\n\n")
  }

object Refiner:
  def create(conversationId: ObsTypes.ConversationId): Refiner =
    new Refiner(AgentId.generate(), conversationId)
