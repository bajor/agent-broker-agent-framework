package com.llmagent.common

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.json.*
import zio.json.ast.Json
import com.llmagent.common.A2AJson.*

object A2AJsonSpec extends ZIOSpecDefault:

  def spec = suite("A2AJson")(
    envelopeSuite,
    userInputSuite,
    agentInputSuite,
    agentOutputSuite,
    userOutputSuite,
    executionStatsSuite
  )

  val envelopeSuite = suite("A2AEnvelope")(
    test("round-trips through JSON") {
      val payload = Json.Obj("key" -> Json.Str("value"))
      val envelope = A2AEnvelope(
        fromAgent = "agent1",
        toAgent = "agent2",
        traceId = "trace-123",
        conversationId = "conv-456",
        payloadType = "TestPayload",
        payload = payload
      )
      val json = encodeEnvelope(envelope)
      val decoded = decodeEnvelope(json)
      assertTrue(decoded == Some(envelope))
    },
    test("uses snake_case field names") {
      val payload = Json.Null
      val envelope = A2AEnvelope("a", "b", "t", "c", "p", payload)
      val json = encodeEnvelope(envelope)
      assertTrue(
        json.contains("\"from_agent\"") &&
        json.contains("\"to_agent\"") &&
        json.contains("\"trace_id\"") &&
        json.contains("\"conversation_id\"") &&
        json.contains("\"payload_type\"")
      )
    },
    test("decodeEnvelope returns None for invalid JSON") {
      assertTrue(decodeEnvelope("invalid json") == None)
    },
    test("decodeEnvelope returns None for missing fields") {
      assertTrue(decodeEnvelope("""{"from_agent": "a"}""") == None)
    }
  )

  val userInputSuite = suite("UserInput")(
    test("round-trips through JSON") {
      val input = UserInput("test prompt", Map("key" -> "value"))
      val json = encodeUserInput(input)
      val decoded = decodeUserInput(json)
      assertTrue(decoded == Some(input))
    },
    test("handles empty metadata") {
      val input = UserInput("test prompt")
      val json = encodeUserInput(input)
      val decoded = decodeUserInput(json)
      assertTrue(decoded == Some(input))
    },
    test("decodeUserInput returns None for invalid JSON") {
      assertTrue(decodeUserInput("not json") == None)
    },
    test("preserves special characters in prompt") {
      val input = UserInput("Hello \"world\" with 'quotes' and\nnewlines")
      val json = encodeUserInput(input)
      val decoded = decodeUserInput(json)
      assertTrue(decoded == Some(input))
    }
  )

  val agentInputSuite = suite("AgentInput")(
    test("round-trips through JSON") {
      val input = AgentInput("task description", Map("context" -> "data"))
      val json = encodeAgentInput(input)
      val decoded = decodeAgentInput(json)
      assertTrue(decoded == Some(input))
    },
    test("handles empty context") {
      val input = AgentInput("task description")
      val json = encodeAgentInput(input)
      val decoded = decodeAgentInput(json)
      assertTrue(decoded == Some(input))
    },
    test("decodeAgentInput returns None for invalid JSON") {
      assertTrue(decodeAgentInput("{malformed") == None)
    }
  )

  val agentOutputSuite = suite("AgentOutput")(
    test("round-trips through JSON") {
      val output = AgentOutput("summary", 5, 1000L)
      val json = encodeAgentOutput(output)
      val decoded = decodeAgentOutput(json)
      assertTrue(decoded == Some(output))
    },
    test("handles zero values") {
      val output = AgentOutput("", 0, 0L)
      val json = encodeAgentOutput(output)
      val decoded = decodeAgentOutput(json)
      assertTrue(decoded == Some(output))
    },
    test("handles large latency values") {
      val output = AgentOutput("summary", 100, Long.MaxValue)
      val json = encodeAgentOutput(output)
      val decoded = decodeAgentOutput(json)
      assertTrue(decoded == Some(output))
    },
    test("decodeAgentOutput returns None for invalid JSON") {
      assertTrue(decodeAgentOutput("[]") == None)
    }
  )

  val userOutputSuite = suite("UserOutput")(
    test("round-trips through JSON") {
      val stats = ExecutionStats(3, 500L)
      val output = UserOutput("response text", stats)
      val json = encodeUserOutput(output)
      val decoded = decodeUserOutput(json)
      assertTrue(decoded == Some(output))
    },
    test("preserves nested stats") {
      val stats = ExecutionStats(10, 2000L)
      val output = UserOutput("result", stats)
      val json = encodeUserOutput(output)
      val decoded = decodeUserOutput(json)
      assertTrue(
        decoded.isDefined &&
        decoded.get.stats.totalToolCalls == 10 &&
        decoded.get.stats.totalLatencyMs == 2000L
      )
    }
  )

  val executionStatsSuite = suite("ExecutionStats")(
    test("round-trips through JSON") {
      val stats = ExecutionStats(5, 1500L)
      val json = encodeExecutionStats(stats)
      val decoded = decodeExecutionStats(json)
      assertTrue(decoded == Some(stats))
    },
    test("handles boundary values") {
      val stats = ExecutionStats(Int.MaxValue, Long.MaxValue)
      val json = encodeExecutionStats(stats)
      val decoded = decodeExecutionStats(json)
      assertTrue(decoded == Some(stats))
    }
  )
