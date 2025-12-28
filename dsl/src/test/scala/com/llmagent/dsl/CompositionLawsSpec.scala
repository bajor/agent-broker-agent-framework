package com.llmagent.dsl

import zio.*
import zio.test.*
import zio.test.Assertion.*
import com.llmagent.dsl.Types.*
import com.llmagent.common.observability.Types.ConversationId

object CompositionLawsSpec extends ZIOSpecDefault:

  def spec = suite("Composition Laws")(
    processCompositionSuite,
    pipelineStepCompositionSuite,
    pipelineResultFunctorSuite
  )

  val processCompositionSuite = suite("Process composition")(
    test("identity law (left): id >>> f === f") {
      val f = Process.pure[Int, Int]("addOne")(_ + 1)
      for
        leftResult <- (Process.identity[Int] >>> f).execute(5, mockContext)
        rightResult <- f.execute(5, mockContext)
      yield assertTrue(leftResult == rightResult)
    },
    test("identity law (right): f >>> id === f") {
      val f = Process.pure[Int, Int]("addOne")(_ + 1)
      for
        leftResult <- (f >>> Process.identity[Int]).execute(5, mockContext)
        rightResult <- f.execute(5, mockContext)
      yield assertTrue(leftResult == rightResult)
    },
    test("associativity: (f >>> g) >>> h === f >>> (g >>> h)") {
      val f = Process.pure[Int, Int]("addOne")(_ + 1)
      val g = Process.pure[Int, Int]("double")(_ * 2)
      val h = Process.pure[Int, Int]("addTen")(_ + 10)
      for
        leftResult <- ((f >>> g) >>> h).execute(5, mockContext)
        rightResult <- (f >>> (g >>> h)).execute(5, mockContext)
      yield assertTrue(leftResult == rightResult)
    },
    test("associativity holds for multiple inputs") {
      val f = Process.pure[Int, Int]("addOne")(_ + 1)
      val g = Process.pure[Int, Int]("double")(_ * 2)
      val h = Process.pure[Int, Int]("addTen")(_ + 10)
      for
        results <- ZIO.foreach(List(0, 1, 10, 100, -5)) { n =>
          for
            left <- ((f >>> g) >>> h).execute(n, mockContext)
            right <- (f >>> (g >>> h)).execute(n, mockContext)
          yield left == right
        }
      yield assertTrue(results.forall(identity))
    }
  )

  val pipelineStepCompositionSuite = suite("PipelineStep composition")(
    test("identity law (left): identity >>> step === step (value)") {
      val step = PipelineStep.pure[Int, Int]("double")(_ * 2)
      for
        left <- (PipelineStep.identity[Int] >>> step).run(5, mockContext)
        right <- step.run(5, mockContext)
      yield assertTrue(extractValue(left) == extractValue(right))
    },
    test("identity law (right): step >>> identity === step (value)") {
      val step = PipelineStep.pure[Int, Int]("double")(_ * 2)
      for
        left <- (step >>> PipelineStep.identity[Int]).run(5, mockContext)
        right <- step.run(5, mockContext)
      yield assertTrue(extractValue(left) == extractValue(right))
    },
    test("associativity: (f >>> g) >>> h === f >>> (g >>> h) (value)") {
      val f = PipelineStep.pure[Int, Int]("addOne")(_ + 1)
      val g = PipelineStep.pure[Int, Int]("double")(_ * 2)
      val h = PipelineStep.pure[Int, Int]("addTen")(_ + 10)
      for
        left <- ((f >>> g) >>> h).run(5, mockContext)
        right <- (f >>> (g >>> h)).run(5, mockContext)
      yield assertTrue(extractValue(left) == extractValue(right))
    },
    test("composition preserves failures") {
      val failingStep = PipelineStep[Int, Int]("fail", (_, ctx) =>
        ZIO.succeed(PipelineResult.Failure("error", ctx))
      )
      val step2 = PipelineStep.pure[Int, Int]("double")(_ * 2)
      for
        leftFirst <- (failingStep >>> step2).run(5, mockContext)
        rightFirst <- (step2 >>> failingStep).run(5, mockContext)
      yield assertTrue(
        leftFirst.isInstanceOf[PipelineResult.Failure[?]] &&
        rightFirst.isInstanceOf[PipelineResult.Failure[?]]
      )
    }
  )

  val pipelineResultFunctorSuite = suite("PipelineResult functor laws")(
    test("map identity: x.map(id) === x") {
      val result = PipelineResult.Success(42, mockContext)
      assertTrue(result.map(identity) == result)
    },
    test("map composition: x.map(f).map(g) === x.map(f andThen g)") {
      val f = (x: Int) => x + 1
      val g = (x: Int) => x * 2
      val result = PipelineResult.Success(5, mockContext)
      assertTrue(result.map(f).map(g) == result.map(f andThen g))
    },
    test("map identity holds for Failure") {
      val result = PipelineResult.Failure[Int]("error", mockContext)
      assertTrue(result.map(identity) == result)
    },
    test("map identity holds for Rejected") {
      val result = PipelineResult.Rejected[Int]("guard", "reason", mockContext)
      assertTrue(result.map(identity) == result)
    },
    test("map composition holds for multiple values") {
      val f = (x: Int) => x + 1
      val g = (x: Int) => x * 2
      val values = List(0, 1, 10, 100, -5)
      val results = values.map { n =>
        val result = PipelineResult.Success(n, mockContext)
        result.map(f).map(g) == result.map(f andThen g)
      }
      assertTrue(results.forall(identity))
    }
  )

  private def extractValue[A](result: PipelineResult[A]): Option[A] =
    result match
      case PipelineResult.Success(v, _) => Some(v)
      case _ => None

  private val mockContext: PipelineContext = PipelineContext.initial(
    "test-agent",
    TraceId.unsafe("test-trace-id"),
    ConversationId.unsafe("test-conv-id")
  )
