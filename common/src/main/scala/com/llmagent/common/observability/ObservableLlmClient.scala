package com.llmagent.common.observability

import com.llmagent.common.{Config, LlmClient}
import java.time.Instant
import Types.*

/** LLM client wrapper that loads prompts dynamically and logs all exchanges */
object ObservableLlmClient {

  /** Query result with version tracking */
  final case class ObservableResult(
    result: LlmClient.QueryResult,
    versionId: VersionId,
    latencyMs: LatencyMs
  )

  /**
   * Query using a named prompt with A/B testing.
   * Selects a random enabled version and logs the exchange.
   */
  def queryWithPrompt(
    promptName: String,
    conversationId: ConversationId,
    userInput: String,
    model: String = Config.Ollama.defaultModel
  ): ObservableResult = {
    PromptRegistry.getRandomEnabledVersionByName(promptName) match {
      case PromptResult.Success(version) =>
        val fullPrompt = version.content.replace("{{input}}", userInput)
        queryAndLog(fullPrompt, conversationId, version.id, model)

      case PromptResult.NotFound(msg) =>
        val dummyVersionId = VersionId.fromString("no-version").get
        ObservableResult(
          LlmClient.QueryResult.Failure(s"Prompt not found: $msg"),
          dummyVersionId,
          LatencyMs(0)
        )

      case PromptResult.Error(msg) =>
        val dummyVersionId = VersionId.fromString("error").get
        ObservableResult(
          LlmClient.QueryResult.Failure(s"Prompt registry error: $msg"),
          dummyVersionId,
          LatencyMs(0)
        )
    }
  }

  /**
   * Query with a specific version ID (for when you already know which version to use).
   */
  def queryWithVersion(
    versionId: VersionId,
    conversationId: ConversationId,
    userInput: String,
    model: String = Config.Ollama.defaultModel
  ): ObservableResult = {
    PromptRegistry.getVersionById(versionId) match {
      case PromptResult.Success(version) =>
        val fullPrompt = version.content.replace("{{input}}", userInput)
        queryAndLog(fullPrompt, conversationId, versionId, model)

      case PromptResult.NotFound(msg) =>
        ObservableResult(
          LlmClient.QueryResult.Failure(s"Version not found: $msg"),
          versionId,
          LatencyMs(0)
        )

      case PromptResult.Error(msg) =>
        ObservableResult(
          LlmClient.QueryResult.Failure(s"Prompt registry error: $msg"),
          versionId,
          LatencyMs(0)
        )
    }
  }

  /**
   * Query with raw prompt content (bypasses prompt registry).
   * Still logs the exchange with a placeholder version ID.
   */
  def queryRaw(
    prompt: String,
    conversationId: ConversationId,
    model: String = Config.Ollama.defaultModel
  ): ObservableResult = {
    val rawVersionId = VersionId.fromString("raw-prompt").get
    queryAndLog(prompt, conversationId, rawVersionId, model)
  }

  private def queryAndLog(
    prompt: String,
    conversationId: ConversationId,
    versionId: VersionId,
    model: String
  ): ObservableResult = {
    val startTime = System.currentTimeMillis()
    val result = LlmClient.query(prompt, model)
    val latencyMs = LatencyMs(System.currentTimeMillis() - startTime)

    val (outputResponse, rawResponse, error) = result match {
      case LlmClient.QueryResult.Success(response) =>
        (response, response, None)
      case LlmClient.QueryResult.Failure(err) =>
        ("", "", Some(err))
    }

    val log = ExchangeLog(
      id = ExchangeId.generate(),
      conversationId = conversationId,
      promptVersionId = versionId,
      inputMessages = prompt,
      outputResponse = outputResponse,
      rawResponse = rawResponse,
      modelName = model,
      latencyMs = latencyMs,
      inputTokens = TokenCount(estimateTokens(prompt)),
      outputTokens = TokenCount(estimateTokens(outputResponse)),
      error = error,
      timestamp = Instant.now()
    )

    ConversationLogger.logExchange(log)

    ObservableResult(result, versionId, latencyMs)
  }

  private def estimateTokens(text: String): Int = {
    // Rough estimate: ~4 chars per token
    (text.length / 4).max(1)
  }
}
