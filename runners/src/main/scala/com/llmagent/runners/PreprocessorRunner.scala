package com.llmagent.runners

import com.llmagent.common.*
import com.llmagent.common.Agent.AgentId
import com.llmagent.common.observability.Types as ObsTypes
import com.llmagent.common.RunnerInfra.{ProcessResult, RunnerConfig}
import zio.*

/** Preprocessor Agent - transforms user input into structured agent input */
object PreprocessorRunner extends ZIOAppDefault:

  private val config = RunnerConfig(
    agentName = AgentNames.preprocessor,
    inputQueue = s"agent_${AgentNames.preprocessor}_tasks",
    outputQueue = Some(s"agent_${AgentNames.codegen}_tasks")
  )

  private lazy val agentId: AgentId = AgentId.unsafe(config.agentName)

  private def preprocess(input: UserInput): AgentInput =
    val cleaned = input.rawPrompt
      .trim
      .replaceAll("\\s+", " ")
      .stripPrefix("please ")
      .stripPrefix("Please ")

    AgentInput(taskDescription = s"Write a Python script that: $cleaned")

  private def handler(
    envelope: A2AJson.A2AEnvelope,
    convId: ObsTypes.ConversationId
  ): ZIO[Any, Throwable, ProcessResult] =
    ZIO.attempt {
      A2AJson.decodeUserInputFromEnvelope(envelope) match
        case Some(userInput) =>
          val agentInput = preprocess(userInput)

          val outputEnvelope = A2AJson.createEnvelope(
            from = agentId,
            to = AgentNames.codegen,
            traceId = envelope.traceId,
            conversationId = convId,
            payload = agentInput,
            encoder = A2AJson.encodeAgentInput,
            payloadType = A2AJson.PayloadTypes.AgentInput
          )

          ProcessResult.Success(Some(outputEnvelope))

        case None =>
          ProcessResult.Failure("Failed to decode UserInput from payload")
    }

  override def run: ZIO[Any, Throwable, Nothing] =
    RunnerInfra.runAgent(config, handler)
