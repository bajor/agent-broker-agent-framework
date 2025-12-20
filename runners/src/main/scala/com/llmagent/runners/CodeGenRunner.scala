package com.llmagent.runners

import com.llmagent.common.*
import com.llmagent.common.Agent.{AgentId, ToolAgent, Tool, ToolResult, ToolPhaseContext, AgentResult}
import com.llmagent.common.Config as AppConfig
import com.llmagent.common.observability.Types as ObsTypes
import com.llmagent.common.RunnerInfra.{ProcessResult, RunnerConfig}
import com.llmagent.tools.{PythonExecutorTool, PythonInput, PythonOutput, LlmTool}
import zio.*

/** CodeGen Agent - generates Python code and executes it
  * Takes AgentInput, produces AgentOutput with code and execution results
  */
object CodeGenRunner extends ZIOAppDefault:

  private val config = RunnerConfig(
    agentName = AgentNames.codegen,
    inputQueue = s"agent_${AgentNames.codegen}_tasks",
    outputQueue = Some(s"agent_${AgentNames.explainer}_tasks")
  )

  private lazy val agentId: AgentId = AgentId.unsafe(config.agentName)

  private val systemPrompt: String = """You are a Python code generator. Given a task description, generate clean, working Python code.

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

  /** The code generation agent - uses LLM to generate code and executes it */
  private class CodeGenAgent(
    override val id: AgentId,
    conversationId: ObsTypes.ConversationId,
    model: String
  ) extends ToolAgent[AgentInput, AgentOutput]:

    override def tools: List[Tool[?, ?]] = List(LlmTool.instance, PythonExecutorTool.instance)

    private var generatedCode: String = ""
    private var codeGenLatency: Long = 0L
    private var execResult: Option[PythonOutput] = None
    private var execLatency: Long = 0L

    override protected def runToolPhase(input: AgentInput, context: ToolPhaseContext): Unit =
      val prompt = s"""$systemPrompt

Task: ${input.taskDescription}

Generate the Python code:"""

      val llmInput = LlmTool.LlmInput(prompt, model, conversationId)

      executeTool(LlmTool.instance, llmInput, feedback =>
        LlmTool.LlmInput(s"$systemPrompt\n\nPrevious attempt failed: $feedback\n\nTask: ${input.taskDescription}\n\nGenerate corrected code:", model, conversationId),
        context
      ) match
        case ToolResult.Success(output: LlmTool.LlmOutput) =>
          generatedCode = cleanCode(output.response)
          codeGenLatency = output.latencyMs

          val pythonInput = PythonInput.unsafe(generatedCode, timeoutSeconds = 30)
          executeTool(PythonExecutorTool.instance, pythonInput, _ => pythonInput, context) match
            case ToolResult.Success(output: PythonOutput) =>
              execResult = Some(output)
              execLatency = output.executionTimeMs
            case ToolResult.Failure(_) =>
              execResult = None

        case ToolResult.Failure(error) =>
          generatedCode = s"# Failed to generate code: $error"

    override protected def summarize(input: AgentInput, context: ToolPhaseContext): AgentResult[AgentOutput] =
      val status = execResult.map(r => if r.isSuccess then "SUCCESS" else "FAILED").getOrElse("NOT_EXECUTED")
      val output = execResult.map(_.stdout).getOrElse("")
      val error = execResult.map(_.stderr).filter(_.nonEmpty).getOrElse("")

      val summary = s"""Task: ${input.taskDescription}

## Generated Code
```python
$generatedCode
```

## Execution Result
Status: $status
${if output.nonEmpty then s"Output:\n$output" else ""}
${if error.nonEmpty then s"Error:\n$error" else ""}

## Timing
- Code generation: ${codeGenLatency}ms
- Execution: ${execLatency}ms"""

      AgentResult.Completed(AgentOutput(
        summary = summary,
        toolExecutions = context.size,
        totalLatencyMs = codeGenLatency + execLatency
      ))

    private def cleanCode(response: String): String =
      response.trim
        .stripPrefix("```python").stripPrefix("```py").stripPrefix("```")
        .stripSuffix("```").trim

  private def handler(
    envelope: A2AJson.A2AEnvelope,
    convId: ObsTypes.ConversationId
  ): ZIO[Any, Throwable, ProcessResult] =
    ZIO.attempt {
      A2AJson.decodeAgentInputFromEnvelope(envelope) match
        case Some(agentInput) =>
          val agent = new CodeGenAgent(agentId, convId, AppConfig.Ollama.defaultModel)

          agent.process(agentInput) match
            case AgentResult.Completed(output) =>
              val outputEnvelope = A2AJson.createEnvelope(
                from = agentId,
                to = AgentNames.explainer,
                traceId = envelope.traceId,
                conversationId = convId,
                payload = output,
                encoder = A2AJson.encodeAgentOutput,
                payloadType = A2AJson.PayloadTypes.AgentOutput
              )
              ProcessResult.Success(Some(outputEnvelope))

            case AgentResult.Failed(error) =>
              ProcessResult.Failure(s"Code generation failed: $error")

        case None =>
          ProcessResult.Failure("Failed to decode AgentInput from payload")
    }

  override def run: ZIO[Any, Throwable, Nothing] =
    RunnerInfra.runAgent(config, handler)
