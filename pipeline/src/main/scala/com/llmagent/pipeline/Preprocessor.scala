package com.llmagent.pipeline

import com.llmagent.common.*
import com.llmagent.common.Agent.{AgentId, AgentDef, AgentResult}
import com.llmagent.common.observability.Types as ObsTypes

/** Preprocessed input ready for first agent */
final case class PreprocessedInput(
  prompt: String,
  model: String,
  context: String,
  originalInput: UserInput
)

/** Preprocessor - prepares user input for the agent chain */
final class Preprocessor(
  override val id: AgentId,
  conversationId: ObsTypes.ConversationId,
  defaultModel: String = Config.Ollama.defaultModel
) extends AgentDef[UserInput, PreprocessedInput]:
  import AgentId.value as agentIdValue

  private val agentName = "Preprocessor"

  override def process(input: UserInput): AgentResult[PreprocessedInput] = {
    Logging.info(
      conversationId,
      Logging.Source.Agent,
      agentName,
      s"${id.agentIdValue} processing user input: ${input.rawPrompt.take(50)}..."
    )

    val cleaned = cleanPrompt(input.rawPrompt)
    val context = extractContext(input)
    val model = input.metadata.getOrElse("model", defaultModel)

    val preprocessed = PreprocessedInput(
      prompt = cleaned,
      model = model,
      context = context,
      originalInput = input
    )

    Logging.info(
      conversationId,
      Logging.Source.Agent,
      agentName,
      s"${id.agentIdValue} completed. Prompt length: ${cleaned.length}"
    )

    AgentResult.Completed(preprocessed)
  }

  private def cleanPrompt(raw: String): String = {
    raw
      .trim
      .replaceAll("\\s+", " ")
      .stripPrefix("please ")
      .stripPrefix("Please ")
  }

  private def extractContext(input: UserInput): String = {
    val parts = List(
      input.metadata.get("context"),
      input.metadata.get("system"),
      input.metadata.get("instructions")
    ).flatten

    if parts.isEmpty then ""
    else parts.mkString("\n")
  }

object Preprocessor:
  def create(conversationId: ObsTypes.ConversationId): Preprocessor =
    new Preprocessor(AgentId.generate(), conversationId)

  def withModel(conversationId: ObsTypes.ConversationId, model: String): Preprocessor =
    new Preprocessor(AgentId.generate(), conversationId, model)
