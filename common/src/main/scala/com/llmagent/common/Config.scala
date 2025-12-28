package com.llmagent.common

import scala.concurrent.duration.*

/** Type-safe configuration using opaque types to make invalid states unrepresentable */
object Config:

  // Opaque types for type safety - values can only be created through validated constructors
  opaque type Port = Int
  opaque type TimeoutSeconds = Int
  opaque type RetryCount = Int

  object Port:
    def apply(value: Int): Option[Port] =
      if value > 0 && value <= 65535 then Some(value) else None

    def unsafe(value: Int): Port = value

    extension (p: Port)
      def value: Int = p

  object TimeoutSeconds:
    def apply(value: Int): Option[TimeoutSeconds] =
      if value > 0 then Some(value) else None

    def unsafe(value: Int): TimeoutSeconds = value

    extension (t: TimeoutSeconds)
      def value: Int = t
      def toDuration: FiniteDuration = t.seconds

  object RetryCount:
    def apply(value: Int): Option[RetryCount] =
      if value >= 0 then Some(value) else None

    def unsafe(value: Int): RetryCount = value

    extension (r: RetryCount)
      def value: Int = r

  /** RabbitMQ connection configuration */
  object RabbitMQ:
    val host: String = "localhost"
    val port: Port = Port.unsafe(5672)
    val user: String = "guest"
    val password: String = "guest"
    val virtualHost: String = "/"

  /** Queue names */
  object Queues:
    val taskQueue: String = "llm_tasks"
    val resultQueue: String = "llm_results"

  /** Ollama LLM configuration */
  object Ollama:
    val baseUrl: String = "http://localhost:11434"
    val generateEndpoint: String = "/api/generate"
    val defaultModel: String = "bielik_v3_4_5B_instruct_Q_8:latest"

  /** Timing configuration */
  object Timing:
    val requestTimeout: TimeoutSeconds = TimeoutSeconds.unsafe(300)
    val retryDelay: TimeoutSeconds = TimeoutSeconds.unsafe(5)
    val maxRetries: RetryCount = RetryCount.unsafe(3)
    val connectionTimeout: TimeoutSeconds = TimeoutSeconds.unsafe(30)

  /** Agent configuration */
  object Agent:
    val maxReflections: RetryCount = RetryCount.unsafe(3)

  /** Queue naming convention - centralized to ensure consistency */
  object QueueNaming:
    val prefix: String = "agent_"
    val suffix: String = "_tasks"

    def toQueueName(agentName: String): String = s"$prefix${agentName}$suffix"
    def fromQueueName(queueName: String): String =
      queueName.stripPrefix(prefix).stripSuffix(suffix)

  /** Logging configuration */
  object Logging:
    val maxRetries: RetryCount = RetryCount.unsafe(5)
    val initialDelayMs: Int = 50
    val maxDelayMs: Int = 500

  /** Python execution configuration */
  object Python:
    val executionTimeout: TimeoutSeconds = TimeoutSeconds.unsafe(30)
