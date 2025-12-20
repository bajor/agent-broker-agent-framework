package com.llmagent.examples

import zio.*
import com.llmagent.dsl.*
import com.llmagent.dsl.Types.*
import com.llmagent.examples.Pipeline.{AgentName, RefinerTypes, inputQueue}
import com.llmagent.common.{UserOutput, ExecutionStats}

/**
 * Refiner Agent Runner (Terminal)
 *
 * Source: Explainer agent output (AgentOutput)
 * Output: None (terminal agent - final response to user)
 *
 * Refines and formats the final output for the user.
 */
object RefinerMain extends ZIOAppDefault:

  /** Process: Refine output for final presentation */
  val RefineOutput: Process[RefinerTypes.Input, RefinerTypes.Output] =
    Process.pure("RefineOutput") { input =>
      val refined = input.summary.trim
        .replaceAll("\\n{3,}", "\n\n")

      UserOutput(
        response = refined,
        stats = ExecutionStats(
          totalToolCalls = input.toolExecutions,
          totalLatencyMs = input.totalLatencyMs
        )
      )
    }

  val agent: AgentDefinition[RefinerTypes.Input, RefinerTypes.Output] =
    Agent(AgentName.Refiner)
      .readFrom(
        inputQueue(AgentName.Refiner),
        RefinerTypes.decodeInput
      )
      .process(RefineOutput)
      .terminal(RefinerTypes.encodeOutput)  // Terminal agent - no output queue
      .build

  override def run: ZIO[Any, Throwable, Nothing] =
    AgentRuntime.run(agent)
