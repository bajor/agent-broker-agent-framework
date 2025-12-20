package com.llmagent.tools

import com.llmagent.common.*
import com.llmagent.common.Agent.{Tool, ToolResult}
import com.llmagent.common.observability.{ObservableLlmClient, Types as ObsTypes}

/** Tool for calling LLM - wraps LlmClient with Tool interface */
object LlmTool:

  /** Input for LLM tool */
  final case class LlmInput(
    prompt: String,
    model: String,
    conversationId: ObsTypes.ConversationId
  )

  /** Output from LLM tool */
  final case class LlmOutput(
    response: String,
    latencyMs: Long
  )

  /** LLM Tool implementation */
  val instance: Tool[LlmInput, LlmOutput] = new Tool[LlmInput, LlmOutput]:
    def name: String = "llm"

    def execute(input: LlmInput): ToolResult[LlmOutput] = {
      val obsResult = ObservableLlmClient.queryRaw(
        input.prompt,
        input.conversationId,
        input.model
      )

      obsResult.result match {
        case LlmClient.QueryResult.Success(response) =>
          ToolResult.Success(LlmOutput(response, obsResult.latencyMs.value))
        case LlmClient.QueryResult.Failure(error) =>
          ToolResult.Failure(error)
      }
    }
