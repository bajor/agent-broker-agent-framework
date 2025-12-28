package com.llmagent.common

import zio.*
import zio.test.*
import zio.test.Assertion.*
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.DurationInt
object ConfigSpec extends ZIOSpecDefault:

  def spec = suite("Config")(
    portSuite,
    timeoutSecondsSuite,
    retryCountSuite,
    queueNamingSuite
  )

  val portSuite = suite("Port")(
    test("rejects zero") {
      assertTrue(com.llmagent.common.Config.Port(0) == None)
    },
    test("rejects negative") {
      assertTrue(
        com.llmagent.common.Config.Port(-1) == None &&
        com.llmagent.common.Config.Port(-100) == None
      )
    },
    test("rejects values > 65535") {
      assertTrue(
        com.llmagent.common.Config.Port(65536) == None &&
        com.llmagent.common.Config.Port(70000) == None
      )
    },
    test("accepts valid port numbers") {
      assertTrue(
        com.llmagent.common.Config.Port(1).isDefined &&
        com.llmagent.common.Config.Port(80).isDefined &&
        com.llmagent.common.Config.Port(8080).isDefined &&
        com.llmagent.common.Config.Port(65535).isDefined
      )
    },
    test("value extension returns the port number") {
      assertTrue(com.llmagent.common.Config.Port(8080).get.value == 8080)
    },
    test("unsafe bypasses validation") {
      assertTrue(com.llmagent.common.Config.Port.unsafe(0).value == 0)
    }
  )

  val timeoutSecondsSuite = suite("TimeoutSeconds")(
    test("rejects zero") {
      assertTrue(com.llmagent.common.Config.TimeoutSeconds(0) == None)
    },
    test("rejects negative") {
      assertTrue(
        com.llmagent.common.Config.TimeoutSeconds(-1) == None &&
        com.llmagent.common.Config.TimeoutSeconds(-100) == None
      )
    },
    test("accepts positive") {
      assertTrue(
        com.llmagent.common.Config.TimeoutSeconds(1).isDefined &&
        com.llmagent.common.Config.TimeoutSeconds(30).isDefined &&
        com.llmagent.common.Config.TimeoutSeconds(300).isDefined
      )
    },
    test("toDuration converts correctly") {
      val timeout = com.llmagent.common.Config.TimeoutSeconds.unsafe(5)
      assertTrue(timeout.toDuration == DurationInt(5).seconds)
    },
    test("unsafe bypasses validation") {
      assertTrue(com.llmagent.common.Config.TimeoutSeconds.unsafe(0).value == 0)
    }
  )

  val retryCountSuite = suite("RetryCount")(
    test("accepts zero") {
      assertTrue(
        com.llmagent.common.Config.RetryCount(0).isDefined &&
        com.llmagent.common.Config.RetryCount(0).get.value == 0
      )
    },
    test("rejects negative") {
      assertTrue(
        com.llmagent.common.Config.RetryCount(-1) == None &&
        com.llmagent.common.Config.RetryCount(-100) == None
      )
    },
    test("accepts positive") {
      assertTrue(
        com.llmagent.common.Config.RetryCount(1).isDefined &&
        com.llmagent.common.Config.RetryCount(10).isDefined
      )
    },
    test("unsafe bypasses validation") {
      assertTrue(com.llmagent.common.Config.RetryCount.unsafe(-5).value == -5)
    }
  )

  val queueNamingSuite = suite("QueueNaming")(
    test("toQueueName formats with prefix and suffix") {
      assertTrue(com.llmagent.common.Config.QueueNaming.toQueueName("codegen") == "agent_codegen_tasks")
    },
    test("fromQueueName extracts agent name") {
      assertTrue(com.llmagent.common.Config.QueueNaming.fromQueueName("agent_codegen_tasks") == "codegen")
    },
    test("round-trip preserves agent name") {
      val name = "myagent"
      assertTrue(com.llmagent.common.Config.QueueNaming.fromQueueName(com.llmagent.common.Config.QueueNaming.toQueueName(name)) == name)
    },
    test("works with various agent names") {
      val names = List("preprocessor", "explainer", "refiner", "my-agent-123")
      assertTrue(names.forall(n =>
        com.llmagent.common.Config.QueueNaming.fromQueueName(com.llmagent.common.Config.QueueNaming.toQueueName(n)) == n
      ))
    }
  )
