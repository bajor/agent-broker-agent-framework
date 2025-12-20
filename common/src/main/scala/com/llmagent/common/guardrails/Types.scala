package com.llmagent.common.guardrails

import java.util.UUID
import java.time.Instant

/** Domain types for the guardrails safety system */
object Types:

  // === ID Types ===

  opaque type GuardrailId = String

  object GuardrailId:
    def generate(): GuardrailId = UUID.randomUUID().toString

    def fromString(s: String): Option[GuardrailId] =
      if s.nonEmpty then Some(s) else None

    def unsafe(s: String): GuardrailId = s

    extension (id: GuardrailId)
      def value: String = id

  /** ID for a pipeline's safety configuration (stored in guardrails.db) */
  opaque type SafetyConfigId = String

  object SafetyConfigId:
    def generate(): SafetyConfigId = UUID.randomUUID().toString

    def fromString(s: String): Option[SafetyConfigId] =
      if s.nonEmpty then Some(s) else None

    def unsafe(s: String): SafetyConfigId = s

    extension (id: SafetyConfigId)
      def value: String = id

  // === Domain Objects ===

  /** A guardrail rule that defines what content should be blocked */
  final case class Guardrail(
    id: GuardrailId,
    safetyConfigId: SafetyConfigId,
    name: String,
    description: String,
    checkPrompt: String,
    enabled: Boolean,
    createdAt: Instant
  )

  /**
   * Safety configuration for a pipeline - defines allowed scope and links to guardrails.
   * Stored in guardrails.db (not the runtime Pipeline class).
   */
  final case class PipelineSafetyConfig(
    id: SafetyConfigId,
    name: String,
    description: String,
    allowedScope: String,
    createdAt: Instant
  )

  // === Result Types ===

  /** Result of guardrail validation */
  enum GuardrailResult:
    case Passed
    case Blocked(guardrailName: String, reason: String)
    case Error(message: String)
