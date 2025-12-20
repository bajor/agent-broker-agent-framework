package com.llmagent.submit

import com.llmagent.common.*
import com.llmagent.common.Messages.*
import com.llmagent.common.observability.Types as ObsTypes
import com.rabbitmq.client.{Channel, Connection}
import java.util.concurrent.atomic.AtomicBoolean

/** Background listener for results from the worker */
object ResultListener:

  private val running = new AtomicBoolean(false)
  @volatile private var listenerThread: Option[Thread] = None

  /** System conversation ID for infrastructure logs */
  private val systemConversationId: ObsTypes.ConversationId =
    ObsTypes.ConversationId.unsafe("system-result-listener")

  /** Start the result listener in a background thread */
  def start(): Unit =
    if running.compareAndSet(false, true) then
      val thread = new Thread(() => listenLoop(), "result-listener")
      thread.setDaemon(true)
      thread.start()
      listenerThread = Some(thread)
      Logging.info(systemConversationId, Logging.Source.Submit, "Result listener started")

  /** Stop the result listener */
  def stop(): Unit =
    running.set(false)
    listenerThread.foreach { t =>
      t.interrupt()
      t.join(5000)
    }
    listenerThread = None
    Logging.info(systemConversationId, Logging.Source.Submit, "Result listener stopped")

  /** Check if listener is running */
  def isRunning: Boolean = running.get()

  /** Main listening loop */
  private def listenLoop(): Unit =
    while running.get() do
      RabbitMQ.connect(systemConversationId) match
        case RabbitMQ.ConnectionResult.Connected(connection, channel) =>
          try
            RabbitMQ.declareQueue(channel, Config.Queues.resultQueue)
            Logging.info(systemConversationId, Logging.Source.Submit, "Connected to result queue")
            consumeResults(connection, channel)
          catch
            case _: InterruptedException =>
              Logging.info(systemConversationId, Logging.Source.Submit, "Listener interrupted")
            case e: Exception =>
              Logging.logError(systemConversationId, Logging.Source.Submit, s"Listener error: ${e.getMessage}", Some(e))
          finally
            RabbitMQ.close(connection, channel)

        case RabbitMQ.ConnectionResult.Failed(error) =>
          Logging.logError(systemConversationId, Logging.Source.Submit, s"Failed to connect: $error")

      // Retry delay
      if running.get() then
        try Thread.sleep(Config.Timing.retryDelay.value * 1000L)
        catch case _: InterruptedException => ()

  /** Consume results from the queue */
  private def consumeResults(connection: Connection, channel: Channel): Unit =
    while running.get() && connection.isOpen do
      RabbitMQ.consumeOne(channel, Config.Queues.resultQueue, systemConversationId) match
        case RabbitMQ.ConsumeResult.Message(body, deliveryTag) =>
          Json.decodeResult(body) match
            case Some(result) =>
              val conversationId = ObsTypes.ConversationId.fromString(result.taskId.value).getOrElse(systemConversationId)
              ResultStore.put(result)
              RabbitMQ.ack(channel, deliveryTag)
              val status = result.status match
                case ResultStatus.Success(_) => "SUCCESS"
                case ResultStatus.Failure(_) => "FAILURE"
              Logging.info(conversationId, Logging.Source.Submit, s"Received result for task ${result.taskId.value}: $status")
            case None =>
              Logging.logError(systemConversationId, Logging.Source.Submit, s"Failed to parse result: $body")
              RabbitMQ.ack(channel, deliveryTag)

        case RabbitMQ.ConsumeResult.Timeout =>
          Thread.sleep(100)

        case RabbitMQ.ConsumeResult.Failed(error) =>
          Logging.logError(systemConversationId, Logging.Source.Submit, s"Consume failed: $error")
          Thread.sleep(1000)
