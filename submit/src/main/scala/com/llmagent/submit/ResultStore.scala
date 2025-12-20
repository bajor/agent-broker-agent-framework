package com.llmagent.submit

import com.llmagent.common.Messages.*
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

/** Thread-safe result store for caching task results */
object ResultStore:

  private val results = new ConcurrentHashMap[String, Result]()

  /** Store a result */
  def put(result: Result): Unit =
    results.put(result.taskId.value, result)

  /** Get a result by task ID */
  def get(taskId: TaskId): Option[Result] =
    Option(results.get(taskId.value))

  /** Get a result by task ID string */
  def getByString(taskIdStr: String): Option[Result] =
    Option(results.get(taskIdStr))

  /** Check if a result exists */
  def contains(taskId: TaskId): Boolean =
    results.containsKey(taskId.value)

  /** Get all results */
  def all: List[Result] =
    results.values().asScala.toList

  /** Clear all results */
  def clear(): Unit =
    results.clear()

  /** Count of stored results */
  def size: Int =
    results.size()
