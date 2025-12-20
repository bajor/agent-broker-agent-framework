package com.llmagent.common

/** Single source of truth for all agent names in the system.
  * Reference these constants throughout the codebase to avoid duplication.
  */
object AgentNames:
  val preprocessor: String = "preprocessor"
  val codegen: String = "codegen"
  val explainer: String = "explainer"
  val refiner: String = "refiner"

  /** All defined agent names for validation */
  val all: Set[String] = Set(preprocessor, codegen, explainer, refiner)

  def isValid(name: String): Boolean = all.contains(name)
