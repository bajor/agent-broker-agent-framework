package com.llmagent.common.observability

/** Configuration constants for the observability system */
object ObservabilityConfig {

  object Database {
    val promptsDb: String = "prompts.db"
    val guardrailsDb: String = "guardrails.db"
  }

  object Logs {
    val conversationDirectory: String = "conversation_logs"
    val agentDirectory: String = "agent_logs"
    // Alias for backward compatibility
    val directory: String = conversationDirectory
  }
}
