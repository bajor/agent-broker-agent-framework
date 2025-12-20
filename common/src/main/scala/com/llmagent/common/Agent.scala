package com.llmagent.common

import java.util.UUID
import scala.collection.mutable.ListBuffer
import com.llmagent.common.observability.Types.ConversationId

/** Agent abstraction with typed Input/Output and A2A protocol */
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

  /** Opaque type for reflection count */
  opaque type ReflectionCount = Int

  object ReflectionCount:
    val zero: ReflectionCount = 0

    def apply(n: Int): Option[ReflectionCount] =
      if n >= 0 then Some(n) else None

    def unsafe(n: Int): ReflectionCount = n

    extension (r: ReflectionCount)
      def value: Int = r
      def increment: ReflectionCount = r + 1
      def hasReflectionsLeft(max: Int): Boolean = r < max

  /** A2A Protocol - Agent to Agent communication message */
  final case class A2AMessage[T](
    fromAgent: AgentId,
    toAgent: AgentId,
    payload: T,
    traceId: String
  )

  object A2AMessage:
    def create[T](from: AgentId, to: AgentId, payload: T): A2AMessage[T] =
      A2AMessage(from, to, payload, UUID.randomUUID().toString)

    def initial[T](to: AgentId, payload: T): A2AMessage[T] =
      A2AMessage(AgentId.unsafe("__input__"), to, payload, UUID.randomUUID().toString)

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

  /** Record of a single tool execution */
  final case class ToolExecution[I, O](
    toolName: String,
    input: I,
    result: ToolResult[O],
    reflectionsUsed: Int
  )

  /** Context that accumulates tool executions during tool phase */
  final class ToolPhaseContext:
    private val executions: ListBuffer[ToolExecution[?, ?]] = ListBuffer.empty

    def record[I, O](execution: ToolExecution[I, O]): Unit =
      executions += execution

    def allExecutions: List[ToolExecution[?, ?]] = executions.toList

    def successfulExecutions: List[ToolExecution[?, ?]] =
      executions.filter(_.result.isSuccess).toList

    def failedExecutions: List[ToolExecution[?, ?]] =
      executions.filterNot(_.result.isSuccess).toList

    def isEmpty: Boolean = executions.isEmpty

    def size: Int = executions.size

  /** Agent execution result */
  enum AgentResult[+O]:
    case Completed(output: O)
    case Failed(error: String)

    def map[U](f: O => U): AgentResult[U] = this match
      case Completed(o) => Completed(f(o))
      case Failed(e) => Failed(e)

  /** Agent trait - all agents have input and output types */
  trait AgentDef[I, O]:
    def id: AgentId
    def process(input: I): AgentResult[O]

  /** Agent with tool support - can call multiple tools then summarize */
  trait ToolAgent[I, O] extends AgentDef[I, O]:
    def tools: List[Tool[?, ?]]

    /**
     * Tool phase - call tools as needed, results are recorded in context.
     * Agent decides when to stop calling tools.
     */
    protected def runToolPhase(input: I, context: ToolPhaseContext): Unit

    /**
     * Summarize phase - after tool phase is done, produce output for next agent.
     * Has access to input and all tool executions.
     */
    protected def summarize(input: I, context: ToolPhaseContext): AgentResult[O]

    /** Execute a tool with reflection support, recording result in context */
    protected def executeTool[TI, TO](
      tool: Tool[TI, TO],
      toolInput: TI,
      onFailure: String => TI,
      context: ToolPhaseContext
    ): ToolResult[TO] = {
      import ReflectionCount.{hasReflectionsLeft, increment, value as rcValue}

      def executeWithReflection(
        currentInput: TI,
        reflections: ReflectionCount
      ): (ToolResult[TO], Int) = {
        tool.execute(currentInput) match {
          case s @ ToolResult.Success(_) =>
            (s, reflections.rcValue)
          case ToolResult.Failure(feedback) =>
            if reflections.hasReflectionsLeft(Config.Agent.maxReflections.value) then {
              val newInput = onFailure(feedback)
              executeWithReflection(newInput, reflections.increment)
            } else {
              (ToolResult.Failure(s"Max reflections exceeded. Last: $feedback"), reflections.rcValue)
            }
        }
      }

      val (result, reflectionsUsed) = executeWithReflection(toolInput, ReflectionCount.zero)
      context.record(ToolExecution(tool.name, toolInput, result, reflectionsUsed))
      result
    }

    /** Main process: run tool phase, then summarize */
    override def process(input: I): AgentResult[O] = {
      val context = new ToolPhaseContext
      runToolPhase(input, context)
      summarize(input, context)
    }
