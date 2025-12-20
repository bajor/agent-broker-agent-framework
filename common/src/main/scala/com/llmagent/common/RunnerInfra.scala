package com.llmagent.common

import com.llmagent.common.observability.Types as ObsTypes
import com.rabbitmq.client.{Channel, Connection}
import zio.*

/** Reusable infrastructure for distributed agent runners.
  * Handles RabbitMQ connection, queue setup, and parallel message processing.
  */
object RunnerInfra:

  /** Result of processing a message */
  enum ProcessResult:
    case Success(outputEnvelope: Option[String])
    case Failure(error: String)

  /** Configuration for a runner */
  final case class RunnerConfig(
    agentName: String,
    inputQueue: String,
    outputQueue: Option[String],
    prefetchCount: Int = 10
  )

  /** Connect to RabbitMQ with retries */
  def connect(convId: ObsTypes.ConversationId, agentName: String): ZIO[Any, Throwable, (Connection, Channel)] =
    ZIO.attempt {
      Logging.info(convId, Logging.Source.Agent, s"$agentName: Connecting to RabbitMQ...")
      RabbitMQ.connect(convId) match
        case RabbitMQ.ConnectionResult.Connected(conn, chan) =>
          Logging.info(convId, Logging.Source.Agent, s"$agentName: Connected")
          (conn, chan)
        case RabbitMQ.ConnectionResult.Failed(error) =>
          throw new RuntimeException(s"Connection failed: $error")
    }.retry(Schedule.recurs(5) && Schedule.spaced(2.seconds))

  /** Setup queues */
  def setupQueues(
    channel: Channel,
    config: RunnerConfig,
    convId: ObsTypes.ConversationId
  ): ZIO[Any, Throwable, Unit] =
    ZIO.attempt {
      RabbitMQ.declareQueue(channel, config.inputQueue)
      config.outputQueue.foreach(q => RabbitMQ.declareQueue(channel, q))
      RabbitMQ.setQos(channel, config.prefetchCount)
      val outputInfo = config.outputQueue.getOrElse("(terminal)")
      Logging.info(convId, Logging.Source.Agent,
        s"${config.agentName}: Queues ready: in=${config.inputQueue}, out=$outputInfo")
    }

  /** Publish to output queue */
  def publishOutput(
    channel: Channel,
    outputQueue: String,
    envelope: String,
    convId: ObsTypes.ConversationId,
    agentName: String
  ): ZIO[Any, Throwable, Unit] =
    ZIO.attempt {
      RabbitMQ.publish(channel, outputQueue, envelope, convId) match
        case RabbitMQ.PublishResult.Published =>
          Logging.info(convId, Logging.Source.Agent, agentName, s"Published to $outputQueue")
        case RabbitMQ.PublishResult.Failed(error) =>
          throw new RuntimeException(s"Publish failed: $error")
    }

  /** Process a single message in its own fiber */
  def processTask(
    channel: Channel,
    body: String,
    deliveryTag: Long,
    config: RunnerConfig,
    systemConvId: ObsTypes.ConversationId,
    handler: (A2AJson.A2AEnvelope, ObsTypes.ConversationId) => ZIO[Any, Throwable, ProcessResult]
  ): ZIO[Any, Nothing, Unit] =
    val process: ZIO[Any, Throwable, Unit] = for
      envelope <- ZIO.fromOption(A2AJson.decodeEnvelope(body))
        .mapError(_ => new RuntimeException(s"Failed to parse envelope"))

      convId = ObsTypes.ConversationId.fromString(envelope.conversationId)
        .getOrElse(ObsTypes.ConversationId.unsafe(envelope.conversationId))

      _ = Logging.info(convId, Logging.Source.Agent, config.agentName,
        s"Processing ${envelope.payloadType} from ${envelope.fromAgent}")

      result <- handler(envelope, convId)

      _ <- result match
        case ProcessResult.Success(Some(output)) =>
          config.outputQueue match
            case Some(queue) => publishOutput(channel, queue, output, convId, config.agentName)
            case None => ZIO.succeed(())
        case ProcessResult.Success(None) =>
          ZIO.succeed(Logging.info(convId, Logging.Source.Agent, config.agentName, "Complete (no output)"))
        case ProcessResult.Failure(error) =>
          ZIO.fail(new RuntimeException(error))

      _ <- ZIO.attempt(RabbitMQ.ack(channel, deliveryTag))
    yield ()

    process.catchAll { e =>
      ZIO.attempt {
        Logging.logError(systemConvId, Logging.Source.Agent,
          s"${config.agentName}: Task failed: ${e.getMessage}", Some(e))
        RabbitMQ.nack(channel, deliveryTag, requeue = false)
      }.ignore
    }

  /** Consumer loop - spawns fiber per message for parallelism */
  def consumerLoop(
    channel: Channel,
    config: RunnerConfig,
    systemConvId: ObsTypes.ConversationId,
    handler: (A2AJson.A2AEnvelope, ObsTypes.ConversationId) => ZIO[Any, Throwable, ProcessResult]
  ): ZIO[Any, Nothing, Nothing] =
    val pollOnce: ZIO[Any, Nothing, Unit] = ZIO.attempt {
      RabbitMQ.consumeOne(channel, config.inputQueue, systemConvId)
    }.flatMap {
      case RabbitMQ.ConsumeResult.Message(body, deliveryTag) =>
        processTask(channel, body, deliveryTag, config, systemConvId, handler).forkDaemon.unit

      case RabbitMQ.ConsumeResult.Timeout =>
        ZIO.sleep(100.millis)

      case RabbitMQ.ConsumeResult.Failed(error) =>
        ZIO.succeed(Logging.logError(systemConvId, Logging.Source.Agent,
          s"${config.agentName}: Consume error: $error")) *> ZIO.sleep(1.second)
    }.catchAll { e =>
      ZIO.succeed(Logging.logError(systemConvId, Logging.Source.Agent,
        s"${config.agentName}: Poll error: ${e.getMessage}", Some(e))) *> ZIO.sleep(1.second)
    }

    pollOnce.forever

  /** Run a distributed agent */
  def runAgent(
    config: RunnerConfig,
    handler: (A2AJson.A2AEnvelope, ObsTypes.ConversationId) => ZIO[Any, Throwable, ProcessResult]
  ): ZIO[Any, Throwable, Nothing] =
    val systemConvId = ObsTypes.ConversationId.unsafe(s"system-${config.agentName}-runner")

    for
      _ <- ZIO.succeed(Logging.info(systemConvId, Logging.Source.Agent, s"${config.agentName}: Starting..."))
      connChan <- connect(systemConvId, config.agentName)
      (connection, channel) = connChan
      _ <- setupQueues(channel, config, systemConvId)
      _ <- ZIO.succeed(Logging.info(systemConvId, Logging.Source.Agent,
        s"${config.agentName}: Listening on ${config.inputQueue}..."))
      result <- consumerLoop(channel, config, systemConvId, handler)
        .ensuring(ZIO.succeed {
          Logging.info(systemConvId, Logging.Source.Agent, s"${config.agentName}: Shutting down...")
          RabbitMQ.close(connection, channel)
        })
    yield result
