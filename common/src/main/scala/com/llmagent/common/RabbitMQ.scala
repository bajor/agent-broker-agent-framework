package com.llmagent.common

import com.rabbitmq.client.*
import scala.util.{Try, Using}
import com.llmagent.common.observability.Types.ConversationId

/** RabbitMQ client wrapper with type-safe operations */
object RabbitMQ:

  /** Connection result */
  enum ConnectionResult:
    case Connected(connection: Connection, channel: Channel)
    case Failed(error: String)

  /** Publish result */
  enum PublishResult:
    case Published
    case Failed(error: String)

  /** Consume result */
  enum ConsumeResult:
    case Message(body: String, deliveryTag: Long)
    case Timeout
    case Failed(error: String)

  /** Create a connection to RabbitMQ */
  def connect(conversationId: ConversationId): ConnectionResult =
    try
      val factory = new ConnectionFactory()
      factory.setHost(Config.RabbitMQ.host)
      factory.setPort(Config.RabbitMQ.port.value)
      factory.setUsername(Config.RabbitMQ.user)
      factory.setPassword(Config.RabbitMQ.password)
      factory.setVirtualHost(Config.RabbitMQ.virtualHost)
      factory.setConnectionTimeout(Config.Timing.connectionTimeout.value * 1000)

      val connection = factory.newConnection()
      val channel = connection.createChannel()

      ConnectionResult.Connected(connection, channel)
    catch
      case e: Exception =>
        val error = s"Failed to connect to RabbitMQ: ${e.getMessage}"
        Logging.logError(conversationId, Logging.Source.Agent, error, Some(e))
        ConnectionResult.Failed(error)

  /** Declare a durable queue */
  def declareQueue(channel: Channel, queueName: String): Unit =
    channel.queueDeclare(
      queueName,
      true,   // durable
      false,  // exclusive
      false,  // autoDelete
      null    // arguments
    )

  /** Set QoS (prefetch count) */
  def setQos(channel: Channel, prefetchCount: Int): Unit =
    channel.basicQos(prefetchCount)

  /** Publish a message to a queue */
  def publish(channel: Channel, queueName: String, message: String, conversationId: ConversationId): PublishResult =
    try
      val props = new AMQP.BasicProperties.Builder()
        .deliveryMode(2) // persistent
        .contentType("application/json")
        .build()

      channel.basicPublish(
        "",         // exchange (default)
        queueName,  // routing key = queue name
        props,
        message.getBytes("UTF-8")
      )
      PublishResult.Published
    catch
      case e: Exception =>
        val error = s"Failed to publish message: ${e.getMessage}"
        Logging.logError(conversationId, Logging.Source.Agent, error, Some(e))
        PublishResult.Failed(error)

  /** Acknowledge a message */
  def ack(channel: Channel, deliveryTag: Long): Unit =
    channel.basicAck(deliveryTag, false)

  /** Negative acknowledge (reject) a message */
  def nack(channel: Channel, deliveryTag: Long, requeue: Boolean): Unit =
    channel.basicNack(deliveryTag, false, requeue)

  /** Consume one message with timeout (blocking) */
  def consumeOne(channel: Channel, queueName: String, conversationId: ConversationId, timeoutMs: Int = 5000): ConsumeResult =
    try
      val response = channel.basicGet(queueName, false) // manual ack
      if response != null then
        val body = new String(response.getBody, "UTF-8")
        ConsumeResult.Message(body, response.getEnvelope.getDeliveryTag)
      else
        ConsumeResult.Timeout
    catch
      case e: Exception =>
        val error = s"Failed to consume message: ${e.getMessage}"
        Logging.logError(conversationId, Logging.Source.Agent, error, Some(e))
        ConsumeResult.Failed(error)

  /** Close connection and channel */
  def close(connection: Connection, channel: Channel): Unit =
    try
      if channel.isOpen then channel.close()
      if connection.isOpen then connection.close()
    catch
      case _: Exception => () // Ignore close errors
