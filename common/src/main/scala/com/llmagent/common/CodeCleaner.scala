package com.llmagent.common

/**
 * Utility for cleaning code extracted from LLM responses.
 *
 * LLMs often wrap code in markdown code blocks even when instructed not to.
 * This utility strips those markers while preserving the actual code.
 */
object CodeCleaner:

  private val codeBlockPrefixes = List("```python", "```py", "```")
  private val codeBlockSuffix = "```"

  /**
   * Clean markdown code block markers from a string.
   *
   * Handles responses like:
   * {{{
   * ```python
   * print("hello")
   * ```
   * }}}
   *
   * Returns just the code: `print("hello")`
   */
  def cleanMarkdownCode(response: String): String =
    val trimmed = response.trim
    val withoutPrefix = codeBlockPrefixes.foldLeft(trimmed) { (s, prefix) =>
      if s.startsWith(prefix) then s.stripPrefix(prefix)
      else s
    }
    withoutPrefix.stripSuffix(codeBlockSuffix).trim
