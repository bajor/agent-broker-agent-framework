package com.llmagent.common

import zio.json.*

/** Agent output - universal exchange format for agent-to-agent communication */
final case class AgentOutput(
  summary: String,
  toolExecutions: Int,
  totalLatencyMs: Long
) derives JsonEncoder, JsonDecoder

/** Agent input - task description passed between agents */
final case class AgentInput(
  taskDescription: String,
  context: Map[String, String] = Map.empty
) derives JsonEncoder, JsonDecoder

/** Raw user input before preprocessing - entry point to the pipeline */
final case class UserInput(
  rawPrompt: String,
  metadata: Map[String, String] = Map.empty
) derives JsonEncoder, JsonDecoder

/** Execution statistics */
final case class ExecutionStats(
  totalToolCalls: Int,
  totalLatencyMs: Long
) derives JsonEncoder, JsonDecoder

/** Final output refined for user presentation */
final case class UserOutput(
  response: String,
  stats: ExecutionStats
) derives JsonEncoder, JsonDecoder
