package com.llmagent.common

import java.util.UUID
import zio.json.*

/** General-purpose constrained opaque types to make invalid states unrepresentable */
object Types:

  // === Constrained String Types ===

  /** Non-empty string - validates that content is not blank */
  opaque type NonEmptyString = String

  object NonEmptyString:
    def apply(s: String): Option[NonEmptyString] =
      if s.trim.nonEmpty then Some(s.trim) else None

    def unsafe(s: String): NonEmptyString = s

    extension (s: NonEmptyString)
      def value: String = s

  // === Constrained Numeric Types ===

  /** Positive integer (> 0) - for counts, timeouts, etc. */
  opaque type PositiveInt = Int

  object PositiveInt:
    def apply(n: Int): Option[PositiveInt] =
      if n > 0 then Some(n) else None

    def unsafe(n: Int): PositiveInt = n

    extension (n: PositiveInt)
      def value: Int = n

  /** Non-negative integer (>= 0) - for counters, indices, etc. */
  opaque type NonNegativeInt = Int

  object NonNegativeInt:
    val zero: NonNegativeInt = 0

    def apply(n: Int): Option[NonNegativeInt] =
      if n >= 0 then Some(n) else None

    def unsafe(n: Int): NonNegativeInt = n

    extension (n: NonNegativeInt)
      def value: Int = n

  /** Non-negative long (>= 0) - for latency, byte counts, etc. */
  opaque type NonNegativeLong = Long

  object NonNegativeLong:
    val zero: NonNegativeLong = 0L

    def apply(n: Long): Option[NonNegativeLong] =
      if n >= 0 then Some(n) else None

    def unsafe(n: Long): NonNegativeLong = n

    extension (n: NonNegativeLong)
      def value: Long = n

  // === Measurement Types (no validation - just wrappers) ===

  /** Latency in milliseconds */
  opaque type LatencyMs = Long

  object LatencyMs:
    val zero: LatencyMs = 0L

    def apply(value: Long): LatencyMs = value

    extension (l: LatencyMs)
      def value: Long = l

    given JsonEncoder[LatencyMs] = JsonEncoder.long.contramap(_.value)
    given JsonDecoder[LatencyMs] = JsonDecoder.long.map(apply)

  /** Token count for LLM usage */
  opaque type TokenCount = Int

  object TokenCount:
    val zero: TokenCount = 0

    def apply(value: Int): TokenCount = value

    extension (t: TokenCount)
      def value: Int = t

    given JsonEncoder[TokenCount] = JsonEncoder.int.contramap(_.value)
    given JsonDecoder[TokenCount] = JsonDecoder.int.map(apply)

  // === ID Types ===

  /** Trace ID for distributed tracing */
  opaque type TraceId = String

  object TraceId:
    def generate(): TraceId = UUID.randomUUID().toString

    def fromString(s: String): Option[TraceId] =
      if s.nonEmpty then Some(s) else None

    def unsafe(s: String): TraceId = s

    extension (t: TraceId)
      def value: String = t

  // === Unified Result Type ===

  /** Generic query result - replaces PromptResult and GuardrailQueryResult */
  enum QueryResult[+A]:
    case Success(value: A)
    case NotFound(message: String)
    case Error(message: String)

  object QueryResult:
    def fromOption[A](opt: Option[A], notFoundMsg: String): QueryResult[A] =
      opt match
        case Some(v) => QueryResult.Success(v)
        case None => QueryResult.NotFound(notFoundMsg)

    def fromEither[A](either: Either[String, A]): QueryResult[A] =
      either match
        case Right(v) => QueryResult.Success(v)
        case Left(err) => QueryResult.Error(err)
