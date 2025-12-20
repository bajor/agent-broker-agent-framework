package com.llmagent.runners

import com.llmagent.common.*
import com.llmagent.common.Agent.{AgentId, AgentResult}
import com.llmagent.common.Config as AppConfig
import com.llmagent.common.observability.Types as ObsTypes
import com.llmagent.common.RunnerInfra.{ProcessResult, RunnerConfig}
import com.llmagent.pipeline.GuardedRefiner
import zio.*

/** Refiner Agent - terminal agent that validates and outputs final result
  * Takes AgentOutput from Explainer, validates against guardrails, outputs to console
  */
object RefinerRunner extends ZIOAppDefault:

  private val config = RunnerConfig(
    agentName = AgentNames.refiner,
    inputQueue = s"agent_${AgentNames.refiner}_tasks",
    outputQueue = None // Terminal agent
  )

  private lazy val agentId: AgentId = AgentId.unsafe(config.agentName)
  private val pipelineName: String = "code-execution"

  private def logFinalOutput(
    convId: ObsTypes.ConversationId,
    traceId: String,
    output: UserOutput
  ): Unit =
    import ObsTypes.ConversationId.value as convIdValue

    val sep = "=" * 70

    println(s"""
$sep
FINAL OUTPUT [conversation: ${convId.convIdValue}] [trace: $traceId]
$sep

${output.response}

$sep
EXECUTION STATS
$sep
  Total tool calls: ${output.stats.totalToolCalls}
  Total latency:    ${output.stats.totalLatencyMs}ms
$sep
""")

  private def handler(
    envelope: A2AJson.A2AEnvelope,
    convId: ObsTypes.ConversationId
  ): ZIO[Any, Throwable, ProcessResult] =
    ZIO.attempt {
      A2AJson.decodeAgentOutputFromEnvelope(envelope) match
        case Some(agentOutput) =>
          Logging.info(convId, Logging.Source.Agent, config.agentName,
            "Checking guardrails and refining output...")

          val guardedRefiner = GuardedRefiner.create(
            pipelineName,
            convId,
            config.agentName,
            AppConfig.Ollama.defaultModel
          )

          guardedRefiner.process(agentOutput) match
            case AgentResult.Completed(userOutput) =>
              Logging.info(convId, Logging.Source.Agent, config.agentName,
                "Guardrails passed, output ready")

              logFinalOutput(convId, envelope.traceId, userOutput)

              ProcessResult.Success(None) // Terminal agent, no output envelope

            case AgentResult.Failed(error) =>
              ProcessResult.Failure(s"Refinement failed: $error")

        case None =>
          ProcessResult.Failure("Failed to decode AgentOutput from payload")
    }

  override def run: ZIO[Any, Throwable, Nothing] =
    RunnerInfra.runAgent(config, handler)
