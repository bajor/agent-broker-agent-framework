package com.llmagent.tools

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.test.TestAspect.*
import com.llmagent.common.Agent.ToolResult

object PythonExecutorToolSpec extends ZIOSpecDefault:

  def spec = suite("PythonExecutorTool")(
    pythonInputSuite,
    pythonOutputSuite,
    executionSuite
  )

  val pythonInputSuite = suite("PythonInput")(
    test("rejects empty code") {
      assertTrue(PythonInput("", 30) == None)
    },
    test("rejects whitespace-only code") {
      assertTrue(PythonInput("   ", 30) == None)
    },
    test("rejects zero timeout") {
      assertTrue(PythonInput("print(1)", 0) == None)
    },
    test("rejects negative timeout") {
      assertTrue(PythonInput("print(1)", -1) == None)
    },
    test("accepts valid input") {
      assertTrue(PythonInput("print(1)", 30).isDefined)
    },
    test("accepts minimal valid input") {
      assertTrue(PythonInput("x", 1).isDefined)
    },
    test("unsafe bypasses validation") {
      val input = PythonInput.unsafe("", -1)
      assertTrue(input != null)
    }
  )

  val pythonOutputSuite = suite("PythonOutput")(
    test("isSuccess returns true for exit code 0") {
      val output = PythonOutput("out", "", 0, 100L)
      assertTrue(output.isSuccess)
    },
    test("isSuccess returns false for exit code 1") {
      val output = PythonOutput("", "error", 1, 100L)
      assertTrue(!output.isSuccess)
    },
    test("isSuccess returns false for negative exit code") {
      val output = PythonOutput("", "", -1, 100L)
      assertTrue(!output.isSuccess)
    },
    test("summary returns stdout for success") {
      val output = PythonOutput("hello world", "", 0, 100L)
      assertTrue(output.summary == "hello world")
    },
    test("summary returns message for success with no output") {
      val output = PythonOutput("", "", 0, 100L)
      assertTrue(output.summary.contains("successfully"))
    },
    test("summary returns error info for failure") {
      val output = PythonOutput("", "syntax error", 1, 100L)
      assertTrue(
        output.summary.contains("Error") &&
        output.summary.contains("syntax error")
      )
    }
  )

  val executionSuite = suite("execution")(
    test("executes simple Python code") {
      val input = PythonInput.unsafe("print('hello')")
      val result = PythonExecutorTool.instance.execute(input)
      assertTrue(result match
        case ToolResult.Success(out) => out.stdout.trim == "hello" && out.isSuccess
        case _ => false
      )
    },
    test("captures stdout") {
      val input = PythonInput.unsafe("print('line1')\nprint('line2')")
      val result = PythonExecutorTool.instance.execute(input)
      assertTrue(result match
        case ToolResult.Success(out) =>
          out.stdout.contains("line1") && out.stdout.contains("line2")
        case _ => false
      )
    },
    test("captures stderr") {
      val input = PythonInput.unsafe("import sys; sys.stderr.write('error msg')")
      val result = PythonExecutorTool.instance.execute(input)
      assertTrue(result match
        case ToolResult.Success(out) => out.stderr.contains("error msg")
        case _ => false
      )
    },
    test("returns Failure for syntax error") {
      val input = PythonInput.unsafe("print(")
      val result = PythonExecutorTool.instance.execute(input)
      assertTrue(result match
        case ToolResult.Failure(_) => true
        case ToolResult.Success(out) => !out.isSuccess
      )
    },
    test("returns result with non-zero exit code for runtime error") {
      val input = PythonInput.unsafe("raise Exception('boom')")
      val result = PythonExecutorTool.instance.execute(input)
      assertTrue(result match
        case ToolResult.Failure(msg) => msg.contains("Exception")
        case ToolResult.Success(out) => out.exitCode != 0
      )
    },
    test("handles computation correctly") {
      val input = PythonInput.unsafe("print(2 + 2)")
      val result = PythonExecutorTool.instance.execute(input)
      assertTrue(result match
        case ToolResult.Success(out) => out.stdout.trim == "4"
        case _ => false
      )
    },
    test("handles multiline code") {
      val code = """
def factorial(n):
    if n <= 1:
        return 1
    return n * factorial(n - 1)

print(factorial(5))
"""
      val input = PythonInput.unsafe(code)
      val result = PythonExecutorTool.instance.execute(input)
      assertTrue(result match
        case ToolResult.Success(out) => out.stdout.trim == "120"
        case _ => false
      )
    },
    test("handles timeout") {
      // Python sleeps for 5s, but timeout is 1s - should fail quickly
      val input = PythonInput.unsafe("import time; time.sleep(5)", timeoutSeconds = 1)
      val result = PythonExecutorTool.instance.execute(input)
      assertTrue(result match
        case ToolResult.Failure(msg) =>
          msg.toLowerCase.contains("timed out") || msg.toLowerCase.contains("timeout")
        case ToolResult.Success(out) =>
          out.exitCode == -1 && out.stderr.toLowerCase.contains("timed out")
      )
    } @@ timeout(10.seconds)
  ) @@ sequential
