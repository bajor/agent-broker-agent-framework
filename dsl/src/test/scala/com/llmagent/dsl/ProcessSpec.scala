package com.llmagent.dsl

import zio.*
import zio.test.*
import zio.test.Assertion.*
import com.llmagent.dsl.Types.*
import com.llmagent.common.observability.Types.ConversationId

object ProcessSpec extends ZIOSpecDefault:

  def spec = suite("Process")(
    pureSuite,
    effectSuite,
    compositionSuite,
    identitySuite,
    whenSuite,
    toStepSuite
  )

  val pureSuite = suite("pure")(
    test("creates process that transforms synchronously") {
      val process = Process.pure[Int, String]("intToString")(_.toString)
      for
        result <- process.execute(42, mockContext)
      yield assertTrue(result == "42")
    },
    test("handles exceptions by propagating them") {
      val process = Process.pure[Int, String]("failing")(_ => throw new RuntimeException("boom"))
      for
        result <- process.execute(42, mockContext).either
      yield assertTrue(result.isLeft)
    },
    test("name is set correctly") {
      val process = Process.pure[Int, String]("myProcess")(_.toString)
      assertTrue(process.name == "myProcess")
    },
    test("maxReflections is none for pure processes") {
      val process = Process.pure[Int, String]("test")(_.toString)
      assertTrue(process.maxReflections.value == 0)
    }
  )

  val effectSuite = suite("effect")(
    test("creates process that runs ZIO effects") {
      val process = Process.effect[Int, Int]("double") { (n, _) =>
        ZIO.succeed(n * 2)
      }
      for
        result <- process.execute(21, mockContext)
      yield assertTrue(result == 42)
    },
    test("propagates failures") {
      val process = Process.effect[Int, Int]("failing") { (_, _) =>
        ZIO.fail(new RuntimeException("boom"))
      }
      for
        result <- process.execute(1, mockContext).either
      yield assertTrue(result.isLeft)
    },
    test("has access to pipeline context") {
      val process = Process.effect[Int, String]("withContext") { (n, ctx) =>
        ZIO.succeed(s"$n at step ${ctx.stepIndex}")
      }
      for
        result <- process.execute(42, mockContext)
      yield assertTrue(result == "42 at step 0")
    },
    test("can have custom maxReflections") {
      val process = Process.effect[Int, Int]("withReflections", MaxReflections.unsafe(5)) { (n, _) =>
        ZIO.succeed(n * 2)
      }
      assertTrue(process.maxReflections.value == 5)
    }
  )

  val compositionSuite = suite("composition (>>>)")(
    test("chains processes sequentially") {
      val addOne = Process.pure[Int, Int]("addOne")(_ + 1)
      val double = Process.pure[Int, Int]("double")(_ * 2)
      val composed = addOne >>> double
      for
        result <- composed.execute(5, mockContext)
      yield assertTrue(result == 12) // (5 + 1) * 2
    },
    test("composed name includes both process names") {
      val p1 = Process.pure[Int, Int]("A")(_ + 1)
      val p2 = Process.pure[Int, Int]("B")(_ * 2)
      val composed = p1 >>> p2
      assertTrue(composed.name == "A >>> B")
    },
    test("chains three processes") {
      val addOne = Process.pure[Int, Int]("addOne")(_ + 1)
      val double = Process.pure[Int, Int]("double")(_ * 2)
      val addTen = Process.pure[Int, Int]("addTen")(_ + 10)
      val composed = addOne >>> double >>> addTen
      for
        result <- composed.execute(5, mockContext)
      yield assertTrue(result == 22) // ((5 + 1) * 2) + 10
    },
    test("composition increments step context at composition boundaries") {
      // Note: Step increments happen at composition boundaries, not per-process.
      // (A >>> B) >>> C: A gets step 0, B gets step 1 (from inner composition),
      // C gets step 1 (from outer composition, since ctx.nextStep is called once)
      var stepIndices = List.empty[Int]
      val captureStep = Process.effect[Int, Int]("capture") { (n, ctx) =>
        stepIndices = stepIndices :+ ctx.stepIndex
        ZIO.succeed(n)
      }
      val composed = captureStep >>> captureStep >>> captureStep
      for
        _ <- composed.execute(1, mockContext)
      yield assertTrue(stepIndices == List(0, 1, 1))
    }
  )

  val identitySuite = suite("identity")(
    test("returns input unchanged") {
      val process = Process.identity[String]
      for
        result <- process.execute("test", mockContext)
      yield assertTrue(result == "test")
    },
    test("works with complex types") {
      val process = Process.identity[List[Int]]
      for
        result <- process.execute(List(1, 2, 3), mockContext)
      yield assertTrue(result == List(1, 2, 3))
    }
  )

  val whenSuite = suite("when")(
    test("executes process when predicate is true") {
      val double = Process.pure[Int, Int]("double")(_ * 2)
      val conditional = Process.when[Int]("conditionalDouble")(_ > 10)(double)
      for
        result <- conditional.execute(15, mockContext)
      yield assertTrue(result == 30)
    },
    test("returns input when predicate is false") {
      val double = Process.pure[Int, Int]("double")(_ * 2)
      val conditional = Process.when[Int]("conditionalDouble")(_ > 10)(double)
      for
        result <- conditional.execute(5, mockContext)
      yield assertTrue(result == 5)
    },
    test("evaluates predicate correctly at boundary") {
      val double = Process.pure[Int, Int]("double")(_ * 2)
      val conditional = Process.when[Int]("conditionalDouble")(_ >= 10)(double)
      for
        resultAt10 <- conditional.execute(10, mockContext)
        resultAt9 <- conditional.execute(9, mockContext)
      yield assertTrue(resultAt10 == 20 && resultAt9 == 9)
    }
  )

  val toStepSuite = suite("toStep")(
    test("converts to PipelineStep that returns PipelineResult.Success") {
      val process = Process.pure[Int, String]("test")(_.toString)
      val step = process.toStep
      for
        result <- step.run(42, mockContext)
      yield assertTrue(result match
        case PipelineResult.Success(v, _) => v == "42"
        case _ => false
      )
    },
    test("converted step captures failures in PipelineResult.Failure") {
      val process = Process.effect[Int, String]("failing") { (_, _) =>
        ZIO.fail(new RuntimeException("boom"))
      }
      val step = process.toStep
      for
        result <- step.run(42, mockContext)
      yield assertTrue(result match
        case PipelineResult.Failure(e, _) => e.contains("boom")
        case _ => false
      )
    }
  )

  private val mockContext: PipelineContext = PipelineContext.initial(
    "test-agent",
    TraceId.unsafe("test-trace-id"),
    ConversationId.unsafe("test-conv-id")
  )
