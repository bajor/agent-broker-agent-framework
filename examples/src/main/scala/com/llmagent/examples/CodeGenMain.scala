package com.llmagent.examples

import zio.*
import com.llmagent.dsl.*
import com.llmagent.dsl.Types.*
import com.llmagent.examples.Pipeline.{CodeGenTypes, inputQueue, outputQueue}
import com.llmagent.common.{AgentNames, AgentOutput, CodeCleaner, Config}
import com.llmagent.tools.{LlmTool, PythonExecutorTool, PythonInput, PythonOutput}
import com.llmagent.common.Agent.ToolResult

/**
 * CodeGen Agent Runner
 *
 * Source: Preprocessor agent output (AgentInput)
 * Output: Explainer agent (AgentOutput)
 *
 * Generates Python code from task description and executes it.
 */
object CodeGenMain extends ZIOAppDefault:

  /** Intermediate state for code generation pipeline */
  case class CodeGenState(
    input: CodeGenTypes.Input,
    generatedCode: String = "",
    codeGenLatency: Long = 0L,
    execResult: Option[PythonOutput] = None,
    execLatency: Long = 0L
  )

  private val systemPrompt = """You are a Python code generator. Given a task description, generate clean, working Python code.

IMPORTANT RULES:
1. Output ONLY the Python code, no markdown code blocks
2. Include necessary imports at the top
3. Make the code self-contained and executable
4. Print the result to stdout
5. Handle potential errors gracefully
6. Keep the code simple and focused on the task

Do NOT include:
- Markdown formatting like ```python or ```
- Explanations before or after the code
- Comments asking for user input

Just output the raw Python code that can be executed directly."""

  /** Process: Initialize state from input */
  val InitState: Process[CodeGenTypes.Input, CodeGenState] =
    Process.pure("InitState")(input => CodeGenState(input))

  /** Process: Generate code using LLM */
  val GenerateCode: Process[CodeGenState, CodeGenState] =
    Process.withLlm[CodeGenState, CodeGenState](
      processName = "GenerateCode",
      buildPrompt = (state, _) => s"""$systemPrompt

Task: ${state.input.taskDescription}

Generate the Python code:""",
      parseResponse = (state, response, _) =>
        state.copy(
          generatedCode = CodeCleaner.cleanMarkdownCode(response),
          codeGenLatency = java.lang.System.currentTimeMillis() // Approximation, actual latency from LLM
        ),
      model = Config.Ollama.defaultModel,
      reflections = MaxReflections.default
    )

  /** Process: Execute the generated code using PythonExecutorTool */
  val ExecuteCode: Process[CodeGenState, CodeGenState] =
    Process.withTool[CodeGenState, PythonInput, PythonOutput, CodeGenState](
      processName = "ExecuteCode",
      tool = PythonExecutorTool.instance,
      prepareInput = (state, _) =>
        PythonInput.unsafe(state.generatedCode, timeoutSeconds = Config.Python.executionTimeout.value),
      handleOutput = (state, output, _) =>
        state.copy(execResult = Some(output), execLatency = output.executionTimeMs),
      reflections = MaxReflections.unsafe(1)
    )

  /** Process: Summarize results into AgentOutput */
  val Summarize: Process[CodeGenState, CodeGenTypes.Output] =
    Process.pure("Summarize") { state =>
      val status = state.execResult.map(r => if r.isSuccess then "SUCCESS" else "FAILED").getOrElse("NOT_EXECUTED")
      val output = state.execResult.map(_.stdout).getOrElse("")
      val error = state.execResult.map(_.stderr).filter(_.nonEmpty).getOrElse("")

      AgentOutput(
        summary = s"""Task: ${state.input.taskDescription}

## Generated Code
```python
${state.generatedCode}
```

## Execution Result
Status: $status
${if output.nonEmpty then s"Output:\n$output" else ""}
${if error.nonEmpty then s"Error:\n$error" else ""}

## Timing
- Code generation: ${state.codeGenLatency}ms
- Execution: ${state.execLatency}ms""",
        toolExecutions = if state.execResult.isDefined then 2 else 1,
        totalLatencyMs = state.codeGenLatency + state.execLatency
      )
    }

  /** Complete pipeline: Init >>> Generate >>> Execute >>> Summarize */
  val pipeline: Process[CodeGenTypes.Input, CodeGenTypes.Output] =
    InitState >>> GenerateCode >>> ExecuteCode >>> Summarize

  val agent: AgentDefinition[CodeGenTypes.Input, CodeGenTypes.Output] =
    Agent(AgentNames.codegen)
      .readFrom(
        inputQueue(AgentNames.codegen),
        CodeGenTypes.decodeInput
      )
      .process(pipeline)
      .writeTo(
        outputQueue(AgentNames.explainer),
        CodeGenTypes.encodeOutput
      )
      .build

  override def run: ZIO[Any, Throwable, Nothing] =
    AgentRuntime.run(agent)
