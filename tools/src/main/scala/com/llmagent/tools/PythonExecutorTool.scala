package com.llmagent.tools

import com.llmagent.common.Agent.{Tool, ToolResult}
import com.llmagent.common.Types.{NonEmptyString, PositiveInt}
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import scala.util.Try

/** Input for Python execution - uses validated types */
final case class PythonInput private (
  code: NonEmptyString,
  timeoutSeconds: PositiveInt
)

object PythonInput:
  import NonEmptyString.value as codeValue
  import PositiveInt.value as timeoutValue

  private val defaultTimeout: PositiveInt = PositiveInt.unsafe(30)

  /** Create PythonInput with validation */
  def apply(code: String, timeoutSeconds: Int = 30): Option[PythonInput] =
    for
      c <- NonEmptyString(code)
      t <- PositiveInt(timeoutSeconds)
    yield new PythonInput(c, t)

  /** Create PythonInput without validation (for internal use) */
  def unsafe(code: String, timeoutSeconds: Int = 30): PythonInput =
    new PythonInput(NonEmptyString.unsafe(code), PositiveInt.unsafe(timeoutSeconds))

/** Output from Python execution */
final case class PythonOutput(
  stdout: String,
  stderr: String,
  exitCode: Int,
  executionTimeMs: Long
):
  def isSuccess: Boolean = exitCode == 0

  def summary: String =
    if isSuccess then
      if stdout.trim.nonEmpty then stdout.trim
      else "(executed successfully with no output)"
    else
      s"Error (exit code $exitCode):\n${stderr.trim}"

/** Tool that executes Python code safely in a subprocess */
final class PythonExecutorTool extends Tool[PythonInput, PythonOutput]:
  import NonEmptyString.value as codeValue
  import PositiveInt.value as timeoutValue

  override val name: String = "python-executor"

  override def execute(input: PythonInput): ToolResult[PythonOutput] =
    Try {
      val tempFile = Files.createTempFile("agent_script_", ".py")
      try
        Files.writeString(tempFile, input.code.codeValue)
        runPython(tempFile, input.timeoutSeconds.timeoutValue)
      finally
        Files.deleteIfExists(tempFile)
    }.fold(
      err => ToolResult.Failure(s"Failed to execute Python: ${err.getMessage}"),
      output =>
        if output.isSuccess then ToolResult.Success(output)
        else ToolResult.Failure(output.summary)
    )

  private def runPython(scriptPath: Path, timeoutSeconds: Int): PythonOutput =
    val startTime = System.currentTimeMillis()

    val processBuilder = new ProcessBuilder("python3", scriptPath.toString)
    processBuilder.redirectErrorStream(false)

    val process = processBuilder.start()

    val stdoutReader = new BufferedReader(new InputStreamReader(process.getInputStream))
    val stderrReader = new BufferedReader(new InputStreamReader(process.getErrorStream))

    val completed = process.waitFor(timeoutSeconds.toLong, TimeUnit.SECONDS)

    val stdout = readAll(stdoutReader)
    val stderr = readAll(stderrReader)

    stdoutReader.close()
    stderrReader.close()

    val executionTimeMs = System.currentTimeMillis() - startTime

    if !completed then
      process.destroyForcibly()
      PythonOutput(stdout, "Execution timed out", -1, executionTimeMs)
    else
      PythonOutput(stdout, stderr, process.exitValue(), executionTimeMs)

  private def readAll(reader: BufferedReader): String =
    val sb = new StringBuilder
    var line = reader.readLine()
    while line != null do
      if sb.nonEmpty then sb.append("\n")
      sb.append(line)
      line = reader.readLine()
    sb.toString

object PythonExecutorTool:
  val instance: PythonExecutorTool = new PythonExecutorTool
