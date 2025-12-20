package com.llmagent.examples

import zio.json.*
import com.llmagent.dsl.Types.{SourceQueue, DestQueue}
import com.llmagent.common.{AgentInput, AgentOutput, UserInput, UserOutput}

/**
 * Pipeline definition - agent names (defined once) and type mappings.
 *
 * == Pipeline Flow ==
 *
 * {{{
 *   UserSubmit ─▶ Preprocessor ─▶ CodeGen ─▶ Explainer ─▶ Refiner (terminal)
 * }}}
 */
object Pipeline:

  // ════════════════════════════════════════════════════════════════════════════
  // AGENT NAMES - Defined once, used everywhere
  // ════════════════════════════════════════════════════════════════════════════

  object AgentName:
    val Preprocessor = "preprocessor"
    val CodeGen = "codegen"
    val Explainer = "explainer"
    val Refiner = "refiner"

  // ════════════════════════════════════════════════════════════════════════════
  // TYPE DEFINITIONS - What each agent receives and produces
  // ════════════════════════════════════════════════════════════════════════════

  /** Preprocessor: UserInput -> AgentInput */
  object PreprocessorTypes:
    type Input = UserInput
    type Output = AgentInput
    val decodeInput: String => Either[String, Input] = _.fromJson[UserInput].left.map(_.toString)
    val encodeOutput: Output => String = _.toJson

  /** CodeGen: AgentInput -> AgentOutput (reads from Preprocessor) */
  object CodeGenTypes:
    type Input = PreprocessorTypes.Output  // Explicit: reads from Preprocessor output
    type Output = AgentOutput
    val decodeInput: String => Either[String, Input] = _.fromJson[AgentInput].left.map(_.toString)
    val encodeOutput: Output => String = _.toJson

  /** Explainer: AgentOutput -> AgentOutput (reads from CodeGen) */
  object ExplainerTypes:
    type Input = CodeGenTypes.Output  // Explicit: reads from CodeGen output
    type Output = AgentOutput
    val decodeInput: String => Either[String, Input] = _.fromJson[AgentOutput].left.map(_.toString)
    val encodeOutput: Output => String = _.toJson

  /** Refiner: AgentOutput -> UserOutput (reads from Explainer, terminal) */
  object RefinerTypes:
    type Input = ExplainerTypes.Output  // Explicit: reads from Explainer output
    type Output = UserOutput
    val decodeInput: String => Either[String, Input] = _.fromJson[AgentOutput].left.map(_.toString)
    val encodeOutput: Output => String = _.toJson

  // ════════════════════════════════════════════════════════════════════════════
  // QUEUE HELPERS
  // ════════════════════════════════════════════════════════════════════════════

  def inputQueue(agentName: String): SourceQueue = SourceQueue.fromAgentName(agentName)
  def outputQueue(agentName: String): DestQueue = DestQueue.fromAgentName(agentName)
