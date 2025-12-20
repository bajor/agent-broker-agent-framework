package com.llmagent.common

import java.util.UUID

/** Core agent types - foundation for the DSL */
object Agent:

  /** Opaque type for agent IDs */
  opaque type AgentId = String

  object AgentId:
    def generate(): AgentId = UUID.randomUUID().toString
    def fromString(s: String): Option[AgentId] =
      if s.nonEmpty then Some(s) else None
    def unsafe(s: String): AgentId = s

    extension (id: AgentId)
      def value: String = id

  /** Tool result - success or failure with feedback */
  enum ToolResult[+T]:
    case Success(value: T)
    case Failure(feedback: String)

    def isSuccess: Boolean = this match
      case Success(_) => true
      case Failure(_) => false

    def map[U](f: T => U): ToolResult[U] = this match
      case Success(v) => Success(f(v))
      case Failure(fb) => Failure(fb)

  /** Tool definition - a local function an agent can call */
  trait Tool[I, O]:
    def name: String
    def execute(input: I): ToolResult[O]
