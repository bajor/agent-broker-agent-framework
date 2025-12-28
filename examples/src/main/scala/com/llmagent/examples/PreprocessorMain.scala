package com.llmagent.examples

import zio.*
import com.llmagent.dsl.*
import com.llmagent.dsl.Types.*
import com.llmagent.examples.Pipeline.{PreprocessorTypes, inputQueue, outputQueue}
import com.llmagent.common.{AgentInput, AgentNames}

/**
 * Preprocessor Agent Runner
 *
 * Source: UserSubmit CLI (UserInput)
 * Output: CodeGen agent (AgentInput)
 *
 * Cleans and normalizes user input before passing to code generation.
 */
object PreprocessorMain extends ZIOAppDefault:

  /** Process: Clean and normalize user input */
  val CleanInput: Process[PreprocessorTypes.Input, PreprocessorTypes.Output] =
    Process.pure("CleanInput") { input =>
      val cleaned = input.rawPrompt.trim
        .replaceAll("\\s+", " ")
        .replaceAll("[\\x00-\\x1F]", "")

      AgentInput(
        taskDescription = cleaned,
        context = input.metadata
      )
    }

  val agent: AgentDefinition[PreprocessorTypes.Input, PreprocessorTypes.Output] =
    Agent(AgentNames.preprocessor)
      .readFrom(
        inputQueue(AgentNames.preprocessor),
        PreprocessorTypes.decodeInput
      )
      .process(CleanInput)
      .writeTo(
        outputQueue(AgentNames.codegen),  // Output goes to CodeGen
        PreprocessorTypes.encodeOutput
      )
      .build

  override def run: ZIO[Any, Throwable, Nothing] =
    AgentRuntime.run(agent)
