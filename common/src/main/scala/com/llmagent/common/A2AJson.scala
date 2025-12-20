package com.llmagent.common

import com.llmagent.common.Agent.AgentId
import com.llmagent.common.observability.Types as ObsTypes
import zio.json.*
import zio.json.ast.Json

/** JSON serialization for A2A protocol messages in the distributed pipeline.
  * Uses zio-json for type-safe encoding/decoding.
  */
object A2AJson:
  import AgentId.value as agentIdValue
  import ObsTypes.ConversationId.value as convIdValue

  /** Envelope for all A2A messages over RabbitMQ.
    * The payload is stored as a Json AST for type-safe embedding.
    */
  final case class A2AEnvelope(
    @jsonField("from_agent") fromAgent: String,
    @jsonField("to_agent") toAgent: String,
    @jsonField("trace_id") traceId: String,
    @jsonField("conversation_id") conversationId: String,
    @jsonField("payload_type") payloadType: String,
    payload: Json
  ) derives JsonEncoder, JsonDecoder

  /** Encode envelope to JSON string */
  def encodeEnvelope(env: A2AEnvelope): String = env.toJson

  /** Decode envelope from JSON string */
  def decodeEnvelope(json: String): Option[A2AEnvelope] =
    json.fromJson[A2AEnvelope].toOption

  /** Decode payload from envelope to specific type */
  private def decodePayload[T: JsonDecoder](envelope: A2AEnvelope): Option[T] =
    envelope.payload.as[T].toOption

  // Type-safe payload encoding using zio-json
  def encodeUserInput(input: UserInput): String = input.toJson
  def encodeAgentInput(input: AgentInput): String = input.toJson
  def encodeAgentOutput(output: AgentOutput): String = output.toJson
  def encodeUserOutput(output: UserOutput): String = output.toJson
  def encodeExecutionStats(stats: ExecutionStats): String = stats.toJson

  // Type-safe payload decoding from JSON string (for backward compatibility)
  def decodeUserInput(json: String): Option[UserInput] = json.fromJson[UserInput].toOption
  def decodeAgentInput(json: String): Option[AgentInput] = json.fromJson[AgentInput].toOption
  def decodeAgentOutput(json: String): Option[AgentOutput] = json.fromJson[AgentOutput].toOption
  def decodeUserOutput(json: String): Option[UserOutput] = json.fromJson[UserOutput].toOption
  def decodeExecutionStats(json: String): Option[ExecutionStats] = json.fromJson[ExecutionStats].toOption

  // Convenience: decode from envelope directly
  def decodeUserInputFromEnvelope(envelope: A2AEnvelope): Option[UserInput] = decodePayload[UserInput](envelope)
  def decodeAgentInputFromEnvelope(envelope: A2AEnvelope): Option[AgentInput] = decodePayload[AgentInput](envelope)
  def decodeAgentOutputFromEnvelope(envelope: A2AEnvelope): Option[AgentOutput] = decodePayload[AgentOutput](envelope)
  def decodeUserOutputFromEnvelope(envelope: A2AEnvelope): Option[UserOutput] = decodePayload[UserOutput](envelope)

  /** Create envelope with typed payload */
  def createEnvelope[T: JsonEncoder](
    from: AgentId,
    to: String,
    traceId: String,
    conversationId: ObsTypes.ConversationId,
    payload: T,
    encoder: T => String,
    payloadType: String
  ): String =
    // Convert payload to Json AST
    val payloadJson = payload.toJsonAST match
      case Right(json) => json
      case Left(err) => Json.Str(s"encoding error: $err")

    val env = A2AEnvelope(
      fromAgent = from.agentIdValue,
      toAgent = to,
      traceId = traceId,
      conversationId = conversationId.convIdValue,
      payloadType = payloadType,
      payload = payloadJson
    )
    env.toJson

  /** Payload type constants */
  object PayloadTypes:
    val UserInput = "UserInput"
    val AgentInput = "AgentInput"
    val AgentOutput = "AgentOutput"
    val UserOutput = "UserOutput"
