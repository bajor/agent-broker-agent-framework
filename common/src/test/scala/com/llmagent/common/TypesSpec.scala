package com.llmagent.common

import zio.*
import zio.test.*
import zio.test.Assertion.*
import com.llmagent.common.Types.*

object TypesSpec extends ZIOSpecDefault:

  def spec = suite("Types")(
    nonEmptyStringSuite,
    positiveIntSuite,
    nonNegativeIntSuite,
    traceIdSuite,
    queryResultSuite
  )

  val nonEmptyStringSuite = suite("NonEmptyString")(
    test("rejects empty string") {
      assertTrue(NonEmptyString("") == None)
    },
    test("rejects whitespace-only string") {
      assertTrue(
        NonEmptyString("   ") == None &&
        NonEmptyString("\t\n") == None
      )
    },
    test("accepts non-empty string and trims it") {
      val result = NonEmptyString("  hello  ")
      assertTrue(
        result.isDefined &&
        result.get.value == "hello"
      )
    },
    test("unsafe bypasses validation") {
      val result = NonEmptyString.unsafe("")
      assertTrue(result.value == "")
    },
    test("preserves internal whitespace") {
      val result = NonEmptyString("hello world")
      assertTrue(result.get.value == "hello world")
    }
  )

  val positiveIntSuite = suite("PositiveInt")(
    test("rejects zero") {
      assertTrue(PositiveInt(0) == None)
    },
    test("rejects negative") {
      assertTrue(
        PositiveInt(-1) == None &&
        PositiveInt(-100) == None
      )
    },
    test("accepts positive") {
      assertTrue(
        PositiveInt(1).isDefined &&
        PositiveInt(1).get.value == 1 &&
        PositiveInt(100).get.value == 100
      )
    },
    test("unsafe bypasses validation") {
      val result = PositiveInt.unsafe(-5)
      assertTrue(result.value == -5)
    }
  )

  val nonNegativeIntSuite = suite("NonNegativeInt")(
    test("accepts zero") {
      assertTrue(
        NonNegativeInt(0).isDefined &&
        NonNegativeInt(0).get.value == 0
      )
    },
    test("rejects negative") {
      assertTrue(
        NonNegativeInt(-1) == None &&
        NonNegativeInt(-100) == None
      )
    },
    test("accepts positive") {
      assertTrue(
        NonNegativeInt(1).isDefined &&
        NonNegativeInt(100).isDefined
      )
    },
    test("zero constant works") {
      assertTrue(NonNegativeInt.zero.value == 0)
    }
  )

  val traceIdSuite = suite("TraceId")(
    test("generates unique IDs") {
      val id1 = TraceId.generate()
      val id2 = TraceId.generate()
      assertTrue(id1.value != id2.value)
    },
    test("fromString rejects empty") {
      assertTrue(TraceId.fromString("") == None)
    },
    test("fromString accepts non-empty") {
      val result = TraceId.fromString("trace-123")
      assertTrue(
        result.isDefined &&
        result.get.value == "trace-123"
      )
    },
    test("unsafe bypasses validation") {
      val result = TraceId.unsafe("")
      assertTrue(result.value == "")
    }
  )

  val queryResultSuite = suite("QueryResult")(
    test("fromOption creates Success for Some") {
      val result = QueryResult.fromOption(Some("value"), "not found")
      assertTrue(result == QueryResult.Success("value"))
    },
    test("fromOption creates NotFound for None") {
      val result = QueryResult.fromOption(None, "not found")
      assertTrue(result == QueryResult.NotFound("not found"))
    },
    test("fromEither creates Success for Right") {
      val result = QueryResult.fromEither(Right("value"))
      assertTrue(result == QueryResult.Success("value"))
    },
    test("fromEither creates Error for Left") {
      val result = QueryResult.fromEither(Left("error message"))
      assertTrue(result == QueryResult.Error("error message"))
    }
  )
