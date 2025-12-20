package com.llmagent.dsl

import zio.*
import zio.json.*
import com.llmagent.dsl.Types.*
import com.llmagent.common.{RabbitMQ, Logging, A2AJson}
import com.llmagent.common.observability.Types as ObsTypes
import com.llmagent.common.observability.ConversationLogger
import com.rabbitmq.client.{Channel, Connection}

/**
 * Runtime infrastructure for executing AgentDefinitions.
 *
 * This module bridges the functional DSL world with the RabbitMQ infrastructure,
 * preserving the existing logging system:
 *
 * - Agent logs: agent_logs/{conversationId}_{agentName}.jsonl
 * - Conversation logs: conversation_logs/{conversationId}.jsonl
 * - LLM query logs with prompt/response/duration
 *
 * The conversation ID (UUID) is extracted from incoming A2A envelopes and
 * propagated to all downstream agents via the output envelope.
 *
 * Note: Named AgentRuntime to avoid conflict with zio.Runtime.
 */
object AgentRuntime:

  // ════════════════════════════════════════════════════════════════════════════
  // CONFIGURATION
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * Runtime configuration for an agent.
   */
  final case class RuntimeConfig(
    prefetchCount: Int = 10,
    pollIntervalMs: Int = 100,
    connectionRetries: Int = 5,
    retryDelaySeconds: Int = 2
  )

  object RuntimeConfig:
    val default: RuntimeConfig = RuntimeConfig()

  // ════════════════════════════════════════════════════════════════════════════
  // RUNNER
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * Run an agent definition as a distributed service.
   * This is the main entry point for deploying agents.
   *
   * Logging behavior:
   * - System logs go to: agent_logs/system-{agentName}-runner_{agentName}.jsonl
   * - Per-conversation logs go to: agent_logs/{conversationId}_{agentName}.jsonl
   * - Conversation-level logs go to: conversation_logs/{conversationId}.jsonl
   *
   * @param agent The agent definition built from the DSL
   * @param config Runtime configuration
   * @return A ZIO effect that runs forever until interrupted
   */
  def run[In, Out](
    agent: AgentDefinition[In, Out],
    config: RuntimeConfig = RuntimeConfig.default
  ): ZIO[Any, Throwable, Nothing] =
    val systemConvId = ObsTypes.ConversationId.unsafe(s"system-${agent.name}-runner")

    for
      _ <- ZIO.succeed(
        Logging.info(systemConvId, Logging.Source.Agent, agent.name,
          s"Starting DSL runtime...")
      )
      connChan <- connect(systemConvId, agent.name, config)
      (connection, channel) = connChan
      _ <- setupQueues(channel, agent, config, systemConvId)
      _ <- ZIO.succeed(
        Logging.info(systemConvId, Logging.Source.Agent, agent.name,
          s"Listening on ${agent.inputQueueName}...")
      )
      result <- consumerLoop(channel, agent, config, systemConvId)
        .ensuring(ZIO.succeed {
          Logging.info(systemConvId, Logging.Source.Agent, agent.name,
            s"Shutting down...")
          RabbitMQ.close(connection, channel)
        })
    yield result

  /**
   * Run multiple agents in parallel.
   * Useful for testing or running a complete pipeline in a single process.
   */
  def runAll(agents: AgentDefinition[?, ?]*): ZIO[Any, Throwable, Nothing] =
    ZIO.collectAllParDiscard(
      agents.map(a => run(a.asInstanceOf[AgentDefinition[Any, Any]]))
    ) *> ZIO.never

  // ════════════════════════════════════════════════════════════════════════════
  // CONNECTION MANAGEMENT
  // ════════════════════════════════════════════════════════════════════════════

  private def connect(
    convId: ObsTypes.ConversationId,
    agentName: String,
    config: RuntimeConfig
  ): ZIO[Any, Throwable, (Connection, Channel)] =
    ZIO.attempt {
      Logging.info(convId, Logging.Source.Agent, agentName, s"Connecting to RabbitMQ...")
      RabbitMQ.connect(convId) match
        case RabbitMQ.ConnectionResult.Connected(conn, chan) =>
          Logging.info(convId, Logging.Source.Agent, agentName, s"Connected")
          (conn, chan)
        case RabbitMQ.ConnectionResult.Failed(error) =>
          throw new RuntimeException(s"Connection failed: $error")
    }.retry(
      Schedule.recurs(config.connectionRetries) &&
      Schedule.spaced(config.retryDelaySeconds.seconds)
    )

  private def setupQueues[In, Out](
    channel: Channel,
    agent: AgentDefinition[In, Out],
    config: RuntimeConfig,
    convId: ObsTypes.ConversationId
  ): ZIO[Any, Throwable, Unit] =
    ZIO.attempt {
      RabbitMQ.declareQueue(channel, agent.inputQueueName)
      agent.outputQueueName.foreach(q => RabbitMQ.declareQueue(channel, q))
      RabbitMQ.setQos(channel, config.prefetchCount)
      val outputInfo = agent.outputQueueName.getOrElse("(terminal)")
      Logging.info(convId, Logging.Source.Agent, agent.name,
        s"Queues ready: in=${agent.inputQueueName}, out=$outputInfo")
    }

  // ════════════════════════════════════════════════════════════════════════════
  // MESSAGE PROCESSING
  // ════════════════════════════════════════════════════════════════════════════

  private def consumerLoop[In, Out](
    channel: Channel,
    agent: AgentDefinition[In, Out],
    config: RuntimeConfig,
    systemConvId: ObsTypes.ConversationId
  ): ZIO[Any, Nothing, Nothing] =
    val pollOnce: ZIO[Any, Nothing, Unit] = ZIO.attempt {
      RabbitMQ.consumeOne(channel, agent.inputQueueName, systemConvId)
    }.flatMap {
      case RabbitMQ.ConsumeResult.Message(body, deliveryTag) =>
        processMessage(channel, body, deliveryTag, agent, systemConvId).forkDaemon.unit

      case RabbitMQ.ConsumeResult.Timeout =>
        ZIO.sleep(config.pollIntervalMs.millis)

      case RabbitMQ.ConsumeResult.Failed(error) =>
        ZIO.succeed(
          Logging.logError(systemConvId, Logging.Source.Agent, agent.name,
            s"Consume error: $error")
        ) *> ZIO.sleep(1.second)
    }.catchAll { e =>
      ZIO.succeed(
        Logging.logError(systemConvId, Logging.Source.Agent, agent.name,
          s"Poll error: ${e.getMessage}", Some(e))
      ) *> ZIO.sleep(1.second)
    }

    pollOnce.forever

  /**
   * Process a single message, maintaining full conversation ID propagation.
   *
   * The conversation ID from the incoming envelope is:
   * 1. Used for all logging in this agent (agent_logs/{convId}_{agentName}.jsonl)
   * 2. Passed to the pipeline context for step-level logging
   * 3. Propagated to the output envelope for downstream agents
   */
  private def processMessage[In, Out](
    channel: Channel,
    body: String,
    deliveryTag: Long,
    agent: AgentDefinition[In, Out],
    systemConvId: ObsTypes.ConversationId
  ): ZIO[Any, Nothing, Unit] =
    val process: ZIO[Any, Throwable, Unit] = for
      // Decode the A2A envelope - this contains the conversation ID
      envelope <- ZIO.fromOption(A2AJson.decodeEnvelope(body))
        .mapError(_ => new RuntimeException("Failed to parse A2A envelope"))

      // Extract conversation ID from envelope - this is the UUID that links all agents
      convId = ObsTypes.ConversationId.fromString(envelope.conversationId)
        .getOrElse(ObsTypes.ConversationId.unsafe(envelope.conversationId))

      traceId = TraceId.unsafe(envelope.traceId)

      // Log with the actual conversation ID - this goes to agent_logs/{convId}_{agentName}.jsonl
      _ = Logging.info(convId, Logging.Source.Agent, agent.name,
        s"Processing ${envelope.payloadType} from ${envelope.fromAgent}")

      // Extract payload JSON as string for the agent's decoder
      payloadJson = envelope.payload.toString

      // Execute the pipeline with the conversation context
      result <- agent.executeFromMessage(payloadJson, traceId, convId)

      // Log the result - goes to the same agent log file
      _ = logResult(result, agent.name, convId)

      // Publish output if we have an output queue
      // CRITICAL: The conversation ID is preserved in the output envelope
      _ <- agent.outputQueueName match
        case Some(outputQueue) =>
          publishResult(channel, outputQueue, result, envelope, agent, convId)
        case None =>
          // Terminal agent - log the final output
          logTerminalOutput(result, agent, convId)

      // Acknowledge the message
      _ <- ZIO.attempt(RabbitMQ.ack(channel, deliveryTag))
    yield ()

    process.catchAll { e =>
      ZIO.attempt {
        Logging.logError(systemConvId, Logging.Source.Agent, agent.name,
          s"Task failed: ${e.getMessage}", Some(e))
        RabbitMQ.nack(channel, deliveryTag, requeue = false)
      }.ignore
    }

  private def logResult[Out](
    result: PipelineResult[Out],
    agentName: String,
    convId: ObsTypes.ConversationId
  ): Unit =
    result match
      case PipelineResult.Success(_, ctx) =>
        val stepSummary = ctx.stepLogs.map(s => s"${s.stepName}:${s.durationMs}ms").mkString(", ")
        Logging.info(convId, Logging.Source.Agent, agentName,
          s"Pipeline completed (${ctx.stepLogs.size} steps): $stepSummary")
      case PipelineResult.Failure(error, _) =>
        Logging.logError(convId, Logging.Source.Agent, agentName, s"Pipeline failed: $error")
      case PipelineResult.Rejected(guardrail, reason, _) =>
        Logging.info(convId, Logging.Source.Agent, agentName,
          s"Pipeline rejected by guardrail '$guardrail': $reason")

  private def logTerminalOutput[In, Out](
    result: PipelineResult[Out],
    agent: AgentDefinition[In, Out],
    convId: ObsTypes.ConversationId
  ): ZIO[Any, Nothing, Unit] =
    ZIO.succeed {
      result match
        case PipelineResult.Success(out, _) =>
          val encoded = agent.encoder(out)
          Logging.info(convId, Logging.Source.Agent, agent.name,
            s"=== FINAL OUTPUT ===\n${encoded.take(2000)}${if encoded.length > 2000 then "..." else ""}")
        case PipelineResult.Failure(error, _) =>
          Logging.logError(convId, Logging.Source.Agent, agent.name,
            s"=== FINAL OUTPUT (FAILED) ===\n$error")
        case PipelineResult.Rejected(guardrail, reason, _) =>
          Logging.info(convId, Logging.Source.Agent, agent.name,
            s"=== FINAL OUTPUT (REJECTED) ===\nGuardrail: $guardrail\nReason: $reason")
    }

  /**
   * Publish the result to the output queue.
   *
   * CRITICAL: The conversation ID from the original envelope is preserved
   * in the output envelope, ensuring downstream agents log to the same
   * conversation files.
   */
  private def publishResult[In, Out](
    channel: Channel,
    outputQueue: String,
    result: PipelineResult[Out],
    originalEnvelope: A2AJson.A2AEnvelope,
    agent: AgentDefinition[In, Out],
    convId: ObsTypes.ConversationId
  ): ZIO[Any, Throwable, Unit] =
    ZIO.attempt {
      // Create the output envelope based on result type
      val (outputPayload, payloadType) = result match
        case PipelineResult.Success(out, _) =>
          (agent.encoder(out), "AgentOutput")
        case PipelineResult.Failure(error, ctx) =>
          // Encode failure as a special payload that downstream can handle
          (FailurePayload(ctx.agentName, error).toJson, "UpstreamFailure")
        case PipelineResult.Rejected(guardrail, reason, ctx) =>
          // Encode rejection as a special payload
          (RejectionPayload(ctx.agentName, guardrail, reason).toJson, "UpstreamRejection")

      // Parse the payload as JSON
      val payloadJson = zio.json.ast.Json.decoder.decodeJson(outputPayload)
        .getOrElse(zio.json.ast.Json.Str(outputPayload))

      // Extract target agent name from queue (e.g., "agent_explainer_tasks" -> "explainer")
      val toAgent = outputQueue
        .stripPrefix("agent_")
        .stripSuffix("_tasks")

      // Build the output envelope, PRESERVING the original conversation ID and trace ID
      val outputEnvelope = A2AJson.A2AEnvelope(
        fromAgent = agent.name,
        toAgent = toAgent,
        traceId = originalEnvelope.traceId,           // Preserve trace ID
        conversationId = originalEnvelope.conversationId, // CRITICAL: Preserve conversation ID
        payloadType = payloadType,
        payload = payloadJson
      )

      val envelopeJson = outputEnvelope.toJson

      RabbitMQ.publish(channel, outputQueue, envelopeJson, convId) match
        case RabbitMQ.PublishResult.Published =>
          Logging.info(convId, Logging.Source.Agent, agent.name,
            s"Published $payloadType to $outputQueue (convId: ${originalEnvelope.conversationId.take(8)}...)")
        case RabbitMQ.PublishResult.Failed(error) =>
          throw new RuntimeException(s"Publish failed: $error")
    }

  // ════════════════════════════════════════════════════════════════════════════
  // FAILURE/REJECTION PAYLOADS
  // ════════════════════════════════════════════════════════════════════════════

  /** Payload for upstream failures - allows downstream to handle gracefully */
  final case class FailurePayload(
    @jsonField("from_agent") fromAgent: String,
    error: String
  ) derives JsonEncoder, JsonDecoder

  /** Payload for upstream rejections - allows downstream to handle gracefully */
  final case class RejectionPayload(
    @jsonField("from_agent") fromAgent: String,
    @jsonField("guardrail_name") guardrailName: String,
    reason: String
  ) derives JsonEncoder, JsonDecoder

  // ════════════════════════════════════════════════════════════════════════════
  // SINGLE MESSAGE EXECUTION (for testing)
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * Execute an agent with a single input (for testing).
   * Creates a new conversation ID if not provided.
   */
  def executeOnce[In, Out](
    agent: AgentDefinition[In, Out],
    input: In,
    conversationId: ObsTypes.ConversationId
  ): ZIO[Any, Nothing, PipelineResult[Out]] =
    val ctx = PipelineContext.initial(
      agent.name,
      TraceId.generate(),
      conversationId
    )
    agent.execute(input, ctx)

  /**
   * Execute an agent with a new conversation ID (for testing).
   */
  def executeOnceNewConversation[In, Out](
    agent: AgentDefinition[In, Out],
    input: In
  ): ZIO[Any, Nothing, PipelineResult[Out]] =
    val convId = ObsTypes.ConversationId.generate()
    executeOnce(agent, input, convId)
