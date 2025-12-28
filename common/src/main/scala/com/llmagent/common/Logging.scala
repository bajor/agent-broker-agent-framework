package com.llmagent.common

import java.io.{File, FileWriter, PrintWriter}
import java.time.{Instant, LocalDateTime}
import java.time.format.DateTimeFormatter
import scala.util.{Try, Using}
import scala.annotation.tailrec
import zio.json.*
import com.llmagent.common.Config
import com.llmagent.common.Config.RetryCount
import com.llmagent.common.observability.Types.ConversationId
import com.llmagent.common.observability.Types.ConversationId.given
import com.llmagent.common.observability.Types.given
import com.llmagent.common.observability.ObservabilityConfig

/** Structured logging with type-safe source identification
  * - Agent logs go to: agent_logs/{conversationId}_{agentName}.jsonl
  * - Conversation logs go to: conversation_logs/{conversationId}.jsonl
  * - Also prints to console for runtime visibility
  */
object Logging:

  /** Log source - sealed to ensure exhaustive handling */
  enum Source:
    case Agent
    case Submit
    case LLM
    case CLI

  /** Log level */
  enum Level:
    case Info
    case Error

  /** JSON-serializable log entry */
  final case class LogEntry(
    @jsonField("type") entryType: String,
    @jsonField("conversation_id") conversationId: String,
    level: String,
    source: String,
    @jsonField("agent_name") agentName: Option[String],
    message: String,
    exception: Option[String],
    timestamp: Instant,
    prompt: Option[String] = None,
    response: Option[String] = None,
    model: Option[String] = None,
    @jsonField("duration_ms") durationMs: Option[Long] = None
  ) derives JsonEncoder, JsonDecoder

  private val conversationLogsDir = ObservabilityConfig.Logs.conversationDirectory
  private val agentLogsDir = ObservabilityConfig.Logs.agentDirectory
  private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

  /** Print to console for runtime visibility */
  private def printToConsole(
    level: Level,
    agentName: Option[String],
    conversationId: ConversationId,
    message: String
  ): Unit =
    import ConversationId.value
    val time = LocalDateTime.now().format(timeFormatter)
    val convId = {
      val id = conversationId.value
      if id.length > 8 then id.take(8) else id
    }
    val agent = agentName.getOrElse("System")
    val levelStr = level match
      case Level.Info => "INFO "
      case Level.Error => "ERROR"

    val output = s"[$time] [$levelStr] [$agent] [$convId] $message"
    level match
      case Level.Info => println(output)
      case Level.Error => Console.err.println(output)

  private def ensureDirExists(dir: String): Unit =
    val f = new File(dir)
    if !f.exists() then f.mkdirs()

  /** Retry configuration for file operations - from centralized Config */
  private val maxRetries = Config.Logging.maxRetries.value
  private val initialDelayMs = Config.Logging.initialDelayMs
  private val maxDelayMs = Config.Logging.maxDelayMs

  /** Execute a file operation with retry logic for handling file locks.
    * Uses exponential backoff between retries.
    */
  @tailrec
  private def withRetry[A](operation: => A, attempt: Int = 1, lastError: Option[Throwable] = None): Option[A] =
    if attempt > maxRetries then
      lastError.foreach { e =>
        System.err.println(s"[Logging] Failed to write to log file after $maxRetries attempts: ${e.getMessage}")
      }
      None
    else
      Try(operation) match
        case scala.util.Success(result) => Some(result)
        case scala.util.Failure(error) =>
          val delayMs = math.min(initialDelayMs * math.pow(2, attempt - 1).toInt, maxDelayMs)
          // Note: Intentionally blocking - logging must complete before proceeding
          // to ensure log entries are written in order and file handles are released
          Thread.sleep(delayMs)
          withRetry(operation, attempt + 1, Some(error))

  private def agentLogFilePath(conversationId: ConversationId, agentName: String): String =
    import ConversationId.value
    val sanitizedAgentName = agentName.replaceAll("[^a-zA-Z0-9_-]", "_")
    s"$agentLogsDir/${conversationId.value}_${sanitizedAgentName}.jsonl"

  private def conversationLogFilePath(conversationId: ConversationId): String =
    import ConversationId.value
    s"$conversationLogsDir/${conversationId.value}.jsonl"

  private def toJsonLine(
    conversationId: ConversationId,
    level: Level,
    source: Source,
    message: String,
    agentName: Option[String],
    exception: Option[Throwable],
    extraFields: Map[String, String]
  ): String =
    import ConversationId.value
    val exceptionStr = exception.map(e => s"${e.getClass.getName}: ${e.getMessage}")

    val entry = LogEntry(
      entryType = "log",
      conversationId = conversationId.value,
      level = level.toString.toUpperCase,
      source = source.toString.toUpperCase,
      agentName = agentName,
      message = message,
      exception = exceptionStr,
      timestamp = Instant.now(),
      prompt = extraFields.get("prompt"),
      response = extraFields.get("response"),
      model = extraFields.get("model"),
      durationMs = extraFields.get("duration_ms").flatMap(_.toLongOption)
    )
    entry.toJson

  private def appendToAgentLog(conversationId: ConversationId, agentName: String, jsonLine: String): Unit =
    ensureDirExists(agentLogsDir)
    val filePath = agentLogFilePath(conversationId, agentName)
    withRetry {
      Using.resource(new PrintWriter(new FileWriter(filePath, true))) { writer =>
        writer.println(jsonLine)
      }
    }

  private def appendToConversationLog(conversationId: ConversationId, jsonLine: String): Unit =
    ensureDirExists(conversationLogsDir)
    val filePath = conversationLogFilePath(conversationId)
    withRetry {
      Using.resource(new PrintWriter(new FileWriter(filePath, true))) { writer =>
        writer.println(jsonLine)
      }
    }

  /** Log an info message for an agent - writes to agent_logs/{conversationId}_{agentName}.jsonl */
  def info(conversationId: ConversationId, source: Source, agentName: String, message: String): Unit =
    printToConsole(Level.Info, Some(agentName), conversationId, message)
    val line = toJsonLine(conversationId, Level.Info, source, message, Some(agentName), None, Map.empty)
    appendToAgentLog(conversationId, agentName, line)

  /** Log an info message (backward compatible - writes to conversation_logs) */
  def info(conversationId: ConversationId, source: Source, message: String): Unit =
    printToConsole(Level.Info, None, conversationId, message)
    val line = toJsonLine(conversationId, Level.Info, source, message, None, None, Map.empty)
    appendToConversationLog(conversationId, line)

  /** Log an error message for an agent - writes to agent_logs/{conversationId}_{agentName}.jsonl */
  def logError(
    conversationId: ConversationId,
    source: Source,
    agentName: String,
    message: String,
    exception: Option[Throwable]
  ): Unit =
    val errorMsg = exception.map(e => s"$message: ${e.getMessage}").getOrElse(message)
    printToConsole(Level.Error, Some(agentName), conversationId, errorMsg)
    val line = toJsonLine(conversationId, Level.Error, source, message, Some(agentName), exception, Map.empty)
    appendToAgentLog(conversationId, agentName, line)

  /** Log an error message for an agent (no exception) */
  def logError(
    conversationId: ConversationId,
    source: Source,
    agentName: String,
    message: String
  ): Unit =
    logError(conversationId, source, agentName, message, None)

  /** Log an error message (backward compatible - writes to conversation_logs) */
  def logError(
    conversationId: ConversationId,
    source: Source,
    message: String,
    exception: Option[Throwable] = None
  ): Unit =
    val errorMsg = exception.map(e => s"$message: ${e.getMessage}").getOrElse(message)
    printToConsole(Level.Error, None, conversationId, errorMsg)
    val line = toJsonLine(conversationId, Level.Error, source, message, None, exception, Map.empty)
    appendToConversationLog(conversationId, line)

  /** Log an LLM query and response */
  def logLlmQuery(
    conversationId: ConversationId,
    agentName: String,
    prompt: String,
    response: String,
    model: String,
    durationMs: Long
  ): Unit =
    val extraFields = Map(
      "prompt" -> prompt,
      "response" -> response,
      "model" -> model,
      "duration_ms" -> durationMs.toString
    )
    val line = toJsonLine(conversationId, Level.Info, Source.LLM, "LLM query", Some(agentName), None, extraFields)
    appendToAgentLog(conversationId, agentName, line)

  /** Log an LLM query and response (backward compatible) */
  def logLlmQuery(
    conversationId: ConversationId,
    prompt: String,
    response: String,
    model: String,
    durationMs: Long
  ): Unit =
    val extraFields = Map(
      "prompt" -> prompt,
      "response" -> response,
      "model" -> model,
      "duration_ms" -> durationMs.toString
    )
    val line = toJsonLine(conversationId, Level.Info, Source.LLM, "LLM query", None, None, extraFields)
    appendToConversationLog(conversationId, line)
