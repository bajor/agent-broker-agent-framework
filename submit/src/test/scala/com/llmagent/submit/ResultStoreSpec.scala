package com.llmagent.submit

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.test.TestAspect.*
import com.llmagent.common.Messages.{TaskId, Result as MsgResult, ResultStatus}

object ResultStoreSpec extends ZIOSpecDefault:

  def spec = suite("ResultStore")(
    test("put and get round-trips") {
      ResultStore.clear()
      val taskId = TaskId.unsafe("task-1")
      val result = MsgResult.success(taskId, "response")
      ResultStore.put(result)
      assertTrue(ResultStore.get(taskId) == Some(result))
    },
    test("getByString finds by ID string") {
      ResultStore.clear()
      val taskId = TaskId.unsafe("task-2")
      val result = MsgResult.success(taskId, "response")
      ResultStore.put(result)
      assertTrue(ResultStore.getByString("task-2") == Some(result))
    },
    test("get returns None for non-existent ID") {
      ResultStore.clear()
      val taskId = TaskId.unsafe("non-existent")
      assertTrue(ResultStore.get(taskId) == None)
    },
    test("contains returns true for existing result") {
      ResultStore.clear()
      val taskId = TaskId.unsafe("task-3")
      ResultStore.put(MsgResult.success(taskId, "response"))
      assertTrue(ResultStore.contains(taskId))
    },
    test("contains returns false for non-existent result") {
      ResultStore.clear()
      val taskId = TaskId.unsafe("non-existent")
      assertTrue(!ResultStore.contains(taskId))
    },
    test("all returns all stored results") {
      ResultStore.clear()
      ResultStore.put(MsgResult.success(TaskId.unsafe("t1"), "r1"))
      ResultStore.put(MsgResult.failure(TaskId.unsafe("t2"), "error"))
      assertTrue(ResultStore.all.size == 2)
    },
    test("clear removes all results") {
      ResultStore.put(MsgResult.success(TaskId.unsafe("t"), "r"))
      ResultStore.clear()
      assertTrue(ResultStore.size == 0)
    },
    test("size returns correct count") {
      ResultStore.clear()
      assertTrue(ResultStore.size == 0)
      ResultStore.put(MsgResult.success(TaskId.unsafe("t1"), "r1"))
      assertTrue(ResultStore.size == 1)
      ResultStore.put(MsgResult.success(TaskId.unsafe("t2"), "r2"))
      assertTrue(ResultStore.size == 2)
    },
    test("overwrites result with same task ID") {
      ResultStore.clear()
      val taskId = TaskId.unsafe("t")
      ResultStore.put(MsgResult.success(taskId, "response1"))
      ResultStore.put(MsgResult.success(taskId, "response2"))
      val result = ResultStore.get(taskId)
      assertTrue(
        ResultStore.size == 1 &&
        (result match
          case Some(MsgResult(_, ResultStatus.Success(r))) => r == "response2"
          case _ => false
        )
      )
    },
    test("handles both success and failure results") {
      ResultStore.clear()
      val successId = TaskId.unsafe("success")
      val failureId = TaskId.unsafe("failure")
      ResultStore.put(MsgResult.success(successId, "ok"))
      ResultStore.put(MsgResult.failure(failureId, "error"))
      val successResult = ResultStore.get(successId)
      val failureResult = ResultStore.get(failureId)
      assertTrue(
        (successResult match
          case Some(MsgResult(_, ResultStatus.Success(_))) => true
          case _ => false
        ) &&
        (failureResult match
          case Some(MsgResult(_, ResultStatus.Failure(_))) => true
          case _ => false
        )
      )
    },
    test("handles concurrent access") {
      ResultStore.clear()
      for
        _ <- ZIO.foreachParDiscard(1 to 100) { i =>
          ZIO.succeed(ResultStore.put(
            MsgResult.success(TaskId.unsafe(s"task-$i"), s"response-$i")
          ))
        }
      yield assertTrue(ResultStore.size == 100)
    }
  ) @@ sequential
