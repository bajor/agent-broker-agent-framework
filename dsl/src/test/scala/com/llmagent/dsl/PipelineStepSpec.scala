package com.llmagent.dsl

import zio.*
import zio.test.*
import zio.test.Assertion.*
import com.llmagent.dsl.Types.*
import com.llmagent.common.observability.Types.ConversationId

object PipelineStepSpec extends ZIOSpecDefault:

  def spec = suite("PipelineStep")(
    identitySuite,
    pureSuite,
    fromZIOSuite,
    compositionSuite,
    mapSuite,
    withReflectionSuite,
    whenSuite
  )

  val identitySuite = suite("identity")(
    test("returns Success with input unchanged") {
      val step = PipelineStep.identity[Int]
      for
        result <- step.run(42, mockContext)
      yield assertTrue(result == PipelineResult.Success(42, mockContext))
    },
    test("preserves context") {
      val step = PipelineStep.identity[String]
      for
        result <- step.run("test", mockContext)
      yield assertTrue(result.context == mockContext)
    }
  )

  val pureSuite = suite("pure")(
    test("wraps pure function in Success") {
      val step = PipelineStep.pure[Int, String]("intToStr")(_.toString)
      for
        result <- step.run(42, mockContext)
      yield assertTrue(result == PipelineResult.Success("42", mockContext))
    },
    test("name is set correctly") {
      val step = PipelineStep.pure[Int, String]("myStep")(_.toString)
      assertTrue(step.name == "myStep")
    }
  )

  val fromZIOSuite = suite("fromZIO")(
    test("wraps ZIO success in PipelineResult.Success") {
      val step = PipelineStep.fromZIO[Int, String]("test") { (n, _) =>
        ZIO.succeed(n.toString)
      }
      for
        result <- step.run(42, mockContext)
      yield assertTrue(result == PipelineResult.Success("42", mockContext))
    },
    test("converts ZIO failure to PipelineResult.Failure") {
      val step = PipelineStep.fromZIO[Int, String]("failing") { (_, _) =>
        ZIO.fail(new RuntimeException("boom"))
      }
      for
        result <- step.run(42, mockContext)
      yield assertTrue(result match
        case PipelineResult.Failure(msg, _) => msg == "boom"
        case _ => false
      )
    },
    test("has access to pipeline context") {
      val step = PipelineStep.fromZIO[Int, String]("withContext") { (n, ctx) =>
        ZIO.succeed(s"$n from ${ctx.agentName}")
      }
      for
        result <- step.run(42, mockContext)
      yield assertTrue(result match
        case PipelineResult.Success(v, _) => v == "42 from test-agent"
        case _ => false
      )
    }
  )

  val compositionSuite = suite("andThen (>>>)")(
    test("composes steps sequentially") {
      val step1 = PipelineStep.pure[Int, Int]("add1")(_ + 1)
      val step2 = PipelineStep.pure[Int, Int]("double")(_ * 2)
      val composed = step1 >>> step2
      for
        result <- composed.run(5, mockContext)
      yield assertTrue(result match
        case PipelineResult.Success(v, _) => v == 12 // (5 + 1) * 2
        case _ => false
      )
    },
    test("composed name includes both step names") {
      val step1 = PipelineStep.pure[Int, Int]("A")(_ + 1)
      val step2 = PipelineStep.pure[Int, Int]("B")(_ * 2)
      val composed = step1 >>> step2
      assertTrue(composed.name == "A >>> B")
    },
    test("short-circuits on Failure") {
      var step2Called = false
      val step1 = PipelineStep[Int, Int]("fail", (_, ctx) =>
        ZIO.succeed(PipelineResult.Failure("error", ctx))
      )
      val step2 = PipelineStep.pure[Int, Int]("never") { n =>
        step2Called = true
        n * 2
      }
      val composed = step1 >>> step2
      for
        result <- composed.run(5, mockContext)
      yield assertTrue(
        result.isInstanceOf[PipelineResult.Failure[?]] &&
        !step2Called
      )
    },
    test("short-circuits on Rejected") {
      var step2Called = false
      val step1 = PipelineStep[Int, Int]("reject", (_, ctx) =>
        ZIO.succeed(PipelineResult.Rejected("guard", "reason", ctx))
      )
      val step2 = PipelineStep.pure[Int, Int]("never") { n =>
        step2Called = true
        n * 2
      }
      val composed = step1 >>> step2
      for
        result <- composed.run(5, mockContext)
      yield assertTrue(
        result.isInstanceOf[PipelineResult.Rejected[?]] &&
        !step2Called
      )
    },
    test("increments stepIndex through composition") {
      var capturedIndices = List.empty[Int]
      val captureStep = PipelineStep[Int, Int]("capture", (n, ctx) =>
        capturedIndices = capturedIndices :+ ctx.stepIndex
        ZIO.succeed(PipelineResult.Success(n, ctx))
      )
      val composed = captureStep >>> captureStep >>> captureStep
      for
        _ <- composed.run(5, mockContext)
      yield assertTrue(capturedIndices == List(0, 1, 2))
    }
  )

  val mapSuite = suite("map")(
    test("transforms successful output") {
      val step = PipelineStep.pure[Int, Int]("base")(_ + 1).map(_ * 10)
      for
        result <- step.run(5, mockContext)
      yield assertTrue(result match
        case PipelineResult.Success(v, _) => v == 60 // (5 + 1) * 10
        case _ => false
      )
    },
    test("preserves Failure") {
      val step = PipelineStep[Int, Int]("fail", (_, ctx) =>
        ZIO.succeed(PipelineResult.Failure("error", ctx))
      ).map(_ * 10)
      for
        result <- step.run(5, mockContext)
      yield assertTrue(result match
        case PipelineResult.Failure(e, _) => e == "error"
        case _ => false
      )
    },
    test("preserves Rejected") {
      val step = PipelineStep[Int, Int]("reject", (_, ctx) =>
        ZIO.succeed(PipelineResult.Rejected("guard", "reason", ctx))
      ).map(_ * 10)
      for
        result <- step.run(5, mockContext)
      yield assertTrue(result match
        case PipelineResult.Rejected(g, r, _) => g == "guard" && r == "reason"
        case _ => false
      )
    }
  )

  val withReflectionSuite = suite("withReflection")(
    test("retries on failure up to maxReflections") {
      var attempts = 0
      val step = PipelineStep.withReflection[Int, Int](
        "retrying",
        MaxReflections.unsafe(3)
      )(
        execute = (n, _) =>
          attempts += 1
          if attempts < 3 then ZIO.fail(new RuntimeException("not yet"))
          else ZIO.succeed(n * 2),
        onFailure = (n, _) => n
      )
      for
        result <- step.run(5, mockContext)
      yield assertTrue(
        attempts == 3 &&
        (result match
          case PipelineResult.Success(v, _) => v == 10
          case _ => false
        )
      )
    },
    test("returns Failure after exhausting reflections") {
      // maxReflections=2 means 1 initial + 2 retries = 3 total attempts
      var attempts = 0
      val step = PipelineStep.withReflection[Int, Int](
        "alwaysFails",
        MaxReflections.unsafe(2)
      )(
        execute = (_, _) =>
          attempts += 1
          ZIO.fail(new RuntimeException("always fails")),
        onFailure = (n, _) => n
      )
      for
        result <- step.run(5, mockContext)
      yield assertTrue(
        attempts == 3 &&
        (result match
          case PipelineResult.Failure(e, _) => e.contains("Max reflections")
          case _ => false
        )
      )
    },
    test("succeeds immediately if first attempt succeeds") {
      var attempts = 0
      val step = PipelineStep.withReflection[Int, Int](
        "succeedsFirst",
        MaxReflections.unsafe(3)
      )(
        execute = (n, _) =>
          attempts += 1
          ZIO.succeed(n * 2),
        onFailure = (n, _) => n
      )
      for
        result <- step.run(5, mockContext)
      yield assertTrue(
        attempts == 1 &&
        (result match
          case PipelineResult.Success(v, _) => v == 10
          case _ => false
        )
      )
    },
    test("with maxReflections 0 attempts once and returns Failure (no retries)") {
      // maxReflections=0 means 1 initial attempt only, no retries
      var attempts = 0
      val step = PipelineStep.withReflection[Int, Int](
        "noRetries",
        MaxReflections.none
      )(
        execute = (_, _) =>
          attempts += 1
          ZIO.fail(new RuntimeException("fail")),
        onFailure = (n, _) => n
      )
      for
        result <- step.run(5, mockContext)
      yield assertTrue(
        attempts == 1 &&
        result.isInstanceOf[PipelineResult.Failure[?]]
      )
    }
  )

  val whenSuite = suite("when")(
    test("executes step when predicate is true") {
      val step = PipelineStep.when[Int]("cond")(_ > 10)(
        PipelineStep.pure("double")(_ * 2)
      )
      for
        result <- step.run(15, mockContext)
      yield assertTrue(result match
        case PipelineResult.Success(v, _) => v == 30
        case _ => false
      )
    },
    test("passes through when predicate is false") {
      val step = PipelineStep.when[Int]("cond")(_ > 10)(
        PipelineStep.pure("double")(_ * 2)
      )
      for
        result <- step.run(5, mockContext)
      yield assertTrue(result match
        case PipelineResult.Success(v, _) => v == 5
        case _ => false
      )
    }
  )

  private val mockContext: PipelineContext = PipelineContext.initial(
    "test-agent",
    TraceId.unsafe("test-trace-id"),
    ConversationId.unsafe("test-conv-id")
  )
