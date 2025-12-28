package com.llmagent.dsl

import zio.*
import zio.test.*
import zio.test.Assertion.*
import com.llmagent.dsl.Types.*
import com.llmagent.common.observability.Types.ConversationId

object TypesSpec extends ZIOSpecDefault:

  def spec = suite("DSL Types")(
    sourceQueueSuite,
    destQueueSuite,
    maxReflectionsSuite,
    traceIdSuite,
    pipelineContextSuite,
    pipelineResultSuite,
    pipelineEnvelopeSuite
  )

  val sourceQueueSuite = suite("SourceQueue")(
    test("rejects empty string") {
      assertTrue(SourceQueue("") == None)
    },
    test("rejects string with spaces") {
      assertTrue(SourceQueue("queue name") == None)
    },
    test("rejects string with dots") {
      assertTrue(SourceQueue("queue.name") == None)
    },
    test("accepts valid alphanumeric with underscores and hyphens") {
      assertTrue(
        SourceQueue("valid_queue-123").isDefined &&
        SourceQueue("myqueue").isDefined &&
        SourceQueue("QUEUE_NAME").isDefined
      )
    },
    test("fromAgentName generates correct queue name") {
      val queue = SourceQueue.fromAgentName("preprocessor")
      assertTrue(queue.value == "agent_preprocessor_tasks")
    },
    test("unsafe bypasses validation") {
      assertTrue(SourceQueue.unsafe("").value == "")
    }
  )

  val destQueueSuite = suite("DestQueue")(
    test("rejects empty string") {
      assertTrue(DestQueue("") == None)
    },
    test("rejects invalid characters") {
      assertTrue(
        DestQueue("queue name") == None &&
        DestQueue("queue.name") == None
      )
    },
    test("accepts valid names") {
      assertTrue(DestQueue("valid_queue-123").isDefined)
    },
    test("fromAgentName generates correct queue name") {
      val queue = DestQueue.fromAgentName("codegen")
      assertTrue(queue.value == "agent_codegen_tasks")
    }
  )

  val maxReflectionsSuite = suite("MaxReflections")(
    test("rejects negative") {
      assertTrue(MaxReflections(-1) == None)
    },
    test("rejects values > 10") {
      assertTrue(MaxReflections(11) == None)
    },
    test("accepts valid range 0-10") {
      assertTrue(
        MaxReflections(0).isDefined &&
        MaxReflections(5).isDefined &&
        MaxReflections(10).isDefined
      )
    },
    test("hasMore returns true when current < max") {
      val mr = MaxReflections.unsafe(3)
      assertTrue(
        mr.hasMore(0) &&
        mr.hasMore(1) &&
        mr.hasMore(2) &&
        !mr.hasMore(3) &&
        !mr.hasMore(4)
      )
    },
    test("default is 3") {
      assertTrue(MaxReflections.default.value == 3)
    },
    test("none is 0") {
      assertTrue(MaxReflections.none.value == 0)
    },
    test("unsafe bypasses validation") {
      assertTrue(MaxReflections.unsafe(100).value == 100)
    }
  )

  val traceIdSuite = suite("TraceId")(
    test("generates unique IDs") {
      val id1 = TraceId.generate()
      val id2 = TraceId.generate()
      assertTrue(id1.value != id2.value)
    },
    test("unsafe accepts any string") {
      assertTrue(TraceId.unsafe("test-trace").value == "test-trace")
    }
  )

  val pipelineContextSuite = suite("PipelineContext")(
    test("initial creates context with stepIndex 0") {
      val ctx = mockContext
      assertTrue(
        ctx.stepIndex == 0 &&
        ctx.stepLogs.isEmpty
      )
    },
    test("nextStep increments stepIndex") {
      val ctx = mockContext
      assertTrue(
        ctx.nextStep.stepIndex == 1 &&
        ctx.nextStep.nextStep.stepIndex == 2
      )
    },
    test("withLog appends to stepLogs") {
      val ctx = mockContext
      val log = StepLog("step1", 0, 100L, 0, StepResultStatus.Success)
      val updated = ctx.withLog(log)
      assertTrue(
        updated.stepLogs.size == 1 &&
        updated.stepLogs.head == log
      )
    },
    test("withLog preserves existing logs") {
      val ctx = mockContext
      val log1 = StepLog("step1", 0, 100L, 0, StepResultStatus.Success)
      val log2 = StepLog("step2", 1, 200L, 0, StepResultStatus.Success)
      val updated = ctx.withLog(log1).withLog(log2)
      assertTrue(
        updated.stepLogs.size == 2 &&
        updated.stepLogs(0) == log1 &&
        updated.stepLogs(1) == log2
      )
    }
  )

  val pipelineResultSuite = suite("PipelineResult")(
    test("isSuccess returns true for Success") {
      val result = PipelineResult.Success("value", mockContext)
      assertTrue(result.isSuccess)
    },
    test("isSuccess returns false for Failure") {
      val result = PipelineResult.Failure[String]("error", mockContext)
      assertTrue(!result.isSuccess)
    },
    test("isSuccess returns false for Rejected") {
      val result = PipelineResult.Rejected[String]("guard", "reason", mockContext)
      assertTrue(!result.isSuccess)
    },
    test("map transforms Success value") {
      val result = PipelineResult.Success(10, mockContext).map(_ * 2)
      assertTrue(result match
        case PipelineResult.Success(v, _) => v == 20
        case _ => false
      )
    },
    test("map preserves Failure") {
      val result = PipelineResult.Failure[Int]("error", mockContext).map(_ * 2)
      assertTrue(result match
        case PipelineResult.Failure(e, _) => e == "error"
        case _ => false
      )
    },
    test("map preserves Rejected") {
      val result = PipelineResult.Rejected[Int]("guard", "reason", mockContext).map(_ * 2)
      assertTrue(result match
        case PipelineResult.Rejected(g, r, _) => g == "guard" && r == "reason"
        case _ => false
      )
    },
    test("flatMap chains Success") {
      val result = PipelineResult.Success(10, mockContext)
        .flatMap(n => PipelineResult.Success(n * 2, mockContext))
      assertTrue(result match
        case PipelineResult.Success(v, _) => v == 20
        case _ => false
      )
    },
    test("flatMap short-circuits on Failure") {
      val result = PipelineResult.Failure[Int]("error", mockContext)
        .flatMap(n => PipelineResult.Success(n * 2, mockContext))
      assertTrue(result match
        case PipelineResult.Failure(e, _) => e == "error"
        case _ => false
      )
    },
    test("context accessor works for all variants") {
      val ctx = mockContext
      assertTrue(
        PipelineResult.Success("v", ctx).context == ctx &&
        PipelineResult.Failure("e", ctx).context == ctx &&
        PipelineResult.Rejected("g", "r", ctx).context == ctx
      )
    }
  )

  val pipelineEnvelopeSuite = suite("PipelineEnvelope")(
    test("fromResult converts Success to Payload") {
      val result = PipelineResult.Success("value", mockContext)
      val envelope = PipelineEnvelope.fromResult(result)
      assertTrue(envelope == PipelineEnvelope.Payload("value"))
    },
    test("fromResult converts Failure to UpstreamFailure") {
      val result = PipelineResult.Failure[String]("error", mockContext)
      val envelope = PipelineEnvelope.fromResult(result)
      assertTrue(envelope match
        case PipelineEnvelope.UpstreamFailure(agent, error) =>
          agent == "test-agent" && error == "error"
        case _ => false
      )
    },
    test("fromResult converts Rejected to UpstreamRejection") {
      val result = PipelineResult.Rejected[String]("guard", "reason", mockContext)
      val envelope = PipelineEnvelope.fromResult(result)
      assertTrue(envelope match
        case PipelineEnvelope.UpstreamRejection(agent, guard, reason) =>
          agent == "test-agent" && guard == "guard" && reason == "reason"
        case _ => false
      )
    },
    test("isPayload returns true for Payload") {
      assertTrue(PipelineEnvelope.Payload("value").isPayload)
    },
    test("isPayload returns false for UpstreamFailure") {
      assertTrue(!PipelineEnvelope.UpstreamFailure("agent", "error").isPayload)
    },
    test("isPayload returns false for UpstreamRejection") {
      assertTrue(!PipelineEnvelope.UpstreamRejection("agent", "guard", "reason").isPayload)
    }
  )

  private val mockContext: PipelineContext = PipelineContext.initial(
    "test-agent",
    TraceId.unsafe("test-trace-id"),
    ConversationId.unsafe("test-conv-id")
  )
