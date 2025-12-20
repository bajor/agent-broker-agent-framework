package com.llmagent.common.observability

import java.util.UUID
import java.time.Instant
import zio.json.*
import com.llmagent.common.Types.{LatencyMs, TokenCount, QueryResult}

/** Type-safe domain types for the observability system (prompts, logging) */
object Types:

  // === ID Types ===

  opaque type PromptId = String

  object PromptId:
    def generate(): PromptId = UUID.randomUUID().toString

    def fromString(s: String): Option[PromptId] =
      if s.nonEmpty then Some(s) else None

    def unsafe(s: String): PromptId = s

    extension (id: PromptId)
      def value: String = id

    given JsonEncoder[PromptId] = JsonEncoder.string.contramap(_.value)
    given JsonDecoder[PromptId] = JsonDecoder.string.map(unsafe)

  opaque type VersionId = String

  object VersionId:
    def generate(): VersionId = UUID.randomUUID().toString

    def fromString(s: String): Option[VersionId] =
      if s.nonEmpty then Some(s) else None

    def unsafe(s: String): VersionId = s

    extension (id: VersionId)
      def value: String = id

    given JsonEncoder[VersionId] = JsonEncoder.string.contramap(_.value)
    given JsonDecoder[VersionId] = JsonDecoder.string.map(unsafe)

  opaque type ConversationId = String

  object ConversationId:
    def generate(): ConversationId = UUID.randomUUID().toString

    def fromString(s: String): Option[ConversationId] =
      if s.nonEmpty then Some(s) else None

    def unsafe(s: String): ConversationId = s

    extension (id: ConversationId)
      def value: String = id

    given JsonEncoder[ConversationId] = JsonEncoder.string.contramap(_.value)
    given JsonDecoder[ConversationId] = JsonDecoder.string.map(unsafe)

  opaque type ExchangeId = String

  object ExchangeId:
    def generate(): ExchangeId = UUID.randomUUID().toString

    def fromString(s: String): Option[ExchangeId] =
      if s.nonEmpty then Some(s) else None

    def unsafe(s: String): ExchangeId = s

    extension (id: ExchangeId)
      def value: String = id

    given JsonEncoder[ExchangeId] = JsonEncoder.string.contramap(_.value)
    given JsonDecoder[ExchangeId] = JsonDecoder.string.map(unsafe)

  // === JSON Codecs for java.time.Instant ===
  given JsonEncoder[Instant] = JsonEncoder.string.contramap(_.toString)
  given JsonDecoder[Instant] = JsonDecoder.string.map(Instant.parse)

  // Import codecs from common.Types
  import com.llmagent.common.Types.LatencyMs.given
  import com.llmagent.common.Types.TokenCount.given

  // === Domain Objects ===

  /** A prompt definition (stored in prompts.db) */
  final case class Prompt(
    id: PromptId,
    name: String,
    description: String,
    @jsonField("created_at") createdAt: Instant
  ) derives JsonEncoder, JsonDecoder

  /** A version of a prompt - enabled versions are used for A/B testing (evenly distributed) */
  final case class PromptVersion(
    id: VersionId,
    @jsonField("prompt_id") promptId: PromptId,
    version: String,
    content: String,
    enabled: Boolean,
    @jsonField("created_at") createdAt: Instant
  ) derives JsonEncoder, JsonDecoder

  /** An LLM exchange log entry (stored in conversation_logs/ as JSONL) */
  final case class ExchangeLog(
    id: ExchangeId,
    @jsonField("conversation_id") conversationId: ConversationId,
    @jsonField("prompt_version_id") promptVersionId: VersionId,
    @jsonField("input_messages") inputMessages: String,
    @jsonField("output_response") outputResponse: String,
    @jsonField("raw_response") rawResponse: String,
    @jsonField("model_name") modelName: String,
    @jsonField("latency_ms") latencyMs: LatencyMs,
    @jsonField("input_tokens") inputTokens: TokenCount,
    @jsonField("output_tokens") outputTokens: TokenCount,
    error: Option[String],
    timestamp: Instant
  ) derives JsonEncoder, JsonDecoder

  /** Agent activity log entry (stored in conversation_logs/ as JSONL) */
  final case class AgentActivityLog(
    id: ExchangeId,
    @jsonField("conversation_id") conversationId: ConversationId,
    @jsonField("agent_id") agentId: String,
    @jsonField("agent_type") agentType: String,
    phase: String,
    @jsonField("input_summary") inputSummary: String,
    @jsonField("output_summary") outputSummary: String,
    @jsonField("latency_ms") latencyMs: LatencyMs,
    error: Option[String],
    timestamp: Instant
  ) derives JsonEncoder, JsonDecoder

  // Re-export common types
  export com.llmagent.common.Types.{LatencyMs, TokenCount, QueryResult}

  // Type alias
  type PromptResult[+A] = QueryResult[A]
  val PromptResult = QueryResult
