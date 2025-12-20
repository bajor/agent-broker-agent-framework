package com.llmagent.common.observability

import java.io.{File, FileWriter, PrintWriter}
import scala.util.Using
import zio.json.*
import Types.*

/** Logger for LLM exchanges - appends to JSONL files per conversation */
object ConversationLogger:

  private val logsDir = ObservabilityConfig.Logs.directory

  private def ensureLogsDirExists(): Unit =
    val dir = new File(logsDir)
    if !dir.exists() then dir.mkdirs()

  private def logFilePath(conversationId: ConversationId): String =
    import ConversationId.value
    s"$logsDir/${conversationId.value}.jsonl"

  /** Log an LLM exchange - appends to conversation file */
  def logExchange(log: ExchangeLog): Unit =
    ensureLogsDirExists()
    val filePath = logFilePath(log.conversationId)
    Using.resource(new PrintWriter(new FileWriter(filePath, true))) { writer =>
      writer.println(log.toJson)
    }

  /** Log agent activity - appends to conversation file */
  def logAgentActivity(log: AgentActivityLog): Unit =
    ensureLogsDirExists()
    val filePath = logFilePath(log.conversationId)
    Using.resource(new PrintWriter(new FileWriter(filePath, true))) { writer =>
      writer.println(log.toJson)
    }
