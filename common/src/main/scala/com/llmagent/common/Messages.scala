package com.llmagent.common

import java.util.UUID
import zio.json.*

/** Type-safe message types using ADTs and opaque types */
object Messages:

  /** Opaque type for task IDs - ensures type safety without runtime overhead */
  opaque type TaskId = String

  object TaskId:
    def generate(): TaskId = UUID.randomUUID().toString

    def fromString(s: String): Option[TaskId] =
      if s.nonEmpty then Some(s) else None

    def unsafe(s: String): TaskId = s

    extension (id: TaskId)
      def value: String = id

    given JsonEncoder[TaskId] = JsonEncoder.string.contramap(_.value)
    given JsonDecoder[TaskId] = JsonDecoder.string.mapOrFail { s =>
      fromString(s).toRight("TaskId cannot be empty")
    }

  /** Opaque type for retry count with validation */
  opaque type Retries = Int

  object Retries:
    val zero: Retries = 0

    def apply(n: Int): Option[Retries] =
      if n >= 0 then Some(n) else None

    def unsafe(n: Int): Retries = n

    extension (r: Retries)
      def value: Int = r
      def increment: Retries = r + 1
      def hasRetriesLeft(max: Int): Boolean = r < max

    given JsonEncoder[Retries] = JsonEncoder.int.contramap(_.value)
    given JsonDecoder[Retries] = JsonDecoder.int.mapOrFail { n =>
      apply(n).toRight("Retries must be non-negative")
    }

  // Export extensions so they're available in this scope
  export TaskId.value
  export Retries.{value, increment, hasRetriesLeft}

  /** A task to be processed by the worker */
  final case class Task(
    @jsonField("task_id") id: TaskId,
    prompt: String,
    model: String,
    @jsonField("retry_count") retries: Retries
  ) derives JsonEncoder, JsonDecoder

  object Task:
    def create(prompt: String, model: String = Config.Ollama.defaultModel): Task =
      Task(TaskId.generate(), prompt, model, Retries.zero)

  /** Result status - sealed trait ensures exhaustive pattern matching */
  enum ResultStatus:
    case Success(response: String)
    case Failure(error: String)

  object ResultStatus:
    given JsonEncoder[ResultStatus] = JsonEncoder.derived
    given JsonDecoder[ResultStatus] = JsonDecoder.derived

  /** A result from task processing */
  final case class Result(
    @jsonField("task_id") taskId: TaskId,
    status: ResultStatus
  )

  object Result:
    def success(taskId: TaskId, response: String): Result =
      Result(taskId, ResultStatus.Success(response))

    def failure(taskId: TaskId, error: String): Result =
      Result(taskId, ResultStatus.Failure(error))

    given JsonEncoder[Result] = JsonEncoder.derived
    given JsonDecoder[Result] = JsonDecoder.derived

  /** Ollama API request/response types */
  final case class OllamaRequest(
    model: String,
    prompt: String,
    stream: Boolean = false
  ) derives JsonEncoder, JsonDecoder

  final case class OllamaResponse(
    response: String
  ) derives JsonEncoder, JsonDecoder

  /** JSON serialization utilities using zio-json */
  object Json:
    def encodeTask(task: Task): String = task.toJson
    def decodeTask(json: String): Option[Task] = json.fromJson[Task].toOption
    def encodeResult(result: Result): String = result.toJson
    def decodeResult(json: String): Option[Result] = json.fromJson[Result].toOption
    def encodeOllamaRequest(prompt: String, model: String): String = OllamaRequest(model, prompt).toJson
    def decodeOllamaResponse(json: String): Option[String] = json.fromJson[OllamaResponse].toOption.map(_.response)
