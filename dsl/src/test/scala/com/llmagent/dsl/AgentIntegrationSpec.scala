package com.llmagent.dsl

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.json.*
import com.llmagent.dsl.Types.*
import com.llmagent.common.{AgentInput, AgentOutput}
import com.llmagent.common.observability.Types.ConversationId

object AgentIntegrationSpec extends ZIOSpecDefault:

  def spec = suite("Agent Integration")(
    singleAgentSuite,
    composedPipelineSuite,
    errorHandlingSuite,
    jsonMessageFlowSuite
  )

  val singleAgentSuite = suite("single agent execution")(
    test("agent processes input through pipeline") {
      val agent = Agent("TestAgent")
        .readFrom(
          SourceQueue.fromAgentName("test"),
          s => s.fromJson[AgentInput].left.map(_.toString)
        )
        .process(
          Process.pure[AgentInput, AgentOutput]("transform")(input =>
            AgentOutput(s"Processed: ${input.taskDescription}", 1, 100L)
          )
        )
        .writeTo(DestQueue.fromAgentName("output"), _.toJson)
        .build

      val input = AgentInput("test task", Map.empty)
      for
        result <- agent.execute(input, mockContext)
      yield assertTrue(result match
        case PipelineResult.Success(out, _) =>
          out.summary.contains("Processed") && out.summary.contains("test task")
        case _ => false
      )
    },
    test("agent with identity process passes through unchanged") {
      val agent = Agent("IdentityAgent")
        .readFrom(SourceQueue.fromAgentName("test"), s => Right(s))
        .process(Process.identity[String])
        .writeTo(DestQueue.fromAgentName("output"), identity)
        .build
      for
        result <- agent.execute("hello", mockContext)
      yield assertTrue(result match
        case PipelineResult.Success(v, _) => v == "hello"
        case _ => false
      )
    }
  )

  val composedPipelineSuite = suite("composed pipeline")(
    test("multi-step pipeline processes correctly") {
      val step1 = Process.pure[String, Int]("parseLength")(_.length)
      val step2 = Process.pure[Int, String]("format")(n => s"Length: $n")
      val pipeline = step1 >>> step2

      val agent = Agent("MultiStep")
        .readFrom(SourceQueue.fromAgentName("input"), s => Right(s))
        .process(pipeline)
        .terminal(identity)
        .build

      for
        result <- agent.execute("hello", mockContext)
      yield assertTrue(result match
        case PipelineResult.Success(v, _) => v == "Length: 5"
        case _ => false
      )
    },
    test("three-step pipeline executes in order") {
      var executionOrder = List.empty[String]
      val step1 = Process.effect[Int, Int]("step1") { (n, _) =>
        executionOrder = executionOrder :+ "step1"
        ZIO.succeed(n + 1)
      }
      val step2 = Process.effect[Int, Int]("step2") { (n, _) =>
        executionOrder = executionOrder :+ "step2"
        ZIO.succeed(n * 2)
      }
      val step3 = Process.effect[Int, String]("step3") { (n, _) =>
        executionOrder = executionOrder :+ "step3"
        ZIO.succeed(s"Result: $n")
      }
      val pipeline = step1 >>> step2 >>> step3

      val agent = Agent("ThreeStep")
        .readFrom(SourceQueue.fromAgentName("input"), s => Right(s.toInt))
        .process(pipeline)
        .terminal(identity)
        .build

      for
        result <- agent.execute(5, mockContext)
      yield assertTrue(
        executionOrder == List("step1", "step2", "step3") &&
        (result match
          case PipelineResult.Success(v, _) => v == "Result: 12" // (5 + 1) * 2 = 12
          case _ => false
        )
      )
    }
  )

  val errorHandlingSuite = suite("error propagation")(
    test("failure in first step stops pipeline") {
      var step2Called = false
      val step1 = Process.effect[Int, Int]("fail") { (_, _) =>
        ZIO.fail(new RuntimeException("intentional failure"))
      }
      val step2 = Process.effect[Int, Int]("step2") { (n, _) =>
        step2Called = true
        ZIO.succeed(n * 2)
      }

      val agent = Agent("FailingPipeline")
        .readFrom(SourceQueue.fromAgentName("input"), s => Right(s.toInt))
        .process(step1 >>> step2)
        .terminal(_.toString)
        .build

      for
        result <- agent.execute(5, mockContext)
      yield assertTrue(
        !step2Called &&
        (result match
          case PipelineResult.Failure(e, _) => e.contains("intentional failure")
          case _ => false
        )
      )
    },
    test("failure in middle step stops pipeline") {
      var stepsExecuted = List.empty[String]
      val step1 = Process.effect[Int, Int]("step1") { (n, _) =>
        stepsExecuted = stepsExecuted :+ "step1"
        ZIO.succeed(n + 1)
      }
      val step2 = Process.effect[Int, Int]("step2") { (_, _) =>
        stepsExecuted = stepsExecuted :+ "step2"
        ZIO.fail(new RuntimeException("middle failure"))
      }
      val step3 = Process.effect[Int, Int]("step3") { (n, _) =>
        stepsExecuted = stepsExecuted :+ "step3"
        ZIO.succeed(n * 2)
      }

      val agent = Agent("MiddleFailPipeline")
        .readFrom(SourceQueue.fromAgentName("input"), s => Right(s.toInt))
        .process(step1 >>> step2 >>> step3)
        .terminal(_.toString)
        .build

      for
        result <- agent.execute(5, mockContext)
      yield assertTrue(
        stepsExecuted == List("step1", "step2") &&
        result.isInstanceOf[PipelineResult.Failure[?]]
      )
    },
    test("decode error returns Failure") {
      val agent = Agent("DecodeAgent")
        .readFrom(SourceQueue.fromAgentName("input"), s =>
          s.toIntOption.toRight("Not a valid integer")
        )
        .process(Process.identity[Int])
        .terminal(_.toString)
        .build

      for
        result <- agent.executeFromMessage("not-an-int", TraceId.unsafe("t"), ConversationId.unsafe("c"))
      yield assertTrue(result match
        case PipelineResult.Failure(e, _) => e.contains("Failed to decode")
        case _ => false
      )
    }
  )

  val jsonMessageFlowSuite = suite("JSON message flow")(
    test("decodes JSON input and encodes JSON output") {
      val agent = Agent("JsonAgent")
        .readFrom(
          SourceQueue.fromAgentName("input"),
          s => s.fromJson[AgentInput].left.map(_.toString)
        )
        .process(
          Process.pure[AgentInput, AgentOutput]("process")(input =>
            AgentOutput(input.taskDescription.toUpperCase, 1, 50L)
          )
        )
        .writeTo(DestQueue.fromAgentName("output"), _.toJson)
        .build

      val jsonInput = """{"taskDescription":"hello world","context":{}}"""
      for
        result <- agent.executeFromMessage(jsonInput, TraceId.unsafe("t"), ConversationId.unsafe("c"))
      yield assertTrue(result match
        case PipelineResult.Success(out, _) =>
          out.summary == "HELLO WORLD" &&
          out.toolExecutions == 1
        case _ => false
      )
    },
    test("rejects malformed JSON") {
      val agent = Agent("JsonAgent")
        .readFrom(
          SourceQueue.fromAgentName("input"),
          s => s.fromJson[AgentInput].left.map(_.toString)
        )
        .process(Process.identity[AgentInput])
        .writeTo(DestQueue.fromAgentName("output"), _.toJson)
        .build

      for
        result <- agent.executeFromMessage("{invalid json", TraceId.unsafe("t"), ConversationId.unsafe("c"))
      yield assertTrue(result match
        case PipelineResult.Failure(e, _) => e.contains("Failed to decode")
        case _ => false
      )
    },
    test("handles complex nested context") {
      val agent = Agent("ContextAgent")
        .readFrom(
          SourceQueue.fromAgentName("input"),
          s => s.fromJson[AgentInput].left.map(_.toString)
        )
        .process(
          Process.pure[AgentInput, String]("extractContext")(input =>
            input.context.get("key").getOrElse("no key")
          )
        )
        .terminal(identity)
        .build

      val jsonInput = """{"taskDescription":"task","context":{"key":"value123"}}"""
      for
        result <- agent.executeFromMessage(jsonInput, TraceId.unsafe("t"), ConversationId.unsafe("c"))
      yield assertTrue(result match
        case PipelineResult.Success(v, _) => v == "value123"
        case _ => false
      )
    }
  )

  private val mockContext: PipelineContext = PipelineContext.initial(
    "test-agent",
    TraceId.unsafe("test-trace-id"),
    ConversationId.unsafe("test-conv-id")
  )
