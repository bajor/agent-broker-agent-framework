package com.llmagent.common.observability

/** Configuration constants for the observability system */
object ObservabilityConfig {

  object Database {
    val promptsDb: String = "prompts.db"
    val guardrailsDb: String = "guardrails.db"
  }

  object Logs {
    val directory: String = "conversation_logs"
  }
}
