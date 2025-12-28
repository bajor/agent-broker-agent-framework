package com.llmagent.examples

import zio.*
import com.llmagent.dsl.*
import com.llmagent.dsl.Types.*
import com.llmagent.examples.Pipeline.{ExplainerTypes, inputQueue, outputQueue}
import com.llmagent.common.{AgentNames, AgentOutput, Config}

/**
 * Explainer Agent Runner
 *
 * Source: CodeGen agent output (AgentOutput)
 * Output: Refiner agent (AgentOutput)
 *
 * Explains code execution results in user-friendly terms.
 */
object ExplainerMain extends ZIOAppDefault:

  private val systemPrompt = """You are a helpful assistant that explains code execution results.
Given the code and its output, provide a clear, concise explanation of what happened.

Focus on:
1. What the code does
2. What the output means
3. Any errors or issues encountered

Keep your explanation brief and user-friendly."""

  /** Process: Generate explanation using LLM */
  val GenerateExplanation: Process[ExplainerTypes.Input, ExplainerTypes.Output] =
    Process.withLlm[ExplainerTypes.Input, ExplainerTypes.Output](
      processName = "GenerateExplanation",
      buildPrompt = (input, _) => s"""$systemPrompt

Here is the code execution result to explain:

${input.summary}

Provide a brief explanation:""",
      parseResponse = (input, response, _) =>
        AgentOutput(
          summary = s"""${input.summary}

## Explanation
$response""",
          toolExecutions = input.toolExecutions + 1,
          totalLatencyMs = input.totalLatencyMs
        ),
      model = Config.Ollama.defaultModel,
      reflections = MaxReflections.default
    )

  val agent: AgentDefinition[ExplainerTypes.Input, ExplainerTypes.Output] =
    Agent(AgentNames.explainer)
      .readFrom(
        inputQueue(AgentNames.explainer),
        ExplainerTypes.decodeInput
      )
      .process(GenerateExplanation)
      .writeTo(
        outputQueue(AgentNames.refiner),
        ExplainerTypes.encodeOutput
      )
      .build

  override def run: ZIO[Any, Throwable, Nothing] =
    AgentRuntime.run(agent)
