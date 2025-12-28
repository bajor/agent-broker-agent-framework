package com.llmagent.dsl

import zio.*
import zio.test.*
import zio.test.Assertion.*
import com.llmagent.dsl.Types.*
import com.llmagent.common.observability.Types.ConversationId

object AgentBuilderSpec extends ZIOSpecDefault:

  def spec = suite("AgentBuilder")(
    buildSuite,
    agentDefinitionSuite,
    terminalAgentSuite
  )

  val buildSuite = suite("build")(
    test("can build with both readFrom and writeTo") {
      val agent = Agent("TestAgent")
        .readFrom(SourceQueue.fromAgentName("input"), s => Right(s))
        .process(Process.identity[String])
        .writeTo(DestQueue.fromAgentName("output"), identity)
        .build
      assertTrue(agent.name == "TestAgent")
    },
    test("captures input queue name correctly") {
      val agent = Agent("TestAgent")
        .readFrom(SourceQueue.fromAgentName("preprocessor"), s => Right(s))
        .process(Process.identity[String])
        .writeTo(DestQueue.fromAgentName("codegen"), identity)
        .build
      assertTrue(agent.inputQueueName == "agent_preprocessor_tasks")
    },
    test("captures output queue name correctly") {
      val agent = Agent("TestAgent")
        .readFrom(SourceQueue.fromAgentName("preprocessor"), s => Right(s))
        .process(Process.identity[String])
        .writeTo(DestQueue.fromAgentName("codegen"), identity)
        .build
      assertTrue(agent.outputQueueName == Some("agent_codegen_tasks"))
    }
  )

  val agentDefinitionSuite = suite("AgentDefinition")(
    test("execute runs pipeline with context") {
      val agent = Agent("TestAgent")
        .readFrom(SourceQueue.fromAgentName("test"), s => Right(s.toInt))
        .process(Process.pure[Int, Int]("double")(_ * 2))
        .writeTo(DestQueue.fromAgentName("output"), _.toString)
        .build
      for
        result <- agent.execute(21, mockContext)
      yield assertTrue(result match
        case PipelineResult.Success(v, _) => v == 42
        case _ => false
      )
    },
    test("executeFromMessage decodes and executes") {
      val agent = Agent("TestAgent")
        .readFrom(SourceQueue.fromAgentName("test"), s => s.toIntOption.toRight("Not an integer"))
        .process(Process.pure[Int, Int]("inc")(_ + 1))
        .writeTo(DestQueue.fromAgentName("output"), _.toString)
        .build
      for
        result <- agent.executeFromMessage(
          "42",
          TraceId.unsafe("t"),
          ConversationId.unsafe("c")
        )
      yield assertTrue(result match
        case PipelineResult.Success(v, _) => v == 43
        case _ => false
      )
    },
    test("executeFromMessage returns Failure for decode error") {
      val agent = Agent("TestAgent")
        .readFrom(SourceQueue.fromAgentName("test"), s =>
          s.toIntOption.toRight("Not an integer")
        )
        .process(Process.pure[Int, Int]("id")(identity))
        .writeTo(DestQueue.fromAgentName("output"), _.toString)
        .build
      for
        result <- agent.executeFromMessage(
          "not-a-number",
          TraceId.unsafe("t"),
          ConversationId.unsafe("c")
        )
      yield assertTrue(result match
        case PipelineResult.Failure(msg, _) => msg.contains("Failed to decode")
        case _ => false
      )
    },
    test("encodeOutput encodes Success result") {
      val agent = buildTestAgent()
      val result = PipelineResult.Success(42, mockContext)
      assertTrue(agent.encodeOutput(result) == Some("42"))
    },
    test("encodeOutput returns None for Failure") {
      val agent = buildTestAgent()
      val result = PipelineResult.Failure[Int]("error", mockContext)
      assertTrue(agent.encodeOutput(result) == None)
    },
    test("encodeOutput returns None for Rejected") {
      val agent = buildTestAgent()
      val result = PipelineResult.Rejected[Int]("guard", "reason", mockContext)
      assertTrue(agent.encodeOutput(result) == None)
    },
    test("createEnvelope creates correct envelope for Success") {
      val agent = buildTestAgent()
      val result = PipelineResult.Success(42, mockContext)
      val envelope = agent.createEnvelope(result)
      assertTrue(envelope == PipelineEnvelope.Payload(42))
    },
    test("createEnvelope creates correct envelope for Failure") {
      val agent = buildTestAgent()
      val result = PipelineResult.Failure[Int]("error", mockContext)
      val envelope = agent.createEnvelope(result)
      assertTrue(envelope match
        case PipelineEnvelope.UpstreamFailure(_, e) => e == "error"
        case _ => false
      )
    }
  )

  val terminalAgentSuite = suite("terminal agents")(
    test("terminal agents have no output queue") {
      val agent = Agent("TerminalAgent")
        .readFrom(SourceQueue.fromAgentName("input"), s => Right(s))
        .process(Process.identity[String])
        .terminal(identity)
        .build
      assertTrue(agent.isTerminal && agent.outputQueueName.isEmpty)
    },
    test("terminal agents can still execute") {
      val agent = Agent("TerminalAgent")
        .readFrom(SourceQueue.fromAgentName("input"), s => Right(s.toInt))
        .process(Process.pure[Int, String]("format")(n => s"Result: $n"))
        .terminal(identity)
        .build
      for
        result <- agent.execute(42, mockContext)
      yield assertTrue(result match
        case PipelineResult.Success(v, _) => v == "Result: 42"
        case _ => false
      )
    }
  )

  private def buildTestAgent(): AgentDefinition[Int, Int] =
    Agent("TestAgent")
      .readFrom(SourceQueue.fromAgentName("test"), s => Right(s.toInt))
      .process(Process.pure[Int, Int]("double")(_ * 2))
      .writeTo(DestQueue.fromAgentName("output"), _.toString)
      .build

  private val mockContext: PipelineContext = PipelineContext.initial(
    "test-agent",
    TraceId.unsafe("test-trace-id"),
    ConversationId.unsafe("test-conv-id")
  )
