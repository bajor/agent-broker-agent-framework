package com.llmagent.common.observability

import java.io.{File, FileWriter, PrintWriter}
import scala.util.{Try, Using}
import scala.annotation.tailrec
import zio.json.*
import Types.*

/** Logger for LLM exchanges - appends to JSONL files per conversation */
object ConversationLogger:

  private val logsDir = ObservabilityConfig.Logs.directory

  /** Retry configuration for file operations */
  private val maxRetries = 5
  private val initialDelayMs = 50
  private val maxDelayMs = 500

  private def ensureLogsDirExists(): Unit =
    val dir = new File(logsDir)
    if !dir.exists() then dir.mkdirs()

  private def logFilePath(conversationId: ConversationId): String =
    import ConversationId.value
    s"$logsDir/${conversationId.value}.jsonl"

  /** Execute a file operation with retry logic for handling file locks.
    * Uses exponential backoff between retries.
    */
  @tailrec
  private def withRetry[A](operation: => A, attempt: Int = 1, lastError: Option[Throwable] = None): Option[A] =
    if attempt > maxRetries then
      lastError.foreach { e =>
        System.err.println(s"[ConversationLogger] Failed to write to log file after $maxRetries attempts: ${e.getMessage}")
      }
      None
    else
      Try(operation) match
        case scala.util.Success(result) => Some(result)
        case scala.util.Failure(error) =>
          val delayMs = math.min(initialDelayMs * math.pow(2, attempt - 1).toInt, maxDelayMs)
          Thread.sleep(delayMs)
          withRetry(operation, attempt + 1, Some(error))

  /** Log an LLM exchange - appends to conversation file */
  def logExchange(log: ExchangeLog): Unit =
    ensureLogsDirExists()
    val filePath = logFilePath(log.conversationId)
    withRetry {
      Using.resource(new PrintWriter(new FileWriter(filePath, true))) { writer =>
        writer.println(log.toJson)
      }
    }

  /** Log agent activity - appends to conversation file */
  def logAgentActivity(log: AgentActivityLog): Unit =
    ensureLogsDirExists()
    val filePath = logFilePath(log.conversationId)
    withRetry {
      Using.resource(new PrintWriter(new FileWriter(filePath, true))) { writer =>
        writer.println(log.toJson)
      }
    }
